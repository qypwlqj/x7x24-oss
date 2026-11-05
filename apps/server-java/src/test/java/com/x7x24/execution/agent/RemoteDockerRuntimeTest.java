package com.x7x24.execution.agent;

import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteDockerRuntimeTest {
  static final String KEY = "s".repeat(40);
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.NullSource
  @org.junit.jupiter.params.provider.ValueSource(strings = {"gateway/observed"})
  void signsEveryRequestAndParsesResult(String observedModel) throws Exception {
    try (var srv = new MockWebServer()) {
      srv.enqueue(new MockResponse().setBody("{\"id\":\"job_1\",\"state\":\"QUEUED\"}"));
      String modelField = observedModel == null ? "" : "\"observedModel\":\"" + observedModel + "\",";
      srv.enqueue(new MockResponse().setBody("{\"state\":\"SUCCEEDED\",\"result\":{" + modelField + "\"files\":[{\"path\":\"src/a.js\",\"content\":\"x\",\"delete\":false},{\"path\":\"old.js\",\"delete\":true}],\"untracked\":[\"tmp.log\"],\"summary\":\"ok\",\"numTurns\":3,\"headSha\":\"abc\",\"baseSha\":\"baseline\",\"usage\":{\"inputTokens\":10,\"outputTokens\":5,\"cacheReadInputTokens\":7}}}"));
      srv.enqueue(new MockResponse().setBody("{\"events\":[{\"seq\":0,\"ts\":\"t\",\"kind\":\"text\",\"text\":\"hi\"}],\"next\":1}"));
      srv.start();
      var rt = new RemoteDockerRuntime(srv.url("/").toString(), KEY, () -> 1700000000L);
      var h = rt.start(new AgentRuntime.JobSpec("k1", "o/r", "main", "TOKEN-SHOULD-NOT-LOG", "IMPLEMENT", "p", "deepseek-flash", AgentRuntime.Limits.defaults()));
      assertEquals("job_1", h.remoteId());
      var req = srv.takeRequest(); String body = req.getBody().readUtf8();
      String expect = Hmac.sign(KEY, Hmac.canonical("POST", "/v1/jobs", "1700000000", req.getHeader("X-X7-Nonce"), body.getBytes()));
      assertEquals(expect, req.getHeader("X-X7-Signature"));
      assertEquals(32, req.getHeader("X-X7-Nonce").length());
      var s = rt.poll(h); assertTrue(s.terminal()); assertEquals(2, s.result().files().size()); assertTrue(s.result().files().get(1).delete()); assertEquals(7, s.result().usage().cacheReadTokens());
      assertEquals("baseline", s.result().baseSha());
      assertEquals(observedModel, s.result().observedModel());
      var get = srv.takeRequest(); assertEquals("/v1/jobs/job_1", get.getPath());
      assertEquals(Hmac.sign(KEY, Hmac.canonical("GET", "/v1/jobs/job_1", "1700000000", get.getHeader("X-X7-Nonce"), new byte[0])), get.getHeader("X-X7-Signature"));
      assertEquals(1, rt.logs(h, 0).next());
      assertEquals("/v1/jobs/job_1/logs?from=0", srv.takeRequest().getPath());
    }
  }
  @Test void rejectsUnsafeJobId() {
    var rt = new RemoteDockerRuntime("http://127.0.0.1:1", KEY);
    assertThrows(IllegalArgumentException.class, () -> rt.poll(new AgentRuntime.Handle("remote-docker", "../x")));
  }
  @Test void specToStringHidesToken() {
    var s = new AgentRuntime.JobSpec("k", "o/r", "main", "SECRET", "IMPLEMENT", "p", "m", AgentRuntime.Limits.defaults());
    assertFalse(s.toString().contains("SECRET"));
  }
  @Test void settingsDefaultIsLegacyAndRemoteNeedsKey() {
    var env = new org.springframework.mock.env.MockEnvironment();
    assertFalse(AgentRuntimeSettings.from(env).remote());
    env.setProperty("AGENT_RUNTIME", "remote-docker"); env.setProperty("AGENT_RUNNER_URL", "http://127.0.0.1:8790");
    assertThrows(IllegalStateException.class, () -> AgentRuntimeSettings.from(env));
    env.setProperty("AGENT_RUNNER_HMAC_KEY", KEY); assertTrue(AgentRuntimeSettings.from(env).remote());
    env.setProperty("AGENT_RUNTIME", "bogus"); assertThrows(IllegalStateException.class, () -> AgentRuntimeSettings.from(env));
    env.setProperty("AGENT_RUNTIME", "legacy-json"); env.setProperty("AGENT_FALLBACK_LEGACY", "true");
    assertFalse(AgentRuntimeSettings.from(env).remote(), "legacy-json 兼容旧值：只告警，当作沙盒不可用");
  }
}
