package com.x7x24.web;

import com.x7x24.db.Db;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 首页等待名单：不需要登录；只收邮箱；去重（不暴露是否已存在）；按来源 IP 与全局限流；蜜罐字段防机器人。不发送任何邮件。 */
@RestController
@RequestMapping("/api")
public class WaitlistApi {
  static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24}$");
  static final int PER_IP = 5, GLOBAL = 300; static final long WINDOW_MS = 10 * 60_000L;
  private final Db db; private final com.x7x24.util.Clk clk; private final com.x7x24.pipeline.PipelineService pipes;
  final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>(); final Deque<Long> all = new ArrayDeque<>();
  public java.util.function.LongSupplier now = System::currentTimeMillis;
  public WaitlistApi(Db db, com.x7x24.util.Clk clk, com.x7x24.pipeline.PipelineService pipes) { this.db = db; this.clk = clk; this.pipes = pipes; }

  static final Map<String, Object> OK = Map.of("ok", true, "message", "已加入等待名单。开通后我们会通过这个邮箱联系你。");

  @PostMapping("/public/waitlist") public ResponseEntity<?> join(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest r) {
    if (body == null) body = Map.of();
    if (!String.valueOf(body.getOrDefault("website", "")).isBlank()) return ResponseEntity.ok(OK); // 蜜罐：机器人填了隐藏字段，假装成功
    if (!allow(clientIp(r))) return ResponseEntity.status(429).body(Map.of("error", "rate_limited", "message", "提交太频繁了，请稍后再试"));
    String email = String.valueOf(body.getOrDefault("email", "")).trim().toLowerCase(Locale.ROOT);
    if (email.length() > 254 || !EMAIL.matcher(email).matches()) return ResponseEntity.badRequest().body(Map.of("error", "invalid_email", "field", "email", "message", "请填写有效的邮箱地址"));
    if (!Boolean.TRUE.equals(body.get("consent"))) return ResponseEntity.badRequest().body(Map.of("error", "consent_required", "field", "consent", "message", "请先阅读并同意隐私政策"));
    String src = String.valueOf(body.getOrDefault("source", "home")).replaceAll("[^a-z0-9_-]", ""); if (src.isEmpty() || src.length() > 40) src = "home";
    if (db.count("SELECT COUNT(*) FROM waitlist WHERE email=?", email) == 0) {
      try { String t = clk.iso(); db.update("INSERT INTO waitlist(id,email,source,consent_at,created_at) VALUES(?,?,?,?,?)", "wl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), email, src, t, t); }
      catch (org.springframework.dao.DuplicateKeyException ignored) { /* 并发重复提交 */ }
    }
    return ResponseEntity.ok(OK); // 已存在也返回同样的结果，不泄露名单
  }

  synchronized boolean allow(String ip) {
    long t = now.getAsLong(), cut = t - WINDOW_MS;
    while (!all.isEmpty() && all.peekFirst() < cut) all.pollFirst();
    var q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>()); while (!q.isEmpty() && q.peekFirst() < cut) q.pollFirst();
    if (q.size() >= PER_IP || all.size() >= GLOBAL) return false;
    q.addLast(t); all.addLast(t);
    if (hits.size() > 10_000) hits.entrySet().removeIf(e -> e.getValue().isEmpty() || e.getValue().peekLast() < cut);
    return true;
  }
  /** 反向代理（nginx）用 X-Real-IP 传真实地址；客户端自带的 X-Forwarded-For 可伪造，取最后一跳；全局上限兜底 */
  static String clientIp(HttpServletRequest r) {
    String x = r.getHeader("X-Real-IP"); if (x != null && !x.isBlank()) return x.trim();
    String f = r.getHeader("X-Forwarded-For"); if (f != null && !f.isBlank()) { var a = f.split(","); return a[a.length - 1].trim(); }
    return String.valueOf(r.getRemoteAddr());
  }
  void admin(HttpServletRequest r) {
    String login = r.getAttribute(com.x7x24.auth.AuthFilter.ATTR) instanceof com.x7x24.auth.AuthService.User u ? u.login() : null;
    if (!pipes.isAdmin(login)) throw new com.x7x24.tenant.WorkspaceService.Forbidden("只有平台管理员可以查看等待名单");
  }
  @GetMapping("/platform/waitlist") public Map<String, Object> list(HttpServletRequest r) {
    admin(r); return Map.of("total", db.count("SELECT COUNT(*) FROM waitlist"), "items", db.rows("SELECT email, source, created_at FROM waitlist ORDER BY created_at DESC LIMIT 500"));
  }
  @GetMapping(value = "/platform/waitlist.csv", produces = "text/csv;charset=UTF-8") public ResponseEntity<String> csv(HttpServletRequest r) {
    admin(r); var sb = new StringBuilder("\uFEFFemail,source,created_at\n");
    for (var row : db.rows("SELECT email, source, created_at FROM waitlist ORDER BY created_at")) sb.append(cell(row.get("email"))).append(',').append(cell(row.get("source"))).append(',').append(cell(row.get("created_at"))).append('\n');
    return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"waitlist.csv\"").body(sb.toString());
  }
  /** CSV 单元格：转义引号，并防公式注入 */
  static String cell(Object v) { String s = v == null ? "" : String.valueOf(v); if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s; return "\"" + s.replace("\"", "\"\"") + "\""; }
}
