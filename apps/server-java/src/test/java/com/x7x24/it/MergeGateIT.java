package com.x7x24.it;

import com.x7x24.agents.Models;
import com.x7x24.core.Status;
import com.x7x24.engine.Decision;
import com.x7x24.engine.Task;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** P0 合并关卡：大小不再决定任务能不能做；大改动合并前等负责人确认（绑定 PR 头提交），小改动按自治等级自动合并 */
class MergeGateIT extends Harness {
  /** 临时替换引擎的执行 agent（只改规划结果的预估），用完还原；不新建 Spring 上下文 */
  void withPlanEstimate(Map<String, Object> est, Runnable body) {
    try {
      var f = com.x7x24.engine.Engine.class.getDeclaredField("agents"); f.setAccessible(true);
      var orig = (Models.ExecAgents) f.get(engine);
      var wrap = new Models.ExecAgents() {
        public String mode() { return orig.mode(); } public String model() { return orig.model(); }
        public Models.Prd draftPrd(Models.Ctx c) { return orig.draftPrd(c); }
        public Models.Plan plan(Models.Ctx c) { var p = orig.plan(c); return new Models.Plan(p.path(), p.template(), "llm-presplit", p.scopeLevel(), p.risk(), p.files(), p.subtasks(), est); }
        public List<Models.Case> specCases(Models.Ctx c) { return orig.specCases(c); }
        public Map<String, Object> design(Models.Ctx c) { return orig.design(c); }
        public Models.CodeChange code(Models.Ctx c) { return orig.code(c); }
      };
      f.set(engine, wrap); try { body.run(); } finally { f.set(engine, orig); }
    } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
  }

  long pending(String id) { return db.count("SELECT COUNT(*) FROM merge_approval WHERE task_id=? AND status='pending'", id); }
  Task toWaiting(String id) { return run(id, x -> pending(id) > 0 || x.st() == Status.RELEASED || x.st() == Status.DISCARDED, 200); }
  void largeGate() { var c = cfg.get(); c.gate.g1Lines = 1; c.gate.g2Lines = 2; c.gate.g3Lines = 1_000_000; cfg.update(com.x7x24.util.Json.M.valueToTree(c)); }
  boolean hasEvent(String id, String type) { return audit.list(id, null, 300, 0).stream().anyMatch(e -> type.equals(e.get("event_type"))); }

  @Test void japaneseI18nPlanOverBudgetNowPlansAndProceeds() {
    Map<String, Object> est = new LinkedHashMap<>(); est.put("files", 38); est.put("weightedLines", 2600); est.put("exceedsBudget", true); est.put("neededParts", 9); est.put("partsCap", 4);
    String[] box = new String[1]; Task[] tt = new Task[1];
    withPlanEstimate(est, () -> {
      box[0] = ingestIssue("日语多语言切换：新增 slugify 助手", PipelineIT.SLUG);
      tt[0] = run(box[0], x -> x.st() == Status.RELEASED || x.st() == Status.DISCARDED, 200);
    });
    String id = box[0]; var t = tt[0];
    assertNotEquals("exceeds_budget", t.discardReason, "以前会因预估超出预算被放弃");
    assertEquals(Status.RELEASED, t.st(), t.discardReason + " " + t.discardDetail);
    assertTrue(hasEvent(id, "SIZE_LARGE"), "记录「改动较大」事实，交给合并关卡");
    assertEquals(0, db.count("SELECT COUNT(*) FROM merge_approval WHERE task_id=?", id), "实际 PR 很小（G0）→ L2 自动合并");
  }

  @Test void largeChangeWaitsForOwnerThenMergesOnApproval() {
    largeGate();
    String id = ingestIssue("新增 capitalize 工具函数及测试（大改动确认）", "新增 capitalize 工具函数：首字母大写。示例：capitalize(\"hello\") => \"Hello\"");
    var t = toWaiting(id);
    assertEquals(Status.DELIVERED, t.st()); assertEquals(1, pending(id)); assertTrue(hasEvent(id, "MERGE_APPROVAL_REQUESTED"));
    for (int i = 0; i < 20; i++) { clk.advance(60 * 60_000L); engine.tick(); }
    t = repo.get(id); assertEquals(Status.DELIVERED, t.st(), "不自动合并、不自动放弃"); assertTrue(tools.merged.isEmpty());
    for (int i = 0; i < 60; i++) { clk.advance(60 * 60_000L); engine.tick(); }
    assertTrue(hasEvent(id, "MERGE_APPROVAL_REMINDER"), "超过 72 小时提醒"); assertEquals(Status.DELIVERED, repo.get(id).st());
    assertThrows(IllegalArgumentException.class, () -> engine.decideMerge(id, "approve", "deadbeef", "", "owner"), "确认必须绑定当前头提交");
    engine.decideMerge(id, "approve", repo.get(id).context.pr.headSha, "看过了", "owner");
    t = run(id, x -> x.st() == Status.RELEASED || x.st() == Status.DISCARDED, 200);
    assertEquals(Status.RELEASED, t.st()); assertFalse(tools.merged.isEmpty()); assertTrue(hasEvent(id, "MERGE_APPROVED"));
  }

  @Test void newCommitInvalidatesApproval() {
    largeGate();
    String id = ingestIssue("新增 wordCount 工具函数及测试（新提交重新确认）", "新增 wordCount 工具函数：统计单词数。示例：wordCount(\"hello world\") => 2");
    var t = toWaiting(id); String oldHead = t.context.pr.headSha;
    engine.decideMerge(id, "approve", oldHead, "", "owner");
    engine.apply(repo.get(id), Decision.stay(0.01, w -> w.context.pr.headSha = "f".repeat(40))); // 确认后又推了新提交
    run(id, x -> pending(id) > 0, 10);
    assertTrue(hasEvent(id, "MERGE_APPROVAL_STALE"));
    assertEquals("stale", db.row("SELECT status FROM merge_approval WHERE task_id=? AND head_sha=?", id, oldHead).get("status"), "旧确认作废");
    assertEquals(1, pending(id), "新提交需要重新确认"); assertTrue(tools.merged.isEmpty());
    assertThrows(IllegalArgumentException.class, () -> engine.decideMerge(id, "approve", oldHead, "", "owner"), "用旧提交确认被拒");
  }

  @Test void ownerCanReturnForChangesWithNote() {
    largeGate();
    String id = ingestIssue("新增 isPalindrome 工具函数及测试（退回修改）", "新增 isPalindrome 工具函数（src/isPalindrome.js）。示例：isPalindrome(\"abba\") => true");
    var t = toWaiting(id); String head = t.context.pr.headSha;
    assertThrows(IllegalArgumentException.class, () -> engine.decideMerge(id, "return", head, " ", "owner"), "退回要写原因");
    engine.decideMerge(id, "return", head, "请补充空字符串的处理", "owner");
    t = repo.get(id); assertEquals(Status.DEV, t.st()); assertTrue(t.context.lastCiLog.contains("空字符串"));
    assertEquals("returned", db.row("SELECT status FROM merge_approval WHERE task_id=?", id).get("status"));
  }

  @Test void smallChangeAutoMergesPerAutonomyLevel() {
    String id = ingestIssue("新增 capitalize 工具函数及测试（小改动）", "新增 capitalize 工具函数：首字母大写。示例：capitalize(\"x\") => \"X\"");
    var t = run(id, x -> x.st() == Status.RELEASED || x.st() == Status.DISCARDED, 200);
    assertEquals(Status.RELEASED, t.st()); assertEquals(0, db.count("SELECT COUNT(*) FROM merge_approval WHERE task_id=?", id));
  }
}
