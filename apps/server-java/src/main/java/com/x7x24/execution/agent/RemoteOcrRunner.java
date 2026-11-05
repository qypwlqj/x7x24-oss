package com.x7x24.execution.agent;

import com.x7x24.review.OcrResult;
import com.x7x24.review.OcrRunner;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 在 runner 沙箱里跑 open-code-review（ocr v1.13.5，官方 skill「open-code-review」的命令）。
 * runner 一次只跑一个 job：忙（409）时等一会儿再提交，最多等 busyWaitSec；整次审查超过 wallClockMin + 2 分钟没结束就取消（runner 会让 ocr 存下会话）。
 * 模型密钥随签名请求经私有网络交给 runner，runner 只经环境变量交给 ocr；这里不记日志、不落库。
 */
public class RemoteOcrRunner implements OcrRunner {
  private final RemoteDockerRuntime runtime; private final AgentRuntimeSettings settings; private final GitTokenProvider tokens; private final Consumer<Long> sleeper;
  public int busyWaitSec = 900;
  public RemoteOcrRunner(RemoteDockerRuntime runtime, AgentRuntimeSettings settings, GitTokenProvider tokens) {
    this(runtime, settings, tokens, ms -> { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted"); } });
  }
  public RemoteOcrRunner(RemoteDockerRuntime runtime, AgentRuntimeSettings settings, GitTokenProvider tokens, Consumer<Long> sleeper) { this.runtime = runtime; this.settings = settings; this.tokens = tokens; this.sleeper = sleeper; }

  public static Map<String, Object> body(Request r, String cloneToken, AgentRuntime.Limits limits) {
    var b = new LinkedHashMap<String, Object>();
    b.put("jobKey", r.jobKey().replaceAll("[^A-Za-z0-9_:.-]", "_")); b.put("reviewKey", r.reviewKey().replaceAll("[^A-Za-z0-9_:.-]", "_"));
    b.put("repo", r.repo()); b.put("baseRef", r.baseRef()); b.put("headSha", r.headSha()); b.put("cloneToken", cloneToken == null ? "" : cloneToken);
    b.put("llm", Map.of("url", r.llm().url(), "token", r.llm().token(), "model", r.llm().model(), "protocol", r.llm().protocol() == null ? "openai" : r.llm().protocol()));
    if (r.resume() != null) b.put("resume", r.resume());
    b.put("background", r.background() == null ? "" : com.x7x24.util.U.ellipsis(r.background(), 5000));
    b.put("maxTokensBudget", r.maxTokensBudget()); b.put("timeoutMin", r.timeoutMin()); b.put("concurrency", r.concurrency());
    b.put("limits", AgentRuntime.limitsMap(limits));
    return b;
  }

  @Override public Outcome review(Request r, BooleanSupplier cancelled, Consumer<String> log) {
    if (r.llm() == null) return Outcome.failed("FAILED", "没有可用的审查模型（模型配置里没有拿得到密钥的模型，或都被本任务的开发用过）");
    var limits = new AgentRuntime.Limits(Math.max(60, Math.min(7200, r.wallClockMin() * 60)), 1, settings.limits().memory(), settings.limits().cpus());
    var body = body(r, tokens.tokenFor(r.repo()), limits);
    AgentRuntime.Handle h = null; long busyUntil = System.currentTimeMillis() + busyWaitSec * 1000L; boolean said = false;
    while (h == null) {
      if (cancelled.getAsBoolean()) return Outcome.failed("CANCELLED", "已暂停");
      try { h = runtime.startReview(body); }
      catch (RemoteDockerRuntime.RunnerException e) {
        if (e.status != 409) return Outcome.failed("FAILED", "runner 启动审查失败：" + e.getMessage());
        if (System.currentTimeMillis() > busyUntil) return Outcome.failed("BUSY", "runner 一直在忙（别的任务在跑），等了 " + busyWaitSec / 60 + " 分钟还没空出来");
        if (!said && log != null) { log.accept("runner 正在跑别的任务，排队等它空出来"); said = true; }
        sleeper.accept(Math.max(5000L, settings.pollMs() * 5));
      }
    }
    body.clear();
    long deadline = System.currentTimeMillis() + (limits.wallClockSec() + 120L) * 1000L;
    while (true) {
      var j = runtime.job(h); String state = j.path("state").asText("FAILED");
      if (!"QUEUED".equals(state) && !"RUNNING".equals(state)) {
        var doc = j.path("result").path("ocr");
        OcrResult res = null; String err = j.hasNonNull("error") ? j.path("error").asText() : null;
        if (doc.isObject()) { try { res = OcrResult.parse(doc); } catch (RuntimeException e) { err = "ocr 结果解析失败：" + e.getMessage(); } }
        return new Outcome(state, err, res);
      }
      if (cancelled.getAsBoolean() || System.currentTimeMillis() > deadline) {
        boolean cancel = cancelled.getAsBoolean();
        try { runtime.cancel(h, cancel ? "cancelled" : "timeout"); } catch (RuntimeException ignore) { }
        // 取消后 runner 会让 ocr 存下会话再结束：再等一会儿拿会话 id，下次 --resume
        for (int i = 0; i < 12; i++) {
          sleeper.accept(5000L);
          var k = runtime.job(h); String st = k.path("state").asText();
          if (!"QUEUED".equals(st) && !"RUNNING".equals(st)) {
            var doc = k.path("result").path("ocr"); OcrResult res = null;
            if (doc.isObject()) { try { res = OcrResult.parse(doc); } catch (RuntimeException ignore) { } }
            return new Outcome(cancel ? "CANCELLED" : "TIMED_OUT", cancel ? "已暂停" : "审查超过 " + r.wallClockMin() + " 分钟", res);
          }
        }
        return Outcome.failed(cancel ? "CANCELLED" : "TIMED_OUT", cancel ? "已暂停" : "审查超过 " + r.wallClockMin() + " 分钟");
      }
      sleeper.accept(settings.pollMs());
    }
  }
}
