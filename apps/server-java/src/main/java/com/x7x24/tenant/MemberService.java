package com.x7x24.tenant;

import com.x7x24.db.Db;
import com.x7x24.gov.AuditLog;
import com.x7x24.util.Clk;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 多工作区 P5：邀请 / 接受、角色管理、移出成员、转让所有权。
 * 规则：
 * <ul>
 *   <li>只有 owner/admin 能邀请；admin 只能邀请 member/viewer，owner 还能邀请 admin；不能邀请 owner（用转让）。</li>
 *   <li>改角色 / 移出：owner 可以改除自己以外的任何人；admin 只能改 member/viewer，且不能把人提升为 admin。</li>
 *   <li>任何人可以自己退出，owner 除外（先转让）。</li>
 *   <li>转让：只有 owner；对方必须已是成员；原 owner 变为 admin。</li>
 * </ul>
 */
@Service
public class MemberService {
  public static final long INVITE_DAYS = 7;
  private final Db db; private final Clk clk; private final AuditLog audit; private final SecureRandom rng = new SecureRandom();
  public MemberService(Db db, Clk clk, AuditLog audit) { this.db = db; this.clk = clk; this.audit = audit; }

  public record Actor(String userId, String login, String role) {}
  public record Created(String id, String token, String expiresAt) {}

  static String hash(String token) { return com.x7x24.util.U.sha256(token); }
  private static void check(boolean ok, String msg) { if (!ok) throw new WorkspaceService.Forbidden(msg); }
  private static boolean owner(String r) { return "owner".equals(r); }

  public Created invite(String ws, Actor a, String role, String login) {
    check(WorkspaceService.manager(a.role()), "只有所有者 / 管理员可以邀请成员");
    if (!List.of("admin", "member", "viewer").contains(role)) throw new IllegalArgumentException("角色只能是 admin / member / viewer");
    check(owner(a.role()) || !"admin".equals(role), "只有所有者可以邀请管理员");
    String l = login == null || login.isBlank() ? null : login.trim().replaceFirst("^@", "");
    if (l != null && !l.matches("[A-Za-z0-9-]{1,39}")) throw new IllegalArgumentException("GitHub 登录名格式不对");
    byte[] b = new byte[24]; rng.nextBytes(b); String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    String id = "inv_" + Long.toHexString(rng.nextLong() & Long.MAX_VALUE);
    String now = clk.iso(); String exp = Instant.parse(now).plus(INVITE_DAYS, ChronoUnit.DAYS).toString();
    db.update("INSERT INTO workspace_invite(id,workspace_id,token_hash,role,login,created_by,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?)", id, ws, hash(token), role, l, a.userId(), now, exp);
    Tenant.run(ws, () -> audit.sys("WORKSPACE_INVITE_CREATED", a.login(), mapOf("invite", id, "role", role, "login", l), null));
    return new Created(id, token, exp);
  }

  public List<Map<String, Object>> invites(String ws, Actor a) {
    check(WorkspaceService.manager(a.role()), "只有所有者 / 管理员可以查看邀请");
    return db.rows("SELECT i.id,i.role,i.login,i.created_at,i.expires_at,i.accepted_at,i.revoked_at,u.login AS created_by,au.login AS accepted_by FROM workspace_invite i LEFT JOIN app_user u ON u.id=i.created_by LEFT JOIN app_user au ON au.id=i.accepted_by WHERE i.workspace_id=? ORDER BY i.created_at DESC", ws);
  }

  public void revoke(String ws, Actor a, String id) {
    check(WorkspaceService.manager(a.role()), "只有所有者 / 管理员可以撤销邀请");
    int n = db.update("UPDATE workspace_invite SET revoked_at=? WHERE id=? AND workspace_id=? AND accepted_at IS NULL AND revoked_at IS NULL", clk.iso(), id, ws);
    if (n == 0) throw new WorkspaceService.NotMember();
    Tenant.run(ws, () -> audit.sys("WORKSPACE_INVITE_REVOKED", a.login(), Map.of("invite", id), null));
  }

