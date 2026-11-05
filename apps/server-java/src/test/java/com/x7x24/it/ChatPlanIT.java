package com.x7x24.it;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.x7x24.chat.ChatService;
import com.x7x24.chat.PlanService;
import com.x7x24.engine.ProjectRepo;
import com.x7x24.pipeline.PipelineService;
import com.x7x24.pipeline.Plans;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.Json;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** C2 任务级流程计划：治理校验（收紧 / 放松 / 拒绝）、规则分类、确认时钉快照、放松项审批、重新钉快照保留计划、动态工作流编排与自动切换。 */
class ChatPlanIT extends Harness {
  @Autowired ChatService chat; @Autowired PipelineService pipes; @Autowired ProjectRepo projects; @Autowired PlanService plans; @Autowired TestRestTemplate http;

  String line() { return projects.require("sandbox-default").productLineId(); }
  static Plans.Adj a(String op, String stage, String check, String param, Double value) { var x = new Plans.Adj(); x.op = op; x.stage = stage; x.check = check; x.param = param; x.value = value; return x; }
  Plans.Result apply(Plans.Adj... adjs) { return Tenant.run(Tenant.LEGACY, () -> pipes.previewPlan(line(), List.of(adjs), null)); }
  Plans.Adj only(Plans.Result r) { assertEquals(1, r.plan().items.size()); return r.plan().items.get(0); }

  @Test void governanceRejectsIllegalAdjustments() {
    assertNotNull(only(apply(a("disable", "PRD_REVIEW", null, null, null))).rejected, "锁定节点类型不能关");
    assertNotNull(only(apply(a("disable", "QA", null, null, null))).rejected);
    assertTrue(only(apply(a("removeCheck", "QA", "ci", null, null))).rejected.contains("ci"), "QA 必须保留 ci");
    assertTrue(only(apply(a("addCheck", "QA", "rm-rf", null, null))).rejected.contains("未知检查"));
    assertNotNull(only(apply(a("setParam", "ACCEPTANCE", null, "gate.pass", 1.5))).rejected, "越界");
    assertNotNull(only(apply(a("setParam", "DEV", null, "maxChangeLines", 1e9))).rejected, "超过全局上限");
    assertNotNull(only(apply(a("setParam", "DEV", null, "rm", 1.0))).rejected, "未知参数");
    assertNotNull(only(apply(a("enable", "CUSTOM", null, null, null))).rejected, "不支持自定义节点");
    assertNotNull(only(apply(a("drop_table", "DEV", null, null, null))).rejected);
    var role = a("setRole", "DEV", null, null, null); role.slot = "maker"; role.role = "acceptance.judge";
    assertNotNull(only(apply(role)).rejected, "槽位类型不匹配");
    var many = new Plans.Adj[15]; for (int i = 0; i < 15; i++) many[i] = a("addCheck", "QA", i % 2 == 0 ? "markdown-lint" : "i18n-keys-complete", null, null);
    assertTrue(apply(many).plan().items.stream().filter(x -> x.rejected != null).anyMatch(x -> x.rejected.contains("最多")));
    // 被拒的调整不影响快照
    assertEquals(Tenant.run(Tenant.LEGACY, () -> pipes.resolve(line()).id), apply(a("removeCheck", "QA", "ci", null, null)).eff().id);
  }

  @Test void tightenAutoRelaxNeedsApproval() {
    var t = apply(a("enable", "DESIGN", null, null, null), a("addCheck", "QA", "markdown-lint", null, null), a("setParam", "DEV", null, "maxChangeLines", 200.0));
    assertTrue(t.plan().items.stream().allMatch(x -> x.rejected == null && !x.relax), t.plan().items.toString());
    assertFalse(t.plan().needsApproval);
    assertTrue(t.eff().stages.get("DESIGN").required); assertTrue(t.eff().stages.get("QA").checks.contains("markdown-lint"));
    assertEquals("plan:chat", t.eff().sources.get("stages.QA"));
    assertNotEquals(Tenant.run(Tenant.LEGACY, () -> pipes.resolve(line()).id), t.eff().id);
    for (var relax : List.of(a("disable", "SPEC", null, null, null), a("setParam", "DEV", null, "maxLoops", 8.0), a("setParam", "ACCEPTANCE", null, "gate.pass", 0.1))) {
      var r = apply(relax); assertNull(only(r).rejected, only(r).toString()); assertTrue(only(r).relax); assertTrue(r.plan().needsApproval);
    }
  }

