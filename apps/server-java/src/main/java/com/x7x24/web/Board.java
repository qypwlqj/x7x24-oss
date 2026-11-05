package com.x7x24.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.core.Status;
import com.x7x24.db.Db;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;

import java.util.*;

/** 看板指标（口径见 docs/decisions.md §5）：resident=区间内创建任务当前驻留；flow=曾进入该节点的不同任务数。数据全部来自真实运行。 */
@Component
public class Board {
  private final Db db; private final Clk clk;
  public Board(Db db, Clk clk) { this.db = db; this.clk = clk; }
  private com.x7x24.pipeline.PipelineService pipes;
  @org.springframework.beans.factory.annotation.Autowired(required = false) void setPipes(com.x7x24.pipeline.PipelineService p) { this.pipes = p; }

  public Map<String, String> defaultRange() {
    String to = clk.dayKey(); var first = db.row("SELECT MIN(created_at) AS m FROM task WHERE 1=1" + com.x7x24.tenant.Tenant.and("workspace_id"), com.x7x24.tenant.Tenant.args()); String fromDef = clk.dayKey(clk.now() - 29 * U.DAY);
    String f = first != null && first.get("m") != null ? ((String) first.get("m")).substring(0, 10) : fromDef;
    return Map.of("from", f.compareTo(fromDef) > 0 ? f : fromDef, "to", to);
  }
  public static String lo(String from) { return from + "T00:00:00.000Z"; }
  public static String hi(String to) { return Clk.iso(Clk.parse(to + "T00:00:00.000Z") + U.DAY); }

  private long one(String sql, List<Object> p, String line) { return db.count(sql, p.toArray()); }

