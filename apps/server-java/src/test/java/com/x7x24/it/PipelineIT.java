package com.x7x24.it;

import com.x7x24.core.Status;
import com.x7x24.engine.Engine;
import com.x7x24.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipelineIT extends Harness {
  @BeforeEach void isolatePipelineFromOtherTestsPatrolHistory() {
    tools.developmentFailure = null; tools.branchUpdateFailure = null;
    // 流水线用例共享审计历史；自动自治收紧由 GovernanceIT 独立覆盖。
    cfg.update(Json.tree("{\"autonomy\":{\"tightenDiscards\":10000,\"tightenRollbacks\":10000,\"globalRollbackKill\":10000}}"));
  }
  static final String SLUG = "请新增 slugify 工具函数（src/slugify.js），把任意字符串转成 URL 友好的 slug，并补充单元测试与文档。示例：slugify(\"Hello World\") => \"hello-world\"";
  private String ingestCiIssue(String title) {
    // 这些用例核验同一份开发产物的 CI 生命周期；语义去重另有专门覆盖。
    cfg.update(Json.tree("{\"thresholds\":{\"similarityDup\":1}}"));
    return ingestIssue(title, SLUG);
  }

  @Test void missingCiStopsOnceAndResumesQaWithoutRepeatingDevelopment() {
    String id = ingestCiIssue("为文章链接增加 slugify 工具函数");
    var qa = runTo(id, Status.QA); assertEquals(Status.QA, qa.st(), String.valueOf(qa.discardReason)); int commits = tools.commits.get();
    tools.ciFailure = new com.x7x24.adapters.DeliveryTools.GateBlocked("ci_broken", "缺少 .github/workflows/ci.yml");
    clk.advance(120_000); engine.tick();
    var stopped = repo.get(id); assertBlocked(stopped);
    assertEquals("ci_broken", db.row("SELECT type FROM anomaly WHERE task_id=? AND status='open'", id).get("type"));
    assertEquals(0, stopped.loopCount); assertEquals(0, stopped.stepAttempts);
    clk.advance(120_000); engine.tick(); assertEquals(commits, tools.commits.get());
    tools.ciFailure = null; engine.resumeAnomaly(id, "test");
    assertEquals(Status.QA, repo.get(id).st()); assertEquals(qa.context.pr.number, repo.get(id).context.pr.number);
    assertEquals(commits, tools.commits.get()); assertTrue(tools.merged.isEmpty());
    assertEquals(Status.ACCEPTANCE, runTo(id, Status.ACCEPTANCE).st());
  }

  @Test void missingProjectSkeletonStopsBeforeAnyDevelopmentCommit() {
    String id = ingestCiIssue("为文章标题增加 slugify 工具函数");
    tools.developmentFailure = new com.x7x24.adapters.DeliveryTools.GateBlocked("project_not_ready", "缺少 package.json");
    var stopped = runTo(id, Status.ANOMALY);
    assertBlocked(stopped); assertEquals(0, tools.commits.get()); assertEquals(0, stopped.context.devRuns);
    tools.developmentFailure = null; engine.resumeAnomaly(id, "test");
    assertEquals(Status.DEV, repo.get(id).st());
  }

  @Test void historicalWorkflow404CanResumeQaOnTheUpdatedHead() {
    String id = ingestCiIssue("历史 CI 配置修复后继续生成 slugify 工具函数");
    var qa = runTo(id, Status.QA); int commits = tools.commits.get();
    tools.ciFailure = new com.x7x24.adapters.DeliveryTools.GateBlocked("ci_broken", "missing CI");
    clk.advance(120_000); engine.tick(); assertBlocked(repo.get(id));
    db.update("UPDATE anomaly SET type='agent_error' WHERE task_id=? AND status='open'", id);
    var legacy = repo.get(id);
    legacy.context.failures.add(new com.x7x24.engine.TaskCtx.Failure("QA", "agent_error", "GitHub 404 /repos/o/r/actions/workflows/ci.yml/runs?head_sha=old: Not Found", clk.iso()));
    assertTrue(repo.save(legacy));
    tools.ciFailure = null; tools.main.put("docs/ci-fixed.md", "base updated");
    String original = db.row("SELECT config FROM project WHERE id=?", qa.projectId).get("config").toString();
    var repaired = (com.fasterxml.jackson.databind.node.ObjectNode) Json.tree(original); repaired.put("workflow", "checks.yml");
    db.update("UPDATE project SET config=? WHERE id=?", repaired.toString(), qa.projectId);
    try {
      engine.resumeAnomaly(id, "test");
      var resumed = repo.get(id); assertEquals(Status.QA, resumed.st()); assertNotEquals(qa.context.pr.headSha, resumed.context.pr.headSha);
      assertEquals("checks.yml", resumed.context.projectSnapshot.workflow, "修复文件名后必须更新任务的 CI 配置快照");
      assertEquals(resumed.context.pr.headSha, resumed.context.ci.headSha); assertEquals("pending", resumed.context.ci.state);
      assertEquals(commits, tools.commits.get()); assertTrue(tools.merged.isEmpty());
    } finally { db.update("UPDATE project SET config=? WHERE id=?", original, qa.projectId); }
  }

  @Test void changingThePrHeadInvalidatesOldCiBeforeAcceptance() {
    String id = ingestCiIssue("为搜索结果增加 slugify 工具函数");
    var qa = runTo(id, Status.QA); assertEquals(Status.QA, qa.st());
    String oldHead = qa.context.ci.headSha;
    tools.branches.get(qa.context.pr.branch).put("docs/external.md", "a new commit");
    clk.advance(120_000); engine.tick();
    var refreshed = repo.get(id); assertEquals(Status.QA, refreshed.st());
    assertNotEquals(oldHead, refreshed.context.ci.headSha);
    assertEquals(refreshed.context.pr.headSha, refreshed.context.ci.headSha);
    assertTrue(tools.merged.isEmpty()); assertEquals(0, refreshed.context.qaRuns);
  }

  @Test void failedBranchUpdateCannotSilentlyResumeQaWithAnOldHead() {
    String id = ingestCiIssue("为详情链接增加 slugify 工具函数");
    runTo(id, Status.QA);
    tools.ciFailure = new com.x7x24.adapters.DeliveryTools.GateBlocked("ci_permission", "缺少 Actions 读权限");
    clk.advance(120_000); engine.tick(); assertBlocked(repo.get(id));
    tools.ciFailure = null;
    tools.branchUpdateFailure = new com.x7x24.adapters.DeliveryTools.GateBlocked("ci_broken", "分支更新未完成");
    assertThrows(com.x7x24.adapters.DeliveryTools.GateBlocked.class, () -> engine.resumeAnomaly(id, "test"));
    assertBlocked(repo.get(id));
  }

  @Test void closedLoopFromIssueToReleased() {
    String id = ingestIssue("新增 slugify 工具函数及测试", SLUG);
    var t = run(id, x -> x.st() == Status.RELEASED && x.feedbackDone == 1 || x.st() == Status.DISCARDED, 200);
    assertEquals(Status.RELEASED, t.st(), "最终状态");
    assertNotNull(t.context.pr); assertNotNull(t.context.rel); assertTrue(t.context.rel.verified);
    assertTrue(tools.main.containsKey("src/slugify.js") && tools.main.containsKey("tests/slugify.test.js") && tools.main.containsKey("docs/slugify.md"), "合并后主干含代码 + 测试 + 文档");
    int issue = issueNo(id);
    assertTrue(tools.closed.contains(issue), "Issue 已关闭");
    assertTrue(tools.comments.get(issue).stream().anyMatch(c -> c.contains("GitHub Release 已创建")), "仓库版本发布评论");
    assertFalse(tools.comments.get(issue).stream().anyMatch(c -> c.contains("**已上线**")), "仓库版本不能被宣称为平台上线");
    assertTrue(tools.labelsAdded.get(issue).contains("7x24:released"));
    assertEquals(1, tools.prOpened.get(), "同一 Issue 只开一个 PR（幂等）");
    assertTrue(Boolean.TRUE.equals(audit.verify().get("ok")), "哈希链");
    assertTrue(evidence.verifyTask(id, cfg.get().release.requireEvidence).ok());
    assertEquals(Status.RELEASED, engine.describe(id).replayStatus(), "事件溯源重放状态一致");
    assertTrue(t.spentCi > 0, "CI 分钟记入预算");
    assertEquals(0, db.count("SELECT COUNT(*) FROM merge_approval WHERE task_id=?", id), "小改动在 L2 自动合并，不等确认");
  }

  @Test void ingestingSameIssueTwiceIsIdempotent() {
    String id = ingestIssue("新增 wordCount 工具函数及测试", "新增 wordCount 工具函数：统计单词数。示例：wordCount(\"hello world\") => 2");
    long before = db.count("SELECT COUNT(*) FROM task");
    assertEquals(0, intake.poll()); intake.poll();
    assertEquals(before, db.count("SELECT COUNT(*) FROM task"));
    var r = engine.ingest(new Engine.Ingest("sandbox", "新增 wordCount 工具函数及测试", "x", "github", "gh:#" + issueNo(id), null, false, null));
    assertTrue(r.duplicate()); assertEquals(id, r.taskId());
  }

  @Test void repairLoopReadsCiLogAndFixesIt() {
    String id = ingestIssue("新增 clamp 工具函数及测试", "新增 clamp 工具函数（src/clamp.js）。示例：clamp(-1, 0, 10) => 0\nx7x24: first-attempt=naive");
    var t = run(id, x -> x.st() == Status.RELEASED || x.st() == Status.DISCARDED, 200);
    assertEquals(Status.RELEASED, t.st(), t.discardReason + " " + t.discardDetail);
    assertTrue(t.loopCount >= 1, "Loop 计数增加：" + t.loopCount);
    var logs = evidence.list(id).stream().filter(e -> "ci_log".equals(e.get("kind"))).map(e -> (String) e.get("content")).toList();
    assertTrue(logs.stream().anyMatch(l -> l.contains("\"state\":\"failed\"")) && logs.stream().anyMatch(l -> l.contains("\"state\":\"passed\"")));
    assertTrue(tools.main.get("src/clamp.js").contains("Math.max(n, min)"), "合并的是返修后的健壮实现");
    assertEquals(1, tools.prOpened.get());
    assertTrue(tools.commits.get() >= 2, "返修产生了新的提交");
    // 返修直接回到开发执行：不重新规划、不重做上游步骤
    assertTrue(db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='STAGE_RESUMED'", id) >= 1, "记录了从哪一步接着返修");
    assertEquals(1, evidence.list(id).stream().filter(e -> "plan".equals(e.get("kind"))).count(), "返修不重新出计划");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"zero", "failed-count", "cancelled", "unknown", "missing-log"})
  void incompleteOrContradictoryChecksNeverEnterAcceptance(String condition) {
    String id = ingestCiIssue("检查必须完整通过 " + condition);
    assertEquals(Status.QA, runTo(id, Status.QA).st());
    String state = condition.equals("unknown") ? "unknown" : condition.equals("missing-log") ? "failed" : "passed";
    tools.ciResultOverride = new com.x7x24.adapters.DeliveryTools.CiState(state, 99L, "https://example.test/run/99", 1,
        condition.equals("zero") ? 0 : 2, condition.equals("failed-count") || condition.equals("missing-log") ? 1 : 0, 0,
        "AssertionError: expected 3 to be 7", true, java.util.List.of(99L), condition.equals("cancelled") ? "cancelled" : "success", !condition.equals("missing-log"));
    clk.advance(120_000); engine.tick();
    var stopped = repo.get(id); assertBlocked(stopped);
    assertEquals(Status.QA, stopped.st()); assertEquals(0, stopped.loopCount);
    assertEquals("evidence_missing", engine.recoveryInfo(id).get("code"));
    assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND to_status='ACCEPTANCE'", id));
    assertTrue(tools.merged.isEmpty());
  }

  @Test void fullFailureReportReachesRepairAndIsStoredWithoutDroppingMiddleFailures() {
    String id = ingestCiIssue("完整多用例错误返修");
    assertEquals(Status.QA, runTo(id, Status.QA).st());
    String log = java.util.stream.IntStream.rangeClosed(1, 40).mapToObj(i ->
        "FAIL src/case" + i + ".test.ts > case " + i + "\nAssertionError: expected " + i + " to be 100\n ❯ src/case" + i + ".test.ts:42:8\n").collect(java.util.stream.Collectors.joining("\n"));
    tools.ciResultOverride = new com.x7x24.adapters.DeliveryTools.CiState("failed", 98L, "https://example.test/run/98", 1, 2, 1, 0, log, true, java.util.List.of(98L), "failure");
    var dev = runTo(id, Status.DEV);
    assertEquals(log, dev.context.lastCiLog); assertEquals(40, dev.context.ciFiles.size());
    var stored = evidence.latest(id, "ci_log"); assertEquals(log, stored.path("log").asText());
    var report = evidence.latest(id, "test_report"); assertFalse(report.path("passed").asBoolean());
    assertEquals(40, report.path("failures").path("failures").size());
    assertEquals("src/case20.test.ts", report.path("failures").path("failures").get(19).path("file").asText());
  }

  @Test void prChangingDuringTheCiPollCannotUseTheOldPassToEnterAcceptance() throws Exception {
    String id = ingestCiIssue("检查完成前分支发生变化");
    var qa = runTo(id, Status.QA);
    var field = Engine.class.getDeclaredField("tools"); field.setAccessible(true);
    var original = (com.x7x24.adapters.DeliveryTools) field.get(engine);
    var proxy = (com.x7x24.adapters.DeliveryTools) java.lang.reflect.Proxy.newProxyInstance(
        original.getClass().getClassLoader(), new Class<?>[]{com.x7x24.adapters.DeliveryTools.class}, (p, method, args) -> {
          Object result;
          try { result = method.invoke(original, args); }
          catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
          if (method.getName().equals("ci"))
            tools.commit(null, "fixture:ci-race", qa.context.pr.branch, "concurrent change", java.util.List.of(com.x7x24.adapters.DeliveryTools.FileChange.put("docs/new-head.md", "# new head\n")));
          return result;
        });
    field.set(engine, proxy);
    try { clk.advance(120_000); engine.tick(); }
    finally { field.set(engine, original); }
    var stopped = repo.get(id); assertBlocked(stopped); assertEquals(Status.QA, stopped.st());
    assertEquals("head_changed", engine.recoveryInfo(id).get("code"));
    assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND to_status='ACCEPTANCE'", id));
  }

  @org.springframework.beans.factory.annotation.Autowired com.x7x24.chat.TaskActions taskActions;
  @org.springframework.beans.factory.annotation.Autowired com.x7x24.engine.ProjectRepo projects;
  static final com.x7x24.chat.TaskActions.Ctx MEMBER_CTX = new com.x7x24.chat.TaskActions.Ctx("mia", "member");

  /** 可控的假沙盒：按顺序返回失败信息；记录启动次数。 */
  static class FailingSandbox implements com.x7x24.execution.agent.AgentRuntime {
    final java.util.List<String> errors; int starts; final String path;
    FailingSandbox(String path, String... errors) { this.path = path; this.errors = new java.util.ArrayList<>(java.util.List.of(errors)); }
    public String id() { return "fake-remote"; }
    public Handle start(JobSpec spec) { starts++; return new Handle(id(), "local"); }
    public Status poll(Handle handle) { String e = errors.isEmpty() ? "boom" : errors.size() == 1 ? errors.getFirst() : errors.removeFirst(); return new Status("FAILED", e, new Result(java.util.List.of(new FileOut(path, "half-done", false)), java.util.List.of(), "partial", Usage.ZERO, "c".repeat(40), "d".repeat(40))); }
    public Logs logs(Handle handle, long seq) { return new Logs(java.util.List.of(), seq); }
    public void cancel(Handle handle, String reason) { }
  }
  <T> T withSandbox(com.x7x24.execution.agent.AgentRuntime runtime, java.util.concurrent.Callable<T> body) throws Exception {
    var settings = new com.x7x24.execution.agent.AgentRuntimeSettings("remote-docker", "http://unused", "k".repeat(40), "fixture", com.x7x24.execution.agent.AgentRuntime.Limits.defaults(), 1);
    var service = new com.x7x24.execution.agent.AgentJobService(db, clk, runtime, settings, r -> "", null, 0, 0);
    var field = Engine.class.getDeclaredField("agents"); field.setAccessible(true);
    var original = (com.x7x24.agents.Models.ExecAgents) field.get(engine);
    field.set(engine, new com.x7x24.execution.agent.AgentRuntimeAgents(original, service));
    try { return body.call(); } finally { field.set(engine, original); }
  }
  String devTask(String title) {
    String id = ingestCiIssue(title); runTo(id, Status.QA); tools.ciAlwaysFail = true;
    assertEquals(Status.DEV, runTo(id, Status.DEV).st()); return id;
  }
  java.util.List<java.util.Map<String, Object>> events(String id, String type) { return db.rows("SELECT payload FROM task_event WHERE task_id=? AND event_type=? ORDER BY id", id, type); }

  @Test void sandboxFailureStopsRecoverablyWithPlainReasonAndKeepsNoPartialWork() throws Exception {
    String id = devTask("沙盒失败时停下"); String path = "src/slugify.js", branch = repo.get(id).context.pr.branch;
    String before = tools.readFile(null, branch, path);
    var sandbox = new FailingSandbox(path, "agent 用完轮数上限（61 轮，error_max_turns）");
    withSandbox(sandbox, () -> {
      clk.advance(120_000); engine.tick();
      var stopped = repo.get(id); assertBlocked(stopped); assertEquals(Status.DEV, stopped.st());
      assertEquals(1, sandbox.starts, "超轮数不自动重试，避免白花钱");
      assertEquals("sandbox_failed", engine.recoveryInfo(id).get("code"));
      assertTrue(String.valueOf(engine.recoveryInfo(id)).contains("用完了对话轮数上限"), String.valueOf(engine.recoveryInfo(id)));
      assertEquals(before, tools.readFile(null, branch, path), "沙盒半成品不能写入分支");
      assertEquals(0, db.count("SELECT COUNT(*) FROM anomaly WHERE task_id=? AND type<>'sandbox_failed'", id), "不进入换模型 / 重新规划 / 自动丢弃链");
      clk.advance(3_600_000); engine.tick(); engine.tick();
      assertEquals(1, sandbox.starts, "停住后不自动重跑"); assertBlocked(repo.get(id)); assertEquals(Status.DEV, repo.get(id).st());
      assertTrue(String.valueOf(events(id, "DEV_MODE").getFirst().get("payload")).contains("本轮：沙盒"));
      @SuppressWarnings("unchecked") var sugs = (java.util.List<java.util.Map<String, Object>>) com.x7x24.tenant.Tenant.run(com.x7x24.tenant.Tenant.LEGACY, () -> taskActions.blocked(id)).get("suggestions");
      assertEquals(java.util.List.of("task_resume", "dev_mode_retry", "task_abandon"), sugs.stream().map(m -> m.get("kind")).toList());
      assertEquals("再试一次（沙盒）", sugs.getFirst().get("title"));
      // 成员点「本任务改用快速方式重试」：下一轮走快速方式，不再启动沙盒
      com.x7x24.tenant.Tenant.run(com.x7x24.tenant.Tenant.LEGACY, () -> {
        String a = (String) taskActions.propose(id, "dev_mode_retry", Json.tree("{\"devMode\":\"legacy\"}"), "button", null, MEMBER_CTX).get("id");
        assertEquals("executed", taskActions.confirm(id, a, null, MEMBER_CTX).get("status")); return null; });
      assertEquals("legacy", repo.get(id).context.devModeOverride);
      clk.advance(120_000); engine.tick();
      assertEquals(1, sandbox.starts, "快速方式不启动沙盒");
      var modes = events(id, "DEV_MODE"); assertTrue(String.valueOf(modes.getLast().get("payload")).contains("本轮：快速方式"), String.valueOf(modes));
      assertEquals(1, events(id, "TASK_DEV_MODE").size());
      return null;
    });
  }

  @Test void networkSandboxFailuresRetryTwiceThenStopWithoutAnomalyChain() throws Exception {
    String id = devTask("沙盒拉代码失败自动重试");
    var sandbox = new FailingSandbox("src/slugify.js", "clone 失败（已重试 3 次）：Failed to connect to github.com port 443");
    withSandbox(sandbox, () -> {
      clk.advance(120_000); engine.tick();
      assertEquals(1, sandbox.starts); assertEquals(1, events(id, "SANDBOX_RETRY").size()); assertFalse(com.x7x24.engine.RecoveryService.stopped(repo.get(id)));
      engine.tick(); assertEquals(1, sandbox.starts, "1 分钟之内不重试");
      clk.advance(61_000); engine.tick(); assertEquals(2, sandbox.starts); assertEquals(2, events(id, "SANDBOX_RETRY").size());
      clk.advance(301_000); engine.tick(); assertEquals(3, sandbox.starts);
      var stopped = repo.get(id); assertBlocked(stopped); assertEquals("sandbox_failed", engine.recoveryInfo(id).get("code"));
      assertTrue(String.valueOf(engine.recoveryInfo(id)).contains("拉代码失败"));
      assertEquals(0, stopped.context.sandboxRetries, "停住后重新计数，用户点「再试一次」还能自动重试");
      assertEquals(0, db.count("SELECT COUNT(*) FROM anomaly WHERE task_id=? AND type<>'sandbox_failed'", id));
      clk.advance(3_600_000); engine.tick(); assertEquals(3, sandbox.starts); assertEquals(Status.DEV, repo.get(id).st());
      return null;
    });
  }

  @Test void projectDevModeIsReadLiveAndTaskOverrideWins() throws Exception {
    String id = devTask("项目选快速方式");
    var sandbox = new FailingSandbox("src/slugify.js", "boom");
    var t = repo.get(id); var cfg = projects.require(t.projectId).cfg();
    try {
      projects.update(t.projectId, Json.tree("{\"config\":{\"devMode\":\"legacy\"}}"));
      withSandbox(sandbox, () -> { clk.advance(120_000); engine.tick(); return null; });
      assertEquals(0, sandbox.starts, "项目当前设置立即生效（不走任务快照）");
      assertTrue(String.valueOf(events(id, "DEV_MODE").getLast().get("payload")).contains("project"));
      assertThrows(IllegalArgumentException.class, () -> projects.update(t.projectId, Json.tree("{\"config\":{\"devMode\":\"bogus\"}}")));
    } finally { projects.update(t.projectId, Json.tree("{\"config\":{\"devMode\":\"" + (cfg.devMode == null ? "sandbox" : cfg.devMode) + "\"}}")); }
  }

  @Test void actualRemoteRepairPreservesPreviousEditsAndWaitsForCiBeforeAcceptance() throws Exception {
    String id = ingestCiIssue("远程返修保留原分支修改");
    var qa = runTo(id, Status.QA); String path = "src/slugify.js", branch = qa.context.pr.branch;
    String prior = tools.readFile(null, branch, path) + "\n// previous repair must survive\n";
    tools.commit(null, "fixture:previous", branch, "previous repair", java.util.List.of(com.x7x24.adapters.DeliveryTools.FileChange.put(path, prior)));
    tools.ciAlwaysFail = true;
    assertEquals(Status.DEV, runTo(id, Status.DEV).st());
    var runtime = new com.x7x24.execution.agent.AgentRuntime() {
      JobSpec submitted;
      public String id() { return "fake-remote"; }
      public Handle start(JobSpec spec) { submitted = spec; assertEquals(tools.prHead(null, qa.context.pr.number), spec.baseRef()); return new Handle(id(), "local"); }
      public Status poll(Handle handle) { return new Status("SUCCEEDED", null, new Result(java.util.List.of(new FileOut(path, tools.readFile(null, branch, path) + "// next repair\n", false)), java.util.List.of(), "submitted; CI pending", Usage.ZERO, "c".repeat(40), submitted.baseRef())); }
      public Logs logs(Handle handle, long seq) { return new Logs(java.util.List.of(), seq); }
      public void cancel(Handle handle, String reason) { }
    };
    var settings = new com.x7x24.execution.agent.AgentRuntimeSettings("remote-docker", "http://unused", "k".repeat(40), "fixture", com.x7x24.execution.agent.AgentRuntime.Limits.defaults(), 1);
    var service = new com.x7x24.execution.agent.AgentJobService(db, clk, runtime, settings, r -> "", null, 0, 0);
    var field = Engine.class.getDeclaredField("agents"); field.setAccessible(true);
    var original = (com.x7x24.agents.Models.ExecAgents) field.get(engine);
    field.set(engine, new com.x7x24.execution.agent.AgentRuntimeAgents(original, service));
    try {
      assertEquals(Status.QA, runTo(id, Status.QA).st());
      assertTrue(tools.readFile(null, branch, path).contains("// previous repair must survive"));
      assertTrue(tools.readFile(null, branch, path).contains("// next repair"));
      assertEquals(Status.DEV, runTo(id, Status.DEV).st(), "编码完成但 CI 仍失败时必须继续返修");
      assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND to_status='ACCEPTANCE'", id));
      tools.ciAlwaysFail = false;
      assertEquals(Status.ACCEPTANCE, runTo(id, Status.ACCEPTANCE).st());
      assertTrue(tools.readFile(null, branch, path).contains("// previous repair must survive"));
      assertTrue(evidence.latest(id, "test_report").path("passed").asBoolean());
    } finally { field.set(engine, original); }
  }

  @Test void qaFailureReworksInDevKeepingSegmentPlanWithoutReplanning() {
    String id = ingestIssue("分段任务的返修：数值夹取", "需要一个数值夹取函数 clamp，放在 src/clamp.js 里，超出范围时取边界。示例：clamp(-1, 0, 10) => 0\nx7x24: first-attempt=naive");
    var q = run(id, x -> x.st() == Status.QA || x.st() == Status.DISCARDED, 200); assertEquals(Status.QA, q.st());
    // 模拟已拆成 3 段且都已提交的计划
    engine.apply(repo.get(id), com.x7x24.engine.Decision.stay(0.01, w -> { var pl = (com.fasterxml.jackson.databind.node.ObjectNode) w.plan; pl.set("subtasks", com.x7x24.util.Json.tree("[{\"title\":\"第 1 段\",\"files\":[\"src/clamp.js\"]},{\"title\":\"第 2 段\",\"files\":[\"src/clamp.js\"]},{\"title\":\"第 3 段\",\"files\":[\"tests/clamp.test.js\"]}]")); w.context.subtaskIdx = 2; }));
    var d = run(id, x -> x.st() != Status.QA, 60);
    assertEquals(Status.DEV, d.st(), "测试没过：直接回开发执行，不经过任务规划");
    assertEquals(3, d.plan.path("subtasks").size(), "分段计划保留"); assertEquals(3, d.context.subtaskIdx, "整体返修一次，不从第 1 段重来");
    assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='STATUS_CHANGED' AND from_status='QA' AND to_status='PLANNING'", id));
    var t = run(id, x -> x.st() == Status.RELEASED || x.st() == Status.DISCARDED, 200);
    assertEquals(Status.RELEASED, t.st(), t.discardReason + " " + t.discardDetail);
    long before = db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='SPLIT_PART_COMMITTED' AND occurred_at < (SELECT MIN(occurred_at) FROM task_event WHERE task_id=? AND event_type='STAGE_RESUMED')", id, id);
    assertEquals(before, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='SPLIT_PART_COMMITTED'", id), "返修不重新逐段提交");
    assertEquals(1, evidence.list(id).stream().filter(e -> "plan".equals(e.get("kind"))).count(), "不重新规划");
  }

  java.util.List<String> anomalies(String id) { return db.rows("SELECT type FROM anomaly WHERE task_id=? ORDER BY opened_at, id", id).stream().map(r -> String.valueOf(r.get("type"))).toList(); }
  long owner(String id) { return db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='OWNER_NEEDED'", id); }

  @Test void sameCiFailureRepeatedSwitchesStrategyThenWaitsForOwnerWithoutDiscardOrDegrade() {
    tools.ciAlwaysFail = true;
    String id = ingestIssue("新增 isPalindrome 工具函数及测试", "新增 isPalindrome 工具函数（src/isPalindrome.js）。示例：isPalindrome(\"abba\") => true");
    var t = run(id, x -> x.st() == Status.DISCARDED || owner(id) > 0, 500);
    assertNotEquals(Status.DISCARDED, t.st(), t.discardReason + " " + t.discardDetail);
    assertBlocked(t); assertTrue(owner(id) > 0, "停下等负责人");
    assertTrue(anomalies(id).contains("same_failure"), "同样的失败连续出现 → 换方法，而不是继续原样返修：" + anomalies(id));
    assertEquals(0, t.degradeCount, "返修卡住不是预算问题，不进省钱模式");
    assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND to_status='DEGRADED'", id));
    long qaFails = db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='STATUS_CHANGED' AND edge='qa_loop'", id);
    assertTrue(qaFails <= 2 * 3, "相同失败不会一直烧轮次：" + qaFails);
    assertNull(t.nextRunAt,"停止自动尝试，等待人处理");
    assertTrue(tools.merged.isEmpty(), "CI 一直失败则绝不合并");
    t = run(id, x -> false, 30);
    assertBlocked(t);
  }

  @Test void brokenCiSetupOnBaseBranchPausesForOwnerWithoutBurningReworkThenResumesAfterFix() {
    tools.ciSetupBroken = true; tools.baseCiFailing = true;
    String id = ingestIssue("新增 capitalize 工具函数及测试", "新增 capitalize 工具函数（src/capitalize.js）。示例：capitalize(\"abc\") => \"Abc\"");
    var t = run(id, x -> x.st() == Status.DISCARDED || owner(id) > 0, 300);
    assertBlocked(t); assertEquals(java.util.List.of("ci_broken"), anomalies(id));
    assertEquals(0, t.loopCount, "CI 本身跑不起来不算返修轮次");
    assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND edge='qa_loop'", id), "不让编码去修它改不了的问题");
    assertTrue(tools.comments.get(issueNo(id)).stream().anyMatch(c -> c.contains("CI 本身跑不起来")));
    tools.ciSetupBroken = false; tools.baseCiFailing = false;
    var r = engine.resumeAnomaly(id, "owner"); assertEquals(Status.QA, r.st(), "修好 CI 后直接回测试重跑，不重新规划");
    t = run(id, x -> x.st() == Status.RELEASED || x.st() == Status.DISCARDED, 200);
    assertEquals(Status.RELEASED, t.st(), t.discardReason + " " + t.discardDetail);
  }

  @Test void budgetHitWithDegradeOffPausesThenUnlimitedResumesAndStillTracksCost() {
    cfg.update(Json.tree("{\"budgets\":{\"task\":{\"ciMinutes\":1,\"degrade\":false}}}"));
    String id = ingestIssue("预算开关验证：数值边界截断 clamp", "新增 clamp 工具函数（src/clamp.js）。示例：clamp(-1, 0, 10) => 0\nx7x24: first-attempt=naive");
    var t = run(id, x -> x.st() == Status.RELEASED || x.st() == Status.DISCARDED || owner(id) > 0, 300);
    assertBlocked(t); assertTrue(anomalies(id).contains("budget_paused"), anomalies(id).toString());
    assertEquals(0, t.degradeCount, "关了省钱模式：不进省钱模式");
    var r = engine.setBudgetSwitches(id, true, true, false, null, "owner");
    assertNotEquals(Status.ANOMALY, r.st(), "改成不限预算后自动恢复");
    assertEquals(1, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='TASK_BUDGET_SWITCH'", id));
    t = run(id, x -> x.st() == Status.RELEASED || x.st() == Status.DISCARDED, 300);
    assertEquals(Status.RELEASED, t.st(), t.discardReason + " " + t.discardDetail);
    assertTrue(t.spentCi > 1, "不限预算仍然记录花费：" + t.spentCi);
    assertEquals("task", budget.switches(t).budgetFrom());
  }

  @Test void unlimitedBudgetStillStopsAtSafetyCap() {
    cfg.update(Json.tree("{\"budgets\":{\"task\":{\"unlimited\":true,\"ciMinutes\":0.01,\"safetyMaxIterations\":1}}}"));
    tools.ciAlwaysFail = true;
    String id = ingestIssue("安全上限验证：统计单词数 wordCount", "新增 wordCount 工具函数（src/wordCount.js）。示例：wordCount(\"a b\") => 2");
    run(id, x -> x.st() == Status.QA && x.spentCi > 0.01, 200);
    db.update("UPDATE task SET spent_iterations=5 WHERE id=?", id); // 规则 Agent 不计 token，这里模拟已开发 5 轮
    var t = run(id, x -> x.st() == Status.DISCARDED || owner(id) > 0, 300);
    assertBlocked(t); assertTrue(anomalies(id).contains("safety_cap"), anomalies(id).toString());
    assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='BUDGET_HIT'", id), "不限预算：金额 / CI 分钟触顶不拦");
    assertThrows(IllegalArgumentException.class,()->engine.resumeAnomaly(id,"owner"),"恢复不会默默增加安全上限");
    cfg.update(Json.tree("{\"budgets\":{\"task\":{\"unlimited\":true,\"ciMinutes\":0.01,\"safetyMaxIterations\":20}}}"));
    var r=engine.resumeAnomaly(id,"owner");assertEquals(t.st(),r.st());assertEquals(0,r.context.safetyExtra);
  }

  @Test void forbiddenRequirementIsRejectedBeforeAnyPr() {
    String id = ingestIssue("修改发布流水线并调整支付退款配置", "需要修改 .github/workflows/ci.yml，并调整支付退款（refund）的结算配置。");
    var t = run(id, x -> x.st() == Status.DISCARDED, 40);
    assertBlocked(t);assertEquals(Status.PRD_REVIEW,t.st());assertEquals("forbidden_scope",engine.recoveryInfo(id).get("code"));
    assertEquals(0, tools.prOpened.get()); assertTrue(tools.merged.isEmpty());
    int issue = issueNo(id);
    assertTrue(tools.comments.get(issue).stream().anyMatch(c -> c.contains("进度保留")));
    assertFalse(tools.closed.contains(issue));
  }

  @Test void killSwitchStopsProgressAndResumes() {
    String id = ingestIssue("新增 capitalize 工具函数及测试", "新增 capitalize 工具函数：首字母大写。示例：capitalize(\"hello\") => \"Hello\"");
    run(id, x -> x.st() == Status.PLANNING, 40);
    var ks = kill.activate("global", "HALT", "manual", "test", null);
    var frozen = repo.get(id).status;
    for (int i = 0; i < 6; i++) { clk.advance(5 * 60_000L); engine.tick(); }
    assertEquals(frozen, repo.get(id).status, "HALT 期间任务不前进");
    int n = tools.addIssue("新增 wordCount 工具函数二号", "x"); assertEquals(0, intake.poll(), "HALT 期间不抓取新 Issue");
    kill.release((String) ks.get("id"), "test");
    var t = run(id, x -> x.st() == Status.RELEASED, 200);
    assertEquals(Status.RELEASED, t.st(), "恢复后继续");
  }

  @Test void healthRegressionAfterMergeOpensRevertPrAndRollsBack() {
    tools.mainUnhealthy = true;
    String id = ingestIssue("为 URL 生成 slug：增加 slugify 助手（健康回滚场景）", SLUG);
    var t = run(id, x -> x.rollbackCount >= 1 || x.st() == Status.RELEASED, 200);
    assertTrue(t.rollbackCount >= 1, "主干健康度劣化触发回滚 " + t.status + " " + t.discardReason);
    assertFalse(tools.reverts.isEmpty(), "回滚 = 还原 PR，不直推");
    assertTrue(db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='ROLLBACK'", id) >= 1);
  }

  @Test void governanceFileChangeIsBlockedAtMerge() {
    String id = ingestIssue("新增 wordCount 工具函数及测试三", "新增 wordCount 工具函数。示例：wordCount(\"a b\") => 2");
    run(id, x -> x.st() == Status.QA, 60);
    tools.branches.get(repo.get(id).context.pr.branch).put(".github/workflows/evil.yml", "on: push");
    // 篡改后 head 变化：合并闸门必须拒绝（head 变化 / 治理文件），不会合并
    var t = run(id, x -> x.st() == Status.RELEASED || x.st() == Status.DISCARDED || x.rejectCount > 0, 80);
    assertTrue(tools.merged.isEmpty(), "含治理文件的 PR 绝不合并");
  }
}
