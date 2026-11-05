package com.x7x24.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.auth.AuthService;
import com.x7x24.util.Json;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.MediaType;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 多工作区 P5：邀请 / 接受、角色管理、移出 / 退出、转让所有权，以及跨工作区不能越权。 */
class WorkspaceMembersIT extends Harness {
  @Autowired TestRestTemplate http; @Autowired AuthService auth;
  record U(String id, String login, String token) {}

  String newWs() { String id = "ws_m" + System.nanoTime(); db.update("INSERT INTO workspace(id,slug,name,kind,plan_quota,status,created_at) VALUES(?,?,?,?,?,?,?)", id, id.replace('_', '-'), id, "team", "{}", "active", clk.iso()); return id; }
  U user() { long g = System.nanoTime(); String uid = "u_m" + g, login = "m" + g; db.update("INSERT INTO app_user(id,github_id,login,created_at,last_login_at) VALUES(?,?,?,?,?)", uid, g, login, clk.iso(), clk.iso()); return new U(uid, login, auth.issueSession(new AuthService.User(uid, g, login, null, null))); }
  U member(String ws, String role) { var u = user(); db.update("INSERT INTO workspace_member(workspace_id,user_id,role,created_at) VALUES(?,?,?,?)", ws, u.id, role, clk.iso()); return u; }
  JsonNode call(String ws, U u, HttpMethod m, String path, Object body) {
    var h = new HttpHeaders(); if (ws != null) h.set("X-Workspace", ws); if (u != null) h.setBearerAuth(u.token); h.setContentType(MediaType.APPLICATION_JSON);
    var res = http.exchange(path, m, new HttpEntity<>(body, h), String.class);
    var o = Json.M.createObjectNode(); o.set("body", res.getBody() == null || res.getBody().isBlank() ? Json.M.createObjectNode() : Json.tree(res.getBody())); o.put("_status", res.getStatusCode().value()); o.put("_raw", String.valueOf(res.getBody())); return o;
  }
  int st(JsonNode n) { return n.path("_status").asInt(); }
  String role(String ws, U u) { var r = db.row("SELECT role FROM workspace_member WHERE workspace_id=? AND user_id=?", ws, u.id); return r == null ? null : (String) r.get("role"); }

  @Test void inviteAcceptFlow() {
    String ws = newWs(); U owner = member(ws, "owner"); U joiner = user();
    var inv = call(ws, owner, HttpMethod.POST, "/api/workspace/invites", Map.of("role", "member"));
    assertEquals(200, st(inv), inv.toString()); String token = inv.path("body").path("token").asText();
    assertFalse(call(ws, owner, HttpMethod.GET, "/api/workspace/invites", null).path("_raw").asText().contains(token), "列表不返回明文令牌");
    // 新用户（只有个人工作区）接受
    var acc = call(null, joiner, HttpMethod.POST, "/api/me/invites/accept", Map.of("token", token));
    assertEquals(200, st(acc), acc.toString()); assertEquals(ws, acc.path("body").path("workspace").asText());
    assertEquals("member", role(ws, joiner));
    assertEquals(200, st(call(ws, joiner, HttpMethod.GET, "/api/workspace/members", null)));
    // 令牌只能用一次
    assertEquals(400, st(call(null, user(), HttpMethod.POST, "/api/me/invites/accept", Map.of("token", token))));
    assertEquals(400, st(call(null, joiner, HttpMethod.POST, "/api/me/invites/accept", Map.of("token", "nope"))));
  }

  @Test void inviteBoundToLoginRevokeAndExpiry() {
    String ws = newWs(); U owner = member(ws, "owner"); U a = user(), b = user();
    String t1 = call(ws, owner, HttpMethod.POST, "/api/workspace/invites", Map.of("role", "viewer", "login", a.login)).path("body").path("token").asText();
    assertEquals(403, st(call(null, b, HttpMethod.POST, "/api/me/invites/accept", Map.of("token", t1))), "指定登录名的邀请别人不能用");
    assertEquals(200, st(call(null, a, HttpMethod.POST, "/api/me/invites/accept", Map.of("token", t1)))); assertEquals("viewer", role(ws, a));
    var inv2 = call(ws, owner, HttpMethod.POST, "/api/workspace/invites", Map.of("role", "member")).path("body");
    assertEquals(200, st(call(ws, owner, HttpMethod.DELETE, "/api/workspace/invites/" + inv2.path("id").asText(), null)));
    assertEquals(400, st(call(null, b, HttpMethod.POST, "/api/me/invites/accept", Map.of("token", inv2.path("token").asText()))), "撤销后不能用");
    String t3 = call(ws, owner, HttpMethod.POST, "/api/workspace/invites", Map.of("role", "member")).path("body").path("token").asText();
    clk.advance(8L * 24 * 3600_000);
    assertEquals(400, st(call(null, b, HttpMethod.POST, "/api/me/invites/accept", Map.of("token", t3))), "7 天后过期");
  }

