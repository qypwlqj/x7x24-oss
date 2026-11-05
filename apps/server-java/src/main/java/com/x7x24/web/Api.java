package com.x7x24.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.CallLog;
import com.x7x24.adapters.DeliveryTools;
import com.x7x24.adapters.GitHubTools;
import com.x7x24.core.Status;
import com.x7x24.db.Db;
import com.x7x24.engine.*;
import com.x7x24.gov.*;
import com.x7x24.llm.Providers;
import com.x7x24.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@RestController
@RequestMapping("/api")
public class Api {
  private final Db db; private final Clk clk; private final ConfigStore cfg; private final AuditLog audit; private final EvidenceStore evidence; private final VerdictStore verdicts;
  private final BudgetService budget; private final KillSwitch kill; private final TaskRepo repo; private final LineRepo lines; private final ProjectRepo projects; private final StageExecutions stages; private final DeliveryTools tools; private final Providers providers;
  private final com.x7x24.integration.CredentialProvider creds; private final com.x7x24.integration.RuntimeSettings settings; private final com.x7x24.pipeline.PipelineService pipes;
  private final Engine engine; private final Board board; private final Sse sse; private final CallLog calls; private final Intake intake; private final X7Props props;
  @org.springframework.beans.factory.annotation.Autowired com.x7x24.integration.GitHubAuth githubAuth;

  public Api(Db db, Clk clk, ConfigStore cfg, AuditLog audit, EvidenceStore evidence, VerdictStore verdicts, BudgetService budget, KillSwitch kill, TaskRepo repo, LineRepo lines, ProjectRepo projects, StageExecutions stages, DeliveryTools tools,
             Providers providers, Engine engine, Board board, Sse sse, CallLog calls, Intake intake, X7Props props, com.x7x24.integration.CredentialProvider creds, com.x7x24.integration.RuntimeSettings settings, com.x7x24.pipeline.PipelineService pipes) {
    this.creds = creds; this.settings = settings; this.pipes = pipes;
    this.db = db; this.clk = clk; this.cfg = cfg; this.audit = audit; this.evidence = evidence; this.verdicts = verdicts; this.budget = budget; this.kill = kill; this.repo = repo; this.lines = lines; this.projects = projects; this.stages = stages; this.tools = tools;
    this.providers = providers; this.engine = engine; this.board = board; this.sse = sse; this.calls = calls; this.intake = intake; this.props = props;
  }

