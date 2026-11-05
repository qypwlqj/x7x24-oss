package com.x7x24.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.db.Db;
import com.x7x24.pipeline.Nodes;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工作区汇总的流程节点看板（可组合流程 P4，设计 §10.2 方案 A）：感知 / 交付固定列，生产段按节点族（设计 / 编码 / 专项检查）堆叠小格，
 * 每格按栈分段；流经占比 &lt; 5% 且当前为 0 的节点折叠进「更多」。按 task.current_node 计数，v1 任务按状态映射到同一套节点。
 */
@Component
public class NodeBoard {
  private final Db db; private final Clk clk;
  public NodeBoard(Db db, Clk clk) { this.db = db; this.clk = clk; }
  static final List<String> SENSING = List.of("SIGNAL_SCAN", "INTAKE", "PRD_REVIEW", "PLANNING");
  static final List<String> DELIVERY = List.of("QA", "CODE_REVIEW", "SEC_AUDIT", "ACCEPTANCE", "RELEASE");
  static final Map<String, String> FAMILY_LABEL = Map.of("design", "设计", "build", "编码", "check", "专项检查");
  public static final double RARE_PCT = 5;
  record Cur(String id, String node, String status, String title, String projectId, List<String> stacks, double dwellH, boolean blocked, String blockReason, int attempts) {}

  /** 筛选范围内的任务行（工作区 + 业务线 + 项目） */
  private List<Map<String, Object>> tasks(String line, String project, String extra, Object... extraArgs) {
    StringBuilder w = new StringBuilder("t.deleted_at IS NULL"); List<Object> a = new ArrayList<>();
    String ws = Tenant.current(); if (ws != null) { w.append(" AND t.workspace_id=?"); a.add(ws); }
    if (line != null && !line.isBlank()) { w.append(" AND t.product_line=?"); a.add(line); }
    if (project != null && !project.isBlank()) { w.append(" AND t.project_id=?"); a.add(project); }
    if (extra != null) { w.append(" AND ").append(extra); a.addAll(Arrays.asList(extraArgs)); }
    return db.rows("SELECT t.id, t.title, t.status, t.current_node, t.current_stage, t.project_id, t.context, t.status_changed_at, t.execution_state, t.paused, t.step_attempts, t.created_at FROM task t WHERE " + w, a.toArray());
  }
  /** 旧任务（V16 之前）没有 current_node：按状态映射 */
  static String nodeOf(Map<String, Object> r) {
    String n = Db.str(r.get("current_node")); if (n != null) return n;
    String st = Db.str(r.get("status")), cs = Db.str(r.get("current_stage"));
    return switch (st == null ? "" : st) {
      case "BACKLOG" -> "INTAKE"; case "DESIGN" -> cs != null && !"DESIGN".equals(cs) ? cs : "UX_DESIGN";
      case "ACCEPTANCE" -> cs != null && !"ACCEPTANCE".equals(cs) && com.x7x24.pipeline.Catalog.stageType(cs) == null ? cs : "ACCEPTANCE";
      case "DELIVERED", "RELEASED" -> "RELEASE"; default -> st;
    };
  }
  private List<Cur> current(String line, String project, String stack) {
    long now = clk.now(); List<Cur> out = new ArrayList<>();
    for (var r : tasks(line, project, "(t.status IN ('BACKLOG','PRD_REVIEW','PLANNING','SPEC','DESIGN','DEV','QA','ACCEPTANCE','DELIVERED') OR t.execution_state='WAITING_CONFIGURATION')")) {
      JsonNode ctx = Json.tree(Db.str(r.get("context")));
      List<String> st = new ArrayList<>(); for (var s : ctx.path("stacks")) st.add(s.asText());
      if (stack != null && !stack.isBlank() && !st.contains(stack)) continue;
      String node = nodeOf(r);
      double dwell = r.get("status_changed_at") == null ? 0 : Math.max(0, (now - Clk.parse(Db.str(r.get("status_changed_at")))) / 3_600_000.0);
      boolean blocked = Set.of("RETRYING","BLOCKED","WAITING_CONFIGURATION","FAILED","WAITING_INPUT","RECOVERY_REQUIRED","WAITING_APPROVAL").contains(String.valueOf(r.get("execution_state"))) || Db.l(r.get("paused")) != 0;
      var fails = ctx.path("failures"); String why = fails.size() > 0 ? fails.get(fails.size() - 1).path("detail").asText(null) : null;
      int attempts = ctx.path("nodeLoops").path(node).asInt(0) + (int) Db.l(r.get("step_attempts"));
      out.add(new Cur(Db.str(r.get("id")), node, Db.str(r.get("status")), Db.str(r.get("title")), Db.str(r.get("project_id")), st, dwell, blocked, blocked || attempts > 0 ? why : null, attempts));
    }
    return out;
  }
  /** 窗口内流经每个节点的任务数（节点执行记录按节点去重；附加检查按 QA 的检查结果） */
  private Map<String, Set<String>> through(String line, String project, String stack, String since) {
    Map<String, Set<String>> m = new HashMap<>();
    StringBuilder w = new StringBuilder("t.deleted_at IS NULL AND x.started_at >= ?"); List<Object> a = new ArrayList<>(List.of(since));
    String ws = Tenant.current(); if (ws != null) { w.append(" AND t.workspace_id=?"); a.add(ws); }
    if (line != null && !line.isBlank()) { w.append(" AND t.product_line=?"); a.add(line); }
    if (project != null && !project.isBlank()) { w.append(" AND t.project_id=?"); a.add(project); }
    Set<String> inStack = stack == null || stack.isBlank() ? null : new HashSet<>();
    if (inStack != null) for (var r : tasks(line, project, null)) { for (var s : Json.tree(Db.str(r.get("context"))).path("stacks")) if (stack.equals(s.asText())) inStack.add(Db.str(r.get("id"))); }
    for (var r : db.rows("SELECT x.task_id, x.stage, x.input FROM stage_execution x JOIN task t ON t.id=x.task_id WHERE x.state<>'SKIPPED' AND " + w, a.toArray())) {
      String tid = Db.str(r.get("task_id")); if (inStack != null && !inStack.contains(tid)) continue;
      String node = Json.tree(Db.str(r.get("input"))).path("node").asText(null);
      if (node == null) { String st = Db.str(r.get("stage")); node = "BACKLOG".equals(st) ? "INTAKE" : "DESIGN".equals(st) ? "UX_DESIGN" : st; }
      m.computeIfAbsent(node, k -> new HashSet<>()).add(tid);
    }
    String w2 = w.toString().replace("x.started_at", "x.created_at");
    for (var r : db.rows("SELECT x.task_id, x.content FROM evidence x JOIN task t ON t.id=x.task_id WHERE x.kind='stage_checks' AND " + w2, a.toArray())) {
      String tid = Db.str(r.get("task_id")); if (inStack != null && !inStack.contains(tid)) continue;
      for (var res : Json.tree(Db.str(r.get("content"))).path("results")) {
        String c = res.path("check").asText(); var d = Nodes.NODES.values().stream().filter(x -> c.equals(x.check)).findFirst().orElse(null);
        m.computeIfAbsent(d != null ? d.id : "CHECK:" + c, k -> new HashSet<>()).add(tid);
      }
    }
    return m;
  }

