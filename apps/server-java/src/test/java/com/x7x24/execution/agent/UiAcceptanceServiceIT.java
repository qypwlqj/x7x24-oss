package com.x7x24.execution.agent;

import com.x7x24.engine.ProjectCfg;
import com.x7x24.engine.Task;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UiAcceptanceServiceIT extends com.x7x24.it.Harness {
  static class Fake implements AgentRuntime {
    final AcceptanceStatus finalStatus; AcceptanceSpec spec; int polls;
    Fake(AcceptanceStatus s) { finalStatus = s; }
    public String id() { return "fake"; }
    public Handle start(JobSpec s) { throw new UnsupportedOperationException(); }
    public Status poll(Handle h) { throw new UnsupportedOperationException(); }
    public Handle startAcceptance(AcceptanceSpec s) { spec = s; return new Handle("fake", "a1"); }
    public AcceptanceStatus pollAcceptance(Handle h) { return polls++ == 0 ? new AcceptanceStatus("RUNNING", null, null) : finalStatus; }
    public Logs logs(Handle h, long from) { return from == 0 ? new Logs(List.of(new LogEvent(0, "t", "system", "app ready")), 1) : new Logs(List.of(), from); }
    public void cancel(Handle h, String r) { }
  }
  UiAcceptanceService svc(Fake rt, Path dir, boolean images) {
    var s = new AgentRuntimeSettings("remote-docker", "http://x", "k".repeat(32), "deepseek-flash", new AgentRuntime.Limits(600, 10, "2g", 1.5), 1, false, true, "2560m");
    var judge = new AcceptanceJudge(new AcceptanceJudge.Config("", "", "m", images, 8));
    return new UiAcceptanceService(db, clk, rt, s, r -> "tok", null, judge, dir, ms -> { });
  }
  ProjectCfg cfg(boolean visualBlocking) { var c = new ProjectCfg(); c.repo = "o/r"; c.uiAcceptance.enabled = true; c.uiAcceptance.start = "npm run dev"; c.uiAcceptance.visualBlocking = visualBlocking; return c; }
  Task task() { var t = new Task(); t.id = "t-" + UUID.randomUUID(); t.workspaceId = "platform"; return t; }

  @Test void passingScriptsSaveScreenshotsAndRecordRun() throws Exception {
    Path dir = Files.createTempDirectory("ua");
    var res = new AgentRuntime.AcceptanceResult("tests", 0, List.of(new AgentRuntime.TestCase("AC-1", "passed", null)), 1, 0,
        List.of(new AgentRuntime.Shot("00-root.png", AcceptanceJudgeTest.PNG), new AgentRuntime.Shot("../evil.png", AcceptanceJudgeTest.PNG)), Map.of("00-root", "tree"), "");
    var rt = new Fake(new AgentRuntime.AcceptanceStatus("SUCCEEDED", null, res)); var t = task();
    var o = svc(rt, dir, true).run(t, cfg(false), "agent/abc-it1", List.of("有登录按钮"));
    assertEquals("PASS", o.verdict().verdict()); assertFalse(o.blocked());
    assertEquals("2560m", rt.spec.limits().memory()); assertEquals("agent/abc-it1", rt.spec.ref()); assertEquals("npm run dev", rt.spec.start());
    assertTrue(Files.exists(dir.resolve("ui-acceptance").resolve(o.jobId()).resolve("00-root.png")));
    assertEquals(List.of("00-root.png"), o.report().get("screenshots"));
    var row = db.row("SELECT verdict,blocked FROM ui_acceptance_run WHERE id=?", o.jobId());
    assertEquals("PASS", row.get("verdict"));
    assertEquals("ACCEPTANCE", db.row("SELECT mode FROM agent_job WHERE id=?", o.jobId()).get("mode"));
    assertTrue(UiAcceptanceService.prComment(o, "https://7x24.example/tasks/" + t.id).contains("页面验收：PASS"));
  }
  @Test void scriptFailureOrRunnerFailureBlocks() throws Exception {
    Path dir = Files.createTempDirectory("ua");
    var res = new AgentRuntime.AcceptanceResult("tests", 1, List.of(new AgentRuntime.TestCase("AC-1", "failed", "expect")), 0, 1, List.of(), Map.of(), "");
    assertTrue(svc(new Fake(new AgentRuntime.AcceptanceStatus("SUCCEEDED", null, res)), dir, true).run(task(), cfg(false), "b", List.of("x")).blocked());
    var o = svc(new Fake(new AgentRuntime.AcceptanceStatus("FAILED", "clone 失败", null)), dir, true).run(task(), cfg(false), "b", List.of("x"));
    assertTrue(o.blocked()); assertEquals("FAIL", o.verdict().verdict());
  }
}
