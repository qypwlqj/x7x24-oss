package com.x7x24.execution.agent;

import java.util.List;
import java.util.Map;

/**
 * 代码开发运行时：在隔离沙盒里跑真正的 code agent（克隆、修改、构建、测试）。
 * 实现：{@code remote-docker}（x7x24-runnerd）。
 */
public interface AgentRuntime {
  String id();
  /** 幂等：同一 jobKey 重复调用返回同一远端任务。 */
  Handle start(JobSpec spec);
  Status poll(Handle h);
  Logs logs(Handle h, long fromSeq);
  void cancel(Handle h, String reason);

  record Limits(int wallClockSec, int maxTurns, String memory, double cpus) {
    public static Limits defaults() { return new Limits(1800, 60, "2g", 1.5); }
  }
  /** cloneToken 只用于 runner 在宿主侧 clone，不进容器，不落库。 */
  /** selfTest=true：使用带 Chromium + Playwright MCP 的镜像，agent 改完后自己起应用看页面。 */
  record JobSpec(String jobKey, String repo, String baseRef, String cloneToken, String mode, String prompt, String model, Limits limits, boolean selfTest) {
    public JobSpec(String jobKey, String repo, String baseRef, String cloneToken, String mode, String prompt, String model, Limits limits) { this(jobKey, repo, baseRef, cloneToken, mode, prompt, model, limits, false); }
    @Override public String toString() { return "JobSpec[" + jobKey + "," + repo + "@" + baseRef + "," + mode + (selfTest ? ",selfTest" : "") + "]"; }
  }
  /** 独立验收：在干净容器里从 PR 分支起应用、跑验收脚本、截关键页面。 */
  record AcceptanceSpec(String jobKey, String repo, String ref, String cloneToken, String setup, String start, String url, int readySec, String specDir, List<String> pages, Limits limits) {
    @Override public String toString() { return "AcceptanceSpec[" + jobKey + "," + repo + "@" + ref + "]"; }
  }
  record TestCase(String title, String status, String error) {}
  record Shot(String name, String pngBase64) {}
  /** phase：setup / start / tests（tests 表示应用已起来并跑完脚本）。 */
  record AcceptanceResult(String phase, int exitCode, List<TestCase> tests, int passed, int failed, List<Shot> shots, Map<String, String> a11y, String appLogTail) {}
  record AcceptanceStatus(String state, String error, AcceptanceResult result) {
    public boolean terminal() { return !"QUEUED".equals(state) && !"RUNNING".equals(state); }
  }
  default Handle startAcceptance(AcceptanceSpec spec) { throw new UnsupportedOperationException("该运行时不支持页面验收"); }
  default AcceptanceStatus pollAcceptance(Handle h) { throw new UnsupportedOperationException("该运行时不支持页面验收"); }
  record Handle(String runtime, String remoteId) {}
  record FileOut(String path, String content, boolean delete) {}
  record Usage(long inputTokens, long outputTokens, long cacheReadTokens, int turns) {
    public static final Usage ZERO = new Usage(0, 0, 0, 0);
  }
  record Result(List<FileOut> files, List<String> untracked, String summary, Usage usage, String headSha, String baseSha, String observedModel) {
    public Result(List<FileOut> files, List<String> untracked, String summary, Usage usage, String headSha, String baseSha) { this(files, untracked, summary, usage, headSha, baseSha, null); }
    public Result(List<FileOut> files, List<String> untracked, String summary, Usage usage, String headSha) { this(files, untracked, summary, usage, headSha, null); }
  }
  /** state：QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED / TIMED_OUT */
  record Status(String state, String error, Result result) {
    public boolean terminal() { return !"QUEUED".equals(state) && !"RUNNING".equals(state); }
  }
  record LogEvent(long seq, String ts, String kind, String text) {}
  record Logs(List<LogEvent> events, long next) {}

  static Map<String, Object> limitsMap(Limits l) { return Map.of("wallClockSec", l.wallClockSec(), "maxTurns", l.maxTurns(), "memory", l.memory(), "cpus", l.cpus()); }
}
