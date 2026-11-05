package com.x7x24.unit;

import com.x7x24.llm.LlmProvider;
import com.x7x24.llm.OpenAiCompatProvider;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** OpenAI 兼容 provider 对 mock HTTP 的行为：路径、结构化输出重试、429/5xx 退避、超时、密钥脱敏、用量与估算成本。 */
class OpenAiCompatProviderTest {
  /** fake test key — synthetic, never accepted by a real service */
  static final String KEY = "sk-test-" + "0123456789abcdefghijklmnop";
  MockWebServer server;
  @BeforeEach void up() throws Exception { server = new MockWebServer(); server.start(); }
  @AfterEach void down() throws Exception { server.shutdown(); }

  static String ok(String content, int in, int out) {
    return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + com.x7x24.util.Json.str(content) + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":" + in + ",\"completion_tokens\":" + out + ",\"total_tokens\":" + (in + out) + "}}";
  }
  MockResponse json(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
  OpenAiCompatProvider.Options fast() { return new OpenAiCompatProvider.Options().retries(3, 5, 50).timeouts(Duration.ofSeconds(2), Duration.ofSeconds(5)).price(1.0, 2.0); }
  LlmProvider.Request req() { return new LlmProvider.Request("t", "sys", "hello", "v1"); }

  @Test void executionAuthorityIsRecheckedImmediatelyBeforeHttpAndCannotRetryDeniedWork(){
    var p=new OpenAiCompatProvider(server.url("/").toString(),KEY,"fixture",fast());var calls=new java.util.concurrent.atomic.AtomicInteger();
    assertThrows(com.x7x24.workflow.WorkflowStore.Blocked.class,()->com.x7x24.execution.ExecutionGuard.with(()->{if(calls.incrementAndGet()>1)throw new com.x7x24.workflow.WorkflowStore.Blocked("STALE_EXECUTOR","执行权已撤销");},()->p.chat(req())));
    assertEquals(0,server.getRequestCount());assertFalse(com.x7x24.execution.ExecutionGuard.active());
  }
  @Test void outboundMessagesScrubSecretsIncludingRetryFeedback() throws Exception {
    String fixture = "synthetic-outbound-fixture-value";
    com.x7x24.adapters.Redactor.register(fixture);
    server.enqueue(json(ok("not-json " + fixture, 1, 1)));
    server.enqueue(json(ok("{}", 1, 1)));
    var p = new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast().persona(fixture));
    p.chat(new LlmProvider.Request("t", fixture, "task " + fixture, "v1"));
    for (int i = 0; i < 2; i++) {
      var request = server.takeRequest();
      assertEquals("Bearer " + KEY, request.getHeader("Authorization"));
      String body = request.getBody().readUtf8();
      assertFalse(body.contains(fixture)); assertTrue(body.contains("***"));
    }
  }