  public Map<String, Object> board(String line, String from, String to) { return board(line, null, from, to); }
  public Map<String, Object> board(String line, String project, String from, String to) {
    var rng = from != null && to != null ? Map.of("from", from, "to", to) : defaultRange();
    String w0 = "t.created_at >= ? AND t.created_at < ? AND t.deleted_at IS NULL" + (line != null && !line.isBlank() ? " AND t.product_line = ?" : "") + (project != null && !project.isBlank() ? " AND t.project_id = ?" : "");
    List<Object> p = new ArrayList<>(List.of(lo(rng.get("from")), hi(rng.get("to")))); if (line != null && !line.isBlank()) p.add(line); else line = null;
    if (project != null && !project.isBlank()) p.add(project);
    String ws = com.x7x24.tenant.Tenant.current(); if (ws != null) { w0 += " AND t.workspace_id = ?"; p.add(ws); }
    final String w = w0;
    Object[] a = p.toArray();
    Map<String, Long> statuses = new LinkedHashMap<>(), resident = new LinkedHashMap<>(), flow = new LinkedHashMap<>(); for (var s : Status.values()) { statuses.put(s.name(), 0L); resident.put(s.name(), 0L); flow.put(s.name(), 0L); }
    for (var r : db.rows("SELECT t.status AS s, COUNT(*) AS c FROM task t WHERE " + w + " GROUP BY t.status", a)) statuses.put((String) r.get("s"), Db.l(r.get("c")));
    for (var r : db.rows("SELECT t.current_stage AS s, COUNT(*) AS c FROM task t WHERE " + w + " GROUP BY t.current_stage", a)) resident.put((String) r.get("s"), Db.l(r.get("c")));
    for (var r : db.rows("SELECT e.to_status AS s, COUNT(DISTINCT e.task_id) AS c FROM task_event e JOIN task t ON t.id=e.task_id WHERE e.event_type='STATUS_CHANGED' AND e.to_status IS NOT NULL AND " + w + " GROUP BY e.to_status", a)) flow.put((String) r.get("s"), Db.l(r.get("c")));
    long signals = db.count("SELECT COUNT(*) FROM signal s JOIN task t ON t.id=s.task_id WHERE " + w, a);
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (var n : Status.BOARD_NODES) {
      // 驻留 = 当前状态（异常 / 降级任务只计入「异常巡查」，不再按 current_stage 重复计入原阶段）
      long res = n.id().equals("signal") ? signals : n.statuses().stream().mapToLong(s -> statuses.get(s.name())).sum();
      long fl = n.id().equals("signal") ? signals : n.statuses().stream().mapToLong(s -> flow.get(s.name())).sum();
      Map<String, Object> m = new LinkedHashMap<>(); m.put("id", n.id()); m.put("label", n.label()); m.put("resident", res); m.put("flow", fl); nodes.add(m);
    }
    long watch = Status.WATCH.stream().mapToLong(s -> statuses.get(s.name())).sum(), prism = Status.PRISM.stream().mapToLong(s -> statuses.get(s.name())).sum();
    long delivered = resident.get("DELIVERED"), released = resident.get("RELEASED"), discarded = resident.get("DISCARDED");
    Map<String, Object> totals = new LinkedHashMap<>(); totals.put("watch", watch); totals.put("prism", prism); totals.put("delivered", delivered); totals.put("released", released); totals.put("discarded", discarded); totals.put("all", watch + prism + delivered + released);
    Map<String, Long> edges = new LinkedHashMap<>();
    for (var r : db.rows("SELECT e.edge AS g, COUNT(*) AS c FROM task_event e JOIN task t ON t.id=e.task_id WHERE e.edge IS NOT NULL AND " + w + " GROUP BY e.edge", a)) edges.put((String) r.get("g"), Db.l(r.get("c")));
    long evCount = db.count("SELECT COUNT(*) FROM evidence v JOIN task t ON t.id=v.task_id WHERE " + w, a);
    long accepted = db.count("SELECT COUNT(*) FROM task t WHERE t.accepted_once=1 AND " + w, a);
    long rechecked = db.count("SELECT COUNT(DISTINCT e.task_id) FROM task_event e JOIN task t ON t.id=e.task_id WHERE e.event_type='ROLLBACK' AND t.accepted_once=1 AND " + w, a);

    List<String> days = new ArrayList<>(); long end = Clk.parse(rng.get("to") + "T00:00:00.000Z"); for (int i = 29; i >= 0; i--) days.add(clk.dayKey(end - i * U.DAY));
    Map<String, Long> created = new HashMap<>(), delv = new HashMap<>();
    for (var r : db.rows("SELECT substr(t.created_at,1,10) AS d, COUNT(*) AS c FROM task t WHERE " + w + " GROUP BY substr(t.created_at,1,10)", a)) created.put((String) r.get("d"), Db.l(r.get("c")));
    for (var r : db.rows("SELECT substr(e.occurred_at,1,10) AS d, COUNT(DISTINCT e.task_id) AS c FROM task_event e JOIN task t ON t.id=e.task_id WHERE e.edge='release' AND " + w + " GROUP BY substr(e.occurred_at,1,10)", a)) delv.put((String) r.get("d"), Db.l(r.get("c")));
    List<Map<String, Object>> trend = new ArrayList<>(); for (var d : days) trend.add(Map.of("date", d, "created", created.getOrDefault(d, 0L), "delivered", delv.getOrDefault(d, 0L)));

    var vs = db.rows("SELECT v.gate AS g, v.decision AS d, COUNT(*) AS c FROM verdict v JOIN task t ON t.id=v.task_id WHERE " + w + " GROUP BY v.gate, v.decision", a);
    java.util.function.BiFunction<String, String, Long> vc = (g, d) -> vs.stream().filter(r -> r.get("g").equals(g) && (d == null || r.get("d").equals(d))).mapToLong(r -> Db.l(r.get("c"))).sum();
    int committee = 0, dis = 0;
    for (var r : db.rows("SELECT v.votes AS vt FROM verdict v JOIN task t ON t.id=v.task_id WHERE v.gate IN ('prd_review','acceptance_l2') AND " + w, a)) {
      JsonNode m = Json.tree((String) r.get("vt")).path("meta"); if (m.has("disagreement")) { committee++; if (m.path("disagreement").asBoolean()) dis++; }
    }
    java.util.function.Function<String, Long> ev = type -> db.count("SELECT COUNT(*) FROM task_event e JOIN task t ON t.id=e.task_id WHERE e.event_type='" + type + "' AND " + w, a);
    long anTotal = 0, anRes = 0; for (var r : db.rows("SELECT a.status AS s, COUNT(*) AS c FROM anomaly a JOIN task t ON t.id=a.task_id WHERE " + w + " GROUP BY a.status", a)) { anTotal += Db.l(r.get("c")); if ("resolved".equals(r.get("s"))) anRes += Db.l(r.get("c")); }
    var cost = db.row("SELECT COALESCE(SUM(t.spent_usd),0) AS u, COUNT(*) AS c FROM task t WHERE " + w, a);
    Map<String, Object> health = new LinkedHashMap<>();
    health.put("prdPassRate", vc.apply("prd_review", null) > 0 ? vc.apply("prd_review", "pass") / (double) vc.apply("prd_review", null) : 0);
    health.put("acceptancePassRate", vc.apply("acceptance_l2", null) > 0 ? vc.apply("acceptance_l2", "pass") / (double) vc.apply("acceptance_l2", null) : 0);
    health.put("committeeDisagreementRate", committee > 0 ? dis / (double) committee : 0);
    health.put("canaryBlocks", vc.apply("canary", "rollback")); health.put("rollbacks", ev.apply("ROLLBACK")); health.put("healSuccessRate", anTotal > 0 ? anRes / (double) anTotal : 1);
    health.put("degradeEvents", ev.apply("DEGRADED")); health.put("autoDiscards", edges.getOrDefault("auto_discard", 0L));
    health.put("avgCostUsd", Db.l(cost.get("c")) > 0 ? Db.d(cost.get("u")) / Db.l(cost.get("c")) : 0);
    String wsf = com.x7x24.tenant.Tenant.readFilter(); String wsq = wsf == null ? "" : " AND workspace_id=?"; Object[] wsa = wsf == null ? new Object[0] : new Object[]{wsf};
    health.put("budgetHits", db.count("SELECT COUNT(*) FROM task_event WHERE event_type='BUDGET_HIT'" + wsq, wsa));
    health.put("killSwitchEvents", db.count("SELECT COUNT(*) FROM task_event WHERE event_type='KILL_SWITCH'" + wsq, wsa));
    health.put("actionsDenied", db.count("SELECT COUNT(*) FROM task_event WHERE event_type='ACTION_DENIED'" + wsq, wsa));
    Map<String, Long> reasons = new LinkedHashMap<>();
    for (var r : db.rows("SELECT t.discard_reason AS r, COUNT(*) AS c FROM task t WHERE t.status='DISCARDED' AND " + w + " GROUP BY t.discard_reason", a)) reasons.put(r.get("r") == null ? "unknown" : (String) r.get("r"), Db.l(r.get("c")));

    Map<String, Object> o = new LinkedHashMap<>();
    o.put("projectId", project); o.put("range", rng); o.put("productLine", line); o.put("now", clk.iso()); o.put("nodes", nodes); o.put("totals", totals); o.put("edges", edges);
    // 按业务线看板：标出该业务线流程模板关闭的节点（显示为「跳过」）
    if (line != null && pipes != null) { try { var e = pipes.resolve(line); o.put("pipeline", Map.of("template", e.template, "snapshot", e.id, "skipped", e.stages.values().stream().filter(x -> !x.on()).map(x -> x.type).toList())); } catch (Exception ignore) { } }
    o.put("loops", edges.getOrDefault("qa_loop", 0L)); o.put("iterations", flow.get("RELEASED")); o.put("closureRate", (watch + prism + delivered + released) > 0 ? released / (double) (watch + prism + delivered + released) : 0);
    o.put("evidence", Map.of("count", evCount, "productionDeliveries", 0, "repositoryReleases", released, "recheckRate", accepted > 0 ? rechecked / (double) accepted : 0));
    o.put("trend", trend); o.put("health", health); o.put("discardReasons", reasons);
    return o;
  }
}
