package com.x7x24.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.x7x24.db.Db;
import com.x7x24.engine.*;
import com.x7x24.gov.*;
import com.x7x24.integration.RuntimeSettings;
import com.x7x24.util.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 预算读写：编辑额度（全局 / 产品线 / 项目 / 任务）、触顶行为、预览影响、变更历史。持久化到数据库，保存即时生效并写审计。 */
@RestController
@RequestMapping("/api/budget")
public class BudgetApi {
  private final BudgetService budget; private final ConfigStore cfg; private final LineRepo lines; private final ProjectRepo projects; private final RuntimeSettings settings; private final AuditLog audit; private final Db db; private final Clk clk;
  public BudgetApi(BudgetService budget, ConfigStore cfg, LineRepo lines, ProjectRepo projects, RuntimeSettings settings, AuditLog audit, Db db, Clk clk) {
    this.budget = budget; this.cfg = cfg; this.lines = lines; this.projects = projects; this.settings = settings; this.audit = audit; this.db = db; this.clk = clk;
  }
  public static final List<String> POLICIES = List.of("degrade", "pause_new", "halt");
  public record Req(String scope, String id, Map<String, Double> values, String onExceed, Double softRatio) {}

  /** 顶部一句话概括 */
  @GetMapping("/summary") public Map<String, Object> summary() {
    var g = cfg.get().budgets.global; var d = budget.globalDaily();
    double usedPct = g.dailyUsd > 0 ? d.usd() / g.dailyUsd : 0, tok = g.dailyTokens > 0 ? d.tokens() / g.dailyTokens : 0, ci = g.dailyCiMinutes > 0 ? d.ci() / g.dailyCiMinutes : 0;
    double worst = Math.max(usedPct, Math.max(tok, ci)); String level = worst >= 1 ? "hard" : worst >= cfg.get().budgets.line.softRatio ? "soft" : "ok";
    Map<String, Object> o = new LinkedHashMap<>(); o.put("day", clk.dayKey()); o.put("usedUsd", d.usd()); o.put("dailyUsd", g.dailyUsd); o.put("usedPct", U.round(usedPct, 4)); o.put("worstPct", U.round(worst, 4)); o.put("level", level);
    // 预计用完：今天至少有 3 笔记账且已过 30 分钟才估算；否则不显示
    String wsf = com.x7x24.tenant.Tenant.readFilter();
    var first = wsf == null ? db.row("SELECT MIN(occurred_at) a, COUNT(*) n FROM budget_ledger WHERE day=? AND usd>0", clk.dayKey()) : db.row("SELECT MIN(occurred_at) a, COUNT(*) n FROM budget_ledger WHERE day=? AND usd>0 AND workspace_id=?", clk.dayKey(), wsf);
    String exhaust = null; String note = "今天的数据还不足以预计用完时间";
    if (first != null && first.get("a") != null && Db.l(first.get("n")) >= 3) {
      double hours = (clk.now() - Clk.parse((String) first.get("a"))) / 3_600_000.0;
      if (hours >= 0.5 && d.usd() > 0) {
        double rate = d.usd() / hours; double left = (g.dailyUsd - d.usd()) / rate;
        if (d.usd() >= g.dailyUsd) { note = "今日金额已用完"; }
        else if (left * 3_600_000 + clk.now() < endOfDay()) { exhaust = Clk.iso((long) (clk.now() + left * 3_600_000)); note = null; }
        else note = "按当前速度，今天不会用完";
      }
    }
    o.put("projectedExhaustAt", exhaust); o.put("projectionNote", note);
    String text = "今天已用 $" + U.round(d.usd(), 2) + "，占日上限（$" + g.dailyUsd + "）的 " + Math.round(usedPct * 100) + "%" + (exhaust != null ? "，按当前速度预计 " + exhaust.substring(11, 16) + "（UTC）用完。" : note == null ? "。" : "；" + note + "。");
    o.put("text", text); o.put("policy", settings.budgetOnExceed()); return o;
  }
  private long endOfDay() { return Clk.parse(clk.dayKey() + "T00:00:00.000Z") + 24L * 3_600_000; }

