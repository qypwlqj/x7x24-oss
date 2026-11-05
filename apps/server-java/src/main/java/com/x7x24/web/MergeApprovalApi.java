package com.x7x24.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.db.Db;
import com.x7x24.engine.Engine;
import com.x7x24.engine.TaskRepo;
import com.x7x24.util.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 合并关卡：查看任务的合并等级、等确认记录；负责人一键确认合并 / 退回修改（绑定 PR 头提交） */
@RestController
public class MergeApprovalApi {
  private final TaskRepo repo; private final Engine engine; private final Db db;
  public MergeApprovalApi(TaskRepo repo, Engine engine, Db db) { this.repo = repo; this.engine = engine; this.db = db; }

  @GetMapping("/api/tasks/{id}/merge-gate") public Map<String, Object> gate(@PathVariable String id) {
    var t = repo.get(id); if (t == null) throw new com.x7x24.tenant.WorkspaceService.NotMember();
    Map<String, Object> out = new LinkedHashMap<>();
    var pr = t.context.pr;
    out.put("gate", pr == null ? null : engine.mergeGate(t).toMap());
    out.put("pr", pr == null ? null : Map.of("number", pr.number, "url", String.valueOf(pr.url), "headSha", String.valueOf(pr.headSha), "files", pr.files.size(), "lines", pr.lines, "weightedLines", pr.weightedLines));
    List<Map<String, Object>> hist = new ArrayList<>();
    for (var r : db.rows("SELECT id, tier, head_sha, summary, status, decided_by, decided_at, note, reminded_at, created_at FROM merge_approval WHERE task_id=? ORDER BY created_at DESC, id DESC", id)) {
      Map<String, Object> m = new LinkedHashMap<>(r); m.put("summary", Json.tree(Db.str(r.get("summary")))); hist.add(m);
    }
    out.put("approvals", hist);
    var pending = hist.stream().filter(h -> "pending".equals(h.get("status")) && pr != null && Objects.equals(h.get("head_sha"), pr.headSha)).findFirst().orElse(null);
    out.put("waiting", pending != null && "DELIVERED".equals(t.status));
    out.put("pending", pending);
    return out;
  }

  @PostMapping("/api/tasks/{id}/merge-approval") public Map<String, Object> decide(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(req, "确认合并");
    var t = repo.get(id); if (t == null) throw new com.x7x24.tenant.WorkspaceService.NotMember();
    var r = engine.decideMerge(id, body.path("decision").asText(""), body.path("headSha").asText(null), body.path("note").asText(""), PipelineApiActor.of(req));
    return Map.of("status", r.status(), "taskId", r.taskId(), "headSha", r.headSha());
  }

  @GetMapping("/api/merge-approvals") public List<Map<String, Object>> pending() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (var r : db.rows("SELECT m.task_id, m.tier, m.head_sha, m.created_at, t.title FROM merge_approval m JOIN task t ON t.id=m.task_id WHERE m.status='pending' AND t.status='DELIVERED' ORDER BY m.created_at", new Object[0]))
      if (repo.get((String) r.get("task_id")) != null) out.add(r);
    return out;
  }
}
