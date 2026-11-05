package com.x7x24.notification;

import com.x7x24.db.Db;
import com.x7x24.integration.CryptoBox;
import com.x7x24.tenant.WorkspaceService;
import com.x7x24.util.*;
import jakarta.mail.internet.InternetAddress;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

@Service
public class NotificationService {
  public static final Map<String, Boolean> DEFAULT_EVENTS = Map.of("completed", true, "anomaly", true, "action_required", true, "published", false, "recovered", false, "discarded", false);
  private final Db db; private final Clk clk; private final CryptoBox box; private final NotificationMail mail;
  private final SecureRandom random = new SecureRandom();
  public NotificationService(Db db, Clk clk, com.x7x24.integration.CredentialProvider credentials, NotificationMail mail) { this.db = db; this.clk = clk; this.box = credentials.box(); this.mail = mail; }

  public Map<String, Object> preference(String ws, String uid) {
    var p = db.row("SELECT * FROM notification_preference WHERE workspace_id=? AND user_id=?", ws, uid);
    if (p != null) return p;
    return db.tx(() -> {
      lockUser(uid);
      var existing = db.row("SELECT * FROM notification_preference WHERE workspace_id=? AND user_id=?", ws, uid);
      if (existing == null) db.update("INSERT INTO notification_preference(workspace_id,user_id,events,updated_at) VALUES(?,?,?,?)", ws, uid, Json.str(DEFAULT_EVENTS), clk.iso());
      return db.row("SELECT * FROM notification_preference WHERE workspace_id=? AND user_id=?", ws, uid);
    });
  }
  private void lockUser(String uid) { if (db.row("SELECT id FROM app_user WHERE id=? FOR UPDATE", uid) == null) throw new WorkspaceService.Forbidden("请先登录"); }
  public Map<String, Object> address(String uid) { return db.row("SELECT * FROM user_notification_email WHERE user_id=?", uid); }

