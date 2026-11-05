package com.x7x24.it;

import com.x7x24.chat.ChatService;
import com.x7x24.chat.TaskActions;
import com.x7x24.core.Status;
import com.x7x24.tenant.Tenant;
import com.x7x24.tenant.WorkspaceService;
import com.x7x24.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 指挥台 v2 M3：会话里的流程操作——权限、不可跳过的节点、只读拒绝、乐观锁冲突、可恢复的放弃 */
class TaskActionsIT extends Harness {
  @org.springframework.test.context.DynamicPropertySource static void isolatedDatabase(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:task_actions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1");
  }
  @Autowired TaskActions actions;
  @Autowired com.x7x24.web.CatalogApi catalog;
  static final TaskActions.Ctx VIEWER = new TaskActions.Ctx("vic", "viewer"), MEMBER = new TaskActions.Ctx("mia", "member"), MEMBER2 = new TaskActions.Ctx("max", "member"), ADMIN = new TaskActions.Ctx("ada", "admin");
  @BeforeEach void ws() { Tenant.set(Tenant.LEGACY); }
  @AfterEach void clear() { Tenant.clear(); }
  String task(String t) { String id = ingestIssue(t, "示例：f(1) => 2"); run(id, x -> !x.status.equals("BACKLOG"), 20); return id; }
  String propose(String id, String kind, String json, TaskActions.Ctx who) { return (String) actions.propose(id, kind, Json.tree(json), "button", null, who).get("id"); }
  long events(String id, String type) { return db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type=?", id, type); }

  @Test void readOnlyCannotProposeOrConfirm() {
    String id = task("只读用例：新增 inc 函数");
    assertThrows(WorkspaceService.Forbidden.class, () -> propose(id, TaskActions.PAUSE, "{}", VIEWER));
    String a = propose(id, TaskActions.PAUSE, "{}", MEMBER);
    assertThrows(WorkspaceService.Forbidden.class, () -> actions.confirm(id, a, null, VIEWER), "服务端在确认时再校验角色");
    assertEquals("proposed", actions.get(id, a).get("status")); assertEquals(0, repo.get(id).paused);
    assertNull(actions.parse(id, "为什么卡住了？").kind(), "只读可以提问");
  }

  @Test void memberPauseWritesTimelineEventAndIsIdempotent() {
    String id = task("暂停用例：新增 dec 函数");
    String a = propose(id, TaskActions.PAUSE, "{}", MEMBER);
    assertEquals("executed", actions.confirm(id, a, null, MEMBER).get("status"));
    assertEquals(1, repo.get(id).paused); assertEquals(1, events(id, "TASK_ACTION_EXECUTED"));
    actions.confirm(id, a, null, MEMBER); assertEquals(1, events(id, "TASK_ACTION_EXECUTED"), "重复确认幂等");
    String r = propose(id, TaskActions.RESUME, "{}", MEMBER); actions.confirm(id, r, null, MEMBER); assertEquals(0, repo.get(id).paused);
  }

  @Test void securitySqlAndAcceptanceCannotBeSkipped() {
    String id = task("跳过用例：新增 neg 函数");
    for (String n : new String[]{"SEC_AUDIT", "CHECK_SQL_SAFETY", "ACCEPTANCE", "QA"})
      assertThrows(IllegalArgumentException.class, () -> propose(id, TaskActions.SKIP, "{\"nodeId\":\"" + n + "\",\"reason\":\"赶时间\"}", ADMIN), n + " 不可跳");
    String a = propose(id, TaskActions.SKIP, "{\"nodeId\":\"CHECK_MD\",\"reason\":\"文档改动另行检查\"}", MEMBER);
    assertThrows(WorkspaceService.Forbidden.class, () -> actions.confirm(id, a, "跳过", MEMBER), "跳过需要管理员");
    assertEquals("admin", actions.get(id, a).get("blockedRole")); assertTrue(actions.awaitingManager().stream().anyMatch(x -> a.equals(x.get("id"))), "进管理员的待我处理");
    assertThrows(IllegalArgumentException.class, () -> actions.confirm(id, a, null, ADMIN), "高危操作需要二次确认");
    assertEquals("executed", actions.confirm(id, a, "跳过", ADMIN).get("status"));
    assertTrue(repo.get(id).context.skippedChecks.contains("markdown-lint"));
    assertEquals(1, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='STAGE_SKIPPED' AND actor='ada'", id));
  }

  @Test void optimisticLockConflictAndSinglePending() {
    String id = task("并发用例：新增 sq 函数");
    String a = propose(id, TaskActions.PAUSE, "{}", MEMBER);
    var ex = assertThrows(ChatService.Conflict.class, () -> propose(id, TaskActions.RETRY, "{}", MEMBER2)); assertTrue(ex.getMessage().contains("@mia"));
    var t = repo.get(id); t.paused = 1; assertTrue(repo.save(t)); // 别人先暂停了
    var c = assertThrows(ChatService.Conflict.class, () -> actions.confirm(id, a, null, MEMBER)); assertTrue(c.getMessage().contains("刷新"));
    assertEquals("stale", actions.get(id, a).get("status"));
  }

  @Test void abandonNeedsAdminAndIsRecoverable() {
    String id = task("放弃用例：新增 abs 函数");
    assertEquals(false, actions.blocked(id).get("canRestore"));
    String a = propose(id, TaskActions.ABANDON, "{\"reason\":\"需求取消\"}", MEMBER);
    assertThrows(WorkspaceService.Forbidden.class, () -> actions.confirm(id, a, "放弃", MEMBER));
    actions.confirm(id, a, "放弃", ADMIN);
    var t = repo.get(id); assertEquals(Status.DISCARDED, t.st()); assertEquals("manual_abandon", t.discardReason); assertNull(t.deletedAt, "不删除");
    assertEquals(true, actions.blocked(id).get("canRestore"));
    String r = propose(id, TaskActions.RESTORE, "{}", ADMIN); actions.confirm(id, r, null, ADMIN);
    assertEquals(Status.PRD_REVIEW,repo.get(id).st(),"恢复原节点，不回需求池");
    assertEquals(false, actions.blocked(id).get("canRestore"));
  }

  @Test void automaticDiscardsNeverAdvertiseOrAllowConversationRestore() {
    String id=task("历史自动废弃可恢复：新增 abs 函数");
    for(String reason:java.util.Arrays.asList(null,"forbidden_scope","infeasible","needs_clarification")) {
      var t=repo.get(id);t.status=Status.DISCARDED.name();t.discardReason=reason;t.nextRunAt=null;assertTrue(repo.save(t));
      assertEquals(true,actions.blocked(id).get("canRestore"));
      String action=propose(id,TaskActions.RESTORE,"{}",ADMIN);actions.confirm(id,action,null,ADMIN);
      assertEquals(Status.PRD_REVIEW,repo.get(id).st());
    }
  }

  @Test void freeTextRulesMapToWhitelistedActions() {
    String id = task("解析用例：新增 max 函数");
    assertEquals(TaskActions.PAUSE, actions.parse(id, "先暂停").kind());
    assertEquals(TaskActions.RETRY, actions.parse(id, "重试，别加新依赖").kind());
    assertEquals("别加新依赖", actions.parse(id, "重试，别加新依赖").args().path("hint").asText());
    assertNull(actions.parse(id, "把预算调到 10 美元").kind(), "预算不在会话里改");
    assertEquals(TaskActions.REDIRECT, actions.parse(id, "导出要带学号列").kind(), "其它话当成补充要求，仍需确认");
    assertTrue(actions.blocked(id).containsKey("lockKey"));
    Map<String, Object> b = actions.blocked(id); assertEquals(false, b.get("blocked"));
  }

  @Test void blockedCardHasCauseAndSuggestionsAndRetryResumes() {
    String id = ingestIssue("阻塞用例 slugify " + java.util.UUID.randomUUID(), PipelineIT.SLUG); runTo(id, Status.DEV);
    var t0 = repo.get(id); assertTrue(Status.ANOMALY_FROM.contains(t0.st()), "status=" + t0.status + " " + t0.discardReason);
    engine.apply(t0, com.x7x24.engine.Decision.anomaly("agent_error", "模型连续超时")); assertBlocked(repo.get(id));
    var b = actions.blocked(id); assertEquals(true, b.get("blocked"));
    var sug = (java.util.List<?>) b.get("suggestions"); assertTrue(sug.size() >= 1 && sug.size() <= 3);
    assertEquals(TaskActions.RETRY, ((Map<?, ?>) sug.get(0)).get("kind"));
    String a = propose(id, TaskActions.RETRY, "{\"hint\":\"换个实现\"}", MEMBER); actions.confirm(id, a, null, MEMBER);
    assertNotEquals(Status.ANOMALY, repo.get(id).st());
  }

  String blockedAtTwelfthSegment(String cause) {
    String id = ingestIssue("预算恢复 slugify " + java.util.UUID.randomUUID(), PipelineIT.SLUG);
    assertEquals(Status.DEV, runTo(id, Status.DEV).st());
    var t = repo.get(id);
    var parts = new java.util.ArrayList<Map<String, Object>>();
    for (int i = 0; i < 14; i++) parts.add(Map.of("title", "segment-" + i, "files", java.util.List.of("src/part" + i + ".js")));
    ((com.fasterxml.jackson.databind.node.ObjectNode) t.plan).set("subtasks", Json.M.valueToTree(parts));
    t.context.subtaskIdx = 11; t.budgetTokens = 2084668;
    t.context.failures.add(new com.x7x24.engine.TaskCtx.Failure("DEV", "forbidden_scope", "forbidden_scope", clk.iso()));
    t.context.failures.add(new com.x7x24.engine.TaskCtx.Failure("DEV", "budget", "任务预算用完：单任务Token 2138927.0 / 2084668.0", clk.iso()));
    assertTrue(repo.save(t));
    db.update("UPDATE task SET spent_tokens=2138927 WHERE id=?", id);
    engine.apply(repo.get(id), com.x7x24.engine.Decision.block(cause, "DEV 超过 8h 未完成（心跳缺失）"));
    return id;
  }

  @Test void exhaustedBudgetTakesPriorityOverTimeoutAndRetryStillChecksTheBudget() {
    String id = blockedAtTwelfthSegment("timeout"); var before = repo.get(id);
    var b = Json.M.valueToTree(actions.blocked(id));
    assertEquals("timeout", b.path("cause").asText(), "保留原停止原因");
    assertEquals("budget_paused", b.path("resumeBlocker").path("code").asText());
    assertTrue(b.path("resumeBlocker").path("detail").asText().contains("2,138,927"));
    assertEquals("/t/" + id + "/budget", b.path("guide").path("href").asText());
    assertTrue(b.path("guide").path("recommended").asBoolean());
    assertTrue(b.path("suggestions").isEmpty(), "限制解除前不推荐无法执行的恢复动作");
    assertTrue(b.path("evidence").isEmpty(), "历史失败不再混入当前证据");
    assertEquals("budget", b.path("failureHistory").get(0).path("code").asText());
    assertFalse(b.path("failureHistory").get(0).path("at").asText().isBlank());
    assertEquals("forbidden_scope", b.path("failureHistory").get(1).path("code").asText());
    assertEquals(before.version, repo.get(id).version, "读取推荐不改变任务");
    String a = propose(id, TaskActions.RETRY, "{}", MEMBER);
    assertThrows(IllegalArgumentException.class, () -> actions.confirm(id, a, null, MEMBER));
    var stopped = repo.get(id); assertBlocked(stopped);
    assertEquals(11, stopped.context.subtaskIdx); assertEquals(before.plan, stopped.plan);
    assertEquals(2138927, stopped.spentTokens); assertEquals(0, events(id, "NODE_RETRY_REQUESTED"));
  }

  @Test void historicalBudgetFailuresDoNotPreventRetryOnceTheBudgetIsSufficient() {
    String id = blockedAtTwelfthSegment("timeout"); var t = repo.get(id);
    t.budgetTokens = 4000000; assertTrue(repo.save(t));
    var b = Json.M.valueToTree(actions.blocked(id));
    assertTrue(b.has("resumeBlocker")); assertTrue(b.path("resumeBlocker").isNull());
    assertEquals("timeout", b.path("cause").asText()); assertFalse(b.has("guide"));
    assertEquals(TaskActions.RETRY, b.path("suggestions").get(0).path("kind").asText());
    assertEquals("budget", b.path("failureHistory").get(0).path("code").asText());
    t = repo.get(id); t.context.failures.clear(); assertTrue(repo.save(t));
    assertEquals(b.path("suggestions"), Json.M.valueToTree(actions.blocked(id)).path("suggestions"), "纯超时仍可重试");
  }

  @Test void clearingTheBudgetRestrictionAllowsResumeAtTheSavedTwelfthSegment() {
    String id = blockedAtTwelfthSegment("budget_paused"); var t = repo.get(id); var plan = t.plan;
    t.budgetTokens = 4000000; assertTrue(repo.save(t));
    var b = Json.M.valueToTree(actions.blocked(id));
    assertTrue(b.path("resumeBlocker").isNull()); assertFalse(b.has("guide"));
    assertEquals(TaskActions.RESUME, b.path("suggestions").get(0).path("kind").asText());
    assertTrue(b.path("suggestions").get(0).path("why").asText().contains("已解除"));
    actions.confirm(id, propose(id, TaskActions.RESUME, "{}", MEMBER), null, MEMBER);
    var resumed = repo.get(id);
    assertEquals(Status.DEV, resumed.st()); assertNotNull(resumed.nextRunAt);
    assertEquals(11, resumed.context.subtaskIdx); assertEquals(plan, resumed.plan);
    assertEquals(2138927, resumed.spentTokens);
    assertEquals("RESUMING", engine.recoveryInfo(id).get("state"));
  }

  @Test void unlimitedBudgetIgnoresTokenLimitsButStillReportsTheLiveSafetyCap() {
    String id = blockedAtTwelfthSegment("timeout"); var t = repo.get(id);
    t.context.budgetOff = true; assertTrue(repo.save(t));
    assertTrue(Json.M.valueToTree(actions.blocked(id)).path("resumeBlocker").isNull());
    db.update("UPDATE task SET spent_iterations=? WHERE id=?", budget.switches(t).safetyMaxIterations(), id);
    var b = Json.M.valueToTree(actions.blocked(id));
    assertEquals("timeout", b.path("cause").asText());
    assertEquals("safety_cap", b.path("resumeBlocker").path("code").asText());
    assertTrue(b.path("resumeBlocker").path("detail").asText().contains("安全上限"));
    assertTrue(b.path("suggestions").isEmpty());
  }

  @Test void blockedTasksRejectPauseFromBothEntryPointsAndDoNotRepeatConfigurationEvidence() {
    String id=task("阻塞暂停：新增 clamp 工具函数");
    engine.apply(repo.get(id),com.x7x24.engine.Decision.anomaly("model_config_error","需要两个不同模型"));
    assertThrows(IllegalArgumentException.class,()->propose(id,TaskActions.PAUSE,"{}",MEMBER));
    assertThrows(IllegalArgumentException.class,()->catalog.pauseTask(id,Json.tree("{\"paused\":true}")));
    var blocked=actions.blocked(id);
    assertEquals("需要两个不同模型",blocked.get("detail"));
    assertTrue(((java.util.List<?>)blocked.get("evidence")).isEmpty());
    assertEquals(0,repo.get(id).paused);
  }
  @Test void pauseConfirmationRechecksExecutionStateEvenWhenTheStageHasNotChanged() {
    String id=task("暂停确认状态：新增 clamp 工具函数");
    String a=propose(id,TaskActions.PAUSE,"{}",MEMBER);
    var t=repo.get(id);t.executionState="BLOCKED";assertTrue(repo.save(t));
    assertThrows(ChatService.Conflict.class,()->actions.confirm(id,a,null,MEMBER));
    assertEquals(0,repo.get(id).paused);assertEquals("stale",actions.get(id,a).get("status"));
  }
  String emptyRepositoryTask() {
    String id = ingestIssue("初始化操作 slugify " + java.util.UUID.randomUUID(), PipelineIT.SLUG);
    assertEquals(Status.DEV, runTo(id, Status.DEV).st()); tools.repositoryEmpty = true;
    assertBlocked(runTo(id, Status.ANOMALY)); return id;
  }
  @Test void repositoryInitializationRequiresAdminAndUsesOnlyServerGeneratedContent() {
    String id = emptyRepositoryTask(); var planned = repo.get(id).plan;
    assertThrows(WorkspaceService.Forbidden.class, () -> propose(id, TaskActions.INITIALIZE, "{}", VIEWER));
    String a = propose(id, TaskActions.INITIALIZE, "{\"path\":\".github/workflows/evil.yml\",\"content\":\"evil\",\"branch\":\"other\"}", MEMBER);
    var params = (com.fasterxml.jackson.databind.JsonNode) actions.get(id, a).get("params");
    assertEquals("README.md", params.path("path").asText()); assertEquals("main", params.path("branch").asText());
    assertFalse(params.path("content").asText().contains("evil"));
    assertThrows(WorkspaceService.Forbidden.class, () -> actions.confirm(id, a, null, MEMBER));
    assertEquals(0, tools.repositoryInitializations.get());
    actions.confirm(id, a, null, ADMIN); assertEquals(Status.DEV, repo.get(id).st());
    assertEquals(planned, repo.get(id).plan); assertEquals(1, tools.repositoryInitializations.get());
    assertEquals(params.path("content").asText(), tools.main.get("README.md"));
    actions.confirm(id, a, null, ADMIN); assertEquals(1, tools.repositoryInitializations.get());
    assertEquals(1, events(id, "REPOSITORY_INITIALIZED"));
    assertEquals(Status.QA, runTo(id, Status.QA).st());
    assertEquals(TaskActions.INITIALIZE, actions.parse(id, "初始化仓库并继续").kind());
  }
  @Test void initializationFailureRemainsBlockedAndCanBeRetriedAfterFixingPermissions() {
    String id = emptyRepositoryTask(); var planned = repo.get(id).plan;
    tools.initializationFailure = new com.x7x24.adapters.DeliveryTools.GateBlocked("repository_not_initialized", "Contents 权限不足");
    String a = propose(id, TaskActions.INITIALIZE, "{}", ADMIN);
    assertThrows(com.x7x24.adapters.DeliveryTools.GateBlocked.class, () -> actions.confirm(id, a, null, ADMIN));
    assertEquals("failed", actions.get(id, a).get("status")); assertBlocked(repo.get(id));
    assertEquals(planned, repo.get(id).plan); assertEquals(0, events(id, "REPOSITORY_INITIALIZED"));
    tools.initializationFailure = null;
    actions.confirm(id, propose(id, TaskActions.INITIALIZE, "{}", ADMIN), null, ADMIN);
    assertEquals(Status.DEV, repo.get(id).st());
  }
  @Test void initializationRechecksItsPreviewAndAcceptsManualInitializationWithoutAnotherCommit() {
    String id = emptyRepositoryTask(); String a = propose(id, TaskActions.INITIALIZE, "{}", ADMIN);
    var task = repo.get(id); task.context.projectSnapshot.baseBranch = "other"; assertTrue(repo.save(task));
    assertThrows(ChatService.Conflict.class, () -> actions.confirm(id, a, null, ADMIN));
    assertEquals(0, tools.repositoryInitializations.get()); assertBlocked(repo.get(id));
    task = repo.get(id); task.context.projectSnapshot.baseBranch = "main"; assertTrue(repo.save(task));
    tools.repositoryEmpty = false;
    actions.confirm(id, propose(id, TaskActions.INITIALIZE, "{}", ADMIN), null, ADMIN);
    assertEquals(Status.DEV, repo.get(id).st()); assertEquals(0, tools.repositoryInitializations.get());
    assertThrows(IllegalArgumentException.class, () -> propose(id, TaskActions.INITIALIZE, "{}", ADMIN));
  }
}
