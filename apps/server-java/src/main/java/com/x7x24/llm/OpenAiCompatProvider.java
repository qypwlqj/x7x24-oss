package com.x7x24.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.x7x24.adapters.Redactor;
import com.x7x24.util.Json;
import com.x7x24.util.U;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 chat/completions（DeepSeek 等）。
 * - 路径：baseUrl 以 /chat/completions 结尾则原样使用，否则追加 /chat/completions（兼容 https://api.deepseek.com 与 .../v1 两种写法）。
 * - 超时：连接 / 读取超时；超时与 IO 异常、429、5xx 都做指数退避重试（尊重 Retry-After，有上限）；其它 4xx 立即失败。
 * - 结构化输出：要求 JSON 对象；解析 / 校验失败自动重试一次（把错误反馈给模型），仍失败抛 {@link OutputException} 交调用方降级。
 * - 用量：prompt/completion token 乘以可配置单价估算 USD（单价是可调估算值，不是官方报价）。
 * - 安全：密钥只在 Authorization 头里；所有错误信息、日志都经 Redactor；日志不含提示词与输出内容。
 */
public class OpenAiCompatProvider implements LlmProvider {
  private static final Logger log = LoggerFactory.getLogger(OpenAiCompatProvider.class);

  public static class Options {
    public double temperature = 0.2; public String persona = ""; public int maxRetries = 3; public long backoffMs = 800, maxBackoffMs = 20_000;
    public Duration connectTimeout = Duration.ofSeconds(10), readTimeout = Duration.ofSeconds(180);
    public double priceInPer1m = 0.30, priceOutPer1m = 1.20; public int maxTokens = 0; public LlmGate gate = LlmGate.unlimited();
    public Options temperature(double v) { temperature = v; return this; } public Options persona(String v) { persona = v == null ? "" : v; return this; }
    public Options retries(int n, long backoff, long maxBackoff) { maxRetries = n; backoffMs = backoff; maxBackoffMs = maxBackoff; return this; }
    public Options timeouts(Duration c, Duration r) { connectTimeout = c; readTimeout = r; return this; }
    public Options price(double in, double out) { priceInPer1m = in; priceOutPer1m = out; return this; } public Options gate(LlmGate g) { gate = g; return this; }
  }

  private final String model, path; private final RestClient http; private final Options o; private final boolean reasoner;