  @Test void whoCanInvite() {
    String ws = newWs(); U owner = member(ws, "owner"); U admin = member(ws, "admin"); U mem = member(ws, "member"); U viewer = member(ws, "viewer");
    assertEquals(403, st(call(ws, mem, HttpMethod.POST, "/api/workspace/invites", Map.of("role", "member"))));
    assertEquals(403, st(call(ws, viewer, HttpMethod.POST, "/api/workspace/invites", Map.of("role", "member"))));
    assertEquals(403, st(call(ws, mem, HttpMethod.GET, "/api/workspace/invites", null)));
    assertEquals(403, st(call(ws, admin, HttpMethod.POST, "/api/workspace/invites", Map.of("role", "admin"))), "admin 不能邀请 admin");
    assertEquals(200, st(call(ws, admin, HttpMethod.POST, "/api/workspace/invites", Map.of("role", "member"))));
    assertEquals(400, st(call(ws, owner, HttpMethod.POST, "/api/workspace/invites", Map.of("role", "owner"))));
    assertEquals(200, st(call(ws, owner, HttpMethod.POST, "/api/workspace/invites", Map.of("role", "admin"))));
  }

  @Test void roleManagement() {
    String ws = newWs(); U owner = member(ws, "owner"); U admin = member(ws, "admin"); U admin2 = member(ws, "admin"); U mem = member(ws, "member");
    assertEquals(200, st(call(ws, admin, HttpMethod.PUT, "/api/workspace/members/" + mem.login, Map.of("role", "viewer")))); assertEquals("viewer", role(ws, mem));
    assertEquals(403, st(call(ws, admin, HttpMethod.PUT, "/api/workspace/members/" + mem.login, Map.of("role", "admin"))), "admin 不能提拔 admin");
    assertEquals(403, st(call(ws, admin, HttpMethod.PUT, "/api/workspace/members/" + admin2.login, Map.of("role", "member"))), "admin 不能降级 admin");
    assertEquals(403, st(call(ws, admin, HttpMethod.PUT, "/api/workspace/members/" + owner.login, Map.of("role", "member"))));
    assertEquals(403, st(call(ws, owner, HttpMethod.PUT, "/api/workspace/members/" + owner.login, Map.of("role", "admin"))), "不能改自己");
    assertEquals(403, st(call(ws, mem, HttpMethod.PUT, "/api/workspace/members/" + admin.login, Map.of("role", "viewer"))), "viewer 不能写");
    assertEquals(200, st(call(ws, owner, HttpMethod.PUT, "/api/workspace/members/" + admin2.login, Map.of("role", "member")))); assertEquals("member", role(ws, admin2));
    assertEquals(400, st(call(ws, owner, HttpMethod.PUT, "/api/workspace/members/" + mem.login, Map.of("role", "owner"))));
    // 移出 / 退出
    assertEquals(403, st(call(ws, admin2, HttpMethod.DELETE, "/api/workspace/members/" + mem.login, null)), "member 不能移出别人");
    assertEquals(200, st(call(ws, admin, HttpMethod.DELETE, "/api/workspace/members/" + mem.login, null))); assertNull(role(ws, mem));
    assertEquals(404, st(call(ws, mem, HttpMethod.GET, "/api/workspace/members", null)), "被移出后立即失去访问");
    assertEquals(403, st(call(ws, owner, HttpMethod.POST, "/api/me/leave", null)), "所有者不能直接退出");
    assertEquals(200, st(call(ws, admin2, HttpMethod.POST, "/api/me/leave", null))); assertNull(role(ws, admin2));
  }

  @Test void transferOwnership() {
    String ws = newWs(); U owner = member(ws, "owner"); U admin = member(ws, "admin"); U outsider = user();
    assertEquals(403, st(call(ws, admin, HttpMethod.POST, "/api/workspace/transfer", Map.of("login", admin.login))));
    assertEquals(404, st(call(ws, owner, HttpMethod.POST, "/api/workspace/transfer", Map.of("login", outsider.login))), "对方必须是成员");
    assertEquals(200, st(call(ws, owner, HttpMethod.POST, "/api/workspace/transfer", Map.of("login", admin.login))));
    assertEquals("owner", role(ws, admin)); assertEquals("admin", role(ws, owner));
    assertEquals(admin.id, db.row("SELECT owner_user_id FROM workspace WHERE id=?", ws).get("owner_user_id"));
    assertEquals(1, db.count("SELECT COUNT(*) FROM workspace_member WHERE workspace_id=? AND role='owner'", ws));
  }

  @Test void cannotManageOtherWorkspace() {
    String a = newWs(), b = newWs(); U ownerA = member(a, "owner"); U ownerB = member(b, "owner"); U memA = member(a, "member");
    // B 的 owner 带 A 的请求头 → 不是成员 404；带 B 的请求头操作 A 的成员 → 找不到
    assertEquals(404, st(call(a, ownerB, HttpMethod.POST, "/api/workspace/invites", Map.of("role", "member"))));
    assertEquals(404, st(call(b, ownerB, HttpMethod.DELETE, "/api/workspace/members/" + memA.login, null)));
    assertEquals(404, st(call(b, ownerB, HttpMethod.PUT, "/api/workspace/members/" + memA.login, Map.of("role", "viewer"))));
    assertEquals(404, st(call(b, ownerB, HttpMethod.POST, "/api/workspace/transfer", Map.of("login", ownerA.login))));
    var invA = call(a, ownerA, HttpMethod.POST, "/api/workspace/invites", Map.of("role", "member")).path("body");
    assertEquals(404, st(call(b, ownerB, HttpMethod.DELETE, "/api/workspace/invites/" + invA.path("id").asText(), null)), "不能撤销别的工作区的邀请");
    assertFalse(call(b, ownerB, HttpMethod.GET, "/api/workspace/invites", null).path("_raw").asText().contains(invA.path("id").asText()));
    assertEquals("member", role(a, memA));
  }
}