  public Map<String, Object> board(String line, String project, String stack, int days) {
    String since = Clk.iso(clk.now() - days * U.DAY);
    var cur = current(line, project, stack); var thr = through(line, project, stack, since);
    Set<String> window = new HashSet<>(); thr.values().forEach(window::addAll);
    for (var r : tasks(line, project, "t.created_at >= ?", since)) window.add(Db.str(r.get("id")));
    int total = window.size();
    Map<String, List<Cur>> byNode = new HashMap<>(); for (var c : cur) byNode.computeIfAbsent(c.node(), k -> new ArrayList<>()).add(c);
    java.util.function.BiFunction<String, String, Map<String, Object>> cell = (id, label) -> {
      var cs = byNode.getOrDefault(id, List.of()); var d = Nodes.def(id);
      Map<String, Object> m = new LinkedHashMap<>(); m.put("node", id); m.put("label", label != null ? label : d != null ? d.label : id);
      m.put("current", cs.size()); m.put("blocked", cs.stream().filter(Cur::blocked).count());
      m.put("avgDwellH", cs.isEmpty() ? 0 : U.round(cs.stream().mapToDouble(Cur::dwellH).average().orElse(0), 1));
      int th = thr.getOrDefault(id, Set.of()).size(); m.put("through", th); m.put("throughPct", total == 0 ? 0 : (int) Math.round(100.0 * th / total));
      Map<String, Integer> bs = new TreeMap<>(); for (var c : cs) { if (c.stacks().isEmpty()) bs.merge("other", 1, Integer::sum); else for (var s : c.stacks()) bs.merge(s, 1, Integer::sum); }
      m.put("byStack", bs);
      m.put("rare", cs.isEmpty() && (total == 0 ? true : 100.0 * th / total < RARE_PCT) && !(d != null && !d.optional));
      return m;
    };
    Map<String, Object> modules = new LinkedHashMap<>();
    List<Map<String, Object>> sens = new ArrayList<>();
    for (String id : SENSING) {
      var m = cell.apply(id, null);
      if (id.equals("SIGNAL_SCAN")) {
        StringBuilder w = new StringBuilder("c.state='CANDIDATE'"); List<Object> a = new ArrayList<>();
        String ws = Tenant.current(); if (ws != null) { w.append(" AND p.workspace_id=?"); a.add(ws); }
        if (line != null && !line.isBlank()) { w.append(" AND p.product_line_id=?"); a.add(line); }
        if (project != null && !project.isBlank()) { w.append(" AND c.project_id=?"); a.add(project); }
        m.put("current", db.count("SELECT COUNT(*) FROM discovery_candidate c JOIN project p ON p.id=c.project_id WHERE " + w, a.toArray())); m.put("rare", false); m.put("candidates", true);
      }
      sens.add(m);
    }
    modules.put("sensing", sens);
    // 生产段：节点族 → 目录里已有执行器的节点 + 实际出现过的自定义节点
    Set<String> known = new HashSet<>(SENSING); known.addAll(DELIVERY);
    Map<String, List<String>> fam = new LinkedHashMap<>(); for (String f : List.of("design", "build", "check")) fam.put(f, new ArrayList<>());
    Set<String> delivery = new LinkedHashSet<>(DELIVERY);
    for (var d : Nodes.NODES.values()) if (d.available && fam.containsKey(d.family) && !known.contains(d.id)) fam.get(d.family).add(d.id);
    Set<String> seen = new LinkedHashSet<>(byNode.keySet()); seen.addAll(thr.keySet());
    for (String id : seen) {
      if (known.contains(id) || fam.values().stream().anyMatch(l -> l.contains(id)) || Nodes.def(id) != null) continue;
      if (id.startsWith("CHECK:")) fam.get("check").add(id);
      else if (isReview(id)) delivery.add(id); else fam.get("design").add(id); // 自定义节点：评审类放交付段，其余放设计族
    }
    List<Map<String, Object>> prod = new ArrayList<>();
    for (var en : fam.entrySet()) {
      List<Map<String, Object>> ns = new ArrayList<>(); for (String id : en.getValue()) ns.add(cell.apply(id, id.startsWith("CHECK:") ? id.substring(6) : labelOf(id)));
      prod.add(Map.of("family", en.getKey(), "label", FAMILY_LABEL.get(en.getKey()), "nodes", ns, "rareCount", ns.stream().filter(x -> Boolean.TRUE.equals(x.get("rare"))).count()));
    }
    modules.put("production", prod);
    List<Map<String, Object>> del = new ArrayList<>(); for (String id : delivery) del.add(cell.apply(id, labelOf(id))); modules.put("delivery", del);
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("modules", modules);
    var att = tasks(line, project, "(t.status IN ('ANOMALY','DEGRADED') OR t.execution_state IN ('BLOCKED','WAITING_CONFIGURATION','FAILED','WAITING_INPUT','RECOVERY_REQUIRED'))");
    o.put("attention", Map.of("anomaly", att.stream().filter(r -> !"DEGRADED".equals(r.get("status"))).count(), "degraded", att.stream().filter(r -> "DEGRADED".equals(r.get("status"))).count()));
    o.put("window", days + "d"); o.put("windowTasks", total); o.put("rarePct", RARE_PCT); o.put("now", clk.iso());
    return o;
  }
  /** 停在某个节点的任务（停留时长从长到短） */
  public Map<String, Object> node(String nodeId, String line, String project, String stack) {
    var cs = current(line, project, stack).stream().filter(c -> c.node().equals(nodeId)).sorted(Comparator.comparingDouble(Cur::dwellH).reversed()).toList();
    Map<String, String> names = new HashMap<>(); for (var r : db.rows("SELECT id, name FROM project" + (Tenant.current() == null ? "" : " WHERE workspace_id=?"), Tenant.current() == null ? new Object[0] : new Object[]{Tenant.current()})) names.put(Db.str(r.get("id")), Db.str(r.get("name")));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var c : cs) { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", c.id()); m.put("title", c.title()); m.put("status", c.status()); m.put("projectId", c.projectId()); m.put("project", names.getOrDefault(c.projectId(), c.projectId())); m.put("stacks", c.stacks()); m.put("dwellH", U.round(c.dwellH(), 1)); m.put("attempts", c.attempts()); m.put("blocked", c.blocked()); m.put("reason", c.blockReason()); rows.add(m); }
    return Map.of("node", nodeId, "label", labelOf(nodeId), "tasks", rows);
  }
  private Map<String, Object> wsNode(String id) {
    String ws = Tenant.current();
    return ws == null ? db.row("SELECT body FROM pipeline_node WHERE id=? ORDER BY version DESC", id) : db.row("SELECT body FROM pipeline_node WHERE id=? AND workspace_id=? ORDER BY version DESC", id, ws);
  }
  private String labelOf(String id) {
    var d = Nodes.def(id); if (d != null) return d.label;
    var r = wsNode(id); if (r != null) return Json.tree(Db.str(r.get("body"))).path("label").asText(id);
    return com.x7x24.pipeline.Catalog.stageLabel(id);
  }
  private boolean isReview(String id) {
    var r = wsNode(id); if (r != null) return "review".equals(Json.tree(Db.str(r.get("body"))).path("executor").asText());
    return db.count("SELECT COUNT(*) FROM task t WHERE t.current_node=? AND t.status='ACCEPTANCE'", id) > 0 || id.endsWith("_AUDIT") || id.endsWith("_REVIEW");
  }
}