  // ── 错误
  @ExceptionHandler(Risk.PolicyDenied.class) ResponseEntity<Map<String, Object>> denied(Risk.PolicyDenied e) { return ResponseEntity.status(403).body(Map.of("error", "policy_denied", "message", String.valueOf(e.getMessage()))); }
  @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) { return ResponseEntity.badRequest().body(e instanceof UserError u ? ApiErrors.body(u.code, String.valueOf(u.getMessage()), u.params, null) : ApiErrors.human("bad_request", "请求内容不正确，请检查后重试", e)); }
  @ExceptionHandler(com.x7x24.tenant.WorkspaceService.Forbidden.class) ResponseEntity<Map<String, Object>> wsForbidden(RuntimeException e) { return ResponseEntity.status(403).body(Map.of("error", "forbidden", "message", String.valueOf(e.getMessage()))); }
  @ExceptionHandler(com.x7x24.tenant.WorkspaceService.NotMember.class) ResponseEntity<Map<String, Object>> wsNotMember(RuntimeException e) { return ResponseEntity.status(404).body(Map.of("error", "not_found", "message", String.valueOf(e.getMessage()))); }
  @ExceptionHandler(Exception.class) ResponseEntity<Map<String, Object>> err(Exception e) { return ResponseEntity.status(500).body(ApiErrors.human("server_error", "服务器处理出错了，请稍后重试；一直出现的话，请把「详情」发给管理员", e)); }

  @GetMapping("/health") public Map<String, Object> health() { String c = System.getenv("RENDER_GIT_COMMIT"); return Map.of("ok", true, "now", clk.iso(), "commit", c == null ? "" : c.substring(0, Math.min(7, c.length()))); }

  @GetMapping("/meta") public Map<String, Object> meta(jakarta.servlet.http.HttpServletRequest req) {
    var active = kill.active(); List<Map<String, Object>> ls = new ArrayList<>();
    for (var l : lines.list()) {
      Map<String, Object> m = new LinkedHashMap<>(); m.put("id", l.id()); m.put("name", l.name()); m.put("autonomy", l.autonomyLevel());
      m.put("state", l.state()); m.put("description", l.description()); m.put("paused", l.paused() != 0 || active.stream().anyMatch(k -> ("line:" + l.id()).equals(k.get("scope")))); m.put("intakePausedUntil", l.intakePausedUntil()); m.put("config", l.cfg()); ls.add(m);
    }
    Map<String, Object> o = new LinkedHashMap<>(); o.put("now", clk.iso()); o.put("range", board.defaultRange()); o.put("lines", ls); o.put("killSwitches", active);
    o.put("projects", projects.list().stream().map(p -> CatalogApi.projectDto(p, projects.runtime(p.id()))).toList());
    o.put("pipelineVersion", "v1"); o.put("nodes", Status.BOARD_NODES); o.put("models", Map.of("exec", providers.exec().model(), "judges", providers.judges().stream().map(j -> j.model()).toList(), "arbiter", providers.arbiter().model(), "mode", providers.mode()));
    o.put("configVersion", cfg.version()); o.put("adapterMode", settings.adapterMode()); o.put("live", tools.live()); o.put("connected", creds.hasToken()); o.put("executeMode", tools.live() && !settings.dryRun());
    String userId = req.getAttribute(com.x7x24.auth.AuthFilter.ATTR) instanceof com.x7x24.auth.AuthService.User user ? user.id() : null;
    o.put("githubConnection", githubAuth.connection(userId, false));
    return o;
  }

  @GetMapping(value = "/events", produces = "text/event-stream") public SseEmitter events(jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-cache, no-transform"); response.setHeader("X-Accel-Buffering", "no");
    return sse.open();
  }
  @GetMapping("/agents") public Object agents() { return Agents.ALL; }
  @GetMapping("/board") public Map<String, Object> board(@RequestParam(required = false) String projectId, @RequestParam(required = false) String productLine, @RequestParam(required = false) String from, @RequestParam(required = false) String to) { validateScope(productLine, projectId); return board.board(productLine, projectId, from, to); }

  @org.springframework.beans.factory.annotation.Autowired NodeBoard nodeBoard;
  /** 工作区汇总的流程节点看板（方案 A）：业务线 / 项目 / 栈筛选只缩小范围 */
  @GetMapping("/board/pipeline") public Map<String, Object> boardPipeline(@RequestParam(required = false) String productLine, @RequestParam(required = false) String projectId, @RequestParam(required = false) String stack, @RequestParam(required = false, defaultValue = "30") int days) {
    validateScope(productLine, projectId); return nodeBoard.board(productLine, projectId, stack, Math.max(1, Math.min(days, 180)));
  }
  @GetMapping("/board/pipeline/node/{nodeId}") public Map<String, Object> boardPipelineNode(@PathVariable String nodeId, @RequestParam(required = false) String productLine, @RequestParam(required = false) String projectId, @RequestParam(required = false) String stack) {
    validateScope(productLine, projectId); return nodeBoard.node(nodeId, productLine, projectId, stack);
  }
  private void validateScope(String productLine, String projectId) {
    if (productLine != null && !productLine.isBlank()) lines.require(productLine);
    if (projectId != null && !projectId.isBlank()) projects.resolve(projectId, productLine == null || productLine.isBlank() ? null : productLine);
  }
  // ── 任务
  Map<String, Object> dto(Task t) {
    Map<String, Object> o = new LinkedHashMap<>();
    var project = projects.require(t.projectId); var context = projects.forTask(t);
    { var dm = com.x7x24.execution.agent.DevMode.resolve(t, context.cfg()); var m = new LinkedHashMap<String, Object>(); m.put("mode", dm.mode()); m.put("source", dm.source()); m.put("override", t.context.devModeOverride); o.put("devMode", m); }
    o.put("projectId", t.projectId); o.put("projectName", project.name()); o.put("productLineName", lines.require(t.productLine).name());
    String runState = t.paused != 0 || context.paused() != 0 ? "PAUSED" : kill.isBlocked(t.productLine, t.projectId, t.st() == Status.DELIVERED || t.st() == Status.RELEASED ? "release" : "write", null) != null || budget.projectStatus(t.projectId).equals("hard") || budget.lineStatus(t.productLine).equals("hard") ? "BLOCKED" : t.executionState;
    if ("WAITING_CONFIGURATION".equals(t.executionState)) runState = t.executionState;
    o.put("version",t.version);o.put("recovery",engine.recoveryInfo(t.id));
    o.put("delivery", ReleasePolicy.describe(t, t.feedbackDone != 0 && t.context.projectSnapshot != null ? t.context.projectSnapshot : context.cfg()));
    o.put("currentStage", t.currentStage); o.put("executionState", runState); o.put("acceptanceCriteria", t.acceptanceCriteria); o.put("pipelineVersion", t.pipelineVersion); o.put("projectConfigVersion", t.context.projectConfigVersion);
    o.put("id", t.id); o.put("productLine", t.productLine); o.put("title", t.title); o.put("problem", t.problem); o.put("priorityScore", t.priorityScore); o.put("riskLevel", t.riskLevel); o.put("status", t.status);
    o.put("path", t.path); o.put("scopeLevel", t.scopeLevel); o.put("strategyVariant", t.strategyVariant); o.put("currentIteration", t.currentIteration); o.put("loopCount", t.loopCount); o.put("rejectCount", t.rejectCount);
    o.put("reviewRetry", t.reviewRetry); o.put("rollbackCount", t.rollbackCount); o.put("requeueCount", t.requeueCount); o.put("degradeCount", t.degradeCount); o.put("healCount", t.healCount);
    o.put("discardReason", t.discardReason); o.put("cooldownUntil", t.cooldownUntil); o.put("parentTaskId", t.parentTaskId); o.put("paused", t.paused != 0);
    o.put("budgetTokens", t.budgetTokens); o.put("spentTokens", t.spentTokens); o.put("budgetUsd", t.budgetUsd); o.put("spentUsd", U.round(t.spentUsd, 3)); o.put("budgetCi", t.budgetCi); o.put("spentCi", U.round(t.spentCi, 2)); o.put("spentIterations", t.spentIterations); o.put("budgetIterations", com.x7x24.gov.BudgetService.iterLimit(t)); o.put("budgetSwitches", budget.switches(t).toMap());
    o.put("deleted", t.deletedAt != null); o.put("deletedAt", t.deletedAt); o.put("createdAt", t.createdAt); o.put("statusChangedAt", t.statusChangedAt); o.put("releasedAt", t.releasedAt); o.put("nextRunAt", t.nextRunAt);
    Map<String, Object> links = new LinkedHashMap<>(); var c = t.context;
    if (c.issue != null) links.put("issue", Map.of("number", c.issue.number, "url", String.valueOf(c.issue.url)));
    if (c.pr != null) links.put("pr", Map.of("number", c.pr.number, "url", String.valueOf(c.pr.url), "branch", String.valueOf(c.pr.branch)));
    if (c.ci != null && c.ci.url != null) links.put("ci", Map.of("url", c.ci.url, "state", String.valueOf(c.ci.state), "minutes", c.ci.minutes));
    if (c.merge != null && c.merge.sha != null) links.put("merge", Map.of("sha", c.merge.sha, "mainRunUrl", String.valueOf(c.merge.mainRunUrl)));
    if (c.rel != null && c.rel.url != null) links.put("release", Map.of("tag", String.valueOf(c.rel.tag), "url", c.rel.url));
    o.put("links", links);
    return o;
  }

  @GetMapping("/tasks") public List<Map<String, Object>> tasks(@RequestParam(required = false) String node, @RequestParam(required = false) String status, @RequestParam(required = false) String productLine, @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(defaultValue = "100") int limit) {
    validateScope(productLine, projectId);
    var rng = from != null && to != null ? Map.of("from", from, "to", to) : board.defaultRange();
    List<String> w = new ArrayList<>(List.of("created_at >= ?", "created_at < ?", "deleted_at IS NULL")); List<Object> p = new ArrayList<>(List.of(Board.lo(rng.get("from")), Board.hi(rng.get("to"))));
    List<String> sts = status == null || status.isBlank() ? null : Arrays.asList(status.split(","));
    if (node != null && !node.isBlank()) sts = Status.BOARD_NODES.stream().filter(n -> n.id().equals(node)).findFirst().map(n -> n.statuses().stream().map(Enum::name).toList()).orElse(List.of());
    if (sts != null && !sts.isEmpty()) { w.add("status IN (" + String.join(",", sts.stream().map(s -> "?").toList()) + ")"); p.addAll(sts); }
    if (productLine != null && !productLine.isBlank()) { w.add("product_line = ?"); p.add(productLine); }
    if (projectId != null && !projectId.isBlank()) { w.add("project_id = ?"); p.add(projectId); }
    p.add(Math.max(1, Math.min(limit, 500)));
    List<Map<String, Object>> out = new ArrayList<>();
    for (var t : repo.query(String.join(" AND ", w) + " ORDER BY status_changed_at DESC LIMIT ?", p.toArray())) out.add(dto(t));
    return out;
  }

  @GetMapping("/tasks/{id}") public ResponseEntity<Object> task(@PathVariable String id) {
    var t = repo.get(id); if (t == null) return ResponseEntity.status(404).body(Map.of("error", "找不到这条记录，可能已被删除", "code", "not_found"));
    List<Map<String, Object>> tl = audit.list(id, null, 500, 0); Collections.reverse(tl);
    List<Map<String, Object>> ev = new ArrayList<>();
    for (var e : evidence.list(id)) {
      Map<String, Object> m = new LinkedHashMap<>(); m.put("id", e.get("id")); m.put("kind", e.get("kind")); m.put("stage", e.get("stage")); m.put("iteration", e.get("iteration_seq")); m.put("uri", e.get("uri")); m.put("sha256", e.get("sha256"));
      m.put("producedBy", e.get("produced_by")); m.put("model", e.get("model")); m.put("createdAt", e.get("created_at")); m.put("verified", evidence.verifyOne(e)); m.put("content", Json.tree((String) e.get("content"))); ev.add(m);
    }
    List<Map<String, Object>> an = new ArrayList<>(); for (var a : db.rows("SELECT * FROM anomaly WHERE task_id=? ORDER BY opened_at", id)) { var m = new LinkedHashMap<>(a); m.put("heal_steps", Json.tree((String) a.get("heal_steps"))); an.add(m); }
    Map<String, Object> o = new LinkedHashMap<>(); o.put("task", dto(t)); o.put("discardDetail", t.discardDetail); o.put("plan", t.plan); o.put("failures", t.context.failures);
    var line = lines.get(t.productLine); o.put("line", line == null ? null : line.name()); o.put("project", CatalogApi.projectDto(projects.require(t.projectId), projects.forTask(t))); o.put("repo", projects.forTask(t).cfg().repo);
    o.put("stageExecutions", stages.list(id));
    o.put("timeline", tl); o.put("verdicts", verdicts.list(id)); o.put("evidence", ev); o.put("anomalies", an);
    o.put("iterations", db.rows("SELECT * FROM iteration WHERE task_id=? ORDER BY seq", id)); o.put("replayStatus", engine.replayStatus(id));
    { var pinned = engine.eff(t); String latest; try { latest = pipes.latestFor(t.productLine, t.context.configSnapshot).id; } catch (Exception ex) { latest = null; }
      Map<String, Object> cf = new LinkedHashMap<>(); cf.put("pinned", pinned.id); cf.put("template", pinned.template); cf.put("latest", latest); cf.put("upgradable", latest != null && !latest.equals(t.context.configSnapshot)); cf.put("modelPins", t.context.modelPins); cf.put("schema", pinned.schema); o.put("config", cf); }
    o.put("context", Map.of("issue", Optional.ofNullable((Object) t.context.issue).orElse(Map.of()), "pr", Optional.ofNullable((Object) t.context.pr).orElse(Map.of()), "ci", Optional.ofNullable((Object) t.context.ci).orElse(Map.of()),
        "merge", Optional.ofNullable((Object) t.context.merge).orElse(Map.of()), "release", Optional.ofNullable((Object) t.context.rel).orElse(Map.of())));
    return ResponseEntity.ok(o);
  }

  public record SignalBody(String productLine, String title, String content, String source, String projectId, String acceptanceCriteria, String devMode) {}
  /** 手动创建需求：同步在目标仓库建 Issue（打 7x24:signal 标签），再入池。 */
  @PostMapping("/signals") public Object signals(@RequestBody SignalBody b) { return create(b, "manual"); }
  @PostMapping("/tasks") public Object createTask(@RequestBody SignalBody b) { return create(b, "manual-task"); }
  private Object create(SignalBody b, String src) {
    String projectId = projects.resolve(b.projectId(), b.productLine());
    if (b.title() == null || b.title().trim().length() < 2 || b.title().trim().length() > 500) throw new UserError("title_length", "任务标题需要 2–500 个字，请修改后再提交");
    String mode = com.x7x24.execution.agent.DevMode.normalize(b.devMode());
    var r = engine.ingest(new Engine.Ingest(b.productLine(), b.title().trim(), b.content() == null ? "" : b.content(), b.source() != null ? b.source() : src, null, null, tools.live(), null, projectId, b.acceptanceCriteria()));
    if (mode != null && r.taskId() != null && !r.duplicate()) engine.setDevMode(r.taskId(), null, mode, "operator");
    return r;
  }
  public record DevModeBody(String devMode, Integer expectedVersion) {}
  /** 任务级开发方式：成员可改（只读成员不行）；null / inherit = 跟随项目。 */
  @PatchMapping("/tasks/{id}/dev-mode") public Object devMode(@PathVariable String id, @RequestBody DevModeBody b, jakarta.servlet.http.HttpServletRequest r) {
    com.x7x24.tenant.WorkspaceFilter.requireMember(r);
    return dto(engine.setDevMode(id, b.expectedVersion(), b.devMode(), PipelineApi.actor(r)));
  }

  // ── 已废弃任务：编辑 / 编辑后重启 / 删除（软删除，审计留痕）
  public record TaskEditBody(String title, String problem, String acceptanceCriteria) {}
  @PatchMapping("/tasks/{id}") public Object editTask(@PathVariable String id, @RequestBody TaskEditBody b) { return dto(engine.editDiscarded(id, b.title(), b.problem(), b.acceptanceCriteria(), false, "operator")); }
  @PostMapping("/tasks/{id}/restart") public Object restartTask(@PathVariable String id, @RequestBody(required = false) TaskEditBody b) {
    var e = b == null ? new TaskEditBody(null, null, null) : b; return dto(engine.editDiscarded(id, e.title(), e.problem(), e.acceptanceCriteria(), true, "operator"));
  }
  @PostMapping("/tasks/{id}/config-upgrade") public Object upgradeConfig(@PathVariable String id, jakarta.servlet.http.HttpServletRequest r) { return dto(engine.upgradeConfig(id, PipelineApi.actor(r))); }
  @PatchMapping("/tasks/{id}/budget-switches") public Object budgetSwitches(@PathVariable String id, @RequestBody com.fasterxml.jackson.databind.JsonNode b, jakarta.servlet.http.HttpServletRequest r) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(r, "修改任务预算设置");
    java.util.function.Function<String, Boolean> v = k -> b.get(k) == null || b.get(k).isNull() ? null : b.get(k).asBoolean();
    return dto(engine.setBudgetSwitches(id, b.has("budgetOff"), v.apply("budgetOff"), b.has("degradeOff"), v.apply("degradeOff"), PipelineApi.actor(r)));
  }
  @GetMapping("/tasks/{id}/recovery") public Object taskRecovery(@PathVariable String id) {return engine.recoveryInfo(id);}
  public record RecoveryBody(Integer expectedVersion) {}
  @PostMapping({"/tasks/{id}/resume","/tasks/{id}/restore"}) public Object resumeTask(@PathVariable String id,@RequestBody(required=false) RecoveryBody b,jakarta.servlet.http.HttpServletRequest r) {
    if(repo.get(id)!=null && repo.get(id).st()==Status.DISCARDED)com.x7x24.tenant.WorkspaceFilter.requireManager(r,"恢复已结束任务");
    return dto(b!=null && b.expectedVersion()!=null?engine.resumeTask(id,b.expectedVersion(),PipelineApi.actor(r)):engine.resumeAnomaly(id,PipelineApi.actor(r)));
  }
  @DeleteMapping("/tasks/{id}") public Object deleteTask(@PathVariable String id) { engine.deleteDiscarded(id, "operator"); return Map.of("id", id, "deleted", true); }

  // ── 引擎 / 时钟
  @GetMapping("/clock") public Map<String, Object> clock() { return Map.of("now", clk.iso()); }
  /** 手动加速调度：立即执行一次 tick（不造数据）。 */
  @PostMapping("/engine/tick") public Map<String, Object> tick() { int n = engine.tick(); return Map.of("processed", n, "now", clk.iso()); }
  @PostMapping("/engine/intake") public Map<String, Object> pollIntake() { return Map.of("ingested", intake.poll()); }
  /** 仅测试 profile 可用的时钟拨动 */
  @PostMapping("/clock/advance") public Object advance(@RequestBody Map<String, Object> b) {
    if (!props.engine.testClock) return ResponseEntity.status(404).body(Map.of("error", "测试时钟未开启", "code", "test_clock_disabled"));
    clk.advance((long) (((Number) b.getOrDefault("hours", 0)).doubleValue() * U.HOUR)); return Map.of("now", clk.iso());
  }

  // ── 治理配置
  @GetMapping("/config") public Map<String, Object> config() { return Map.of("version", cfg.version(), "config", cfg.get()); }
  @PutMapping("/config") public Map<String, Object> putConfig(@RequestBody JsonNode patch, jakarta.servlet.http.HttpServletRequest req) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(req, "修改治理配置");
    int before = cfg.version(); var g = cfg.update(patch);
    audit.sys("CONFIG_UPDATED", "operator", Map.of("patch", patch, "from", before, "to", cfg.version()), null);
    return Map.of("version", cfg.version(), "config", g);
  }
  @GetMapping("/budget") public Map<String, Object> budget() { return budget.snapshot(lines.list().stream().map(LineRepo.Line::id).toList()); }

  // ── kill switch
  @GetMapping("/kill-switch") public Map<String, Object> killSwitch() { return Map.of("active", kill.active(), "history", kill.all()); }
  public record KillBody(String mode, String scope, String reason) {}
  @PostMapping("/kill-switch") public Map<String, Object> killOn(@RequestBody KillBody b, jakarta.servlet.http.HttpServletRequest req) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(req, "操作熔断开关");
    if (!List.of("READ_ONLY", "FREEZE_RELEASE", "HALT").contains(b.mode())) throw new UserError("bad_kill_mode", "紧急停止的模式只能是：只读、冻结发布或全部停止");
    // 工作区内的开关只作用于本工作区；作用范围里的产品线 / 项目必须属于本工作区
    String sc = b.scope() == null ? "global" : b.scope();
    if (sc.startsWith("line:") && lines.get(sc.substring(5)) == null) throw new IllegalArgumentException("产品线不存在");
    if (sc.startsWith("project:") && projects.get(sc.substring(8)) == null) throw new IllegalArgumentException("项目不存在");
    return kill.activate(b.scope() == null ? "global" : b.scope(), b.mode(), "manual", b.reason() == null ? "手动触发" : b.reason(), null);
  }
  @DeleteMapping("/kill-switch/{id}") public ResponseEntity<Object> killOff(@PathVariable String id, jakarta.servlet.http.HttpServletRequest req) { com.x7x24.tenant.WorkspaceFilter.requireManager(req, "操作熔断开关"); return kill.release(id, "operator") ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.status(404).body(Map.of("error", "这个紧急停止已经解除了", "code", "not_active")); }

  public record PolicyBody(String type, String target, String actor) {}
  @PostMapping("/policy/evaluate") public Object policy(@RequestBody PolicyBody b) {
    var d = Risk.evaluateAction(cfg.get(), b.type(), b.target());
    if (!d.allowed()) audit.sys("ACTION_DENIED", b.actor() == null ? "agent" : b.actor(), Map.of("action", b.type(), "target", String.valueOf(b.target()), "category", String.valueOf(d.category()), "reason", d.reason()), null);
    return d;
  }

  // ── 适配器
  @GetMapping("/adapters") public Map<String, Object> adapters() {
    Map<String, Object> o = new LinkedHashMap<>(tools.status());
    List<Object> ls = new ArrayList<>(); for (var p : projects.list()) ls.add(tools instanceof GitHubTools g ? g.lineStatus(p.cfg()) : Map.of("id", p.id(), "name", p.name(), "productLineId", p.productLineId()));
    o.put("projects", ls); o.put("lines", ls); o.put("stats", calls.stats()); o.put("recentCalls", calls.recent(50));
    o.put("llm", Map.of("mode", providers.mode(), "exec", providers.exec().model(), "judges", providers.judges().stream().map(j -> j.model()).toList()));
    return o;
  }

  /** LLM 用量台账汇总（来自 budget_ledger；usd 为按可配置单价得出的估算值，不是账单）。 */
  @GetMapping("/llm/usage") public Map<String, Object> llmUsage(@RequestParam(required = false) String taskId) {
    if (taskId != null && repo.get(taskId) == null) throw new IllegalArgumentException("任务不存在");
    String ws = com.x7x24.tenant.Tenant.readFilter(); List<Object> args = new ArrayList<>(); String w = "tokens > 0";
    if (taskId != null) { w += " AND task_id = ?"; args.add(taskId); } if (ws != null) { w += " AND workspace_id = ?"; args.add(ws); } Object[] a = args.toArray();
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("byModel", db.rows("SELECT model, COUNT(*) calls, SUM(tokens) tokens, SUM(usd) usd FROM budget_ledger WHERE " + w + " GROUP BY model ORDER BY model", a));
    o.put("byAgent", db.rows("SELECT agent, model, COUNT(*) calls, SUM(tokens) tokens, SUM(usd) usd FROM budget_ledger WHERE " + w + " GROUP BY agent, model ORDER BY agent, model", a));
    o.put("total", db.row("SELECT COUNT(*) calls, COALESCE(SUM(tokens),0) tokens, COALESCE(SUM(usd),0) usd FROM budget_ledger WHERE " + w, a));
    o.put("note", "usd 为按 LLM_PRICE_IN_PER_1M / LLM_PRICE_OUT_PER_1M 估算的值（可调占位单价，非官方报价）；calls 为记账条数（同一次调用的校验重试合并为一条）。");
    return o;
  }

  // ── 审计 / 证据
  @GetMapping("/audit") public Map<String, Object> audit(@RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset, @RequestParam(required = false) String type, @RequestParam(required = false) String taskId) {
    if (taskId != null && repo.get(taskId) == null) return Map.of("total", 0, "items", List.of());
    return Map.of("total", audit.count(), "head", audit.head(), "items", audit.list(taskId, type, Math.min(limit, 500), offset));
  }
  /** 哈希链是平台级的：校验结果只给出是否完整与条数，锚点只给平台管理员 */
  @GetMapping("/audit/verify") public Map<String, Object> verify() { var o = new LinkedHashMap<>(audit.verify()); if (com.x7x24.tenant.Tenant.readFilter() == null) o.put("anchors", db.rows("SELECT * FROM audit_anchor ORDER BY id DESC LIMIT 5")); else { o.remove("brokenAt"); o.put("anchors", List.of()); } return o; }
  @GetMapping("/evidence/{id}") public ResponseEntity<Object> evidence(@PathVariable String id) {
    var e = evidence.get(id); if (e == null || e.get("task_id") != null && repo.get((String) e.get("task_id")) == null) return ResponseEntity.status(404).body(Map.of("error", "找不到这条记录，可能已被删除", "code", "not_found"));
    var m = new LinkedHashMap<>(e); m.put("content", Json.tree((String) e.get("content"))); m.put("verified", evidence.verifyOne(e)); return ResponseEntity.ok(m);
  }
}
