package com.x7x24.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.db.Db;
import com.x7x24.engine.Engine;
import com.x7x24.engine.ProjectRepo;
import com.x7x24.engine.TaskRepo;
import com.x7x24.engine.StageExecutions;
import com.x7x24.pipeline.Assembler;
import com.x7x24.pipeline.PipelineService;
import com.x7x24.pipeline.Plans;
import com.x7x24.util.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 任务钉住的流程计划（含与模板的差异、流程图、每个节点的执行记录与过程记录）；任务读取走工作区过滤，别的工作区的任务 404 */
@RestController
public class TaskPlanApi {
  private final TaskRepo repo; private final Engine engine; private final Db db; private final PipelineService pipes; private final ProjectRepo projects; private final com.x7x24.gov.AuditLog audit; private final com.x7x24.gov.ConfigStore cfg;
  public TaskPlanApi(TaskRepo repo, Engine engine, Db db, PipelineService pipes, ProjectRepo projects, com.x7x24.gov.AuditLog audit, com.x7x24.gov.ConfigStore cfg) { this.repo = repo; this.engine = engine; this.db = db; this.pipes = pipes; this.projects = projects; this.audit = audit; this.cfg = cfg; }
  public static final int MAX_REVISIONS = 3;

  @GetMapping("/api/tasks/{id}/plan") public Map<String, Object> plan(@PathVariable String id) {
    var t = repo.get(id); if (t == null) throw new com.x7x24.tenant.WorkspaceService.NotMember();
    var e = engine.eff(t); var v = PlanService.view(e, e.plan, t.context.configSnapshot);
    v.put("currentNode", t.currentNode); v.put("currentIteration", t.currentIteration); v.put("status", t.status); v.put("pipelineVersion", t.pipelineVersion); v.put("stacks", t.context.stacks);
    v.put("paused", t.paused != 0); v.put("executionState", t.executionState);
    v.put("revisions", t.context.planRevisions); v.put("maxRevisions", MAX_REVISIONS);
    // 每个节点的执行记录（按节点汇总：尝试次数、最后状态、费用）与过程记录
    // runs = 进入这个节点的次数（连续的轮询 / 重试算同一次）；attempts = 实际执行（成功或出错）的次数；轮询等待不计
    Map<String, Map<String, Object>> rec = new LinkedHashMap<>(); String lastNode = null;
    for (var r : db.rows("SELECT id, iteration_seq, stage, input, output, state, usd, tokens, started_at, ended_at FROM stage_execution WHERE task_id=? ORDER BY started_at, id", id)) {
      var input = Json.tree(Db.str(r.get("input"))); var output = Json.tree(Db.str(r.get("output")));
      String node = input.path("node").asText(null); String st = Db.str(r.get("stage"));
      String from = output.path("from").asText(input.path("workflowStatus").asText(""));
      // 旧版本把自愈记录写在失败节点下；读取时也将其归回异常处理，避免覆盖测试结论。
      if (Set.of("ANOMALY", "DEGRADED").contains(from)) node = from;
      if (node == null) node = "BACKLOG".equals(st) ? "INTAKE" : "DESIGN".equals(st) ? com.x7x24.pipeline.Nodes.nodeOfStage(st, e.stage(st)) : st;
      var m = rec.computeIfAbsent(node, k -> new LinkedHashMap<>(Map.of("runs", 0, "usd", 0.0, "tokens", 0L)));
      @SuppressWarnings("unchecked") var history = (List<Map<String, Object>>) m.computeIfAbsent("history", k -> new ArrayList<>());
      if (m.containsKey("state") && !Set.of("PENDING", "RECOVERED").contains(String.valueOf(m.get("outcome")))) {
        Map<String, Object> previous = new LinkedHashMap<>();
        for (String key : List.of("id", "state", "outcome", "version", "headSha", "iteration", "endedAt")) if (m.get(key) != null) previous.put(key, m.get(key));
        history.add(previous);
      }
      boolean entered = !node.equals(lastNode); lastNode = node;
      if (entered) m.put("runs", (Integer) m.get("runs") + 1);
      if (!"WAITING".equals(r.get("state")) && !"SKIPPED".equals(r.get("state"))) m.merge("attempts", 1, (x, y) -> (Integer) x + (Integer) y);
      m.put("state", r.get("state")); m.put("usd", (Double) m.get("usd") + Db.d(r.get("usd"))); m.put("tokens", (Long) m.get("tokens") + Db.l(r.get("tokens")));
      m.put("id", r.get("id")); m.put("version", input.path("taskVersion").asInt()); m.put("iteration", r.get("iteration_seq"));
      m.put("outcome", output.path("outcome").asText(StageExecutions.outcome(String.valueOf(r.get("state")), from, output.path("to").asText(""), output.path("edge").asText(""))));
      m.put("headSha", output.path("headSha").asText(input.path("headSha").asText(null)));
      m.putIfAbsent("startedAt", r.get("started_at")); m.put("endedAt", r.get("ended_at"));
      if ("SKIPPED".equals(r.get("state"))) { if (entered) m.put("runs", (Integer) m.get("runs") - 1); m.put("reason", Json.tree(Db.str(r.get("output"))).path("reason").asText(null)); }
    }
    var g = Json.M.convertValue(v.get("graph"), com.x7x24.pipeline.Pipeline.Graph.class);
    Map<String, Set<String>> downstream = new HashMap<>();
    if (g != null) for (String node : rec.keySet()) downstream.put(node, StageExecutions.descendants(g, node));
    Set<String> checkedCode = g == null ? Set.of() : StageExecutions.descendants(g, "QA");
    for (var entry : rec.entrySet()) {
      String node = entry.getKey(); var m = entry.getValue(); int version = ((Number) m.get("version")).intValue();
      boolean valid = version >= t.context.nodeInvalidations.getOrDefault(node, 0);
      // 无需改写旧数据：上游在它之后重新执行过时，旧下游结果也已经失效。
      if (g != null) for (var upstream : rec.entrySet()) if (!node.equals(upstream.getKey())
          && ((Number) upstream.getValue().get("version")).intValue() > version
          && downstream.get(upstream.getKey()).contains(node)) { valid = false; break; }
      if (checkedCode.contains(node) && m.get("headSha") != null && t.context.pr != null
          && !Objects.equals(m.get("headSha"), t.context.pr.headSha)) valid = false;
      m.put("valid", valid);
    }
    Map<String, List<String>> ev = new LinkedHashMap<>();
    for (var r : db.rows("SELECT stage, kind FROM evidence WHERE task_id=? ORDER BY created_at, id", id)) {
      String st = Db.str(r.get("stage")); String node = "DESIGN".equals(st) ? com.x7x24.pipeline.Nodes.nodeOfStage(st, e.stage(st)) : st;
      var l = ev.computeIfAbsent(node, k -> new ArrayList<>()); String k = Db.str(r.get("kind")); if (!l.contains(k)) l.add(k);
    }
    v.put("records", rec); v.put("evidence", ev); v.put("nodeLoops", t.context.nodeLoops);
    v.put("routes", routes(g, e, cfg.get().loops, db.rows("SELECT from_status, to_status, edge, payload FROM task_event WHERE task_id=? AND event_type='STATUS_CHANGED' AND from_status IS NOT NULL ORDER BY occurred_at", id)));
    return v;
  }

