package com.x7x24.gov;

import com.x7x24.db.Db;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class VerdictStore {
  private final Db db; private final Clk clk; private final AuditLog audit;
  public VerdictStore(Db db, Clk clk, AuditLog audit) { this.db = db; this.clk = clk; this.audit = audit; }

  public String record(String taskId, int iteration, String gate, Object ruleResults, Object votes, Double aggregate, String decision, Map<String, Object> meta) {
    String id = U.uuid(); Map<String, Object> vm = new LinkedHashMap<>(); vm.put("votes", votes); vm.put("meta", meta);
    db.update("INSERT INTO verdict (id,task_id,iteration_seq,gate,rule_results,votes,aggregate_score,decision,evidence_ids,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        id, taskId, iteration, gate, Json.str(ruleResults), Json.str(vm), aggregate, decision, "[]", clk.iso());
    Map<String, Object> p = new LinkedHashMap<>(); p.put("id", id); p.put("gate", gate); p.put("decision", decision); p.put("aggregate", aggregate);
    audit.task(taskId, iteration, "VERDICT", gate, p, null);
    return id;
  }

  public List<Map<String, Object>> list(String taskId) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (var r : db.rows("SELECT * FROM verdict WHERE task_id=? ORDER BY created_at, id", taskId)) {
      Map<String, Object> m = new LinkedHashMap<>();
      var vm = Json.tree((String) r.get("votes"));
      m.put("id", r.get("id")); m.put("taskId", r.get("task_id")); m.put("iteration", r.get("iteration_seq")); m.put("gate", r.get("gate"));
      m.put("ruleResults", Json.tree((String) r.get("rule_results"))); m.put("votes", vm.get("votes")); m.put("meta", vm.get("meta"));
      m.put("aggregate", r.get("aggregate_score")); m.put("decision", r.get("decision")); m.put("createdAt", r.get("created_at"));
      out.add(m);
    }
    return out;
  }
}
