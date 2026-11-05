package com.x7x24.gov;

import com.x7x24.db.Db;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;

import java.util.*;

/** 证据存储：内容哈希 + WORM（数据库触发器禁止改删）；被 verdict 与验收闸门引用。 */
@Component
public class EvidenceStore {
  private final Db db; private final Clk clk; private final AuditLog audit;
  public EvidenceStore(Db db, Clk clk, AuditLog audit) { this.db = db; this.clk = clk; this.audit = audit; }

  public synchronized Map<String, Object> add(String taskId, int iteration, String stage, String kind, Object content, String producedBy, String model, String key) {
    String id = key != null ? "ev_" + U.sha256(key).substring(0, 24) : "ev_" + U.uuid().replace("-", "").substring(0, 24);
    var ex = db.row("SELECT * FROM evidence WHERE id = ?", id);
    if (ex != null) return ex;
    String c = com.x7x24.adapters.Redactor.json(content); String sha = U.sha256(c); String at = clk.iso();
    db.update("INSERT INTO evidence (id,task_id,iteration_seq,stage,kind,uri,sha256,content,produced_by,model,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        id, taskId, iteration, stage, kind, "evidence://" + taskId + "/" + id, sha, c, producedBy, model, at);
    audit.task(taskId, iteration, "EVIDENCE_ADDED", producedBy == null ? "system" : producedBy, Map.of("id", id, "kind", kind, "sha256", sha), "evidence:" + id);
    return db.row("SELECT * FROM evidence WHERE id = ?", id);
  }
  public List<Map<String, Object>> list(String taskId) { return db.rows("SELECT * FROM evidence WHERE task_id=? ORDER BY created_at, id", taskId); }
  public Map<String, Object> get(String id) { return db.row("SELECT * FROM evidence WHERE id=?", id); }
  public boolean verifyOne(Map<String, Object> r) { return U.sha256((String) r.get("content")).equals(r.get("sha256")); }
  /** 最新一条指定 kind 的证据内容 */
  public com.fasterxml.jackson.databind.JsonNode latest(String taskId, String kind) {
    var rows = list(taskId).stream().filter(e -> kind.equals(e.get("kind"))).toList();
    return rows.isEmpty() ? null : Json.tree((String) rows.get(rows.size() - 1).get("content"));
  }

  public record Verify(boolean ok, List<String> missing, List<String> tampered) {}
  /** 可组合流程 v2：按节点核对证据（stage 为 null 表示不限节点）；缺的返回「节点:证据」 */
  public record Need(String node, String stage, String kind) {}
  public List<String> missingForNodes(String taskId, List<Need> needs) {
    var rows = list(taskId); List<String> out = new ArrayList<>();
    for (var n : needs) if (rows.stream().noneMatch(r -> n.kind().equals(r.get("kind")) && (n.stage() == null || n.stage().equals(r.get("stage"))) && verifyOne(r))) out.add(n.node() + ":" + n.kind());
    return out;
  }
  public Verify verifyTask(String taskId, List<String> required) {
    var rows = list(taskId); Set<String> kinds = new HashSet<>(); for (var r : rows) kinds.add((String) r.get("kind"));
    var missing = required.stream().filter(k -> !kinds.contains(k)).toList();
    var tampered = rows.stream().filter(r -> !verifyOne(r)).map(r -> (String) r.get("id")).toList();
    return new Verify(missing.isEmpty() && tampered.isEmpty(), missing, tampered);
  }
}
