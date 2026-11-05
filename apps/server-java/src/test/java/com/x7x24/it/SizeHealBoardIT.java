package com.x7x24.it;

import com.x7x24.core.Status;
import com.x7x24.engine.Decision;
import com.x7x24.engine.Engine;
import com.x7x24.engine.TaskCtx;
import com.x7x24.gov.Governance;
import com.x7x24.web.Board;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SizeHealBoardIT extends Harness {
  @Autowired Board board;

  String inPlanning(String title) {
    String id = ingestIssue(title, "示例：" + title);
    db.update("UPDATE task SET status='PLANNING', current_stage='PLANNING', version=version+1 WHERE id=?", id); return id;
  }
  long resident(String projectId, String node) {
    @SuppressWarnings("unchecked") var nodes = (List<Map<String, Object>>) board.board(null, projectId, "2000-01-01", "2999-12-31").get("nodes");
    return ((Number) nodes.stream().filter(n -> n.get("id").equals(node)).findFirst().orElseThrow().get("resident")).longValue();
  }

  @Test void boardCountsCurrentStatusAndUpdatesAfterResume() {
    String id = inPlanning("新增 padStartQ 工具函数（看板用例）"); String pid = repo.get(id).projectId;
    long p0 = resident(pid, "planning"), a0 = resident(pid, "anomaly");
    engine.apply(repo.get(id), Decision.anomaly("agent_error", "x"));
    assertEquals(p0,resident(pid,"planning"),"任务留在原节点");
    assertEquals(a0,resident(pid,"anomaly"),"不会重复计入异常节点");
    engine.resumeAnomaly(id, "operator");
    assertEquals(p0, resident(pid, "planning")); assertEquals(a0, resident(pid, "anomaly"), "恢复后看板数字立即反映当前状态");
  }

  @Test void changeTooLargeHealsByShrinkThenSplitThenReplan() {
    String id=inPlanning("新增 padEndQ 工具函数（规模限制）");
    engine.apply(repo.get(id),new Decision.Move(Status.ANOMALY,"anomaly",null,w->w.context.sizeFeedback=Map.of("weightedLines",900,"maxLines",500),null,new Decision.Anomaly("change_too_large","900 行"),null));
    assertBlocked(repo.get(id));assertEquals(Status.PLANNING,repo.get(id).st());assertNotNull(repo.get(id).context.sizeFeedback);
    engine.resumeAnomaly(id,"fixture");assertEquals(Status.PLANNING,repo.get(id).st());assertNotNull(repo.get(id).context.sizeFeedback);
  }

  @Test void prLevelLimitUsesWeightedLinesAndSplitParts() {
    var rel = Governance.defaults().release; var pr = new TaskCtx.Pr(); pr.files = List.of("a", "b"); pr.lines = 2000; pr.weightedLines = 300;
    assertTrue(Engine.prWithin(rel, pr), "翻译 JSON 打折后在上限内");
    pr.weightedLines = 900; assertFalse(Engine.prWithin(rel, pr));
    pr.splitParts = 2; assertTrue(Engine.prWithin(rel, pr), "拆分 2 段时 PR 总上限 ×2");
    pr.splitParts = 99; pr.weightedLines = 1 + rel.maxChangeLines * com.x7x24.gov.ChangeSize.splitCap(rel); assertFalse(Engine.prWithin(rel, pr), "段数封顶 max(maxSplitParts, maxAutoSplitParts)");
  }

  void exhaustSizeHeals(String id) {
    for (int k = 0; k < 4; k++) {
      var cur = repo.get(id); if (cur.st() == Status.DISCARDED || cur.context.sizeAccepted) break;
      engine.apply(cur, new Decision.Move(Status.ANOMALY, "anomaly", null, w -> w.context.sizeFeedback = Map.of("files", 13, "weightedLines", 124), null, new Decision.Anomaly("change_too_large", "124 行"), null));
      run(id, x -> x.st() != Status.ANOMALY, 10);
    }
  }

  @Test void changeTooLargeNoLongerAbandonsTaskWhenHealsRunOut() {
    String id = inPlanning("新增 padEndR 工具函数（预算用尽）");
    exhaustSizeHeals(id);
    var t = repo.get(id);
    assertNotEquals(Status.DISCARDED, t.st(), "P0：自动修复用完不再因改动大小放弃任务");
    assertBlocked(t);assertEquals(Status.PLANNING,t.st());assertFalse(t.context.sizeAccepted);
    assertNull(t.nextRunAt);
  }

  @Test void hardSegmentLimitStillEndsWithGuidanceWhenOptedIn() {
    var c = cfg.get(); c.release.segmentHardLimit = true; cfg.update(com.x7x24.util.Json.M.valueToTree(c));
    try {
      String id = inPlanning("新增 padEndS 工具函数（硬上限）");
      exhaustSizeHeals(id);
      var t = repo.get(id);
      assertBlocked(t);assertEquals(Status.PLANNING,t.st());assertEquals("change_too_large",engine.recoveryInfo(id).get("code"));
    } finally { c.release.segmentHardLimit = false; cfg.update(com.x7x24.util.Json.M.valueToTree(c)); }
  }
}
