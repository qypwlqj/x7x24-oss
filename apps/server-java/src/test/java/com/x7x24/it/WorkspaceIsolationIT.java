package com.x7x24.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.engine.ProjectRepo;
import com.x7x24.tenant.Tenant;
import com.x7x24.tenant.WorkspaceService;
import com.x7x24.util.Json;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 多工作区隔离：B 工作区看不到、改不了 A（遗留工作区）的产品线 / 项目 / 任务；按 id 访问一律 404/400，不泄露内容。 */
class WorkspaceIsolationIT extends Harness {
  static final String B = "ws_test_b";
  @Autowired TestRestTemplate http; @Autowired WorkspaceService workspaces; @Autowired ProjectRepo projects;
  @Autowired RequestMappingHandlerMapping mappings;

  @BeforeEach void ensureB() {
    if (!workspaces.exists(B)) db.update("INSERT INTO workspace(id,slug,name,kind,plan_quota,status,created_at) VALUES(?,?,?,?,?,?,?)", B, "test-b", "B", "personal", "{}", "active", clk.iso());
  }
  JsonNode call(String ws, HttpMethod m, String path, Object body) {
    var h = new HttpHeaders(); if (ws != null) h.set("X-Workspace", ws); h.setContentType(MediaType.APPLICATION_JSON);
    var res = http.exchange(path, m, new HttpEntity<>(body, h), String.class);
    var n = res.getBody() == null || res.getBody().isBlank() ? Json.M.createObjectNode() : Json.tree(res.getBody());
    var o = Json.M.createObjectNode(); o.set("body", n); o.put("_status", res.getStatusCode().value()); o.put("_raw", res.getBody() == null ? "" : res.getBody()); return o;
  }
  String taskInA(String marker) {
    var r = call(null, HttpMethod.POST, "/api/projects/sandbox-default/tasks", Map.of("title", marker, "content", "secret " + marker));
    assertEquals(200, r.path("_status").asInt(), r.toString()); return r.path("body").path("taskId").asText();
  }

  @Test void existingDataBelongsToLegacyWorkspace() {
    assertEquals(Tenant.LEGACY, db.row("SELECT workspace_id FROM product_line WHERE id='sandbox'").get("workspace_id"), "种子产品线归遗留工作区");
    assertEquals(Tenant.LEGACY, db.row("SELECT workspace_id FROM project WHERE id='sandbox-default'").get("workspace_id"));
    assertEquals(0, db.count("SELECT COUNT(*) FROM product_line WHERE workspace_id IS NULL"));
    String id = taskInA("legacy-" + System.nanoTime());
    assertEquals(Tenant.LEGACY, db.row("SELECT workspace_id FROM task WHERE id=?", id).get("workspace_id"));
    assertEquals(Tenant.LEGACY, db.row("SELECT workspace_id FROM signal WHERE task_id=?", id).get("workspace_id"));
    // 无请求头（单用户开发模式）= 遗留工作区，现有页面行为不变
    assertTrue(call(null, HttpMethod.GET, "/api/tasks", null).path("_raw").asText().contains(id));
  }

  @Test void otherWorkspaceCannotListOrReadOrModify() {
    String marker = "A-SECRET-" + System.nanoTime(); String tid = taskInA(marker);
    for (String p : List.of("/api/product-lines", "/api/projects", "/api/tasks", "/api/board"))
      assertFalse(call(B, HttpMethod.GET, p, null).path("_raw").asText().contains(marker) || call(B, HttpMethod.GET, p, null).path("_raw").asText().contains("sandbox-default"), "B 列表泄露：" + p);
    assertNotEquals(200, call(B, HttpMethod.GET, "/api/tasks/" + tid, null).path("_status").asInt());
    assertNotEquals(200, call(B, HttpMethod.GET, "/api/product-lines/sandbox", null).path("_status").asInt());
    assertNotEquals(200, call(B, HttpMethod.GET, "/api/projects/sandbox-default", null).path("_status").asInt());
    // 写操作
    assertNotEquals(200, call(B, HttpMethod.PUT, "/api/tasks/" + tid + "/pause", Map.of("paused", true)).path("_status").asInt());
    assertNotEquals(200, call(B, HttpMethod.POST, "/api/projects/sandbox-default/tasks", Map.of("title", "intrude")).path("_status").asInt());
    assertNotEquals(200, call(B, HttpMethod.PUT, "/api/product-lines/sandbox", Map.of("name", "hacked")).path("_status").asInt());
    assertNotEquals(200, call(B, HttpMethod.POST, "/api/product-lines/sandbox/projects", Map.of("name", "intrude")).path("_status").asInt());
    assertNotEquals(200, call(B, HttpMethod.DELETE, "/api/tasks/" + tid, null).path("_status").asInt());
    assertEquals(0, repo.get(tid).paused); assertNotEquals("hacked", lines.get("sandbox").name()); assertNull(repo.get(tid).deletedAt);
  }

