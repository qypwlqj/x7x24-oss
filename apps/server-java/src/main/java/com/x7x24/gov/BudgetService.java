package com.x7x24.gov;

import com.x7x24.db.Db;
import com.x7x24.engine.LineRepo;
import com.x7x24.engine.ProjectRepo;
import com.x7x24.engine.ProjectCfg;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;

import java.util.*;

/** 三层预算（任务 / 产品线日 / 全局日+月）：金额 + token + CI 分钟三维度；soft 降级、hard 停。 */
@Component
public class BudgetService {
  public record Usage(long tokens, double usd, double ci) {}
  private final Db db; private final Clk clk; private final ConfigStore cfg; private final ProjectRepo projects; private final LineRepo lines; private final AuditLog audit; private final KillSwitch kill; private final com.x7x24.integration.RuntimeSettings settings;
  public BudgetService(Db db, Clk clk, ConfigStore cfg, AuditLog audit, KillSwitch kill, ProjectRepo projects, LineRepo lines, com.x7x24.integration.RuntimeSettings settings) { this.settings = settings; this.db = db; this.clk = clk; this.cfg = cfg; this.audit = audit; this.kill = kill; this.projects = projects; this.lines = lines; }

  /** 记账（幂等：同 key 只记一次）。返回是否新记录。 */
  public synchronized boolean charge(String key, String taskId, String line, String agent, String model, long tokens, double usd, double ci) {
    String projectId = taskId == null ? null : Db.str(db.row("SELECT project_id FROM task WHERE id=?", taskId).get("project_id"));
    return chargeProject(key, taskId, line, projectId, agent, model, tokens, usd, ci);
  }
  public synchronized boolean chargeProject(String key, String taskId, String line, String projectId, String agent, String model, long tokens, double usd, double ci) {
    if (key != null && db.count("SELECT COUNT(*) FROM budget_ledger WHERE ledger_key=?", key) > 0) return false;
    String ws = projectId == null ? null : Db.str(Optional.ofNullable(db.row("SELECT workspace_id FROM project WHERE id=?", projectId)).map(r -> r.get("workspace_id")).orElse(null));
    if (ws == null) ws = com.x7x24.tenant.Tenant.writeId();
    db.update("INSERT INTO budget_ledger (task_id,product_line,project_id,agent,model,tokens,usd,ci_minutes,ledger_key,day,occurred_at,workspace_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", taskId, line, projectId, agent, model, tokens, usd, ci, key, clk.dayKey(), clk.iso(), ws);
    db.update("UPDATE task SET spent_tokens=spent_tokens+?, spent_usd=spent_usd+?, spent_ci=spent_ci+?, spent_iterations=spent_iterations+? WHERE id=?", tokens, usd, ci, "dev".equals(agent) ? 1 : 0, taskId);
    return true;
  }

  private Usage sum(String where, Object... p) {
    var r = db.row("SELECT COALESCE(SUM(tokens),0) t, COALESCE(SUM(usd),0) u, COALESCE(SUM(ci_minutes),0) c FROM budget_ledger WHERE " + where, p);
    return new Usage(Db.l(r.get("t")), U.round(Db.d(r.get("u")), 4), U.round(Db.d(r.get("c")), 2));
  }
  public Usage lineDaily(String line) { return sum("product_line=? AND day=?", line, clk.dayKey()); }
  /** 「全局」= 当前工作区的总量（工作区上下文）；系统 / 平台上下文 = 全平台 */
  public Usage globalDaily() { String ws = com.x7x24.tenant.Tenant.readFilter(); return ws == null ? sum("day=?", clk.dayKey()) : sum("day=? AND workspace_id=?", clk.dayKey(), ws); }
  public Usage globalMonthly() { String ws = com.x7x24.tenant.Tenant.readFilter(); return ws == null ? sum("day LIKE ?", clk.monthKey() + "%") : sum("day LIKE ? AND workspace_id=?", clk.monthKey() + "%", ws); }

