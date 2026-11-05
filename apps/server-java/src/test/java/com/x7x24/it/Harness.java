package com.x7x24.it;

import com.x7x24.adapters.DeliveryTools;
import com.x7x24.core.Status;
import com.x7x24.db.Db;
import com.x7x24.engine.*;
import com.x7x24.gov.*;
import com.x7x24.util.Clk;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.util.function.Predicate;

@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(FakeConfig.class)
public abstract class Harness {

  @Autowired protected Engine engine; @Autowired protected FakeTools tools; @Autowired protected Clk clk; @Autowired protected TaskRepo repo; @Autowired protected Db db;
  @Autowired protected ConfigStore cfg; @Autowired protected AuditLog audit; @Autowired protected KillSwitch kill; @Autowired protected EvidenceStore evidence; @Autowired protected BudgetService budget;
  @Autowired protected Intake intake; @Autowired protected LineRepo lines;

  @BeforeEach void clean() {
    db.update("UPDATE task SET status='DISCARDED', next_run_at=NULL, discard_reason='forbidden_scope' WHERE status NOT IN ('RELEASED','DISCARDED')"); // 清理上一用例遗留的在途任务
    db.update("DELETE FROM budget_ledger"); tools.reset(); for (var k : kill.active()) kill.release((String) k.get("id"), "test");
    var defaults = Governance.defaults(); defaults.release.observeHours = 0.01;
    cfg.update(com.x7x24.util.Json.M.valueToTree(defaults));
    lines.setAutonomy("sandbox", "L2"); db.update("UPDATE product_line SET intake_paused_until=NULL, last_tightened_at=NULL");
  }

  /** 推进时钟并 tick，直到谓词成立（每轮 +2 分钟，相当于 CI/等待自然流逝） */
  protected Task run(String taskId, Predicate<Task> done, int maxTicks) {
    for (int i = 0; i < maxTicks; i++) {
      var t = repo.get(taskId); if (done.test(t) || RecoveryService.stopped(t)) return t;
      clk.advance(2 * 60_000L); engine.tick();
    }
    return repo.get(taskId);
  }
  protected void assertBlocked(Task t) {
    org.junit.jupiter.api.Assertions.assertTrue(RecoveryService.stopped(t), t.status+" "+t.executionState+" "+t.context.failures);
    org.junit.jupiter.api.Assertions.assertEquals(t.anomalyFrom,t.status,"停在出错前的阶段");
    org.junit.jupiter.api.Assertions.assertNull(t.nextRunAt,"停止后等待明确恢复，不再反复调度");
  }
  protected Task runTo(String taskId, Status s) { return run(taskId, t -> t.st() == s, 120); }
  protected String ingestIssue(String title, String body) {
    int n = tools.addIssue(title, body); intake.poll();
    return db.row("SELECT task_id FROM signal WHERE source_ref=?", "gh:#" + n).get("task_id").toString();
  }
  protected int issueNo(String taskId) { return repo.get(taskId).context.issue.number; }
}
