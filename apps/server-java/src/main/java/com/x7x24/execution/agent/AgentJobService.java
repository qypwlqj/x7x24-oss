package com.x7x24.execution.agent;

import com.x7x24.adapters.DeliveryTools;
import com.x7x24.agents.Models;
import com.x7x24.db.Db;
import com.x7x24.llm.LlmProvider;
import com.x7x24.util.Clk;
import com.x7x24.util.U;

import java.util.*;
import java.util.function.Consumer;

/**
 * 一个 agent job 的完整生命周期：落 agent_job → 启动 → 轮询日志与状态（推 SSE）→ 超时/取消 → 记账 → 转成 CodeChange。
 * 返回的 CodeChange 仍走原有 PathPolicy、范围检查、提交与 PR 流程（runner 不推分支）。
 */
public class AgentJobService {
  public com.x7x24.llm.ModelCalls modelCalls;
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentJobService.class);
  public interface LogSink { void accept(String workspaceId, String taskId, String jobId, AgentRuntime.LogEvent e); }
  private final Db db; private final Clk clk; private final AgentRuntime runtime; private final AgentRuntimeSettings settings; private final GitTokenProvider tokens;
  private final LogSink sink; private final double priceIn, priceOut; private final Consumer<Long> sleeper;

  public AgentJobService(Db db, Clk clk, AgentRuntime runtime, AgentRuntimeSettings settings, GitTokenProvider tokens, LogSink sink, double priceInPer1m, double priceOutPer1m) {
    this(db, clk, runtime, settings, tokens, sink, priceInPer1m, priceOutPer1m, ms -> { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("agent job 等待被中断"); } });
  }
  AgentJobService(Db db, Clk clk, AgentRuntime runtime, AgentRuntimeSettings settings, GitTokenProvider tokens, LogSink sink, double priceIn, double priceOut, Consumer<Long> sleeper) {
    this.db = db; this.clk = clk; this.runtime = runtime; this.settings = settings; this.tokens = tokens; this.sink = sink; this.priceIn = priceIn; this.priceOut = priceOut; this.sleeper = sleeper;
  }

  public static class JobFailed extends RuntimeException { public final String state; public JobFailed(String state, String m) { super(m); this.state = state; } }

  public Models.CodeChange run(Models.Ctx c) {
    var t = c.task(); String repo = c.line().repo; if (repo == null || !repo.matches("[\\w.-]+/[\\w.-]+")) throw new JobFailed("FAILED", "项目未配置有效仓库");
    String baseSha = baseSha(c);
    assertCurrentHead(c, baseSha);
    String ws = c.address() != null && c.address().workspaceId() != null ? c.address().workspaceId() : t.workspaceId;
    String attempt = c.address() != null && c.address().attemptId() != null ? c.address().attemptId() : t.id + ":" + t.spentIterations;
    String id = U.uuid(); String mode = c.executionMode() == Models.ExecutionMode.REPAIR ? "REPAIR" : "IMPLEMENT"; String now = clk.iso();
    db.update("INSERT INTO agent_job(id,workspace_id,task_id,node_run_id,runtime,state,repo,base_ref,mode,cancel_requested,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        id, ws, t.id, attempt, runtime.id(), "QUEUED", repo, baseSha, mode, 0, now, now);
    var activity = modelCalls == null ? null : modelCalls.remote(t, c.address(), id, "REPAIR".equals(mode) ? "dev.fix" : "dev.code");
    try {
    var spec = new AgentRuntime.JobSpec(id, repo, baseSha, tokens.tokenFor(repo), mode, AgentPrompt.build(c, settings.selfTest()), settings.model(), settings.selfTest() ? new AgentRuntime.Limits(settings.limits().wallClockSec(), settings.limits().maxTurns(), settings.acceptanceMemory(), settings.limits().cpus()) : settings.limits(), settings.selfTest());
    AgentRuntime.Handle h;
    try { h = runtime.start(spec); } catch (RuntimeException e) { finish(id, "FAILED", e.getMessage(), null); throw new JobFailed("FAILED", "runner 启动失败：" + e.getMessage()); }
    db.update("UPDATE agent_job SET remote_id=?, state='RUNNING', updated_at=? WHERE id=?", h.remoteId(), clk.iso(), id);
    long deadline = System.currentTimeMillis() + (settings.limits().wallClockSec() + 120L) * 1000L; long seq = 0;
    while (true) {
      try {
        com.x7x24.execution.ExecutionGuard.check();
        if (cancelRequested(id)) throw new JobFailed("CANCELLED", "用户取消了本次开发");
        if (System.currentTimeMillis() > deadline) throw new JobFailed("TIMED_OUT", "超过墙钟上限 " + settings.limits().wallClockSec() + " 秒");
      } catch (RuntimeException stop) {
        try { runtime.cancel(h, stop.getMessage()); } catch (RuntimeException ignore) { }
        String st = stop instanceof JobFailed jf ? jf.state : "CANCELLED"; finish(id, st, stop.getMessage(), null); throw stop;
      }
      seq = drain(ws, t.id, id, h, seq, activity);
      var s = runtime.poll(h);
      if (s.terminal()) {
        seq = drain(ws, t.id, id, h, seq, activity);
        if (activity != null && s.result() != null) activity.reported(s.result().observedModel());
        if (!"SUCCEEDED".equals(s.state()) || s.result() == null) {
          finish(id, s.state(), s.error(), s.result());
          throw new JobFailed(s.state(), s.error() == null ? "agent 未成功完成" : s.error());
        }
        try {
          var usage = s.result().usage();
          if (c.meter() != null) c.meter().accept("dev", new LlmProvider.Response(s.result().summary(), settings.model(), "agent-runtime@1", usage.inputTokens() + usage.cacheReadTokens() + usage.outputTokens(), usd(usage)));
          if (!baseSha.equals(s.result().baseSha())) throw new DeliveryTools.GateBlocked("head_changed", "编码结果缺少正确的基线证明，不能把旧补丁覆盖到当前分支；请核对运行器版本和任务提交");
          assertCurrentHead(c, baseSha);
          var change = toChange(c, s.result());
          finish(id, "SUCCEEDED", null, s.result());
          if (activity != null) {
            activity.finish("SUCCEEDED");
            if (!activity.hasModel()) log.warn("开发任务完成但 runner 未上报模型 taskId={} jobId={} remoteId={}", t.id, id, h.remoteId());
          }
          return change;
        } catch (RuntimeException invalid) {
          finish(id, "FAILED", invalid.getMessage(), s.result());
          throw invalid; // 基线错误不能回退到另一种编码方式后静默覆盖。
        }
      }
      if (activity != null && "RUNNING".equals(s.state())) activity.remoteRunning();
      sleeper.accept(settings.pollMs());
    }
    } catch (RuntimeException e) {
      if (activity != null) activity.finish(e instanceof JobFailed f && ("CANCELLED".equals(f.state) || "TIMED_OUT".equals(f.state)) || e instanceof com.x7x24.workflow.WorkflowStore.Blocked ? "CANCELLED" : "FAILED");
      throw e;
    }
  }

  private static String baseSha(Models.Ctx c) {
    String sha = c.task().context.pr != null ? c.task().context.pr.headSha : c.line().baseBranch;
    if (c.task().context.pr == null && !isSha(sha) && c.tools() != null) sha = c.tools().repositoryHead(c.line());
    if (!isSha(sha)) throw new DeliveryTools.GateBlocked("head_changed", "缺少可核验的开发基线提交，请先核对当前 PR 或仓库状态");
    return sha;
  }
  private static boolean isSha(String sha) { return sha != null && sha.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})"); }
  private static void assertCurrentHead(Models.Ctx c, String expected) {
    if (c.task().context.pr == null || c.tools() == null) return;
    String current = c.tools().prHead(c.line(), c.task().context.pr.number);
    if (!expected.equals(current)) throw new DeliveryTools.GateBlocked("head_changed", "编码期间 PR 提交已变化或无法核验，请核对差异后继续；已有修改保留");
  }

  private long drain(String ws, String taskId, String id, AgentRuntime.Handle h, long from, com.x7x24.llm.ModelCalls.Session activity) {
    AgentRuntime.Logs logs;
    try { logs = runtime.logs(h, from); } catch (RuntimeException e) { return from; }
    for (var e : logs.events()) {
      if (activity != null && "model".equals(e.kind())) {
        try { activity.reported(com.x7x24.util.Json.tree(e.text()).path("model").asText(null)); } catch (RuntimeException ignore) { }
      }
      db.update("INSERT INTO agent_job_log(job_id,seq,at,kind,text) VALUES(?,?,?,?,?)", id, e.seq(), e.ts(), e.kind(), trunc(e.text(), 4000));
      if (sink != null) try { sink.accept(ws, taskId, id, e); } catch (RuntimeException ignore) { }
    }
    return Math.max(from, logs.next());
  }

  private boolean cancelRequested(String id) { var r = db.row("SELECT cancel_requested FROM agent_job WHERE id=?", id); return r != null && r.get("cancel_requested") instanceof Number n && n.intValue() != 0; }

  public boolean requestCancel(String id) { return db.update("UPDATE agent_job SET cancel_requested=?, updated_at=? WHERE id=? AND state IN ('QUEUED','RUNNING')", 1, clk.iso(), id) > 0; }

  private void finish(String id, String state, String error, AgentRuntime.Result r) {
    var u = r == null ? AgentRuntime.Usage.ZERO : r.usage();
    db.update("UPDATE agent_job SET state=?, error=?, head_sha=?, files=?, untracked=?, tokens_in=?, tokens_out=?, tokens_cache=?, turns=?, usd=?, updated_at=? WHERE id=?",
        state, error == null ? null : trunc(error, 1000), r == null ? null : r.headSha(), r == null ? 0 : r.files().size(), r == null ? null : String.join("\n", r.untracked()),
        u.inputTokens(), u.outputTokens(), u.cacheReadTokens(), u.turns(), usd(u), clk.iso(), id);
  }
  static String trunc(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n); }
  double usd(AgentRuntime.Usage u) { return ((u.inputTokens() + u.cacheReadTokens()) * priceIn + u.outputTokens() * priceOut) / 1_000_000d; }

  private Models.CodeChange toChange(Models.Ctx c, AgentRuntime.Result r) {
    List<DeliveryTools.FileChange> files = new ArrayList<>();
    for (var f : r.files()) files.add(new DeliveryTools.FileChange(f.path(), f.delete() ? null : f.content(), f.delete()));
    if (files.isEmpty()) throw new JobFailed("FAILED", "agent 没有产生任何改动");
    String title = c.task().title == null ? "change" : c.task().title;
    return new Models.CodeChange(files, "agent: " + trunc(title, 60), r.summary(), "agent-runtime", r.baseSha());
  }
}