  @Test void dynamicWorkflowReplaceInsertFastTrackAndBindSkills() {
    var ft = a("fastTrack", "PRD_REVIEW", null, null, null); ft.reason = "小需求已在对话确认验收条件";
    var rep = a("replaceStage", "DESIGN", null, null, null); rep.targetStage = "API_DESIGN"; rep.label = "接口契约设计";
    var ins = a("insertStage", "DB_SCHEMA", null, null, null); ins.after = "DESIGN"; ins.label = "数据库变更设计";
    var bind = a("bindSkills", "DEV", null, null, null); bind.skills = List.of("java-spring@1", "api-contract@1"); bind.instructions = "使用统一 Result<T> 封装响应";
    var r = apply(ft, rep, ins, bind);
    assertTrue(r.plan().items.stream().allMatch(x -> x.rejected == null && !Boolean.TRUE.equals(x.relax)), r.plan().items.toString());
    assertFalse(r.plan().needsApproval, "极速通道、节点专业化替换、新增设计节点与绑定技能无需额外审批");
    assertFalse(r.eff().stageOn("PRD_REVIEW"), "fastTrack 跳过 PRD_REVIEW");
    assertEquals("接口契约设计", r.eff().stage("DESIGN").label);
    assertEquals("apiContract", r.eff().stage("DESIGN").artifactKey);
    assertNotNull(r.eff().stage("DB_SCHEMA"), "动态插入 DB_SCHEMA 节点");
    assertEquals("dbSchema", r.eff().stage("DB_SCHEMA").artifactKey);
    assertTrue(r.eff().skillText.get("stage:DEV").contains("Spring Boot") && r.eff().skillText.get("stage:DEV").contains("Result<T>"));
    // 安全底线节点不可 fastTrack 或 replaceStage
    assertNotNull(only(apply(a("fastTrack", "QA", null, null, null))).rejected);
    assertNotNull(only(apply(a("replaceStage", "DEV", null, null, null))).rejected);
  }

  @Test void backendDraftAutomaticallySwitchesToBackendWorkflow() {
    String id = draftSession("{\"classification\":\"backend\",\"targetPaths\":[\"src/main/java/com/x7x24/order/OrderService.java\"],\"risk\":\"low\",\"size\":\"S\"}");
    var preview = (Map<?, ?>) Tenant.run(Tenant.LEGACY, () -> chat.get(id)).get("plan");
    assertEquals("backend", preview.get("classification"));
    var c = Tenant.run(Tenant.LEGACY, () -> chat.confirm(id, version(id), "alice", false));
    assertFalse(c.pendingApproval(), "后端规则切换与小需求极速通道均无需额外审批");
    assertNotNull(c.taskId());
    var t = repo.get(c.taskId());
    var eff = pipes.snapshot(t.context.configSnapshot, t.productLine);
    assertEquals("接口契约设计", eff.stage("DESIGN").label, "DESIGN 自动替换为接口契约设计");
    assertEquals("apiContract", eff.stage("DESIGN").artifactKey);
    assertTrue(eff.stage("QA").checks.contains("sql-safety-lint"), "QA 自动附加 sql-safety-lint");
    assertTrue(eff.skillText.get("stage:DEV").contains("Spring Boot"), "DEV 自动挂载 java-spring@1 与 api-contract@1");
    assertFalse(eff.stageOn("PRD_REVIEW"), "size=S 且 risk=low 自动启用极速通道跳过 PRD_REVIEW");
  }

  @Test void lockedStageCannotBeAdjusted() {
    var b = Tenant.run(Tenant.LEGACY, () -> pipes.binding(line())); var copy = Json.convert(b, com.x7x24.pipeline.Pipeline.LineBinding.class);
    var ov = new com.x7x24.pipeline.Pipeline.StageCfg("QA"); ov.locked = true;
    copy.stageOverrides = new LinkedHashMap<>(copy.stageOverrides == null ? Map.of() : copy.stageOverrides); copy.stageOverrides.put("QA", ov);
    var r = Tenant.run(Tenant.LEGACY, () -> Plans.apply(pipes.resolve(line(), copy), List.of(a("addCheck", "QA", "markdown-lint", null, null)), null, pipes.rolesFor(copy)));
    assertTrue(only(r).rejected.contains("锁定"));
  }

