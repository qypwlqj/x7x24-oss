package com.x7x24.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.DeliveryTools;
import com.x7x24.db.Db;
import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 没有任何凭据时：体验模式，所有写操作都不触网；仍可使用「粘贴令牌」备选路径。 */
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExperienceModeIT {
  @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
    r.add("x7x24.auth.required", () -> "false");
    r.add("x7x24.github.token", () -> ""); r.add("x7x24.github.api-url", () -> "http://localhost:1"); r.add("x7x24.github.web-url", () -> "http://localhost:1");
    r.add("x7x24.data-dir", () -> System.getProperty("java.io.tmpdir") + "/x7exp"); r.add("spring.datasource.url", () -> "jdbc:h2:mem:exp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1");
  }
  @Autowired TestRestTemplate http; @Autowired DeliveryTools tools; @Autowired Db db;
  JsonNode get(String p) { return Json.tree(http.getForObject(p, String.class)); }

  @Test void noCredentialMeansExperienceModeAndNothingGoesOverTheNetwork() {
    var s = get("/api/integrations/github");
    assertFalse(s.path("connected").asBoolean()); assertEquals("experience", s.path("mode").asText()); assertEquals("none", s.path("credential").path("source").asText());
    assertFalse(tools.live()); assertTrue(s.path("notice").asText().contains("登录鉴权已关闭") && s.path("notice").asText().contains("AES-GCM"));
    assertFalse(get("/api/meta").path("connected").asBoolean());
    var st = get("/api/integrations/status"); for (var c : st.path("cards")) assertEquals("disconnected", c.path("state").asText().equals("ok") && c.path("id").asText().equals("lastSuccess") ? "x" : c.path("state").asText().replace("attention", "disconnected"));
    assertEquals("disconnected", st.path("cards").get(0).path("state").asText());
    var mode = http.exchange("/api/integrations/mode", HttpMethod.PUT, new HttpEntity<>(Map.of("mode", "execute", "confirm", true)), String.class);
    assertEquals(400, mode.getStatusCode().value()); assertTrue(mode.getBody().contains("体验模式"));
    assertEquals(0, db.count("SELECT COUNT(*) FROM adapter_call WHERE dry_run=0"));
  }
  @Test void healthCheckWithoutCredentialSaysConnectFirstWithoutNetwork() {
    var r = http.postForObject("/api/product-lines/sandbox/projects", Map.of("name", "exp", "config", Map.of("repo", "x/y")), String.class);
    var h = Json.tree(http.postForObject("/api/projects/" + Json.tree(r).path("id").asText() + "/connection", null, String.class));
    assertFalse(h.path("ok").asBoolean()); assertEquals("token", h.path("items").get(0).path("id").asText()); assertTrue(h.path("items").get(0).path("fix").asText().contains("集成"));
  }
  @Test void deviceFlowReportsNetworkFailureWithPasteFallbackInsteadOfCrashing() {
    var s = Json.tree(http.postForObject("/api/integrations/github/device/start", null, String.class));
    assertFalse(s.path("ok").asBoolean()); assertEquals("paste_token", s.path("fallback").asText());
  }
}