  public OpenAiCompatProvider(String baseUrl, String apiKey, String model, Options o) {
    this.model = model; this.o = o; this.reasoner = model.toLowerCase().contains("reasoner");
    Redactor.register(apiKey);
    String b = baseUrl.trim().replaceAll("/+$", ""); this.path = b.endsWith("/chat/completions") ? "" : "/chat/completions";
    this.http = client(b, apiKey, o.connectTimeout, o.readTimeout);
    this.base = b; this.key = apiKey;
  }
  private final String base, key;
  /** 调用策略覆盖读超时时用的客户端，按秒数缓存（只有逐行审查等设置了 {@link CallPolicy} 的调用会用到） */
  private final Map<Long, RestClient> byTimeout = new java.util.concurrent.ConcurrentHashMap<>();
  private static RestClient client(String b, String apiKey, Duration connect, Duration read) {
    var f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connect).build()); f.setReadTimeout(read);
    return RestClient.builder().baseUrl(b).requestFactory(f).defaultHeader("Authorization", "Bearer " + apiKey).build();
  }
  /** 读超时 = min(策略超时, 整组剩余时间)，按 10 秒取整，至少 10 秒 */
  private RestClient http(CallPolicy cp) {
    if (cp == null) return http;
    long sec = cp.timeoutSec() > 0 ? cp.timeoutSec() : o.readTimeout.toSeconds();
    long left = cp.remainingMs(System.currentTimeMillis()); if (left != Long.MAX_VALUE) sec = Math.min(sec, (left + 999) / 1000);
    long bucket = Math.max(10, ((sec + 9) / 10) * 10);
    return byTimeout.computeIfAbsent(bucket, x -> client(base, key, o.connectTimeout, Duration.ofSeconds(x)));
  }
  @Override public String model() { return model; }

  /** 去掉 ```json 围栏后解析成 JSON 对象；仍失败时截取第一个 '{' 到最后一个 '}' 再试（容忍前后夹带说明文字）。 */
  public static JsonNode parseObject(String text) {
    String t = text == null ? "" : text.trim();
    if (t.startsWith("```")) { t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```\\s*$", "").trim(); }
    JsonNode n = tryObj(t); if (n != null) return n;
    int a = t.indexOf('{'), b = t.lastIndexOf('}');
    return a >= 0 && b > a ? tryObj(t.substring(a, b + 1)) : null;
  }
  private static JsonNode tryObj(String t) { try { JsonNode n = Json.M.readTree(t); return n != null && n.isObject() ? n : null; } catch (Exception e) { return null; } }

  @Override public Response chat(Request r) { return chat(r, null); }

  @Override public Response chat(Request r, Validator v) { return chatStream(r, v, null); }

  /** 流式：stream=true 逐段回调；校验失败重试前先 reset。stream 为 null 时走普通请求 */
  @Override public Response chatStream(Request r, Validator v, Stream stream) {
    List<Map<String, String>> msgs = new ArrayList<>();
    msgs.add(Map.of("role", "system", "content", r.system() + (o.persona.isBlank() ? "" : "\n\n" + o.persona)));
    msgs.add(Map.of("role", "user", "content", r.prompt()));
    long tin = 0, tout = 0; String lastErr = null; int truncated = 0;
    for (int attempt = 0; attempt < 2 + Math.min(truncated, 1); attempt++) {
      if (stream != null && attempt > 0) stream.reset();
      Raw raw;
      try { raw = stream == null ? call(msgs) : callStream(msgs, stream); }
      catch (Cancelled c) { throw new Cancelled(c.partial, model, tin + tout + c.tokens, (tin + c.tokens) * o.priceInPer1m / 1e6 + tout * o.priceOutPer1m / 1e6); }
      tin += raw.in; tout += raw.out;
      boolean cut = "length".equals(raw.finish);
      JsonNode n = cut ? null : parseObject(raw.content);
      String err = cut ? "输出超过 max_tokens 被截断（finish_reason=length，" + raw.out + " tokens）" : n == null ? "输出不是合法的 JSON 对象" : v == null ? null : v.validate(n);
      if (err == null) return resp(n.toString(), r, tin, tout);
      lastErr = err; if (cut) truncated++;
      log.warn("LLM 输出校验失败 model={} role={} attempt={} finish={} out={} reason={}", model, r.role(), attempt + 1, raw.finish, raw.out, Redactor.redact(err));
      if (cut) {
        // 截断：不回放被截断的长输出（白白占上下文），直接要求更短的输出形式
        msgs.add(Map.of("role", "user", "content", "你上一次的输出过长，超过单次输出上限被截断（不是合法 JSON）。请重新输出，务必大幅缩短：" + (r.compactHint() == null ? "只保留必要内容。" : r.compactHint()) + " 严格只输出一个 JSON 对象，不要 Markdown 围栏。"));
      } else {
        msgs.add(Map.of("role", "assistant", "content", raw.content.length() > 4000 ? raw.content.substring(0, 4000) : raw.content));
        msgs.add(Map.of("role", "user", "content", "你上一次的输出不合格：" + err + "。请严格只输出一个符合要求的 JSON 对象，不要任何解释或 Markdown 围栏。"));
      }
    }
    double usd = tin * o.priceInPer1m / 1e6 + tout * o.priceOutPer1m / 1e6;
    throw new OutputException("LLM 输出重试后仍不合格（" + Redactor.redact(lastErr) + "）：model=" + model + " role=" + r.role() + "，用量 " + (tin + tout) + " tokens 已计入", model, tin + tout, usd);
  }

  private Response resp(String text, Request r, long tin, long tout) {
    double usd = tin * o.priceInPer1m / 1e6 + tout * o.priceOutPer1m / 1e6;
    return new Response(text, model, r.promptVersion() == null ? "v1" : r.promptVersion(), tin + tout, usd);
  }

  private record Raw(String content, long in, long out, String finish) {}

  private Raw call(List<Map<String, String>> msgs) {
    ObjectNode body = Json.M.createObjectNode(); body.put("model", model);
    // Cover initial prompts, persona, tool/context data and validation retry messages.
    body.set("messages", Json.tree(Redactor.json(msgs)));
    if (!reasoner) { body.put("temperature", o.temperature); body.set("response_format", Json.M.valueToTree(Map.of("type", "json_object"))); }
    int mt = o.maxTokens > 0 ? o.maxTokens : (!reasoner && model.toLowerCase().startsWith("deepseek-chat") ? 8192 : 0); // deepseek-chat 默认只给 4K 输出
    if (mt > 0) body.put("max_tokens", mt);
    RuntimeException last = null; CallPolicy cp = CallPolicy.current(); String lastKind = null; int conn = 0, srv = 0;
    for (int a = 0; cp != null || a <= o.maxRetries; a++) {
      long wait = Math.min(o.maxBackoffMs, o.backoffMs * (1L << Math.min(a, 20)));
      long t0 = System.currentTimeMillis();
      if (cp != null) {
        if (cp.expired(t0)) throw new CallPolicy.DeadlineExceeded("超过本组审查时限，已停止调用 model=" + model + (last == null ? "" : "（上次失败：" + Redactor.redact(String.valueOf(last.getMessage())) + "）"));
        cp.notify(model, a + 1, lastKind == null ? cp.maxAttempts(CallPolicy.CONNECT) : cp.maxAttempts(lastKind), lastKind);
      }
      try {
        o.gate.acquire();
        try {
          com.x7x24.execution.ExecutionGuard.check();
          if(com.x7x24.execution.ExecutionGuard.active())com.x7x24.workflow.ContextBuilder.guardRequest("",Json.str(msgs),16384);
          String raw = http(cp).post().uri(path.isEmpty() ? "" : path).contentType(MediaType.APPLICATION_JSON).body(body.toString()).retrieve().body(String.class);
          JsonNode n = Json.tree(raw); JsonNode ch = n.path("choices").path(0);
          String text = ch.path("message").path("content").asText("");
          String finish = ch.path("finish_reason").asText("");
          if (text.isBlank()) throw new IllegalStateException("LLM 返回空内容（finish_reason=" + ch.path("finish_reason").asText("?") + "）");
          long in = n.path("usage").path("prompt_tokens").asLong(0), out = n.path("usage").path("completion_tokens").asLong(0);
          if (in == 0 && out == 0) { in = msgs.stream().mapToInt(m -> m.get("content").length()).sum() / 3; out = text.length() / 3; }
          log.info("LLM 调用 model={} in={} out={} finish={} latencyMs={} attempt={}", model, in, out, finish, System.currentTimeMillis() - t0, a + 1);
          return new Raw(text, in, out, finish);
        } finally { o.gate.release(); }
      } catch (com.x7x24.workflow.WorkflowStore.Blocked stopped) { throw stopped;
      } catch (RestClientResponseException e) {
        int s = e.getStatusCode().value();
        if (s != 429 && s < 500) { String errBody = U.ellipsis(Redactor.redact(e.getResponseBodyAsString()).replaceAll("\\s+", " "), 300); log.warn("LLM HTTP {}（不重试）model={}：{}", s, model, errBody); throw new IllegalStateException("LLM HTTP " + s + ": " + errBody); }
        last = e; lastKind = CallPolicy.kind(s, e); String ra = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (ra != null) { try { wait = Math.min(o.maxBackoffMs, Math.max(wait, Long.parseLong(ra.trim()) * 1000)); } catch (NumberFormatException ignore) { /* 非秒数格式：用指数退避 */ } }
        log.warn("LLM HTTP {}，第 {} 次失败，{}ms 后重试 model={}", s, a + 1, wait, model);
      } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IllegalStateException("LLM 调用被中断");
      } catch (RuntimeException e) { last = e; lastKind = CallPolicy.kind(0, e); log.warn("LLM 调用异常（{}），第 {} 次失败，{}ms 后重试 model={}", e.getClass().getSimpleName(), a + 1, wait, model); }
      if (cp == null) { if (a == o.maxRetries) break; }
      else {
        boolean server = CallPolicy.RATE_LIMIT.equals(lastKind) || CallPolicy.SERVER.equals(lastKind);
        if (server ? srv++ >= cp.serverRetries() : conn++ >= cp.connectRetries()) break;
        if (cp.remainingMs(System.currentTimeMillis()) <= wait) break;
      }
      try { Thread.sleep(wait + (long) (Math.random() * wait * 0.2)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
    }
    if (cp != null) throw new IllegalStateException("LLM 调用失败（" + kindZh(lastKind) + "，已重试 " + (conn + srv - 1) + " 次）: " + (last == null ? "?" : Redactor.redact(last)));
    throw new IllegalStateException("LLM 调用失败（已重试 " + o.maxRetries + " 次）: " + (last == null ? "?" : Redactor.redact(last)));
  }
  static String kindZh(String k) { return k == null ? "未知错误" : switch (k) { case CallPolicy.TIMEOUT -> "接口超时"; case CallPolicy.CONNECT -> "连不上接口"; case CallPolicy.RATE_LIMIT -> "接口限流 429"; case CallPolicy.SERVER -> "接口 5xx"; default -> k; }; }

  /** 空闲超时：这么久没有新片段就中止（可在测试里调小） */
  public static long idleTimeoutMs = 60_000;
  private static final java.util.concurrent.ScheduledExecutorService WATCH = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "llm-stream-watch"); t.setDaemon(true); return t; });

  /** stream=true：按 SSE 行读取 choices[0].delta.content。已经给出片段后不再重试（避免重复输出），直接报错 */
  private Raw callStream(List<Map<String, String>> msgs, Stream stream) {
    ObjectNode body = Json.M.createObjectNode(); body.put("model", model);
    body.set("messages", Json.tree(Redactor.json(msgs))); body.put("stream", true); body.set("stream_options", Json.M.valueToTree(Map.of("include_usage", true)));
    if (!reasoner) { body.put("temperature", o.temperature); body.set("response_format", Json.M.valueToTree(Map.of("type", "json_object"))); }
    int mt = o.maxTokens > 0 ? o.maxTokens : (!reasoner && model.toLowerCase().startsWith("deepseek-chat") ? 8192 : 0);
    if (mt > 0) body.put("max_tokens", mt);
    RuntimeException last = null;
    for (int a = 0; a <= o.maxRetries; a++) {
      long wait = Math.min(o.maxBackoffMs, o.backoffMs * (1L << a)); long t0 = System.currentTimeMillis();
      StringBuilder text = new StringBuilder(), reasoning = new StringBuilder(); long[] usage = {0, 0}; String[] finish = {""}; boolean[] emitted = {false}, idle = {false}, thinking = {false};
      try {
        o.gate.acquire();
        try {
          com.x7x24.execution.ExecutionGuard.check();
          if (com.x7x24.execution.ExecutionGuard.active()) com.x7x24.workflow.ContextBuilder.guardRequest("", Json.str(msgs), 16384);
          http.post().uri(path.isEmpty() ? "" : path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON).body(body.toString()).exchange((req, res) -> {
            int st = res.getStatusCode().value();
            if (st >= 400) { String eb = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); throw new RestClientResponseException("HTTP " + st, st, "", res.getHeaders(), eb.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8); }
            var in = res.getBody(); long[] lastAt = {System.currentTimeMillis()};
            var watch = WATCH.scheduleAtFixedRate(() -> {
              if (stream.cancelled() || System.currentTimeMillis() - lastAt[0] > idleTimeoutMs) { idle[0] = !stream.cancelled(); try { in.close(); } catch (Exception ignore) { } }
            }, 500, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
            try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
              String line;
              while ((line = br.readLine()) != null) {
                lastAt[0] = System.currentTimeMillis();
                if (stream.cancelled()) break;
                if (!line.startsWith("data:")) continue;
                String d = line.substring(5).trim(); if (d.isEmpty()) continue; if (d.equals("[DONE]")) break;
                JsonNode n; try { n = Json.M.readTree(d); } catch (Exception e) { continue; }
                if (n.path("usage").isObject()) { usage[0] = n.path("usage").path("prompt_tokens").asLong(usage[0]); usage[1] = n.path("usage").path("completion_tokens").asLong(usage[1]); }
                JsonNode ch = n.path("choices").path(0); if (ch.isMissingNode()) continue;
                String f = ch.path("finish_reason").asText(""); if (!f.isEmpty() && !"null".equals(f)) finish[0] = f;
                JsonNode delta = ch.path("delta");
                String thought = delta.path("reasoning_content").isTextual() ? delta.path("reasoning_content").asText() : "";
                if (!thought.isEmpty()) {
                  if (!thinking[0]) { thinking[0] = true; stream.thinking(); }
                  reasoning.append(thought); emitted[0] = true; stream.reasoning(thought);
                }
                String piece = delta.path("content").isTextual() ? delta.path("content").asText() : "";
                if (!piece.isEmpty()) {
                  if (thinking[0]) { thinking[0] = false; stream.reasoningDone(); }
                  text.append(piece); emitted[0] = true; stream.token(piece);
                }
              }
            } catch (java.io.IOException e) {
              if (!stream.cancelled() && !idle[0]) throw e;
            } finally { watch.cancel(false); if (thinking[0]) { thinking[0] = false; stream.reasoningDone(); } }
            return null;
          });
          if (stream.cancelled()) throw new Cancelled(text.toString(), model, usage[0] + usage[1] > 0 ? usage[0] + usage[1] : (msgs.stream().mapToInt(m -> m.get("content").length()).sum() + text.length() + reasoning.length()) / 3, 0);
          if (idle[0]) throw new IllegalStateException("模型 " + (idleTimeoutMs / 1000) + " 秒没有新输出，已中止");
          if (text.length() == 0) throw new IllegalStateException("LLM 返回空内容（finish_reason=" + (finish[0].isEmpty() ? "?" : finish[0]) + "）");
          long in = usage[0], out = usage[1];
          if (in == 0 && out == 0) { in = msgs.stream().mapToInt(m -> m.get("content").length()).sum() / 3; out = (text.length() + reasoning.length()) / 3; }
          log.info("LLM 流式调用 model={} in={} out={} finish={} latencyMs={} attempt={}", model, in, out, finish[0], System.currentTimeMillis() - t0, a + 1);
          return new Raw(text.toString(), in, out, finish[0]);
        } finally { o.gate.release(); }
      } catch (Cancelled c) { throw c;
      } catch (com.x7x24.workflow.WorkflowStore.Blocked stopped) { throw stopped;
      } catch (RestClientResponseException e) {
        int s = e.getStatusCode().value();
        if (s != 429 && s < 500) { String errBody = U.ellipsis(Redactor.redact(e.getResponseBodyAsString()).replaceAll("\\s+", " "), 300); log.warn("LLM HTTP {}（不重试）model={}：{}", s, model, errBody); throw new IllegalStateException("LLM HTTP " + s + ": " + errBody); }
        last = e; String ra = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (ra != null) { try { wait = Math.min(o.maxBackoffMs, Math.max(wait, Long.parseLong(ra.trim()) * 1000)); } catch (NumberFormatException ignore) { } }
        log.warn("LLM HTTP {}，第 {} 次失败，{}ms 后重试 model={}", s, a + 1, wait, model);
      } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IllegalStateException("LLM 调用被中断");
      } catch (RuntimeException e) {
        if (emitted[0] || idle[0]) throw new IllegalStateException("模型输出中断：" + Redactor.redact(String.valueOf(e.getMessage())));
        last = e; log.warn("LLM 流式调用异常（{}），第 {} 次失败，{}ms 后重试 model={}", e.getClass().getSimpleName(), a + 1, wait, model);
      }
      if (a == o.maxRetries) break;
      try { Thread.sleep(wait + (long) (Math.random() * wait * 0.2)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
    }
    throw new IllegalStateException("LLM 调用失败（已重试 " + o.maxRetries + " 次）: " + (last == null ? "?" : Redactor.redact(last)));
  }
}