  @Test void fullstackMigrationAssemblesApiDbSecurityAndStackSkills() {
    var d = new com.x7x24.chat.Draft(); d.projectId = "sandbox-default"; d.title = "AI 工具中新增配置模型能力"; d.goal = "管理员可配置模型供应商与 API Key"; d.risk = "medium"; d.size = "M";
    d.targetPaths = new ArrayList<>(List.of("backend/internal/handlers/ai_model.go", "backend/internal/models/ai_model.go", "database/migrations/0007_ai_models.sql", "frontend/src/features/ai/ModelConfig.tsx"));
    assertEquals(List.of("frontend", "backend", "migration"), PlanService.labels(d.targetPaths));
    assertEquals("fullstack", PlanService.classify(d.targetPaths));
    var v = (Map<?, ?>) Tenant.run(Tenant.LEGACY, () -> plans.preview(d, line()));
    assertEquals("fullstack", v.get("classification")); assertEquals(List.of("frontend", "backend", "migration"), v.get("labels"));
    @SuppressWarnings("unchecked") var st = (List<Map<String, Object>>) v.get("stages");
    var ids = st.stream().map(x -> (String) x.get("type")).toList();
    assertTrue(ids.indexOf("API_DESIGN") > ids.indexOf("PLANNING") && ids.indexOf("DB_SCHEMA") > ids.indexOf("API_DESIGN") && ids.indexOf("DESIGN") > ids.indexOf("DB_SCHEMA"), ids.toString());
    assertTrue(ids.indexOf("SEC_AUDIT") > ids.indexOf("QA") && ids.indexOf("SEC_AUDIT") < ids.indexOf("ACCEPTANCE"), "安全审计在测试之后、验收之前：" + ids);
    var dev = st.stream().filter(x -> "DEV".equals(x.get("type"))).findFirst().orElseThrow();
    assertEquals(List.of("go-service@1", "react-ts@1", "api-contract@1", "sql-migration@1"), dev.get("skills"));
    var sec = st.stream().filter(x -> "SEC_AUDIT".equals(x.get("type"))).findFirst().orElseThrow();
    assertEquals(List.of("sec-review@1"), sec.get("skills"));
    var qa = st.stream().filter(x -> "QA".equals(x.get("type"))).findFirst().orElseThrow();
    assertTrue(((List<?>) qa.get("checks")).contains("sql-safety-lint"));
    @SuppressWarnings("unchecked") var adj = (List<Plans.Adj>) v.get("adjustments");
    assertTrue(adj.stream().allMatch(x -> x.rejected == null), adj.toString()); assertEquals(false, v.get("needsApproval"));
  }