  /** 开发轮次上限随拆段数放宽：每多一段多给 10 轮（每段的开发 / 缩减重试都算一轮），否则多段任务在金额 / Token 还很充足时就会因轮次触顶 */
  public static int iterLimit(com.x7x24.engine.Task t) {
    int parts = t.plan == null ? 1 : Math.max(1, t.plan.path("subtasks").size());
    return t.budgetIterations + 10 * (parts - 1);
  }
  /** 单任务预算各维度用量（金额 / Token / CI 分钟 / 开发轮次），worst 为最先触顶的维度 */
  public Map<String, Object> taskUsage(com.x7x24.engine.Task t) {
    List<Map<String, Object>> dims = new ArrayList<>();
    java.util.function.BiConsumer<Object[], double[]> add = (k, v) -> { if (v[1] <= 0) return; Map<String, Object> m = new LinkedHashMap<>(); m.put("key", k[0]); m.put("label", k[1]); m.put("used", U.round(v[0], 3)); m.put("limit", U.round(v[1], 3)); m.put("ratio", U.round(v[0] / v[1], 3)); dims.add(m); };
    add.accept(new Object[]{"usd", "金额(USD)"}, new double[]{t.spentUsd, t.budgetUsd});
    add.accept(new Object[]{"tokens", "Token"}, new double[]{t.spentTokens, t.budgetTokens});
    add.accept(new Object[]{"ci", "CI 分钟"}, new double[]{t.spentCi, t.budgetCi});
    add.accept(new Object[]{"iterations", "开发轮次"}, new double[]{t.spentIterations, iterLimit(t)});
    var worst = dims.stream().max(Comparator.comparingDouble(m -> ((Number) m.get("ratio")).doubleValue())).orElse(Map.of());
    Map<String, Object> o = new LinkedHashMap<>(); o.put("scope", "task"); o.put("dims", dims); o.put("worst", worst); o.put("switches", switches(t).toMap()); return o;
  }
  /** 任务预算开关：任务设置 > 项目默认 > 治理默认。from = task | project | governance */
  public record Switches(boolean budgetOff, String budgetFrom, boolean degradeOff, String degradeFrom, int safetyMaxIterations) {
    public Map<String, Object> toMap() { Map<String, Object> m = new LinkedHashMap<>(); m.put("budgetOff", budgetOff); m.put("budgetFrom", budgetFrom); m.put("degradeOff", degradeOff); m.put("degradeFrom", degradeFrom); m.put("safetyMaxIterations", safetyMaxIterations); return m; }
  }
  public Switches switches(com.x7x24.engine.Task t) {
    var g = cfg.get().budgets.task; ProjectCfg.BudgetLimits pb = null;
    try { var p = t.projectId == null ? null : projects.get(t.projectId); if (p != null && p.cfg() != null) pb = p.cfg().budget; } catch (RuntimeException ignored) {}
    if (pb == null && t.context.projectSnapshot != null) pb = t.context.projectSnapshot.budget;
    boolean bo; String bf; boolean dof; String df;
    if (t.context.budgetOff != null) { bo = t.context.budgetOff; bf = "task"; } else if (pb != null && pb.taskUnlimited != null) { bo = pb.taskUnlimited; bf = "project"; } else { bo = g.unlimited; bf = "governance"; }
    if (t.context.degradeOff != null) { dof = t.context.degradeOff; df = "task"; } else if (pb != null && pb.taskDegrade != null) { dof = !pb.taskDegrade; df = "project"; } else { dof = !g.degrade; df = "governance"; }
    return new Switches(bo, bf, dof, df, Math.max(1, g.safetyMaxIterations) + Math.max(0, t.context.safetyExtra));
  }
  /** ok | nudge | hard（预算触顶）| safety（不限预算时开发轮次达到安全上限） */
  public String taskStatus(com.x7x24.engine.Task t) {
    var sw = switches(t);
    if (sw.budgetOff()) return t.spentIterations >= sw.safetyMaxIterations() ? "safety" : "ok";
    String raw = rawTaskStatus(t);
    return sw.degradeOff() && raw.equals("nudge") ? "ok" : raw;
  }
  private String rawTaskStatus(com.x7x24.engine.Task t) {
    double nudge = cfg.get().budgets.task.nudgeRatio;
    double m = Math.max(Math.max(t.spentUsd / t.budgetUsd, (double) t.spentTokens / t.budgetTokens), Math.max(t.budgetCi > 0 ? t.spentCi / t.budgetCi : 0, (double) t.spentIterations / iterLimit(t)));
    return m >= 1 ? "hard" : m >= nudge ? "nudge" : "ok";
  }
  public String lineStatus(String line) {
    var b = limits(lines.require(line).cfg().budget); var u = lineDaily(line);
    double m = Math.max(u.usd / b.dailyUsd, Math.max(u.ci / b.dailyCiMinutes, u.tokens / b.dailyTokens));
    return m >= 1 ? "hard" : m >= b.softRatio ? "soft" : "ok";
  }
  public Usage projectDaily(String projectId) { return sum("project_id=? AND day=?", projectId, clk.dayKey()); }
  public String projectStatus(String projectId) {
    var b = limits(projects.require(projectId).cfg().budget); var u = projectDaily(projectId);
    double ratio = Math.max(u.usd / b.dailyUsd, Math.max(u.ci / b.dailyCiMinutes, u.tokens / b.dailyTokens));
    return ratio >= 1 ? "hard" : ratio >= b.softRatio ? "soft" : "ok";
  }
  public Governance.LineBudget limits(ProjectCfg.BudgetLimits override) {
    var b = Json.convert(cfg.get().budgets.line, Governance.LineBudget.class);
    if (override.dailyUsd != null) b.dailyUsd = override.dailyUsd;
    if (override.dailyTokens != null) b.dailyTokens = override.dailyTokens;
    if (override.dailyCiMinutes != null) b.dailyCiMinutes = override.dailyCiMinutes;
    return b;
  }
  public Map<String, Object> globalStatus() {
    var g = cfg.get().budgets.global; var d = globalDaily(); var m = globalMonthly(); String reason = null;
    if (d.usd >= g.dailyUsd) reason = "全局日金额 " + d.usd + " ≥ " + g.dailyUsd;
    else if (d.tokens >= g.dailyTokens) reason = "全局日 token " + d.tokens + " ≥ " + g.dailyTokens;
    else if (d.ci >= g.dailyCiMinutes) reason = "全局日 CI 分钟 " + d.ci + " ≥ " + g.dailyCiMinutes;
    else if (m.usd >= g.monthlyUsd) reason = "全局月金额 " + m.usd + " ≥ " + g.monthlyUsd;
    Map<String, Object> o = new LinkedHashMap<>(); o.put("level", reason != null ? "hard" : "ok"); o.put("monthlyAlert", m.usd >= g.monthlyUsd * g.monthlyAlertRatio); if (reason != null) o.put("reason", reason);
    return o;
  }

