package com.x7x24.it;

import com.x7x24.agents.*;
import com.x7x24.engine.*;
import com.x7x24.llm.*;
import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelCallsIT extends Harness {
  @Autowired ModelCalls calls;
  Task task() {
    var t = repo.get(ingestIssue("模型可见性 " + UUID.randomUUID(), PipelineIT.SLUG));
    t.currentNode = "DEV"; t.currentStage = "DEV"; t.status = "DEV"; return t;
  }
  List<com.fasterxml.jackson.databind.JsonNode> events(Task t) {
    return db.rows("SELECT payload FROM task_event WHERE task_id=? AND event_type='MODEL_CALL' ORDER BY id", t.id).stream().map(r -> Json.tree(r.get("payload").toString())).toList();
  }
  LlmProvider provider(String model, java.util.function.Supplier<String> answer) {
    return new LlmProvider() {
      public String model() { return model; }
      public Response chat(Request r) { return new Response(answer.get(), model, r.promptVersion(), 10, .01); }
    };
  }
  final LlmProvider.Request request = new LlmProvider.Request("dev.code", "system", "prompt", "v1");

  @Test void actualFallbackModelsAreVisibleBeforeReturningAndUsageIsUnchanged() {
    var t = task(); var props = new X7Props(); props.llm.model = "first"; props.llm.cheapModel = "second"; props.llm.apiKey = "test";
    var config = ModelRouter.fromEnv(props); config.profiles.get("exec").chain = List.of("first", "second");
    var router = new ModelRouter(props, config, (p, key, m, temp, persona) -> provider(m.id, () -> {
      assertEquals("RUNNING", events(t).getLast().path("state").asText(), "start committed before provider call");
      if (m.id.equals("first")) throw new LlmProvider.OutputException("bad output", "first", 100, .02);
      return "{}";
    }));
    var response = calls.observe(router.maker("dev.code", t, null, false), t, "execution", "dev.code").chat(request);
    assertEquals(110, response.tokens()); assertEquals(.03, response.usd(), 1e-9);
    var rows = events(t);
    assertEquals(List.of("QUEUED", "RUNNING", "FAILED", "RUNNING", "SUCCEEDED"), rows.stream().map(e -> e.path("state").asText()).toList());
    assertFalse(rows.getFirst().has("model"), "queued is not a guessed model");
    assertEquals("first", rows.get(1).path("model").asText()); assertEquals("second", rows.getLast().path("model").asText());
    assertEquals("first", rows.get(3).path("previousModel").asText());
    assertEquals(1, rows.stream().map(e -> e.path("callId").asText()).distinct().count());
    assertEquals(1, rows.stream().map(e -> e.path("runId").asText()).distinct().count());
  }

  @Test void reviewFallbackModelSwitchIsVisibleOnTheReviewCall() {
    var t = task(); t.currentNode = "CODE_REVIEW"; t.currentStage = "CODE_REVIEW";
    var props = new X7Props(); props.llm.model = "deepseek-chat"; props.llm.judgeModels = "kimi-k2.7-code@0.2,deepseek-reasoner"; props.llm.apiKey = "test";
    var router = new ModelRouter(props, ModelRouter.fromEnv(props), (p, key, m, temp, persona) -> provider(m.id, () -> "{}"));
    // ocr 用的端点：席位候选链在前，备用模型 deepseek-chat 追加在末尾；本任务执行方用过的模型不给审查用
    var eps = router.reviewEndpoints(0, "review.code", Set.of(), null, List.of("deepseek-chat"));
    assertEquals("kimi-k2.7-code", eps.getFirst().model()); assertEquals("deepseek-chat", eps.getLast().model());
    assertFalse(eps.getFirst().toString().contains("test"), "toString 不带密钥");
    assertTrue(router.reviewEndpoints(0, "review.code", Set.of("deepseek-chat"), null, List.of("deepseek-chat")).stream().noneMatch(e -> e.model().equals("deepseek-chat")));
    var failed = com.x7x24.review.OcrResult.parse(Json.tree("{\"status\":\"failed\",\"llm\":{\"model\":\"kimi-k2.7-code\"},\"session_id\":\"aaaaaaaa-1\",\"manifest\":{\"coverage\":{\"selected\":[{\"path\":\"a.js\"}],\"failed\":[{\"path\":\"a.js\",\"classification\":\"timeout\",\"reason\":\"timed out\"}]}}}"));
    var ok = com.x7x24.review.OcrResult.parse(Json.tree("{\"status\":\"complete\",\"llm\":{\"model\":\"deepseek-chat\"},\"session_id\":\"aaaaaaaa-2\",\"comments\":[]}"));
    var outs = new ArrayDeque<>(List.of(new com.x7x24.review.OcrRunner.Outcome("SUCCEEDED", null, failed), new com.x7x24.review.OcrRunner.Outcome("SUCCEEDED", null, ok)));
    List<String> resumes = new ArrayList<>();
    com.x7x24.review.OcrRunner runner = (r, c, l) -> { resumes.add(String.valueOf(r.resume())); return outs.removeFirst(); };
    var session = calls.reviewRun(t, "run-1", "CODE_REVIEW");
    List<com.x7x24.review.OcrRunner.Llm> llms = eps.stream().filter(e -> !e.model().equals("deepseek-reasoner")).map(e -> new com.x7x24.review.OcrRunner.Llm(e.model(), e.baseUrl(), e.apiKey(), e.protocol(), 0, 0)).toList();
    var out = com.x7x24.review.CodeReviewRun.run(runner, new com.x7x24.review.OcrRunner.Request("j", "k", "o/r", "main", "abc1234", "", null, null, 0, 15, 8, 60, List.of()), llms, null, () -> false,
        new com.x7x24.review.CodeReviewRun.Listener() { public void started(String m) { session.started(m); } public void finished(String m, String s) { session.finished(m, s); } }, null);
    assertTrue(out.done()); assertEquals(List.of("null", "aaaaaaaa-1"), resumes, "换备用模型时接着上一次的会话");
    var rows = events(t);
    assertEquals(List.of("QUEUED", "RUNNING", "FAILED", "RUNNING", "SUCCEEDED"), rows.stream().map(e -> e.path("state").asText()).toList());
    assertEquals(1, rows.stream().map(e -> e.path("callId").asText()).distinct().count(), "换模型合成一条记录");
    assertEquals("kimi-k2.7-code", rows.get(3).path("previousModel").asText()); assertEquals("deepseek-chat", rows.getLast().path("model").asText());
    assertTrue(rows.stream().allMatch(e -> e.path("node").asText().equals("CODE_REVIEW") && e.path("source").asText().equals("ocr")));
  }

  @Test void parallelReviewSeatsHaveIndependentCallsAndOneGroup() throws Exception {
    var t = task(); var entered = new CountDownLatch(3); var release = new CountDownLatch(1);
    var reviewers = java.util.stream.IntStream.range(0, 3).mapToObj(i -> provider("judge-" + i, () -> {
      entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); } catch (InterruptedException e) { throw new IllegalStateException(e); }
      return "{\"score\":0.8,\"block\":false,\"rationale\":\"ok\"}";
    })).toList();
    var committee = new Committee(new Providers("llm", reviewers.getFirst(), reviewers.getFirst(), reviewers, reviewers.getLast()), cfg);
    committee.modelCalls = calls;
    var executor = Executors.newSingleThreadExecutor();
    var result = executor.submit(() -> committee.convene(t, false, Map.of(), List.of("accept.risk", "accept.cases", "accept.ux"), "accept.arbiter", .7, 0, (a, r) -> {}, "test"));
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var running = events(t).stream().filter(e -> e.path("state").asText().equals("RUNNING")).toList();
      assertEquals(3, running.size()); assertEquals(3, running.stream().map(e -> e.path("callId").asText()).distinct().count());
      assertEquals(1, running.stream().map(e -> e.path("groupId").asText()).distinct().count());
      assertTrue(running.stream().allMatch(e -> e.path("total").asInt() == 3));
      release.countDown(); assertEquals(3, result.get(5, TimeUnit.SECONDS).votes().size());
      assertEquals(3, events(t).stream().filter(e -> e.path("state").asText().equals("SUCCEEDED")).count());
    } finally { release.countDown(); executor.shutdownNow(); }
  }

  @Test void arbitrationAndRepeatedCallsRemainSeparateAndCancelledCallsClose() {
    var t = task(); var p = calls.observe(provider("gateway-alias", () -> "{}"), t, "execution", "dev.code");
    int iteration = t.currentIteration; t.currentIteration++; t.currentNode = "QA";
    p.chat(request); p.chat(request);
    assertEquals(2, events(t).stream().map(e -> e.path("callId").asText()).distinct().count());
    assertTrue(events(t).stream().allMatch(e -> e.path("node").asText().equals("DEV")), "context fixed when dispatched");
    assertEquals(iteration, db.row("SELECT iteration_seq FROM task_event WHERE task_id=? AND event_type='MODEL_CALL' ORDER BY id LIMIT 1", t.id).get("iteration_seq"));
    var cancelled = calls.observe(provider("cancel-model", () -> { throw new LlmProvider.Cancelled("", "cancel-model", 0, 0); }), t, "arbitration", "accept.arbiter");
    assertThrows(LlmProvider.Cancelled.class, () -> cancelled.chat(request));
    assertEquals("CANCELLED", events(t).getLast().path("state").asText());
    assertEquals("arbitration", events(t).getLast().path("kind").asText());
  }

  @Test void remoteModelComesFromRuntimeAndDuplicateReportsAreIgnored() {
    var t = task(); var session = calls.remote(t, null, "job-" + UUID.randomUUID());
    assertFalse(events(t).getFirst().has("model"));
    session.reported("runtime-actual"); session.reported("runtime-actual"); session.reported("runtime-fallback"); session.finish("CANCELLED");
    assertEquals(4, events(t).size());
    assertEquals("runtime-actual", events(t).get(2).path("previousModel").asText());
    assertEquals("runtime-fallback", events(t).getLast().path("model").asText());
    assertEquals("agent", events(t).getLast().path("source").asText());
  }

  @Test void noAvailableProviderClosesWithoutClaimingTheConfiguredModelRan() {
    var t = task(); var props = new X7Props(); props.llm.model = "configured-only";
    var router = new ModelRouter(props, null, (p, k, m, temp, persona) -> { throw new AssertionError("no call allowed"); });
    assertThrows(LlmProvider.OutputException.class, () -> calls.observe(router.maker("dev.code", t, null, false), t, "execution", "dev.code").chat(request));
    assertEquals(List.of("QUEUED", "FAILED"), events(t).stream().map(e -> e.path("state").asText()).toList());
    assertTrue(events(t).stream().noneMatch(e -> e.has("model")));
  }

  @Test void aDisagreementAddsAnArbiterToTheSameReviewGroup() {
    var t = task();
    var judges = List.of(provider("judge-a", () -> "{\"score\":0.1,\"block\":false}"), provider("judge-b", () -> "{\"score\":0.9,\"block\":false}"), provider("judge-c", () -> "{\"score\":0.9,\"block\":false}"));
    var committee = new Committee(new Providers("llm", judges.getFirst(), judges.getFirst(), judges, provider("arbiter", () -> "{\"score\":0.8,\"block\":false}")), cfg);
    committee.modelCalls = calls;
    var result = committee.convene(t, false, Map.of(), List.of("accept.risk", "accept.cases", "accept.ux"), "accept.arbiter", .7, 0, (a, r) -> {}, "test");
    assertTrue(result.arbitrated()); assertEquals(4, result.votes().size());
    var running = events(t).stream().filter(e -> e.path("state").asText().equals("RUNNING")).toList();
    assertEquals(4, running.size()); assertEquals(1, running.stream().map(e -> e.path("groupId").asText()).distinct().count());
    assertEquals("arbitration", running.getLast().path("kind").asText());
    assertEquals("arbiter", running.getLast().path("model").asText());
  }
}