  @Test void featureFlagAndWorkspaceNodesApi() {
    assertEquals(false, http.getForObject("/api/pipeline/features", Map.class).get("pipelineV2"));
    var on = http.exchange("/api/pipeline/features", org.springframework.http.HttpMethod.PUT, new org.springframework.http.HttpEntity<>(Map.of("pipelineV2", true)), Map.class);
    try {
      assertEquals(200, on.getStatusCode().value()); assertEquals(true, on.getBody().get("pipelineV2"));
      String nid = "PRIVACY_" + (System.nanoTime() % 100000);
      var bad = http.postForEntity("/api/pipeline/workspace-nodes", Map.of("id", "SEC_AUDIT", "label", "x", "description", "y", "executor", "shell"), Map.class);
      assertEquals(400, bad.getStatusCode().value());
      var c = http.postForEntity("/api/pipeline/workspace-nodes", Map.of("id", nid, "label", "隐私合规评审", "description", "检查个人数据收集是否最小化、是否有脱敏", "executor", "review", "skills", List.of("sec-review@1")), Map.class);
      assertEquals(200, c.getStatusCode().value(), String.valueOf(c.getBody())); assertEquals("draft", c.getBody().get("status"));
      // 草稿节点：AI 插入需要审批
      var d = lmsDraft(); d.adjustments = Json.tree("[{\"op\":\"insertStage\",\"stage\":\"" + nid + "\",\"after\":\"QA\",\"executor\":\"llm_checker\",\"label\":\"隐私\"}]");
      var v1 = (Map<?, ?>) Tenant.run(Tenant.LEGACY, () -> plans.preview(d, line()));
      assertEquals(true, v1.get("needsApproval"));
      var ok = http.postForEntity("/api/pipeline/workspace-nodes/" + nid + "/1/review", Map.of("approve", true), Map.class);
      assertEquals(200, ok.getStatusCode().value(), String.valueOf(ok.getBody())); assertEquals("approved", ok.getBody().get("status"));
      d.adjustments = Json.tree("[{\"op\":\"insertStage\",\"stage\":\"" + nid + "\"}]");
      var v2 = (Map<?, ?>) Tenant.run(Tenant.LEGACY, () -> plans.preview(d, line()));
      assertEquals(false, v2.get("needsApproval"), String.valueOf(v2.get("adjustments")));
      var n = nodes(v2).stream().filter(x -> nid.equals(x.get("id"))).findFirst().orElseThrow();
      assertEquals("隐私合规评审", n.get("label")); assertEquals("review", n.get("executor"));
      var p = Tenant.run(Tenant.LEGACY, () -> plans.pin(d, line(), "sandbox-default"));
      var e = Tenant.run(Tenant.LEGACY, () -> pipes.snapshot(p.snapshotId(), null));
      assertEquals(2, e.schema); assertEquals("ACCEPTANCE", e.stage(nid).anchorPhase); assertEquals(List.of("sec-review@1"), e.stage(nid).skills);
    } finally { http.exchange("/api/pipeline/features", org.springframework.http.HttpMethod.PUT, new org.springframework.http.HttpEntity<>(Map.of("pipelineV2", false)), Map.class); }
  }
  static com.x7x24.chat.Draft lmsDraft() {
    var d = new com.x7x24.chat.Draft(); d.projectId = "sandbox-default"; d.title = "AI 工具中新增配置模型能力"; d.goal = "管理员可配置模型供应商与 API Key"; d.risk = "medium"; d.size = "M";
    d.targetPaths = new ArrayList<>(List.of("backend/internal/handlers/ai_model.go", "database/migrations/0007_ai_models.sql", "frontend/src/features/ai/ModelConfig.tsx"));
    return d;
  }
  @SuppressWarnings("unchecked") static List<Map<String, Object>> nodes(Map<?, ?> v) { return (List<Map<String, Object>>) Json.M.convertValue(v.get("graph"), Map.class).get("nodes"); }
  @Test void assemblerBuildsV2GraphWithSourcesAndMandatory() {
    var v = (Map<?, ?>) Tenant.run(Tenant.LEGACY, () -> plans.preview(lmsDraft(), line()));
    assertEquals(2, v.get("schema"));
    var ns = nodes(v); var ids = ns.stream().filter(n -> n.get("parent") == null && n.get("optional") == null).map(n -> (String) n.get("id")).toList();
    assertEquals(List.of("PLANNING", "API_DESIGN", "DB_SCHEMA", "UX_DESIGN", "DEV", "QA", "CODE_REVIEW", "SEC_AUDIT", "ACCEPTANCE", "RELEASE"), ids.subList(ids.indexOf("PLANNING"), ids.size()), "规模 M（G1 起）：测试后先逐行审查，再安全审计");
    Map<String, Map<String, Object>> by = new HashMap<>(); ns.forEach(n -> by.put((String) n.get("id"), n));
    assertEquals("governance", by.get("CODE_REVIEW").get("source")); assertNotNull(by.get("CODE_REVIEW").get("mandatory"));
    assertEquals("governance", by.get("SEC_AUDIT").get("source")); assertNotNull(by.get("SEC_AUDIT").get("mandatory"));
    assertEquals("rule", by.get("DB_SCHEMA").get("source")); assertNotNull(by.get("DB_SCHEMA").get("mandatory"));
    assertNotNull(by.get("API_DESIGN").get("mandatory"), "改动 handler：接口契约设计必选");
    assertEquals("QA", by.get("CHECK_SQL_SAFETY").get("parent")); assertEquals("check", by.get("CHECK_SQL_SAFETY").get("family"));
    assertEquals("preset", by.get("PLANNING").get("source")); assertEquals(true, by.get("SPEC").get("optional"));
    assertNotNull(by.get("DB_SCHEMA").get("reason"));
    var g = Json.M.convertValue(v.get("graph"), Map.class); assertFalse(((List<?>) g.get("edges")).isEmpty()); assertTrue(((Map<?, ?>) g.get("estimate")).get("usd") instanceof Number);
  }
  static com.x7x24.chat.Draft draft(String title, String size, String... paths) {
    var d = new com.x7x24.chat.Draft(); d.projectId = "sandbox-default"; d.title = title; d.goal = title; d.risk = "low"; d.size = size; d.targetPaths = new ArrayList<>(List.of(paths)); return d;
  }
  @Test void i18nTaskAssemblesWithoutUxDesignOrOptionalNodes() {
    var d = draft("React 前端加日语多语言支持", "M", "frontend/src/locales/ja.json", "frontend/src/locales/zh.json", "frontend/src/i18n/index.ts");
    var v = (Map<?, ?>) Tenant.run(Tenant.LEGACY, () -> plans.preview(d, line()));
    var ids = nodes(v).stream().map(n -> (String) n.get("id")).toList();
    assertFalse(ids.contains("UX_DESIGN") || ids.contains("DESIGN"), "只改翻译：组装时就不放体验设计 " + ids);
    assertFalse(ids.contains("SPEC"), "只改翻译：不需要需求策划 " + ids);
    assertTrue(nodes(v).stream().noneMatch(n -> n.get("optional") != null), "组装后不留待定的虚线节点 " + ids);
    assertEquals(false, v.get("needsApproval"), "规则关闭可选步骤不需要审批");
    @SuppressWarnings("unchecked") var adj = (List<Plans.Adj>) v.get("adjustments");
    assertTrue(adj.stream().anyMatch(a -> "disable".equals(a.op) && "DESIGN".equals(a.stage) && a.reason.contains("翻译")), adj.toString());
  }
  @Test void uiChangeTaskKeepsUxDesignRequired() {
    var d = draft("课程详情页新增评分组件", "S", "frontend/src/pages/CourseDetail.tsx", "frontend/src/components/Rating.tsx");
    var v = (Map<?, ?>) Tenant.run(Tenant.LEGACY, () -> plans.preview(d, line()));
    var ux = nodes(v).stream().filter(n -> "UX_DESIGN".equals(n.get("id"))).findFirst().orElseThrow();
    assertNull(ux.get("optional"), "涉及页面 / 组件：体验设计必做"); assertEquals("rule", ux.get("source"));
    assertFalse(nodes(v).stream().anyMatch(n -> "SPEC".equals(n.get("id"))), "小规模：不放需求策划");
    var big = draft("课程详情页改版", "L", "frontend/src/pages/CourseDetail.tsx");
    var vb = (Map<?, ?>) Tenant.run(Tenant.LEGACY, () -> plans.preview(big, line()));
    var spec = nodes(vb).stream().filter(n -> "SPEC".equals(n.get("id"))).findFirst().orElseThrow(); assertNull(spec.get("optional"), "规模 L：需求策划必做");
  }
  @Test void agentCannotRemoveMandatoryAndCatalogNodesNeedStackFit() {
    var d = lmsDraft();
    d.adjustments = Json.tree("[{\"op\":\"removeStage\",\"stage\":\"SEC_AUDIT\"},{\"op\":\"removeStage\",\"stage\":\"DB_SCHEMA\"},{\"op\":\"insertStage\",\"stage\":\"ARCH_REVIEW\",\"after\":\"PLANNING\",\"reason\":\"跨前后端与数据库\"},{\"op\":\"insertStage\",\"stage\":\"PERF_BUDGET\",\"after\":\"QA\"},{\"op\":\"insertStage\",\"stage\":\"MY_NODE\",\"after\":\"QA\",\"label\":\"自定义评审\",\"executor\":\"llm_checker\"}]");
    var v = (Map<?, ?>) Tenant.run(Tenant.LEGACY, () -> plans.preview(d, line()));
    @SuppressWarnings("unchecked") var adj = (List<Plans.Adj>) v.get("adjustments");
    var rm = adj.stream().filter(x -> "removeStage".equals(x.op)).toList();
    assertEquals(2, rm.size()); assertTrue(rm.stream().allMatch(x -> x.rejected != null && x.rejected.contains("治理必选")), rm.toString());
    var arch = adj.stream().filter(x -> "ARCH_REVIEW".equals(x.stage)).findFirst().orElseThrow(); assertNull(arch.rejected, arch.toString());
    var perf = adj.stream().filter(x -> "PERF_BUDGET".equals(x.stage)).findFirst().orElseThrow(); assertTrue(perf.rejected.contains("执行器尚未提供"), perf.rejected);
    var mine = adj.stream().filter(x -> "MY_NODE".equals(x.stage)).findFirst().orElseThrow(); assertNull(mine.rejected); assertEquals(true, mine.relax); assertTrue(mine.warning.contains("审批"));
    assertEquals(true, v.get("needsApproval"));
    var ids = nodes(v).stream().map(n -> (String) n.get("id")).toList();
    assertTrue(ids.contains("ARCH_REVIEW") && ids.contains("SEC_AUDIT") && ids.contains("DB_SCHEMA"), ids.toString());
    var arc = nodes(v).stream().filter(n -> "ARCH_REVIEW".equals(n.get("id"))).findFirst().orElseThrow(); assertEquals("agent", arc.get("source")); assertEquals("跨前后端与数据库", arc.get("reason"));
  }
  @Autowired com.x7x24.integration.RuntimeSettings settings;
  @Test void v2SnapshotPinsAndReplaysGraph() {
    var d = lmsDraft();
    var off = Tenant.run(Tenant.LEGACY, () -> plans.pin(d, line(), "sandbox-default"));
    assertNull(Tenant.run(Tenant.LEGACY, () -> pipes.snapshot(off.snapshotId(), null)).schema, "工作区未开启 pipeline.v2：钉 v1 快照");
    Tenant.run(Tenant.LEGACY, () -> settings.setPipelineV2(true));
    try { v2Pinned(d); } finally { Tenant.run(Tenant.LEGACY, () -> settings.setPipelineV2(false)); }
  }
  void v2Pinned(com.x7x24.chat.Draft d) {
    var p = Tenant.run(Tenant.LEGACY, () -> plans.pin(d, line(), "sandbox-default"));
    var e = Tenant.run(Tenant.LEGACY, () -> pipes.snapshot(p.snapshotId(), null));
    assertEquals(2, e.schema); assertNotNull(e.graph); assertTrue(e.plan.mandatory.containsKey("SEC_AUDIT"));
    var again = Tenant.run(Tenant.LEGACY, () -> pipes.latestFor(e.lineId, p.snapshotId()));
    assertEquals(e.id, again.id, "在相同业务线配置上重放得到同一个快照");
    var old = Tenant.run(Tenant.LEGACY, () -> pipes.previewPlan(e.lineId, List.of(), null));
    assertNull(old.eff().schema); assertFalse(com.x7x24.pipeline.Assembler.graphOf(old.eff()).nodes.isEmpty(), "旧快照从线性 stages 派生流程图");
  }
  @Test void unknownSkillIsDroppedButNodeKept() {
    var ins = a("insertStage", "SEC_AUDIT", null, null, null); ins.after = "QA"; ins.skills = List.of("sec-review@9");
    var r = apply(ins); var x = only(r);
    assertNull(x.rejected, x.toString()); assertTrue(x.warning.contains("sec-review@9") && x.warning.contains("sec-review@1"), x.warning);
    assertEquals(List.of("sec-review@1"), r.eff().stage("SEC_AUDIT").skills, "未知技能被剔除，保留节点默认技能");
    var b = a("bindSkills", "DEV", null, null, null); b.skills = List.of("nope@1");
    assertTrue(only(apply(b)).rejected.contains("可用技能"));
    var mk = a("insertStage", "POST_QA_MAKER", null, null, null); mk.after = "QA"; mk.executor = "llm_maker";
    assertTrue(only(apply(mk)).rejected.contains("评审类"));
  }