  /** 枚举所有带路径变量的 GET 端点，用 A 的 id 以 B 身份访问：不得返回 200 且内容不得含 A 的标记 */
  @Test void everyIdEndpointRejectsForeignIds() {
    String marker = "A-ENUM-" + System.nanoTime(); String tid = taskInA(marker);
    var ev = db.row("SELECT id FROM evidence WHERE task_id=?", tid); String evId = ev == null ? "none" : (String) ev.get("id");
    Map<String, String> ids = Map.of("id", tid, "projectId", "sandbox-default", "lineId", "sandbox", "taskId", tid);
    List<String> leaked = new ArrayList<>();
    mappings.getHandlerMethods().forEach((info, hm) -> {
      if (!info.getMethodsCondition().getMethods().contains(RequestMethod.GET)) return;
      for (String pattern : info.getPatternValues()) {
        if (!pattern.startsWith("/api/") || !pattern.contains("{") || pattern.contains("/auth/")) continue;
        String path = pattern;
        if (pattern.startsWith("/api/evidence/")) path = "/api/evidence/" + evId;
        else if (pattern.startsWith("/api/product-lines/{id}")) path = pattern.replace("{id}", "sandbox");
        else if (pattern.startsWith("/api/projects/{id}") || pattern.startsWith("/api/budget/projects/{id}")) path = pattern.replace("{id}", "sandbox-default");
        for (var e : ids.entrySet()) path = path.replace("{" + e.getKey() + "}", e.getValue());
        if (path.contains("{")) continue;
        var r = call(B, HttpMethod.GET, path, null);
        if (r.path("_status").asInt() == 200 && (r.path("_raw").asText().contains(marker) || r.path("_raw").asText().contains("sandbox-default"))) leaked.add(path);
      }
    });
    assertEquals(List.of(), leaked);
  }

  @Test void workspaceCreatedInBIsInvisibleToA() {
    var r = call(B, HttpMethod.POST, "/api/product-lines", Map.of("name", "B 线"));
    assertEquals(200, r.path("_status").asInt(), r.toString()); String lid = r.path("body").path("id").asText();
    assertEquals(B, db.row("SELECT workspace_id FROM product_line WHERE id=?", lid).get("workspace_id"));
    var p = call(B, HttpMethod.POST, "/api/product-lines/" + lid + "/projects", Map.of("name", "B 项目", "config", Map.of("repo", "b-owner/b-repo-" + System.nanoTime()))); assertEquals(200, p.path("_status").asInt(), p.toString());
    assertEquals(B, db.row("SELECT workspace_id FROM project WHERE id=?", p.path("body").path("id").asText()).get("workspace_id"));
    assertFalse(call(null, HttpMethod.GET, "/api/product-lines", null).path("_raw").asText().contains(lid));
    assertTrue(call(B, HttpMethod.GET, "/api/product-lines", null).path("_raw").asText().contains(lid));
  }

  @Test void unknownWorkspaceHeaderIs404AndFailsClosed() {
    assertEquals(404, call("ws_nope", HttpMethod.GET, "/api/tasks", null).path("_status").asInt());
    // 后台线程（无请求）为系统模式；请求线程缺上下文时抛异常（这里直接验证系统模式不过滤）
    assertNull(Tenant.current()); assertTrue(projects.list().size() >= 1);
    assertEquals(0, Tenant.run(B, () -> projects.list().stream().filter(x -> x.id().equals("sandbox-default")).count()));
  }

  @Test void personalWorkspaceAutoCreatedAndLegacyClaimedByOwner() {
    long g = System.nanoTime();
    db.update("INSERT INTO app_user(id,github_id,login,created_at,last_login_at) VALUES(?,?,?,?,?)", "u_lt" + g, g, "synthetic-new-user-" + g, clk.iso(), clk.iso());
    var ms = workspaces.ensure("u_lt" + g, "synthetic-new-user-" + g);
    assertEquals(1, ms.size()); assertEquals("personal", ms.getFirst().kind()); assertEquals("owner", ms.getFirst().role()); assertNotEquals(Tenant.LEGACY, ms.getFirst().id());
    assertEquals(ms, workspaces.ensure("u_lt" + g, "synthetic-new-user-" + g), "幂等");
    assertThrows(WorkspaceService.NotMember.class, () -> workspaces.pick(ms, Tenant.LEGACY), "不能通过请求头进入别人的工作区");
    // Empty production owner configuration must not grant the former author any legacy data.
    var neutral = new WorkspaceService(db, clk, audit, "");
    String old = "former-author-" + g;
    db.update("INSERT INTO app_user(id,github_id,login,created_at,last_login_at) VALUES(?,?,?,?,?)", "u_old" + g, g + 1, old, clk.iso(), clk.iso());
    assertNotEquals(Tenant.LEGACY, neutral.ensure("u_old" + g, old).getFirst().id());
    assertEquals(0, db.count("SELECT COUNT(*) FROM workspace_member WHERE workspace_id=? AND role='owner'", Tenant.LEGACY));
    String owner = "explicit-operator-" + g;
    db.update("INSERT INTO app_user(id,github_id,login,created_at,last_login_at) VALUES(?,?,?,?,?)", "u_owner" + g, g + 2, owner, clk.iso(), clk.iso());
    var configured = new WorkspaceService(db, clk, audit, owner);
    var claimed = configured.ensure("u_owner" + g, owner);
    assertEquals(Tenant.LEGACY, claimed.getFirst().id()); assertEquals("owner", claimed.getFirst().role());

  }

  @Test void membersAreScopedToCurrentWorkspace() {
    if (db.count("SELECT COUNT(*) FROM app_user WHERE id='u_mem_a'") == 0) {
      db.update("INSERT INTO app_user(id,github_id,login,name,avatar_url,created_at,last_login_at) VALUES('u_mem_a',990001,'member-a',null,null,?,?)", clk.iso(), clk.iso());
      db.update("INSERT INTO workspace_member(workspace_id,user_id,role,created_at) VALUES(?,?,?,?)", Tenant.LEGACY, "u_mem_a", "member", clk.iso());
    }
    assertTrue(call(null, HttpMethod.GET, "/api/workspace/members", null).path("_raw").asText().contains("member-a"));
    var b = call(B, HttpMethod.GET, "/api/workspace/members", null);
    assertEquals(200, b.path("_status").asInt()); assertFalse(b.path("_raw").asText().contains("member-a"), "B 不应看到 A 的成员");
    assertTrue(call(null, HttpMethod.GET, "/api/me/workspaces", null).path("body").has("platformAdmin"));
  }
}
