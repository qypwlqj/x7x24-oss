package com.x7x24.execution.agent;

import com.x7x24.db.Db;
import com.x7x24.engine.ProjectCfg;
import com.x7x24.engine.Task;
import com.x7x24.util.Clk;
import com.x7x24.util.Json;
import com.x7x24.util.U;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * 页面验收（UI_ACCEPTANCE）：编码结束、PR 分支就绪后，让 runner 在干净容器里从该分支起应用、跑验收脚本、截关键页面，
 * 再交给独立判定 agent。runner 一次只跑一个 job，验收永远排在编码之后，不并行。
 * 截图保存在 DATA_DIR/ui-acceptance/&lt;jobId&gt;/，经 /api/agent-jobs/{id}/screenshots/{name} 读取；不写进数据库。
 */
public class UiAcceptanceService {
  public record Outcome(String jobId, String state, AcceptanceJudge.Verdict verdict, boolean blocked, Map<String, Object> report) {}
  private final Db db; private final Clk clk; private final AgentRuntime runtime; private final AgentRuntimeSettings settings; private final GitTokenProvider tokens;
  private final AgentJobService.LogSink sink; private final AcceptanceJudge judge; private final Path dataDir; private final Consumer<Long> sleeper;

  public UiAcceptanceService(Db db, Clk clk, AgentRuntime runtime, AgentRuntimeSettings settings, GitTokenProvider tokens, AgentJobService.LogSink sink, AcceptanceJudge judge, Path dataDir) {
    this(db, clk, runtime, settings, tokens, sink, judge, dataDir, ms -> { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted"); } });
  }
  UiAcceptanceService(Db db, Clk clk, AgentRuntime runtime, AgentRuntimeSettings settings, GitTokenProvider tokens, AgentJobService.LogSink sink, AcceptanceJudge judge, Path dataDir, Consumer<Long> sleeper) {
    this.db = db; this.clk = clk; this.runtime = runtime; this.settings = settings; this.tokens = tokens; this.sink = sink; this.judge = judge; this.dataDir = dataDir; this.sleeper = sleeper;
  }

  public Outcome run(Task t, ProjectCfg lc, String branch, List<String> criteria) {
    var ua = lc.uiAcceptance; String id = U.uuid(); String now = clk.iso();
    db.update("INSERT INTO agent_job(id,workspace_id,task_id,node_run_id,runtime,state,repo,base_ref,mode,cancel_requested,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        id, t.workspaceId == null ? com.x7x24.tenant.Tenant.writeId() : t.workspaceId, t.id, t.id + ":ui:" + t.currentIteration, runtime.id(), "QUEUED", lc.repo, branch, "ACCEPTANCE", 0, now, now);
    var limits = new AgentRuntime.Limits(Math.min(settings.limits().wallClockSec(), 1800), 1, settings.acceptanceMemory(), settings.limits().cpus());
    var spec = new AgentRuntime.AcceptanceSpec(id, lc.repo, branch, tokens.tokenFor(lc.repo), ua.setup, ua.start, ua.url, ua.readySec, ua.specDir, ua.pages, limits);
    AgentRuntime.Handle h;
    try { h = runtime.startAcceptance(spec); } catch (RuntimeException e) { return done(id, "FAILED", "runner 启动失败：" + e.getMessage(), null, criteria, ua); }
    db.update("UPDATE agent_job SET remote_id=?, state='RUNNING', updated_at=? WHERE id=?", h.remoteId(), clk.iso(), id);
    long deadline = System.currentTimeMillis() + (limits.wallClockSec() + 120L) * 1000L; long seq = 0;
    while (true) {
      boolean cancel = db.row("SELECT cancel_requested FROM agent_job WHERE id=?", id).get("cancel_requested") instanceof Number n && n.intValue() != 0;
      if (cancel || System.currentTimeMillis() > deadline) {
        try { runtime.cancel(h, cancel ? "cancelled" : "timeout"); } catch (RuntimeException ignore) { }
        return done(id, cancel ? "CANCELLED" : "TIMED_OUT", cancel ? "用户取消了页面验收" : "页面验收超时", null, criteria, ua);
      }
      seq = drain(t, id, h, seq);
      var s = runtime.pollAcceptance(h);
      if (s.terminal()) { drain(t, id, h, seq); return done(id, s.state(), s.error(), s.result(), criteria, ua); }
      sleeper.accept(settings.pollMs());
    }
  }

  private long drain(Task t, String id, AgentRuntime.Handle h, long from) {
    AgentRuntime.Logs logs; try { logs = runtime.logs(h, from); } catch (RuntimeException e) { return from; }
    for (var e : logs.events()) {
      db.update("INSERT INTO agent_job_log(job_id,seq,at,kind,text) VALUES(?,?,?,?,?)", id, e.seq(), e.ts(), e.kind(), AgentJobService.trunc(e.text(), 4000));
      if (sink != null) try { sink.accept(t.workspaceId, t.id, id, e); } catch (RuntimeException ignore) { }
    }
    return Math.max(from, logs.next());
  }

  private Outcome done(String id, String state, String error, AgentRuntime.AcceptanceResult r, List<String> criteria, ProjectCfg.UiAcceptance ua) {
    List<String> shots = r == null ? List.of() : saveShots(id, r.shots());
    var verdict = "SUCCEEDED".equals(state) ? judge.judge(criteria, r) : new AcceptanceJudge.Verdict("FAIL", "deterministic", error == null ? "页面验收没有完成（" + state + "）" : error, List.of(), 0, false);
    boolean deterministicFail = "FAIL".equals(verdict.verdict()) && "deterministic".equals(verdict.source());
    boolean blocked = deterministicFail || ("FAIL".equals(verdict.verdict()) && ua.visualBlocking);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("jobId", id); report.put("state", state); report.put("verdict", verdict.verdict()); report.put("source", verdict.source()); report.put("summary", verdict.summary());
    report.put("criteria", verdict.criteria()); report.put("usedImages", verdict.usedImages()); report.put("blocked", blocked); report.put("observeOnly", !ua.visualBlocking);
    report.put("screenshots", shots);
    if (r != null) { report.put("phase", r.phase()); report.put("passed", r.passed()); report.put("failed", r.failed()); report.put("tests", r.tests()); report.put("appLogTail", r.appLogTail()); }
    db.update("UPDATE agent_job SET state=?, error=?, files=?, untracked=?, tokens_in=?, updated_at=? WHERE id=?", state, error == null ? null : AgentJobService.trunc(error, 1000), shots.size(), null, verdict.tokens(), clk.iso(), id);
    db.update("INSERT INTO ui_acceptance_run(id,task_id,verdict,blocked,report,created_at) VALUES(?,?,?,?,?,?)", id, db.row("SELECT task_id FROM agent_job WHERE id=?", id).get("task_id"), verdict.verdict(), blocked ? 1 : 0, Json.str(report), clk.iso());
    return new Outcome(id, state, verdict, blocked, report);
  }

  /** 截图上限：每次最多 12 张、每张 2MB；保留天数见 prune()（UI_SHOT_RETENTION_DAYS，默认 30） */
  public static final int MAX_SHOTS = 12, MAX_SHOT_BYTES = 2_000_000;
  /** 删除超过 days 天的截图目录（按目录修改时间）。返回删除的目录数；目录不存在时什么也不做。 */
  public static int prune(Path dataDir, int days, long nowMs) {
    Path root = dataDir.resolve("ui-acceptance"); if (days <= 0 || !Files.isDirectory(root)) return 0; int n = 0;
    try (var ds = Files.newDirectoryStream(root)) {
      for (Path d : ds) {
        if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS) || Files.getLastModifiedTime(d).toMillis() > nowMs - days * 86_400_000L) continue;
        try (var w = Files.walk(d)) { w.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (Exception ignore) { } }); }
        n++;
      }
    } catch (Exception ignore) { }
    return n;
  }

  private List<String> saveShots(String id, List<AgentRuntime.Shot> shots) {
    List<String> out = new ArrayList<>();
    try {
      Path dir = dataDir.resolve("ui-acceptance").resolve(id); Files.createDirectories(dir);
      for (var s : shots) {
        if (out.size() >= MAX_SHOTS) break;
        if (!s.name().matches("[A-Za-z0-9_.-]{1,80}") || s.pngBase64() == null || s.pngBase64().length() > MAX_SHOT_BYTES * 4 / 3 + 8) continue;
        byte[] png = Base64.getDecoder().decode(s.pngBase64());
        if (png.length > MAX_SHOT_BYTES) continue;
        if (png.length < 8 || png[0] != (byte) 0x89 || png[1] != 'P') continue;
        Files.write(dir.resolve(s.name().endsWith(".png") ? s.name() : s.name() + ".png"), png); out.add(s.name().endsWith(".png") ? s.name() : s.name() + ".png");
      }
    } catch (Exception e) { /* 截图保存失败不影响判定结论 */ }
    return out;
  }

  /** PR 评论正文：结果表 + 到 7x24 任务页的链接（截图需登录查看，不上传到 GitHub）。 */
  public static String prComment(Outcome o, String taskUrl) {
    var r = o.report(); var sb = new StringBuilder("### 7x24 页面验收：").append(o.verdict().verdict()).append(o.blocked() ? "（阻断）" : "").append("\n\n");
    sb.append(String.valueOf(r.get("summary"))).append("\n\n");
    if (r.containsKey("passed")) sb.append("- 验收脚本：").append(r.get("passed")).append(" 通过 / ").append(r.get("failed")).append(" 失败\n");
    sb.append("- 判定来源：").append(o.verdict().source()).append(o.verdict().usedImages() ? "（含截图）" : "（仅文字）").append('\n');
    for (var c : o.verdict().criteria()) sb.append("- ").append(c.criterion()).append("：").append(c.verdict()).append(" —— ").append(c.reason()).append('\n');
    @SuppressWarnings("unchecked") var shots = (List<String>) r.get("screenshots");
    if (taskUrl != null && !taskUrl.isBlank()) sb.append("\n截图 ").append(shots == null ? 0 : shots.size()).append(" 张：").append(taskUrl).append('\n');
    return sb.toString();
  }
}
