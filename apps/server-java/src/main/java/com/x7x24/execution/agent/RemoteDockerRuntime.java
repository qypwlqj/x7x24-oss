package com.x7x24.execution.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/** x7x24-runnerd 客户端（经私有网络访问）。每个请求带时间戳、随机 nonce 与 HMAC 签名。 */
public class RemoteDockerRuntime implements AgentRuntime {
  private final String base; private final String key; private final HttpClient http; private final SecureRandom rnd = new SecureRandom();
  private final java.util.function.LongSupplier clock;
  public RemoteDockerRuntime(String baseUrl, String hmacKey) { this(baseUrl, hmacKey, () -> System.currentTimeMillis() / 1000); }
  RemoteDockerRuntime(String baseUrl, String hmacKey, java.util.function.LongSupplier clock) {
    this.base = baseUrl.replaceAll("/+$", ""); this.key = hmacKey; this.clock = clock;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }
  @Override public String id() { return AgentRuntimeSettings.REMOTE; }

  @Override public Handle start(JobSpec s) {
    var body = new LinkedHashMap<String, Object>();
    body.put("jobKey", s.jobKey()); body.put("repo", s.repo()); body.put("baseRef", s.baseRef()); body.put("cloneToken", s.cloneToken() == null ? "" : s.cloneToken());
    body.put("mode", s.mode()); body.put("prompt", s.prompt()); body.put("model", s.model()); body.put("limits", AgentRuntime.limitsMap(s.limits())); body.put("selfTest", s.selfTest());
    var r = call("POST", "/v1/jobs", Json.str(body));
    return new Handle(id(), r.path("id").asText());
  }
  @Override public Status poll(Handle h) {
    var r = call("GET", "/v1/jobs/" + enc(h.remoteId()), null);
    Result result = null; var res = r.path("result");
    if (res.isObject()) {
      List<FileOut> files = new ArrayList<>(); for (var f : res.path("files")) files.add(new FileOut(f.path("path").asText(), f.path("delete").asBoolean() ? null : f.path("content").asText(), f.path("delete").asBoolean()));
      List<String> untracked = new ArrayList<>(); for (var u : res.path("untracked")) untracked.add(u.asText());
      var u = res.path("usage");
      result = new Result(files, untracked, res.path("summary").asText(""), new Usage(u.path("inputTokens").asLong(), u.path("outputTokens").asLong(), u.path("cacheReadInputTokens").asLong(), res.path("numTurns").asInt()), res.path("headSha").asText(null), res.path("baseSha").asText(null), res.path("observedModel").isTextual() ? res.path("observedModel").asText() : null);
    }
    return new Status(r.path("state").asText("FAILED"), r.hasNonNull("error") ? r.path("error").asText() : null, result);
  }
  @Override public Handle startAcceptance(AcceptanceSpec s) {
    var body = new LinkedHashMap<String, Object>();
    body.put("jobKey", s.jobKey()); body.put("repo", s.repo()); body.put("ref", s.ref()); body.put("cloneToken", s.cloneToken() == null ? "" : s.cloneToken());
    body.put("setup", s.setup()); body.put("start", s.start()); body.put("url", s.url()); body.put("readySec", s.readySec()); body.put("specDir", s.specDir()); body.put("pages", s.pages());
    body.put("limits", AgentRuntime.limitsMap(s.limits()));
    return new Handle(id(), call("POST", "/v1/acceptance", Json.str(body)).path("id").asText());
  }
  @Override public AcceptanceStatus pollAcceptance(Handle h) {
    var r = call("GET", "/v1/jobs/" + enc(h.remoteId()), null); var a = r.path("result"); AcceptanceResult res = null;
    if (a.isObject() && a.has("phase")) {
      List<TestCase> tests = new ArrayList<>(); for (var t : a.path("tests")) tests.add(new TestCase(t.path("title").asText(), t.path("status").asText(), t.hasNonNull("error") ? t.path("error").asText() : null));
      List<Shot> shots = new ArrayList<>(); for (var sh : a.path("shots")) shots.add(new Shot(sh.path("name").asText(), sh.path("pngBase64").asText()));
      Map<String, String> a11y = new LinkedHashMap<>(); a.path("a11y").fields().forEachRemaining(e -> a11y.put(e.getKey(), e.getValue().asText()));
      res = new AcceptanceResult(a.path("phase").asText(), a.path("exitCode").asInt(), tests, a.path("passed").asInt(), a.path("failed").asInt(), shots, a11y, a.path("appLogTail").asText(""));
    }
    return new AcceptanceStatus(r.path("state").asText("FAILED"), r.hasNonNull("error") ? r.path("error").asText() : null, res);
  }
  /** 逐行审查（open-code-review）job：POST /v1/review，结果原样（ocr 的 JSON）放在 result.ocr */
  public Handle startReview(Map<String, Object> body) { return new Handle(id(), call("POST", "/v1/review", Json.str(body)).path("id").asText()); }
  public JsonNode job(Handle h) { return call("GET", "/v1/jobs/" + enc(h.remoteId()), null); }
  @Override public Logs logs(Handle h, long fromSeq) {
    var r = call("GET", "/v1/jobs/" + enc(h.remoteId()) + "/logs?from=" + fromSeq, null);
    List<LogEvent> ev = new ArrayList<>(); for (var e : r.path("events")) ev.add(new LogEvent(e.path("seq").asLong(), e.path("ts").asText(), e.path("kind").asText(), e.path("text").asText()));
    return new Logs(ev, r.path("next").asLong(fromSeq));
  }
  @Override public void cancel(Handle h, String reason) { call("POST", "/v1/jobs/" + enc(h.remoteId()) + "/cancel", Json.str(Map.of("reason", reason == null ? "" : reason))); }

  private static String enc(String s) { if (!s.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("非法 job id"); return s; }

  JsonNode call(String method, String pathAndQuery, String json) {
    byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
    String ts = String.valueOf(clock.getAsLong()); byte[] n = new byte[16]; rnd.nextBytes(n); String nonce = HexFormat.of().formatHex(n);
    String sig = Hmac.sign(key, Hmac.canonical(method, pathAndQuery, ts, nonce, body));
    var b = HttpRequest.newBuilder(URI.create(base + pathAndQuery)).timeout(Duration.ofSeconds(20))
        .header("X-X7-Timestamp", ts).header("X-X7-Nonce", nonce).header("X-X7-Signature", sig).header("Content-Type", "application/json")
        .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
    try {
      var resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) throw new RunnerException(resp.statusCode(), "runner 返回 " + resp.statusCode());
      return Json.tree(resp.body());
    } catch (RunnerException e) { throw e; }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RunnerException(0, "runner 请求被中断"); }
    catch (Exception e) { throw new RunnerException(0, "runner 不可达：" + e.getClass().getSimpleName()); }
  }
  public static class RunnerException extends RuntimeException { public final int status; RunnerException(int status, String m) { super(m); this.status = status; } }
}
