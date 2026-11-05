package com.x7x24.integration;

import com.x7x24.db.Db;
import com.x7x24.engine.X7Props;
import org.springframework.stereotype.Component;

/** 可在运行时切换并持久化的全局设置（settings 表）；环境变量 ADAPTER_MODE / ADAPTER_DRY_RUN 仅作初始默认。 */
@Component
public class RuntimeSettings {
  private final Db db; private final X7Props props;
  public RuntimeSettings(Db db, X7Props props) { this.db = db; this.props = props; }
  /** 工作区自己的值优先；遗留工作区没有时沿用平台值（迁移前的全局设置）；其他工作区没有时用默认值 */
  private String get(String k) {
    String ws = com.x7x24.tenant.Tenant.storeScope();
    if (ws != null) { var r = db.row("SELECT value FROM settings WHERE skey=?", "ws:" + ws + ":" + k); return r != null ? (String) r.get("value") : wsDefault(k); }
    var r = db.row("SELECT value FROM settings WHERE skey=?", k); return r == null ? null : (String) r.get("value");
  }
  /** 新工作区：适配器模式沿用平台，但默认「观察」（dry run），由工作区管理员确认后才切到执行 */
  private String wsDefault(String k) {
    if (k.equals("adapter_dry_run")) return "true";
    if (k.equals("adapter_mode")) { var r = db.row("SELECT value FROM settings WHERE skey=?", k); return r == null ? null : (String) r.get("value"); }
    return null;
  }
  private void put(String k0, String v) { String ws = com.x7x24.tenant.Tenant.storeScope(); String k = ws == null ? k0 : "ws:" + ws + ":" + k0; if (db.update("UPDATE settings SET value=? WHERE skey=?", v, k) == 0) db.update("INSERT INTO settings(skey,value) VALUES(?,?)", k, v); }
  /** real | offline（内部名；界面不展示） */
  public String adapterMode() { String v = get("adapter_mode"); return v != null ? v : props.adapter.mode; }
  public boolean dryRun() { String v = get("adapter_dry_run"); return v != null ? Boolean.parseBoolean(v) : props.adapter.dryRun; }
  public void setAdapterMode(String m) { put("adapter_mode", m); }
  public void setDryRun(boolean b) { put("adapter_dry_run", String.valueOf(b)); }
  /** 预算触顶行为：degrade（降级到低成本模型，触顶后只读）| pause_new（暂停新任务）| halt（全部停止） */
  public String budgetOnExceed() { String v = get("budget_on_exceed"); return v != null ? v : "degrade"; }
  public void setBudgetOnExceed(String v) { put("budget_on_exceed", v); }
  /** 可组合流程功能开关（按工作区，默认关）：开启后新任务钉 v2 快照并走 v2 引擎；在途任务不受影响（决策 D10） */
  public boolean pipelineV2() { String v = get("pipeline_v2"); return v != null && Boolean.parseBoolean(v); }
  public void setPipelineV2(boolean on) { put("pipeline_v2", String.valueOf(on)); }
}
