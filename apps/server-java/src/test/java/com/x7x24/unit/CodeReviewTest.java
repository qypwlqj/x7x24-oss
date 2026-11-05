package com.x7x24.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.DeliveryTools.FilePatch;
import com.x7x24.engine.Engine;
import com.x7x24.execution.agent.AgentRuntime;
import com.x7x24.execution.agent.AgentRuntimeSettings;
import com.x7x24.execution.agent.RemoteDockerRuntime;
import com.x7x24.execution.agent.RemoteOcrRunner;
import com.x7x24.gov.Governance;
import com.x7x24.review.*;
import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 逐行审查（open-code-review v1.13.5）：解析录制的 ocr JSON、判定、换备用模型 + --resume、离线规则版会话。 */
class CodeReviewTest {
  static JsonNode fixture(String name) {
    try (InputStream in = CodeReviewTest.class.getResourceAsStream("/ocr/" + name)) { return Json.tree(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }
  static final OcrRunner.Request REQ = new OcrRunner.Request("job", "t1:CODE_REVIEW", "o/r", "main", "abc1234", "背景", null, null, 600_000, 15, 8, 60, List.of());
  static OcrRunner.Llm llm(String m) { return new OcrRunner.Llm(m, "https://api.example.com/v1", "fake-token-for-tests", "openai", 1.0, 2.0); }

  @Test void parsesRecordedCompleteRunAndDedupesRepeatedRounds() {
    var r = OcrResult.parse(fixture("ocr-complete.json"));
    assertEquals("complete", r.status()); assertTrue(r.complete()); assertNotNull(r.sessionId()); assertEquals("fake-model", r.model());
    assertEquals(1, r.findings().size(), "两轮报出的相同意见只算一次"); assertEquals(1, r.unlocated().size());
    var f = r.findings().getFirst();
    assertEquals("src/app.js", f.path()); assertEquals(5, f.startLine()); assertEquals("high", f.severity()); assertEquals("bug", f.category());
    assertTrue(f.blocking(), "high + bug 阻断"); assertEquals("  return user?.name?.toUpperCase() ?? '';", f.suggestion());
    var u = r.unlocated().getFirst(); assertEquals(0, u.startLine()); assertFalse(u.blocking(), "没定位到行的意见不阻断");
    assertEquals(Map.of("critical", 0, "high", 1, "medium", 1, "low", 0), r.bySeverity());
    assertEquals(List.of("src/app.js"), r.selected()); assertEquals(List.of("src/app.js"), r.reviewed()); assertEquals(960, r.totalTokens());
    assertEquals(Boolean.TRUE, f.map().get("blocking")); assertEquals(Boolean.TRUE, u.map().get("unlocated"));
  }
  @Test void parsesRecordedFailedAndResumedRuns() {
    var failed = OcrResult.parse(fixture("ocr-failed.json"));
    assertFalse(failed.complete()); assertFalse(failed.onlyBudget()); assertEquals(1, failed.failed().size());
    assertEquals("timeout", failed.failed().getFirst().classification()); assertTrue(failed.findings().isEmpty(), "comments 为 null 也能解析");
    var resumed = OcrResult.parse(fixture("ocr-resumed.json"));
    assertTrue(resumed.complete()); assertEquals(failed.sessionId(), resumed.resumedFrom());
  }
  @Test void unlocatedCriticalNeverBlocksAndUnknownEnumsAreNormalised() {
    var r = OcrResult.parse(Json.tree("""
        {"status":"complete","session_id":"11111111-2222","comments":[
          {"path":"a.js","content":"x","start_line":0,"end_line":0,"severity":"critical","category":"security"},
          {"path":"a.js","content":"y","start_line":3,"end_line":2,"severity":"BLOCKER","category":"weird"},
          {"path":"b.js","content":"z","start_line":7,"end_line":7,"severity":"high","category":"style"}]}"""));
    assertTrue(r.blocking().isEmpty(), "定位失败的 critical、style 类的 high 都不阻断");
    assertEquals("medium", r.findings().stream().filter(f -> f.path().equals("a.js")).findFirst().orElseThrow().severity());
    assertEquals(3, r.findings().stream().filter(f -> f.path().equals("a.js")).findFirst().orElseThrow().endLine(), "end < start 时取 start");
  }
  @Test void budgetOnlyPartialCountsAsDone() {
    var r = OcrResult.parse(Json.tree("""
        {"status":"partial","summary":{"budget_exceeded":true},"manifest":{"coverage":{"selected":[{"path":"a"},{"path":"b"}],"completed":[{"path":"a"}],"failed":[{"path":"b","classification":"budget","reason":"token budget"}]}}}"""));
    assertTrue(r.onlyBudget()); assertFalse(r.complete());
    var cov = Engine.codeReviewCoverage(r, false);
    assertEquals(true, cov.get("partial")); assertEquals(1, cov.get("reviewedFiles")); assertEquals(2, cov.get("totalFiles"));
    assertEquals("只审了 1/2 个文件，1 个没审（超出本次审查的 Token 预算 1 个）：b", cov.get("coverageText"));
  }

  /** 假 runner：按顺序返回预设结果，记下每次请求 */
  static class Scripted implements OcrRunner {
    final Deque<Outcome> outs = new ArrayDeque<>(); final List<Request> seen = new ArrayList<>();
    Scripted(Outcome... o) { outs.addAll(List.of(o)); }
    @Override public Outcome review(Request r, java.util.function.BooleanSupplier c, java.util.function.Consumer<String> log) { seen.add(r); return outs.removeFirst(); }
  }
  @Test void primaryFailsThenFallbackResumesTheSameSession() {
    var failed = OcrResult.parse(fixture("ocr-failed.json")); var ok = OcrResult.parse(fixture("ocr-resumed.json"));
    var runner = new Scripted(new OcrRunner.Outcome("SUCCEEDED", null, failed), new OcrRunner.Outcome("SUCCEEDED", null, ok));
    List<String> events = new ArrayList<>(); List<String> logs = new ArrayList<>();
    var out = CodeReviewRun.run(runner, REQ, List.of(llm("kimi-k2.7-code"), llm("deepseek-chat")), null, () -> false, new CodeReviewRun.Listener() {
      public void started(String m) { events.add("start " + m); } public void finished(String m, String s) { events.add(s + " " + m); } }, logs::add);
    assertTrue(out.done()); assertEquals(2, out.attempts().size());
    assertNull(runner.seen.get(0).resume()); assertEquals(failed.sessionId(), runner.seen.get(1).resume(), "换模型时 --resume 上一次的会话");
    assertEquals("deepseek-chat", runner.seen.get(1).llm().model());
    assertEquals(List.of("start kimi-k2.7-code", "FAILED fake-model", "start deepseek-chat", "SUCCEEDED fake-model"), events);
    assertTrue(logs.getFirst().contains("换 deepseek-chat 接着审"), logs.toString());
    assertEquals("INCOMPLETE", out.attempts().getFirst().state());
    assertEquals(960, out.tokens()); assertEquals((800 * 1.0 + 160 * 2.0) / 1e6, out.usd(), 1e-9);
  }
  @Test void allModelsFailKeepsSessionForRetryAndStops() {
    var failed = OcrResult.parse(fixture("ocr-failed.json"));
    var runner = new Scripted(OcrRunner.Outcome.failed("FAILED", "runner 不可达"), new OcrRunner.Outcome("SUCCEEDED", null, failed));
    var out = CodeReviewRun.run(runner, REQ, List.of(llm("a"), llm("b")), "prev-session", () -> false, null, null);
    assertFalse(out.done()); assertFalse(out.cancelled());
    assertEquals("prev-session", runner.seen.get(0).resume(), "重试时接着上次的会话");
    assertEquals("prev-session", runner.seen.get(1).resume(), "runner 没返回会话时继续用原来的");
    assertEquals(failed.sessionId(), out.sessionId()); assertTrue(out.error().contains("1 个文件没审完"), out.error());
  }
  @Test void pausedBeforeStartDoesNotCallRunner() {
    var runner = new Scripted();
    var out = CodeReviewRun.run(runner, REQ, List.of(llm("a")), "s1", () -> true, null, null);
    assertTrue(out.cancelled()); assertTrue(runner.seen.isEmpty()); assertEquals("s1", out.sessionId());
  }

  @Test void requestNeverPrintsTheKeyAndBodyCarriesSkillFlags() {
    var r = new OcrRunner.Request("cr-1", "task:CODE_REVIEW", "o/r", "main", "abc1234", "背景", llm("kimi"), "sess-1", 600_000, 15, 8, 60, List.of());
    assertFalse(r.toString().contains("fake-token-for-tests")); assertFalse(r.llm().toString().contains("fake-token-for-tests"));
    var b = RemoteOcrRunner.body(r, "gh", new AgentRuntime.Limits(3600, 1, "2g", 1.5));
    assertEquals("abc1234", b.get("headSha")); assertEquals("sess-1", b.get("resume")); assertEquals(600_000L, b.get("maxTokensBudget"));
    assertEquals("fake-token-for-tests", ((Map<?, ?>) b.get("llm")).get("token"), "密钥只放在签名请求体里交给 runner");
  }
  @Test void remoteRunnerPollsUntilDoneAndParsesOcrJson() {
    var doc = fixture("ocr-complete.json");
    List<String> calls = new ArrayList<>();
    var rt = new RemoteDockerRuntime("http://127.0.0.1:8790", "k".repeat(40)) {
      int polls;
      @Override public Handle startReview(Map<String, Object> body) { calls.add("start " + ((Map<?, ?>) body.get("llm")).get("model")); return new Handle(id(), "job_1"); }
      @Override public JsonNode job(Handle h) { calls.add("poll"); return Json.M.valueToTree(++polls < 2 ? Map.of("state", "RUNNING") : Map.of("state", "SUCCEEDED", "result", Map.of("ocr", doc))); }
    };
    var settings = new AgentRuntimeSettings("remote-docker", "http://127.0.0.1:8790", "k".repeat(40), "m", AgentRuntime.Limits.defaults(), 10);
    var runner = new RemoteOcrRunner(rt, settings, repo -> "gh", ms -> { });
    var o = runner.review(new OcrRunner.Request("cr-1", "t:CR", "o/r", "main", "abc1234", "", llm("kimi"), null, 0, 15, 8, 60, List.of()), () -> false, null);
    assertEquals("SUCCEEDED", o.state()); assertTrue(o.result().complete()); assertEquals(List.of("start kimi", "poll", "poll"), calls);
    var none = runner.review(new OcrRunner.Request("cr-2", "t:CR", "o/r", "main", "abc1234", "", null, null, 0, 15, 8, 60, List.of()), () -> false, null);
    assertEquals("FAILED", none.state()); assertTrue(none.error().contains("没有可用的审查模型"));
  }

  @Test void ruleRunnerResumeReusesFinishedFiles() {
    var rr = new RuleOcrRunner();
    var ok = new FilePatch("src/a.js", "added", 2, 0, "@@ -0,0 +1,2 @@\n+export const KEY = \"sk-abcdefghijklmnopqrstuvwxyz0123\";\n+console.log(1);");
    var bad = new FilePatch("src/b.js", "added", 1, 0, "@@ -0,0 +1,1 @@\n+export const b = \"" + RuleOcrRunner.UNREACHABLE_FIXTURE + "\";");
    var first = rr.review(new OcrRunner.Request("j", "k", "o/r", "main", "h1", "", null, null, 0, 15, 8, 60, List.of(ok, bad)), () -> false, null).result();
    assertEquals("partial", first.status()); assertEquals(List.of("src/b.js"), first.failed().stream().map(OcrResult.FileFailure::path).toList());
    assertEquals("critical", first.findings().getFirst().severity()); assertEquals(1, first.findings().getFirst().startLine());
    assertEquals(2, first.findings().get(1).startLine(), "console.log 在第 2 行");
    var fixed = new FilePatch("src/b.js", "added", 1, 0, "@@ -0,0 +1,1 @@\n+export const b = 1;");
    var second = rr.review(new OcrRunner.Request("j2", "k", "o/r", "main", "h1", "", null, first.sessionId(), 0, 15, 8, 60, List.of(ok, fixed)), () -> false, null).result();
    assertTrue(second.complete()); assertEquals(first.sessionId(), second.resumedFrom()); assertEquals(2, second.findings().size(), "沿用 a.js 的结果");
  }
  @Test void governanceDefaultsDropGroupKnobs() {
    var cr = new Governance().codeReview;
    assertEquals(List.of("deepseek-chat"), cr.fallbackModels); assertEquals(600_000, cr.maxTokens); assertEquals(15, cr.timeoutMin); assertEquals(8, cr.concurrency);
    var old = Json.convert(Json.tree("{\"maxGroups\":8,\"groupChars\":14000,\"blocking\":false}"), Governance.CodeReview.class);
    assertFalse(old.blocking, "旧配置里的分组字段直接忽略");
  }
}
