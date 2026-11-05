package com.x7x24.unit;

import com.x7x24.util.Json;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectMigrationTest {
  @Test void populatedVersionTwoUpgradesWithoutChangingAuditOrEvidence() throws Exception {
    String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1";
    var config = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/common", "classpath:db/migration/h2");
    config.target("2").load().migrate();
    try (var db = DriverManager.getConnection(url, "sa", "")) {
      execute(db, "INSERT INTO product_line(id,name,config,created_at) VALUES ('legacy','旧产品线',?,'2026-01-01T00:00:00Z')", Json.str(Map.of("repo", "owner/legacy", "baseBranch", "main", "workflow", "ci.yml")));
      execute(db, "INSERT INTO task(id,product_line,title,status,budget_tokens,budget_usd,created_at,status_changed_at) VALUES ('old-task','legacy','历史任务','DEV',1000,1,'2026-01-02T00:00:00Z','2026-01-02T00:00:00Z')");
      execute(db, "INSERT INTO signal(id,product_line,source,source_ref,fingerprint,task_id,created_at) VALUES ('old-signal','legacy','github','gh:#1','fingerprint','old-task','2026-01-02T00:00:00Z')");
      execute(db, "INSERT INTO task_event(task_id,event_type,actor,payload,prev_hash,hash,occurred_at) VALUES ('old-task','STATUS_CHANGED','dev','{}',?,?,'2026-01-02T00:00:00Z')", "0".repeat(64), "a".repeat(64));
      execute(db, "INSERT INTO evidence(id,task_id,stage,kind,uri,sha256,content,created_at) VALUES ('old-evidence','old-task','DEV','pr','evidence://old',?,'{}','2026-01-02T00:00:00Z')", "b".repeat(64));
      execute(db, "INSERT INTO budget_ledger(task_id,product_line,tokens,usd,day,occurred_at) VALUES ('old-task','legacy',12,0.2,'2026-01-02','2026-01-02T00:00:00Z')");
      execute(db, "INSERT INTO adapter_call(at,adapter,op,line) VALUES ('2026-01-02T00:00:00Z','git','read','legacy')");
      Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/common", "classpath:db/migration/h2").load().migrate();
      assertEquals("legacy-default", one(db, "SELECT project_id FROM task WHERE id='old-task'"));
      assertEquals("legacy-default", one(db, "SELECT project_id FROM adapter_call WHERE line='legacy'"));
      assertEquals("DEV", one(db, "SELECT current_stage FROM task WHERE id='old-task'"));
      assertEquals("legacy-default", one(db, "SELECT project_id FROM signal WHERE id='old-signal'"));
      assertEquals("legacy-default", one(db, "SELECT project_id FROM budget_ledger WHERE task_id='old-task'"));
      assertEquals("owner/legacy", Json.tree(one(db, "SELECT config FROM project WHERE id='legacy-default'")).path("repo").asText());
      assertEquals("a".repeat(64), one(db, "SELECT hash FROM task_event WHERE task_id='old-task'"));
      assertEquals("b".repeat(64), one(db, "SELECT sha256 FROM evidence WHERE id='old-evidence'"));
      // 多工作区迁移（V9–V11）：全部历史数据归入 ws_legacy，审计哈希不变
      for (String t : List.of("product_line", "project", "task", "signal", "task_event", "budget_ledger", "adapter_call"))
        assertEquals("ws_legacy", one(db, "SELECT DISTINCT workspace_id FROM " + t), t);
      assertEquals("legacy-unclaimed", one(db, "SELECT slug FROM workspace WHERE id='ws_legacy'"));
      assertNull(one(db, "SELECT owner_user_id FROM workspace WHERE id='ws_legacy'"));
      assertEquals("0", one(db, "SELECT COUNT(*) FROM workspace_member"));
      assertThrows(SQLException.class, () -> execute(db, "UPDATE task_event SET actor='changed'"));
      assertThrows(SQLException.class, () -> execute(db, "DELETE FROM evidence WHERE id='old-evidence'"));
    }
  }
  private static void execute(Connection db, String sql, Object... args) throws SQLException {
    try (var statement = db.prepareStatement(sql)) { for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]); statement.executeUpdate(); }
  }
  private static String one(Connection db, String sql) throws SQLException { try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) { assertTrue(result.next()); return result.getString(1); } }
}
