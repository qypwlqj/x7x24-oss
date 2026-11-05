package com.x7x24.it;

import com.x7x24.agents.Committee;
import com.x7x24.core.Status;
import com.x7x24.engine.Engine;
import com.x7x24.gov.ConfigStore;
import com.x7x24.llm.Providers;
import com.x7x24.pipeline.PipelineService;
import com.x7x24.pipeline.Plans;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Import(EngineFailureIT.FailureConfig.class)
class EngineFailureIT extends Harness {
  @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:engine_failure;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1");
  }
  @TestConfiguration
  static class FailureConfig {
    @Bean @Primary FailureCommittee failureCommittee(Providers providers, ConfigStore cfg, PipelineService pipes) {
      var c = new FailureCommittee(providers, cfg); c.pipes = pipes; return c;
    }
  }

  static class FailureCommittee extends Committee {
    final Providers providers; final ConfigStore cfg;
    boolean sameModel; RuntimeException failure;
    FailureCommittee(Providers providers, ConfigStore cfg) { super(providers, cfg); this.providers = providers; this.cfg = cfg; }
    @Override public void assertIsolation() {
      if (sameModel) {
        var p = providers.exec();
        new Committee(new Providers("rule", p, p, List.of(p, p), p), cfg).assertIsolation();
      }
      if (failure != null) throw failure;
      super.assertIsolation();
    }
  }

  @Autowired FailureCommittee committee;
  @Autowired PipelineService pipes;
  @Autowired com.x7x24.chat.TaskPlanApi planApi;
  @Autowired com.x7x24.chat.TaskActions actions;
  @BeforeEach void resetFailure() { committee.sameModel = false; committee.failure = null; tools.repositoryEmpty = false; }
  String inReview(String title) {
    String id = ingestIssue(title, PipelineIT.SLUG);
    assertEquals(Status.PRD_REVIEW, runTo(id, Status.PRD_REVIEW).st()); return id;
  }
  void step() { clk.advance(120_000); engine.tick(); }
  com.fasterxml.jackson.databind.JsonNode nodeRecord(String id, String node) {
    return Json.M.valueToTree(planApi.plan(id)).path("records").path(node);
  }

  @Test void transientQaRecoveryRetriesTheSameNodeWithoutReplanningOrOverwritingItsFailure() {
    String id = inReview("测试接口临时异常：新增 slugify 工具函数");
    var qa = runTo(id, Status.QA); int iteration = qa.currentIteration;
    tools.ciFailure = new IllegalStateException("读取 CI 超时");
    assertBlocked(runTo(id, Status.ANOMALY));
    tools.ciFailure=null;engine.resumeAnomaly(id,"fixture");
    var resumed = repo.get(id);
    assertEquals(Status.QA, resumed.st(), "原样重试必须回到出错的测试节点");
    assertEquals(iteration, resumed.currentIteration, "重试不创建重规划轮次");
    assertEquals("FAILED", nodeRecord(id, "QA").path("state").asText(), "恢复动作不能覆盖测试执行失败");
    assertEquals("RESUMING",engine.recoveryInfo(id).get("state"));
    tools.ciFailure = null;
    assertEquals(Status.ACCEPTANCE, runTo(id, Status.ACCEPTANCE).st());
    assertEquals("PASSED", nodeRecord(id, "QA").path("outcome").asText());
  }

  @Test void failedTestsHaveAnIndependentBusinessOutcomeAndKeepHistoryAcrossCodeVersions() {
    String id = ingestIssue("测试结论与执行结果分离：新增 clamp 工具函数", "新增 clamp 工具函数（src/clamp.js）。示例：clamp(-1, 0, 10) => 0\nx7x24: first-attempt=naive");
    assertEquals(Status.QA, runTo(id, Status.QA).st());
    assertEquals(Status.DEV, run(id, t -> t.st() != Status.QA, 60).st());
    var failed = nodeRecord(id, "QA");
    assertEquals("SUCCEEDED", failed.path("state").asText(), "读取并处理测试报告成功");
    assertEquals("FAILED", failed.path("outcome").asText(), "测试结论仍然是未通过");
    assertFalse(failed.path("valid").asBoolean(true));
    assertEquals(Status.ACCEPTANCE, runTo(id, Status.ACCEPTANCE).st());
    var passed = nodeRecord(id, "QA");
    assertEquals("PASSED", passed.path("outcome").asText()); assertTrue(passed.path("valid").asBoolean());
    assertTrue(passed.path("history").toString().contains("FAILED"), "旧版本未通过的结果保留在历史里");
  }

  @Test void reworkSwitchesStrategyInDevWhileASpecificationGapReplans() {
    String id=inReview("按明确操作恢复：新增 slugify 工具函数");runTo(id,Status.QA);var plan=repo.get(id).plan;
    engine.apply(repo.get(id),com.x7x24.engine.Decision.anomaly("same_failure","同一测试连续失败"));
    step();assertEquals(Status.QA,repo.get(id).st());assertBlocked(repo.get(id));
    engine.adjustPlan(id,repo.get(id).version,"DEV","fixture");assertEquals(Status.DEV,repo.get(id).st());assertEquals(plan,repo.get(id).plan);
    engine.apply(repo.get(id),com.x7x24.engine.Decision.anomaly("spec_gap","缺少验收标准"));
    engine.adjustPlan(id,repo.get(id).version,"PLANNING","fixture");assertEquals(Status.PLANNING,repo.get(id).st());
  }

  @Test void repeatedServiceFailuresStopAtTheOriginalNodeInsteadOfReplanningOrDiscarding() {
    String id = inReview("外部服务持续异常：新增 slugify 工具函数");
    runTo(id, Status.QA); tools.ciFailure = new IllegalStateException("CI 服务持续超时");
    var stopped = run(id, t -> events(id, "OWNER_NEEDED") > 0 || t.st() == Status.DISCARDED, 100);
    assertBlocked(stopped); assertEquals("QA", stopped.currentNode);
    assertTrue(events(id, "OWNER_NEEDED") > 0);
    assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND from_status='ANOMALY' AND to_status='PLANNING'", id));
    assertEquals(1, evidence.list(id).stream().filter(e -> "plan".equals(e.get("kind"))).count());
  }

  @Test void oldDownstreamResultsStayInvalidAfterTheNewPlanHasMovedForward() {
    String id = inReview("重规划版本有效性：新增 slugify 工具函数");
    runTo(id, Status.ACCEPTANCE); var old = nodeRecord(id, "QA");
    engine.apply(repo.get(id), com.x7x24.engine.Decision.anomaly("spec_gap", "验收目标调整"));
    engine.adjustPlan(id,repo.get(id).version,"PLANNING","fixture");assertEquals(Status.PLANNING,repo.get(id).st());
    runTo(id, Status.QA);
    var stale = nodeRecord(id, "QA");
    assertEquals(old.path("id"), stale.path("id")); assertFalse(stale.path("valid").asBoolean(true));
    assertTrue(nodeRecord(id, "PRD_REVIEW").path("valid").asBoolean(), "不受影响的上游结论保留");
    runTo(id, Status.ACCEPTANCE);
    assertTrue(nodeRecord(id, "QA").path("valid").asBoolean());
    assertEquals("PASSED", nodeRecord(id, "QA").path("outcome").asText());
  }

  @Test void historicalHealingRowsAreNotReadAsPassingTestResults() {
    String id = inReview("旧恢复记录兼容：新增 slugify 工具函数");
    runTo(id, Status.QA); tools.ciFailure = new IllegalStateException("CI 读取超时");
    runTo(id, Status.ANOMALY); var t = repo.get(id); var failed = nodeRecord(id, "QA");
    clk.advance(1);
    db.update("INSERT INTO stage_execution (id,task_id,iteration_seq,stage,attempt,operation_key,state,input,output,started_at,ended_at) VALUES (?,?,?,?,?,?,'SUCCEEDED',?,?,?,?)",
        com.x7x24.util.U.uuid(), id, t.currentIteration, "QA", 20, id + ":legacy-heal",
        Json.str(Map.of("node", "QA", "workflowStatus", "ANOMALY", "taskVersion", t.version)),
        Json.str(Map.of("from", "ANOMALY", "to", "PLANNING")), clk.iso(), clk.iso());
    assertEquals(failed.path("id"), nodeRecord(id, "QA").path("id"));
    assertEquals("ERROR", nodeRecord(id, "QA").path("outcome").asText());
    assertEquals("RECOVERED",nodeRecord(id,"ANOMALY").path("outcome").asText());
  }
  long events(String id, String type) { return db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type=?", id, type); }
  com.fasterxml.jackson.databind.JsonNode lastFailure(String id) {
    return Json.tree((String) db.row("SELECT payload FROM task_event WHERE task_id=? AND event_type='AGENT_FAILED' ORDER BY id DESC LIMIT 1", id).get("payload"));
  }

  @Test void isolationErrorPausesOnFirstFailureAndNeverAutoHealsOrDiscards() {
    String id = inReview("模型配置暂停：新增 slugify 工具函数"); committee.sameModel = true;
    step();
    var task = repo.get(id);
    assertBlocked(task); assertEquals("BLOCKED", task.executionState);
    assertEquals("PRD_REVIEW", task.anomalyFrom);
    assertEquals("model_config_error", db.row("SELECT type FROM anomaly WHERE task_id=?", id).get("type"));
    assertEquals("评审委员会至少需要 2 个不同模型", lastFailure(id).path("error").asText());
    assertFalse(lastFailure(id).path("retryable").asBoolean(true));
    assertFalse(lastFailure(id).path("willRetry").asBoolean(true));
    assertEquals(1, events(id, "AGENT_FAILED")); assertEquals(1, events(id, "ANOMALY_OPENED"));
    var blocked = actions.blocked(id);
    assertNotNull(blocked.get("guide"), "配置错误应引导到模型配置页");
    assertEquals("/admin/models", ((Map<?, ?>) blocked.get("guide")).get("href"));
    var suggestions = (List<?>) blocked.get("suggestions");
    assertEquals(List.of("task_resume"), suggestions.stream().map(s -> ((Map<?, ?>) s).get("kind")).toList());
    double spent = task.spentUsd; long tokens = task.spentTokens;
    for (int i = 0; i < 8; i++) { clk.advance(3_600_000); engine.tick(); }
    assertBlocked(repo.get(id)); assertEquals("BLOCKED", repo.get(id).executionState);
    assertEquals(1, events(id, "AGENT_FAILED")); assertEquals(1, events(id, "OWNER_NEEDED"));
    assertEquals(0, events(id, "SELF_HEAL")); assertEquals(0, repo.get(id).healCount);
    assertEquals(spent, repo.get(id).spentUsd); assertEquals(tokens, repo.get(id).spentTokens);
  }

  @Test void fixedConfigurationResumesTheFailedPrdReviewBeforePlanning() {
    String id = inReview("模型配置恢复：新增 slugify 工具函数"); committee.sameModel = true; step();
    assertBlocked(repo.get(id));
    committee.sameModel = false;
    var resumed = engine.resumeAnomaly(id, "operator");
    assertEquals(Status.PRD_REVIEW, resumed.st()); assertEquals("PRD_REVIEW", resumed.currentStage);
    assertEquals(0, resumed.stepAttempts);
    assertEquals(0, db.count("SELECT COUNT(*) FROM anomaly WHERE task_id=? AND status='open'", id));
    step(); assertEquals(Status.PLANNING, repo.get(id).st());
    assertEquals(1, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND from_status='PRD_REVIEW' AND to_status='PLANNING' AND edge='pass'", id));
  }

  @Test void fixingAcceptanceConfigurationPreservesTheFailedStage() {
    String id = inReview("验收配置恢复：新增 slugify 工具函数");
    assertEquals(Status.ACCEPTANCE, runTo(id, Status.ACCEPTANCE).st());
    committee.sameModel = true; step(); assertBlocked(repo.get(id));
    assertEquals("ACCEPTANCE", repo.get(id).anomalyFrom);
    committee.sameModel = false;
    var resumed = engine.resumeAnomaly(id, "operator");
    assertEquals(Status.ACCEPTANCE, resumed.st()); assertEquals("ACCEPTANCE", resumed.currentStage);
    step(); assertEquals(Status.DELIVERED, repo.get(id).st());
  }

  @Test void wrappedIsolationErrorAlsoStopsAndKeepsTheUsefulCause() {
    String id = inReview("包装异常暂停：新增 slugify 工具函数");
    committee.failure = new IllegalStateException("评审执行失败", new Committee.IsolationError("评审委员会至少需要 2 个不同模型"));
    step(); assertBlocked(repo.get(id));
    assertFalse(lastFailure(id).path("retryable").asBoolean(true));
    assertEquals("评审委员会至少需要 2 个不同模型", lastFailure(id).path("error").asText());
  }

  @Test void fixedConfigurationResumesACustomReviewNodeWithoutSkippingIt() {
    var adj = new Plans.Adj(); adj.op = "insertStage"; adj.stage = "SEC_AUDIT"; adj.after = "QA"; adj.label = "安全审计"; adj.source = "governance";
    var ctx = new com.x7x24.pipeline.Assembler.Ctx(List.of("backend"), List.of(), null, Map.of("SEC_AUDIT", "测试：必须做安全审计"), "low", "L2");
    String snap = Tenant.run(Tenant.LEGACY, () -> pipes.assemble("sandbox", List.of(adj), "backend", ctx, true, true).eff().id);
    String id = engine.ingest(new Engine.Ingest("sandbox", "安全审计配置恢复：新增 slugify 工具函数", PipelineIT.SLUG, "manual", "review-node-fixture", null, false, null, "sandbox-default", "示例：slugify(\"Hello World\") => \"hello-world\"", snap)).taskId();
    var before = run(id, t -> "SEC_AUDIT".equals(t.currentStage) || Set.of(Status.DISCARDED, Status.RELEASED).contains(t.st()), 120);
    assertEquals("SEC_AUDIT", before.currentStage);
    committee.sameModel = true; step(); assertBlocked(repo.get(id));
    committee.sameModel = false;
    var resumed = engine.resumeAnomaly(id, "operator");
    assertEquals(Status.ACCEPTANCE, resumed.st()); assertEquals("SEC_AUDIT", resumed.currentStage); assertEquals("SEC_AUDIT", resumed.currentNode);
    step();
    assertEquals(1, db.count("SELECT COUNT(*) FROM verdict WHERE task_id=? AND gate='sec_audit' AND decision='pass'", id));
    assertEquals("ACCEPTANCE", repo.get(id).currentStage);
  }

  @Test void transientPrdFailuresKeepBoundedRetriesAndDoNotSkipReviewAfterHealing() {
    String id = inReview("临时异常重试：新增 slugify 工具函数");
    committee.failure = new IllegalStateException("调用模型超时");
    step(); assertEquals(Status.PRD_REVIEW, repo.get(id).st());
    assertEquals("RETRYING", repo.get(id).executionState); assertEquals(1, repo.get(id).stepAttempts);
    assertTrue(lastFailure(id).path("retryable").asBoolean()); assertTrue(lastFailure(id).path("willRetry").asBoolean());
    for (int i = 1; i < cfg.get().loops.maxStepAttempts; i++) step();
    assertBlocked(repo.get(id));
    assertEquals(cfg.get().loops.maxStepAttempts, events(id, "AGENT_FAILED"));
    assertTrue(lastFailure(id).path("retryable").asBoolean()); assertFalse(lastFailure(id).path("willRetry").asBoolean(true));
    assertEquals("agent_error", db.row("SELECT type FROM anomaly WHERE task_id=?", id).get("type"));
    step(); assertEquals(Status.PRD_REVIEW, repo.get(id).st(), "自动处理后仍需通过 PRD 评审");
    committee.failure=null;engine.resumeAnomaly(id,"fixture");step();assertEquals(Status.PLANNING,repo.get(id).st());
  }

  @Test void emptyRepositoryWaitsBeforeCodingAndResumesWithoutLosingThePlan() {
    String id = inReview("空仓库规划：新增 slugify 工具函数");
    tools.repositoryEmpty = true;
    assertEquals(Status.DEV, runTo(id, Status.DEV).st());
    var planned = repo.get(id).plan;
    double spent = repo.get(id).spentUsd;
    int commits = tools.commits.get();
    step();
    assertBlocked(repo.get(id));
    assertEquals("DEV", repo.get(id).anomalyFrom);
    assertEquals("repository_not_initialized", db.row("SELECT type FROM anomaly WHERE task_id=?", id).get("type"));
    assertFalse(lastFailure(id).path("retryable").asBoolean(true));
    assertFalse(lastFailure(id).path("willRetry").asBoolean(true));
    var blocked = actions.blocked(id);
    assertEquals(List.of("repository_initialize_and_resume", "task_resume"), ((List<?>) blocked.get("suggestions")).stream().map(s -> ((Map<?, ?>) s).get("kind")).toList());
    for (int i = 0; i < 6; i++) { clk.advance(3_600_000); engine.tick(); }
    assertBlocked(repo.get(id));
    assertEquals(1, events(id, "AGENT_FAILED")); assertEquals(0, events(id, "SELF_HEAL"));
    assertEquals(commits, tools.commits.get()); assertEquals(spent, repo.get(id).spentUsd);
    tools.repositoryEmpty = false;
    var resumed = engine.resumeAnomaly(id, "operator");
    assertEquals(Status.DEV, resumed.st()); assertEquals("DEV", resumed.currentStage);
    assertEquals(planned, resumed.plan); assertEquals(0, resumed.stepAttempts);
    assertEquals(Status.QA, runTo(id, Status.QA).st());
    assertTrue(tools.commits.get() > commits, "初始化后应继续编码和提交");
  }
  @Autowired com.x7x24.engine.ProjectRepo projects;
  @Test void automaticInitializationUsesCurrentProjectAuthorizationAndKeepsThePlan() {
    String id = inReview("自动初始化：新增 slugify 工具函数");
    tools.repositoryEmpty = true; assertEquals(Status.DEV, runTo(id, Status.DEV).st());
    var planned = repo.get(id).plan; String projectId = repo.get(id).projectId;
    assertFalse(repo.get(id).context.projectSnapshot.autoInitializeRepository);
    try {
      projects.update(projectId, Json.tree("{\"config\":{\"autoInitializeRepository\":true}}"));
      step();
      assertEquals(1, tools.repositoryInitializations.get()); assertTrue(tools.main.containsKey("README.md"));
      assertEquals(Status.QA, runTo(id, Status.QA).st()); assertEquals(planned, repo.get(id).plan);
      assertEquals(1, events(id, "REPOSITORY_INITIALIZED"));
      assertFalse(repo.get(id).context.projectSnapshot.autoInitializeRepository, "旧快照保留，运行时读取当前授权");
    } finally { projects.update(projectId, Json.tree("{\"config\":{\"autoInitializeRepository\":false}}")); }
  }
  @Test void revokedAutomaticInitializationIsNotRestoredByAnOldSnapshot() {
    String id = inReview("撤回自动初始化：新增 slugify 工具函数");
    assertEquals(Status.DEV, runTo(id, Status.DEV).st());
    var t = repo.get(id); t.context.projectSnapshot.autoInitializeRepository = true; assertTrue(repo.save(t));
    tools.repositoryEmpty = true; step();
    assertBlocked(repo.get(id)); assertEquals(0, tools.repositoryInitializations.get());
  }
}
