package com.x7x24.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.util.Json;
import com.x7x24.web.TaskStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** 指挥台 v2 M2：任务时间线 / SSE 续传 / 连接上限 / 404 */
class TaskStreamIT extends Harness {
  @LocalServerPort int port; @Autowired TaskStream stream;
  @Autowired com.x7x24.llm.ModelCalls modelCalls;
  @Autowired com.x7x24.web.Sse globalStream;
  @Autowired org.springframework.scheduling.TaskScheduler businessScheduler;
  final HttpClient http = HttpClient.newHttpClient();
  URI u(String p) { return URI.create("http://localhost:" + port + p); }
  JsonNode get(String p) throws Exception { return Json.tree(http.send(HttpRequest.newBuilder(u(p)).build(), HttpResponse.BodyHandlers.ofString()).body()); }
  int code(String p) throws Exception { return http.send(HttpRequest.newBuilder(u(p)).build(), HttpResponse.BodyHandlers.discarding()).statusCode(); }

  @Test void heartbeatsContinueWhileTheBusinessSchedulerIsBlocked() throws Exception {
    String id = ingestIssue("心跳隔离 " + UUID.randomUUID(), PipelineIT.SLUG);
    var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
    var received = new java.util.concurrent.CountDownLatch(1);
    var blocked = businessScheduler.schedule(() -> { entered.countDown(); try { release.await(25, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }, java.time.Instant.now());
    HttpResponse<java.io.InputStream> response = null;
    try {
      assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
      response = http.send(HttpRequest.newBuilder(u("/api/tasks/" + id + "/stream")).build(), HttpResponse.BodyHandlers.ofInputStream());
      var input = response.body();
      var reader = new Thread(() -> { try { var lines = new BufferedReader(new InputStreamReader(input)); String line; while ((line = lines.readLine()) != null) if (line.startsWith("event:heartbeat") || line.startsWith("event: heartbeat") || line.startsWith(":ping")) received.countDown(); } catch (Exception ignored) {} });
      reader.setDaemon(true); reader.start();
      assertTrue(received.await(17, java.util.concurrent.TimeUnit.SECONDS), "业务调度阻塞时仍须在 15 秒周期内收到心跳");
    } finally { release.countDown(); if (blocked != null) blocked.cancel(false); if (response != null) response.body().close(); }
  }

  /** 真实 Next.js rewrite 的 90 秒静默连接验证，显式启用以免拖慢日常测试。 */
  @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "sse.proxy-smoke", matches = "true")
  void namedHeartbeatsSurviveNextProxy() throws Exception {
    String id = ingestIssue("代理心跳 " + UUID.randomUUID(), PipelineIT.SLUG);
    int proxyPort; try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { proxyPort = socket.getLocalPort(); }
    var web = java.nio.file.Path.of("../web").toAbsolutePath().normalize();
    var output = java.nio.file.Files.createTempFile("x7x24-next-sse-", ".log");
    var agentRules = web.resolve("AGENTS.md");
    byte[] originalRules = java.nio.file.Files.exists(agentRules) ? java.nio.file.Files.readAllBytes(agentRules) : null;
    var builder = new ProcessBuilder("node", web.resolve("../../node_modules/next/dist/bin/next").normalize().toString(), "dev", "--hostname", "127.0.0.1", "--port", String.valueOf(proxyPort)).directory(web.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
    builder.environment().put("API_PROXY_TARGET", "http://127.0.0.1:" + port);
    builder.environment().put("NEXT_PUBLIC_BASE_PATH", ""); builder.environment().put("NEXT_PUBLIC_API_BASE", "");
    Process next = builder.start(); HttpResponse<java.io.InputStream> response = null;
    var proxyHttp = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(2)).build();
    var release = new java.util.concurrent.CountDownLatch(1);
    try {
      URI base = URI.create("http://127.0.0.1:" + proxyPort); boolean ready = false; String last = "未响应";
      long startup = System.currentTimeMillis() + 45000;
      while (System.currentTimeMillis() < startup && next.isAlive()) {
        try { int status = proxyHttp.send(HttpRequest.newBuilder(base.resolve("/api/health")).timeout(java.time.Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding()).statusCode(); last = "HTTP " + status; if (status == 200) { ready = true; break; } } catch (Exception e) { last = e.getClass().getSimpleName(); }
        Thread.sleep(200);
      }
      assertTrue(ready, "Next.js 代理未就绪（" + last + "），日志：" + output);
      response = proxyHttp.send(HttpRequest.newBuilder(base.resolve("/api/tasks/" + id + "/stream")).timeout(java.time.Duration.ofSeconds(110)).build(), HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, response.statusCode());
      var input = response.body(); var beats = new java.util.concurrent.atomic.AtomicInteger();
      var readFailure = new java.util.concurrent.atomic.AtomicReference<Exception>();
      var reader = new Thread(() -> { try { var lines = new BufferedReader(new InputStreamReader(input)); String line; while ((line = lines.readLine()) != null) if (line.startsWith("event:heartbeat") || line.startsWith("event: heartbeat")) beats.incrementAndGet(); } catch (Exception e) { readFailure.set(e); } });
      reader.setDaemon(true); reader.start();
      var entered = new java.util.concurrent.CountDownLatch(1);
      businessScheduler.schedule(() -> { entered.countDown(); try { release.await(100, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }, java.time.Instant.now());
      assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
      long until = System.currentTimeMillis() + 90000;
      while (System.currentTimeMillis() < until && reader.isAlive()) Thread.sleep(200);
      assertTrue(reader.isAlive(), "90 秒内代理连接已关闭：" + readFailure.get());
      assertTrue(beats.get() >= 5, "90 秒内至少需要 5 次未缓冲心跳，实际 " + beats.get());
    } finally {
      release.countDown(); if (response != null) response.body().close();
      next.destroy(); if (!next.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) next.destroyForcibly();
      if (originalRules == null) java.nio.file.Files.deleteIfExists(agentRules); else java.nio.file.Files.write(agentRules, originalRules);
    }
  }

  @Test void modelLifecycleReplaysFullNamesAndMarksUnfinishedOldRuntimeCalls() throws Exception {
    String id = ingestIssue("模型流 " + UUID.randomUUID(), PipelineIT.SLUG);
    var task = repo.get(id); task.currentNode = "DEV";
    String model = "provider/" + "long-name-".repeat(15);
    var session = modelCalls.remote(task, null, "model-stream-" + UUID.randomUUID());
    session.reported(model);
    var first = get("/api/tasks/" + id + "/timeline");
    var running = java.util.stream.StreamSupport.stream(first.path("events").spliterator(), false).filter(e -> "MODEL_CALL".equals(e.path("type").asText()) && "RUNNING".equals(e.path("data").path("state").asText())).findFirst().orElseThrow();
    assertEquals(model, running.path("model").asText()); assertFalse(running.path("data").path("stale").asBoolean());
    session.finish("SUCCEEDED");
    var next = get("/api/tasks/" + id + "/timeline?after=" + first.path("seq").asLong());
    assertEquals("SUCCEEDED", next.path("events").get(0).path("data").path("state").asText());
    audit.task(id, 1, "MODEL_CALL", "test", Map.of("runtimeId", "previous-process", "node", "DEV", "state", "RUNNING", "model", "old-model"), "old-model:" + id);
    var replay = get("/api/tasks/" + id + "/timeline?after=" + next.path("seq").asLong());
    assertTrue(replay.path("events").get(0).path("data").path("stale").asBoolean());
    assertFalse(db.row("SELECT payload FROM task_event WHERE task_id=? AND idempotency_key=?", id, "old-model:" + id).get("payload").toString().contains("stale"), "audit remains unchanged");
    audit.task(id, 1, "CODE_REVIEW_PROGRESS", "test", Map.of("runtimeId", "previous-process", "node", "CODE_REVIEW", "state", "REVIEWING"), "old-review:" + id);
    var progress = get("/api/tasks/" + id + "/timeline?after=" + replay.path("seq").asLong());
    assertTrue(progress.path("events").get(0).path("data").path("stale").asBoolean(), "重启前分组也必须标为待确认");
  }

  @Test void timelinePagesBySeqAndCarriesEvidence() throws Exception {
    String id = ingestIssue("新增 clamp 工具函数（时间线用例）", "示例：clamp(5,0,3) => 3");
    run(id, x -> !x.status.equals("BACKLOG"), 20);
    var all = get("/api/tasks/" + id + "/timeline"); var evs = all.path("events"); assertTrue(evs.size() >= 2, "有事件");
    long prev = 0; for (var e : evs) { long s = e.path("seq").asLong(); assertTrue(s > prev, "seq 递增"); prev = s; assertEquals(id, e.path("taskId").asText()); assertTrue(e.has("type")); }
    assertEquals(prev, all.path("seq").asLong());
    long mid = evs.get(0).path("seq").asLong(); var rest = get("/api/tasks/" + id + "/timeline?after=" + mid);
    assertEquals(evs.size() - 1, rest.path("events").size(), "after 之后的事件"); assertEquals(0, get("/api/tasks/" + id + "/timeline?after=" + prev).path("events").size());
    boolean status = false; for (var e : evs) if ("STATUS_CHANGED".equals(e.path("type").asText())) { status = true; assertEquals(e.path("to").asText(), e.path("nodeId").asText()); }
    assertTrue(status);
    for (var e : evs) if ("EVIDENCE_ADDED".equals(e.path("type").asText())) assertTrue(e.path("evidence").has("kind"), "证据事件带摘要");
    assertEquals(404, code("/api/tasks/nope/timeline")); assertEquals(404, code("/api/tasks/nope/stream"));
  }

  @Test void namedHeartbeatsAreControlFramesOnBothStreams() throws Exception {
    String id = ingestIssue("心跳协议 " + UUID.randomUUID(), PipelineIT.SLUG);
    long count = db.count("SELECT COUNT(*) FROM task_event WHERE task_id=?", id);
    for (String path : List.of("/api/tasks/" + id + "/stream", "/api/events")) {
      var response = http.send(HttpRequest.newBuilder(u(path)).build(), HttpResponse.BodyHandlers.ofInputStream());
      List<String> lines = new CopyOnWriteArrayList<>(); var input = response.body();
      var reader = new Thread(() -> { try { var r = new BufferedReader(new InputStreamReader(input)); String line; while ((line = r.readLine()) != null) lines.add(line); } catch (Exception ignored) {} });
      reader.setDaemon(true); reader.start();
      try {
        waitFor(lines, "heartbeatMs");
        var hello = lines.stream().filter(l -> l.startsWith("data:") && l.contains("heartbeatMs")).findFirst().orElseThrow();
        var body = Json.tree(hello.substring(5).trim()); assertEquals(15000, body.path("heartbeatMs").asLong()); assertTrue(body.hasNonNull("connectionId"));
        if (path.endsWith("/events")) globalStream.ping(); else stream.heartbeat();
        waitFor(lines, "event:heartbeat");
        int at = -1; for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("event:heartbeat")) { at = i; break; }
        assertTrue(at >= 0); assertTrue(lines.subList(at, lines.size()).stream().noneMatch(l -> l.startsWith("id:")), "心跳不推进业务事件序号");
      } finally { input.close(); reader.interrupt(); }
    }
    assertEquals(count, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=?", id), "心跳不写入任务审计事件");
  }

  @Test void sseResumesFromLastEventIdAndPushesLive() throws Exception {
    String id = ingestIssue("新增 sum 工具函数（流用例）", "示例：sum([1,2]) => 3");
    long first = get("/api/tasks/" + id + "/timeline").path("events").get(0).path("seq").asLong();
    var resp = http.send(HttpRequest.newBuilder(u("/api/tasks/" + id + "/stream")).header("Accept", "text/event-stream").header("Last-Event-ID", String.valueOf(first)).build(), HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, resp.statusCode()); assertTrue(resp.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));
    assertTrue(resp.headers().firstValue("cache-control").orElse("").contains("no-transform"), "代理不得压缩缓冲实时事件");
    assertEquals("no", resp.headers().firstValue("x-accel-buffering").orElse(""));
    List<String> lines = new CopyOnWriteArrayList<>(); var reader = new BufferedReader(new InputStreamReader(resp.body()));
    var t = new Thread(() -> { try { String l; while ((l = reader.readLine()) != null) lines.add(l); } catch (Exception ignore) { } }); t.setDaemon(true); t.start();
    waitFor(lines, "hello");
    run(id, x -> !x.status.equals("BACKLOG"), 20);
    long end = System.currentTimeMillis() + 5000; while (System.currentTimeMillis() < end && lines.stream().noneMatch(l -> l.contains("STATUS_CHANGED"))) Thread.sleep(50);
    var seqs = lines.stream().filter(l -> l.startsWith("data:") && l.contains("\"seq\"")).map(l -> Json.tree(l.substring(5).trim()).path("seq").asLong()).toList();
    assertFalse(seqs.isEmpty()); assertTrue(seqs.stream().allMatch(s -> s > first), "从 Last-Event-ID 之后续传，不重发");
    assertTrue(lines.stream().anyMatch(l -> l.contains("STATUS_CHANGED")), "实时推送状态变化");
    resp.body().close(); t.interrupt(); t.join(1000);
  }

  @Test void perTaskConnectionLimitReturns429() throws Exception {
    String id = ingestIssue("新增 max 工具函数（连接上限用例）", "示例：max([1,2]) => 2");
    List<HttpResponse<java.io.InputStream>> open = new ArrayList<>();
    for (int i = 0; i < TaskStream.PER_TASK; i++) open.add(http.send(HttpRequest.newBuilder(u("/api/tasks/" + id + "/stream")).build(), HttpResponse.BodyHandlers.ofInputStream()));
    for (var r : open) assertEquals(200, r.statusCode());
    long end = System.currentTimeMillis() + 3000; while (System.currentTimeMillis() < end && stream.total() < TaskStream.PER_TASK) Thread.sleep(20);
    assertEquals(429, http.send(HttpRequest.newBuilder(u("/api/tasks/" + id + "/stream")).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
    for (var r : open) r.body().close();
  }

  @Test void longLogsAreTrimmedInEnvelope() {
    var big = "x".repeat(TaskStream.LOG_MAX + 500);
    var out = com.x7x24.web.TaskStream.class; assertNotNull(out);
    var j = Json.tree(Json.str(Map.of("log", big, "state", "failed")));
    var trimmed = invokeTrim(j); assertTrue(trimmed.path("log").asText().length() <= TaskStream.LOG_MAX + 1); assertTrue(trimmed.path("logTruncated").asBoolean());
  }
  static JsonNode invokeTrim(JsonNode j) { try { var m = TaskStream.class.getDeclaredMethod("trim", JsonNode.class); m.setAccessible(true); return (JsonNode) m.invoke(null, j); } catch (Exception e) { throw new RuntimeException(e); } }
  static void waitFor(List<String> lines, String s) throws InterruptedException { long end = System.currentTimeMillis() + 5000; while (System.currentTimeMillis() < end && lines.stream().noneMatch(l -> l.contains(s))) Thread.sleep(50); }
}
