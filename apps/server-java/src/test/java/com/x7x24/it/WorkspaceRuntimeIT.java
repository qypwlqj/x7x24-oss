package com.x7x24.it;

import com.fasterxml.jackson.databind.JsonNode;
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

/** 多工作区 P3：审计、预算、熔断开关、调用日志、看板与调度按工作区隔离。 */
class WorkspaceRuntimeIT extends Harness {
  static final String B = "ws_rt_b";
  @Autowired TestRestTemplate http; @Autowired WorkspaceService workspaces; @Autowired RequestMappingHandlerMapping mappings;

  @BeforeEach void ensureB() { if (!workspaces.exists(B)) db.update("INSERT INTO workspace(id,slug,name,kind,plan_quota,status,created_at) VALUES(?,?,?,?,?,?,?)", B, "rt-b", "B", "personal", "{}", "active", clk.iso()); }
  JsonNode call(String ws, HttpMethod m, String path, Object body) {
    var h = new HttpHeaders(); if (ws != null) h.set("X-Workspace", ws); h.setContentType(MediaType.APPLICATION_JSON);
    var res = http.exchange(path, m, new HttpEntity<>(body, h), String.class);
    var o = Json.M.createObjectNode(); o.put("_status", res.getStatusCode().value()); o.put("_raw", String.valueOf(res.getBody())); o.set("body", res.getBody() == null || res.getBody().isBlank() ? Json.M.createObjectNode() : Json.tree(res.getBody())); return o;
  }
  String taskInA(String marker) {
    var r = call(null, HttpMethod.POST, "/api/projects/sandbox-default/tasks", Map.of("title", marker, "content", "secret " + marker));
    assertEquals(200, r.path("_status").asInt(), r.toString()); return r.path("body").path("taskId").asText();
  }

  /** 枚举所有不带路径变量的 GET 端点：以 B 身份访问，响应里不得出现 A 的任务标题 / 项目 id / 产品线 id */
  @Test void noListEndpointLeaksAnotherWorkspace() {
    String marker = "RT-SECRET-" + System.nanoTime(); String tid = taskInA(marker);
    budget.charge("rt:" + tid, tid, "sandbox", "dev", "m-" + marker, 10, 0.5, 0);
    audit.sys("TEST_EVENT", "operator", Map.of("marker", marker), null); // 系统上下文 → 平台事件，工作区不可见
    Tenant.run(Tenant.LEGACY, () -> kill.activate("project:sandbox-default", "FREEZE_RELEASE", "manual", "A " + marker, null));
    List<String> leaked = new ArrayList<>(), checked = new ArrayList<>();
    mappings.getHandlerMethods().forEach((info, hm) -> {
      if (!info.getMethodsCondition().getMethods().isEmpty() && !info.getMethodsCondition().getMethods().contains(RequestMethod.GET)) return;
      for (String p : info.getPatternValues()) {
        if (!p.startsWith("/api/") || p.contains("{") || p.startsWith("/api/auth/") || p.equals("/api/events") || p.startsWith("/api/platform/")) continue;
        var r = call(B, HttpMethod.GET, p, null); checked.add(p);
        String raw = r.path("_raw").asText();
        if (raw.contains(marker) || raw.contains(tid) || raw.contains("sandbox-default")) leaked.add(p + " → " + r.path("_status").asInt());
      }
    });
    assertTrue(checked.size() > 20, "枚举到的端点：" + checked);
    assertEquals(List.of(), leaked);
    for (var k : Tenant.run(Tenant.LEGACY, () -> kill.active())) if (String.valueOf(k.get("reason")).contains(marker)) kill.release((String) k.get("id"), "test");
  }

