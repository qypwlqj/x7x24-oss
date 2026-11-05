package com.x7x24.tenant;

import com.x7x24.auth.AuthFilter;
import com.x7x24.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 当前用户的工作区列表与当前工作区 */
@RestController
@RequestMapping("/api")
public class WorkspaceApi {
  private final WorkspaceService ws; private final com.x7x24.pipeline.PipelineService pipes; private final com.x7x24.db.Db db; private final com.x7x24.gov.AuditLog audit; private final MemberService members;
  public WorkspaceApi(WorkspaceService ws, com.x7x24.pipeline.PipelineService pipes, com.x7x24.db.Db db, com.x7x24.gov.AuditLog audit, MemberService members) { this.ws = ws; this.pipes = pipes; this.db = db; this.audit = audit; this.members = members; }

  /** 成员管理需要真实登录用户（开发模式的匿名 operator 不能邀请 / 改角色） */
  MemberService.Actor actor(HttpServletRequest r) {
    var cur = WorkspaceFilter.of(r); if (cur == null) throw new WorkspaceService.NotMember();
    if (!(r.getAttribute(AuthFilter.ATTR) instanceof AuthService.User u)) throw new WorkspaceService.Forbidden("需要登录");
    return new MemberService.Actor(u.id(), u.login(), cur.role());
  }
  static String cur(HttpServletRequest r) { return WorkspaceFilter.of(r).id(); }

  // ---- P5：邀请 / 角色 / 移出 / 转让 ----
  @GetMapping("/workspace/invites") public List<Map<String, Object>> invites(HttpServletRequest r) { var a = actor(r); return members.invites(cur(r), a); }
  @PostMapping("/workspace/invites") public Map<String, Object> invite(@RequestBody Map<String, Object> body, HttpServletRequest r) {
    var a = actor(r); var c = members.invite(cur(r), a, String.valueOf(body.getOrDefault("role", "member")), body.get("login") == null ? null : String.valueOf(body.get("login")));
    return Map.of("id", c.id(), "token", c.token(), "expiresAt", c.expiresAt());
  }
  @DeleteMapping("/workspace/invites/{id}") public Map<String, Object> revoke(@PathVariable String id, HttpServletRequest r) { members.revoke(cur(r), actor(r), id); return Map.of("ok", true); }
  @PutMapping("/workspace/members/{login}") public Map<String, Object> role(@PathVariable String login, @RequestBody Map<String, Object> body, HttpServletRequest r) { members.setRole(cur(r), actor(r), login, String.valueOf(body.get("role"))); return Map.of("ok", true); }
  @DeleteMapping("/workspace/members/{login}") public Map<String, Object> remove(@PathVariable String login, HttpServletRequest r) { members.remove(cur(r), actor(r), login); return Map.of("ok", true); }
  @PostMapping("/workspace/transfer") public Map<String, Object> transfer(@RequestBody Map<String, Object> body, HttpServletRequest r) { members.transfer(cur(r), actor(r), String.valueOf(body.get("login"))); return Map.of("ok", true); }
  /** 接受邀请 / 退出工作区放在 /api/me/ 下：只读成员（viewer）也能做 */
  @PostMapping("/me/invites/accept") public Map<String, Object> accept(@RequestBody Map<String, Object> body, HttpServletRequest r) {
    var a = actor(r); String id = members.accept(a.userId(), a.login(), String.valueOf(body.get("token")));
    var w = ws.get(id); return Map.of("workspace", id, "slug", w.slug(), "name", w.name());
  }
  @PostMapping("/me/leave") public Map<String, Object> leave(HttpServletRequest r) { var a = actor(r); members.remove(cur(r), a, a.login()); return Map.of("ok", true); }
  static String login(HttpServletRequest r) { return r.getAttribute(AuthFilter.ATTR) instanceof AuthService.User u ? u.login() : "operator"; }
  void platformAdmin(HttpServletRequest r) { if (!pipes.isAdmin(login(r))) throw new WorkspaceService.Forbidden("只有平台管理员可以管理工作区配额"); }
  /** 平台：工作区列表与配额（平台管理员）。不返回工作区内的业务数据，只有计数。 */
  @GetMapping("/platform/workspaces") public List<Map<String, Object>> all(HttpServletRequest r) {
    platformAdmin(r);
    return Tenant.run(Tenant.PLATFORM, () -> db.rows("SELECT w.id,w.slug,w.name,w.kind,w.status,w.created_at,w.plan_quota,u.login AS owner FROM workspace w LEFT JOIN app_user u ON u.id=w.owner_user_id ORDER BY w.created_at,w.id").stream().map(x -> {
      Map<String, Object> m = new LinkedHashMap<>(x); m.put("quota", ws.quota((String) x.get("id"))); m.remove("plan_quota");
      m.put("members", db.count("SELECT COUNT(*) FROM workspace_member WHERE workspace_id=?", x.get("id"))); m.put("projects", db.count("SELECT COUNT(*) FROM project WHERE workspace_id=?", x.get("id"))); m.put("tasks", db.count("SELECT COUNT(*) FROM task WHERE workspace_id=?", x.get("id"))); return m; }).toList());
  }
  @PutMapping("/platform/workspaces/{id}/quota") public Map<String, Object> quota(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    platformAdmin(r); if (!ws.exists(id)) throw new WorkspaceService.NotMember();
    var q = ws.quota(id); for (String k : List.of("llmFallback", "llmFallbackUsdMonth", "maxConcurrentTasks")) if (body.containsKey(k)) { if (body.get(k) == null) q.remove(k); else q.put(k, body.get(k)); }
    if (q.get("llmFallbackUsdMonth") instanceof Number n && n.doubleValue() < 0) throw new IllegalArgumentException("额度不能为负");
    ws.putQuota(id, q); Tenant.run(Tenant.PLATFORM, () -> audit.sys("WORKSPACE_QUOTA_UPDATED", login(r), Map.of("workspace", id, "quota", q), null));
    return Map.of("id", id, "quota", q);
  }
  @GetMapping("/me/workspaces") public Map<String, Object> mine(HttpServletRequest r) {
    var cur = WorkspaceFilter.of(r); var u = r.getAttribute(AuthFilter.ATTR) instanceof AuthService.User x ? x : null;
    List<Map<String, Object>> list = u == null ? (cur == null ? List.of() : List.of(cur.toMap())) : ws.memberships(u.id()).stream().map(WorkspaceService.Ws::toMap).toList();
    return Map.of("current", cur == null ? Map.of() : cur.toMap(), "workspaces", list, "platformAdmin", pipes.isAdmin(login(r)));
  }
  /** 当前工作区成员（只读；任何成员可见）。只返回登录名 / 头像 / 角色，不返回邮箱等信息。 */
  @GetMapping("/workspace/members") public List<Map<String, Object>> members(HttpServletRequest r) {
    var cur = WorkspaceFilter.of(r); if (cur == null) throw new WorkspaceService.NotMember();
    return db.rows("SELECT u.login, u.name, u.avatar_url, m.role, m.created_at FROM workspace_member m JOIN app_user u ON u.id=m.user_id WHERE m.workspace_id=? ORDER BY m.created_at, u.login", cur.id());
  }
  @GetMapping("/workspace") public Map<String, Object> current(HttpServletRequest r) { var cur = WorkspaceFilter.of(r); if (cur == null) throw new WorkspaceService.NotMember(); return cur.toMap(); }
}
