package com.x7x24.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.util.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * 最小 GitHub REST 客户端（JDK HttpClient）：重试 + 限流退避 + 出口白名单 + 调用日志 + 凭据脱敏。
 * token 只存在于内存与 Authorization 头；不进入日志/错误/审计。跨主机重定向（如日志下载）不转发 Authorization。
 */
public class GitHubClient {
  public static class GitHubException extends RuntimeException {
    public final int status; public final String path;
    public GitHubException(int status, String msg, String path) { super(Redactor.redact("GitHub " + status + " " + path + ": " + msg)); this.status = status; this.path = path; }
  }
  public static class OutcomeUnknown extends GitHubException { public OutcomeUnknown(int s, String m, String p) { super(s, "OUTCOME_UNKNOWN: " + m, p); } }
  public static class RateLimited extends GitHubException { public RateLimited(int s, String m, String p) { super(s, m, p); } }
  public record Res(int status, JsonNode data, String text, HttpHeaders headers) {}
  public static class Rate { public Integer limit, remaining; public String resetAt, lastRetryAt, lastError; public int retries; public long throttledMs; public Integer lastStatus; }

  private final String baseUrl; private final java.util.function.Supplier<String> token; private final HttpClient http; private final CallLog calls; private final Consumer<String> guard; private final int maxRetries; private final long maxWaitMs;
  private final java.util.function.LongConsumer sleeper; public final Rate rate = new Rate(); private final String line;
  private java.util.function.IntConsumer observer = status -> {};
  private java.util.function.BooleanSupplier refreshAuthorization = () -> false;
  public GitHubClient authorization(java.util.function.IntConsumer observer, java.util.function.BooleanSupplier refresh) { this.observer = observer; this.refreshAuthorization = refresh; return this; }