  @Test void normalCallHitsChatCompletionsAndRecordsUsage() throws Exception {
    server.enqueue(json(ok("{\"a\":1}", 1000, 500)));
    var p = new OpenAiCompatProvider(server.url("/").toString().replaceAll("/$", ""), KEY, "deepseek-chat", fast().temperature(0.3));
    var r = p.chat(req());
    assertEquals("{\"a\":1}", r.text()); assertEquals(1500, r.tokens()); assertEquals(1000 * 1.0 / 1e6 + 500 * 2.0 / 1e6, r.usd(), 1e-12);
    var rec = server.takeRequest(); assertEquals("/chat/completions", rec.getPath()); assertEquals("Bearer " + KEY, rec.getHeader("Authorization"));
    var body = com.x7x24.util.Json.tree(rec.getBody().readUtf8());
    assertEquals("deepseek-chat", body.path("model").asText()); assertEquals("json_object", body.path("response_format").path("type").asText()); assertEquals(0.3, body.path("temperature").asDouble());
  }
  @Test void v1PrefixAndFullPathBothWork() throws Exception {
    server.enqueue(json(ok("{}", 1, 1))); server.enqueue(json(ok("{}", 1, 1)));
    new OpenAiCompatProvider(server.url("/v1/").toString(), KEY, "m", fast()).chat(req());
    new OpenAiCompatProvider(server.url("/chat/completions").toString(), KEY, "m", fast()).chat(req());
    assertEquals("/v1/chat/completions", server.takeRequest().getPath()); assertEquals("/chat/completions", server.takeRequest().getPath());
  }
  @Test void reasonerModelOmitsResponseFormatAndTemperature() throws Exception {
    server.enqueue(json(ok("{}", 1, 1)));
    new OpenAiCompatProvider(server.url("/").toString(), KEY, "deepseek-reasoner", fast()).chat(req());
    var body = com.x7x24.util.Json.tree(server.takeRequest().getBody().readUtf8());
    assertFalse(body.has("response_format")); assertFalse(body.has("temperature"));
  }
  @Test void fencedJsonIsAccepted() {
    server.enqueue(json(ok("```json\n{\"x\":2}\n```", 1, 1)));
    assertEquals("{\"x\":2}", new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast()).chat(req()).text());
  }
  @Test void nonJsonOutputIsRetriedOnceAndTokensAreSummed() throws Exception {
    server.enqueue(json(ok("好的，这是结果", 100, 10))); server.enqueue(json(ok("{\"ok\":true}", 120, 20)));
    var r = new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast()).chat(req());
    assertEquals("{\"ok\":true}", r.text()); assertEquals(250, r.tokens()); assertEquals(2, server.getRequestCount());
    server.takeRequest(); var second = com.x7x24.util.Json.tree(server.takeRequest().getBody().readUtf8());
    assertTrue(second.path("messages").toString().contains("不合格"), "第二次请求应带上校验失败反馈");
  }
  @Test void validatorFailureRetriesOnceThenThrowsOutputException() {
    server.enqueue(json(ok("{\"a\":1}", 10, 1))); server.enqueue(json(ok("{\"a\":2}", 10, 1)));
    var p = new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast());
    var e = assertThrows(LlmProvider.OutputException.class, () -> p.chat(req(), j -> j.has("score") ? null : "缺少 score"));
    assertEquals(2, server.getRequestCount()); assertTrue(e.getMessage().contains("缺少 score"));
  }
  @Test void rateLimit429HonorsRetryAfterThenSucceeds() {
    server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "0").setBody("{\"error\":\"rate\"}")); server.enqueue(json(ok("{}", 1, 1)));
    new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast()).chat(req()); assertEquals(2, server.getRequestCount());
  }
  @Test void serverErrorsBackOffExponentially() {
    server.enqueue(new MockResponse().setResponseCode(503)); server.enqueue(new MockResponse().setResponseCode(500)); server.enqueue(json(ok("{}", 1, 1)));
    long t0 = System.currentTimeMillis();
    new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast().retries(3, 40, 500)).chat(req());
    assertEquals(3, server.getRequestCount()); assertTrue(System.currentTimeMillis() - t0 >= 40 + 80, "应至少退避 40ms + 80ms");
  }
  @Test void clientErrorFailsFastWithoutRetry() {
    server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key " + KEY + "\"}"));
    var e = assertThrows(IllegalStateException.class, () -> new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast()).chat(req()));
    assertEquals(1, server.getRequestCount()); assertTrue(e.getMessage().contains("401")); assertFalse(e.getMessage().contains(KEY), "错误信息不得含密钥");
  }
  @Test void persistentServerErrorGivesUpAndRedactsKey() {
    for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setResponseCode(500).setBody("echo " + KEY));
    var e = assertThrows(IllegalStateException.class, () -> new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast().retries(2, 5, 20)).chat(req()));
    assertEquals(3, server.getRequestCount()); assertFalse(e.getMessage().contains(KEY));
  }
  @Test void readTimeoutIsRetriedThenFails() {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    var p = new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast().retries(1, 5, 20).timeouts(Duration.ofSeconds(2), Duration.ofMillis(300)));
    var e = assertThrows(IllegalStateException.class, () -> p.chat(req()));
    assertEquals(2, server.getRequestCount()); assertTrue(e.getMessage().contains("LLM 调用失败"));
  }
  @Test void personaIsAppendedToSystemPrompt() throws Exception {
    server.enqueue(json(ok("{}", 1, 1)));
    new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast().persona("PERSONA-X")).chat(req());
    assertTrue(server.takeRequest().getBody().readUtf8().contains("PERSONA-X"));
  }
  static String truncated(String content, int out) {
    return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + com.x7x24.util.Json.str(content) + "},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":" + out + ",\"total_tokens\":" + (100 + out) + "}}";
  }
  @Test void lengthFinishReasonTriggersCompactRetryAndChargesUsageOnFailure() throws Exception {
    server.enqueue(json(truncated("{\"files\":[{\"path\":\"a.js\",\"content\":\"xxxx", 8192)));
    server.enqueue(json(ok("{\"ok\":true}", 50, 20)));
    var p = new OpenAiCompatProvider(server.url("/").toString(), KEY, "deepseek-chat", fast());
    var r = p.chat(new LlmProvider.Request("dev.code", "sys", "p", "v1", "只用 edits，最多 4 个文件"));
    assertEquals("{\"ok\":true}", r.text()); assertEquals(100 + 8192 + 50 + 20, r.tokens());
    server.takeRequest();
    var second = com.x7x24.util.Json.tree(server.takeRequest().getBody().readUtf8());
    assertEquals(8192, second.path("max_tokens").asInt(), "deepseek-chat 默认拉满输出上限");
    assertTrue(second.path("messages").toString().contains("只用 edits"), "截断重试应带 compactHint");
    assertFalse(second.path("messages").toString().contains("xxxx"), "截断输出不应回放进上下文");
  }
  @Test void lengthThenStillBadThrowsWithUsage() {
    server.enqueue(json(truncated("{incomplete", 8192)));
    server.enqueue(json(truncated("{still", 8192)));
    server.enqueue(json(truncated("{nope", 8192)));
    var p = new OpenAiCompatProvider(server.url("/").toString(), KEY, "deepseek-chat", fast());
    var e = assertThrows(LlmProvider.OutputException.class, () -> p.chat(req()));
    assertTrue(e.getMessage().contains("截断") || e.getMessage().contains("不合格"), e.getMessage());
    assertEquals(3 * (100 + 8192L), e.tokens); assertTrue(e.usd > 0);
  }
  @Test void parseObjectExtractsJsonBuriedInProse() {
    assertEquals(1, OpenAiCompatProvider.parseObject("说明如下：\n{\"a\":1}\n谢谢").path("a").asInt());
    assertNull(OpenAiCompatProvider.parseObject("{\"a\":"));
  }


  static String sse(String... pieces) {
    StringBuilder b = new StringBuilder();
    for (String p : pieces) b.append("data: {\"choices\":[{\"delta\":{\"content\":").append(com.x7x24.util.Json.str(p)).append("},\"finish_reason\":null}]}\n\n");
    b.append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n");
    return b.toString();
  }
  static class Rec implements LlmProvider.Stream { final java.util.List<String> got = new java.util.ArrayList<>(); int resets; volatile boolean cancel;
    public void token(String t) { got.add(t); } public void reset() { resets++; got.clear(); } public boolean cancelled() { return cancel; } }

  static String thought(String text) {
    return "data: {\"choices\":[{\"delta\":{\"reasoning_content\":" + com.x7x24.util.Json.str(text) + "}}]}\n\n";
  }
  static class ReasoningRec extends Rec {
    final java.util.List<String> thoughts = new java.util.ArrayList<>(), order = new java.util.ArrayList<>();
    public void reasoning(String t) { thoughts.add(t); order.add("reasoning"); }
    public void reasoningDone() { order.add("reasoning_done"); }
    @Override public void token(String t) { super.token(t); order.add("token"); }
    @Override public void reset() { super.reset(); thoughts.clear(); }
  }

  @Test void reasoningPiecesAreDeliveredAndEndBeforeTheAnswer() {
    server.enqueue(new MockResponse().setBody(thought("先检查需求。") + thought("再组织回复。") + sse("{\"reply\":\"好的\"}")));
    var rec = new ReasoningRec();
    new OpenAiCompatProvider(server.url("/").toString(), KEY, "deepseek-reasoner", fast()).chatStream(req(), null, rec);
    assertEquals(java.util.List.of("先检查需求。", "再组织回复。"), rec.thoughts);
    assertEquals(java.util.List.of("reasoning", "reasoning", "reasoning_done", "token"), rec.order);
    assertEquals(java.util.List.of("{\"reply\":\"好的\"}"), rec.got);
  }

  @Test void validationRetryReplacesReasoningInsteadOfDuplicatingIt() {
    server.enqueue(new MockResponse().setBody(thought("失败尝试") + sse("not json")));
    server.enqueue(new MockResponse().setBody(thought("重新检查") + sse("{\"ok\":true}")));
    var rec = new ReasoningRec();
    new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast()).chatStream(req(), null, rec);
    assertEquals(1, rec.resets); assertEquals(java.util.List.of("重新检查"), rec.thoughts);
    assertEquals(2, rec.order.stream().filter("reasoning_done"::equals).count());
  }

  @Test void failureAfterReasoningDoesNotSilentlyRetryAndDuplicateIt() {
    server.enqueue(new MockResponse().setBody(thought("已收到的思考")));
    server.enqueue(new MockResponse().setBody(thought("意外重试") + sse("{}")));
    var rec = new ReasoningRec();
    assertThrows(IllegalStateException.class, () -> new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast()).chatStream(req(), null, rec));
    assertEquals(1, server.getRequestCount()); assertEquals(java.util.List.of("已收到的思考"), rec.thoughts);
  }

  @Test void streamSendsStreamTrueAndDeliversPiecesWithUsage() throws Exception {
    server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse("{\"a\":", " 1", "}")));
    var p = new OpenAiCompatProvider(server.url("/").toString(), KEY, "deepseek-chat", fast()); var rec = new Rec();
    var r = p.chatStream(req(), null, rec);
    assertEquals(java.util.List.of("{\"a\":", " 1", "}"), rec.got); assertEquals("{\"a\":1}", r.text()); assertEquals(12, r.tokens());
    var body = server.takeRequest().getBody().readUtf8(); assertTrue(body.contains("\"stream\":true")); assertTrue(body.contains("include_usage"));
  }
  @Test void streamValidationRetryResetsBeforeSecondAttempt() {
    server.enqueue(new MockResponse().setBody(sse("not json")));
    server.enqueue(new MockResponse().setBody(sse("{\"ok\":true}")));
    var rec = new Rec(); var r = new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast()).chatStream(req(), null, rec);
    assertEquals(1, rec.resets); assertEquals("{\"ok\":true}", String.join("", rec.got)); assertEquals("{\"ok\":true}", r.text());
  }
  @Test void stream429BeforeFirstTokenIsRetried() {
    server.enqueue(new MockResponse().setResponseCode(429).setBody("{}"));
    server.enqueue(new MockResponse().setBody(sse("{}")));
    assertEquals("{}", new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast()).chatStream(req(), null, new Rec()).text());
    assertEquals(2, server.getRequestCount());
  }
  @Test void streamCancelStopsAndReturnsPartial() {
    server.enqueue(new MockResponse().setBody(sse("{\"reply\":\"a", "b", "c\"}")).throttleBody(40, 300, java.util.concurrent.TimeUnit.MILLISECONDS));
    var rec = new Rec() { @Override public void token(String t) { super.token(t); cancel = true; } };
    var e = assertThrows(LlmProvider.Cancelled.class, () -> new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast()).chatStream(req(), null, rec));
    assertTrue(e.partial.startsWith("{\"reply\":\"a"), e.partial); assertTrue(e.tokens > 0);
  }
  @Test void streamIdleTimeoutAborts() {
    long old = OpenAiCompatProvider.idleTimeoutMs; OpenAiCompatProvider.idleTimeoutMs = 600;
    try {
      server.enqueue(new MockResponse().setBody(sse("{\"reply\":\"a", "b\"}")).throttleBody(60, 3, java.util.concurrent.TimeUnit.SECONDS));
      var e = assertThrows(IllegalStateException.class, () -> new OpenAiCompatProvider(server.url("/").toString(), KEY, "m", fast().timeouts(Duration.ofSeconds(2), Duration.ofSeconds(30))).chatStream(req(), null, new Rec()));
      assertTrue(e.getMessage().contains("没有新输出") || e.getMessage().contains("中断"), e.getMessage());
    } finally { OpenAiCompatProvider.idleTimeoutMs = old; }
  }
}
