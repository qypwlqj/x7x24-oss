package com.x7x24.adapters;

import com.x7x24.db.Db;
import com.x7x24.util.Clk;
import org.springframework.stereotype.Component;

import java.util.*;

/** 适配器调用日志：只记方法/路径/状态码/耗时，不记请求头与 body；note 经脱敏。 */
@Component
public class CallLog {
  private final Db db; private final Clk clk;
  public CallLog(Db db, Clk clk) { this.db = db; this.clk = clk; }
  public void add(String adapter, String op, String method, String path, Integer status, boolean ok, boolean dryRun, long durMs, String key, String line, String note) {
    var project = line == null ? null : db.row("SELECT id,product_line_id FROM project WHERE id=?", line);
    String productLine = project == null ? line : Db.str(project.get("product_line_id"));
    String projectId = project == null ? null : Db.str(project.get("id"));
    String ws = project == null ? null : Db.str(db.row("SELECT workspace_id FROM project WHERE id=?", projectId).get("workspace_id")); if (ws == null) ws = com.x7x24.tenant.Tenant.writeId();
    db.update("INSERT INTO adapter_call (at,adapter,op,method,path,status,ok,dry_run,duration_ms,op_key,line,project_id,note,workspace_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", clk.iso(), adapter, op, method,
        path == null ? null : Redactor.redact(path), status, ok ? 1 : 0, dryRun ? 1 : 0, durMs, key == null ? null : (key.length() > 290 ? key.substring(0, 290) : key), productLine, projectId, note == null ? null : Redactor.redact(note).substring(0, Math.min(500, Redactor.redact(note).length())), ws);
  }
  public List<Map<String, Object>> recent(int n) { String ws = com.x7x24.tenant.Tenant.readFilter(); return ws == null ? db.rows("SELECT * FROM adapter_call ORDER BY id DESC LIMIT ?", n) : db.rows("SELECT * FROM adapter_call WHERE workspace_id=? ORDER BY id DESC LIMIT ?", ws, n); }
  public List<Map<String, Object>> stats() {
    String ws = com.x7x24.tenant.Tenant.readFilter();
    if (ws != null) return db.rows("SELECT adapter, COUNT(*) calls, SUM(CASE WHEN ok=1 THEN 0 ELSE 1 END) failures, SUM(dry_run) dry_runs FROM adapter_call WHERE workspace_id=? GROUP BY adapter", ws);
    return db.rows("SELECT adapter, COUNT(*) calls, SUM(CASE WHEN ok=1 THEN 0 ELSE 1 END) failures, SUM(dry_run) dry_runs FROM adapter_call GROUP BY adapter");
  }
}
