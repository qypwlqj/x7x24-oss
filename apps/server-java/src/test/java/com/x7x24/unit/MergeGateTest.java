package com.x7x24.unit;

import com.x7x24.gov.ChangeSize;
import com.x7x24.gov.Governance;
import com.x7x24.gov.MergeGate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MergeGateTest {
  final Governance.Gate g = new Governance.Gate();
  @Test void tierIsMaxOfSizeAndRisk() {
    assertEquals("G0", MergeGate.evaluate(g, 50, "low", "L2").tier());
    assertEquals("G1", MergeGate.evaluate(g, 250, "low", "L2").tier());
    assertEquals("G2", MergeGate.evaluate(g, 500, "low", "L2").tier());
    assertEquals("G3", MergeGate.evaluate(g, 5000, "low", "L3").tier());
    assertEquals("G2", MergeGate.evaluate(g, 10, "high", "L3").tier(), "高风险至少 G2");
  }
  @Test void autonomyMatrix() {
    assertTrue(MergeGate.evaluate(g, 10, "low", "L1").needsApproval(), "L1 每次合并都要确认");
    assertFalse(MergeGate.evaluate(g, 10, "low", "L2").needsApproval());
    assertFalse(MergeGate.evaluate(g, 250, "mid", "L2").needsApproval());
    assertTrue(MergeGate.evaluate(g, 500, "low", "L2").needsApproval(), "L2 大改动要确认");
    assertFalse(MergeGate.evaluate(g, 500, "low", "L3").needsApproval());
    assertTrue(MergeGate.evaluate(g, 5000, "low", "L3").needsApproval(), "很大的改动总要确认");
    assertTrue(MergeGate.evaluate(g, 10, "high", "L3").needsApproval(), "高风险总要确认");
  }
  @Test void testsAndPureDeletionsWeighHalf() {
    var rel = Governance.defaults().release;
    assertEquals(50, ChangeSize.measure(rel, List.of(new ChangeSize.Item("tests/a.test.js", 100))).weightedLines());
    assertEquals(50, ChangeSize.measure(rel, List.of(new ChangeSize.Item("src/a.js", 100, 100))).weightedLines());
    assertEquals(100, ChangeSize.measure(rel, List.of(new ChangeSize.Item("src/a.js", 100))).weightedLines());
    assertEquals(10, ChangeSize.measure(rel, List.of(new ChangeSize.Item("src/locales/ja.json", 100))).weightedLines(), "语言包仍按 0.1");
  }
}
