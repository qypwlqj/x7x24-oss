package com.x7x24.web;

import com.x7x24.db.Db;
import com.x7x24.gov.AuditLog;
import com.x7x24.tenant.Tenant;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** agent job 只读查询与取消。只返回本工作区的 job；不含任何令牌。 */
@RestController
@RequestMapping("/api/agent-jobs")
public class AgentJobApi {
  private final Db db; private final AuditLog audit; private final com.x7x24.util.Clk clk; private final String dataDir;
  public AgentJobApi(Db db, AuditLog audit, com.x7x24.util.Clk clk, org.springframework.core.env.Environment env) { this.db = db; this.audit = audit; this.clk = clk; this.dataDir = env.getProperty("DATA_DIR", "./data"); }
  static final String COLS = "id,task_id,runtime,state,repo,base_ref,mode,cancel_requested,head_sha,files,untracked,tokens_in,tokens_out,tokens_cache,turns,usd,error,created_at,updated_at";

  private Map<String, Object> job(String id) {
    String ws = Tenant.readFilter();
    var r = ws == null ? db.row("SELECT " + COLS + ",workspace_id FROM agent_job WHERE id=?", id) : db.row("SELECT " + COLS + ",workspace_id FROM agent_job WHERE id=? AND workspace_id=?", id, ws);
    if (r == null) throw new UserError("NOT_FOUND", "找不到这个开发任务");
    return r;
  }
  @GetMapping public List<Map<String, Object>> list(@RequestParam String taskId) {
    String ws = Tenant.readFilter();
    return ws == null ? db.rows("SELECT " + COLS + " FROM agent_job WHERE task_id=? ORDER BY created_at DESC LIMIT 20", taskId)
        : db.rows("SELECT " + COLS + " FROM agent_job WHERE task_id=? AND workspace_id=? ORDER BY created_at DESC LIMIT 20", taskId, ws);
  }
  /** 任务的页面验收记录（验收页签 / 右栏用），按时间倒序；只含本工作区 */
  @GetMapping("/ui-acceptance") public List<Map<String, Object>> uiRuns(@RequestParam String taskId) {
    String ws = Tenant.readFilter();
    var rows = ws == null ? db.rows("SELECT u.id,u.verdict,u.blocked,u.report,u.created_at FROM ui_acceptance_run u JOIN agent_job j ON j.id=u.id WHERE u.task_id=? ORDER BY u.created_at DESC LIMIT 20", taskId)
        : db.rows("SELECT u.id,u.verdict,u.blocked,u.report,u.created_at FROM ui_acceptance_run u JOIN agent_job j ON j.id=u.id WHERE u.task_id=? AND j.workspace_id=? ORDER BY u.created_at DESC LIMIT 20", taskId, ws);
    List<Map<String, Object>> out = new ArrayList<>();
    for (var r : rows) { var o = new LinkedHashMap<String, Object>(r); o.put("report", com.x7x24.util.Json.tree(String.valueOf(r.get("report")))); out.add(o); }
    return out;
  }
  /** 截图保留：默认 30 天（UI_SHOT_RETENTION_DAYS）；目录不存在（开关关闭）时什么也不做 */
  @org.springframework.scheduling.annotation.Scheduled(initialDelay = 600_000, fixedDelay = 21_600_000)
  public void pruneShots() { com.x7x24.execution.agent.UiAcceptanceService.prune(java.nio.file.Path.of(dataDir), retentionDays, System.currentTimeMillis()); }
  @org.springframework.beans.factory.annotation.Value("${UI_SHOT_RETENTION_DAYS:30}") int retentionDays;

  @GetMapping("/{id}") public Map<String, Object> get(@PathVariable String id) { var r = new LinkedHashMap<>(job(id)); r.remove("workspace_id"); return r; }
  @GetMapping("/{id}/logs") public Map<String, Object> logs(@PathVariable String id, @RequestParam(defaultValue = "0") long from) {
    job(id); var ev = db.rows("SELECT seq,at,kind,text FROM agent_job_log WHERE job_id=? AND seq>=? ORDER BY seq LIMIT 500", id, from);
    long next = ev.isEmpty() ? from : ((Number) ev.get(ev.size() - 1).get("seq")).longValue() + 1;
    return Map.of("events", ev, "next", next);
  }
  /** 页面验收结果（不含截图内容，截图另取）。 */
  @GetMapping("/{id}/ui-acceptance") public Object uiAcceptance(@PathVariable String id) {
    job(id); var r = db.row("SELECT verdict,blocked,report,created_at FROM ui_acceptance_run WHERE id=?", id);
    if (r == null) throw new UserError("NOT_FOUND", "这个任务没有页面验收结果");
    var o = new LinkedHashMap<String, Object>(r); o.put("report", com.x7x24.util.Json.tree(String.valueOf(r.get("report")))); return o;
  }
  @GetMapping(value = "/{id}/screenshots/{name}", produces = "image/png") public org.springframework.http.ResponseEntity<byte[]> screenshot(@PathVariable String id, @PathVariable String name) throws java.io.IOException {
    job(id); if (!name.matches("[A-Za-z0-9_.-]{1,84}\\.png")) throw new UserError("NOT_FOUND", "截图不存在");
    var p = java.nio.file.Path.of(dataDir).resolve("ui-acceptance").resolve(id).resolve(name).normalize();
    if (!p.startsWith(java.nio.file.Path.of(dataDir).resolve("ui-acceptance").normalize()) || !java.nio.file.Files.isRegularFile(p)) throw new UserError("NOT_FOUND", "截图不存在");
    return org.springframework.http.ResponseEntity.ok().header("Cache-Control", "private, max-age=300").header("X-Content-Type-Options", "nosniff").body(java.nio.file.Files.readAllBytes(p));
  }
  @PostMapping("/{id}/cancel") public Map<String, Object> cancel(@PathVariable String id, jakarta.servlet.http.HttpServletRequest r) {
    var j = job(id); String actor = PipelineApi.actor(r);
    int n = db.update("UPDATE agent_job SET cancel_requested=1, updated_at=? WHERE id=? AND state IN ('QUEUED','RUNNING')", clk.iso(), id);
    if (n > 0) audit.sys("AGENT_JOB_CANCEL_REQUESTED", actor, Map.of("jobId", id, "taskId", String.valueOf(j.get("task_id"))), null);
    return Map.of("ok", n > 0);
  }
}
