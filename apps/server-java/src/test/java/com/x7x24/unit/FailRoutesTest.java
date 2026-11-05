package com.x7x24.unit;

import com.x7x24.chat.TaskPlanApi;
import com.x7x24.gov.Governance;
import com.x7x24.pipeline.Pipeline;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FailRoutesTest {
  static Pipeline.GNode n(String id, String module, String executor) { var x = new Pipeline.GNode(); x.id = id; x.module = module; x.executor = executor; return x; }
  static Map<String, Object> ev(String from, String to, String edge, String payload) { Map<String, Object> m = new HashMap<>(); m.put("from_status", from); m.put("to_status", to); m.put("edge", edge); m.put("payload", payload); return m; }
  @Test void failureAndExceptionRoutesWithTakenCounts() {
    var g = new Pipeline.Graph(); g.nodes = new ArrayList<>(List.of(n("INTAKE", "sensing", "agent"), n("PLANNING", "sensing", "agent"), n("UX_DESIGN", "production", "agent"), n("DEV", "production", "agent"), n("QA", "delivery", "ci"), n("SEC_AUDIT", "delivery", "review"), n("ACCEPTANCE", "delivery", "review")));
    var r = TaskPlanApi.routes(g, null, new Governance.Loops(), List.of(
        ev("QA", "DEV", "qa_loop", "{}"), ev("QA", "DEV", "qa_loop", "{}"), ev("ACCEPTANCE", "DEV", "node_fix", "{\"node\":\"SEC_AUDIT\"}"), ev("QA", "DEGRADED", "qa_loop", "{}"), ev("DEGRADED", "DEV", "degrade", "{}"), ev("DEV", "PLANNING", "split", "{}"), ev("DEV", "QA", "advance", "{}")));
    Map<String, Map<String, Object>> by = new HashMap<>(); r.forEach(m -> by.put(m.get("from") + ">" + m.get("to"), m));
    assertEquals(2, by.get("QA>DEV").get("count")); assertEquals("测试未过", by.get("QA>DEV").get("label")); assertEquals(3, by.get("QA>DEV").get("max"));
    assertEquals(1, by.get("SEC_AUDIT>DEV").get("count")); assertEquals("审计不通过", by.get("SEC_AUDIT>DEV").get("label"));
    assertEquals(0, by.get("ACCEPTANCE>PLANNING").get("count"));
    assertEquals("exc", by.get("QA>DEGRADED").get("kind")); assertEquals(1, by.get("QA>DEGRADED").get("count")); assertEquals(1, by.get("DEGRADED>DEV").get("count"));
    assertEquals(0, by.get("UX_DESIGN>ANOMALY").get("count")); assertEquals(0, by.get("DEV>ANOMALY").get("count"));
    assertEquals(1, by.get("DEV>PLANNING").get("count"), "规则外但实际发生的去向也补上");
    assertFalse(by.containsKey("DEV>QA"), "正常推进不算失败去向");
  }
}
