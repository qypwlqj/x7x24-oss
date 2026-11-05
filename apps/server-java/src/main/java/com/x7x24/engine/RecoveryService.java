package com.x7x24.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.Redactor;
import com.x7x24.core.Status;
import com.x7x24.db.Db;
import com.x7x24.gov.AuditLog;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Consumer;

/** Durable stop/resume boundaries. A failed attempt never ends the delivery task. */
@Component
public class RecoveryService {
  private final Db db; private final TaskRepo tasks; private final Clk clk; private final AuditLog audit; private final com.x7x24.notification.NotificationEvents notifications;
  public RecoveryService(Db db, TaskRepo tasks, Clk clk, AuditLog audit,com.x7x24.notification.NotificationEvents notifications) { this.db=db; this.tasks=tasks; this.clk=clk; this.audit=audit;this.notifications=notifications; }
  public static boolean stopped(Task t) { return Set.of("BLOCKED","FAILED","WAITING_CONFIGURATION","WAITING_INPUT","WAITING_PLAN","WAITING_APPROVAL","WAITING_VALIDATION","WAITING_EXTERNAL","RECOVERY_REQUIRED","CANCELLED").contains(t.executionState) && t.nextRunAt==null; }
  private Task task(String id) { var t=tasks.get(id); if(t==null || t.deletedAt!=null)throw new IllegalArgumentException("任务不存在"); return t; }

