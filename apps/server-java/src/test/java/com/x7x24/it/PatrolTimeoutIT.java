package com.x7x24.it;

import com.x7x24.adapters.DeliveryTools.FileChange;
import com.x7x24.agents.Models.*;
import com.x7x24.core.Status;
import com.x7x24.engine.*;
import com.x7x24.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real recovery, segment commits and patrol, with a controllable clock and in-memory GitHub. */
class PatrolTimeoutIT extends Harness {
  @org.springframework.test.context.DynamicPropertySource static void isolated(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:patrol_timeout;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1");
  }
  Field agentsField;
  ExecAgents originalAgents;

  @BeforeEach void deterministicSegments() throws Exception {
    cfg.update(Json.tree("{\"thresholds\":{\"similarityDup\":1},\"autonomy\":{\"tightenDiscards\":10000,\"tightenRollbacks\":10000,\"globalRollbackKill\":10000}}"));
    agentsField = Engine.class.getDeclaredField("agents"); agentsField.setAccessible(true);
    originalAgents = (ExecAgents) agentsField.get(engine);
    agentsField.set(engine, java.lang.reflect.Proxy.newProxyInstance(ExecAgents.class.getClassLoader(), new Class<?>[]{ExecAgents.class}, (self, method, args) -> {
      if (!method.getName().equals("code")) return method.invoke(originalAgents, args);
      int part = ((Ctx) args[0]).task().context.subtaskIdx;
      return new CodeChange(List.of(new FileChange("src/part" + part + ".js", "module.exports = " + part + ";", false, 1)), "feat: segment", "segment", "fixture");
    }));
  }
  @AfterEach void restoreAgents() throws Exception { if (agentsField != null && originalAgents != null) agentsField.set(engine, originalAgents); }

