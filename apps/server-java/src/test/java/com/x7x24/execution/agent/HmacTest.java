package com.x7x24.execution.agent;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class HmacTest {
  /** 与 apps/agent-runner/test_runnerd.py 的 test_known_vector 使用同一组输入，保证两端签名一致。 */
  @Test void knownVector() {
    String c = Hmac.canonical("post", "/v1/jobs", "1700000000", "abc", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
    assertEquals("POST\n/v1/jobs\n1700000000\nabc\n015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862", c);
    assertEquals("413c654cc76b3b2d2c179b0b6a91f56802305d3a1e1ec7c4df493262ae03d4b4", Hmac.sign("k".repeat(32), c));
    assertNotEquals(Hmac.sign("k".repeat(32), c), Hmac.sign("j".repeat(32), c));
    assertEquals(64, Hmac.sign("k".repeat(32), c).length());
  }
}
