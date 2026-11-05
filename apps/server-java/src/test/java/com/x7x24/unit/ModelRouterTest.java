package com.x7x24.unit;

import com.x7x24.engine.Task;
import com.x7x24.engine.TaskCtx;
import com.x7x24.engine.X7Props;
import com.x7x24.llm.LlmProvider;
import com.x7x24.llm.ModelRouter;
import com.x7x24.pipeline.Pipeline.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ModelRouterTest {
  final Map<String, RuntimeException> failing = new HashMap<>(); final List<String> calls = new ArrayList<>();
  final ModelRouter.Factory fake = (p, key, m, t, persona) -> new LlmProvider() {
    public String model() { return m.id; }
    public Response chat(Request r) { calls.add(m.id); var e = failing.get(m.id); if (e != null) throw e; return new Response("{}", m.id, "v", 10, 0.01); }
  };
  X7Props props() { var p = new X7Props(); p.llm.baseUrl = "https://api.deepseek.com"; p.llm.apiKey = "k"; p.llm.model = "deepseek-chat"; p.llm.judgeModels = "deepseek-chat@0.2,deepseek-chat@0.7,deepseek-reasoner"; return p; }
  ModelConfig twoModels(X7Props p) { var c = ModelRouter.fromEnv(p); c.profiles.get("exec").chain = new ArrayList<>(List.of("deepseek-chat", "deepseek-reasoner")); return c; }
  Task task() { var t = new Task(); t.id = "t"; t.context = new TaskCtx(); return t; }

  @Test void migratesEnvExactly() {
    var c = ModelRouter.fromEnv(props());
    assertTrue(c.migratedFromEnv); assertEquals(List.of("deepseek-chat"), c.profiles.get("exec").chain);
    var seats = c.profiles.get("judges").seats; assertEquals(3, seats.size());
    assertEquals(0.2, seats.get(0).temperature); assertEquals(0.7, seats.get(1).temperature); assertEquals(0.7, seats.get(2).temperature);
    assertEquals(List.of("deepseek-reasoner"), seats.get(2).chain); assertEquals(ModelRouter.PERSONAS.get(1), seats.get(1).persona);
    assertEquals(List.of(), ModelRouter.validate(c, null));
  }
  @Test void singleModelBehavesLikeBefore() {
    var r = new ModelRouter(props(), null, fake); failing.put("deepseek-chat", new LlmProvider.OutputException("bad", "deepseek-chat", 40, 0.02));
    var e = assertThrows(LlmProvider.OutputException.class, () -> r.maker("dev.code", task(), null, false).chat(new LlmProvider.Request("dev.code", "s", "p", "v")));
    assertEquals("bad", e.getMessage()); assertEquals(40, e.tokens);
    assertNull(r.nextModel(task(), null), "只有一个模型时 switch_model 无可切换");
  }
  @Test void fallsBackAlongChainAndKeepsUsage() {
    var p = props(); var r = new ModelRouter(p, twoModels(p), fake);
    failing.put("deepseek-chat", new LlmProvider.OutputException("truncated", "deepseek-chat", 100, 0.05));
    var res = r.maker("dev.code", task(), null, false).chat(new LlmProvider.Request("dev.code", "s", "p", "v"));
    assertEquals("deepseek-reasoner", res.model()); assertEquals(110, res.tokens(), "失败调用的用量也计入");
  }
  @Test void breakerOpensAndSkipsModel() {
    var p = props(); var r = new ModelRouter(p, twoModels(p), fake); failing.put("deepseek-chat", new RuntimeException("503"));
    for (int i = 0; i < 6; i++) r.maker("dev.code", task(), null, false).chat(new LlmProvider.Request("dev.code", "s", "p", "v"));
    assertTrue(r.open("deepseek-chat")); calls.clear();
    r.maker("dev.code", task(), null, false).chat(new LlmProvider.Request("dev.code", "s", "p", "v"));
    assertEquals(List.of("deepseek-reasoner"), calls, "熔断后直接走兜底模型");
  }
  @Test void healthKeepsCountsAndRedactedLastErrorAfterTrip() {
    var p = props(); var r = new ModelRouter(p, twoModels(p), fake);
    failing.put("deepseek-chat", new IllegalStateException("LLM 调用失败（已重试 2 次）: LLM HTTP 401: {\"error\":\"Invalid key sk-abcdefghijklmnopqrstuvwx Bearer abcdefghijklmnop\"}"));
    for (int i = 0; i < 6; i++) r.maker("dev.code", task(), null, false).chat(new LlmProvider.Request("dev.code", "s", "p", "v"));
    assertTrue(r.open("deepseek-chat"));
    @SuppressWarnings("unchecked") var h = (Map<String, Object>) r.breakerStatus().get("deepseek-chat");
    assertTrue(((Number) h.get("recent")).intValue() > 0, "熔断后仍显示真实调用次数"); assertTrue(((Number) h.get("failures")).intValue() > 0);
    String err = String.valueOf(h.get("lastError"));
    assertTrue(err.startsWith("LLM HTTP 401"), err); assertFalse(err.contains("sk-abcdef"), err); assertFalse(err.contains("abcdefghijklmnop\""), err);
    assertEquals(401, h.get("lastStatus")); assertNotNull(h.get("lastErrorAt"));
    assertTrue(r.pausedReasons().getFirst().startsWith("deepseek-chat 暂停：LLM HTTP 401"));
  }
  @Test void healthOnlyIncludesModelsStillRegisteredInTheCurrentWorkspace() {
    var p=props();var r=new ModelRouter(p,twoModels(p),fake);
    r.maker("dev.code",task(),null,false).chat(new LlmProvider.Request("dev.code","s","p","v"));
    assertTrue(r.breakerStatus().containsKey("deepseek-chat"));
    r.resetForTest(ModelRouter.blank());
    assertTrue(r.breakerStatus().isEmpty(),"removed models must not appear healthy");
  }
  @Test void switchModelPinsNextModel() {
    var p = props(); var r = new ModelRouter(p, twoModels(p), fake); var t = task();
    assertEquals("deepseek-reasoner", r.nextModel(t, null));
    t.context.modelPins.put("*", "deepseek-reasoner");
    r.maker("dev.code", t, null, false).chat(new LlmProvider.Request("dev.code", "s", "p", "v"));
    assertEquals(List.of("deepseek-reasoner"), calls);
  }
  @Test void judgeSeatPrefersModelsMakerDidNotUse() {
    var p = props(); var c = ModelRouter.fromEnv(p); c.profiles.get("judges").seats.get(0).chain = new ArrayList<>(List.of("deepseek-chat", "deepseek-reasoner"));
    var r = new ModelRouter(p, c, fake);
    r.seat(0, "prd.value", Set.of("deepseek-chat")).chat(new LlmProvider.Request("prd.value", "s", "p", "v"));
    assertEquals(List.of("deepseek-reasoner"), calls);
  }
  @Test void resolvedSeatKeepsItsModelsUntilTheCurrentReviewFinishes() {
    var p=props();var c=ModelRouter.fromEnv(p);var r=new ModelRouter(p,c,fake);
    var seat=r.seat(2,"review.prd",Set.of());
    var next=ModelRouter.fromEnv(p);next.profiles.get("judges").seats.get(0).chain=List.of("deepseek-reasoner");next.profiles.get("judges").seats.get(2).chain=List.of("deepseek-chat");r.reload(next);
    assertEquals("deepseek-reasoner",seat.model());
    assertEquals("deepseek-reasoner",seat.chat(new LlmProvider.Request("review.prd","s","p","v")).model());
    assertEquals("deepseek-chat",r.seat(2,"review.prd",Set.of()).model());
  }
  @Test void secondProviderWithoutKeyIsSkipped() {
    var p = props(); var c = ModelRouter.fromEnv(p);
    var pv = new Provider(); pv.id = "anthropic"; pv.baseUrl = "https://api.anthropic.com/v1"; pv.secretRef = "env:X7_TEST_UNSET_API_KEY"; c.providers.add(pv);
    var m = new Model(); m.id = "claude-sonnet"; m.provider = "anthropic"; c.models.add(m);
    c.profiles.get("exec").chain = new ArrayList<>(List.of("claude-sonnet", "deepseek-chat"));
    assertEquals(List.of(), ModelRouter.validate(c, null));
    var r = new ModelRouter(p, c, fake); assertFalse(r.available("anthropic")); assertTrue(r.available("default"));
    assertEquals("deepseek-chat", r.maker("dev.code", task(), null, false).chat(new LlmProvider.Request("dev.code", "s", "p", "v")).model());
  }
  @Test void validationBlocksSecretsAndIsolationViolations() {
    var c = ModelRouter.fromEnv(props());
    var pv = new Provider(); pv.id = "evil"; pv.baseUrl = "http://evil.example"; pv.secretRef = "env:GITHUB_TOKEN"; c.providers.add(pv);
    var errs = ModelRouter.validate(c, null);
    assertTrue(errs.stream().anyMatch(e -> e.contains("https"))); assertTrue(errs.stream().anyMatch(e -> e.contains("密钥引用")));
    var c2 = ModelRouter.fromEnv(props()); c2.profiles.get("judges").seats.forEach(s -> s.chain = new ArrayList<>(List.of("deepseek-chat")));
    assertTrue(ModelRouter.validate(c2, null).stream().anyMatch(e -> e.contains("隔离") || e.contains("不同模型")));
    var c3 = ModelRouter.fromEnv(props()); var pv3 = new Provider(); pv3.id = "r"; pv3.baseUrl = "https://x"; pv3.secretRef = "env:RENDER_API_KEY"; c3.providers.add(pv3);
    assertTrue(ModelRouter.validate(c3, null).stream().anyMatch(e -> e.contains("密钥引用")), "平台自身凭据不可引用");
  }
}