  public GitHubClient(String baseUrl, String token, CallLog calls, Consumer<String> guard, int maxRetries, long maxWaitMs, java.util.function.LongConsumer sleeper, String line) {
    this(baseUrl, () -> token, calls, guard, maxRetries, maxWaitMs, sleeper, line);
  }
  /** 令牌由 Supplier 在每次请求时提供（运行时可变，无需重启） */
  public GitHubClient(String baseUrl, java.util.function.Supplier<String> token, CallLog calls, Consumer<String> guard, int maxRetries, long maxWaitMs, java.util.function.LongConsumer sleeper, String line) {
    this.baseUrl = baseUrl.replaceAll("/+$", ""); this.token = token; this.calls = calls; this.guard = guard; this.maxRetries = maxRetries; this.maxWaitMs = maxWaitMs; this.line = line;
    this.sleeper = sleeper != null ? sleeper : ms -> { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
  }
  public String host() { return URI.create(baseUrl).getHost() + (URI.create(baseUrl).getPort() > 0 ? ":" + URI.create(baseUrl).getPort() : ""); }
  public static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

  public Res get(String path, String op, String adapter) { return request("GET", path, null, op, adapter, null, false, false); }
  public Res get404(String path, String op, String adapter) { return request("GET", path, null, op, adapter, null, true, false); }
  public Res send(String method, String path, Object body, String op, String adapter, String key) { return request(method, path, body, op, adapter, key, false, false); }
  public Res text(String path, String op, String adapter) { return request("GET", path, null, op, adapter, null, false, true); }

  /** Bounded complete array enumeration. Never follows an arbitrary Link URL. */
  public JsonNode array(String path, String op, String adapter) {
    var out = Json.M.createArrayNode(); String separator = path.contains("?") ? "&" : "?";
    for (int page = 1; page <= 100; page++) {
      var r = get(path + separator + "per_page=100&page=" + page, op, adapter);
      if (r.data() == null || !r.data().isArray()) throw new GitHubException(r.status(), "Incomplete array response", path);
      r.data().forEach(out::add);
      boolean next = r.headers().firstValue("link").orElse("").contains("rel=\"next\"");
      if (!next && r.data().size() < 100) return out;
    }
    throw new GitHubException(0, "Pagination completeness not proven", path);
  }

  /** Paginated object envelopes (Actions jobs/runs and commit check runs). */
  public JsonNode objectArray(String path,String field,String op,String adapter) {
    var out=Json.M.createArrayNode();String separator=path.contains("?")?"&":"?";
    for(int page=1;page<=100;page++){
      var r=get(path+separator+"per_page=100&page="+page,op,adapter);
      if(r.data()==null || !r.data().path(field).isArray())throw new GitHubException(r.status(),"无法核验分页检查清单",path);
      var entries=r.data().path(field);entries.forEach(out::add);
      boolean next=r.headers().firstValue("link").orElse("").contains("rel=\"next\"");
      if(!next && entries.size()<100){
        if(r.data().has("total_count") && out.size()<r.data().path("total_count").asInt())throw new GitHubException(r.status(),"分页结果少于声明总数，检查证据不完整",path);
        return out;
      }
    }
    throw new GitHubException(0,"分页完整性无法确认",path);
  }

  public Res request(String method, String path, Object body, String op, String adapter, String key, boolean ok404, boolean asText) {
    guard.accept(host());
    int attempt = 0; boolean refreshed = false;
    for (;;) {
      proactiveThrottle(path);
      long t0 = System.currentTimeMillis(); HttpResponse<String> res = null; Exception net = null;
      try { res = exec(method, URI.create(baseUrl + path), body, true, 0); }
      catch (GitHubException e) { observer.accept(e.status); throw e; }
      catch (Exception e) { net = e; }
      long dur = System.currentTimeMillis() - t0;
      if (res != null) readRate(res.headers());
      int status = res == null ? 0 : res.statusCode(); String text = res == null ? "" : res.body() == null ? "" : res.body();
      JsonNode data = null; if (!asText && !text.isBlank()) { try { data = Json.M.readTree(text); } catch (Exception ignore) { /* plain text */ } }
      rate.lastStatus = status == 0 ? null : status;
      Long retryWait = res == null ? Long.valueOf(Math.min(500L << attempt, 8000)) : retryDelay(res, status, data, attempt);
      boolean ok = res != null && status < 400 || (ok404 && status == 404);
      calls.add(adapter, op, method, path, status, ok, false, dur, key, line, net != null ? "network: " + Redactor.redact(net) : status >= 400 ? errMsg(data, text) : null);
      observer.accept(status == 403 && retryWait != null ? 429 : status);
      // Only reads are replayed after authorization recovery. Writes retain their existing reconciliation semantics.
      if (status == 401 && method.equals("GET") && !refreshed) { refreshed = true; if (refreshAuthorization.getAsBoolean()) continue; }
      if (res != null && status < 400) return new Res(status, data, text, res.headers());
      if (res != null && ok404 && status == 404) return new Res(status, null, text, res.headers());
      if (!method.equals("GET") && (res == null || status >= 500)) throw new OutcomeUnknown(status, "write requires reconciliation", path);
      if (method.equals("GET") && retryWait != null && attempt < maxRetries) {
        if (retryWait > maxWaitMs) { rate.lastError = "限流等待超过上限"; throw new RateLimited(status, "限流，需等待 " + retryWait / 1000 + "s，超过上限 " + maxWaitMs / 1000 + "s", path); }
        attempt++; rate.retries++; rate.lastRetryAt = Instant.now().toString(); rate.throttledMs += retryWait; sleeper.accept(retryWait); continue;
      }
      String msg = net != null ? "network error: " + Redactor.redact(net) : errMsg(data, text);
      rate.lastError = Redactor.redact(status + " " + msg);
      if (rate.lastError.length() > 200) rate.lastError = rate.lastError.substring(0, 200);
      if (status == 429 || (status == 403 && msg.toLowerCase().contains("rate limit"))) throw new RateLimited(status, msg, path);
      throw new GitHubException(status, msg, path);
    }
  }

  private HttpResponse<String> exec(String method, URI uri, Object body, boolean auth, int hop) throws Exception {
    var b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "7x24-agent");
    if (auth) { String t = token.get(); if (t == null || t.isBlank()) throw new GitHubException(401, "未连接 GitHub（没有可用令牌）", uri.getPath()); Redactor.register(t); b.header("Authorization", "Bearer " + t); }
    if (body != null) { b.header("Content-Type", "application/json"); b.method(method, HttpRequest.BodyPublishers.ofString(Json.str(body))); }
    else b.method(method, HttpRequest.BodyPublishers.noBody());
    var res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    int s = res.statusCode();
    if ((s == 301 || s == 302 || s == 307 || s == 308) && method.equals("GET") && hop < 3) {
      String loc = res.headers().firstValue("location").orElse(null);
      if (loc != null) { URI next = uri.resolve(loc); return exec(method, next, null, auth && Objects.equals(next.getHost(), uri.getHost()), hop + 1); }
    }
    return res;
  }
  static String errMsg(JsonNode d, String text) {
    if (d == null || !d.has("message")) return Redactor.redact(text.length() > 200 ? text.substring(0, 200) : text);
    StringBuilder sb = new StringBuilder(d.get("message").asText());
    if (d.has("errors") && d.get("errors").isArray()) { List<String> xs = new ArrayList<>(); for (var e : d.get("errors")) xs.add(e.isTextual() ? e.asText() : e.path("message").asText(e.path("code").asText(""))); xs.removeIf(String::isEmpty); if (!xs.isEmpty()) sb.append(": ").append(String.join("; ", xs)); }
    return Redactor.redact(sb.toString());
  }
  private void readRate(HttpHeaders h) {
    h.firstValue("x-ratelimit-limit").ifPresent(v -> rate.limit = Integer.valueOf(v)); h.firstValue("x-ratelimit-remaining").ifPresent(v -> rate.remaining = Integer.valueOf(v));
    h.firstValue("x-ratelimit-reset").ifPresent(v -> rate.resetAt = Instant.ofEpochSecond(Long.parseLong(v)).toString());
  }
  private Long retryDelay(HttpResponse<String> res, int status, JsonNode data, int attempt) {
    var ra = res.headers().firstValue("retry-after");
    if (status == 429) return ra.map(v -> Long.parseLong(v) * 1000).orElse(Math.min(1000L << attempt, 30_000));
    if (status == 403) {
      String msg = data != null ? data.path("message").asText("") : "";
      if (ra.isPresent() && msg.toLowerCase().matches(".*(rate limit|abuse).*")) return Long.parseLong(ra.get()) * 1000;
      if ("0".equals(res.headers().firstValue("x-ratelimit-remaining").orElse(""))) { long reset = Long.parseLong(res.headers().firstValue("x-ratelimit-reset").orElse("0")) * 1000; return Math.max(1000, reset - System.currentTimeMillis()); }
      if (msg.toLowerCase().contains("secondary rate limit")) return 60_000L;
      return null;
    }
    if (status == 500 || status == 502 || status == 503 || status == 504) return Math.min(500L << attempt, 8000);
    return null;
  }
  private void proactiveThrottle(String path) {
    if (rate.remaining != null && rate.remaining == 0 && rate.resetAt != null) {
      long w = Instant.parse(rate.resetAt).toEpochMilli() - System.currentTimeMillis();
      if (w > 0) { if (w > maxWaitMs) throw new RateLimited(429, "配额已用尽，" + w / 1000 + "s 后重置，超过等待上限", path); rate.throttledMs += w; sleeper.accept(w + 50); rate.remaining = null; }
    }
  }
}
