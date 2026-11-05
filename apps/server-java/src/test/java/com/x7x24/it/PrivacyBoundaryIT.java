package com.x7x24.it;

import com.x7x24.adapters.Redactor;
import com.x7x24.pipeline.PipelineService;
import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** All values are synthetic; no external services or credential validation. */
class PrivacyBoundaryIT extends Harness {
  @Autowired TestRestTemplate http;

  @Test void emptyPlatformAdminConfigurationDeniesEveryLogin() {
    var p = new PipelineService(db, clk, "");
    assertFalse(p.adminsConfigured());
    for (String login : List.of("former-author", "test", "operator")) assertFalse(p.isAdmin(login));
    assertFalse(p.isAdmin(null));
    var explicit = new PipelineService(db, clk, "operator");
    assertTrue(explicit.isAdmin("OPERATOR")); assertFalse(explicit.isAdmin("other-user"));
  }

  @Test void evidenceAndAuditAreScrubbedBeforeHashingAndApiReads() {
    String secret = "synthetic-privacy-fixture-" + System.nanoTime();
    Redactor.register(secret);
    String tid = ingestIssue("privacy boundary", "synthetic task");
    var body = Map.of("nested", List.of(Map.of("secret", secret, "number", 3, "flag", true)));
    var ev = evidence.add(tid, 0, "TEST", "privacy", body, "test", null, null);
    String stored = (String) ev.get("content");
    assertFalse(stored.contains(secret)); assertTrue(stored.contains("***"));
    assertEquals(3, Json.tree(stored).path("nested").path(0).path("number").asInt());
    assertTrue(evidence.verifyOne(ev));
    var event = audit.task(tid, 0, "PRIVACY_TEST", "test", body, null);
    assertFalse(String.valueOf(event).contains(secret));
    assertEquals(true, audit.verify().get("ok"));
    String response = http.getForObject("/api/evidence/" + ev.get("id"), String.class);
    assertNotNull(response); assertFalse(response.contains(secret)); assertTrue(response.contains("***"));
  }
}
