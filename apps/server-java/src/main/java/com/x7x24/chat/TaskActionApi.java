package com.x7x24.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.db.Db;
import com.x7x24.engine.TaskRepo;
import com.x7x24.tenant.Tenant;
import com.x7x24.tenant.WorkspaceFilter;
import com.x7x24.tenant.WorkspaceService;
import com.x7x24.util.Clk;
import com.x7x24.util.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 指挥台 v2 M3：任务会话里的流程操作。所有写操作在服务端按角色再校验；预算 / 治理只给跳转，不在这里改。 */
@RestController
public class TaskActionApi {
  private final TaskActions actions; private final ChatService chat; private final TaskRepo repo; private final Db db; private final Clk clk;
  public TaskActionApi(TaskActions actions, ChatService chat, TaskRepo repo, Db db, Clk clk) { this.actions = actions; this.chat = chat; this.repo = repo; this.db = db; this.clk = clk; }
  static TaskActions.Ctx who(HttpServletRequest r) { return new TaskActions.Ctx(ChatApi.actor(r), WorkspaceFilter.role(r)); }

  /** 阻塞卡 + 待确认的操作 + 当前用户能做什么 */
  @GetMapping("/api/tasks/{id}/actions") public Map<String, Object> state(@PathVariable String id, HttpServletRequest r) {
    Map<String, Object> m = new LinkedHashMap<>(actions.blocked(id)); var w = who(r);
    m.put("pending", actions.pending(id)); m.put("history", actions.history(id));
    m.put("can", Map.of("act", TaskActions.member(w.role()), "admin", WorkspaceService.manager(w.role())));
    var s = db.row("SELECT id FROM chat_session WHERE task_id=? AND workspace_id=? ORDER BY created_at LIMIT 1", id, Tenant.id()); m.put("sessionId", s == null ? null : s.get("id"));
    return m;
  }
  @PostMapping("/api/tasks/{id}/actions") public Map<String, Object> propose(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    return actions.propose(id, b.path("kind").asText(""), b.path("params"), b.path("source").asText("button"), b.path("sessionId").asText(null), who(r));
  }
  @PostMapping("/api/tasks/{id}/actions/{aid}/confirm") public Map<String, Object> confirm(@PathVariable String id, @PathVariable String aid, @RequestBody(required = false) JsonNode b, HttpServletRequest r) {
    return actions.confirm(id, aid, b == null ? null : b.path("confirmText").asText(null), who(r));
  }
  @PostMapping("/api/tasks/{id}/actions/{aid}/cancel") public Map<String, Object> cancel(@PathVariable String id, @PathVariable String aid, HttpServletRequest r) { return actions.cancel(id, aid, who(r)); }
  @GetMapping("/api/task-actions/pending") public List<Map<String, Object>> awaiting(HttpServletRequest r) { return WorkspaceFilter.manager(r) ? actions.awaitingManager() : List.of(); }

  /** 自由文本：只读成员也能用（只会得到回答）；会改流程的只生成待确认的提议 */
  @PostMapping("/api/tasks/{id}/say") public Map<String, Object> say(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    var w = who(r); String text = b.path("text").asText(""); String sid = session(id, w, false);
    var p = actions.parse(id, text); Map<String, Object> out = new LinkedHashMap<>(); out.put("via", p.via());
    if (sid != null) msg(sid, "user", text);
    if (p.kind() != null && TaskActions.member(w.role())) {
      try { out.put("action", actions.propose(id, p.kind(), p.args(), "text", sid, w)); out.put("reply", p.reply() != null ? p.reply() : "我理解为「" + TaskActions.SPECS.get(p.kind()).zh() + "」，确认后才会执行。"); }
      catch (IllegalArgumentException e) { out.put("reply", "没法这样做：" + e.getMessage()); }
    } else out.put("reply", p.kind() != null ? "你是只读成员，只能提问；这个操作需要成员或管理员来做。" : p.reply());
    if (sid != null) msg(sid, "assistant", String.valueOf(out.get("reply")));
    return out;
  }
  /** 没有会话的任务：从任务会话页开一个，绑定到任务（一个任务一个主会话） */
  @PostMapping("/api/tasks/{id}/session") public Map<String, Object> start(@PathVariable String id, HttpServletRequest r) {
    var w = who(r); if (!TaskActions.member(w.role())) throw new WorkspaceService.Forbidden("只读成员不能开会话");
    return Map.of("sessionId", session(id, w, true));
  }
  synchronized String session(String taskId, TaskActions.Ctx w, boolean create) {
    var t = repo.get(taskId); if (t == null) throw new WorkspaceService.NotMember();
    var s = db.row("SELECT id FROM chat_session WHERE task_id=? AND workspace_id=? ORDER BY created_at LIMIT 1", taskId, Tenant.id());
    if (s != null) return (String) s.get("id"); if (!create) return null;
    var c = chat.create(w.login(), t.projectId); String sid = (String) c.get("id");
    db.update("UPDATE chat_session SET task_id=?, status='confirmed', anchor_kind='task', anchor_id=?, title=? WHERE id=? AND workspace_id=?", taskId, taskId, com.x7x24.util.U.ellipsis(t.title, 200), sid, Tenant.id());
    msg(sid, "assistant", "这是任务「" + t.title + "」的会话。可以问进度、原因，也可以直接说怎么处理（比如“重试，别加新依赖”），会先出确认卡。");
    return sid;
  }
  private void msg(String sid, String role, String text) { db.update("INSERT INTO chat_message(session_id,workspace_id,role,content,created_at) VALUES(?,?,?,?,?)", sid, Tenant.id(), role, text, clk.iso()); }

  @ExceptionHandler(ChatService.Conflict.class) ResponseEntity<Map<String, Object>> conflict(RuntimeException e) { return ResponseEntity.status(409).body(Map.of("error", "conflict", "message", e.getMessage())); }
  @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String, Object>> bad(RuntimeException e) { return ResponseEntity.status(400).body(Map.of("error", "bad_request", "message", String.valueOf(e.getMessage()))); }
  @ExceptionHandler(WorkspaceService.Forbidden.class) ResponseEntity<Map<String, Object>> forbidden(RuntimeException e) { return ResponseEntity.status(403).body(Map.of("error", "forbidden", "message", String.valueOf(e.getMessage()))); }
  @ExceptionHandler(WorkspaceService.NotMember.class) ResponseEntity<Map<String, Object>> nf(RuntimeException e) { return ResponseEntity.status(404).body(Map.of("error", "not_found")); }
}