  @Test void killSwitchesAreScopedToWorkspace() {
    var r = call(B, HttpMethod.POST, "/api/kill-switch", Map.of("mode", "HALT", "reason", "B halts itself"));
    assertEquals(200, r.path("_status").asInt(), r.toString()); String bid = r.path("body").path("id").asText();
    assertEquals(B, db.row("SELECT workspace_id FROM kill_switch WHERE id=?", bid).get("workspace_id"));
    assertNull(Tenant.run(Tenant.LEGACY, () -> kill.isBlocked("sandbox", "sandbox-default", "write", null)), "B 的全局开关不影响 A");
    assertNotNull(Tenant.run(B, () -> kill.isBlocked("x", "y", "write", null)));
    assertFalse(call(null, HttpMethod.GET, "/api/kill-switch", null).path("_raw").asText().contains(bid));
    assertNotEquals(200, call(B, HttpMethod.POST, "/api/kill-switch", Map.of("mode", "HALT", "scope", "project:sandbox-default")).path("_status").asInt(), "不能对别人的项目开开关");
    var a = Tenant.run(Tenant.LEGACY, () -> kill.activate("line:sandbox", "READ_ONLY", "manual", "A", null)); String aid = (String) a.get("id");
    assertEquals(404, call(B, HttpMethod.DELETE, "/api/kill-switch/" + aid, null).path("_status").asInt(), "B 不能解除 A 的开关");
    kill.release(aid, "test"); kill.release(bid, "test");
    // 平台级开关（系统上下文）作用于所有工作区
    var p = Tenant.run(Tenant.PLATFORM, () -> kill.activate("global", "READ_ONLY", "auto", "platform", "test:platform"));
    assertNotNull(Tenant.run(B, () -> kill.isBlocked("x", "y", "write", null)));
    assertEquals(404, call(B, HttpMethod.DELETE, "/api/kill-switch/" + p.get("id"), null).path("_status").asInt(), "工作区不能解除平台级开关");
    kill.release((String) p.get("id"), "test");
  }

  @Test void budgetsAndAuditAreScopedToWorkspace() {
    String tid = taskInA("budget-" + System.nanoTime());
    double before = Tenant.run(B, () -> budget.globalDaily().usd());
    budget.charge("rtb:" + tid, tid, "sandbox", "dev", "m", 100, 7.5, 0);
    assertEquals(before, Tenant.run(B, () -> budget.globalDaily().usd()), "A 的花费不计入 B");
    assertTrue(Tenant.run(Tenant.LEGACY, () -> budget.globalDaily().usd()) >= 7.5);
    assertEquals(Tenant.LEGACY, db.row("SELECT workspace_id FROM budget_ledger WHERE ledger_key=?", "rtb:" + tid).get("workspace_id"));
    assertEquals(Tenant.LEGACY, db.row("SELECT workspace_id FROM task_event WHERE task_id=? ORDER BY id LIMIT 1", tid).get("workspace_id"));
    assertEquals(0, call(B, HttpMethod.GET, "/api/audit?taskId=" + tid, null).path("body").path("items").size());
    assertEquals(0, Tenant.run(B, () -> audit.list(null, null, 500, 0)).stream().filter(e -> tid.equals(e.get("task_id"))).count());
  }

  @Test void schedulerInterleavesWorkspacesAndHonoursQuota() {
    List<com.x7x24.engine.Task> due = new ArrayList<>();
    for (int i = 0; i < 4; i++) { var t = new com.x7x24.engine.Task(); t.id = "a" + i; t.workspaceId = "ws_a"; due.add(t); }
    for (int i = 0; i < 2; i++) { var t = new com.x7x24.engine.Task(); t.id = "b" + i; t.workspaceId = "ws_b"; due.add(t); }
    var out = engine.fairForTest(due);
    assertEquals(List.of("a0", "b0", "a1", "b1", "a2", "a3"), out.stream().map(t -> t.id).toList(), "按工作区轮转，A 不能把 B 饿死");
    workspaces.putQuota(B, new LinkedHashMap<>(Map.of("maxConcurrentTasks", 1)));
    List<com.x7x24.engine.Task> d2 = new ArrayList<>();
    for (int i = 0; i < 3; i++) { var t = new com.x7x24.engine.Task(); t.id = "q" + i; t.workspaceId = B; d2.add(t); }
    assertEquals(1, engine.fairForTest(d2).size(), "配额限制每轮并发");
    workspaces.putQuota(B, new LinkedHashMap<>());
  }
}