  /** 接受邀请：令牌有效、未过期、未使用、未撤销；指定了登录名时必须匹配。已是成员时不改角色。返回工作区 id。 */
  public synchronized String accept(String userId, String login, String token) {
    if (token == null || token.isBlank()) throw new IllegalArgumentException("缺少邀请令牌");
    var r = db.row("SELECT * FROM workspace_invite WHERE token_hash=?", hash(token.trim()));
    if (r == null || r.get("revoked_at") != null || r.get("accepted_at") != null || Instant.parse(Db.str(r.get("expires_at"))).isBefore(Instant.parse(clk.iso())))
      throw new IllegalArgumentException("邀请无效、已使用、已撤销或已过期");
    String want = Db.str(r.get("login"));
    check(want == null || want.equalsIgnoreCase(login), "这个邀请是发给 @" + want + " 的");
    String ws = Db.str(r.get("workspace_id")); String role = Db.str(r.get("role")); String now = clk.iso();
    db.tx(() -> {
      if (db.count("SELECT COUNT(*) FROM workspace_member WHERE workspace_id=? AND user_id=?", ws, userId) == 0)
        db.update("INSERT INTO workspace_member(workspace_id,user_id,role,created_at) VALUES(?,?,?,?)", ws, userId, role, now);
      db.update("UPDATE workspace_invite SET accepted_by=?, accepted_at=? WHERE id=?", userId, now, r.get("id"));
    });
    Tenant.run(ws, () -> audit.sys("WORKSPACE_MEMBER_JOINED", login, Map.of("invite", r.get("id"), "role", role), null));
    return ws;
  }

  private Map<String, Object> target(String ws, String login) {
    var t = db.row("SELECT m.user_id, m.role, u.login FROM workspace_member m JOIN app_user u ON u.id=m.user_id WHERE m.workspace_id=? AND lower(u.login)=lower(?)", ws, login);
    if (t == null) throw new WorkspaceService.NotMember();
    return t;
  }

  public void setRole(String ws, Actor a, String login, String role) {
    if (!List.of("admin", "member", "viewer").contains(role)) throw new IllegalArgumentException("角色只能是 admin / member / viewer（所有者请用转让）");
    var t = target(ws, login); String cur = Db.str(t.get("role"));
    check(!a.userId().equals(t.get("user_id")), "不能修改自己的角色");
    check(!owner(cur), "不能修改所有者的角色（请用转让）");
    if (!owner(a.role())) check("admin".equals(a.role()) && !"admin".equals(cur) && !"admin".equals(role), "只有所有者可以任免管理员");
    db.update("UPDATE workspace_member SET role=? WHERE workspace_id=? AND user_id=?", role, ws, t.get("user_id"));
    Tenant.run(ws, () -> audit.sys("WORKSPACE_ROLE_CHANGED", a.login(), Map.of("login", t.get("login"), "from", cur, "to", role), null));
  }

  public void remove(String ws, Actor a, String login) {
    var t = target(ws, login); String cur = Db.str(t.get("role")); boolean self = a.userId().equals(t.get("user_id"));
    check(!owner(cur), self ? "所有者不能退出，请先转让所有权" : "不能移出所有者");
    if (!self) { check(WorkspaceService.manager(a.role()), "只有所有者 / 管理员可以移出成员"); if (!owner(a.role())) check(!"admin".equals(cur), "只有所有者可以移出管理员"); }
    db.update("DELETE FROM workspace_member WHERE workspace_id=? AND user_id=?", ws, t.get("user_id"));
    Tenant.run(ws, () -> audit.sys(self ? "WORKSPACE_MEMBER_LEFT" : "WORKSPACE_MEMBER_REMOVED", a.login(), Map.of("login", t.get("login"), "role", cur), null));
  }

  public void transfer(String ws, Actor a, String login) {
    check(owner(a.role()), "只有所有者可以转让");
    var t = target(ws, login); check(!a.userId().equals(t.get("user_id")), "已经是所有者");
    db.tx(() -> {
      db.update("UPDATE workspace_member SET role='admin' WHERE workspace_id=? AND user_id=?", ws, a.userId());
      db.update("UPDATE workspace_member SET role='owner' WHERE workspace_id=? AND user_id=?", ws, t.get("user_id"));
      db.update("UPDATE workspace SET owner_user_id=? WHERE id=?", t.get("user_id"), ws);
    });
    Tenant.run(ws, () -> audit.sys("WORKSPACE_OWNER_TRANSFERRED", a.login(), Map.of("from", a.login(), "to", t.get("login")), null));
  }

  private static Map<String, Object> mapOf(Object... kv) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]); return m; }
}
