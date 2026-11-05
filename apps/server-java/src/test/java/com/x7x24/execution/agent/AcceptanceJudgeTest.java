package com.x7x24.execution.agent;

import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AcceptanceJudgeTest {
  static final String PNG = Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4});
  static AgentRuntime.AcceptanceResult result(String phase, int passed, int failed) {
    return new AgentRuntime.AcceptanceResult(phase, 0, List.of(new AgentRuntime.TestCase("AC-1 登录按钮", failed > 0 ? "failed" : "passed", null)), passed, failed,
        List.of(new AgentRuntime.Shot("00-root.png", PNG)), Map.of("00-root", "- button \"登录\""), "");
  }
  static AcceptanceJudge.Config cfg(boolean images) { return new AcceptanceJudge.Config("http://judge", "k", "deepseek-flash", images, 8); }
  static String reply(String json) { return Json.str(Map.of("choices", List.of(Map.of("message", Map.of("content", json))), "usage", Map.of("total_tokens", 321))); }

  @Test void scriptFailureIsDeterministicAndSkipsModel() {
    var called = new AtomicReference<String>();
    var v = new AcceptanceJudge(cfg(true), b -> { called.set(b); return reply("{\"verdict\":\"PASS\"}"); }).judge(List.of("有登录按钮"), result("tests", 0, 1));
    assertEquals("FAIL", v.verdict()); assertEquals("deterministic", v.source()); assertNull(called.get());
    assertEquals("FAIL", new AcceptanceJudge(cfg(true), b -> reply("{}")).judge(List.of(), result("start", 0, 0)).verdict());
  }
  @Test void sendsScreenshotsAsImageBlocksWhenEnabled() {
    var body = new AtomicReference<String>();
    var v = new AcceptanceJudge(cfg(true), b -> { body.set(b); return reply("{\"verdict\":\"PASS\",\"summary\":\"ok\",\"criteria\":[{\"criterion\":\"AC-1\",\"verdict\":\"PASS\",\"reason\":\"可见\",\"evidence\":\"00-root\"}]}"); })
        .judge(List.of("有登录按钮"), result("tests", 1, 0));
    assertEquals("PASS", v.verdict()); assertTrue(v.usedImages()); assertEquals(321, v.tokens());
    assertTrue(body.get().contains("\"image_url\"")); assertTrue(body.get().contains("data:image/png;base64,")); assertTrue(body.get().contains("登录"));
  }
  @Test void textOnlyWhenImagesDisabled() {
    var body = new AtomicReference<String>();
    var v = new AcceptanceJudge(cfg(false), b -> { body.set(b); return reply("{\"verdict\":\"PASS\",\"criteria\":[]}"); }).judge(List.of("x"), result("tests", 1, 0));
    assertFalse(v.usedImages()); assertFalse(body.get().contains("image_url"));
  }
  @Test void anyFailingCriterionFailsAndGarbageNeedsHuman() {
    var v = new AcceptanceJudge(cfg(true), b -> reply("{\"verdict\":\"PASS\",\"criteria\":[{\"criterion\":\"AC-1\",\"verdict\":\"FAIL\",\"reason\":\"看不到\"}]}")).judge(List.of("x"), result("tests", 1, 0));
    assertEquals("FAIL", v.verdict()); assertEquals("model", v.source());
    assertEquals("NEEDS_HUMAN", new AcceptanceJudge(cfg(true), b -> reply("not json")).judge(List.of("x"), result("tests", 1, 0)).verdict());
    assertEquals("NEEDS_HUMAN", new AcceptanceJudge(cfg(true), b -> { throw new IllegalStateException("down"); }).judge(List.of("x"), result("tests", 1, 0)).verdict());
  }
  @Test void noModelConfiguredPassesOnlyOnScripts() {
    var none = new AcceptanceJudge(new AcceptanceJudge.Config("", "", "m", true, 8));
    assertEquals("PASS", none.judge(List.of(), result("tests", 2, 0)).verdict());
    assertEquals("NEEDS_HUMAN", none.judge(List.of(), result("tests", 0, 0)).verdict());
    assertFalse(new AcceptanceJudge.Config("u", "SECRET", "m", true, 1).toString().contains("SECRET"));
  }
}
