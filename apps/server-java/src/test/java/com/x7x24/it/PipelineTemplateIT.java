package com.x7x24.it;

import com.x7x24.core.Status;
import com.x7x24.engine.Decision;
import com.x7x24.pipeline.Catalog;
import com.x7x24.pipeline.Pipeline.*;
import com.x7x24.pipeline.PipelineService;
import com.x7x24.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipelineTemplateIT extends Harness {
  @Autowired PipelineService pipes;

  @AfterEach void resetBinding() { pipes.setAdminsForTest(List.of("test", "operator")); pipes.putBinding("sandbox", new LineBinding(), "test"); }

  LineBinding bind(String tpl) { var b = new LineBinding(); b.template = tpl; return b; }

  @Test void newTaskPinsDefaultSnapshot() {
    String id = ingestIssue("新增 clampQ 工具函数（流程快照）", "示例：clampQ(5,1,3) 返回 3");
    var t = repo.get(id); assertNotNull(t.context.configSnapshot);
    var e = engine.eff(t); assertEquals("default@1", e.template); assertEquals(Catalog.defaultHeal(), e.heal);
    assertTrue(e.stageOn("SPEC") && e.stageOn("DESIGN"));
  }

  @Test void docsOnlyTemplateSkipsSpecAndDesign() {
    pipes.putBinding("sandbox", bind("docs-only@1"), "test");
    String id = ingestIssue("新增 trimQ 工具函数（纯文档流程）", "示例：trimQ(' a ') 返回 'a'");
    var t = run(id, x -> x.st() == Status.DEV || x.st() == Status.DISCARDED || x.st() == Status.SPEC, 60);
    assertEquals(Status.DEV, t.st(), "docs-only 关闭了 SPEC / DESIGN，规划后直接进开发");
    assertFalse(t.path.contains("SPEC"));
    assertTrue(audit.list(id, null, 100, 0).stream().anyMatch(ev -> "PATH_FILTERED".equals(ev.get("event_type"))));
  }

  @Test void runningTaskStaysPinnedUntilReplan() {
    String id = ingestIssue("新增 lerpQ 工具函数（钉住版本）", "示例：lerpQ(0,10,0.5) 返回 5");
    String s0 = repo.get(id).context.configSnapshot;
    pipes.putBinding("sandbox", bind("docs-only@1"), "test");
    assertEquals(s0, repo.get(id).context.configSnapshot); assertEquals("default@1", engine.eff(repo.get(id)).template, "运行中任务不受新配置影响");
    db.update("UPDATE task SET status='PLANNING', current_stage='PLANNING', version=version+1 WHERE id=?", id);
    engine.apply(repo.get(id), Decision.anomaly("change_too_large", "x"));
    engine.resumeAnomaly(id, "operator");
    var after = repo.get(id); assertEquals(s0,after.context.configSnapshot);assertEquals("default@1",engine.eff(after).template,"恢复保留钉住的流程配置");
  }

  @Test void templateHealPlanDrivesSelfHeal() {
    var t = Json.convert(Catalog.TEMPLATES.get("default@1"), Template.class); t.id = "heal-test"; t.builtin = false; t.heal = Map.of("default", List.of("replan")); t.maxHeals = 2;
    var saved = pipes.submitTemplate(t, "test"); pipes.putBinding("sandbox", bind(saved.key()), "test");
    String id = ingestIssue("新增 signQ 工具函数（自愈计划）", "示例：signQ(-2) 返回 -1");
    db.update("UPDATE task SET status='PLANNING', current_stage='PLANNING', version=version+1 WHERE id=?", id);
    engine.apply(repo.get(id), Decision.anomaly("change_too_large", "x"));
    run(id, x -> x.st() != Status.ANOMALY, 10);
    assertBlocked(repo.get(id));assertEquals(Status.PLANNING,repo.get(id).st());assertEquals(0,db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='SELF_HEAL'",id),"模板不能绕过停止规则");
  }

  @Test void linePrivateTemplateNeedsAdminApproval() {
    pipes.setAdminsForTest(List.of("boss"));
    var t = Json.convert(Catalog.TEMPLATES.get("docs-only@1"), Template.class); t.id = "sandbox-docs"; t.owner = "sandbox"; t.builtin = false;
    var saved = pipes.submitTemplate(t, "dev1"); assertEquals("pending", saved.status);
    assertThrows(PipelineService.Invalid.class, () -> pipes.putBinding("sandbox", bind(saved.key()), "dev1"), "未审批不能绑定");
    assertThrows(PipelineService.Forbidden.class, () -> pipes.review(saved.key(), true, null, "dev1"));
    assertThrows(PipelineService.Forbidden.class, () -> { var p = Json.convert(t, Template.class); p.owner = null; p.id = "platform-x"; pipes.submitTemplate(p, "dev1"); });
    pipes.review(saved.key(), true, "ok", "boss");
    assertEquals(saved.key(), pipes.putBinding("sandbox", bind(saved.key()), "dev1").template);
  }

  @Test void lineOverridesCanOnlyTighten() {
    var t = Json.convert(Catalog.TEMPLATES.get("default@1"), Template.class); t.id = "gate-test"; t.builtin = false; t.stage("ACCEPTANCE").gate = new Gate(0.8, null); t.stage("DEV").maxChangeLines = 800;
    var saved = pipes.submitTemplate(t, "test");
    var b = bind(saved.key()); var ov = new StageCfg(); ov.gate = new Gate(0.7, null); b.stageOverrides = Map.of("ACCEPTANCE", ov);
    assertThrows(PipelineService.Invalid.class, () -> pipes.putBinding("sandbox", b, "test"));
    var b2 = bind(saved.key()); var ov2 = new StageCfg(); ov2.maxChangeLines = 1200; b2.stageOverrides = Map.of("DEV", ov2);
    assertThrows(PipelineService.Invalid.class, () -> pipes.putBinding("sandbox", b2, "test"));
    var b3 = bind(saved.key()); var ov3 = new StageCfg(); ov3.gate = new Gate(0.9, null); b3.stageOverrides = Map.of("ACCEPTANCE", ov3);
    pipes.putBinding("sandbox", b3, "test"); assertEquals(0.9, pipes.resolve("sandbox").stage("ACCEPTANCE").gate.pass);
    var b4 = bind("default@1"); var ov4 = new StageCfg(); ov4.enabled = false; b4.stageOverrides = Map.of("QA", ov4);
    assertThrows(PipelineService.Invalid.class, () -> pipes.putBinding("sandbox", b4, "test"), "锁定节点不能被业务线关闭");
  }

  @Test void derivedRoleInjectsSkillsIntoPrompt() {
    var b = bind("web-fullstack@1"); var r = new Role(); r.extendsRole = "dev.code"; r.skills = List.of("i18n-json@1", "react-ts@1"); r.instructions = "日语文案用敬体。";
    b.roles = Map.of("sandbox.dev.code", r); var ov = new StageCfg(); ov.maker = "sandbox.dev.code"; b.stageOverrides = Map.of("DEV", ov);
    pipes.putBinding("sandbox", b, "test");
    var e = pipes.resolve("sandbox"); assertEquals("sandbox.dev.code", e.stage("DEV").maker);
    assertTrue(e.skillText.get("sandbox.dev.code").contains("多语言") && e.skillText.get("sandbox.dev.code").contains("敬体"));
    String id = ingestIssue("新增 roundQ 工具函数（技能注入）", "示例：roundQ(1.25,1) 返回 1.3");
    var t = repo.get(id);
    var agents = new com.x7x24.agents.LlmAgents(new com.x7x24.llm.ModelRouter(new com.x7x24.engine.X7Props(), null, (p, k, m, temp, per) -> null), cfg, pipes);
    var ctx = new com.x7x24.agents.Models.Ctx(t, null, null, null, null, null, null, null, false);
    assertTrue(agents.skillsFor(ctx, "dev.code").contains("React"), "DEV 节点绑定的派生角色技能注入 dev.code 提示词");
    assertEquals("", agents.skillsFor(ctx, "prd.draft"), "其它角色不受影响");
    var bad = bind("web-fullstack@1"); var r2 = new Role(); r2.extendsRole = "dev.code"; r2.schema = "Prd"; bad.roles = Map.of("x.dev", r2);
    assertThrows(PipelineService.Invalid.class, () -> pipes.putBinding("sandbox", bad, "test"), "派生角色不能改 schema");
  }

  @Test void docsOnlyRunsStageChecksAndReleases() {
    pipes.putBinding("sandbox", bind("docs-only@1"), "test");
    cfg.update(Json.tree("{\"autonomy\":{\"tightenDiscards\":10000,\"tightenRollbacks\":10000,\"globalRollbackKill\":10000}}"));
    String id = ingestIssue("新增 kebabQ 工具函数及测试（附加检查）", "请新增 kebabQ 工具函数（src/kebabQ.js），并补充单元测试与文档。示例：kebabQ(\"Hello World\") => \"hello-world\"");
    var t = run(id, x -> x.st() == Status.RELEASED || x.st() == Status.DISCARDED || x.st() == Status.DEGRADED, 200);
    assertEquals(Status.RELEASED, t.st());
    var ev = evidence.latest(id, "stage_checks"); assertNotNull(ev, "QA 附加检查留证据"); assertTrue(ev.toString().contains("markdown-lint"));
  }

  @Test void failingMandatoryHookBlocksAcceptanceAndMakesTheCombinedReportFail() {
    pipes.putBinding("sandbox", bind("docs-only@1"), "test");
    cfg.update(Json.tree("{\"autonomy\":{\"tightenDiscards\":10000,\"tightenRollbacks\":10000,\"globalRollbackKill\":10000}}"));
    String id = ingestIssue("附加检查必须通过 slugify", "新增 slugify 工具函数 src/slugify.js，并补充单元测试与文档。示例：slugify(\"Hello World\") => \"hello-world\"");
    var qa = runTo(id, Status.QA);
    String doc = qa.context.pr.files.stream().filter(p -> p.startsWith("docs/") && p.endsWith(".md")).findFirst().orElseThrow();
    tools.commit(null, "fixture:invalid-link", qa.context.pr.branch, "invalid link", List.of(com.x7x24.adapters.DeliveryTools.FileChange.put(doc, "# Usage\n\n[broken]()\n")));
    assertEquals(Status.DEV, runTo(id, Status.DEV).st(), "CI 成功但 markdown 检查失败必须返修");
    assertFalse(evidence.latest(id, "test_report").path("passed").asBoolean());
    assertTrue(evidence.latest(id, "stage_checks").toString().contains("FAIL"));
    assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND to_status='ACCEPTANCE'", id));
    assertEquals(Status.ACCEPTANCE, runTo(id, Status.ACCEPTANCE).st());
    assertTrue(evidence.latest(id, "test_report").path("passed").asBoolean());
  }

  @Autowired com.x7x24.web.PipelineApi pipelineApi; @Autowired com.x7x24.web.Board boardApi;

  @Test void runningTaskCanBeUpgradedManually() {
    String id = ingestIssue("新增 absQ 工具函数（手动升级配置）", "示例：absQ(-2) 返回 2");
    String s0 = repo.get(id).context.configSnapshot;
    assertThrows(IllegalArgumentException.class, () -> engine.upgradeConfig(id, "op"), "已是最新时拒绝");
    pipes.putBinding("sandbox", bind("docs-only@1"), "test");
    var t = engine.upgradeConfig(id, "op");
    assertNotEquals(s0, t.context.configSnapshot); assertEquals("docs-only@1", engine.eff(t).template);
    assertTrue(audit.list(id, null, 50, 0).stream().anyMatch(e -> "CONFIG_UPGRADED".equals(e.get("event_type"))));
  }

  @Test void dryRunShowsPathRolesAndSkipsWithoutSideEffects() {
    String id = ingestIssue("新增 minQ 工具函数（试运行）", "示例：minQ(1,2) 返回 1");
    run(id, x -> x.st() == Status.DEV || x.st() == Status.SPEC || x.st() == Status.DISCARDED, 60);
    int v0 = pipes.doc().version; String pinned = repo.get(id).context.configSnapshot;
    var draft = bind("docs-only@1"); var r = new Role(); r.extendsRole = "dev.code"; r.skills = List.of("docs-style@1"); draft.roles = Map.of("sandbox.dev.code", r);
    var ov = new StageCfg(); ov.maker = "sandbox.dev.code"; draft.stageOverrides = Map.of("DEV", ov);
    var out = pipelineApi.dryRun("sandbox", Map.of("taskId", id, "binding", Json.M.valueToTree(draft)));
    @SuppressWarnings("unchecked") var path = (List<String>) out.get("path");
    assertFalse(path.contains("SPEC")); assertTrue(((List<?>) out.get("skipped")).contains("SPEC"));
    assertTrue(String.valueOf(out.get("steps")).contains("sandbox.dev.code") && String.valueOf(out.get("steps")).contains("docs-style@1"));
    assertEquals(v0, pipes.doc().version, "试运行不保存配置"); assertEquals(pinned, repo.get(id).context.configSnapshot, "不改任务");
  }

  @Test void boardMarksSkippedStagesForLine() {
    pipes.putBinding("sandbox", bind("docs-only@1"), "test");
    @SuppressWarnings("unchecked") var p = (Map<String, Object>) boardApi.board("sandbox", null, "2000-01-01", "2999-12-31").get("pipeline");
    assertEquals("docs-only@1", p.get("template")); assertEquals(List.of("SPEC", "DESIGN"), p.get("skipped"));
  }
}
