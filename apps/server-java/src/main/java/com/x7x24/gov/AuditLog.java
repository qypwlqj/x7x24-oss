package com.x7x24.gov;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.db.Db;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 只追加、哈希链的审计日志（数据库触发器禁止 UPDATE/DELETE）。 */
@Component
public class AuditLog {
  public static final String GENESIS = "0".repeat(64);
  public record Input(String taskId, Integer iteration, String type, String from, String to, String edge, String actor, String model, String promptVersion, Object payload, String key, String at) {}
  /** SSE / 实时看板订阅者；监听器异常不影响写入 */
  private final List<Consumer<Map<String, Object>>> listeners = new CopyOnWriteArrayList<>();
  private final Db db; private final Clk clk; private final org.springframework.transaction.support.TransactionTemplate requiresNew;
  private final com.x7x24.notification.NotificationEvents notifications;

  public AuditLog(Db db, Clk clk, org.springframework.transaction.PlatformTransactionManager tm, com.x7x24.notification.NotificationEvents notifications) {
    this.notifications = notifications;
    this.db = db; this.clk = clk; this.requiresNew = new org.springframework.transaction.support.TransactionTemplate(tm);
    this.requiresNew.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public Runnable subscribe(Consumer<Map<String, Object>> fn) { listeners.add(fn); return () -> listeners.remove(fn); }

  public static Input ev(String taskId, Integer iteration, String type, String actor, Object payload, String key, String at) {
    return new Input(taskId, iteration, type, null, null, null, actor, null, null, payload, key, at);
  }
  public String now() { return clk.iso(); }

  /** 便捷：无任务的系统事件 */
  public Map<String, Object> sys(String type, String actor, Object payload, String key) { return append(ev(null, null, type, actor, payload, key, clk.iso())).row; }
  public Map<String, Object> task(String taskId, Integer it, String type, String actor, Object payload, String key) { return append(ev(taskId, it, type, actor, payload, key, clk.iso())).row; }

  public record Appended(Map<String, Object> row, boolean created) {}

  /** 链头是平台级的（跨所有工作区），所以不受行级安全限制 */
  public Map<String, Object> head() {
    var r = com.x7x24.tenant.Tenant.unscoped(() -> db.row("SELECT id, hash FROM task_event ORDER BY id DESC LIMIT 1"));
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("hash", r == null ? GENESIS : r.get("hash")); m.put("id", r == null ? 0 : Db.l(r.get("id")));
    return m;
  }

  static String digest(String prev, String task, Integer it, String type, String from, String to, String edge, String actor, String model, String pv, String payload, String key, String at) {
    return U.sha256(prev + "|" + String.join("\u0001", n(task), n(it), n(type), n(from), n(to), n(edge), n(actor), n(model), n(pv), n(payload), n(key), n(at)));
  }
  private static String n(Object o) { return o == null ? "" : o.toString(); }

  /** 幂等：同一 key 重复调用返回已有记录（created=false） */
  /** 独立事务 + 互斥：保证哈希链按提交顺序串行，不被调用方的外层事务拖慢可见性 */
  public synchronized Appended append(Input i) { return com.x7x24.tenant.Tenant.unscoped(() -> requiresNew.execute(st -> doAppend(i))); }

  private Appended doAppend(Input i) {
    if (i.key() != null) {
      var ex = db.row("SELECT * FROM task_event WHERE idempotency_key = ?", i.key());
      if (ex != null) return new Appended(hydrate(ex), false);
    }
    String prev = (String) head().get("hash");
    String payload = i.payload() == null ? null : com.x7x24.adapters.Redactor.json(i.payload());
    String hash = digest(prev, i.taskId(), i.iteration(), i.type(), i.from(), i.to(), i.edge(), i.actor(), i.model(), i.promptVersion(), payload, i.key(), i.at());
    // 归属：任务事件跟任务走；系统事件跟当前上下文（平台 / 系统 = _platform）。不参与哈希（链是平台级的，保持原算法）。
    String ws = null; if (i.taskId() != null) { var t = db.row("SELECT workspace_id FROM task WHERE id=?", i.taskId()); if (t != null) ws = (String) t.get("workspace_id"); }
    if (ws == null) ws = com.x7x24.tenant.Tenant.writeId();
    db.update("INSERT INTO task_event (task_id, iteration_seq, event_type, from_status, to_status, edge, actor, model, prompt_version, payload, idempotency_key, prev_hash, hash, occurred_at, workspace_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        i.taskId(), i.iteration(), i.type(), i.from(), i.to(), i.edge(), i.actor(), i.model(), i.promptVersion(), payload, i.key(), prev, hash, i.at(), ws);
    var row = hydrate(db.row("SELECT * FROM task_event WHERE hash = ?", hash));
    notifications.fromAudit(row);
    for (var l : listeners) { try { l.accept(row); } catch (Exception ignore) { /* ignore */ } }
    return new Appended(row, true);
  }

  public Map<String, Object> hydrate(Map<String, Object> r) {
    Map<String, Object> m = new LinkedHashMap<>(r);
    String p = (String) r.get("payload"); m.put("payload", p == null ? null : Json.tree(p));
    m.put("_raw", p); return m;
  }

  public Map<String, Object> verify() { return com.x7x24.tenant.Tenant.unscoped(this::doVerify); }
  private Map<String, Object> doVerify() {
    String prev = GENESIS; long n = 0;
    Map<String, Object> out = new LinkedHashMap<>();
    for (var r : db.rows("SELECT * FROM task_event ORDER BY id")) {
      long id = Db.l(r.get("id"));
      if (!prev.equals(r.get("prev_hash"))) { out.put("ok", false); out.put("checked", n); out.put("brokenAt", id); out.put("reason", "prev_hash mismatch"); return out; }
      String d = digest(prev, Db.str(r.get("task_id")), r.get("iteration_seq") == null ? null : Db.i(r.get("iteration_seq")), Db.str(r.get("event_type")), Db.str(r.get("from_status")), Db.str(r.get("to_status")), Db.str(r.get("edge")),
          Db.str(r.get("actor")), Db.str(r.get("model")), Db.str(r.get("prompt_version")), Db.str(r.get("payload")), Db.str(r.get("idempotency_key")), Db.str(r.get("occurred_at")));
      if (!d.equals(r.get("hash"))) { out.put("ok", false); out.put("checked", n); out.put("brokenAt", id); out.put("reason", "hash mismatch"); return out; }
      prev = (String) r.get("hash"); n++;
    }
    out.put("ok", true); out.put("checked", n); return out;
  }

  public List<Map<String, Object>> list(String taskId, String type, int limit, int offset) {
    StringBuilder w = new StringBuilder(); List<Object> p = new ArrayList<>();
    if (taskId != null) { w.append(" task_id = ?"); p.add(taskId); }
    if (type != null) { w.append(w.length() > 0 ? " AND" : "").append(" event_type = ?"); p.add(type); }
    String ws = com.x7x24.tenant.Tenant.readFilter(); if (ws != null) { w.append(w.length() > 0 ? " AND" : "").append(" workspace_id = ?"); p.add(ws); }
    p.add(limit); p.add(offset);
    var rows = db.rows("SELECT * FROM task_event" + (w.length() > 0 ? " WHERE" + w : "") + " ORDER BY id DESC LIMIT ? OFFSET ?", p.toArray());
    List<Map<String, Object>> out = new ArrayList<>();
    for (var r : rows) { var h = hydrate(r); h.remove("_raw"); out.add(h); }
    return out;
  }
  public long count() { String ws = com.x7x24.tenant.Tenant.readFilter(); return ws == null ? db.count("SELECT COUNT(*) FROM task_event") : db.count("SELECT COUNT(*) FROM task_event WHERE workspace_id=?", ws); }
  public JsonNode payloadOf(Map<String, Object> row) { return (JsonNode) row.get("payload"); }
}