  @GetMapping("/projects/{id}/tasks") public List<Map<String, Object>> topTasks(@PathVariable String id) {
    projects.require(id);
    return db.rows("SELECT id, title, status, spent_usd, spent_tokens, spent_ci, budget_usd FROM task WHERE project_id=? ORDER BY spent_usd DESC, spent_tokens DESC LIMIT 50", id);
  }
  @GetMapping("/changes") public List<Map<String, Object>> changes() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (var e : audit.list(null, "BUDGET_UPDATED", 50, 0)) { Map<String, Object> m = new LinkedHashMap<>(); m.put("at", e.get("occurred_at")); m.put("actor", e.get("actor")); m.put("payload", e.get("payload")); out.add(m); }
    return out;
  }

  // ── 校验 / 预览 / 保存
  private static final String[][] FIELDS = {{"dailyUsd", "日金额（美元）"}, {"dailyTokens", "日 token"}, {"dailyCiMinutes", "日 CI 分钟"}};
  private Governance.LineBudget lineLimits(String lineId) { return budget.limits(lines.require(lineId).cfg().budget); }
  private Governance.LineBudget projectLimits(String projectId) { return budget.limits(projects.require(projectId).cfg().budget); }
  /** 只取显式覆盖值（null 表示沿用默认，不参与「下层不超上层」的子级检查） */
  private static Double ov(ProjectCfg.BudgetLimits b, String k) { return switch (k) { case "dailyUsd" -> b.dailyUsd; case "dailyTokens" -> b.dailyTokens; default -> b.dailyCiMinutes; }; }
  private static double f(Governance.LineBudget b, String k) { return switch (k) { case "dailyUsd" -> b.dailyUsd; case "dailyTokens" -> b.dailyTokens; default -> b.dailyCiMinutes; }; }
  private static double g(Governance.GlobalBudget b, String k) { return switch (k) { case "dailyUsd" -> b.dailyUsd; case "dailyTokens" -> b.dailyTokens; default -> b.dailyCiMinutes; }; }
  private static Double v(Map<String, Double> m, String k) { return m == null ? null : m.get(k); }

  public Map<String, Object> plan(Req r, boolean apply) {
    String scope = r.scope() == null ? "" : r.scope(); List<String> errors = new ArrayList<>(), warnings = new ArrayList<>(); List<Map<String, Object>> impact = new ArrayList<>();
    Map<String, Double> val = r.values() == null ? Map.of() : r.values();
    for (var e : val.entrySet()) if (e.getValue() != null && (!Double.isFinite(e.getValue()) || e.getValue() <= 0)) errors.add(label(e.getKey()) + " 必须是大于 0 的数字");
    if (r.onExceed() != null && !POLICIES.contains(r.onExceed())) errors.add("触顶行为只能是：降级到低成本模型 / 暂停新任务 / 全部停止");
    if (r.softRatio() != null && (r.softRatio() < 0.1 || r.softRatio() > 1)) errors.add("省钱线应在 10%–100% 之间");
    if (!List.of("global", "line", "project", "task", "policy").contains(scope)) errors.add("未知的额度层级");
    Object before = null, after = null; Runnable commit = null;
    if (errors.isEmpty()) switch (scope) {
      case "global" -> {
        var cur = cfg.get().budgets.global; var next = new Governance.GlobalBudget();
        next.dailyUsd = orElse(v(val, "dailyUsd"), cur.dailyUsd); next.dailyTokens = orElse(v(val, "dailyTokens"), cur.dailyTokens); next.dailyCiMinutes = orElse(v(val, "dailyCiMinutes"), cur.dailyCiMinutes); next.monthlyUsd = orElse(v(val, "monthlyUsd"), cur.monthlyUsd); next.monthlyAlertRatio = cur.monthlyAlertRatio;
        if (next.monthlyUsd < next.dailyUsd) errors.add("月金额上限不能低于日金额上限");
        for (var l : lines.list()) for (var fl : FIELDS) { Double o = ov(l.cfg().budget, fl[0]); if (o != null && o > g(next, fl[0])) errors.add("产品线「" + l.name() + "」设置的" + fl[1] + "额度（" + o + "）高于新的全局上限，请先调低它"); }
        for (var p : projects.list()) { if ("ARCHIVED".equals(p.state())) continue; for (var fl : FIELDS) { Double o = ov(p.cfg().budget, fl[0]); if (o != null && o > g(next, fl[0])) errors.add("项目「" + p.name() + "」设置的" + fl[1] + "额度（" + o + "）高于新的全局上限"); } }
        if (next.dailyUsd < cfg.get().budgets.task.usd) errors.add("全局日金额不能低于单任务金额上限（" + cfg.get().budgets.task.usd + "）");
        var d = budget.globalDaily(); var m = budget.globalMonthly();
        impact.add(impactRow("全局今日", worst(d.usd() / next.dailyUsd, d.tokens() / next.dailyTokens, d.ci() / next.dailyCiMinutes), 1, false));
        impact.add(impactRow("全局本月金额", m.usd() / next.monthlyUsd, 1, false));
        before = Json.M.valueToTree(cur); after = Json.M.valueToTree(next); var fn = next; commit = () -> cfg.update(Json.M.createObjectNode().set("budgets", Json.M.createObjectNode().set("global", Json.M.valueToTree(fn))));
      }
      case "line" -> {
        var line = lines.require(r.id()); var gl = cfg.get().budgets.global; var cur = line.cfg().budget; var nb = new ProjectCfg.BudgetLimits();
        nb.dailyUsd = val.containsKey("dailyUsd") ? val.get("dailyUsd") : cur.dailyUsd; nb.dailyTokens = val.containsKey("dailyTokens") ? val.get("dailyTokens") : cur.dailyTokens; nb.dailyCiMinutes = val.containsKey("dailyCiMinutes") ? val.get("dailyCiMinutes") : cur.dailyCiMinutes;
        var eff = budget.limits(nb);
        for (var fl : FIELDS) { Double o = ov(nb, fl[0]); if (o != null && o > g(gl, fl[0])) errors.add(fl[1] + "不能超过全局上限（" + g(gl, fl[0]) + "）"); }
        for (var p : projects.list()) if (p.productLineId().equals(line.id()) && !"ARCHIVED".equals(p.state())) for (var fl : FIELDS) { Double o = ov(p.cfg().budget, fl[0]); if (o != null && o > f(eff, fl[0])) errors.add("项目「" + p.name() + "」设置的" + fl[1] + "额度（" + o + "）高于新的产品线额度，请先调低它"); }
        var d = budget.lineDaily(line.id()); impact.add(impactRow("产品线「" + line.name() + "」今日", worst(d.usd() / eff.dailyUsd, d.tokens() / eff.dailyTokens, d.ci() / eff.dailyCiMinutes), cfg.get().budgets.line.softRatio, true));
        before = Json.M.valueToTree(cur); after = Json.M.valueToTree(nb); var fn = nb; commit = () -> { var n = Json.M.createObjectNode(); n.set("budget", Json.M.valueToTree(fn)); lines.update(line.id(), n); };
      }
      case "project" -> {
        var p = projects.require(r.id()); var cur = p.cfg().budget; var nb = new ProjectCfg.BudgetLimits();
        nb.dailyUsd = val.containsKey("dailyUsd") ? val.get("dailyUsd") : cur.dailyUsd; nb.dailyTokens = val.containsKey("dailyTokens") ? val.get("dailyTokens") : cur.dailyTokens; nb.dailyCiMinutes = val.containsKey("dailyCiMinutes") ? val.get("dailyCiMinutes") : cur.dailyCiMinutes; nb.taskUnlimited = cur.taskUnlimited; nb.taskDegrade = cur.taskDegrade;
        var eff = budget.limits(nb); var up = lineLimits(p.productLineId());
        for (var fl : FIELDS) { Double o = ov(nb, fl[0]); if (o != null && o > f(up, fl[0])) errors.add(fl[1] + "不能超过所属产品线的额度（" + f(up, fl[0]) + "）"); }
        var d = budget.projectDaily(p.id()); impact.add(impactRow("项目「" + p.name() + "」今日", worst(d.usd() / eff.dailyUsd, d.tokens() / eff.dailyTokens, d.ci() / eff.dailyCiMinutes), cfg.get().budgets.line.softRatio, true));
        before = Json.M.valueToTree(cur); after = Json.M.valueToTree(nb); var fn = nb; commit = () -> { ObjectNode n = Json.M.createObjectNode(); n.set("config", Json.M.createObjectNode().set("budget", Json.M.valueToTree(fn))); projects.update(p.id(), n); };
      }
      case "task" -> {
        var cur = cfg.get().budgets.task; var next = Json.convert(Json.M.valueToTree(cur), Governance.TaskBudget.class);
        if (v(val, "usd") != null) next.usd = val.get("usd"); if (v(val, "tokens") != null) next.tokens = val.get("tokens"); if (v(val, "ciMinutes") != null) next.ciMinutes = val.get("ciMinutes"); if (v(val, "iterations") != null) next.iterations = val.get("iterations");
        var gl = cfg.get().budgets.global; if (next.usd > gl.dailyUsd) errors.add("单任务金额不能超过全局日金额上限（" + gl.dailyUsd + "）"); if (next.tokens > gl.dailyTokens) errors.add("单任务 token 不能超过全局日 token 上限"); if (next.ciMinutes > gl.dailyCiMinutes) errors.add("单任务 CI 分钟不能超过全局日上限");
        for (var l : lines.list()) { var lim = lineLimits(l.id()); if (next.usd > lim.dailyUsd) errors.add("单任务金额不能超过产品线「" + l.name() + "」的日金额额度（" + lim.dailyUsd + "）"); }
        Map<String, Object> row = new LinkedHashMap<>(); row.put("label", "已有任务"); row.put("text", "只影响保存之后创建的任务，已有任务保持原来的额度"); row.put("status", "ok"); impact.add(row);
        before = Json.M.valueToTree(cur); after = Json.M.valueToTree(next); var fn = next; commit = () -> cfg.update(Json.M.createObjectNode().set("budgets", Json.M.createObjectNode().set("task", Json.M.valueToTree(fn))));
      }
      default -> { }
    }
    if (errors.isEmpty() && r.softRatio() != null) { before = before; var cl = cfg.get().budgets.line.softRatio; warnings.add("降级阈值由 " + Math.round(cl * 100) + "% 改为 " + Math.round(r.softRatio() * 100) + "%，所有产品线与项目共用"); }
    Map<String, Object> out = new LinkedHashMap<>(); out.put("ok", errors.isEmpty()); out.put("errors", errors); out.put("warnings", warnings); out.put("impact", impact); out.put("before", before); out.put("after", after);
    if (r.onExceed() != null && errors.isEmpty()) out.put("policyChange", Map.of("from", settings.budgetOnExceed(), "to", r.onExceed()));
    if (apply && errors.isEmpty()) {
      if (commit != null) commit.run();
      if (r.softRatio() != null) cfg.update(Json.M.createObjectNode().set("budgets", Json.M.createObjectNode().set("line", Json.M.createObjectNode().put("softRatio", r.softRatio()))));
      String oldPolicy = settings.budgetOnExceed(); if (r.onExceed() != null) settings.setBudgetOnExceed(r.onExceed());
      Map<String, Object> payload = new LinkedHashMap<>(); payload.put("scope", scope); payload.put("id", r.id()); payload.put("before", before); payload.put("after", after);
      if (r.onExceed() != null) payload.put("policy", Map.of("from", oldPolicy, "to", r.onExceed())); if (r.softRatio() != null) payload.put("softRatio", r.softRatio());
      payload.put("by", "操作员（当前版本无登录，不区分具体用户）"); audit.sys("BUDGET_UPDATED", "operator", payload, null);
      budget.enforceGlobal(); out.put("saved", true);
    }
    return out;
  }
  private static double orElse(Double a, double b) { return a == null ? b : a; }
  private static double worst(double... r) { double m = 0; for (double x : r) m = Math.max(m, Double.isFinite(x) ? x : 0); return m; }
  private String label(String k) { return switch (k) { case "dailyUsd" -> "日金额"; case "monthlyUsd" -> "月金额"; case "dailyTokens" -> "日 token"; case "dailyCiMinutes" -> "日 CI 分钟"; case "usd" -> "单任务金额"; case "tokens" -> "单任务 token"; case "ciMinutes" -> "单任务 CI 分钟"; case "iterations" -> "单任务迭代轮数"; default -> k; }; }
  private Map<String, Object> impactRow(String label, double ratio, double soft, boolean hasSoft) { return impactRow(label, ratio, soft, hasSoft, true); }
  private Map<String, Object> impactRow(String label, double ratio, double soft, boolean hasSoft, boolean x) {
    String st = ratio >= 1 ? "hard" : hasSoft && ratio >= soft ? "soft" : "ok"; int pct = (int) Math.round(ratio * 100);
    Map<String, Object> m = new LinkedHashMap<>(); m.put("label", label); m.put("usedPct", pct); m.put("status", st);
    m.put("text", "当前已用 " + pct + "%，保存后" + (st.equals("hard") ? "将触顶：新的步骤会停止执行" : st.equals("soft") ? "将进入降级：改用低成本模型" : "保持正常")); return m;
  }
  @PostMapping("/preview") public Map<String, Object> preview(@RequestBody Req r) { return plan(r, false); }
  public record SwitchReq(String scope, String id, Boolean unlimited, Boolean degrade, Boolean clearUnlimited, Boolean clearDegrade) {}
  /**
   * 任务预算开关的默认值：scope=governance（全工作区默认）| project（某项目，null = 跟随治理默认）。
   * 对没有单独设置的任务立即生效。只有所有者 / 管理员能改，记入审计（BUDGET_UPDATED）。
   */
  @PutMapping("/task-switches") public Map<String, Object> taskSwitches(@RequestBody SwitchReq r, jakarta.servlet.http.HttpServletRequest req) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(req, "修改预算");
    Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>(); String scope = r.scope() == null ? "" : r.scope();
    if (scope.equals("governance")) {
      var cur = cfg.get().budgets.task; before.put("unlimited", cur.unlimited); before.put("degrade", cur.degrade);
      ObjectNode t = Json.M.createObjectNode(); if (r.unlimited() != null) t.put("unlimited", r.unlimited()); if (r.degrade() != null) t.put("degrade", r.degrade());
      cfg.update(Json.M.createObjectNode().set("budgets", Json.M.createObjectNode().set("task", t)));
      var nx = cfg.get().budgets.task; after.put("unlimited", nx.unlimited); after.put("degrade", nx.degrade);
    } else if (scope.equals("project")) {
      var p = projects.require(r.id()); var cur = p.cfg().budget == null ? new ProjectCfg.BudgetLimits() : p.cfg().budget;
      before.put("unlimited", cur.taskUnlimited); before.put("degrade", cur.taskDegrade);
      var nb = Json.convert(Json.M.valueToTree(cur), ProjectCfg.BudgetLimits.class);
      if (Boolean.TRUE.equals(r.clearUnlimited())) nb.taskUnlimited = null; else if (r.unlimited() != null) nb.taskUnlimited = r.unlimited();
      if (Boolean.TRUE.equals(r.clearDegrade())) nb.taskDegrade = null; else if (r.degrade() != null) nb.taskDegrade = r.degrade();
      ObjectNode n = Json.M.createObjectNode(); n.set("config", Json.M.createObjectNode().set("budget", Json.M.valueToTree(nb))); projects.update(p.id(), n);
      after.put("unlimited", nb.taskUnlimited); after.put("degrade", nb.taskDegrade);
    } else throw new IllegalArgumentException("scope 只能是 governance 或 project");
    Map<String, Object> payload = new LinkedHashMap<>(); payload.put("scope", "task_switches"); payload.put("target", scope); payload.put("id", r.id()); payload.put("before", before); payload.put("after", after);
    audit.sys("BUDGET_UPDATED", com.x7x24.web.PipelineApi.actor(req), payload, null);
    Map<String, Object> o = new LinkedHashMap<>(); o.put("ok", true); o.put("scope", scope); o.put("after", after); return o;
  }
  @PutMapping("/limits") public Map<String, Object> save(@RequestBody Req r, jakarta.servlet.http.HttpServletRequest req) { com.x7x24.tenant.WorkspaceFilter.requireManager(req, "修改预算"); 
    var res = plan(r, true); if (!Boolean.TRUE.equals(res.get("ok"))) throw new IllegalArgumentException(String.join("；", (List<String>) res.get("errors"))); return res;
  }
}
