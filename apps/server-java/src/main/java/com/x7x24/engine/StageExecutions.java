package com.x7x24.engine;

import com.x7x24.adapters.Redactor;
import com.x7x24.db.Db;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** 节点执行的持久化记录；同一任务版本重放复用 operation_key。 */
@Component
public class StageExecutions {
  private final Db db; private final Clk clk;
  public StageExecutions(Db db, Clk clk) { this.db = db; this.clk = clk; }
  public String begin(Task task) {
    String key = task.id + ":step:v" + task.version;
    boolean handler = Set.of("ANOMALY", "DEGRADED").contains(task.status);
    String stage = handler ? task.status : task.currentStage;
    var old = db.row("SELECT id FROM stage_execution WHERE operation_key=?", key);
    String id;
    if (old != null) { id = Db.str(old.get("id")); db.update("UPDATE stage_execution SET state='RUNNING',ended_at=NULL WHERE id=?", id); }
    else {
      id = U.uuid(); int attempt = 1 + (int) db.count("SELECT COUNT(*) FROM stage_execution WHERE task_id=? AND iteration_seq=? AND stage=?", task.id, task.currentIteration, stage);
      Map<String, Object> input = new LinkedHashMap<>(Map.of("projectId", task.projectId, "projectConfigVersion", task.context.projectConfigVersion, "pipelineVersion", task.pipelineVersion, "taskVersion", task.version, "workflowStatus", task.status));
      if (task.currentNode != null) input.put("node", handler ? task.status : task.currentNode);
      if (task.context.pr != null) input.put("headSha", task.context.pr.headSha);
      db.update("INSERT INTO stage_execution (id,task_id,iteration_seq,stage,attempt,operation_key,state,input,started_at) VALUES (?,?,?,?,?,?,'RUNNING',?,?)", id, task.id, task.currentIteration, stage, attempt, key, Json.str(input), clk.iso());
    }
    db.update("UPDATE task SET execution_state='RUNNING' WHERE id=? AND version=?", task.id, task.version);
    return id;
  }
  public void finish(String id, Task before, Task after, String error, Decision decision) {
    if (after == null) return;
    db.update("UPDATE task SET execution_state='WAITING' WHERE id=? AND execution_state='RUNNING'", before.id);
    String state = error != null ? "FAILED" : !before.status.equals(after.status) || !java.util.Objects.equals(before.currentStage, after.currentStage) || after.feedbackDone != 0 ? "SUCCEEDED" : "WAITING";
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("from", before.status); output.put("to", after.status); output.put("nextRunAt", after.nextRunAt);
    if (decision instanceof Decision.Move move) output.put("edge", move.edge());
    String result = outcome(state, before.status, after.status, String.valueOf(output.get("edge")));
    if (error == null && decision instanceof Decision.Move move && move.anomaly() != null
        && Set.of("same_failure", "rework_exhausted").contains(move.anomaly().type())) result = "FAILED";
    if(error==null && RecoveryService.stopped(after))result="BLOCKED";
    output.put("outcome", result);
    if (after.context.pr != null) output.put("headSha", after.context.pr.headSha);
    db.update("UPDATE stage_execution SET state=?,output=?,error=?,tokens=?,usd=?,ended_at=? WHERE id=?", state, Json.str(output), error == null ? null : Redactor.redact(error), Math.max(0, after.spentTokens - before.spentTokens), Math.max(0, after.spentUsd - before.spentUsd), clk.iso(), id);
  }
  /** 执行成功仅表示本次处理完成；测试、评审和阻塞有独立业务结论。也兼容旧记录。 */
  public static String outcome(String state, String from, String to, String edge) {
    if ("SKIPPED".equals(state)) return "SKIPPED";
    if ("FAILED".equals(state)) return "ERROR";
    if (Set.of("ANOMALY", "DEGRADED").contains(to)) return "BLOCKED";
    if (Set.of("ANOMALY", "DEGRADED").contains(from)) return "SUCCEEDED".equals(state) ? "RECOVERED" : "PENDING";
    if (Set.of("qa_loop", "node_fix", "reject", "review_pin", "rollback").contains(edge)
        || "QA".equals(from) && Set.of("DEV", "PLANNING").contains(to)
        || "ACCEPTANCE".equals(from) && Set.of("DEV", "PLANNING").contains(to)
        || "PRD_REVIEW".equals(from) && "BACKLOG".equals(to)
        || Set.of("DELIVERED", "RELEASED").contains(from) && "PLANNING".equals(to)) return "FAILED";
    if ("split".equals(edge) || "DEV".equals(from) && "PLANNING".equals(to) || "DISCARDED".equals(to)) return "PENDING";
    return "SUCCEEDED".equals(state) ? "PASSED" : "PENDING";
  }

