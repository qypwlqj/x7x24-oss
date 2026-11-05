package com.x7x24.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.db.Db;
import com.x7x24.gov.AuditLog;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class LineRepo {
  public record Line(String id, String name, String description, String owner, String state, String autonomyLevel, LineCfg cfg, int paused, String intakePausedUntil, String lastTightenedAt, String createdAt) {}
  private final Db db; private final Clk clk; private final AuditLog audit;
  public LineRepo(Db db, Clk clk, AuditLog audit) { this.db = db; this.clk = clk; this.audit = audit; }
  private static Line map(Map<String, Object> r) {
    return new Line(Db.str(r.get("id")), Db.str(r.get("name")), Db.str(r.get("description")), Db.str(r.get("owner")), Db.str(r.get("state")), Db.str(r.get("autonomy_level")), Json.parse((String) r.get("config"), LineCfg.class), Db.i(r.get("paused")), Db.str(r.get("intake_paused_until")), Db.str(r.get("last_tightened_at")), Db.str(r.get("created_at")));
  }
  public Line get(String id) { var row = db.row("SELECT * FROM product_line WHERE id=?" + com.x7x24.tenant.Tenant.and("workspace_id"), com.x7x24.tenant.Tenant.args(id)); return row == null ? null : map(row); }
  public List<Line> list() { return db.rows("SELECT * FROM product_line WHERE 1=1" + com.x7x24.tenant.Tenant.and("workspace_id") + " ORDER BY created_at,id", com.x7x24.tenant.Tenant.args()).stream().map(LineRepo::map).toList(); }
  /** 启动配置仅初始化，不能覆盖页面中已保存的内容。 */
  public void upsert(LegacyLineCfg c, boolean ignored) {
    if (get(c.id) != null) return;
    if (db.count("SELECT COUNT(*) FROM product_line WHERE id=?", c.id) > 0) return; // 种子可能属于其他工作区：不重复创建
    db.update("INSERT INTO product_line (id,name,autonomy_level,config,created_at,workspace_id) VALUES (?,?,?,?,?,?)", c.id, c.name, c.autonomy, Json.str(new LineCfg()), clk.iso(), com.x7x24.tenant.Tenant.forInsert());
  }
  public synchronized Line create(JsonNode body) {
    String id = "line-" + U.uuid(), name = requiredName(body.path("name").asText());
    String autonomy = body.path("autonomy").asText("L1"); validateAutonomy(autonomy);
    LineCfg config = new LineCfg(); if (body.has("budget")) config.budget = Json.convert(body.get("budget"), ProjectCfg.BudgetLimits.class);
    ProjectRepo.validateBudget(config.budget);
    db.update("INSERT INTO product_line (id,name,description,owner,autonomy_level,config,created_at,workspace_id) VALUES (?,?,?,?,?,?,?,?)", id, name, body.path("description").asText(""), body.path("owner").asText(""), autonomy, Json.str(config), clk.iso(), com.x7x24.tenant.Tenant.forInsert());
    audit.sys("LINE_CREATED", "operator", Map.of("id", id, "name", name), null); return get(id);
  }
  public synchronized Line update(String id, JsonNode body) {
    return db.tx(() -> {
      db.row("SELECT id FROM product_line WHERE id=? FOR UPDATE", id);
      var old = require(id); String state = body.path("state").asText(old.state()); validateState(state);
      if (state.equals("ARCHIVED") && db.count("SELECT COUNT(*) FROM project WHERE product_line_id=? AND state<>'ARCHIVED'", id) > 0) throw new IllegalArgumentException("请先归档产品线下的项目");
      if (old.state().equals("ARCHIVED") && !state.equals("ARCHIVED")) throw new IllegalArgumentException("已归档产品线不可重新启用");
      String autonomy = body.path("autonomy").asText(old.autonomyLevel()); validateAutonomy(autonomy);
      var config = old.cfg(); if (body.has("budget")) config.budget = Json.convert(body.get("budget"), ProjectCfg.BudgetLimits.class); ProjectRepo.validateBudget(config.budget);
      db.update("UPDATE product_line SET name=?,description=?,owner=?,state=?,paused=?,autonomy_level=?,config=? WHERE id=?", requiredName(body.path("name").asText(old.name())), body.path("description").asText(old.description()), body.path("owner").asText(old.owner()), state, state.equals("ACTIVE") ? 0 : 1, autonomy, Json.str(config), id);
      audit.sys("LINE_UPDATED", "operator", Map.of("id", id, "state", state, "autonomy", autonomy), null); return get(id);
    });
  }
  public Line require(String id) { var line = get(id); if (line == null) throw new IllegalArgumentException("产品线不存在"); return line; }
  public void setAutonomy(String id, String level) { validateAutonomy(level); db.update("UPDATE product_line SET autonomy_level=? WHERE id=?", level, id); }
  static String requiredName(String name) { if (name == null || name.isBlank() || name.trim().length() > 200) throw new IllegalArgumentException("名称不能为空，且不能超过 200 字"); return name.trim(); }
  static void validateAutonomy(String level) { if (level == null || !List.of("L1", "L2", "L3").contains(level)) throw new IllegalArgumentException("自治等级必须为 L1/L2/L3"); }
  static void validateState(String state) { if (state == null || !List.of("ACTIVE", "PAUSED", "ARCHIVED").contains(state)) throw new IllegalArgumentException("状态必须为 ACTIVE/PAUSED/ARCHIVED"); }
}
