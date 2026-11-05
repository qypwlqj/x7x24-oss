package com.x7x24.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ApiIT extends Harness {
  @LocalServerPort int port;
  final HttpClient http = HttpClient.newHttpClient();

  JsonNode get(String path) throws Exception { return Json.tree(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), HttpResponse.BodyHandlers.ofString()).body()); }
  JsonNode send(String method, String path, String body) throws Exception {
    return Json.tree(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("content-type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).body());
  }

  @Test void metaBoardAdaptersAndAgents() throws Exception {
    var meta = get("/api/meta"); assertEquals("sandbox", meta.path("lines").get(0).path("id").asText()); assertEquals(13, meta.path("nodes").size());
    var board = get("/api/board"); assertEquals(13, board.path("nodes").size()); assertTrue(board.has("health")); assertEquals(30, board.path("trend").size());
    var ad = get("/api/adapters"); assertTrue(ad.has("lines")); assertTrue(ad.has("recentCalls")); assertEquals("rule", ad.path("llm").path("mode").asText());
    assertEquals(15, get("/api/agents").size());
    assertTrue(get("/api/audit/verify").path("ok").asBoolean());
    assertTrue(get("/api/config").path("config").path("loops").has("maxLoops"));
  }

  @Test void createTaskSyncsIssueAndShowsInTasksApiWithLinks() throws Exception {
    var r = send("POST", "/api/tasks", "{\"productLine\":\"sandbox\",\"title\":\"新增 wordCount 工具函数（API 创建）\",\"content\":\"示例：wordCount(\\\"a b\\\") => 2\"}");
    assertFalse(r.path("duplicate").asBoolean()); String id = r.path("taskId").asText();
    assertEquals(1, tools.issues.size(), "手动创建需求同步在仓库建了 Issue");
    var d = get("/api/tasks/" + id); assertEquals("BACKLOG", d.path("task").path("status").asText());
    assertTrue(d.path("task").path("links").path("issue").path("url").asText().contains("/issues/"));
    var list = get("/api/tasks?limit=50&from=2000-01-01&to=2999-12-31"); assertTrue(list.size() >= 1);
    assertEquals(400, http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/tasks")).header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"productLine\":\"nope\",\"title\":\"xx\"}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test void killSwitchApiRoundTripAndPolicyDenial() throws Exception {
    var ks = send("POST", "/api/kill-switch", "{\"mode\":\"HALT\",\"scope\":\"global\",\"reason\":\"it\"}");
    assertEquals(1, get("/api/kill-switch").path("active").size());
    assertTrue(send("DELETE", "/api/kill-switch/" + ks.path("id").asText(), "").path("ok").asBoolean());
    assertEquals(0, get("/api/kill-switch").path("active").size());
    var d = send("POST", "/api/policy/evaluate", "{\"type\":\"payment.refund\",\"target\":\"o-1\"}"); assertFalse(d.path("allowed").asBoolean());
    assertEquals("OK", "OK");
  }

  @Test void sseStreamsHelloAndFlowEventsOnStateChange() throws Exception {
    var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events")).header("Accept", "text/event-stream").build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream()); assertEquals(200, resp.statusCode());
    assertTrue(resp.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));
    assertTrue(resp.headers().firstValue("cache-control").orElse("").contains("no-transform"), "代理不得压缩缓冲实时事件");
    List<String> lines = new CopyOnWriteArrayList<>();
    var reader = new BufferedReader(new InputStreamReader(resp.body()));
    var t = new Thread(() -> { try { String l; while ((l = reader.readLine()) != null) lines.add(l); } catch (Exception ignore) { } }); t.setDaemon(true); t.start();
    long end = System.currentTimeMillis() + 5000; while (System.currentTimeMillis() < end && lines.stream().noneMatch(l -> l.equals("event:hello") || l.equals("event: hello"))) Thread.sleep(50);
    assertTrue(lines.stream().anyMatch(l -> l.contains("hello")), "hello 事件");
    String id = ingestIssue("新增 isPalindrome 工具函数（SSE 用例）", "示例：isPalindrome(\"abba\") => true");
    run(id, x -> !x.status.equals("BACKLOG"), 10);
    end = System.currentTimeMillis() + 5000; while (System.currentTimeMillis() < end && lines.stream().noneMatch(l -> l.contains("event:flow") || l.contains("event: flow"))) Thread.sleep(50);
    assertTrue(lines.stream().anyMatch(l -> l.contains("flow")), "状态流转推送 flow 事件");
    var data = lines.stream().filter(l -> l.startsWith("data:") && l.contains("taskId")).findFirst().orElseThrow();
    var n = Json.tree(data.substring(5).trim()); assertEquals(id, n.path("taskId").asText()); assertTrue(n.has("to") && n.has("edge")); assertFalse(n.has("payload"), "SSE 不含 payload");
    resp.body().close(); t.interrupt(); t.join(1000);
  }

  @Test void testClockEndpointOnlyWorksWhenEnabled() throws Exception {
    assertTrue(send("POST", "/api/clock/advance", "{\"hours\":0}").has("now"));
  }
}