  @Test void classifyRules() {
    assertEquals("docs", PlanService.classify(List.of("README.md", "docs/a.md")));
    assertEquals("migration", PlanService.classify(List.of("db/migrations/001.sql", "src/a.ts")));
    assertEquals(List.of("backend", "migration"), PlanService.labels(List.of("db/migrations/001.sql", "src/a.ts")));
    assertEquals("fullstack", PlanService.classify(List.of("db/migrations/001.sql", "web/src/App.tsx", "api/x.go")));
    assertEquals("fullstack", PlanService.classify(List.of("src/pages/Grades.tsx", "server/routes/grades.ts")));
    assertEquals("frontend", PlanService.classify(List.of("src/components/Btn.tsx")));
    assertEquals("infra", PlanService.classify(List.of(".github/workflows/ci.yml")));
    assertNull(PlanService.classify(List.of()));
  }

  String draftSession(String extra) {
    return Tenant.run(Tenant.LEGACY, () -> {
      String id = (String) chat.create("alice", "sandbox-default").get("id");
      ObjectNode d = (ObjectNode) Json.tree(ChatIntakeIT.FULL_DRAFT); if (extra != null) Json.tree(extra).fields().forEachRemaining(e -> d.set(e.getKey(), e.getValue()));
      chat.editDraft(id, 0, d); return id;
    });
  }
  int version(String id) { return Tenant.run(Tenant.LEGACY, () -> ((Number) chat.session(id).get("draft_version")).intValue()); }

