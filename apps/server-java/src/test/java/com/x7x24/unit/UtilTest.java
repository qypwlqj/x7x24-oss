package com.x7x24.unit;

import com.x7x24.util.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UtilTest {
  @Test void canonicalJsonIsKeyOrderIndependent() {
    assertEquals(Json.canonical(Map.of("b", 1, "a", 2)), Json.canonical(new java.util.LinkedHashMap<>(Map.of("a", 2, "b", 1))));
    assertEquals(U.sha256(Json.canonical(Map.of("a", 1, "b", 2))), U.sha256(Json.canonical(Map.of("b", 2, "a", 1))));
  }
  @Test void lenientParsesJsonWrappedInText() {
    var n = Json.lenient("好的，结果如下：```json\n{\"score\": 4.5, \"rationale\": \"ok\"}\n```");
    assertNotNull(n); assertEquals(4.5, n.path("score").asDouble());
    assertNull(Json.lenient("没有 JSON"));
  }
  @Test void similarityAndFingerprint() {
    assertTrue(U.similarity("新增 slugify 工具函数", "新增 slugify 工具函数及测试") > 0.6);
    assertTrue(U.similarity("新增 slugify 工具函数", "修改支付配置") < 0.3);
    assertEquals(U.fingerprint("A  b"), U.fingerprint("a b"));
  }
  @Test void clockCanOnlyMoveForward() {
    var c = new Clk(); long a = c.now(); c.advance(1000); assertTrue(c.now() >= a + 1000);
    assertThrows(IllegalArgumentException.class, () -> c.advance(-1));
  }
}
