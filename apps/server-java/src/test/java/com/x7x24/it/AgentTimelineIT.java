package com.x7x24.it;

import com.x7x24.execution.agent.AgentRuntime;
import com.x7x24.execution.agent.AgentTimeline;
import com.x7x24.tenant.Tenant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** M4：runner 明细 → task_event（幂等、带节点、进时间线接口） */
class AgentTimelineIT extends Harness {
  @Test void toolBatchesBecomeIdempotentTaskEventsWithNode() {
    String id = ingestIssue("运行明细用例 slugify " + java.util.UUID.randomUUID(), PipelineIT.SLUG);
    String ws = db.row("SELECT workspace_id FROM task WHERE id=?", id).get("workspace_id").toString();
    var tl = new AgentTimeline(db, audit);
    var e = new AgentRuntime.LogEvent(7, "2026-10-24T22:00:00+0900", "tools", "{\"items\":[{\"name\":\"Bash\",\"args\":\"npm test\",\"ms\":900,\"ok\":true}]}");
    tl.accept(ws, id, "job1", e); tl.accept(ws, id, "job1", e);
    assertEquals(1, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='AGENT_TOOLS'", id), "同一条日志只写一次");
    var p = db.row("SELECT payload FROM task_event WHERE task_id=? AND event_type='AGENT_TOOLS'", id).get("payload").toString();
    assertTrue(p.contains("\"node\"") && p.contains("npm test"));
    tl.accept(ws, id, "job1", new AgentRuntime.LogEvent(8, null, "system", "clone x"));
    assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND payload LIKE '%clone x%'", id));
    Tenant.clear();
  }
}
