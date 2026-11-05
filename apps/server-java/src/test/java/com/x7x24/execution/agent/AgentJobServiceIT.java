package com.x7x24.execution.agent;

import com.x7x24.agents.Models;
import com.x7x24.engine.ProjectCfg;
import com.x7x24.engine.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentJobServiceIT extends com.x7x24.it.Harness {
  @Autowired Models.ExecAgents agents;
  @Autowired com.x7x24.llm.ModelCalls modelCalls;

  /** 默认开关：Wiring 返回的仍是旧实现本身（不是装饰器），行为与 main 一致。 */
  @Test void defaultSwitchKeepsLegacyAgents() { assertFalse(agents instanceof AgentRuntimeAgents); }

  static class FakeRuntime implements AgentRuntime {
    final List<Status> states; int polls; boolean cancelled; String token; JobSpec spec;
    FakeRuntime(Status... s) { states = new ArrayList<>(List.of(s)); }
    public String id() { return "fake"; }
    public Handle start(JobSpec s) { spec = s; token = s.cloneToken(); return new Handle("fake", "r1"); }
    public Status poll(Handle h) { return states.get(Math.min(polls++, states.size() - 1)); }
    public Logs logs(Handle h, long from) { return from == 0 ? new Logs(List.of(new LogEvent(0, "t", "text", "running tests")), 1) : new Logs(List.of(), from); }
    public void cancel(Handle h, String r) { cancelled = true; }
  }
  Models.Ctx ctx(Task t, List<Double> usd) {
    var line = new ProjectCfg(); line.repo = "o/r"; line.baseBranch = "a".repeat(40);
    return new Models.Ctx(t, line, null, (a, r) -> usd.add(r.usd()), null, null, null, null, false, null, Models.ExecutionMode.IMPLEMENT, null);
  }
  Task task() { var t = new Task(); t.id = "t-" + UUID.randomUUID(); t.workspaceId = "platform"; t.title = "fix add"; t.problem = "add is wrong"; return t; }
  AgentJobService svc(FakeRuntime rt, List<AgentRuntime.LogEvent> sink) {
    var s = new AgentRuntimeSettings("remote-docker", "http://x", "k".repeat(32), "deepseek-flash", new AgentRuntime.Limits(600, 10, "2g", 1.5), 1);
    return new AgentJobService(db, clk, rt, s, repo -> "tok", (w, t, j, e) -> sink.add(e), 1.0, 2.0, ms -> { });
  }

  @Test void successBecomesCodeChangeAndIsRecorded() {
    var res = new AgentRuntime.Result(List.of(new AgentRuntime.FileOut("src/a.js", "x", false)), List.of("tmp.log"), "done", new AgentRuntime.Usage(1000, 500, 2000, 4), "sha1", "a".repeat(40));
    var rt = new FakeRuntime(new AgentRuntime.Status("RUNNING", null, null), new AgentRuntime.Status("SUCCEEDED", null, res));
    var logs = new ArrayList<AgentRuntime.LogEvent>(); var usd = new ArrayList<Double>(); var t = task();
    var change = svc(rt, logs).run(ctx(t, usd));
    assertEquals("agent-runtime", change.variant()); assertEquals("src/a.js", change.files().get(0).path());
    assertEquals("tok", rt.token); assertTrue(rt.spec.prompt().contains("add is wrong"));
    assertEquals(1, logs.size()); assertEquals(1, usd.size()); assertEquals((3000 * 1.0 + 500 * 2.0) / 1e6, usd.get(0), 1e-12);
    var row = db.row("SELECT state,files,untracked,tokens_in,head_sha FROM agent_job WHERE task_id=?", t.id);
    assertEquals("SUCCEEDED", row.get("state")); assertEquals("tmp.log", row.get("untracked")); assertEquals("sha1", row.get("head_sha"));
    assertEquals(1, db.rows("SELECT * FROM agent_job_log WHERE job_id IN (SELECT id FROM agent_job WHERE task_id=?)", t.id).size());
  }

  @Test void repairAndSegmentContinuationUseThePrHeadAndRetainEarlierEdits() {
    for (var mode : Models.ExecutionMode.values()) {
      var t = task(); t.context.pr = new com.x7x24.engine.TaskCtx.Pr();
      t.context.pr.branch = "agent/issue-42"; t.context.pr.headSha = "b".repeat(40);
      var line = new ProjectCfg(); line.repo = "o/r"; line.baseBranch = "main";
      var context = new Models.Ctx(t, line, null, null, null, null, null, "failed CI", false, null, mode, null);
      var rt = new FakeRuntime() {
        @Override public Status poll(Handle h) {
          String prior = spec.baseRef().equals(t.context.pr.headSha) ? "firstRepair = true;\n" : "firstRepair = false;\n";
          return new Status("SUCCEEDED", null, new Result(List.of(new FileOut("src/a.js", prior + "secondRepair = true;\n", false)), List.of(), "fixed", Usage.ZERO, "c".repeat(40), spec.baseRef()));
        }
      };
      var change = svc(rt, new ArrayList<>()).run(context);
      assertEquals(t.context.pr.headSha, rt.spec.baseRef(), "返修和分段续写都必须使用 PR 当前提交");
      assertTrue(change.files().getFirst().content().contains("firstRepair = true"), "保留前一轮修改");
      assertTrue(change.files().getFirst().content().contains("secondRepair = true"));
      assertEquals(t.context.pr.headSha, db.row("SELECT base_ref FROM agent_job WHERE task_id=?", t.id).get("base_ref"));
    }
  }

  @Test void cancelRequestStopsRemoteJob() {
    var rt = new FakeRuntime(new AgentRuntime.Status("RUNNING", null, null));
    var t = task(); var s = svc(rt, new ArrayList<>()); AtomicInteger n = new AtomicInteger();
    var svc = new AgentJobService(db, clk, rt, new AgentRuntimeSettings("remote-docker", "http://x", "k".repeat(32), "m", new AgentRuntime.Limits(600, 10, "2g", 1.5), 1),
        r -> "tok", null, 1, 1, ms -> { if (n.incrementAndGet() == 2) db.update("UPDATE agent_job SET cancel_requested=1 WHERE task_id=?", t.id); });
    var e = assertThrows(AgentJobService.JobFailed.class, () -> svc.run(ctx(t, new ArrayList<>())));
    assertEquals("CANCELLED", e.state); assertTrue(rt.cancelled);
    assertEquals("CANCELLED", db.row("SELECT state FROM agent_job WHERE task_id=?", t.id).get("state"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.NullAndEmptySource
  @org.junit.jupiter.params.provider.ValueSource(strings = {"dddddddddddddddddddddddddddddddddddddddd"})
  void missingOrWrongResultBaseCannotFallBackAndOverwriteTheBranch(String returnedBase) {
    var t = task(); var context = ctx(t, new ArrayList<>());
    var res = new AgentRuntime.Result(List.of(new AgentRuntime.FileOut("src/a.js", "stale", false)), List.of(), "done", AgentRuntime.Usage.ZERO, "sha", returnedBase);
    var legacy = org.mockito.Mockito.mock(Models.ExecAgents.class);
    var rt = new FakeRuntime(new AgentRuntime.Status("SUCCEEDED", null, res));
    var wrapped = new AgentRuntimeAgents(legacy, svc(rt, new ArrayList<>()));
    assertThrows(com.x7x24.adapters.DeliveryTools.GateBlocked.class, () -> wrapped.code(context));
    org.mockito.Mockito.verifyNoInteractions(legacy);
    assertEquals("FAILED", db.row("SELECT state FROM agent_job WHERE task_id=?", t.id).get("state"));
  }

  @Test void aPrChangedWhileCodingRejectsTheResult() {
    var t = task(); t.context.pr = new com.x7x24.engine.TaskCtx.Pr(); t.context.pr.number = 42; t.context.pr.headSha = "b".repeat(40);
    var initial = ctx(t, new ArrayList<>()); var delivery = org.mockito.Mockito.mock(com.x7x24.adapters.DeliveryTools.class);
    org.mockito.Mockito.when(delivery.prHead(initial.line(), 42)).thenReturn(t.context.pr.headSha, "d".repeat(40));
    var context = new Models.Ctx(t, initial.line(), null, null, delivery, null, null, "failed CI", false, null, Models.ExecutionMode.REPAIR, null);
    var result = new AgentRuntime.Result(List.of(new AgentRuntime.FileOut("src/a.js", "stale", false)), List.of(), "done", AgentRuntime.Usage.ZERO, "sha", t.context.pr.headSha);
    var rt = new FakeRuntime(new AgentRuntime.Status("SUCCEEDED", null, result));
    assertThrows(com.x7x24.adapters.DeliveryTools.GateBlocked.class, () -> svc(rt, new ArrayList<>()).run(context));
    assertEquals(t.context.pr.headSha, rt.spec.baseRef());
  }

  @Test void remoteLifecycleUsesTheReportedModelInsteadOfTheConfiguredDefault() {
    var result = new AgentRuntime.Result(List.of(new AgentRuntime.FileOut("src/a.js", "x", false)), List.of(), "done", AgentRuntime.Usage.ZERO, "sha", "a".repeat(40));
    var runtime = new FakeRuntime(new AgentRuntime.Status("SUCCEEDED", null, result)) {
      @Override public Logs logs(Handle h, long from) { return from == 0 ? new Logs(List.of(new LogEvent(0, "t", "model", "{\"model\":\"runtime-reported-model\"}")), 1) : new Logs(List.of(), from); }
    };
    var task = repo.get(ingestIssue("远程模型记录 " + UUID.randomUUID(), "示例：sum([1,2]) => 3"));
    task.currentNode = "DEV";
    var service = svc(runtime, new ArrayList<>()); service.modelCalls = modelCalls;
    service.run(ctx(task, new ArrayList<>()));
    var rows = db.rows("SELECT payload FROM task_event WHERE task_id=? AND event_type='MODEL_CALL' ORDER BY id", task.id).stream().map(r -> com.x7x24.util.Json.tree(r.get("payload").toString())).toList();
    assertEquals(List.of("QUEUED", "RUNNING", "SUCCEEDED"), rows.stream().map(r -> r.path("state").asText()).toList());
    assertFalse(rows.getFirst().has("model"));
    assertEquals("runtime-reported-model", rows.getLast().path("model").asText());
    assertFalse(rows.toString().contains("deepseek-flash"), "configured defaults must not be presented as runtime evidence");
  }

  @Test void terminalResultRecoversModelWhenTheLogRequestFails() {
    var result = new AgentRuntime.Result(List.of(new AgentRuntime.FileOut("src/a.js", "x", false)),
        List.of(), "done", AgentRuntime.Usage.ZERO, "sha", "a".repeat(40), "gateway/observed");
    var runtime = new FakeRuntime(new AgentRuntime.Status("RUNNING", null, null), new AgentRuntime.Status("SUCCEEDED", null, result)) {
      @Override public Logs logs(Handle h, long from) { throw new IllegalStateException("logs unavailable"); }
    };
    var task = repo.get(ingestIssue("终态模型补报 " + UUID.randomUUID(), "示例：sum([1,2]) => 3"));
    task.currentNode = "DEV";
    var service = svc(runtime, new ArrayList<>()); service.modelCalls = modelCalls;
    service.run(ctx(task, new ArrayList<>()));

    var rows = db.rows("SELECT payload FROM task_event WHERE task_id=? AND event_type='MODEL_CALL' ORDER BY id", task.id)
        .stream().map(r -> com.x7x24.util.Json.tree(r.get("payload").toString())).toList();
    assertEquals(List.of("QUEUED", "RUNNING", "RUNNING", "SUCCEEDED"), rows.stream().map(r -> r.path("state").asText()).toList());
    assertFalse(rows.get(1).has("model"));
    assertEquals("gateway/observed", rows.get(2).path("model").asText());
    assertEquals("gateway/observed", rows.getLast().path("model").asText());
  }

  @Test void failureFallsBackToLegacyOnlyForFailed() {
    var legacy = new AtomicInteger();
    Models.ExecAgents base = new Models.ExecAgents() {
      public String mode() { return "rule"; } public String model() { return "m"; }
      public Models.Prd draftPrd(Models.Ctx c) { return null; } public Models.Plan plan(Models.Ctx c) { return null; }
      public List<Models.Case> specCases(Models.Ctx c) { return List.of(); } public Map<String, Object> design(Models.Ctx c) { return Map.of(); }
      public Models.CodeChange code(Models.Ctx c) { legacy.incrementAndGet(); return new Models.CodeChange(List.of(), "m", "s", "legacy"); }
    };
    var failed = new AgentRuntimeAgents(base, svc(new FakeRuntime(new AgentRuntime.Status("FAILED", "agent 用完轮数上限（61 轮，error_max_turns）", null)), new ArrayList<>()));
    var f = assertThrows(AgentJobService.JobFailed.class, () -> failed.code(ctx(task(), new ArrayList<>())));
    assertEquals(0, legacy.get(), "沙盒失败不能回退到旧的 JSON 编码方式");
    assertTrue(SandboxFailure.explain(f).contains("用完了对话轮数上限"));
    var timedOut = new AgentRuntimeAgents(base, svc(new FakeRuntime(new AgentRuntime.Status("TIMED_OUT", "slow", null)), new ArrayList<>()));
    assertThrows(AgentJobService.JobFailed.class, () -> timedOut.code(ctx(task(), new ArrayList<>()))); assertEquals(0, legacy.get());
    var legacyTask = task(); legacyTask.context.devModeOverride = DevMode.LEGACY;
    var started = new FakeRuntime(new AgentRuntime.Status("FAILED", "不应启动", null));
    var chosen = new AgentRuntimeAgents(base, svc(started, new ArrayList<>()));
    assertEquals("legacy", chosen.code(ctx(legacyTask, new ArrayList<>())).variant(), "主动选了快速方式才走旧实现");
    assertEquals("legacy", chosen.devMode(ctx(legacyTask, new ArrayList<>()))); assertEquals(0, db.count("SELECT COUNT(*) FROM agent_job WHERE task_id=?", legacyTask.id));
    legacy.set(0);
    var unconfigured = AgentRuntimeAgents.wrap(base, AgentRuntimeSettings.unconfigured(), () -> { throw new AssertionError("未配置时不应创建 job 服务"); });
    var u = assertThrows(AgentJobService.JobFailed.class, () -> unconfigured.code(ctx(task(), new ArrayList<>())));
    assertTrue(SandboxFailure.explain(u).contains("沙盒还没有配置")); assertEquals(0, legacy.get());
  }
}