  @Test void confirmPinsPlanAndReplanKeepsIt() {
    String id = draftSession("{\"targetPaths\":[\"prisma/migrations/20261015_grade/migration.sql\",\"src/grades/export.ts\"]}");
    var preview = (Map<?, ?>) Tenant.run(Tenant.LEGACY, () -> chat.get(id)).get("plan");
    assertEquals("migration", preview.get("classification"));
    var c = Tenant.run(Tenant.LEGACY, () -> chat.confirm(id, version(id), "alice", false));
    assertFalse(c.pendingApproval(), "规则项都是收紧，不需审批"); assertNotNull(c.taskId());
    var t = repo.get(c.taskId()); var eff = pipes.snapshot(t.context.configSnapshot, t.productLine);
    assertTrue(eff.stages.get("DESIGN").required, "数据迁移强制设计评审"); assertNotNull(eff.plan);
    var res = http.getForEntity("/api/tasks/" + c.taskId() + "/plan", String.class);
    assertEquals(200, res.getStatusCode().value()); var body = Json.tree(res.getBody());
    assertEquals(t.context.configSnapshot, body.path("snapshotId").asText());
    assertTrue(body.path("stages").toString().contains("\"type\":\"DESIGN\"") && body.path("adjustments").toString().contains("数据迁移"), body.toString());
    // 重新规划 / 升级：在最新业务线配置上重放计划
    String re = Tenant.run(Tenant.LEGACY, () -> pipes.repin(t.productLine, t.context.configSnapshot));
    assertEquals(t.context.configSnapshot, re);
    assertEquals(Boolean.TRUE, audit.verify().get("ok"));
  }

