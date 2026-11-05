package com.x7x24.unit;

import com.x7x24.core.Status;
import org.junit.jupiter.api.Test;

import static com.x7x24.core.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class StatusTest {
  @Test void happyPathIsAllowed() {
    assertTrue(Status.can(BACKLOG, PRD_REVIEW, "submit"));
    assertTrue(Status.can(PRD_REVIEW, PLANNING, "pass"));
    assertTrue(Status.can(QA, ACCEPTANCE, "advance"));
    assertTrue(Status.can(ACCEPTANCE, DELIVERED, "accept"));
    assertTrue(Status.can(DELIVERED, RELEASED, "release"));
  }
  @Test void shortcutsAreRejected() {
    assertFalse(Status.can(BACKLOG, RELEASED, "release"));
    assertFalse(Status.can(DEV, RELEASED, "release"));
    assertFalse(Status.can(PRD_REVIEW, DEV, "advance"));
    assertFalse(Status.can(DISCARDED, RELEASED, "release"));
  }
  @Test void reviewFailuresCanPauseAndResumeWithoutSkippingTheReview() {
    for (var stage : java.util.List.of(PRD_REVIEW, SPEC, DESIGN, ACCEPTANCE)) {
      assertTrue(Status.can(stage, ANOMALY, "anomaly"), stage.name());
      assertTrue(Status.can(ANOMALY, stage, "resume"), stage.name());
    }
  }
  @Test void edgeNameMustMatch() {
    assertTrue(Status.can(QA, PLANNING, "qa_loop"));
    assertFalse(Status.can(QA, PLANNING, "accept"));
  }
  @Test void rollbackAndDegradeChain() {
    assertTrue(Status.can(RELEASED, PLANNING, "rollback"));
    assertTrue(Status.can(DEGRADED, PLANNING, "degrade"));
    assertFalse(Status.can(DEGRADED,DISCARDED,"auto_discard"));
    assertTrue(Status.can(DEGRADED,DISCARDED,"abandon"));
    assertTrue(Status.can(DISCARDED,DEV,"restore_progress"));
    assertTrue(Status.can(DISCARDED, BACKLOG, "requeue"));
  }
  @Test void everyStatusHasBoardNodeExceptSignal() {
    for (var s : Status.values()) assertTrue(Status.BOARD_NODES.stream().anyMatch(n -> n.statuses().contains(s)), s + " 不在任何看板节点");
  }
  @Test void discardReasonsRequeueFlags() {
    assertFalse(Status.DISCARD_REASONS.get("forbidden_scope"));
    assertFalse(Status.DISCARD_REASONS.get("loop_exhausted"));
  }
}
