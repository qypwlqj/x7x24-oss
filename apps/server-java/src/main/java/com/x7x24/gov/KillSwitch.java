package com.x7x24.gov;

import com.x7x24.db.Db;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;

import java.util.*;

/** Kill switch：只作系统保护开关。scope: global | line:<id> | agent:<name>；HALT/READ_ONLY 阻断写，FREEZE_RELEASE 只阻断发布。 */
@Component
public class KillSwitch {
  public static class Blocked extends RuntimeException { public final Map<String, Object> sw; public Blocked(Map<String, Object> sw) { super("kill switch " + sw.get("mode") + "@" + sw.get("scope") + " 已启用"); this.sw = sw; } }
  private final Db db; private final AuditLog audit; private final Clk clk;
  public KillSwitch(Db db, AuditLog audit, Clk clk) { this.db = db; this.audit = audit; this.clk = clk; }

  public Map<String, Object> activate(String scope, String mode, String source, String reason, String triggerKey) {
    scope = scope == null ? "global" : scope;
    String ws = com.x7x24.tenant.Tenant.writeId();
    var ex = db.row("SELECT * FROM kill_switch WHERE active=1 AND scope=? AND mode=? AND COALESCE(trigger_key,'')=? AND workspace_id=?", scope, mode, triggerKey == null ? "" : triggerKey, ws);
    if (ex != null) return ex;
    String id = U.uuid(), at = clk.iso();
    db.update("INSERT INTO kill_switch (id,scope,mode,source,reason,trigger_key,active,created_at,workspace_id) VALUES (?,?,?,?,?,?,1,?,?)", id, scope, mode, source, reason, triggerKey, at, ws);
    var row = db.row("SELECT * FROM kill_switch WHERE id=?", id);
    audit.sys("KILL_SWITCH", "auto".equals(source) ? "system" : "operator", Map.of("action", "activate", "id", id, "scope", scope, "mode", mode, "source", source, "reason", String.valueOf(reason)), null);
    return row;
  }
  public boolean release(String id, String by) {
    String ws = com.x7x24.tenant.Tenant.readFilter();
    int n = ws == null ? db.update("UPDATE kill_switch SET active=0, released_at=? WHERE id=? AND active=1", clk.iso(), id)
        : db.update("UPDATE kill_switch SET active=0, released_at=? WHERE id=? AND active=1 AND workspace_id=?", clk.iso(), id, ws); // 工作区不能解除平台级开关
    if (n > 0) audit.sys("KILL_SWITCH", by, Map.of("action", "release", "id", id), null);
    return n > 0;
  }
  /** 生效中的开关：工作区上下文 = 本工作区 + 平台级；系统 / 平台上下文 = 全部 */
  public List<Map<String, Object>> active() { String ws = com.x7x24.tenant.Tenant.readFilter(); return ws == null ? db.rows("SELECT * FROM kill_switch WHERE active=1 ORDER BY created_at") : db.rows("SELECT * FROM kill_switch WHERE active=1 AND workspace_id IN (?,?) ORDER BY created_at", ws, com.x7x24.tenant.Tenant.PLATFORM); }
  public List<Map<String, Object>> all() { String ws = com.x7x24.tenant.Tenant.readFilter(); return ws == null ? db.rows("SELECT * FROM kill_switch ORDER BY created_at DESC LIMIT 100") : db.rows("SELECT * FROM kill_switch WHERE workspace_id IN (?,?) ORDER BY created_at DESC LIMIT 100", ws, com.x7x24.tenant.Tenant.PLATFORM); }

  /** kind: write | release */
  public Map<String, Object> isBlocked(String line, String kind, String agent) {
    return isBlocked(line, null, kind, agent);
  }
  public Map<String, Object> isBlocked(String line, String project, String kind, String agent) {
    for (var k : active()) {
      String scope = (String) k.get("scope"); String mode = (String) k.get("mode");
      boolean applies = "global".equals(scope) || ("line:" + line).equals(scope) || (project != null && ("project:" + project).equals(scope)) || (agent != null && ("agent:" + agent).equals(scope));
      if (!applies) continue;
      if (mode.equals("HALT") || mode.equals("READ_ONLY")) return k;
      if (mode.equals("FREEZE_RELEASE") && "release".equals(kind)) return k;
    }
    return null;
  }
  public void assertWritable(String line, String kind) { var b = isBlocked(line, kind, null); if (b != null) throw new Blocked(b); }
  public void assertWritable(String line, String project, String kind) { var b = isBlocked(line, project, kind, null); if (b != null) throw new Blocked(b); }
}
