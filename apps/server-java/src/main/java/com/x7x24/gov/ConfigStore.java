package com.x7x24.gov;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.db.Db;
import com.x7x24.util.Json;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 治理配置存取：从 settings 读、热更新时深合并 + 校验，失败不改变现有配置。 */
@Component
public class ConfigStore {
  private static final Pattern COND = Pattern.compile("^result\\s*(<=|>=|<|>|==|!=)\\s*-?\\d+(\\.\\d+)?$");
  private final Db db;
  /** 每个范围一份（null 键 = 平台 / 系统上下文；工作区 = 其 id）。决策：治理配置归工作区。 */
  /** inherit=true：工作区还没有自己的治理配置，沿用平台配置（遗留工作区迁移后即如此，行为不变） */
  record State(Governance g, int version, boolean inherit) { State(Governance g, int version) { this(g, version, false); } }
  private final java.util.Map<String, State> states = new java.util.concurrent.ConcurrentHashMap<>();
  private static final String PLATFORM_KEY = "";

  public ConfigStore(Db db) { this.db = db; }
  static String k(String scope, String name) { return scope == null || scope.isEmpty() ? name : "ws:" + scope + ":" + name; }
  private State load(String scope) {
    String sc = scope.isEmpty() ? null : scope;
    var row = db.row("SELECT value FROM settings WHERE skey=?", k(sc, "governance"));
    Governance g = Governance.defaults();
    if (row != null) g = merge(g, Json.tree((String) row.get("value")));
    var v = db.row("SELECT value FROM settings WHERE skey=?", k(sc, "governance_version"));
    return new State(g, v == null ? 1 : Integer.parseInt((String) v.get("value")));
  }
  private String scope() { String s = com.x7x24.tenant.Tenant.storeScope(); return s == null ? PLATFORM_KEY : s; }
  private State state() { return states.computeIfAbsent(scope(), this::load); }
  public Governance get() { return state().g(); }
  public int version() { return state().version(); }

  /** 深合并：数组整体替换，对象逐字段合并 */
  public static Governance merge(Governance base, JsonNode patch) {
    try {
      Governance copy = Json.M.readValue(Json.M.writeValueAsString(base), Governance.class);
      Governance out = Json.M.readerForUpdating(copy).readValue(patch);
      validate(out); return out;
    } catch (IllegalArgumentException e) { throw e; } catch (Exception e) { throw new com.x7x24.web.UserError("bad_governance_config", "治理配置格式有误，请检查后重试：" + e.getMessage()); }
  }
  static void validate(Governance g) {
    for (var m : g.canary.metrics) {
      if (m.name == null || m.successCondition == null || !COND.matcher(m.successCondition.trim()).matches()) throw new com.x7x24.web.UserError("bad_canary_condition", "灰度发布的成功条件写法不对：" + m.successCondition);
      if (m.failureLimit < 0 || m.count < 1) throw new com.x7x24.web.UserError("bad_canary_metric", "灰度发布指标「" + m.name + "」的设置不对：失败次数不能小于 0，观察次数至少 1");
    }
    if (g.loops.maxLoops < 1 || g.loops.maxStepAttempts < 1) throw new com.x7x24.web.UserError("bad_loops", "返修次数上限至少为 1");
    if (g.budgets.task.usd <= 0 || g.budgets.task.tokens <= 0) throw new com.x7x24.web.UserError("bad_task_budget", "单任务金额和 Token 额度都必须大于 0");
    if(g.loops.maxPolicyRepairAttempts<0 || g.loops.maxPolicyRepairAttempts>5)throw new com.x7x24.web.UserError("bad_policy_retries","内容检查的自动修复次数必须在 0 到 5 之间");
    ChangeSize.validate(g.release);
  }

  public synchronized Governance update(JsonNode patch) {
    String sc = scope(); var st = state();
    Governance merged = merge(st.g(), patch); int v = st.version() + 1;
    states.put(sc, new State(merged, v));
    String s0 = sc.isEmpty() ? null : sc; save(k(s0, "governance"), Json.str(merged)); save(k(s0, "governance_version"), String.valueOf(v));
    return merged;
  }
  public synchronized void reset() { var st = state(); states.put(scope(), new State(Governance.defaults(), st.version() + 1)); }
  private void save(String k, String v) {
    if (db.update("UPDATE settings SET value=? WHERE skey=?", v, k) == 0) db.update("INSERT INTO settings(skey,value) VALUES(?,?)", k, v);
  }
}