  public Map<String,Object> cursor(Task t) {
    var p=new LinkedHashMap<String,Object>(); String phase=t.status, stage=t.currentStage, node=t.currentNode;
    if(Set.of("ANOMALY","DEGRADED","DISCARDED").contains(phase)) {
      var origin=db.row("SELECT from_status FROM task_event WHERE task_id=? AND event_type='STATUS_CHANGED' AND from_status NOT IN ('ANOMALY','DEGRADED','DISCARDED') ORDER BY id DESC LIMIT 1",t.id);
      phase=origin==null ? t.anomalyFrom : Db.str(origin.get("from_status"));
      if(stage!=null)try{var savedStage=Status.valueOf(stage);if(!Set.of(Status.ANOMALY,Status.DEGRADED,Status.DISCARDED).contains(savedStage))phase=stage;}catch(IllegalArgumentException ignored){ /* custom node: use its owning phase from history */ }
      var execution=db.row("SELECT stage,input FROM stage_execution WHERE task_id=? AND stage NOT IN ('ANOMALY','DEGRADED','DISCARDED') ORDER BY started_at DESC,id DESC LIMIT 1",t.id);
      if(execution!=null) { stage=Db.str(execution.get("stage")); node=Json.tree(Db.str(execution.get("input"))).path("node").asText(stage); }
      else if(stage==null || Set.of("ANOMALY","DEGRADED","DISCARDED").contains(stage)) stage=phase;
    }
    p.put("phase",phase);p.put("stage",stage);p.put("node",node);p.put("segmentIndex",t.context.subtaskIdx);p.put("iteration",t.currentIteration);
    int parts=t.plan==null?0:t.plan.path("subtasks").size();p.put("parts",parts);p.put("completedSegments","DEV".equals(phase)?Math.min(t.context.subtaskIdx,parts):Set.of("QA","ACCEPTANCE","DELIVERED","RELEASED").contains(Objects.toString(phase,""))?parts:0);
    p.put("configSnapshot",t.context.configSnapshot);p.put("planDigest",t.plan==null?null:U.sha256(t.plan.toString()));
    p.put("pr",t.context.pr);p.put("ci",t.context.ci==null?null:Map.of("runIds",t.context.ci.runIds,"headSha",String.valueOf(t.context.ci.headSha)));
    p.put("merge",t.context.merge);p.put("release",t.context.rel);
    p.put("attempts",Map.of("step",t.stepAttempts,"heal",t.healCount,"loop",t.loopCount,"reject",t.rejectCount,"degrade",t.degradeCount));
    return p;
  }
  /** Called under the task transaction; immutable cursor and task stop commit together. */
  public void block(Task before,Task after,String code,Object detail) {
    var point=cursor(before); var row=db.row("SELECT id FROM task_recovery WHERE task_id=? AND task_version=?",before.id,before.version);
    String id=row==null?U.uuid():Db.str(row.get("id"));
    if(row==null) db.update("INSERT INTO task_recovery(id,task_id,workspace_id,task_version,code,detail,checkpoint,state,created_at) VALUES(?,?,?,?,?,?,?,'OPEN',?)",id,before.id,before.workspaceId,before.version,code,Redactor.json(detail),Redactor.json(point),clk.iso());
    if(!"manual_abandon".equals(code) && row==null) {
      db.update("INSERT INTO anomaly(id,task_id,stage,type,severity,status,heal_steps,opened_at) VALUES(?,?,?,?,?,'open','[]',?)",id,before.id,Objects.toString(point.get("phase"),before.status),code,"medium",clk.iso());
      after.anomalyFrom=Objects.toString(point.get("phase"),before.status);
    }
    String phase=(String)point.get("phase");
    if(phase!=null && !Set.of("ANOMALY","DEGRADED","DISCARDED").contains(phase))after.status=phase;
    after.currentStage=(String)point.get("stage");after.currentNode=(String)point.get("node");after.context.recoveryId=id;
    after.executionState="needs_clarification".equals(code)?"WAITING_INPUT":"outcome_unknown".equals(code)?"RECOVERY_REQUIRED":"BLOCKED";after.nextRunAt=null;after.cooldownUntil=null;
    if (ReleasePolicy.WAIT_CODE.equals(code)) after.executionState = "WAITING_CONFIGURATION";
    after.context.failures.add(new TaskCtx.Failure(after.currentStage,code,Redactor.redact(detail),clk.iso()));
  }
  /** Shared timeline and attention events for both execution engines. */
  public void publishBlock(Task before,Task after,String code,Object detail) {
    var point=cursor(before);var payload=new LinkedHashMap<String,Object>();
    payload.put("code",code);payload.put("detail",Redactor.redact(detail));payload.put("node",after.currentStage);payload.put("segment",after.context.subtaskIdx+1);payload.put("parts",point.get("parts"));payload.put("completedSegments",point.get("completedSegments"));payload.put("keptProgress",true);
    audit.task(after.id,after.currentIteration,"TASK_BLOCKED","engine",payload,before.id+":blocked:v"+before.version);
    audit.task(after.id,after.currentIteration,"ANOMALY_OPENED","engine",Map.of("type",code,"detail",Redactor.redact(detail)),before.id+":anomaly:v"+before.version);
    audit.task(after.id,after.currentIteration,"OWNER_NEEDED","engine",Map.of("anomaly",code,"detail",Redactor.redact(detail)),before.id+":owner:v"+before.version);
    String key=before.id+":notify:block:v"+before.version;
    if(db.count("SELECT COUNT(*) FROM notification_event WHERE id=?",key)==0)notifications.task(after,ReleasePolicy.WAIT_CODE.equals(code)?"action_required":"anomaly",key,ReleasePolicy.WAIT_CODE.equals(code)?"待配置发布："+Redactor.redact(detail):"任务停在「"+after.currentStage+"」，进度已保存，处理后可恢复执行。");
  }
  public Map<String,Object> describe(Task t) {
    var row= t.context.recoveryId==null?null:db.row("SELECT id,code,detail,checkpoint,state FROM task_recovery WHERE id=? AND task_id=? AND workspace_id=?",t.context.recoveryId,t.id,t.workspaceId);
    var out=new LinkedHashMap<String,Object>();out.put("canResume",stopped(t)||Set.of("ANOMALY","DEGRADED","DISCARDED").contains(t.status));
    out.put("checkpoint",row==null?cursor(t):Json.tree(Db.str(row.get("checkpoint"))));
    out.put("code",row==null?t.discardReason:row.get("code"));out.put("detail",row==null?t.discardDetail:readDetail(Db.str(row.get("detail"))));out.put("state",row==null?t.executionState:row.get("state"));
    return out;
  }
  private Object readDetail(String value) {var n=Json.tree(value);return n.isTextual()?n.asText():n;}
  public void assertStoppedExecutors(Task t) {
    String prefix=t.id+":";
    if(db.count("SELECT COUNT(*) FROM tool_ops WHERE LEFT(op_key,?)=? AND state IN ('DISPATCHING','OUTCOME_UNKNOWN')",prefix.length(),prefix)>0)
      throw new IllegalArgumentException("有结果未知的外部操作，请先核对原操作结果，不能重新执行");
    if(db.count("SELECT COUNT(*) FROM node_run WHERE task_id=? AND workspace_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN')",t.id,t.workspaceId)>0)
      throw new IllegalArgumentException("旧执行尚未确认停止，请先核对执行结果");
  }
  public Task resume(String id,int expectedVersion,String actor,Consumer<Task> guard) {
    return db.tx(()->{
      var visible=task(id); db.row("SELECT id FROM task WHERE id=? FOR UPDATE",visible.id);var t=task(id);
      if(t.version!=expectedVersion)throw new IllegalArgumentException("任务已变化，请刷新后再恢复");
      if(!stopped(t) && !Set.of("ANOMALY","DEGRADED","DISCARDED").contains(t.status) && t.paused==0)throw new IllegalArgumentException("任务没有停止，无需重复恢复");
      assertStoppedExecutors(t);
      var before=t.copy();var point=cursor(t);
      boolean pausedOnly=t.paused!=0 && !stopped(t) && !Set.of("ANOMALY","DEGRADED","DISCARDED").contains(t.status);
      if(!pausedOnly && t.context.recoveryId!=null){var row=db.row("SELECT checkpoint FROM task_recovery WHERE id=? AND task_id=? AND workspace_id=?",t.context.recoveryId,t.id,t.workspaceId);if(row!=null)point=Json.map(Json.tree(Db.str(row.get("checkpoint"))));}
      String digest=(String)point.get("planDigest");
      if(digest!=null && t.plan!=null && !digest.equals(U.sha256(t.plan.toString())))throw new IllegalArgumentException("计划内容已变化，请在调整方案中核对受影响的节点后继续");
      String phase=(String)point.get("phase");
      if(phase==null || Set.of("ANOMALY","DEGRADED","DISCARDED").contains(phase))throw new IllegalArgumentException("历史记录不足，无法确定原节点；请明确选择恢复位置或从头重新开始");
      if(!pausedOnly && t.context.recoveryId==null) {
        block(before,t,Objects.toString(t.discardReason,"legacy_failure"),"从历史记录恢复原进度");
      }
      Status.valueOf(phase);
      t.status=phase;t.currentStage=Objects.toString(point.get("stage"),phase);t.currentNode=Objects.toString(point.get("node"),t.currentStage);
      // Do not replace plan, segment index, outputs, spending, or completed external effects.
      guard.accept(t);
      t.context.lastProgressAt=clk.iso();
      t.context.signals.removeIf(TaskCtx.Sig::timeout);
      t.paused=0;t.stepAttempts=0;t.context.policyAttempts=0;t.context.recoveryGeneration++;t.discardReason=null;t.discardDetail=null;t.cooldownUntil=null;t.executionState="WAITING";t.nextRunAt=clk.iso();
      if(!tasks.save(t))throw new IllegalArgumentException("任务已变化，请刷新后重试");
      if(!pausedOnly && t.context.recoveryId!=null)db.update("UPDATE anomaly SET status='healing',resolution='resume_requested' WHERE id=? AND task_id=?",t.context.recoveryId,t.id);
      if(!pausedOnly && t.context.recoveryId!=null)db.update("UPDATE task_recovery SET state='RESUMING',resumed_at=?,actor=? WHERE id=? AND task_id=? AND workspace_id=?",clk.iso(),actor,t.context.recoveryId,t.id,t.workspaceId);
      if(!before.status.equals(t.status))audit.append(new AuditLog.Input(t.id,t.currentIteration,"STATUS_CHANGED",before.status,t.status,"restore_progress",actor,null,null,Map.of("checkpoint",point),t.id+":restore:v"+before.version,clk.iso()));
      var payload=new LinkedHashMap<String,Object>();
      payload.put("from",before.status);payload.put("to",t.status);payload.put("node",t.currentStage);payload.put("segment",t.context.subtaskIdx+1);payload.put("keptProgress",true);
      if(t.context.reworkQuota.round!=before.context.reworkQuota.round) {
        payload.put("reworkReset",Map.of("before",before.context.reworkQuota,"after",t.context.reworkQuota,
            "totals",Map.of("qa",t.loopCount,"reject",t.rejectCount,"nodes",t.context.nodeLoops)));
      }
      audit.task(id,t.currentIteration,"TASK_RESUMED",actor,payload,id+":resume:v"+before.version);
      return t;
    });
  }
  public void complete(Task before,Task after) {
    if(before.context.recoveryId==null || stopped(after))return;
    if(before.context.reworkQuota.pendingNode!=null)return; // 派发返修还不代表修复成功。
    if(!Objects.equals(before.currentStage,after.currentStage) || after.context.subtaskIdx>before.context.subtaskIdx || after.feedbackDone>before.feedbackDone)
      markResolved(before);
  }
  public void markResolved(Task task) {
    if(task.context.recoveryId==null)return;
    db.update("UPDATE task_recovery SET state='RESOLVED',resolved_at=? WHERE id=? AND task_id=? AND workspace_id=? AND state IN ('OPEN','RESUMING')",clk.iso(),task.context.recoveryId,task.id,task.workspaceId);
    db.update("UPDATE anomaly SET status='resolved',resolution='step_passed',resolved_at=? WHERE id=? AND task_id=? AND status IN ('open','healing')",clk.iso(),task.context.recoveryId,task.id);
  }

  public JsonNode checkpoint(Task t,String key) {var r=db.row("SELECT body FROM task_step_checkpoint WHERE id=? AND task_id=? AND workspace_id=?",key,t.id,t.workspaceId);return r==null?null:Json.tree(Db.str(r.get("body")));}
  public void checkpoint(Task t,String key,Object body) {
    String content=Redactor.json(body);
    if(db.update("UPDATE task_step_checkpoint SET body=?,updated_at=? WHERE id=? AND task_id=? AND workspace_id=?",content,clk.iso(),key,t.id,t.workspaceId)==0)
      db.update("INSERT INTO task_step_checkpoint(id,task_id,workspace_id,body,updated_at) VALUES(?,?,?,?,?)",key,t.id,t.workspaceId,content,clk.iso());
  }
}