  String development() {
    String id = ingestIssue("新增 slugify 超时回归 " + UUID.randomUUID(), PipelineIT.SLUG);
    assertEquals(Status.DEV, runTo(id, Status.DEV).st());
    var t = repo.get(id); var plan = Json.convert(t.plan, Plan.class);
    var parts = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 14; i++) parts.add(Map.of("title", "segment-" + i, "files", List.of("src/part" + i + ".js")));
    t.plan = Json.M.valueToTree(new Plan(plan.path(), plan.template(), plan.strategy(), plan.scopeLevel(), plan.risk(), plan.files(), parts));
    t.context.subtaskIdx = 12; assertTrue(repo.save(t)); return id;
  }
  String progress(Task t) { return Json.M.valueToTree(t.context).path("lastProgressAt").asText(null); }
  void legacy(String id) {
    var t = repo.get(id); var ctx = (com.fasterxml.jackson.databind.node.ObjectNode) Json.M.valueToTree(t.context);
    ctx.remove("lastProgressAt"); t.context = Json.convert(ctx, TaskCtx.class); assertTrue(repo.save(t));
  }
  void waitWithoutProgress(String id) { engine.apply(repo.get(id), Decision.stay(60)); }
  long signals(String id) { return db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='SIGNAL_SENT'", id); }
  void commitPart(String id) throws Exception {
    var dev = Engine.class.getDeclaredMethod("onDev", Task.class); dev.setAccessible(true);
    var t = repo.get(id); engine.apply(t, (Decision) dev.invoke(engine, t));
  }

  @Test void resumedThirteenthAndFourteenthSegmentsCompleteWithoutAnotherTimeout() {
    String id = development(); String phaseStarted = repo.get(id).statusChangedAt;
    clk.advance(9 * U.HOUR);
    engine.apply(repo.get(id), Decision.block("timeout", "原 DEV 超时"));
    var before = repo.get(id); var plan = before.plan; String snapshot = before.context.configSnapshot;
    var resumed = engine.resumeTask(id, before.version, "fixture");
    engine.tick(); var thirteenth = repo.get(id);
    assertEquals(13, thirteenth.context.subtaskIdx); assertEquals(1, tools.commits.get());
    assertTrue(thirteenth.context.signals.isEmpty(), "刚提交第 13/14 段不能立即产生新的超时信号");
    assertFalse(RecoveryService.stopped(thirteenth)); assertNotNull(progress(resumed));
    assertTrue(Clk.parse(progress(resumed)) > Clk.parse(phaseStarted));
    assertEquals(phaseStarted, thirteenth.statusChangedAt, "阶段开始时间继续用于历史耗时");
    clk.advance(10_000); engine.tick(); var finished = repo.get(id);
    assertEquals(Status.QA, finished.st()); assertEquals(2, tools.commits.get()); assertEquals(1, tools.prOpened.get());
    assertEquals(0, signals(id)); assertEquals(plan, finished.plan); assertEquals(snapshot, finished.context.configSnapshot);
    assertEquals(before.spentTokens, finished.spentTokens); assertEquals(before.spentUsd, finished.spentUsd);
    assertEquals("RESOLVED", db.row("SELECT state FROM task_recovery WHERE id=?", before.context.recoveryId).get("state"));
  }

  @Test void aSuccessfulSegmentRefreshesIdleTimeEvenWhenThePhaseIsNineHoursOld() {
    String id = development(); String phaseStarted = repo.get(id).statusChangedAt;
    clk.advance(9 * U.HOUR); engine.tick(); var t = repo.get(id);
    assertEquals(13, t.context.subtaskIdx); assertEquals(1, tools.commits.get());
    assertTrue(t.context.signals.isEmpty(), "分段推进属于有效进展");
    assertNotNull(progress(t)); assertTrue(Clk.parse(progress(t)) > Clk.parse(phaseStarted));
    assertEquals(phaseStarted, t.statusChangedAt);
  }

  @Test void pollingDoesNotRefreshIdleTimeAndAGenuineStallEmitsOnlyOneSignal() {
    String id = development(); String started = progress(repo.get(id));
    clk.advance(7 * U.HOUR + 59 * U.MIN); waitWithoutProgress(id); engine.watchdog();
    assertTrue(repo.get(id).context.signals.isEmpty());
    clk.advance(2 * U.MIN); waitWithoutProgress(id); engine.watchdog(); engine.watchdog();
    var t = repo.get(id);
    assertEquals(started, progress(t)); assertEquals(1, t.context.signals.size()); assertEquals(1, signals(id));
    var signal = Json.M.valueToTree(t.context.signals.getFirst());
    assertEquals("DEV", signal.path("observedStage").asText());
    assertEquals(started, signal.path("observedProgressAt").asText());
    assertEquals(t.context.recoveryGeneration, signal.path("observedRecoveryGeneration").asInt());
    assertTrue(t.context.signals.getFirst().detail.contains("没有有效进展"));
    assertFalse(t.context.signals.getFirst().detail.contains("心跳缺失"));
    engine.tick(); assertBlocked(repo.get(id)); assertEquals("timeout", engine.recoveryInfo(id).get("code"));
    engine.watchdog(); assertEquals(1, signals(id));
  }

  @Test void resumeDropsOldTimeoutsButKeepsOtherSignals() {
    String id = development(); var t = repo.get(id);
    t.context.signals.add(new TaskCtx.Sig("ANOMALY", "timeout", "旧超时"));
    t.context.signals.add(new TaskCtx.Sig("ANOMALY", "model_config_error", "仍需核对配置")); assertTrue(repo.save(t));
    engine.apply(repo.get(id), Decision.block("timeout", "原执行已停"));
    var stopped = repo.get(id); var resumed = engine.resumeTask(id, stopped.version, "fixture");
    assertEquals(1, resumed.context.signals.size()); assertEquals("model_config_error", resumed.context.signals.getFirst().anomalyType);
    assertEquals(stopped.context.recoveryGeneration + 1, resumed.context.recoveryGeneration);
  }

  @Test void failedResumeDoesNotChangeTheClockOrDropSignals() {
    String id = development(); var t = repo.get(id);
    t.context.signals.add(new TaskCtx.Sig("ANOMALY", "timeout", "旧超时")); assertTrue(repo.save(t));
    engine.apply(repo.get(id), Decision.block("timeout", "原执行已停"));
    db.update("UPDATE task SET spent_tokens=budget_tokens+1 WHERE id=?", id);
    var before = repo.get(id);
    assertThrows(IllegalArgumentException.class, () -> engine.resumeTask(id, before.version, "fixture"));
    var after = repo.get(id);
    assertEquals(progress(before), progress(after)); assertEquals(before.version, after.version);
    assertEquals(Json.str(before.context.signals), Json.str(after.context.signals)); assertBlocked(after);
  }

  @Test void failedDevelopmentRetriesDoNotRefreshTheIdleClock() {
    String id = development(); String started = progress(repo.get(id)); assertNotNull(started);
    clk.advance(U.HOUR);
    tools.developmentFailure = new com.x7x24.adapters.DeliveryTools.GateBlocked("agent_error", "临时执行错误");
    engine.tick(); assertEquals(1, repo.get(id).stepAttempts);
    clk.advance(U.MIN); engine.tick(); var retried = repo.get(id);
    assertEquals(2, retried.stepAttempts); assertEquals(started, progress(retried));
    assertEquals(0, tools.commits.get()); assertEquals(0, signals(id));
  }

  @Test void aTimeoutQueuedBeforeSuccessfulProgressIsDiscardedAtConsumption() throws Exception {
    String id = development(); clk.advance(9 * U.HOUR); waitWithoutProgress(id); engine.watchdog();
    assertEquals(1, repo.get(id).context.signals.size());
    // A step already in flight can finish after patrol's observation.
    commitPart(id); var committed = repo.get(id); String progressed = progress(committed);
    assertEquals(13, committed.context.subtaskIdx); assertEquals(1, tools.commits.get());
    clk.advance(10_000); engine.tick(); var t = repo.get(id);
    assertFalse(RecoveryService.stopped(t), "较早观察产生的信号不能阻塞已经取得进展的任务");
    assertTrue(t.context.signals.isEmpty()); assertEquals(progressed, progress(t), "丢弃旧信号不算新进展");
  }

  @Test void anEarlierRecoveryGenerationsSignalCannotBlockTheNewAttempt() {
    String id = development(); clk.advance(9 * U.HOUR); waitWithoutProgress(id); engine.watchdog();
    var old = repo.get(id).context.signals.getFirst();
    engine.apply(repo.get(id), Decision.block("timeout", "旧执行超时"));
    var stopped = repo.get(id); engine.resumeTask(id, stopped.version, "fixture");
    clk.advance(9 * U.HOUR);
    var t = repo.get(id); t.context.signals.clear(); t.context.signals.add(old); t.nextRunAt = clk.iso(); assertTrue(repo.save(t));
    engine.tick(); assertFalse(RecoveryService.stopped(repo.get(id))); assertTrue(repo.get(id).context.signals.isEmpty());
  }

  @Test void aPatrolSnapshotTakenBeforeResumeCannotEnqueueANewTimeout() throws Exception {
    String id = development(); clk.advance(9 * U.HOUR); waitWithoutProgress(id); var observed = repo.get(id);
    engine.apply(repo.get(id), Decision.block("timeout", "原执行超时"));
    var stopped = repo.get(id); engine.resumeTask(id, stopped.version, "fixture");
    var send = Engine.class.getDeclaredMethod("sendTimeoutSignal", String.class, int.class); send.setAccessible(true);
    assertEquals(false, send.invoke(engine, id, observed.version)); assertTrue(repo.get(id).context.signals.isEmpty()); assertEquals(0, signals(id));
  }

  @ParameterizedTest @ValueSource(strings = {"TASK_RESUMED", "SPLIT_PART_COMMITTED"})
  void legacyTasksUseRecentSuccessfulEvents(String event) {
    String id = development(); clk.advance(9 * U.HOUR); legacy(id);
    audit.task(id, repo.get(id).currentIteration, event, "fixture", Map.of("node", "DEV", "part", 12, "parts", 14), null);
    waitWithoutProgress(id); var version = repo.get(id).version; engine.watchdog();
    assertTrue(repo.get(id).context.signals.isEmpty(), "旧记录也要采用最近恢复或提交时间");
    assertEquals(version, repo.get(id).version, "读取旧任务的计时基准不改写历史记录");
  }

  @Test void aLegacyTaskWithoutRecentProgressStillTimesOut() {
    String id = development(); clk.advance(9 * U.HOUR); legacy(id); waitWithoutProgress(id); engine.watchdog();
    assertEquals(1, repo.get(id).context.signals.size());
  }

  @Test void aNewPhaseGetsItsOwnIdleWindowAndIgnoresOtherPhasesLegacyEvents() {
    String id = development(); clk.advance(9 * U.HOUR);
    engine.apply(repo.get(id), Decision.move(Status.QA, "advance"));
    var qa = repo.get(id); assertEquals(qa.statusChangedAt, progress(qa));
    engine.watchdog(); assertTrue(repo.get(id).context.signals.isEmpty());
    clk.advance(3 * U.HOUR); legacy(id);
    audit.task(id, qa.currentIteration, "TASK_RESUMED", "fixture", Map.of("node", "DEV"), null);
    waitWithoutProgress(id); engine.watchdog(); assertEquals(1, repo.get(id).context.signals.size());
  }

  @Test void pausedStoppedAndTypedTasksAreNotHandledByTheLegacyTimeoutPatrol() {
    String id = development(); clk.advance(9 * U.HOUR); var t = repo.get(id);
    t.paused = 1; t.nextRunAt = clk.after(60); assertTrue(repo.save(t)); engine.watchdog();
    assertTrue(repo.get(id).context.signals.isEmpty());
    t = repo.get(id); t.paused = 0; assertTrue(repo.save(t));
    db.update("UPDATE task SET pipeline_version='v3' WHERE id=?", id); engine.watchdog();
    assertTrue(repo.get(id).context.signals.isEmpty());
    db.update("UPDATE task SET pipeline_version='v1' WHERE id=?", id);
    engine.apply(repo.get(id), Decision.block("agent_error", "已停止")); engine.watchdog();
    assertTrue(repo.get(id).context.signals.isEmpty()); assertEquals(0, signals(id));
  }
}