  @Test void relaxNeedsApprovalAndEditResets() {
    String id = draftSession("{\"size\":\"M\",\"adjustments\":[{\"op\":\"disable\",\"stage\":\"SPEC\",\"reason\":\"改动很小，跳过策划\"},{\"op\":\"removeCheck\",\"stage\":\"QA\",\"check\":\"ci\"}]}");
    var c = Tenant.run(Tenant.LEGACY, () -> chat.confirm(id, version(id), "bob", false));
    assertTrue(c.pendingApproval()); assertNull(c.taskId());
    assertEquals("pending_approval", Tenant.run(Tenant.LEGACY, () -> chat.session(id).get("status")));
    assertEquals(0, db.count("SELECT COUNT(*) FROM task WHERE source_chat_id=?", id));
    @SuppressWarnings("unchecked") var adj = (List<Plans.Adj>) ((Map<String, Object>) c.plan()).get("adjustments");
    assertTrue(adj.stream().anyMatch(x -> "ci".equals(x.check) && x.rejected != null), "ci 移除被拒");
    // 改草稿 → 撤回审批，回到编辑态
    Tenant.run(Tenant.LEGACY, () -> chat.editDraft(id, version(id), Json.tree("{\"risk\":\"low\"}")));
    assertEquals("open", Tenant.run(Tenant.LEGACY, () -> chat.session(id).get("status")));
    assertTrue(Tenant.run(Tenant.LEGACY, () -> chat.confirm(id, version(id), "bob", false)).pendingApproval());
    // 管理员审批通过 → 建任务；再次确认幂等
    var ok = Tenant.run(Tenant.LEGACY, () -> chat.confirm(id, version(id), "owner", true));
    assertNotNull(ok.taskId()); assertTrue(ok.created());
    var eff = pipes.snapshot(repo.get(ok.taskId()).context.configSnapshot, line());
    assertFalse(eff.stages.get("SPEC").on()); var qa = eff.stages.get("QA"); assertTrue(qa == null || qa.checks == null || qa.checks.contains("ci"));
    var again = Tenant.run(Tenant.LEGACY, () -> chat.confirm(id, version(id), "owner", true));
    assertEquals(ok.taskId(), again.taskId()); assertFalse(again.created());
    assertEquals(1, db.count("SELECT COUNT(*) FROM task WHERE source_chat_id=?", id));
  }

  @Test void proposeToolPreviewsOnly() {
    String id = draftSession(null);
    var d = Tenant.run(Tenant.LEGACY, () -> chat.draft(id));
    var out = (Map<?, ?>) Tenant.run(Tenant.LEGACY, () -> plans.propose(d, Json.tree("{\"adjustments\":[{\"op\":\"disable\",\"stage\":\"DESIGN\"}]}")));
    assertEquals(true, out.get("needsApproval"));
    assertEquals(0, db.count("SELECT COUNT(*) FROM task WHERE source_chat_id=?", id));
  }