  /** 按依赖闭包失效，保留不受影响的上游与旁支。 */
  public static Set<String> descendants(com.x7x24.pipeline.Pipeline.Graph graph, String node) {
    Set<String> out = new LinkedHashSet<>(); if (node == null) return out; out.add(node);
    boolean changed;
    do {
      changed = false;
      for (var edge : graph.edges) if (edge.size() >= 2 && out.contains(edge.get(0))) changed |= out.add(edge.get(1));
      for (var n : graph.nodes) if (n.parent != null && out.contains(n.parent)) changed |= out.add(n.id);
    } while (changed);
    return out;
  }
  public void invalidate(Task before, Task after, com.x7x24.pipeline.Pipeline.Graph graph, Decision decision) {
    Set<String> affected = new LinkedHashSet<>();
    if (decision instanceof Decision.Move move && !Set.of("ANOMALY", "DEGRADED", "DISCARDED").contains(after.status)) {
      String target = after.currentNode;
      var downstream = descendants(graph, target);
      boolean replan = "replan".equals(Json.M.valueToTree(move.payload()).path("action").asText());
      if (replan || !Objects.equals(target, before.currentNode) && downstream.contains(before.currentNode)) affected.addAll(downstream);
    }
    String oldHead = before.context.pr == null ? null : before.context.pr.headSha;
    String newHead = after.context.pr == null ? null : after.context.pr.headSha;
    if (!Objects.equals(oldHead, newHead)) affected.addAll(descendants(graph, "QA"));
    for (String node : affected) after.context.nodeInvalidations.put(node, before.version + 1);
    clearArtifacts(after, affected);
  }

  static void clearArtifacts(Task task, Set<String> affected) {
    if (!affected.isEmpty()) task.context.artifacts.values().removeIf(artifact -> artifact != null && affected.contains(artifact.get("stage")));
  }
  public void skip(Task task, String stage) { skip(task, stage, "任务规划未要求该阶段"); }
  public void skip(Task task, String stage, String reason) {
    String key = task.id + ":it" + task.currentIteration + ":skip:" + stage;
    if (db.count("SELECT COUNT(*) FROM stage_execution WHERE operation_key=?", key) > 0) return;
    db.update("INSERT INTO stage_execution (id,task_id,iteration_seq,stage,attempt,operation_key,state,input,output,started_at,ended_at) VALUES (?,?,?,?,0,?,'SKIPPED',?,?,?,?)", U.uuid(), task.id, task.currentIteration, stage, key, Json.str(Map.of("path", task.path, "taskVersion", task.version)), Json.str(Map.of("reason", reason, "outcome", "SKIPPED")), clk.iso(), clk.iso());
  }
  public List<Map<String, Object>> list(String taskId) {
    return db.rows("SELECT * FROM stage_execution WHERE task_id=? ORDER BY started_at,id", taskId).stream().map(row -> {
      var out = new LinkedHashMap<>(row); var input = Json.tree(Db.str(row.get("input"))); var output = Json.tree(Db.str(row.get("output")));
      out.put("input", input); out.put("output", output);
      out.put("outcome", output.path("outcome").asText(outcome(String.valueOf(row.get("state")), output.path("from").asText(input.path("workflowStatus").asText("")), output.path("to").asText(""), output.path("edge").asText(""))));
      List<Object> args = new ArrayList<>(Arrays.asList(taskId, row.get("iteration_seq"), row.get("stage"), row.get("started_at")));
      String query = "SELECT id FROM evidence WHERE task_id=? AND iteration_seq=? AND stage=? AND created_at>=?";
      if (row.get("ended_at") != null) { query += " AND created_at<=?"; args.add(row.get("ended_at")); }
      out.put("evidenceIds", db.rows(query, args.toArray()).stream().map(e -> e.get("id")).toList()); return (Map<String, Object>) out;
    }).toList();
  }
}