  /**
   * 失败 / 异常去向（与引擎的路由规则一致）：测试未过 → 编码；评审 / 审计节点不通过 → 编码；验收退回 → 任务规划；
   * 设计评审连续未过、编码出错或改动超限 → 异常处理；返修 / 退回次数用完 → 省钱模式；异常修复后、省钱模式换方案后 → 编码。
   * count = 这个任务实际走过几次（来自状态变化事件）；不在静态规则里但实际发生过的去向也会补上。
   */
  public static List<Map<String, Object>> routes(com.x7x24.pipeline.Pipeline.Graph g, com.x7x24.pipeline.Pipeline.Effective e, com.x7x24.gov.Governance.Loops loops, List<Map<String, Object>> events) {
    if (g == null || g.nodes == null) return List.of();
    Set<String> ids = new LinkedHashSet<>(); for (var n : g.nodes) if (n.parent == null) ids.add(n.id);
    Map<String, Map<String, Object>> out = new LinkedHashMap<>();
    java.util.function.BiConsumer<String[], Integer> add = (a, max) -> { // a = from, to, kind, label, key
      if (!ids.contains(a[0]) && !Set.of("ANOMALY", "DEGRADED").contains(a[0]) || !ids.contains(a[1]) && !Set.of("ANOMALY", "DEGRADED").contains(a[1])) return;
      Map<String, Object> m = new LinkedHashMap<>(); m.put("from", a[0]); m.put("to", a[1]); m.put("kind", a[2]); m.put("label", a[3]); m.put("count", 0); if (max != null) m.put("max", max);
      out.putIfAbsent(a[0] + ">" + a[1], m);
    };
    String dev = ids.contains("DEV") ? "DEV" : null;
    if (dev != null) {
      if (ids.contains("QA")) { var qa = e == null ? null : e.stage("QA"); add.accept(new String[]{"QA", dev, "loop", "测试未过"}, qa != null && qa.maxLoops != null ? qa.maxLoops : loops.maxLoops); }
      for (var n : g.nodes) if (n.parent == null && "delivery".equals(n.module) && !Set.of("QA", "ACCEPTANCE").contains(n.id) && ("review".equals(n.executor) || "llm_checker".equals(n.executor) || "review".equals(n.family) || n.id.contains("AUDIT") || n.id.contains("REVIEW"))) {
        var sc = e == null ? null : e.stage(n.id); add.accept(new String[]{n.id, dev, "loop", n.id.contains("AUDIT") ? "审计不通过" : "评审不通过"}, sc != null && sc.maxLoops != null ? sc.maxLoops : 2);
      }
    }
    if (ids.contains("ACCEPTANCE") && ids.contains("PLANNING")) add.accept(new String[]{"ACCEPTANCE", "PLANNING", "loop", "验收退回"}, loops.maxRejects);
    for (var n : g.nodes) if (n.parent == null && "production".equals(n.module) && !"DEV".equals(n.id) && !"SPEC".equals(n.id)) { add.accept(new String[]{n.id, "ANOMALY", "exc", "设计评审未过"}, null); break; }
    if (dev != null) add.accept(new String[]{dev, "ANOMALY", "exc", "出错 / 超限"}, null);
    // 返修用完 / 同样的失败反复出现 / CI 本身坏了 → 异常处理（换方案或等负责人）；省钱模式只由预算触发，走过才画
    if (ids.contains("QA")) add.accept(new String[]{"QA", "ANOMALY", "exc", "测试卡住"}, null);
    if (dev != null) add.accept(new String[]{"ANOMALY", dev, "exc", "修复后继续"}, null);
    // 实际走过的次数
    for (var r : events) {
      String from = String.valueOf(r.get("from_status")), to = String.valueOf(r.get("to_status")), edge = String.valueOf(r.get("edge"));
      var p = Json.tree(Db.str(r.get("payload"))); String node = p.path("node").asText(null);
      String a = node != null && ("node_fix".equals(edge) || "reject".equals(edge)) ? node : nodeOf(from, e), b = nodeOf(to, e);
      if ("DEGRADED".equals(from) && "PLANNING".equals(to) || "ANOMALY".equals(from) && "PLANNING".equals(to)) b = "PLANNING";
      if (a.equals(b) || !"loop".equals(kindOf(from, to, edge)) && !"exc".equals(kindOf(from, to, edge))) continue;
      String k = a + ">" + b;
      String lbl = "DEGRADED".equals(b) ? ("budget".equals(p.path("category").asText()) || "PLANNING".equals(a) ? "预算用完" : "次数用完") : "ANOMALY".equals(b) && "QA".equals(a) ? "测试卡住" : "ANOMALY".equals(a) && "QA".equals(b) ? "CI 修好后重跑" : "DEGRADED".equals(a) ? (p.path("action").asText("").contains("缩小") ? "缩小范围重新规划" : "换方案继续") : "ANOMALY".equals(a) ? "修复后继续" : EDGE_TEXT.getOrDefault(edge, edge);
      if (!out.containsKey(k)) add.accept(new String[]{a, b, kindOf(from, to, edge), lbl}, null);
      var m = out.get(k); if (m != null) m.put("count", (Integer) m.get("count") + 1);
    }
    return new ArrayList<>(out.values());
  }
  static final Map<String, String> EDGE_TEXT = Map.of("qa_loop", "测试未过", "node_fix", "评审不通过", "reject", "验收退回", "anomaly", "出错", "degrade", "省钱模式", "resume", "修复后继续", "split", "改动太大，拆段", "rollback", "撤回上线");
  static String kindOf(String from, String to, String edge) {
    if ("ANOMALY".equals(to) || "DEGRADED".equals(to) || "ANOMALY".equals(from) || "DEGRADED".equals(from)) return "exc";
    return Set.of("qa_loop", "node_fix", "reject", "split", "rollback").contains(edge) ? "loop" : "flow";
  }
  static String nodeOf(String status, com.x7x24.pipeline.Pipeline.Effective e) {
    if ("BACKLOG".equals(status)) return "INTAKE";
    if ("DESIGN".equals(status) && e != null) return com.x7x24.pipeline.Nodes.nodeOfStage("DESIGN", e.stage("DESIGN"));
    return status;
  }

