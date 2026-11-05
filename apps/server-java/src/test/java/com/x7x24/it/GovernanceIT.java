package com.x7x24.it;

import com.x7x24.core.Status;
import com.x7x24.engine.Engine;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GovernanceIT extends Harness {
  @Test void budgetChargeIsIdempotentAndEnforcesSoftAndHardLimits() {
    String id = engine.ingest(new Engine.Ingest("sandbox", "预算测试任务 alpha", "x")).taskId();
    assertTrue(budget.charge("k1", id, "sandbox", "dev", "m", 100, 0.5, 2));
    assertFalse(budget.charge("k1", id, "sandbox", "dev", "m", 100, 0.5, 2), "同一幂等键只记一次");
    var t = repo.get(id); assertEquals(100, t.spentTokens); assertEquals("ok", budget.taskStatus(t));
    budget.charge("k2", id, "sandbox", "dev", "m", 0, t.budgetUsd * 0.85 - t.spentUsd, 0);
    assertEquals("nudge", budget.taskStatus(repo.get(id)), "达到 80% 进入 soft（降级到便宜模型）");
    budget.charge("k3", id, "sandbox", "dev", "m", 0, t.budgetUsd, 0);
    assertEquals("hard", budget.taskStatus(repo.get(id)), "超过 100% 为 hard");
  }

  @Test void lineSoftThenHardAndGlobalHardTriggersReadOnlyKillSwitch() {
    var b = cfg.get().budgets;
    String id = engine.ingest(new Engine.Ingest("sandbox", "预算测试任务 bravo", "x")).taskId();
    budget.charge("line-soft", id, "sandbox", "x", "m", 0, b.line.dailyUsd * 0.85, 0);
    assertEquals("soft", budget.lineStatus("sandbox"));
    budget.charge("line-hard", id, "sandbox", "x", "m", 0, b.line.dailyUsd, 0);
    assertEquals("hard", budget.lineStatus("sandbox"));
    budget.charge("global-hard", id, "sandbox", "x", "m", 0, b.global.dailyUsd, 0);
    assertTrue(budget.enforceGlobal());
    assertTrue(kill.active().stream().anyMatch(k -> "READ_ONLY".equals(k.get("mode")) && "budget:global".equals(k.get("trigger_key"))), "全局触顶自动 READ_ONLY");
    assertNotNull(kill.isBlocked("sandbox", "write", null));
    db.update("DELETE FROM budget_ledger");  // 条件消除后自动解除
    assertFalse(budget.enforceGlobal());
    assertTrue(kill.active().stream().noneMatch(k -> "budget:global".equals(k.get("trigger_key"))));
  }

  @Test void auditHashChainIsValidAndTaskEventsAreAppendOnly() {
    String id = engine.ingest(new Engine.Ingest("sandbox", "审计链测试任务 charlie", "x")).taskId();
    for (int i = 0; i < 3; i++) engine.tick();
    var v = audit.verify(); assertEquals(Boolean.TRUE, v.get("ok")); assertTrue(((Number) v.get("checked")).longValue() > 3);
    assertThrows(Exception.class, () -> db.update("UPDATE task_event SET payload='{\"x\":1}' WHERE task_id=?", id), "UPDATE 被数据库触发器拒绝");
    assertThrows(Exception.class, () -> db.update("DELETE FROM task_event WHERE task_id=?", id), "DELETE 被拒绝");
    evidence.add(id, 1, "DEV", "test", Map.of("a", 1), "x", "m", "ev-k");
    assertThrows(Exception.class, () -> db.update("UPDATE evidence SET content='tampered'"), "证据 WORM");
  }

  @Test void auditEventsAreIdempotentByKey() {
    var a = audit.append(com.x7x24.gov.AuditLog.ev(null, null, "CONFIG_UPDATED", "t", Map.of("a", 1), "idem-key-1", audit.now()));
    var b = audit.append(com.x7x24.gov.AuditLog.ev(null, null, "CONFIG_UPDATED", "t", Map.of("a", 1), "idem-key-1", audit.now()));
    assertTrue(a.created()); assertFalse(b.created()); assertEquals(a.row().get("id"), b.row().get("id"));
  }

  @Test void illegalTransitionsAreRefused() {
    String id = engine.ingest(new Engine.Ingest("sandbox", "非法迁移测试任务 delta", "x")).taskId();
    var t = repo.get(id);
    assertThrows(IllegalStateException.class, () -> engine.apply(t, com.x7x24.engine.Decision.move(Status.RELEASED, "release")));
    assertEquals(Status.BACKLOG, repo.get(id).st());
  }

  @Test void configHotUpdateValidatesAndTakesEffect() {
    int v = cfg.version();
    cfg.update(com.x7x24.util.Json.tree("{\"loops\":{\"maxLoops\":5}}"));
    assertEquals(5, cfg.get().loops.maxLoops); assertEquals(v + 1, cfg.version());
    assertThrows(IllegalArgumentException.class, () -> cfg.update(com.x7x24.util.Json.tree("{\"canary\":{\"metrics\":[{\"name\":\"x\",\"successCondition\":\"drop table\",\"failureLimit\":0,\"count\":1}]}}")));
    assertEquals(5, cfg.get().loops.maxLoops, "非法配置不生效");
    cfg.update(com.x7x24.util.Json.tree("{\"loops\":{\"maxLoops\":3}}"));
  }

  @Test void autonomyL1BlocksMidRiskWorkAtPrdReview() {
    lines.setAutonomy("sandbox", "L1");
    String id = engine.ingest(new Engine.Ingest("sandbox", "新增搜索接口 api 页面 echo", "新增功能：业务逻辑与 api 改造")).taskId();
    var t = run(id, x -> x.st() == Status.DISCARDED || x.st() == Status.PLANNING, 40);
    assertBlocked(t); assertEquals("forbidden_scope",engine.recoveryInfo(t.id).get("code"));
  }
}
