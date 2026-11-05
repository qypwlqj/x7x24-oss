package com.x7x24.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.auth.AuthService;
import com.x7x24.integration.RuntimeSettings;
import com.x7x24.util.Json;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.MediaType;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@org.springframework.test.context.TestPropertySource(properties = {
    "x7x24.auth.allowed-origins=https://budget.example.invalid",
    "spring.datasource.url=jdbc:h2:mem:budget_api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1"
})
class BudgetApiIT extends Harness {
  @Autowired TestRestTemplate http; @Autowired RuntimeSettings settings; @Autowired com.x7x24.engine.ProjectRepo projects;
  @Autowired AuthService auth; @LocalServerPort int port;
  final java.net.http.HttpClient browser = java.net.http.HttpClient.newHttpClient();
  static final String ORIGIN = "https://budget.example.invalid";
  static final String PROJECT = "sandbox-default";

  @BeforeEach void clearBudget() { db.update("DELETE FROM budget_ledger"); settings.setBudgetOnExceed("degrade"); db.update("UPDATE project SET config=REPLACE(config,'\"budget\"','\"budget\"') WHERE 1=0"); resetOverrides(); }
  @AfterEach void restore() { resetOverrides(); db.update("DELETE FROM budget_ledger"); settings.setBudgetOnExceed("degrade"); cfg.update(Json.M.valueToTree(com.x7x24.gov.Governance.defaults())); for (var k : kill.active()) kill.release((String) k.get("id"), "test"); }
  void resetOverrides() {
    put("project", PROJECT, null, null, null, null); put("line", "sandbox", null, null, null, null);
  }
  JsonNode req(HttpMethod m, String path, Object body) { var r = http.exchange(path, m, body == null ? null : new HttpEntity<>(body), String.class); var n = (com.fasterxml.jackson.databind.node.ObjectNode) Json.tree(r.getBody()); n.put("_status", r.getStatusCode().value()); return n; }
  Map<String, Object> body(String scope, String id, Map<String, Double> v, String policy) { Map<String, Object> b = new HashMap<>(); b.put("scope", scope); if (id != null) b.put("id", id); b.put("values", v); if (policy != null) b.put("onExceed", policy); return b; }
  JsonNode put(String scope, String id, Double usd, Double tokens, Double ci, String policy) {
    Map<String, Double> v = new HashMap<>(); v.put("dailyUsd", usd); v.put("dailyTokens", tokens); v.put("dailyCiMinutes", ci); return req(HttpMethod.PUT, "/api/budget/limits", body(scope, id, v, policy));
  }
  JsonNode preview(String scope, String id, Map<String, Double> v) { return req(HttpMethod.POST, "/api/budget/preview", body(scope, id, v, null)); }

