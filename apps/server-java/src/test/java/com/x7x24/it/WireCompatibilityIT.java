package com.x7x24.it;

import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Assert real HTTP conversion and canonical bytes across the Boot4 migration. */
@Import(WireCompatibilityIT.Fixture.class)
class WireCompatibilityIT extends Harness {
  @Autowired TestRestTemplate http;
  @LocalServerPort int port;
  record Payload(com.fasterxml.jackson.databind.JsonNode node, List<Integer> values, String absent) {}
  @RestController static class Endpoint {
    @GetMapping("/test/wire") Payload wire() { return new Payload(Json.tree("{\"text\":\"中文\\nquoted\\\"\",\"flag\":true}"), List.of(1,2), null); }
  }
  @TestConfiguration static class Fixture { @Bean Endpoint endpoint() { return new Endpoint(); } }

  @Test void jackson2NodeAndNullContractsAndCanonicalBytesArePreserved() {
    var body = Json.tree(http.getForObject("/test/wire", String.class));
    assertEquals("中文\nquoted\"", body.path("node").path("text").asText());
    assertTrue(body.path("node").path("flag").asBoolean());
    assertEquals(2,body.path("values").path(1).asInt());
    assertTrue(body.has("absent") && body.get("absent").isNull());
    assertFalse(body.path("node").has("nodeType"));
    Map<String,Object> input = new LinkedHashMap<>(); input.put("z",2); input.put("a",List.of(true,"中文\n\""));
    assertEquals("{\"a\":[true,\"中文\\n\\\"\"],\"z\":2}",Json.canonical(input));
  }

  @Test void sseHelloIsFlushedAsAnEventStream() throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/events")).timeout(Duration.ofSeconds(5)).build();
    try (var client=HttpClient.newHttpClient()) {
      var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200,response.statusCode());
      assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
      try (var stream=response.body()) {
        String first=CompletableFuture.supplyAsync(() -> {
          try {
            var reader=new BufferedReader(new InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder(); String line;
            while ((line=reader.readLine())!=null && !line.isEmpty()) b.append(line).append('\n');
            return b.toString();
          } catch (Exception e) { throw new IllegalStateException(e); }
        }).get(5,TimeUnit.SECONDS);
        String frame = "SSE 首帧:\n" + first;
        assertTrue(first.lines().anyMatch(line -> line.equals("event:hello") || line.equals("event: hello")), frame);
        String data = first.lines().filter(line -> line.startsWith("data:"))
            .map(line -> line.substring(5).stripLeading()).collect(Collectors.joining("\n"));
        var hello = assertDoesNotThrow(() -> Json.tree(data), frame);
        assertTrue(hello.path("ok").isBoolean() && hello.path("ok").booleanValue(), "hello.ok 应为 true\n" + frame);
        var heartbeatMs = hello.path("heartbeatMs");
        assertTrue(heartbeatMs.isIntegralNumber() && heartbeatMs.canConvertToLong() && heartbeatMs.longValue() > 0,
            "hello.heartbeatMs 应为正整数\n" + frame);
        var connectionId = hello.path("connectionId");
        assertTrue(connectionId.isTextual() && !connectionId.textValue().isBlank(), "hello.connectionId 应为非空字符串\n" + frame);
      }
    }
  }
}
