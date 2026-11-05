package com.x7x24.unit;

import com.x7x24.core.Status;
import com.x7x24.engine.*;
import com.x7x24.pipeline.Pipeline;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StageExecutionsTest {
  @Test void invalidationFollowsDependenciesAndPreservesIndependentBranches() {
    var graph = new Pipeline.Graph();
    for (String id : List.of("PLAN", "API", "DOCS", "DEV", "QA", "REVIEW", "CHILD")) {
      var n = new Pipeline.GNode(); n.id = id; if (id.equals("CHILD")) n.parent = "QA"; graph.nodes.add(n);
    }
    graph.edges = List.of(List.of("PLAN", "API"), List.of("PLAN", "DOCS"), List.of("API", "DEV"), List.of("DEV", "QA"), List.of("QA", "REVIEW"));
    var before = new Task(); before.id = "test"; before.status = "QA"; before.currentNode = "QA"; before.version = 12;
    var after = before.copy(); after.status = "DEV"; after.currentNode = "DEV";
    new StageExecutions(null, null).invalidate(before, after, graph, Decision.move(Status.DEV, "qa_loop"));
    assertEquals(Map.of("DEV", 13, "QA", 13, "REVIEW", 13, "CHILD", 13), after.context.nodeInvalidations);
    assertFalse(after.context.nodeInvalidations.containsKey("API")); assertFalse(after.context.nodeInvalidations.containsKey("DOCS"));
  }

  @Test void aChangedHeadInvalidatesChecksEvenWhenTheTaskStaysInQa() {
    var graph = new Pipeline.Graph(); graph.edges = List.of(List.of("DEV", "QA"), List.of("QA", "ACCEPTANCE"));
    var before = new Task(); before.status = "QA"; before.version = 5; before.currentNode = "QA";
    before.context.pr = new TaskCtx.Pr(); before.context.pr.headSha = "old";
    var after = before.copy(); after.context.pr.headSha = "new";
    new StageExecutions(null, null).invalidate(before, after, graph, Decision.stay(1));
    assertEquals(Map.of("QA", 6, "ACCEPTANCE", 6), after.context.nodeInvalidations);
  }

  @Test void legacyTransitionsCannotTurnRejectedOrBlockedChecksGreen() {
    assertEquals("FAILED", StageExecutions.outcome("SUCCEEDED", "QA", "DEV", ""));
    assertEquals("BLOCKED", StageExecutions.outcome("SUCCEEDED", "QA", "ANOMALY", ""));
    assertEquals("RECOVERED", StageExecutions.outcome("SUCCEEDED", "ANOMALY", "QA", ""));
    assertEquals("PASSED", StageExecutions.outcome("SUCCEEDED", "QA", "ACCEPTANCE", ""));
  }
}