  @SuppressWarnings("unchecked") List<Map<String, Object>> presetList() { return (List<Map<String, Object>>) http.getForObject("/api/pipeline/presets?line=" + line(), Map.class).get("presets"); }
  void setDefaultPreset(Object v) {
    Map<String, Object> pol = new HashMap<>(); pol.put("defaultPreset", v);
    var r = http.exchange("/api/projects/sandbox-default", org.springframework.http.HttpMethod.PUT, new org.springframework.http.HttpEntity<>(Map.of("config", Map.of("nodePolicy", pol))), Map.class);
    assertEquals(200, r.getStatusCode().value(), String.valueOf(r.getBody()));
  }
  @Test void templatesBecomePresetsAndProjectDefaultPresetSeedsV2() {
    var ps = presetList(); var by = new HashMap<String, Map<String, Object>>(); ps.forEach(p -> by.put((String) p.get("id"), p));
    assertTrue(by.keySet().containsAll(List.of("default", "web-fullstack", "backend-service", "docs-only")), by.keySet().toString());
    assertEquals("标准", by.get("default").get("name")); assertEquals("builtin", by.get("default").get("source"));
    assertFalse(((List<?>) by.get("docs-only").get("nodes")).contains("SPEC"), "纯文档预设关闭策划");
    // 另存工作区预设：只能关可选步骤
    String pid = "lean-" + (System.nanoTime() % 100000);
    assertEquals(400, http.postForEntity("/api/pipeline/presets", Map.of("id", pid, "name", "x", "base", "default@1", "disable", List.of("QA")), Map.class).getStatusCode().value());
    var c = http.postForEntity("/api/pipeline/presets", Map.of("id", pid, "name", "精简", "base", "default@1", "disable", List.of("SPEC")), Map.class);
    assertEquals(200, c.getStatusCode().value(), String.valueOf(c.getBody())); assertEquals("workspace", c.getBody().get("source"));
    assertTrue(presetList().stream().anyMatch(p -> pid.equals(p.get("id"))));
    // 项目默认预设：不存在的拒绝
    var bad = http.exchange("/api/projects/sandbox-default", org.springframework.http.HttpMethod.PUT, new org.springframework.http.HttpEntity<>(Map.of("config", Map.of("nodePolicy", Map.of("defaultPreset", "nope@9")))), Map.class);
    assertEquals(400, bad.getStatusCode().value());
    setDefaultPreset("docs-only@1");
    try {
      var d = lmsDraft();
      // 开关关：仍以业务线绑定的模板为起点（D10）
      var off = Tenant.run(Tenant.LEGACY, () -> plans.pin(d, line(), "sandbox-default"));
      assertEquals("default@1", Tenant.run(Tenant.LEGACY, () -> pipes.snapshot(off.snapshotId(), null)).template);
      Tenant.run(Tenant.LEGACY, () -> settings.setPipelineV2(true));
      try {
        var p = Tenant.run(Tenant.LEGACY, () -> plans.pin(d, line(), "sandbox-default"));
        var e = Tenant.run(Tenant.LEGACY, () -> pipes.snapshot(p.snapshotId(), null));
        assertEquals("docs-only@1", e.template); assertEquals("docs-only@1", e.plan.preset);
        assertTrue(e.plan.mandatory.containsKey("SEC_AUDIT"), "预设只是起点，治理必选照样加");
        assertTrue(e.stages.containsKey("SEC_AUDIT"));
        assertEquals(e.id, Tenant.run(Tenant.LEGACY, () -> pipes.latestFor(e.lineId, p.snapshotId())).id, "按同一预设重放得到同一快照");
        // 手动建任务可以临时选预设
        var t = http.postForEntity("/api/projects/sandbox-default/tasks", Map.of("title", "预设起点的手动任务 " + pid, "content", "补充文档", "preset", pid), Map.class);
        assertEquals(200, t.getStatusCode().value(), String.valueOf(t.getBody()));
        var task = Tenant.run(Tenant.LEGACY, () -> repo.get((String) t.getBody().get("taskId")));
        assertEquals("v2", task.pipelineVersion);
        var te = Tenant.run(Tenant.LEGACY, () -> pipes.snapshot(task.context.configSnapshot, null));
        assertEquals(pid + "@1", te.plan.preset); assertEquals(Boolean.FALSE, te.stages.get("SPEC").enabled);
      } finally { Tenant.run(Tenant.LEGACY, () -> settings.setPipelineV2(false)); }
    } finally { setDefaultPreset(null); }
    assertNull(projects.require("sandbox-default").cfg().nodePolicy.defaultPreset);
  }
}
