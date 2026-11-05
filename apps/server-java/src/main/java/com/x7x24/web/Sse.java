package com.x7x24.web;

import com.x7x24.gov.AuditLog;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.x7x24.util.Json;

/** /api/events：只推状态流转（event: flow），不含 payload。心跳 15s。 */
@Component
public class Sse implements org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> {
  private volatile boolean closed;
  private final com.x7x24.engine.TaskRepo tasks;
  private final CopyOnWriteArrayList<SseEmitter> clients = new CopyOnWriteArrayList<>();
  private record Connection(String id, long openedAt) {}
  private final java.util.Map<SseEmitter, Connection> connections = new java.util.concurrent.ConcurrentHashMap<>();
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Sse.class);
  private final SseHeartbeat heartbeats;
  private java.util.concurrent.ScheduledFuture<?> heartbeatJob;
  /** 每个连接所属工作区：只推送本工作区的事件 */
  private final java.util.Map<SseEmitter, String> wsOf = new java.util.concurrent.ConcurrentHashMap<>();
  public Sse(AuditLog audit, com.x7x24.engine.TaskRepo tasks, SseHeartbeat heartbeats) { this.tasks = tasks; this.heartbeats = heartbeats; audit.subscribe(this::onEvent); }
  @jakarta.annotation.PostConstruct public void startHeartbeat() { heartbeatJob = heartbeats.schedule(this::ping); }
  @jakarta.annotation.PreDestroy public void stopHeartbeat() { if (heartbeatJob != null) heartbeatJob.cancel(false); }

  public synchronized SseEmitter open() {
    if (closed) throw new IllegalStateException("服务正在停止，请稍后重新连接");
    var em = new SseEmitter(0L); String ws = com.x7x24.tenant.Tenant.readFilter(); if (ws != null) wsOf.put(em, ws);
    var c = new Connection(java.util.UUID.randomUUID().toString(), System.currentTimeMillis()); connections.put(em, c);
    clients.add(em); em.onCompletion(() -> remove(em, "completed")); em.onTimeout(() -> remove(em, "timeout")); em.onError(e -> remove(em, "transport_error"));
    log.info("sse_open kind=global connection={}", c.id());
    try { em.send(SseEmitter.event().reconnectTime(2000).name("hello").data(Json.str(Map.of("ok", true, "connectionId", c.id(), "heartbeatMs", heartbeats.intervalMs())))); } catch (IOException e) { remove(em, "send_failed"); }
    return em;
  }
  public int clients() { return clients.size(); }
  private void remove(SseEmitter client, String reason) {
    clients.remove(client); wsOf.remove(client); var c = connections.remove(client);
    if (c != null) log.info("sse_close kind=global connection={} reason={} lifetimeMs={}", c.id(), reason, System.currentTimeMillis() - c.openedAt());
  }
  /** ContextClosedEvent precedes web-server graceful shutdown: do not leave infinite SSE requests active. */
  @Override public synchronized void onApplicationEvent(org.springframework.context.event.ContextClosedEvent event) {
    closed = true;
    stopHeartbeat(); for (var client : clients) { remove(client, "server_shutdown"); client.complete(); }
  }


  private void onEvent(Map<String, Object> r) {
    String type = String.valueOf(r.get("event_type"));
    if (BOARD_EVENTS.contains(type)) { board(r, type); return; }
    if (!"STATUS_CHANGED".equals(type) || r.get("to_status") == null) return;
    Map<String, Object> e = new java.util.LinkedHashMap<>();
    e.put("id", r.get("id")); e.put("taskId", r.get("task_id")); e.put("from", r.get("from_status")); e.put("to", r.get("to_status")); e.put("edge", r.get("edge")); e.put("at", r.get("occurred_at"));
    var task = tasks.get(String.valueOf(r.get("task_id")));
    if (task != null) { e.put("projectId", task.projectId); e.put("productLine", task.productLine); }
    else if (r.get("payload") instanceof com.fasterxml.jackson.databind.JsonNode payload) {
      if (payload.has("projectId")) e.put("projectId", payload.path("projectId").asText());
      if (payload.has("productLine")) e.put("productLine", payload.path("productLine").asText());
    }
    String data = Json.str(e);
    for (var c : clients) { if (!sees(c, r)) continue; try { c.send(SseEmitter.event().id(String.valueOf(r.get("id"))).name("flow").data(data)); } catch (Exception ex) { remove(c, "send_failed"); } }
  }

  /** 不改变状态但影响看板数字的操作（删除 / 编辑 / 暂停 / 重启 / 恢复）：推送 board 事件让前端立即刷新 */
  static final java.util.Set<String> BOARD_EVENTS = java.util.Set.of("TASK_DELETED", "TASK_EDITED", "TASK_PAUSED", "TASK_RESTARTED", "TASK_RESUMED", "SPLIT_PART_COMMITTED");
  private void board(Map<String, Object> r, String type) {
    Map<String, Object> e = new java.util.LinkedHashMap<>(); e.put("taskId", r.get("task_id")); e.put("type", type); e.put("at", r.get("occurred_at"));
    var task = r.get("task_id") == null ? null : tasks.get(String.valueOf(r.get("task_id")));
    if (task != null) { e.put("projectId", task.projectId); e.put("productLine", task.productLine); }
    String data = Json.str(e);
    for (var c : clients) { if (!sees(c, r)) continue; try { c.send(SseEmitter.event().id(String.valueOf(r.get("id"))).name("board").data(data)); } catch (Exception ex) { remove(c, "send_failed"); } }
  }

  /** agent job 日志（event: agent-log）。只推本工作区；文本已在 runner 侧脱敏。 */
  public void agentLog(String workspaceId, String taskId, String jobId, com.x7x24.execution.agent.AgentRuntime.LogEvent ev) {
    Map<String, Object> e = new java.util.LinkedHashMap<>(); e.put("jobId", jobId); e.put("taskId", taskId); e.put("seq", ev.seq()); e.put("kind", ev.kind()); e.put("text", ev.text()); e.put("at", ev.ts());
    String data = Json.str(e); Map<String, Object> scope = Map.of("workspace_id", workspaceId == null ? "" : workspaceId);
    for (var c : clients) { if (!sees(c, scope)) continue; try { c.send(SseEmitter.event().name("agent-log").data(data)); } catch (Exception ex) { remove(c, "send_failed"); } }
  }

  private boolean sees(SseEmitter c, Map<String, Object> r) { String ws = wsOf.get(c); return ws == null || ws.equals(r.get("workspace_id")); }
  public void ping() {
    if (closed) return;
    for (var c : clients) { var connection = connections.get(c); if (connection == null) continue;
      try { c.send(SseEmitter.event().name("heartbeat").data(Json.str(Map.of("at", System.currentTimeMillis(), "connectionId", connection.id())))); }
      catch (Exception ex) { remove(c, "heartbeat_send_failed"); }
    }
  }
}
