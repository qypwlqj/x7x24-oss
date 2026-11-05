package com.x7x24.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.Wiring;
import com.x7x24.db.Db;
import com.x7x24.adapters.DeliveryTools;
import com.x7x24.core.Status;
import com.x7x24.engine.*;
import com.x7x24.gov.*;
import com.x7x24.llm.*;
import com.x7x24.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectsIT extends Harness {
  @LocalServerPort int port;
  @Autowired ProjectRepo projects;
  @Autowired com.x7x24.web.CatalogApi catalog;
  @Autowired Discovery discovery;
  @Autowired StageExecutions stages;
  @Autowired Wiring.Bootstrap bootstrap;
  final HttpClient http = HttpClient.newHttpClient();
  @BeforeEach void defaults() { var g = Governance.defaults(); g.release.observeHours = 0.01; cfg.update(Json.M.valueToTree(g)); }
  JsonNode request(String method, String path, Object body, int expected) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.str(body))).header("content-type", "application/json");
    var result = http.send(builder.build(), HttpResponse.BodyHandlers.ofString()); assertEquals(expected, result.statusCode(), result.body()); return Json.tree(result.body());
  }
  String line() { return lines.create(Json.M.valueToTree(Map.of("name", "实验室产品线 " + U.uuid(), "autonomy", "L2"))).id(); }
  @Test void repositoryInitializationAuthorizationDefaultsOffAndPersistsAcrossUpdates() throws Exception {
    var p = project(line()); assertFalse(p.cfg().autoInitializeRepository);
    var updated = request("PUT", "/api/projects/" + p.id(), Map.of("config", Map.of("autoInitializeRepository", true)), 200);
    assertTrue(updated.path("config").path("autoInitializeRepository").asBoolean());
    assertTrue(projects.require(p.id()).cfg().autoInitializeRepository);
    request("PUT", "/api/projects/" + p.id(), Map.of("config", Map.of("autoInitializeRepository", false)), 200);
    assertFalse(projects.runtime(p.id()).cfg().autoInitializeRepository);
  }
  @Test void onlyManagersCanAuthorizeAutomaticRepositoryInitialization() {
    var p = project(line()); var body = Json.tree("{\"config\":{\"autoInitializeRepository\":true}}");
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    for (String role : List.of("viewer", "member")) {
      request.setAttribute(com.x7x24.tenant.WorkspaceFilter.ATTR, new com.x7x24.tenant.WorkspaceService.Ws(p.workspaceId(), "fixture", "fixture", "team", role, "active"));
      assertThrows(com.x7x24.tenant.WorkspaceService.Forbidden.class, () -> catalog.updateProject(p.id(), body, request));
      assertFalse(projects.require(p.id()).cfg().autoInitializeRepository);
    }
    request.setAttribute(com.x7x24.tenant.WorkspaceFilter.ATTR, new com.x7x24.tenant.WorkspaceService.Ws(p.workspaceId(), "fixture", "fixture", "team", "admin", "active"));
    try { catalog.updateProject(p.id(), body, request); assertTrue(projects.require(p.id()).cfg().autoInitializeRepository); }
    finally { projects.update(p.id(), Json.tree("{\"config\":{\"autoInitializeRepository\":false}}")); }
  }
  ProjectRepo.Project project(String line) { return projects.create(line, Json.M.valueToTree(Map.of("name", "LMS", "config", Map.of("repo", "owner/repo-" + U.uuid())))); }
  String task(ProjectRepo.Project project, String ref) {
    return engine.ingest(new Engine.Ingest(project.productLineId(), "新增 wordCount 工具函数", "示例：wordCount(\"a b\") => 2", "github", ref, null, false, null, project.id(), "wordCount 输入 a b 返回 2")).taskId();
  }
  @Test void nodeCatalogApiReflectsProjectStackProfile() throws Exception {
    var all = request("GET", "/api/pipeline/nodes", null, 200);
    assertTrue(all.path("nodes").size() >= 15); assertTrue(all.path("profile").isMissingNode());
    var p = projects.create(line(), Json.M.valueToTree(Map.of("name", "LMS", "config", Map.of("repo", "owner/lms-" + U.uuid(),
      "modules", List.of(Map.of("name", "api", "path", "backend/", "language", "Go (Gin)"), Map.of("name", "web", "path", "frontend/", "language", "TypeScript (React)")),
      "nodePolicy", Map.of("disabled", List.of("CHECK_I18N"))))));
    var v = request("GET", "/api/pipeline/nodes?project=" + p.id(), null, 200);
    assertEquals("auto", v.path("profile").path("source").asText());
    assertTrue(v.path("profile").path("stacks").toString().contains("go"));
    Map<String, JsonNode> by = new HashMap<>(); v.path("nodes").forEach(n -> by.put(n.path("id").asText(), n));
    assertTrue(by.get("API_DESIGN").path("allowed").asBoolean());
    assertFalse(by.get("CHECK_I18N").path("allowed").asBoolean()); assertEquals("项目已禁用", by.get("CHECK_I18N").path("why").asText());
    assertFalse(by.get("PERF_BUDGET").path("allowed").asBoolean());
    request("GET", "/api/pipeline/nodes?project=nope", null, 400);
    request("PUT", "/api/projects/" + p.id(), Map.of("config", Map.of("nodePolicy", Map.of("disabled", List.of("DEV")))), 400);
    request("PUT", "/api/projects/" + p.id(), Map.of("config", Map.of("stackProfile", Map.of("source", "manual", "modules", List.of(Map.of("path", "x/", "stack", "cobol"))))), 400);
    request("PUT", "/api/projects/" + p.id(), Map.of("config", Map.of("stackProfile", Map.of("source", "manual", "modules", List.of(Map.of("path", "svc/", "stack", "python"))), "nodePolicy", Map.of("disabled", List.of("CHECK_I18N", "SEC_AUDIT")))), 200);
    var after = request("GET", "/api/pipeline/nodes?project=" + p.id(), null, 200);
    assertEquals("manual", after.path("profile").path("source").asText()); assertEquals("[\"python\"]", after.path("profile").path("stacks").toString());
    assertTrue(db.count("SELECT COUNT(*) FROM task_event WHERE event_type='NODE_POLICY_RELAXED'") >= 1);
  }
  @Test void createHierarchyThroughApiAndRejectWrongScope() throws Exception {
    var line = request("POST", "/api/product-lines", Map.of("name", "API 产品线", "description", "业务目标"), 200);
    var a = request("POST", "/api/product-lines/" + line.path("id").asText() + "/projects", Map.of("name", "LMS", "config", Map.of("repo", "https://github.com/owner/api-" + U.uuid())), 200);
    var b = project(line.path("id").asText());
    var created = request("POST", "/api/projects/" + a.path("id").asText() + "/tasks", Map.of("title", "补全实验记录校验", "content", "验证必填字段", "acceptanceCriteria", "空字段至少 1 次被拒绝"), 200);
    var detail = request("GET", "/api/tasks/" + created.path("taskId").asText(), null, 200);
    assertEquals(a.path("id").asText(), detail.path("task").path("projectId").asText()); assertEquals("空字段至少 1 次被拒绝", detail.path("task").path("acceptanceCriteria").asText());
    assertEquals(a.path("config").path("repo").asText(), detail.path("repo").asText());
    assertEquals(0, request("GET", "/api/tasks?projectId=" + b.id() + "&from=2000-01-01&to=2999-12-31", null, 200).size());
    assertEquals(1, request("GET", "/api/board?projectId=" + a.path("id").asText() + "&from=2000-01-01&to=2999-12-31", null, 200).path("totals").path("all").asInt());
    request("GET", "/api/board?projectId=" + b.id() + "&productLine=sandbox", null, 400);
    request("POST", "/api/tasks", Map.of("productLine", line.path("id").asText(), "title", "未选择项目"), 400);
  }
  @Test void sameIssueNumberAndSameTitleInDifferentProjectsDoNotDeduplicate() {
    String line = line(); var a = project(line); var b = project(line);
    String x = task(a, "gh:#23"), y = task(b, "gh:#23"); assertNotEquals(x, y);
    assertEquals(x, task(a, "gh:#23"));
    assertEquals(Status.PLANNING, runTo(x, Status.PLANNING).st()); assertEquals(Status.PLANNING, runTo(y, Status.PLANNING).st());
  }
  @Test void projectPauseAndKillSwitchAreIsolated() {
    String line = line(); var a = project(line); var b = project(line); String x = task(a, "gh:#41"), y = task(b, "gh:#41");
    projects.update(a.id(), Json.tree("{\"state\":\"PAUSED\"}")); engine.tick();
    assertEquals(Status.BACKLOG, repo.get(x).st()); assertNotEquals(Status.BACKLOG, repo.get(y).st());
    projects.update(a.id(), Json.tree("{\"state\":\"ACTIVE\"}")); var sw = kill.activate("project:" + a.id(), "HALT", "manual", "测试", null);
    assertNotNull(kill.isBlocked(line, a.id(), "write", null)); assertNull(kill.isBlocked(line, b.id(), "write", null));
    engine.tick(); assertEquals(Status.BACKLOG, repo.get(x).st()); kill.release(Db.str(sw.get("id")), "test");
    assertNotEquals(Status.BACKLOG, run(x, t -> t.st() != Status.BACKLOG, 2).st());
  }
  @Test void projectBudgetIsChargedOnceAndDoesNotBlockSibling() {
    String line = line(); var a = project(line); var b = project(line); String x = task(a, "gh:#51"), y = task(b, "gh:#51");
    projects.update(a.id(), Json.tree("{\"config\":{\"budget\":{\"dailyUsd\":0.5}}}"));
    assertTrue(budget.charge("project-budget:" + x, x, line, "dev", "mock", 100, 0.5, 1));
    assertFalse(budget.charge("project-budget:" + x, x, line, "dev", "mock", 100, 0.5, 1));
    assertEquals("hard", budget.projectStatus(a.id())); assertEquals("ok", budget.projectStatus(b.id())); assertEquals(0.5, budget.lineDaily(line).usd(), 0.0001);
    engine.tick(); assertEquals(Status.BACKLOG, repo.get(x).st()); assertNotEquals(Status.BACKLOG, repo.get(y).st());
  }
  @Test void concurrentBacklogAdmissionRespectsProjectCapacity() {
    var p = project(line()); task(p, "gh:#61"); task(p, "gh:#62");
    projects.update(p.id(), Json.tree("{\"config\":{\"maxConcurrentTasks\":1}}")); engine.tick();
    assertEquals(1, db.count("SELECT COUNT(*) FROM task WHERE project_id=? AND status='PRD_REVIEW'", p.id()));
    assertEquals(1, db.count("SELECT COUNT(*) FROM task WHERE project_id=? AND status='BACKLOG'", p.id()));
  }
  @Test void configurationSnapshotsAndInheritedRestrictions() {
    String line = line(); var p = project(line); String id = task(p, "gh:#71"); int version = repo.get(id).context.projectConfigVersion;
    projects.update(p.id(), Json.tree("{\"config\":{\"technology\":\"Go 新配置\",\"autonomy\":\"L1\",\"pathRules\":[{\"prefix\":\"src/private/\",\"level\":\"forbidden\"}]}}"));
    var runtime = projects.forTask(repo.get(id)); assertEquals(p.cfg().technology, runtime.cfg().technology); assertEquals("L1", runtime.autonomyLevel());
    assertEquals(version, repo.get(id).context.projectConfigVersion);
    assertNotNull(PathPolicy.checkPath(runtime.cfg(), "src/private/record.ts"));
    assertThrows(IllegalArgumentException.class, () -> projects.update(p.id(), Json.tree("{\"config\":{\"repo\":\"owner/other\"}}")));
    assertThrows(IllegalArgumentException.class, () -> projects.update(p.id(), Json.tree("{\"state\":\"ARCHIVED\"}")));
    assertThrows(IllegalArgumentException.class, () -> lines.update(line, Json.tree("{\"state\":\"ARCHIVED\"}")));
  }
  @Test void archiveEmptyProjectReleasesRepositoryBindingButKeepsHistory() {
    String line = line(); var p = project(line);
    assertThrows(IllegalArgumentException.class, () -> projects.create(line, Json.M.valueToTree(Map.of("name", "重复仓库", "config", Map.of("repo", p.cfg().repo)))));
    projects.update(p.id(), Json.tree("{\"state\":\"ARCHIVED\"}"));
    assertNotNull(projects.get(p.id())); assertNotNull(projects.create(line, Json.M.valueToTree(Map.of("name", "新项目", "config", Map.of("repo", p.cfg().repo)))));
  }
  @Test void bootstrapDoesNotOverwriteSavedNamesAndConfiguration() {
    var oldLine = lines.get("sandbox"); var oldProject = projects.require("sandbox-default");
    try {
      lines.update("sandbox", Json.tree("{\"name\":\"保存的产品线名称\"}"));
      projects.update("sandbox-default", Json.tree("{\"name\":\"保存的项目名称\",\"config\":{\"workflow\":\"custom.yml\"}}"));
      bootstrap.run(null);
      assertEquals("保存的产品线名称", lines.get("sandbox").name()); assertEquals("保存的项目名称", projects.require("sandbox-default").name()); assertEquals("custom.yml", projects.require("sandbox-default").cfg().workflow);
    } finally { lines.update("sandbox", Json.M.valueToTree(Map.of("name", oldLine.name()))); projects.update("sandbox-default", Json.M.valueToTree(Map.of("name", oldProject.name(), "config", oldProject.cfg()))); }
  }
  @Test void stageExecutionHistoryAndCurrentStageSurviveLoopsAndPause() throws Exception {
    var p = project(line()); String id = task(p, "gh:#81"); runTo(id, Status.DEV);
    var records = stages.list(id); assertTrue(records.size() >= 3); assertTrue(records.stream().anyMatch(row -> row.get("stage").equals("PRD_REVIEW")));
    var task = repo.get(id); engine.apply(task, Decision.anomaly("timeout", "测试异常"));
    assertEquals("DEV", repo.get(id).currentStage); assertEquals("BLOCKED", repo.get(id).executionState);
    assertEquals(1,request("GET","/api/tasks?projectId="+p.id()+"&node=dev&from=2000-01-01&to=2999-12-31",null,200).size(),"受阻任务保留原节点");
    assertEquals(0, request("GET", "/api/tasks?projectId=" + p.id() + "&node=anomaly&from=2000-01-01&to=2999-12-31", null, 200).size());
    assertEquals(0, request("GET", "/api/tasks?projectId=" + p.id() + "&status=ANOMALY&from=2000-01-01&to=2999-12-31", null, 200).size());
    request("PUT", "/api/tasks/" + id + "/pause", Map.of("paused", true), 400);
    assertEquals("BLOCKED",request("GET","/api/tasks/"+id,null,200).path("task").path("executionState").asText());
    engine.resumeAnomaly(id, "operator");
    request("PUT", "/api/tasks/" + id + "/pause", Map.of("paused", true), 200);
    assertEquals("PAUSED", request("GET", "/api/tasks/" + id, null, 200).path("task").path("executionState").asText());
  }
  @Test void importedLogsDeduplicateAndPromoteWithinProject() {
    var p = project(line()); String a = discovery.importLog(p.id(), "修复实验记录错误", "2026-10-11T01:00:00Z TypeError: missing record");
    String b = discovery.importLog(p.id(), "修复实验记录错误", "2026-10-11T02:00:00Z TypeError: missing record"); assertEquals(a, b);
    assertEquals(2, discovery.candidates(p.id()).getFirst().get("occurrences"));
    var result = discovery.promote(p.id(), a); assertEquals(p.id(), repo.get(result.taskId()).projectId); assertEquals(result.taskId(), discovery.promote(p.id(), a).taskId());
    assertThrows(IllegalArgumentException.class, () -> discovery.promote(project(line()).id(), a));
  }
  @Test void sourceDiscoveryRejectsFabricatedEvidenceAndChargesProject() {
    var p = project(line()); projects.update(p.id(), Json.tree("{\"config\":{\"allowedPaths\":[\"backend/\"],\"allowedExtensions\":[\"go\"],\"discovery\":{\"testFailures\":false,\"roots\":[\"backend/\"]}}}"));
    String quote = "func divide(a, b int) int { return a / b }";
    tools.main.put("backend/divide.go", quote);
    LlmProvider model = new LlmProvider() {
      public String model() { return "source-test"; }
      public Response chat(Request request) { return new Response(Json.str(Map.of("findings", List.of(Map.of("title", "修复除零异常", "problem", "b=0 时出现 panic", "path", "backend/divide.go", "quote", quote, "acceptanceCriteria", "b=0 返回错误，至少 1 条回归测试通过"), Map.of("title", "不存在的缺陷", "problem", "伪造问题", "path", "backend/missing.go", "quote", "not in repository", "acceptanceCriteria", "测试通过")))), model(), "v1", 42, 0.01); }
    };
    var scanner = new Discovery(db, clk, projects, tools, new Providers("llm", model, model, List.of(model), model), budget, kill, engine, cfg, audit, evidence);
    assertEquals(1, scanner.scan(p.id()).get("candidateCount")); assertEquals(1, scanner.scan(p.id()).get("candidateCount"));
    assertEquals(1, scanner.candidates(p.id()).size()); assertEquals(84, budget.projectDaily(p.id()).tokens());
  }

  @Test void autonomyRaiseAppliesToExistingTasksImmediately() {
    String line = line(); lines.setAutonomy(line, "L3"); var p = project(line); String id = task(p, "gh:#81");
    assertEquals("L2", projects.forTask(repo.get(id)).autonomyLevel());
    projects.update(p.id(), Json.tree("{\"config\":{\"autonomy\":\"L3\"}}"));
    assertEquals("L3", projects.forTask(repo.get(id)).autonomyLevel(), "调高项目自治等级后，已有任务（旧快照）也立即按新等级执行");
  }
  @Test void autonomyDiscardSaysWhichLayerLimits() {
    String line = line(); var p = project(line); projects.update(p.id(), Json.tree("{\"config\":{\"autonomy\":\"L3\"}}"));
    var rt = projects.runtime(p.id()); assertEquals("L2", rt.autonomyLevel());
    var dd = engine.scopeDiscard(rt, "high", Risk.classify(cfg.get(), "数据库 schema 迁移", null));
    @SuppressWarnings("unchecked") var au = (Map<String, Object>) dd.get("autonomy");
    assertEquals("productLine", au.get("limitedBy")); assertEquals("L3", dd.get("requiredAutonomy"));
    String reason = (String) dd.get("reason"), guide = (String) dd.get("guidance");
    assertTrue(reason.contains("高风险") && reason.contains("L2") && reason.contains("产品线"), reason);
    assertTrue(guide.contains("产品线") && guide.contains("L3") && !guide.contains("{need}"), guide);
    db.update("UPDATE project SET config=? WHERE id=?", Json.str(Map.of("repo", p.cfg().repo, "autonomy", "L2")), p.id()); lines.setAutonomy(line, "L3");
    var au2 = projects.autonomyOf(projects.runtime(p.id())); assertEquals("project", au2.get("limitedBy"));
  }
  @Test void chatI18nTaskWithOutOfScopeDatabaseIsNotHighRisk() {
    var p = project(line()); // 产品线和项目都是 L2
    String prd = "## 目标\n为前端新增日语多语言\n\n## 范围\n- 新增 ja 语言包\n\n## 不包含\n- 数据库与后端接口改动\n\n## 验收标准\n1. 切到日语后登录页显示「ログイン」\n";
    String id = engine.ingest(new Engine.Ingest(p.productLineId(), "新增日语多语言", prd, "chat", "chat:ja-" + U.uuid(), null, false, null, p.id(), "1. 切到日语后登录页显示「ログイン」", null, List.of("frontend/src/locales/ja/login.json"))).taskId();
    var t = repo.get(id); assertNotEquals("high", t.riskLevel); assertEquals(List.of("frontend/src/locales/ja/login.json"), t.context.targetPaths);
  }
}
