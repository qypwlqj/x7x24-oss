package com.x7x24.it;

import com.x7x24.core.Status;
import com.x7x24.engine.Decision;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiscardedTaskOpsIT extends Harness {
  String discarded(String title) {
    String id = ingestIssue(title, "示例：" + title);
    engine.apply(repo.get(id), new Decision.Discard("manual_abandon","abandon",Map.of("after","test"),Decision.NOOP));
    assertEquals(Status.DISCARDED, repo.get(id).st()); return id;
  }

  @Test void editThenRestartStartsNewIterationAndAudits() {
    String id = discarded("新增 kebabCase 工具函数（重启用例）");
    int it0 = repo.get(id).currentIteration;
    engine.editDiscarded(id, "新增 kebabCase 工具函数（已编辑）", null, "kebabCase(\"aB\") => \"a-b\"", false, "operator");
    var t = repo.get(id); assertEquals("新增 kebabCase 工具函数（已编辑）", t.title); assertEquals(Status.DISCARDED, t.st());
    t.reviewRetry = 2; repo.save(t);
    var r = engine.editDiscarded(id, null, "新问题描述 kebabCase", null, true, "operator");
    assertEquals(Status.BACKLOG, r.st()); assertEquals(0, r.reviewRetry); assertNull(r.discardReason); assertEquals(it0 + 1, r.currentIteration);
    var types = audit.list(id, null, 100, 0).stream().map(e -> String.valueOf(e.get("event_type"))).toList();
    assertTrue(types.contains("TASK_EDITED") && types.contains("TASK_RESTARTED"), types.toString());
    assertThrows(IllegalArgumentException.class, () -> engine.editDiscarded(id, "xx", null, null, false, "operator"), "非废弃任务不能编辑");
  }

  @Test void softDeleteHidesAndStopsScheduling() {
    String id = discarded("新增 snakeCase 工具函数（删除用例）");
    engine.deleteDiscarded(id, "operator");
    var t = repo.get(id); assertNotNull(t.deletedAt); assertNull(t.nextRunAt);
    assertEquals(0, db.count("SELECT COUNT(*) FROM task WHERE id=? AND deleted_at IS NULL", id));
    assertTrue(audit.list(id, null, 50, 0).stream().anyMatch(e -> "TASK_DELETED".equals(e.get("event_type"))));
    assertThrows(IllegalArgumentException.class, () -> engine.editDiscarded(id, null, null, null, true, "operator"));
  }

  @Test void recreatedTaskIsNotDuplicateOfDiscarded() {
    String title = "新增 camelCaseX 工具函数", body = "示例：camelCaseX(\"a-b\") => \"aB\"";
    String old = ingestIssue(title, body);
    engine.apply(repo.get(old), new Decision.Discard("manual_abandon","abandon",Map.of(),Decision.NOOP));
    String id = ingestIssue(title, body + " ");
    var t = run(id, x -> x.st() != Status.BACKLOG && x.st() != Status.PRD_REVIEW, 30);
    assertNotEquals("duplicate", t.discardReason, "已废弃的同名任务不参与查重");
  }

  @Test void manualResumeFromAnomalyGoesBackToPlanning() {
    String id = ingestIssue("新增 titleCaseZ 工具函数（恢复用例）", "示例：titleCaseZ(\"a b\") => \"A B\"");
    db.update("UPDATE task SET status='PLANNING', current_stage='PLANNING', version=version+1 WHERE id=?", id);
    engine.apply(repo.get(id), new Decision.Move(Status.ANOMALY, "anomaly", null, w -> {}, null, new Decision.Anomaly("agent_error", "GitHub 404"), null));
    var t = repo.get(id); assertBlocked(t); t.healCount = 3; repo.save(t);
    var r = engine.resumeAnomaly(id, "operator");
    assertEquals(Status.PLANNING, r.st()); assertEquals(3,r.healCount,"保留历史尝试次数");
    assertEquals(1,db.count("SELECT COUNT(*) FROM anomaly WHERE task_id=? AND status='healing'",id),"开始恢复时问题仍未解决");
    assertTrue(audit.list(id, null, 100, 0).stream().anyMatch(e -> "TASK_RESUMED".equals(e.get("event_type"))));
    assertThrows(IllegalArgumentException.class, () -> engine.resumeAnomaly(id, "operator"));
  }
}