  public Map<String, Object> settings(String ws, String uid) {
    var p = preference(ws, uid); var a = address(uid);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("email", a == null ? "" : a.get("email")); result.put("verified", a != null && a.get("verified_at") != null);
    result.put("codeExpiresAt", a == null ? null : a.get("code_expires_at")); result.put("mailReady", mail.ready());
    result.put("emailEnabled", Db.i(p.get("email_enabled")) != 0); result.put("inAppEnabled", Db.i(p.get("in_app_enabled")) != 0);
    result.put("scope", p.get("scope")); result.put("projectIds", Json.tree(Db.str(p.get("project_ids")))); result.put("events", events(p));
    return result;
  }
  public static Map<String, Boolean> events(Map<String, Object> p) {
    Map<String, Boolean> result = new LinkedHashMap<>(DEFAULT_EVENTS);
    var n = Json.tree(Db.str(p.get("events"))); DEFAULT_EVENTS.keySet().forEach(k -> { if (n.has(k)) result.put(k, n.path(k).asBoolean()); });
    return result;
  }
  public static boolean includesProject(Map<String, Object> p, String project) {
    if ("workspace".equals(p.get("scope"))) return true;
    var ids = Json.tree(Db.str(p.get("project_ids"))); for (var id : ids) if (id.asText().equals(project)) return true;
    return false;
  }
  public void saveSettings(String ws, String uid, boolean emailEnabled, boolean inAppEnabled, String scope, List<String> ids, Map<String, Boolean> eventSettings) {
    if (scope == null || !List.of("workspace", "projects").contains(scope)) throw new IllegalArgumentException("通知范围不正确");
    var selected = ids == null ? List.<String>of() : ids.stream().distinct().toList();
    if (selected.size() > 100 || "projects".equals(scope) && selected.isEmpty()) throw new IllegalArgumentException("请选择要订阅的项目（最多 100 个）");
    for (String id : selected) if (db.count("SELECT COUNT(*) FROM project WHERE workspace_id=? AND id=?", ws, id) == 0) throw new IllegalArgumentException("所选项目不在当前工作区");
    if (eventSettings == null || !eventSettings.keySet().equals(DEFAULT_EVENTS.keySet()) || eventSettings.values().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("通知事件设置不完整");
    preference(ws, uid);
    db.tx(() -> {
      lockUser(uid); var a = address(uid);
      if (emailEnabled && (a == null || a.get("verified_at") == null)) throw new IllegalArgumentException("请先验证收件邮箱");
      db.update("UPDATE notification_preference SET email_enabled=?,in_app_enabled=?,scope=?,project_ids=?,events=?,updated_at=? WHERE workspace_id=? AND user_id=?", emailEnabled ? 1 : 0, inAppEnabled ? 1 : 0, scope, Json.str("workspace".equals(scope) ? List.of() : selected), Json.str(eventSettings), clk.iso(), ws, uid);
    });
  }
  public void saveEmail(String uid, String value) {
    String email = value == null ? "" : value.trim();
    if (!email.isEmpty()) {
      try {
        var parsed = new InternetAddress(email, true); parsed.validate();
        if (!email.equals(parsed.getAddress()) || parsed.getPersonal() != null || email.length() > 254 || !email.matches("[^\\s<>@,;]+@[^\\s<>@,;]+\\.[^\\s<>@,;]+")) throw new IllegalArgumentException();
      } catch (Exception e) { throw new IllegalArgumentException("请输入有效的收件邮箱地址"); }
    }
    db.tx(() -> {
      lockUser(uid); var a = address(uid);
      if (a != null && email.equals(a.get("email"))) return;
      // Keep resend counters across address changes to prevent verification mail abuse.
      if (a == null) db.update("INSERT INTO user_notification_email(user_id,email,updated_at) VALUES(?,?,?)", uid, email, clk.iso());
      else db.update("UPDATE user_notification_email SET email=?,verified_at=NULL,code_hash=NULL,code_expires_at=NULL,code_attempts=0,updated_at=? WHERE user_id=?", email, clk.iso(), uid);
    });
  }
  private String codeHash(String uid, String email, String code) { return HexFormat.of().formatHex(box.mac("notification-email|" + uid + "|" + email + "|" + code)); }
  public void sendCode(String ws, String uid) {
    if (!mail.ready()) throw new IllegalArgumentException("管理员尚未配置发信服务，请稍后再验证邮箱");
    db.tx(() -> {
      lockUser(uid); var a = address(uid);
      if (a == null || Db.str(a.get("email")).isBlank()) throw new IllegalArgumentException("请先保存收件邮箱");
      if (a.get("verified_at") != null) throw new IllegalArgumentException("这个邮箱已验证");
      long now = clk.now();
      if (a.get("code_sent_at") != null && now - Clk.parse(Db.str(a.get("code_sent_at"))) < 60_000) throw new IllegalArgumentException("验证码已发送，请等待一分钟再重试");
      boolean sameWindow = a.get("code_window_at") != null && now - Clk.parse(Db.str(a.get("code_window_at"))) < U.HOUR;
      int requests = sameWindow ? Db.i(a.get("code_requests")) : 0;
      if (requests >= 5) throw new IllegalArgumentException("验证码发送次数较多，请一小时后重试");
      String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000)), email = Db.str(a.get("email")), hash = codeHash(uid, email, code);
      db.update("UPDATE user_notification_email SET code_hash=?,code_expires_at=?,code_attempts=0,code_sent_at=?,code_window_at=?,code_requests=?,updated_at=? WHERE user_id=?", hash, Clk.iso(now + 10 * U.MIN), clk.iso(), sameWindow ? a.get("code_window_at") : clk.iso(), requests + 1, clk.iso(), uid);
      queue(ws, uid, null, null, null, "verification", "VERIFY", "7x24 · 验证收件邮箱", null, box.encrypt("你的邮箱验证码是：" + code + "\n\n10 分钟内有效。请回到 7x24 通知设置完成验证。"), hash, email, false, "PENDING");
    });
  }
  public void verify(String uid, String code) {
    if (code == null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("请输入 6 位邮箱验证码");
    String error = db.tx(() -> {
      lockUser(uid); var a = address(uid);
      if (a == null || a.get("code_hash") == null || a.get("code_expires_at") == null || Clk.parse(Db.str(a.get("code_expires_at"))) <= clk.now()) return "验证码已过期，请重新发送";
      if (Db.i(a.get("code_attempts")) >= 5) return "验证码尝试次数过多，请重新发送";
      if (!MessageDigest.isEqual(Db.str(a.get("code_hash")).getBytes(StandardCharsets.UTF_8), codeHash(uid, Db.str(a.get("email")), code).getBytes(StandardCharsets.UTF_8))) {
        db.update("UPDATE user_notification_email SET code_attempts=code_attempts+1 WHERE user_id=?", uid); return "验证码不正确";
      }
      db.update("UPDATE user_notification_email SET verified_at=?,code_hash=NULL,code_expires_at=NULL,code_attempts=0,updated_at=? WHERE user_id=?", clk.iso(), clk.iso(), uid);
      return null;
    });
    if (error != null) throw new IllegalArgumentException(error);
  }
  public void testEmail(String ws, String uid) {
    if (!mail.ready()) throw new IllegalArgumentException("管理员尚未配置发信服务");
    db.tx(() -> {
      lockUser(uid); var a = address(uid);
      if (a == null || a.get("verified_at") == null) throw new IllegalArgumentException("请先验证收件邮箱");
      if (db.count("SELECT COUNT(*) FROM notification_delivery WHERE workspace_id=? AND user_id=? AND purpose='TEST' AND created_at>?", ws, uid, Clk.iso(clk.now() - U.MIN)) > 0) throw new IllegalArgumentException("测试邮件已提交，请一分钟后再试");
      queue(ws, uid, null, null, null, "test", "TEST", "7x24 · 测试通知", "这是一封测试邮件。你已成功配置任务通知的收件邮箱。", null, null, Db.str(a.get("email")), false, "PENDING");
    });
  }
  String queue(String ws, String uid, String event, String project, String task, String kind, String purpose, String subject, String message, String cipher, String codeHash, String email, boolean inApp, String state) {
    String id = U.uuid();
    db.update("INSERT INTO notification_delivery(id,event_id,workspace_id,user_id,project_id,task_id,kind,purpose,subject,message,body_cipher,code_hash,email,in_app,state,next_attempt_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, event, ws, uid, project, task, kind, purpose, subject, message, cipher, codeHash, email, inApp ? 1 : 0, state, clk.iso(), clk.iso());
    return id;
  }
  public Map<String, Object> inbox(String ws, String uid, boolean history) {
    preference(ws, uid);
    String extra = history ? "" : " AND in_app=1 AND purpose='TASK'";
    var items = db.rows("SELECT id,task_id,project_id,kind,purpose,subject,message,state,attempts,last_error,created_at,sent_at,read_at FROM notification_delivery WHERE workspace_id=? AND user_id=?" + extra + " ORDER BY created_at DESC,id DESC LIMIT 100", ws, uid);
    return Map.of("items", items, "unread", db.count("SELECT COUNT(*) FROM notification_delivery WHERE workspace_id=? AND user_id=? AND purpose='TASK' AND in_app=1 AND read_at IS NULL", ws, uid));
  }
  public void read(String ws, String uid, String id) {
    if ("all".equals(id)) db.update("UPDATE notification_delivery SET read_at=? WHERE workspace_id=? AND user_id=? AND in_app=1 AND read_at IS NULL", clk.iso(), ws, uid);
    else if (db.update("UPDATE notification_delivery SET read_at=COALESCE(read_at,?) WHERE workspace_id=? AND user_id=? AND id=? AND in_app=1", clk.iso(), ws, uid, id) == 0) throw new WorkspaceService.NotMember();
  }
}
