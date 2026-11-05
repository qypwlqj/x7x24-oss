package com.x7x24.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.x7x24.db.Db;
import com.x7x24.util.Json;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class TaskRepo {
  public static final String UNFINISHED = "deleted_at IS NULL AND NOT(status='DISCARDED' AND next_run_at IS NULL OR status='RELEASED' AND feedback_done<>0)";
  private final Db db;
  public TaskRepo(Db db) { this.db = db; }

  public static String toJson(Task t) { return Json.str(t); }
  public static Task fromJson(String s) { return Json.parse(s, Task.class); }

  @SuppressWarnings("unchecked")
  public static Task map(Map<String, Object> r) {
    Task t = new Task();
    t.projectId = Db.str(r.get("project_id")); t.currentStage = Db.str(r.get("current_stage")); t.executionState = Db.str(r.get("execution_state")); t.acceptanceCriteria = Db.str(r.get("acceptance_criteria")); t.pipelineVersion = Db.str(r.get("pipeline_version")); t.currentNode = Db.str(r.get("current_node"));
    t.workspaceId = Db.str(r.get("workspace_id")); t.id = Db.str(r.get("id")); t.productLine = Db.str(r.get("product_line")); t.title = Db.str(r.get("title")); t.problem = Db.str(r.get("problem")); t.status = Db.str(r.get("status"));
    t.riskLevel = Db.str(r.get("risk_level")); t.cooldownUntil = Db.str(r.get("cooldown_until")); t.parentTaskId = Db.str(r.get("parent_task_id")); t.discardReason = Db.str(r.get("discard_reason"));
    t.anomalyFrom = Db.str(r.get("anomaly_from")); t.observeUntil = Db.str(r.get("observe_until")); t.workflowId = Db.str(r.get("workflow_id")); t.nextRunAt = Db.str(r.get("next_run_at"));
    t.createdAt = Db.str(r.get("created_at")); t.statusChangedAt = Db.str(r.get("status_changed_at")); t.releasedAt = Db.str(r.get("released_at")); t.deletedAt = r.containsKey("deleted_at") ? Db.str(r.get("deleted_at")) : null;
    t.priorityScore = Db.d(r.get("priority_score")); t.budgetUsd = Db.d(r.get("budget_usd")); t.spentUsd = Db.d(r.get("spent_usd")); t.budgetCi = Db.d(r.get("budget_ci")); t.spentCi = Db.d(r.get("spent_ci"));
    t.budgetTokens = Db.l(r.get("budget_tokens")); t.spentTokens = Db.l(r.get("spent_tokens"));
    t.scopeLevel = Db.i(r.get("scope_level")); t.strategyVariant = Db.i(r.get("strategy_variant")); t.currentIteration = Db.i(r.get("current_iteration")); t.loopCount = Db.i(r.get("loop_count"));
    t.rejectCount = Db.i(r.get("reject_count")); t.reviewRetry = Db.i(r.get("review_retry")); t.rollbackCount = Db.i(r.get("rollback_count")); t.requeueCount = Db.i(r.get("requeue_count"));
    t.degradeCount = Db.i(r.get("degrade_count")); t.healCount = Db.i(r.get("heal_count")); t.stepAttempts = Db.i(r.get("step_attempts")); t.budgetIterations = Db.i(r.get("budget_iterations")); t.spentIterations = Db.i(r.get("spent_iterations"));
    t.paused = Db.i(r.get("paused")); t.feedbackDone = Db.i(r.get("feedback_done")); t.acceptedOnce = Db.i(r.get("accepted_once")); t.version = Db.i(r.get("version"));
    t.path = r.get("path") == null ? new ArrayList<>() : Json.parse((String) r.get("path"), new TypeReference<List<String>>() {});
    t.discardDetail = r.get("discard_detail") == null ? null : Json.tree((String) r.get("discard_detail"));
    t.plan = r.get("plan") == null ? null : Json.tree((String) r.get("plan"));
    t.context = r.get("context") == null ? new TaskCtx() : Json.parse((String) r.get("context"), TaskCtx.class);
    t.release = r.get("release") == null ? new LinkedHashMap<>() : Json.parse((String) r.get("release"), new TypeReference<Map<String, Object>>() {});
    return t;
  }

  public Task get(String id) { var r = db.row("SELECT * FROM task WHERE id=?" + com.x7x24.tenant.Tenant.and("workspace_id"), com.x7x24.tenant.Tenant.args(id)); return r == null ? null : map(r); }
  public List<Task> query(String where, Object... args) {
    // 工作区过滤放在最前面（where 里可能带 ORDER BY / LIMIT）
    String ws = com.x7x24.tenant.Tenant.current(); Object[] a = args;
    if (ws != null) { a = new Object[args.length + 1]; a[0] = ws; System.arraycopy(args, 0, a, 1, args.length); }
    String w = ws == null ? (where == null ? "" : " WHERE " + where) : " WHERE workspace_id=?" + (where == null ? "" : where.trim().toUpperCase().startsWith("ORDER BY") ? " " + where : " AND " + where);
    return db.rows("SELECT * FROM task" + w, a).stream().map(TaskRepo::map).toList();
  }

  /** 乐观锁保存（version 必须匹配）；成功则 version+1 并回写到对象。 */
  public boolean save(Task t) {
    int n = db.update("""
        UPDATE task SET current_node=?, current_stage=?, execution_state=?, acceptance_criteria=?, title=?, problem=?, priority_score=?, risk_level=?, status=?, path=?, scope_level=?, strategy_variant=?, current_iteration=?, loop_count=?, reject_count=?,
        review_retry=?, rollback_count=?, requeue_count=?, degrade_count=?, heal_count=?, step_attempts=?, cooldown_until=?, parent_task_id=?, discard_reason=?, discard_detail=?,
        budget_tokens=?, budget_usd=?, budget_ci=?, budget_iterations=?, plan=?, context=?, release=?, paused=?, anomaly_from=?, observe_until=?, feedback_done=?, accepted_once=?,
        next_run_at=?, status_changed_at=?, released_at=?, version=version+1 WHERE id=? AND version=?""",
        t.currentNode, t.currentStage, t.executionState, t.acceptanceCriteria, t.title, t.problem, t.priorityScore, t.riskLevel, t.status, Json.str(t.path), t.scopeLevel, t.strategyVariant, t.currentIteration, t.loopCount, t.rejectCount,
        t.reviewRetry, t.rollbackCount, t.requeueCount, t.degradeCount, t.healCount, t.stepAttempts, t.cooldownUntil, t.parentTaskId, t.discardReason, t.discardDetail == null ? null : t.discardDetail.toString(),
        t.budgetTokens, t.budgetUsd, t.budgetCi, t.budgetIterations, t.plan == null ? null : t.plan.toString(), Json.str(t.context), Json.str(t.release), t.paused, t.anomalyFrom, t.observeUntil, t.feedbackDone, t.acceptedOnce,
        t.nextRunAt, t.statusChangedAt, t.releasedAt, t.id, t.version);
    if (n > 0) t.version++;
    return n > 0;
  }
}