  /** 每个工作区分别检查「全局」（= 工作区总量）预算：本月有花费或有预算熔断开关的工作区 */
  public void enforceAll() {
    if (com.x7x24.tenant.Tenant.current() != null) { enforceGlobal(); return; }
    Set<String> wss = new LinkedHashSet<>();
    for (var r : db.rows("SELECT DISTINCT workspace_id FROM budget_ledger WHERE day LIKE ?", clk.monthKey() + "%")) wss.add(Db.str(r.get("workspace_id")));
    for (var r : db.rows("SELECT DISTINCT workspace_id FROM kill_switch WHERE active=1 AND trigger_key='budget:global'")) wss.add(Db.str(r.get("workspace_id")));
    for (String ws : wss) if (ws != null && !com.x7x24.tenant.Tenant.PLATFORM.equals(ws)) com.x7x24.tenant.Tenant.run(ws, this::enforceGlobal);
  }
  /** 全局触顶 => 自动 READ_ONLY kill switch；条件消除后自动解除 */
  public boolean enforceGlobal() {
    var g = globalStatus(); String tk = "budget:global";
    if (Boolean.TRUE.equals(g.get("monthlyAlert"))) audit.sys("BUDGET_HIT", "system", Map.of("scope", "global_month", "level", "alert"), "budget:month-alert:" + clk.monthKey());
    var act = kill.active().stream().filter(k -> tk.equals(k.get("trigger_key"))).findFirst().orElse(null);
    if ("hard".equals(g.get("level"))) {
      if (act == null) {
        audit.sys("BUDGET_HIT", "system", Map.of("scope", "global", "level", "hard", "reason", String.valueOf(g.get("reason"))), "budget:global-hard:" + clk.dayKey());
        kill.activate("global", "halt".equals(settings.budgetOnExceed()) ? "HALT" : "READ_ONLY", "auto", String.valueOf(g.get("reason")), tk);
      }
      return true;
    }
    if (act != null) kill.release((String) act.get("id"), "system");
    return false;
  }

  public String noteLine(String line) {
    String lv = lineStatus(line);
    if (!lv.equals("ok")) audit.sys("BUDGET_HIT", "system", Map.of("scope", "line:" + line, "level", lv), "budget:line:" + line + ":" + lv + ":" + clk.dayKey());
    return lv;
  }

  public Map<String, Object> snapshot(List<String> lines) {
    var c = cfg.get().budgets; Map<String, Object> o = new LinkedHashMap<>();
    o.put("day", clk.dayKey());
    o.put("global", Map.of("daily", globalDaily(), "monthly", globalMonthly(), "limits", c.global, "status", globalStatus()));
    o.put("lines", lines.stream().map(l -> Map.of("id", l, "name", this.lines.require(l).name(), "daily", lineDaily(l), "limits", limits(this.lines.require(l).cfg().budget), "status", lineStatus(l))).toList());
    o.put("projects", projects.list().stream().map(p -> Map.of("id", p.id(), "name", p.name(), "productLineId", p.productLineId(), "daily", projectDaily(p.id()), "limits", limits(p.cfg().budget), "status", projectStatus(p.id()))).toList());
    o.put("task", c.task); o.put("policy", settings.budgetOnExceed()); o.put("softRatio", c.line.softRatio); return o;
  }

  /** 触顶行为为「暂停新任务」时：项目 / 产品线 / 全局只要进入降级线或已触顶，就不再接收新任务。 */
  public void assertAdmission(String projectId) {
    if (!"pause_new".equals(settings.budgetOnExceed())) return;
    var p = projects.require(projectId);
    if (!projectStatus(projectId).equals("ok") || !lineStatus(p.productLineId()).equals("ok") || "hard".equals(globalStatus().get("level")))
      throw new IllegalArgumentException("预算已用到省钱线，按「触顶行为」的设置暂停接收新任务。可到「预算」页调高额度或更改触顶行为");
  }
  public Usage dailyOfTask(String taskId) { return sum("task_id=?", taskId); }
}