  /** 中途修订（决策 D9）：工作区管理员发起；收紧直接生效，放松由发起的管理员当场批准并记审计；最多 3 次；已执行的节点不重跑 */
  @PostMapping("/api/tasks/{id}/plan/revise") public Map<String, Object> revise(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(req, "修订任务流程");
    var t = repo.get(id); if (t == null) throw new com.x7x24.tenant.WorkspaceService.NotMember();
    if (Set.of("DELIVERED", "RELEASED", "DISCARDED").contains(t.status)) throw new IllegalArgumentException("任务已经交付或结束，不能再修订流程");
    if (t.context.planRevisions >= MAX_REVISIONS) throw new IllegalArgumentException("每个任务最多修订 " + MAX_REVISIONS + " 次流程");
    List<Plans.Adj> add = new ArrayList<>();
    for (var n : body.path("adjustments")) { var a = Json.convert(n, Plans.Adj.class); a.source = "revision"; add.add(a); }
    if (add.isEmpty()) throw new IllegalArgumentException("没有要修订的内容");
    var old = engine.eff(t); var op = old.plan;
    List<Plans.Adj> all = new ArrayList<>(op == null ? List.of() : op.applied()); all.addAll(add);
    var p = projects.require(t.projectId);
    var ctx = new Assembler.Ctx(op == null ? List.of() : op.labels == null ? List.of() : op.labels, t.context.stacks, p.cfg(), op == null || op.mandatory == null ? Map.of() : op.mandatory, t.riskLevel, p.cfg().autonomy);
    var seed = com.x7x24.pipeline.Presets.fromPlan(op);
    var r = pipes.assemble(t.productLine, all, op == null ? null : op.classification, ctx, false, "v2".equals(t.pipelineVersion), seed);
    var items = r.plan().items.subList(r.plan().items.size() - add.size(), r.plan().items.size());
    var applied = items.stream().filter(a -> a.rejected == null).toList();
    if (applied.isEmpty()) return Map.of("ok", false, "adjustments", items, "message", "修订都没有生效：" + String.join("；", items.stream().map(a -> a.summary + "：" + a.rejected).toList()));
    // 已经过去的阶段不补跑：在规划之后插入的设计类节点只提示
    List<String> warns = new ArrayList<>();
    boolean pastPlanning = !Set.of("BACKLOG", "PRD_REVIEW", "PLANNING").contains(t.status);
    for (var a : applied) if (pastPlanning && "insertStage".equals(a.op) && !"ACCEPTANCE".equals(r.eff().stage(a.stage) == null ? null : r.eff().stage(a.stage).phase())) warns.add("「" + a.summary + "」：任务已经过了规划，这个设计步骤不会补跑，下次重新规划时生效");
    pipes.assemble(t.productLine, all, op == null ? null : op.classification, ctx, true, "v2".equals(t.pipelineVersion), seed);
    String from = t.context.configSnapshot; t.context.configSnapshot = r.eff().id; t.context.planRevisions++;
    if (!repo.save(t)) throw new IllegalArgumentException("任务已发生变化，请刷新后重试");
    var relax = applied.stream().filter(a -> Boolean.TRUE.equals(a.relax)).map(a -> a.summary).toList();
    audit.task(t.id, t.currentIteration, "PLAN_REVISED", com.x7x24.web.PipelineApiActor.of(req), Map.of("from", from, "to", r.eff().id, "applied", applied.stream().map(a -> a.summary).toList(), "relaxApprovedBy", relax.isEmpty() ? "" : com.x7x24.web.PipelineApiActor.of(req), "relax", relax, "revision", t.context.planRevisions), t.id + ":revise:" + t.context.planRevisions);
    Map<String, Object> out = new LinkedHashMap<>(plan(id)); out.put("ok", true); out.put("applied", applied); out.put("revisionWarnings", warns);
    return out;
  }
}
