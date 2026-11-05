package com.x7x24.unit;

import com.x7x24.gov.CanaryJudge;
import com.x7x24.gov.Governance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CanaryJudgeTest {
  final List<Governance.Metric> specs = new Governance().canary.metrics;

  @Test void healthyPasses() {
    var j = CanaryJudge.judge(Map.of("error_rate_delta_pp", List.of(0.1, 0.2, 0.1), "error_rate_abs_pct", List.of(0.1, 0.2, 0.1), "p95_regression_pct", List.of(1.0, 2.0, 1.0), "restart_count", List.of(0.0, 1.0, 0.0)), specs, 1);
    assertEquals("pass", j.decision());
  }
  @Test void exceedingFailureLimitFails() {
    var j = CanaryJudge.judge(Map.of("error_rate_delta_pp", List.of(0.9, 0.8, 0.1), "error_rate_abs_pct", List.of(0.1, 0.1, 0.1), "p95_regression_pct", List.of(1.0, 1.0, 1.0), "restart_count", List.of(0.0, 0.0, 0.0)), specs, 1);
    assertEquals("fail", j.decision()); assertFalse(j.reasons().isEmpty());
  }
  @Test void strictnessTightensThresholds() {
    var s = Map.of("error_rate_delta_pp", List.of(0.4, 0.4, 0.4), "error_rate_abs_pct", List.of(0.1, 0.1, 0.1), "p95_regression_pct", List.of(1.0, 1.0, 1.0), "restart_count", List.of(0.0, 0.0, 0.0));
    assertEquals("pass", CanaryJudge.judge(s, specs, 1).decision());
    assertEquals("fail", CanaryJudge.judge(s, specs, 0.5).decision());
  }
  @Test void missingSamplesAreInconclusive() { assertNotEquals("pass", CanaryJudge.judge(Map.of(), specs, 1).decision()); }
}