  String newWorkspace() {
    String ws = "ws_budget_" + System.nanoTime();
    db.update("INSERT INTO workspace(id,slug,name,kind,plan_quota,status,created_at) VALUES(?,?,?,'team','{}','active',?)", ws, ws, "Budget test", clk.iso());
    return ws;
  }
  String member(String ws, String role) {
    long githubId = System.nanoTime(); String uid = "u_budget_" + githubId, login = "budget" + githubId;
    db.update("INSERT INTO app_user(id,github_id,login,created_at,last_login_at) VALUES(?,?,?,?,?)", uid, githubId, login, clk.iso(), clk.iso());
    db.update("INSERT INTO workspace_member(workspace_id,user_id,role,created_at) VALUES(?,?,?,?)", ws, uid, role, clk.iso());
    if ("owner".equals(role)) db.update("UPDATE workspace SET owner_user_id=? WHERE id=?", uid, ws);
    return auth.issueSession(new AuthService.User(uid, githubId, login, null, null));
  }
  java.net.http.HttpResponse<String> browserCall(String method, String ws, String token, String path, Object body) throws Exception {
    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
        .header("Origin", ORIGIN).header("X-Workspace", ws).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
        .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(Json.str(body))).build();
    return browser.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
  }
  JsonNode browserOk(String method, String ws, String token, String path, Object body) throws Exception {
    var response = browserCall(method, ws, token, path, body);
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(ORIGIN, response.headers().firstValue("access-control-allow-origin").orElse(""));
    return Json.tree(response.body());
  }
  record OwnedTask(String workspace, String owner, String task) {}
  OwnedTask createTaskAsOwner() throws Exception {
    String ws = newWorkspace(), owner = member(ws, "owner");
    String line = browserOk("POST", ws, owner, "/api/product-lines", Map.of("name", "Budget line")).path("id").asText();
    String project = browserOk("POST", ws, owner, "/api/product-lines/" + line + "/projects", Map.of("name", "Budget project", "config", Map.of("repo", "example-owner/budget-" + System.nanoTime()))).path("id").asText();
    String task = browserOk("POST", ws, owner, "/api/projects/" + project + "/tasks", Map.of("title", "Budget permission regression", "content", "Exercise budget switches after creating a product line and project")).path("taskId").asText();
    assertFalse(task.isBlank()); return new OwnedTask(ws, owner, task);
  }
  @Test void creatorAndAdminCanSaveTaskBudgetWithCorsPersistenceAndAudit() throws Exception {
    var owned = createTaskAsOwner(); String path = "/api/tasks/" + owned.task() + "/budget-switches";
    String admin = member(owned.workspace(), "admin");
    for (String token : List.of(owned.owner(), admin)) {
      boolean unlimited = token.equals(owned.owner());
      var saved = browserOk("PATCH", owned.workspace(), token, path, Map.of("budgetOff", unlimited, "degradeOff", true));
      assertEquals(unlimited, saved.path("budgetSwitches").path("budgetOff").asBoolean());
      var reloaded = browserOk("GET", owned.workspace(), token, "/api/tasks/" + owned.task(), null).path("task").path("budgetSwitches");
      assertEquals(unlimited, reloaded.path("budgetOff").asBoolean()); assertTrue(reloaded.path("degradeOff").asBoolean());
      assertEquals("task", reloaded.path("budgetFrom").asText());
      assertEquals(Boolean.valueOf(unlimited), repo.get(owned.task()).context.budgetOff);
      assertEquals(1, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='TASK_BUDGET_SWITCH' AND actor=?", owned.task(), auth.userFromToken(token).login()));
    }
    var restored = browserOk("PATCH", owned.workspace(), owned.owner(), path, Json.tree("{\"budgetOff\":null,\"degradeOff\":null}"));
    assertEquals("governance", restored.path("budgetSwitches").path("budgetFrom").asText());
    assertNull(repo.get(owned.task()).context.budgetOff); assertNull(repo.get(owned.task()).context.degradeOff);
  }
  @Test void membersAndViewersCannotChangeTaskBudgetOrWriteAudit() throws Exception {
    var owned = createTaskAsOwner(); String before = Json.str(repo.get(owned.task()).context);
    for (String role : List.of("member", "viewer")) {
      var denied = browserCall("PATCH", owned.workspace(), member(owned.workspace(), role), "/api/tasks/" + owned.task() + "/budget-switches", Map.of("budgetOff", true));
      assertEquals(403, denied.statusCode(), denied.body());
      assertEquals("viewer".equals(role) ? "read_only" : "forbidden", Json.tree(denied.body()).path("error").asText());
      assertEquals(ORIGIN, denied.headers().firstValue("access-control-allow-origin").orElse(""));
      assertEquals(before, Json.str(repo.get(owned.task()).context));
      assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='TASK_BUDGET_SWITCH'", owned.task()));
    }
  }
  @Test void managerCannotChangeTaskBudgetAcrossWorkspaces() throws Exception {
    var owned = createTaskAsOwner(); String foreign = newWorkspace(), foreignOwner = member(foreign, "owner");
    String path = "/api/tasks/" + owned.task() + "/budget-switches", before = Json.str(repo.get(owned.task()).context);
    var foreignScope = browserCall("PATCH", foreign, foreignOwner, path, Map.of("budgetOff", true));
    assertTrue(Set.of(400, 404).contains(foreignScope.statusCode()), foreignScope.body());
    var outsider = browserCall("PATCH", owned.workspace(), foreignOwner, path, Map.of("budgetOff", true));
    assertEquals(404, outsider.statusCode(), outsider.body());
    assertEquals(before, Json.str(repo.get(owned.task()).context));
    assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='TASK_BUDGET_SWITCH'", owned.task()));
  }

  @Test void taskSwitchDefaultsPerProjectArePreservedByLimitEditsAndAudited() {
    var r = req(HttpMethod.PUT, "/api/budget/task-switches", Map.of("scope", "project", "id", PROJECT, "unlimited", true, "degrade", false));
    assertEquals(200, r.path("_status").asInt(), r.toString());
    var pc = projects.require(PROJECT).cfg().budget; assertEquals(Boolean.TRUE, pc.taskUnlimited); assertEquals(Boolean.FALSE, pc.taskDegrade);
    put("project", PROJECT, 5.0, null, null, null);
    pc = projects.require(PROJECT).cfg().budget; assertEquals(Boolean.TRUE, pc.taskUnlimited, "改日额度不会清掉任务预算开关"); assertEquals(5.0, pc.dailyUsd);
    assertTrue(db.count("SELECT COUNT(*) FROM task_event WHERE event_type='BUDGET_UPDATED' AND payload LIKE '%task_switches%'") >= 1);
    req(HttpMethod.PUT, "/api/budget/task-switches", Map.of("scope", "project", "id", PROJECT, "clearUnlimited", true, "clearDegrade", true));
    pc = projects.require(PROJECT).cfg().budget; assertNull(pc.taskUnlimited); assertNull(pc.taskDegrade);
    assertEquals(400, req(HttpMethod.PUT, "/api/budget/task-switches", Map.of("scope", "line", "id", "sandbox")).path("_status").asInt());
  }
  @Test void summarySentenceHasPercentAndOmitsProjectionWithoutEnoughData() {
    budget.chargeProject("b1", null, "sandbox", PROJECT, "dev", "m", 1000, 50, 1);
    var s = req(HttpMethod.GET, "/api/budget/summary", null);
    assertEquals(10, Math.round(s.path("usedPct").asDouble() * 100)); assertTrue(s.path("text").asText().contains("10%")); assertTrue(s.path("projectedExhaustAt").isNull(), "不足 3 笔记账 / 不足 30 分钟：不显示预计时间");
  }
  @Test void projectionAppearsWhenSpendRateWouldExhaustBudgetBeforeMidnight() {
    long start = clk.now() - 2 * 3_600_000L; // 2 小时前开始
    for (int i = 0; i < 4; i++) db.update("INSERT INTO budget_ledger (task_id,product_line,project_id,agent,model,tokens,usd,ci_minutes,ledger_key,day,occurred_at,workspace_id) VALUES (NULL,'sandbox',?,?,?,?,?,?,?,?,?,'ws_legacy')", PROJECT, "dev", "m", 10, 100.0, 0.0, "p" + i, clk.dayKey(), com.x7x24.util.Clk.iso(start + i * 60_000L));
    var s = req(HttpMethod.GET, "/api/budget/summary", null);
    double hours = 2.0, rate = 400 / hours, left = (500 - 400) / rate; boolean sameDay = clk.now() + left * 3_600_000 < Long.parseLong(String.valueOf(com.x7x24.util.Clk.parse(clk.dayKey() + "T00:00:00.000Z") + 24L * 3_600_000));
    if (sameDay) assertFalse(s.path("projectedExhaustAt").isNull(), s.toString()); else assertTrue(s.path("projectionNote").asText().contains("今天不会用完"));
  }
  @Test void childCannotExceedParentAndParentCannotDropBelowChildren() {
    var over = put("line", "sandbox", 600.0, null, null, null); assertEquals(400, over.path("_status").asInt()); assertTrue(over.path("error").asText().contains("全局上限"));
    assertEquals(200, put("line", "sandbox", 40.0, null, null, null).path("_status").asInt());
    var low = preview("global", null, Map.of("dailyUsd", 10.0)); assertFalse(low.path("ok").asBoolean()); assertTrue(low.path("errors").toString().contains("产品线"));
    assertEquals(400, put("global", null, 10.0, null, null, null).path("_status").asInt());
    var proj = put("project", PROJECT, 80.0, null, null, null); assertEquals(400, proj.path("_status").asInt()); assertTrue(proj.path("error").asText().contains("产品线"), proj.toString());
    assertEquals(400, req(HttpMethod.POST, "/api/budget/preview", body("task", null, Map.of("usd", 9999.0), null)).path("ok").asBoolean() ? 200 : 400);
    assertEquals(400, put("line", "sandbox", -1.0, null, null, null).path("_status").asInt());
  }
  @Test void savingGlobalLineProjectAndTaskLimitsPersistsTakesEffectAndAudits() {
    long n = db.count("SELECT COUNT(*) FROM task_event WHERE event_type='BUDGET_UPDATED'");
    Map<String, Double> g = new HashMap<>(); g.put("dailyUsd", 400.0); g.put("monthlyUsd", 8000.0);
    assertEquals(200, req(HttpMethod.PUT, "/api/budget/limits", body("global", null, g, null)).path("_status").asInt());
    assertEquals(400.0, cfg.get().budgets.global.dailyUsd); assertEquals(8000.0, cfg.get().budgets.global.monthlyUsd);
    assertEquals("400.0", db.row("SELECT value FROM settings WHERE skey='governance'").get("value").toString().replaceAll(".*\"global\":\\{\"dailyUsd\":(\\d+\\.\\d+).*", "$1"), "持久化到 settings 表");
    assertEquals(200, put("line", "sandbox", 30.0, null, 100.0, null).path("_status").asInt());
    assertEquals(30.0, budget.limits(lines.require("sandbox").cfg().budget).dailyUsd); assertEquals(100.0, budget.limits(lines.require("sandbox").cfg().budget).dailyCiMinutes);
    assertEquals(200, put("project", PROJECT, 20.0, null, null, null).path("_status").asInt());
    assertEquals(20.0, Json.tree((String) db.row("SELECT config FROM project WHERE id=?", PROJECT).get("config")).path("budget").path("dailyUsd").asDouble());
    assertEquals(200, req(HttpMethod.PUT, "/api/budget/limits", body("task", null, Map.of("usd", 5.0, "iterations", 40.0), null)).path("_status").asInt());
    assertEquals(5.0, cfg.get().budgets.task.usd); assertEquals(40.0, cfg.get().budgets.task.iterations);
    assertEquals(n + 4, db.count("SELECT COUNT(*) FROM task_event WHERE event_type='BUDGET_UPDATED'"));
    assertTrue(Json.tree(http.getForObject("/api/budget/changes", String.class)).size() >= 4); assertEquals("task", Json.tree(http.getForObject("/api/budget/changes", String.class)).get(0).path("payload").path("scope").asText());
  }
  @Test void changeHistoryShowsWhoWhenWhatChanged() {
    put("line", "sandbox", 33.0, null, null, null);
    var list = http.getForObject("/api/budget/changes", String.class); var arr = Json.tree(list);
    assertEquals("operator", arr.get(0).path("actor").asText()); assertEquals("line", arr.get(0).path("payload").path("scope").asText()); assertEquals(33.0, arr.get(0).path("payload").path("after").path("dailyUsd").asDouble());
    assertTrue(arr.get(0).path("payload").path("by").asText().contains("无登录")); assertFalse(arr.get(0).path("at").asText().isEmpty());
  }
  @Test void previewShowsImpactOnCurrentUsageBeforeSaving() {
    budget.chargeProject("i1", null, "sandbox", PROJECT, "dev", "m", 1, 40, 0); // 默认产品线日上限 50 → 已用 80%
    var soft = preview("line", "sandbox", Map.of("dailyUsd", 45.0)); assertTrue(soft.path("impact").get(0).path("text").asText().contains("进入降级"), soft.toString()); assertEquals("soft", soft.path("impact").get(0).path("status").asText());
    var hard = preview("line", "sandbox", Map.of("dailyUsd", 30.0)); assertEquals("hard", hard.path("impact").get(0).path("status").asText()); assertTrue(hard.path("impact").get(0).path("text").asText().contains("触顶"));
    var ok = preview("line", "sandbox", Map.of("dailyUsd", 200.0)); assertEquals("ok", ok.path("impact").get(0).path("status").asText());
    assertEquals(50.0, budget.limits(lines.require("sandbox").cfg().budget).dailyUsd, "预览不改变任何数据");
    assertTrue(preview("task", null, Map.of("usd", 2.0)).path("impact").get(0).path("text").asText().contains("只影响保存之后创建的任务"));
  }
  @Test void exceedBehaviourOptionsAreValidatedPersistedAndEnforced() {
    assertEquals(400, req(HttpMethod.PUT, "/api/budget/limits", body("policy", null, Map.of(), "explode")).path("_status").asInt());
    assertEquals(200, req(HttpMethod.PUT, "/api/budget/limits", body("policy", null, Map.of(), "pause_new")).path("_status").asInt());
    assertEquals("pause_new", db.row("SELECT value FROM settings WHERE skey='budget_on_exceed'").get("value"));
    budget.chargeProject("e1", null, "sandbox", PROJECT, "dev", "m", 1, 40, 0); // 项目与产品线都到 80% 降级线
    assertThrows(IllegalArgumentException.class, () -> budget.assertAdmission(PROJECT), "暂停新任务：进入降级线后拒绝新任务");
    var r = req(HttpMethod.POST, "/api/projects/" + PROJECT + "/tasks", Map.of("title", "新的需求", "content", "x")); assertEquals(400, r.path("_status").asInt()); assertTrue(r.path("error").asText().contains("预算"));
    settings.setBudgetOnExceed("degrade"); assertDoesNotThrow(() -> budget.assertAdmission(PROJECT));
    var snap = req(HttpMethod.GET, "/api/budget", null); assertEquals("degrade", snap.path("policy").asText());
  }
  @Test void haltPolicyMakesGlobalHitFreezeEverythingNotJustWrites() {
    settings.setBudgetOnExceed("halt");
    var d = cfg.get(); db.update("DELETE FROM budget_ledger");
    var upd = new com.x7x24.gov.Governance.GlobalBudget(); upd.dailyUsd = 50; upd.dailyTokens = d.budgets.global.dailyTokens; upd.dailyCiMinutes = d.budgets.global.dailyCiMinutes; upd.monthlyUsd = d.budgets.global.monthlyUsd;
    cfg.update(Json.M.createObjectNode().set("budgets", Json.M.createObjectNode().set("global", Json.M.valueToTree(upd))));
    budget.chargeProject("h1", null, "sandbox", PROJECT, "dev", "m", 1, 60, 0); budget.enforceGlobal();
    assertTrue(kill.active().stream().anyMatch(k -> "HALT".equals(k.get("mode")) && "global".equals(k.get("scope"))));
  }
  @Test void topSpendingTasksOfProjectAreSortedByCost() {
    String a = ingestIssue("预算排序 A 需求", "内容 a"), b = ingestIssue("预算排序 B 需求", "内容 b");
    db.update("UPDATE task SET spent_usd=0.5 WHERE id=?", a); db.update("UPDATE task SET spent_usd=2.5 WHERE id=?", b);
    var rows = Json.tree(http.getForObject("/api/budget/projects/" + PROJECT + "/tasks", String.class));
    assertEquals(b, rows.get(0).path("id").asText()); double prev = Double.MAX_VALUE; for (var r : rows) { assertTrue(r.path("spent_usd").asDouble() <= prev); prev = r.path("spent_usd").asDouble(); }
  }
}
