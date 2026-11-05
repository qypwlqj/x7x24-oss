package com.x7x24.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.db.Db;
import com.x7x24.engine.TaskRepo;
import com.x7x24.gov.AuditLog;
import com.x7x24.gov.EvidenceStore;
import com.x7x24.util.Json;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 指挥台 v2 · M2：单个任务的结构化事件流（docs/command-center-v2.md 第 6 节）。
 * <ul>
 *   <li>GET /api/tasks/{id}/timeline?after=&lt;seq&gt;&amp;limit= —— 首屏与 3 秒轮询兜底</li>
 *   <li>GET /api/tasks/{id}/stream?after=&lt;seq&gt; —— SSE，断线用 Last-Event-ID（或 after）续传；事件名 ev，id = seq</li>
 * </ul>
 * seq 直接用 task_event.id：只追加、全局单调递增，追加在 AuditLog 里串行，所以按 id 续传不会漏也不会乱序。
 * 只读：任何能看到任务的成员（含只读成员）都能订阅；任务按当前工作区过滤，看不到 = 404。
 * 连接上限：每个任务 3 个、全局 60 个（2 核小机器），超出返回 429，前端退回轮询。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskStream implements org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> {
  public static final int PER_TASK = 3, GLOBAL = 60, PAGE = 200, LOG_MAX = 2000;
  private final Db db; private final TaskRepo tasks; private final EvidenceStore evidence;
  private final Map<String, CopyOnWriteArrayList<SseEmitter>> subs = new ConcurrentHashMap<>();
  private record Connection(String id, long openedAt) {}
  private final Map<SseEmitter, Connection> connections = new ConcurrentHashMap<>();
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskStream.class);
  private final SseHeartbeat heartbeats;
  private java.util.concurrent.ScheduledFuture<?> heartbeatJob;
  private volatile boolean closed;
  public TaskStream(Db db, TaskRepo tasks, EvidenceStore evidence, AuditLog audit, SseHeartbeat heartbeats) { this.db = db; this.tasks = tasks; this.evidence = evidence; this.heartbeats = heartbeats; audit.subscribe(this::onEvent); }
  @jakarta.annotation.PostConstruct public void startHeartbeat() { heartbeatJob = heartbeats.schedule(this::heartbeat); }
  @jakarta.annotation.PreDestroy public void stopHeartbeat() { if (heartbeatJob != null) heartbeatJob.cancel(false); }

  @GetMapping("/{id}/timeline") public ResponseEntity<Object> timeline(@PathVariable String id, @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "200") int limit) {
    if (tasks.get(id) == null) return notFound();
    var rows = since(id, after, Math.max(1, Math.min(PAGE, limit)));
    long seq = rows.isEmpty() ? after : Db.l(rows.get(rows.size() - 1).get("seq"));
    Map<String, Object> o = new LinkedHashMap<>(); o.put("taskId", id); o.put("seq", seq); o.put("events", rows); o.put("more", rows.size() >= Math.min(PAGE, limit));
    return ResponseEntity.ok(o);
  }

  @GetMapping(value = "/{id}/stream", produces = "text/event-stream") public Object stream(@PathVariable String id, @RequestParam(defaultValue = "0") long after,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastId, HttpServletResponse res) {
    if (tasks.get(id) == null) return fail(res, 404, "找不到这条记录，可能已被删除", "not_found");
    if (closed) return fail(res, 503, "服务正在停止，请稍后重新连接", "closing");
    var list = subs.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
    if (list.size() >= PER_TASK || total() >= GLOBAL) { log.info("sse_rejected kind=task task={} reason=capacity_limit active={}", id, total()); return fail(res, 429, "实时连接太多，已改用定时刷新", "too_many_streams"); }
    long from = after; try { if (lastId != null && !lastId.isBlank()) from = Math.max(from, Long.parseLong(lastId.trim())); } catch (NumberFormatException ignore) { /* 用 after */ }
    res.setHeader("X-Accel-Buffering", "no"); res.setHeader("Cache-Control", "no-cache, no-transform");
    var em = new SseEmitter(30 * 60_000L); // 30 分钟后由客户端用 Last-Event-ID 重连，避免长期占用线程
    var connection = new Connection(UUID.randomUUID().toString(), System.currentTimeMillis()); connections.put(em, connection);
    list.add(em); em.onCompletion(() -> remove(id, em, "completed")); em.onTimeout(() -> remove(id, em, "timeout")); em.onError(e -> remove(id, em, "transport_error"));
    log.info("sse_open kind=task connection={} task={} after={}", connection.id(), id, from);
    try {
      em.send(SseEmitter.event().reconnectTime(3000).name("hello").data(Json.str(Map.of("taskId", id, "after", from, "connectionId", connection.id(), "heartbeatMs", heartbeats.intervalMs()))));
      // 补发断线期间的事件（分页直到追平）
      long cur = from; while (true) { var rows = since(id, cur, PAGE); for (var r : rows) send(em, r); if (rows.size() < PAGE) break; cur = Db.l(rows.get(rows.size() - 1).get("seq")); }
    } catch (IOException e) { remove(id, em, "send_failed"); }
    return em;
  }

  /** SSE 地址声明了 text/event-stream，错误时直接写 JSON，避免消息转换器协商失败变成 500 */
  private static Object fail(HttpServletResponse res, int status, String msg, String code) {
    try { res.setStatus(status); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write(Json.str(Map.of("error", msg, "code", code))); } catch (IOException ignore) { /* 客户端已断开 */ }
    return null;
  }
  public int total() { return subs.values().stream().mapToInt(List::size).sum(); }
  private void remove(String id, SseEmitter em, String reason) {
    var c = connections.remove(em);
    var l = subs.get(id); if (l != null) { l.remove(em); if (l.isEmpty()) subs.remove(id, l); }
    if (c != null) log.info("sse_close kind=task connection={} task={} reason={} lifetimeMs={}", c.id(), id, reason, System.currentTimeMillis() - c.openedAt());
  }
  private static ResponseEntity<Object> notFound() { return ResponseEntity.status(404).body(Map.of("error", "找不到这条记录，可能已被删除", "code", "not_found")); }

  private void onEvent(Map<String, Object> r) {
    Object tid = r.get("task_id"); if (tid == null) return; var l = subs.get(String.valueOf(tid)); if (l == null || l.isEmpty()) return;
    var env = envelope(r); for (var em : l) { try { send(em, env); } catch (Exception e) { remove(String.valueOf(tid), em, "send_failed"); } }
  }
  private static void send(SseEmitter em, Map<String, Object> env) throws IOException { em.send(SseEmitter.event().id(String.valueOf(env.get("seq"))).name("ev").data(Json.str(env))); }

  public void heartbeat() {
    if (closed) return;
    for (var e : subs.entrySet()) for (var em : e.getValue()) {
      var c = connections.get(em); if (c == null) continue;
      try { em.send(SseEmitter.event().name("heartbeat").data(Json.str(Map.of("at", System.currentTimeMillis(), "connectionId", c.id())))); }
      catch (Exception ex) { remove(e.getKey(), em, "heartbeat_send_failed"); }
    }
  }
  @Override public void onApplicationEvent(org.springframework.context.event.ContextClosedEvent event) {
    closed = true; stopHeartbeat(); for (var e : subs.entrySet()) for (var em : e.getValue()) { remove(e.getKey(), em, "server_shutdown"); try { em.complete(); } catch (Exception ignore) { /* ignore */ } } subs.clear();
  }

  List<Map<String, Object>> since(String taskId, long after, int limit) {
    String ws = com.x7x24.tenant.Tenant.readFilter();
    var rows = ws == null ? db.rows("SELECT * FROM task_event WHERE task_id=? AND id>? ORDER BY id LIMIT ?", taskId, after, limit)
        : db.rows("SELECT * FROM task_event WHERE task_id=? AND id>? AND workspace_id=? ORDER BY id LIMIT ?", taskId, after, ws, limit);
    List<Map<String, Object>> out = new ArrayList<>(); for (var r : rows) out.add(envelope(r)); return out;
  }

  /** 统一信封：{seq, taskId, type, nodeId, iteration, at, actor, from, to, edge, data, evidence?}；文案由前端按 type 生成 */
  Map<String, Object> envelope(Map<String, Object> r) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("seq", Db.l(r.get("id"))); e.put("taskId", r.get("task_id")); e.put("type", r.get("event_type"));
    e.put("iteration", r.get("iteration_seq")); e.put("at", r.get("occurred_at")); e.put("actor", r.get("actor")); e.put("model", r.get("model"));
    e.put("from", r.get("from_status")); e.put("to", r.get("to_status")); e.put("edge", r.get("edge"));
    JsonNode p = r.get("payload") instanceof JsonNode j ? j : r.get("payload") instanceof String s ? Json.tree(s) : null;
    // 重启前未闭合的调用只能作为历史信息展示，不再声称仍在运行；不改写审计记录。
    if (("MODEL_CALL".equals(r.get("event_type")) || "CODE_REVIEW_PROGRESS".equals(r.get("event_type"))) && p != null && p.isObject() && p.hasNonNull("runtimeId")
        && !com.x7x24.llm.ModelCalls.RUNTIME_ID.equals(p.path("runtimeId").asText())) {
      p = p.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) p).put("stale", true);
    }
    e.put("data", p);
    if ("MODEL_CALL".equals(r.get("event_type")) && p != null && p.hasNonNull("model")) e.put("model", p.path("model").asText());
    String node = p == null ? null : p.hasNonNull("node") ? p.path("node").asText() : p.hasNonNull("stage") ? p.path("stage").asText() : null;
    if (node == null && "STATUS_CHANGED".equals(r.get("event_type"))) node = (String) r.get("to_status");
    if ("EVIDENCE_ADDED".equals(r.get("event_type")) && p != null && p.hasNonNull("id")) {
      var ev = evidence.get(p.path("id").asText());
      if (ev != null) { if (node == null) node = (String) ev.get("stage"); e.put("evidence", Map.of("id", ev.get("id"), "kind", ev.get("kind"), "stage", String.valueOf(ev.get("stage")), "content", trim(Json.tree((String) ev.get("content"))))); }
    }
    e.put("nodeId", node);
    return e;
  }
  /** 流里只带摘要：长日志截断（完整内容在任务详情 / 节点页） */
  static JsonNode trim(JsonNode c) {
    if (c == null || !c.isObject()) return c;
    var o = ((com.fasterxml.jackson.databind.node.ObjectNode) c).deepCopy();
    for (String k : List.of("log", "content", "output", "stdout", "stderr")) if (o.path(k).isTextual() && o.path(k).asText().length() > LOG_MAX) { String s = o.path(k).asText(); o.put(k, "…" + s.substring(s.length() - LOG_MAX)); o.put(k + "Truncated", true); }
    if (o.path("files").isArray() && o.path("files").size() > 50) { var a = o.putArray("files"); c.path("files").forEach(f -> { if (a.size() < 50) a.add(f); }); o.put("filesTruncated", c.path("files").size()); }
    return o;
  }
}
