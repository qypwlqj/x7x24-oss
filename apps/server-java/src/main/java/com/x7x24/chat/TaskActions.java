package com.x7x24.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.x7x24.adapters.Redactor;
import com.x7x24.core.Status;
import com.x7x24.db.Db;
import com.x7x24.engine.Decision;
import com.x7x24.engine.Engine;
import com.x7x24.engine.Task;
import com.x7x24.engine.TaskRepo;
import com.x7x24.engine.RecoveryService;
import com.x7x24.gov.AuditLog;
import com.x7x24.gov.BudgetService;
import com.x7x24.llm.LlmProvider;
import com.x7x24.pipeline.Nodes;
import com.x7x24.tenant.Tenant;
import com.x7x24.tenant.WorkspaceService;
import com.x7x24.util.Clk;
import com.x7x24.util.Json;
import com.x7x24.util.U;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 指挥台 v2 M3：在会话里控制流程（docs/command-center-v2.md 第 5 节）。
 * 按钮或自由文本都只会生成「提议」，用户确认后才执行；确认时服务端按角色、任务状态（乐观锁）再校验一次，不信前端。
 * 每次执行都写一条时间线事件（TASK_ACTION_EXECUTED + 动作本身的事件）。
 */
public class TaskActions {
  public static final String RESUME = "task_resume", PAUSE = "task_pause", RETRY = "node_retry", REDIRECT = "task_redirect", SKIP = "node_skip", ANSWER = "answer_question", APPROVE = "approval_decide", ABANDON = "task_abandon", RESTORE = "task_restore", INITIALIZE = "repository_initialize_and_resume", DEV_MODE = "dev_mode_retry", REVIEW_SKIP_FAILED = "code_review_skip_failed";
  public record Spec(String minRole, String danger, String zh, String confirmWord) {}
  public static final Map<String, Spec> SPECS = new LinkedHashMap<>();
  static {
    SPECS.put(RESUME, new Spec("member", "low", "继续", null)); SPECS.put(PAUSE, new Spec("member", "low", "暂停", null));
    SPECS.put(RETRY, new Spec("member", "low", "重试当前节点", null)); SPECS.put(REDIRECT, new Spec("member", "mid", "改方向", null));
    SPECS.put(SKIP, new Spec("admin", "high", "跳过检查", "跳过")); SPECS.put(ANSWER, new Spec("member", "low", "回答问题", null));
    SPECS.put(APPROVE, new Spec("admin", "high", "审批合并", "批准")); SPECS.put(ABANDON, new Spec("admin", "high", "放弃任务", "放弃"));
    SPECS.put(RESTORE, new Spec("admin", "mid", "恢复已放弃的任务", null));
    SPECS.put(INITIALIZE, new Spec("admin", "mid", "初始化仓库并继续", null));
    SPECS.put(DEV_MODE, new Spec("member", "mid", "本任务改用快速方式重试", null));
    SPECS.put(REVIEW_SKIP_FAILED, new Spec("admin", "mid", "跳过没审完的组并继续", null));
  }
  /** 服务端硬规则：这些节点 / 检查不能跳 */
  public static final Set<String> UNSKIPPABLE_NODES = Set.of("SEC_AUDIT", "CHECK_SQL_SAFETY", "ACCEPTANCE", "QA", "DEV", "PRD_REVIEW", "INTAKE", "PLANNING", "RELEASE", "CODE_REVIEW");
  public static final Set<String> UNSKIPPABLE_CHECKS = Set.of("sql-safety-lint", "ci", "security", "sec-audit");
  static final Set<Status> ENDED = Set.of(Status.RELEASED, Status.DISCARDED);

  public record Ctx(String login, String role) {}
  public static class Conflict extends ChatService.Conflict { public Conflict(String m) { super(m); } }

  private final Db db; private final Clk clk; private final AuditLog audit; private final TaskRepo repo; private final Engine engine; private final BudgetService budget; private final Supplier<LlmProvider> llm;
  private final Map<String, Object> locks = new ConcurrentHashMap<>();
  public TaskActions(Db db, Clk clk, AuditLog audit, TaskRepo repo, Engine engine, BudgetService budget, Supplier<LlmProvider> llm) { this.db = db; this.clk = clk; this.audit = audit; this.repo = repo; this.engine = engine; this.budget = budget; this.llm = llm; }

  /** 乐观锁的键：状态 + 节点 + 轮次 + 暂停。任务推进 / 被别人操作后键就变了 */
  public static String lockKey(Task t) { return t.status + ":" + t.currentStage + ":" + t.currentIteration + ":" + t.paused + ":v" + t.version; }
  static boolean member(String role) { return WorkspaceService.rank(role) <= 2; }
  static boolean allowed(String role, String minRole) { return "admin".equals(minRole) ? WorkspaceService.manager(role) : member(role); }
  Task task(String id) { var t = repo.get(id); if (t == null || t.deletedAt != null) throw new WorkspaceService.NotMember(); return t; }

  // ───────── 提议
  public Map<String, Object> propose(String taskId, String kind, JsonNode raw, String source, String sessionId, Ctx who) {
    if (!member(who.role())) throw new WorkspaceService.Forbidden("只读成员只能提问，不能操作流程");
    Spec sp = SPECS.get(kind); if (sp == null) throw new IllegalArgumentException("不支持的操作：" + kind);
    synchronized (locks.computeIfAbsent(taskId, k -> new Object())) {
      Task t = task(taskId); var p = normalize(t, kind, raw == null || !raw.isObject() ? Json.M.createObjectNode() : raw);
      var other = db.row("SELECT id, proposed_by FROM task_action WHERE task_id=? AND workspace_id=? AND status='proposed'", taskId, Tenant.id());
      if (other != null) {
        if (!Objects.equals(other.get("proposed_by"), who.login())) throw new Conflict("@" + other.get("proposed_by") + " 已经提了一个待确认的操作，请先处理那一个（同一任务同一时间只能有一个待确认的流程操作）");
        db.update("UPDATE task_action SET status='superseded', decided_at=? WHERE id=?", clk.iso(), other.get("id"));
      }
      String id = "ta_" + Long.toHexString(new java.security.SecureRandom().nextLong() & Long.MAX_VALUE);
      db.update("INSERT INTO task_action(id,workspace_id,task_id,session_id,kind,params,danger,min_role,expected_key,source,status,proposed_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
          id, Tenant.id(), taskId, sessionId, kind, Json.str(p), sp.danger(), sp.minRole(), lockKey(t), "text".equals(source) ? "text" : "button", "proposed", who.login(), clk.iso());
      return get(taskId, id);
    }
  }

  /** 白名单参数 + 业务规则（跳过的硬规则在提议和执行时都检查） */
  Map<String, Object> normalize(Task t, String kind, JsonNode a) {
    Map<String, Object> p = new LinkedHashMap<>(); Status st = t.st();
    if (ENDED.contains(st) && !RESTORE.equals(kind) && !(RESUME.equals(kind) && "WAITING_CONFIGURATION".equals(t.executionState))) throw new IllegalArgumentException("任务已结束，不能再操作流程");
    switch (kind) {
      case INITIALIZE -> p.putAll(Json.M.convertValue(engine.repositoryInitialization(t.id), Map.class));
      case RESUME -> { if (!RecoveryService.stopped(t) && st != Status.ANOMALY && st != Status.DEGRADED && t.paused == 0) throw new IllegalArgumentException("任务没有暂停或卡住，不需要继续"); }
      case PAUSE -> assertCanPause(t);
      case RETRY -> { p.put("nodeId", t.currentStage); p.put("hint", U.ellipsis(a.path("hint").asText("").strip(), 1000)); }
      case REDIRECT -> {
        String ins = a.path("instruction").asText("").strip(); if (ins.length() < 2) throw new IllegalArgumentException("请说明要怎么改");
        p.put("instruction", U.ellipsis(ins, 2000));
        String target = a.path("backTo").asText("DEV");
        if (!Set.of("DEV", "PLANNING").contains(target) && !(RecoveryService.stopped(t) && engine.designAdjustmentTargets(t).contains(target)))
          throw new IllegalArgumentException("请选择开发、规划或任务中已有的设计节点");
        p.put("backTo", target);
      }
      case SKIP -> {
        String node = a.path("nodeId").asText("").strip(); var def = Nodes.def(node);
        if (def == null) throw new IllegalArgumentException("没有这个节点：" + node);
        if (UNSKIPPABLE_NODES.contains(node) || def.check == null || UNSKIPPABLE_CHECKS.contains(def.check) || !"check".equals(def.family))
          throw new IllegalArgumentException("「" + def.label + "」不能跳过（安全审计、SQL 安全、验收等是硬规则，只有附加检查能跳）");
        String why = a.path("reason").asText("").strip(); if (why.length() < 2) throw new IllegalArgumentException("跳过需要写明原因");
        p.put("nodeId", node); p.put("label", def.label); p.put("check", def.check); p.put("reason", U.ellipsis(why, 500));
      }
      case ANSWER -> { String ans = a.path("answer").asText("").strip(); if (ans.isEmpty()) throw new IllegalArgumentException("回答不能为空"); p.put("questionId", a.path("questionId").asText(null)); p.put("answer", U.ellipsis(ans, 4000)); }
      case APPROVE -> {
        if (st != Status.DELIVERED) throw new IllegalArgumentException("这个任务现在不在等合并审批");
        String d = a.path("approve").asBoolean(true) ? "approve" : "return"; p.put("decision", d); p.put("comment", U.ellipsis(a.path("comment").asText("").strip(), 1000));
        p.put("headSha", t.context.pr == null ? null : t.context.pr.headSha);
      }
      case ABANDON -> { String why = a.path("reason").asText("").strip(); if (why.length() < 2) throw new IllegalArgumentException("放弃需要写明原因"); p.put("reason", U.ellipsis(why, 500)); }
      case RESTORE -> { if (!canRestore(t)) throw new IllegalArgumentException("当前任务没有可恢复的结束记录"); }
      case DEV_MODE -> { String m = com.x7x24.execution.agent.DevMode.normalize(a.path("devMode").asText(com.x7x24.execution.agent.DevMode.LEGACY)); p.put("devMode", m == null ? com.x7x24.execution.agent.DevMode.LEGACY : m); }
      case REVIEW_SKIP_FAILED -> {
        if (!RecoveryService.stopped(t) || !Engine.CODE_REVIEW_FAILED.equals(engine.recoveryInfo(t.id).get("code"))) throw new IllegalArgumentException("任务现在不是因为逐行审查没审完而停下的");
        p.put("node", t.currentStage);
      }
      default -> throw new IllegalArgumentException("不支持的操作");
    }
    if(Set.of(RESUME,RETRY,RESTORE).contains(kind)) {
      var rework=engine.recoveryInfo(t.id).get("rework");
      if(rework!=null)p.put("rework",rework);
    }
    return p;
  }
  /** 页面入口和执行校验使用同一恢复规则；权限仍在提议 / 确认时独立检查。 */
  private static boolean canRestore(Task t) { return t.st() == Status.DISCARDED && t.deletedAt==null; }
  public static void assertCanPause(Task t) {
    if (t.paused != 0) throw new IllegalArgumentException("任务已经暂停了");
    if (Set.of(Status.ANOMALY, Status.DEGRADED, Status.RELEASED, Status.DELIVERED, Status.DISCARDED).contains(t.st())
        || Set.of("BLOCKED", "FAILED", "PAUSED", "COMPLETED", "DISCARDED").contains(String.valueOf(t.executionState)))
      throw new IllegalArgumentException("任务已阻塞或结束，不能再次暂停；请先处理阻塞原因");
  }

  // ───────── 查询
  public List<Map<String, Object>> pending(String taskId) { return db.rows("SELECT * FROM task_action WHERE task_id=? AND workspace_id=? AND status='proposed' ORDER BY created_at", taskId, Tenant.id()).stream().map(TaskActions::dto).toList(); }
  public List<Map<String, Object>> history(String taskId) { return db.rows("SELECT * FROM task_action WHERE task_id=? AND workspace_id=? ORDER BY created_at DESC LIMIT 20", taskId, Tenant.id()).stream().map(TaskActions::dto).toList(); }
  /** 待我处理：本工作区等管理员确认的流程操作（成员提的高危动作） */
  public List<Map<String, Object>> awaitingManager() {
    return db.rows("SELECT a.*, t.title FROM task_action a JOIN task t ON t.id=a.task_id WHERE a.workspace_id=? AND a.status='proposed' AND a.min_role='admin' ORDER BY a.created_at", Tenant.id()).stream().map(r -> { var m = dto(r); m.put("taskTitle", r.get("title")); return m; }).toList();
  }
  public Map<String, Object> get(String taskId, String id) { var r = db.row("SELECT * FROM task_action WHERE id=? AND task_id=? AND workspace_id=?", id, taskId, Tenant.id()); if (r == null) throw new WorkspaceService.NotMember(); return dto(r); }
  static Map<String, Object> dto(Map<String, Object> r) {
    Map<String, Object> m = new LinkedHashMap<>(); String kind = (String) r.get("kind"); Spec sp = SPECS.get(kind);
    m.put("id", r.get("id")); m.put("taskId", r.get("task_id")); m.put("kind", kind); m.put("label", sp == null ? kind : sp.zh()); m.put("params", Json.tree(Db.str(r.get("params"))));
    m.put("danger", r.get("danger")); m.put("minRole", r.get("min_role")); m.put("confirmWord", sp == null ? null : sp.confirmWord()); m.put("expectedKey", r.get("expected_key")); m.put("source", r.get("source"));
    m.put("status", r.get("status")); m.put("proposedBy", r.get("proposed_by")); m.put("blockedRole", r.get("blocked_role")); m.put("result", r.get("result") == null ? null : Json.tree(Db.str(r.get("result"))));
    m.put("error", r.get("error")); m.put("decidedBy", r.get("decided_by")); m.put("createdAt", r.get("created_at")); m.put("decidedAt", r.get("decided_at"));
    return m;
  }

  // ───────── 确认 / 取消
  public Map<String, Object> confirm(String taskId, String id, String confirmText, Ctx who) {
    synchronized (locks.computeIfAbsent(taskId, k -> new Object())) {
      var a = get(taskId, id); String st = (String) a.get("status"); String kind = (String) a.get("kind"); Spec sp = SPECS.get(kind);
      if ("executed".equals(st)) return a; // 幂等
      if (!"proposed".equals(st)) throw new Conflict("这个操作已" + switch (st) { case "cancelled" -> "取消"; case "superseded" -> "被新的提议替换"; case "stale" -> "过期"; default -> "失败"; } + "，不能再确认");
      if (!member(who.role())) throw denied(taskId, id, kind, "member", who, "只读成员不能确认流程操作");
      if (!allowed(who.role(), sp.minRole())) throw denied(taskId, id, kind, sp.minRole(), who, "「" + sp.zh() + "」需要工作区管理员确认，已放进管理员的「待我处理」");
      if (sp.confirmWord() != null && !sp.confirmWord().equals(confirmText == null ? null : confirmText.strip())) throw new IllegalArgumentException("这是高风险操作，请输入「" + sp.confirmWord() + "」再确认");
      Task t = task(taskId); String now = clk.iso();
      if (!lockKey(t).equals(a.get("expectedKey"))) {
        db.update("UPDATE task_action SET status='stale', decided_by=?, decided_at=? WHERE id=? AND status='proposed'", who.login(), now, id);
        var last = db.row("SELECT actor, event_type FROM task_event WHERE task_id=? ORDER BY id DESC LIMIT 1", taskId);
        throw new Conflict("任务在你提议之后有了变化（现在：" + t.status + (t.paused != 0 ? "，已暂停" : "") + (last == null ? "" : "，最近一次由 " + last.get("actor") + " 触发") + "），这个操作没有执行。请刷新后重新选择");
      }
      JsonNode p = (JsonNode) a.get("params"); Map<String, Object> result = new LinkedHashMap<>();
      try { execute(t, kind, p, who, result); }
      catch (RuntimeException e) {
        String msg = Redactor.redact(String.valueOf(e.getMessage()));
        db.update("UPDATE task_action SET status='failed', error=?, decided_by=?, decided_at=? WHERE id=? AND status='proposed'", U.ellipsis(msg, 500), who.login(), now, id);
        audit.task(taskId, t.currentIteration, "TASK_ACTION_FAILED", who.login(), Map.of("action", id, "kind", kind, "error", U.ellipsis(msg, 200)), "task-action-failed:" + id);
        if (msg.contains("请刷新") || msg.contains("optimistic")) throw new Conflict("任务刚刚被别人或系统改动了，这个操作没有执行。请刷新后重试");
        throw e;
      }
      db.update("UPDATE task_action SET status='executed', result=?, decided_by=?, decided_at=? WHERE id=? AND status='proposed'", Json.str(result), who.login(), now, id);
      Map<String, Object> ev = new LinkedHashMap<>(); ev.put("action", id); ev.put("kind", kind); ev.put("label", sp.zh()); ev.put("danger", sp.danger()); ev.put("params", Json.M.convertValue(p, Map.class)); ev.put("proposedBy", a.get("proposedBy")); ev.put("source", a.get("source")); ev.put("result", result);
      audit.task(taskId, repo.get(taskId).currentIteration, "TASK_ACTION_EXECUTED", who.login(), ev, "task-action:" + id);
      return get(taskId, id);
    }
  }
  private RuntimeException denied(String taskId, String id, String kind, String need, Ctx who, String msg) {
    db.update("UPDATE task_action SET blocked_role=? WHERE id=? AND status='proposed'", need, id);
    audit.sys("TASK_ACTION_DENIED", who.login(), Map.of("task", taskId, "action", id, "kind", kind, "role", String.valueOf(who.role())), "task-action-denied:" + id + ":" + who.login() + ":" + clk.iso());
    return new WorkspaceService.Forbidden(msg);
  }
  public Map<String, Object> cancel(String taskId, String id, Ctx who) {
    var a = get(taskId, id);
    if ("proposed".equals(a.get("status"))) {
      if (!member(who.role())) throw new WorkspaceService.Forbidden("只读成员不能取消操作");
      db.update("UPDATE task_action SET status='cancelled', decided_by=?, decided_at=? WHERE id=? AND status='proposed'", who.login(), clk.iso(), id);
    }
    return get(taskId, id);
  }

  void execute(Task t, String kind, JsonNode p, Ctx who, Map<String, Object> out) {
    String actor = who.login(); String id = t.id;
    switch (kind) {
      case INITIALIZE -> { var r = engine.initializeRepositoryAndResume(id, Json.convert(p, com.x7x24.adapters.DeliveryTools.RepositoryInit.class), actor); out.put("status", r.status); }
      case RESUME -> {var r=engine.resumeTask(id,t.version,actor);out.put("status",r.status);}
      case PAUSE -> { assertCanPause(t); t.paused = 1; save(t); audit.task(id, t.currentIteration, "TASK_PAUSED", actor, Map.of("paused", true), null); out.put("paused", true); }
      case RETRY -> {
        String hint = p.path("hint").asText("");
        if (!hint.isBlank()) { t.context.reviewNotes.add("负责人提示（重试 " + t.currentStage + "）：" + hint); }
        if (RecoveryService.stopped(t) || t.st()==Status.ANOMALY || t.st()==Status.DEGRADED) { save(t); var r = engine.resumeAnomaly(id, actor); out.put("status", r.status); }
        else { t.stepAttempts = 0; t.nextRunAt = clk.iso(); save(t); out.put("status", t.status); }
        audit.task(id, t.currentIteration, "NODE_RETRY_REQUESTED", actor, Map.of("node", String.valueOf(t.currentStage), "hint", hint), null);
      }
      case REDIRECT -> {
        String ins = p.path("instruction").asText(); String stamp = "补充要求（@" + actor + "，" + clk.iso().substring(0, 16) + "）：" + ins;
        String target = p.path("backTo").asText("DEV");
        if (!engine.designAdjustmentTargets(t).contains(target))
          t.acceptanceCriteria = (t.acceptanceCriteria == null || t.acceptanceCriteria.isBlank() ? "" : t.acceptanceCriteria + "\n") + stamp;
        t.context.reviewNotes.add(stamp);
        Status st = t.st(); String from = t.status;
        if (RecoveryService.stopped(t) || st==Status.ANOMALY || st==Status.DEGRADED) {save(t);var fresh=repo.get(id);engine.adjustPlan(id,fresh.version,target,actor);}
        else if (st == Status.QA || st == Status.ACCEPTANCE || st == Status.DELIVERED) {
          save(t); Task w = repo.get(id); String edge = st == Status.QA ? "qa_loop" : st == Status.ACCEPTANCE ? "node_fix" : "merge_return";
          int v0 = w.version; engine.apply(w, new Decision.Move(Status.DEV, edge, 0.0, x -> x.currentStage = "DEV", Map.of("manual", true, "actor", actor, "reason", "改方向：" + U.ellipsis(ins, 200)), null, "redirect"));
          if (repo.get(id).version == v0) throw new IllegalArgumentException("任务已发生变化，请刷新后重试");
        } else { t.nextRunAt = clk.iso(); save(t); }
        audit.task(id, repo.get(id).currentIteration, "DIRECTION_CHANGED", actor, Map.of("instruction", ins, "from", from, "to", repo.get(id).status), null);
        out.put("status", repo.get(id).status);
      }
      case SKIP -> {
        String node = p.path("nodeId").asText(); var def = Nodes.def(node);
        if (def == null || UNSKIPPABLE_NODES.contains(node) || def.check == null || UNSKIPPABLE_CHECKS.contains(def.check)) throw new IllegalArgumentException("这个节点不能跳过");
        if (t.context.skippedChecks == null) t.context.skippedChecks = new ArrayList<>(); if (!t.context.skippedChecks.contains(def.check)) t.context.skippedChecks.add(def.check);
        save(t); audit.task(id, t.currentIteration, "STAGE_SKIPPED", actor, Map.of("stage", node, "node", node, "reason", p.path("reason").asText(), "manual", true), null);
        if (t.st() == Status.ANOMALY) engine.resumeAnomaly(id, actor);
        out.put("skipped", node);
      }
      case ANSWER -> {
        String ans = p.path("answer").asText();
        if (t.st() == Status.DISCARDED && "needs_clarification".equals(t.discardReason)) { var r = engine.editDiscarded(id, null, t.problem + "\n\n补充说明（@" + actor + "）：" + ans, null, true, actor); out.put("status", r.status); }
        else { boolean stopped=RecoveryService.stopped(t);t.context.reviewNotes.add("负责人回答（@"+actor+"）："+ans);t.problem += "\n补充说明："+ans;save(t);if(stopped)engine.resumeAnomaly(id,actor);else {t=repo.get(id);t.nextRunAt=clk.iso();save(t);} }
        audit.task(id, repo.get(id).currentIteration, "QUESTION_ANSWERED", actor, Map.of("answer", ans, "questionId", String.valueOf(p.path("questionId").asText(null))), null);
      }
      case APPROVE -> { var r = engine.decideMerge(id, p.path("decision").asText(), p.path("headSha").asText(null), p.path("comment").asText(""), actor); out.put("status", r.status()); }
      case ABANDON -> {
        int v0 = t.version; Map<String, Object> d = new LinkedHashMap<>(); d.put("actor", actor); d.put("note", p.path("reason").asText()); d.put("from", t.status); d.put("recoverable", true);
        engine.apply(t, new Decision.Discard("manual_abandon", "abandon", d, w -> w.nextRunAt = null));
        var after = repo.get(id); if (after.version == v0 || after.st() != Status.DISCARDED) throw new IllegalArgumentException("任务已发生变化，请刷新后重试");
        out.put("status", after.status); out.put("recoverable", true);
      }
      case RESTORE -> { var r = engine.resumeTask(id,t.version,actor); out.put("status", r.status); }
      case REVIEW_SKIP_FAILED -> {
        var r = engine.skipFailedCodeReview(id, t.version, actor);
        r = engine.resumeTask(id, r.version, actor);
        out.put("status", r.status);
      }
      case DEV_MODE -> {
        String mode = com.x7x24.execution.agent.DevMode.normalize(p.path("devMode").asText(com.x7x24.execution.agent.DevMode.LEGACY));
        var r = engine.setDevMode(id, t.version, mode, actor);
        if (RecoveryService.stopped(r) || r.st() == Status.ANOMALY) r = engine.resumeTask(id, r.version, actor);
        out.put("status", r.status); out.put("devMode", String.valueOf(mode));
      }
      default -> throw new IllegalArgumentException("不支持的操作");
    }
  }
  private void save(Task t) { if (!repo.save(t)) throw new IllegalArgumentException("任务已发生变化，请刷新后重试"); }

  // ───────── 阻塞卡：原因 + 证据 + 建议（规则表；每条建议只能映射到白名单动作）
  public Map<String, Object> blocked(String taskId) {
    Task t = task(taskId); Map<String, Object> out = new LinkedHashMap<>();
    boolean blocked = RecoveryService.stopped(t) || t.st()==Status.ANOMALY || t.st()==Status.DEGRADED || t.paused!=0 || t.st()==Status.DISCARDED;
    var recovery = engine.recoveryInfo(taskId);
    out.put("blocked", blocked); out.put("lockKey", lockKey(t)); out.put("status", t.status); out.put("node", t.currentNode==null?t.currentStage:t.currentNode); out.put("executionState",t.executionState);out.put("recovery",recovery); out.put("paused", t.paused != 0);
    out.put("canRestore", canRestore(t));
    var restriction = budgetRestriction(t);
    if (restriction == null && "WAITING_CONFIGURATION".equals(t.executionState)) {
      var capability = engine.releaseCapability(t);
      if (!capability.ready()) restriction = Map.of("code", capability.code(), "detail", capability.detail());
    }
    out.put("resumeBlocker", restriction);
    if (restriction != null) out.put("guide", Map.of("label", "safety_cap".equals(restriction.get("code")) ? "查看预算与安全上限" : "前往预算设置", "href", "/t/" + taskId + "/budget", "recommended", true));
    if ("WAITING_CONFIGURATION".equals(t.executionState) && (restriction == null || com.x7x24.engine.ReleasePolicy.WAIT_CODE.equals(restriction.get("code")))) out.put("guide", Map.of("label", "查看项目发布配置", "href", "/p/" + t.projectId + "/detail", "recommended", true));
    if (!blocked) { out.put("suggestions", List.of()); return out; }
    var an = db.row("SELECT type, stage FROM anomaly WHERE task_id=? AND status IN ('open','healing') ORDER BY opened_at DESC LIMIT 1", taskId);
    var ev = db.row("SELECT payload FROM task_event WHERE task_id=? AND to_status='ANOMALY' ORDER BY id DESC LIMIT 1", taskId);
    String detail = null; if (ev != null) { var j = Json.tree(Db.str(ev.get("payload"))); detail = j.path("detail").asText(j.path("anomaly").path("detail").asText(null)); }
    String type = an == null ? (t.paused != 0 ? "paused" : t.st() == Status.DISCARDED ? "needs_clarification" : String.valueOf(t.status).toLowerCase()) : String.valueOf(an.get("type"));
    if (an != null) {
      var opened = db.row("SELECT payload FROM task_event WHERE task_id=? AND event_type='ANOMALY_OPENED' ORDER BY id DESC LIMIT 1", taskId);
      if (opened != null) {
        var payload = Json.tree(Db.str(opened.get("payload")));
        if (type.equals(payload.path("type").asText())) detail = payload.path("detail").asText(detail);
      }
    }
    if(RecoveryService.stopped(t) && t.context.recoveryId!=null) {
      type=Objects.toString(recovery.get("code"),type);detail=Objects.toString(recovery.get("detail"),detail);
    }
    out.put("cause", type); out.put("detail", detail == null ? null : U.ellipsis(Redactor.redact(detail), 600));
    List<Map<String, Object>> failureHistory = new ArrayList<>(); Set<String> seen = new HashSet<>();
    var fs = t.context.failures == null ? List.<com.x7x24.engine.TaskCtx.Failure>of() : t.context.failures;
    for (int i = fs.size() - 1; i >= 0 && failureHistory.size() < 3; i--) {
      var f = fs.get(i); String item = U.ellipsis(Redactor.redact(Objects.toString(f.detail, "执行未完成")), 200);
      if (!seen.add(item) || Objects.equals(item, out.get("detail"))) continue;
      var entry = new LinkedHashMap<String, Object>();
      entry.put("code", f.code == null && f.category != null ? f.category.split(":", 2)[0] : f.code);
      entry.put("stage", f.stage); entry.put("detail", item); entry.put("at", f.at); failureHistory.add(entry);
    }
    out.put("failureHistory", failureHistory);
    List<String> evid = new ArrayList<>();
    String log = t.context.ci == null ? null : t.context.ci.log;
    if (log != null && !"model_config_error".equals(type)) for (String l : log.split("\n")) if (evid.size() < 5 && Pattern.compile("FAIL|Error|error:|✗|×").matcher(l).find()) {
      String item = U.ellipsis(Redactor.redact(l.strip()), 200); if (!evid.contains(item)) evid.add(item);
    }
    out.put("evidence", evid);
    if (restriction != null) { out.put("suggestions", List.of()); return out; }
    List<Map<String, Object>> s = new ArrayList<>();
    if(t.st()==Status.DISCARDED)s.add(sug(RESTORE,"恢复原进度","核对后从保存的节点继续",Map.of(),true));
    else switch (type) {
      case "release_configuration_required" -> s.add(sug(RESUME, "重新检查发布配置并继续", "保留原节点和已有提交，重新检查发布能力", Map.of(), true));
      case "paused" -> s.add(sug(RESUME, "继续执行", "从当前节点接着跑", Map.of(), true));
      case "needs_clarification" -> s.add(sug(ANSWER, "回答 AI 的问题", "补充说明后继续当前评审", Map.of(), true));
      case "repository_not_initialized" -> {
        s.add(sug(INITIALIZE, "初始化仓库并继续", "由平台创建首个 README 提交，再从当前节点继续；保留已有规划", Map.of(), true));
        s.add(sug(RESUME, "已手动初始化，恢复执行", "重新读取目标分支后继续", Map.of(), false));
      }
      case "path_policy" -> {
        s.add(sug(RETRY,"带提示修复当前段","保留此前提交，重新检查候选补丁",Map.of(),true));
        s.add(sug(REDIRECT,"核对项目配置并调整规划","采用当前项目配置，保留已完成成果",Map.of("instruction","根据最新项目配置核对并调整方案","backTo","PLANNING"),false));
      }
      case "model_config_error" -> {
        out.put("guide", Map.of("label", "前往模型配置", "href", "/admin/models"));
        s.add(sug(RESUME, "配置修正后恢复评审", "先修正评审席位的模型配置，再从失败步骤继续", Map.of(), true));
      }
      case Engine.CODE_REVIEW_FAILED -> {
        s.add(sug(RESUME, "重试", "接着上次的审查往下审，已审完的文件不重审；不占返修次数", Map.of(), true));
        s.add(sug(REVIEW_SKIP_FAILED, "跳过并继续", "没审完的文件按「没审」记下，结论标成部分审查，接着往下走；只有负责人或管理员能点，会记审计", Map.of(), false));
      }
      case com.x7x24.execution.agent.SandboxFailure.CODE -> {
        s.add(sug(RESUME, "再试一次（沙盒）", "在沙盒里重新开发，这次的半成品不会保留", Map.of(), true));
        s.add(sug(DEV_MODE, "本任务改用快速方式重试", "模型直接写补丁：最多参考 10 个文件，不在本地跑构建和测试，只靠 CI 检查", Map.of("devMode", "legacy"), false));
        s.add(sug(ABANDON, "放弃", "结束这个任务，之后还能恢复", Map.of("reason", "沙盒开发没成功，先不做了"), false));
      }
      case "budget_paused", "safety_cap" -> s.add(sug(RESUME,"恢复原节点","当前预算限制已解除，保留计划与分段进度",Map.of(),true));
      case "review_plan_adjustment" -> {
        for (String target : engine.designAdjustmentTargets(t)) {
          String label = com.x7x24.pipeline.Catalog.stageLabel(target);
          s.add(sug(REDIRECT, "确认调整" + label, "从此设计节点继续", Map.of("instruction", Objects.toString(detail, "按评审意见调整设计"), "backTo", target), false));
        }
        s.add(sug(REDIRECT, "确认重新规划", "适用于需要调整任务范围或验收标准的情况", Map.of("instruction", Objects.toString(detail, "按评审意见调整任务规划"), "backTo", "PLANNING"), false));
      }
      case "same_failure", "review_same_failure", "rework_exhausted" -> {
        if(recovery.get("rework") instanceof Map<?,?> rework) {
          s.add(sug(RESUME,"恢复执行，继续返修","重新获得最多 "+rework.get("limit")+" 次返修机会，保留历史记录和花费",Map.of(),true));
          s.add(sug(RETRY,"带提示继续返修","可补充修复提示，使用同一轮次重置规则",Map.of("hint",evid.isEmpty()?"":evid.getFirst()),false));
        } else s.add(sug(RESUME,"恢复原节点","核对限制后继续当前步骤",Map.of(),true));
      }
      case "ci_flaky", "evidence_missing", "agent_error", "timeout" -> s.add(sug(RETRY, "重试当前节点", "多半是临时问题", Map.of(), true));
      default -> { s.add(sug(RETRY, "带提示重试", "把失败信息交给 AI 再修一次", Map.of("hint", evid.isEmpty() ? "" : evid.getFirst()), true)); s.add(sug(REDIRECT, "改方向，退回开发", "补充要求后从开发重来", Map.of(), false)); s.add(sug(ABANDON, "放弃这个任务", "可以恢复", Map.of(), false)); }
    }
    out.put("suggestions", s.stream().limit(3).toList());
    return out;
  }

  /** The recorded stop cause is historical; use the same live budget checks as resumeTask. */
  private Map<String, Object> budgetRestriction(Task t) {
    if (ENDED.contains(t.st())) return null;
    String state = budget.taskStatus(t);
    if (!Set.of("hard", "safety").contains(state)) return null;
    var result = new LinkedHashMap<String, Object>();
    if ("safety".equals(state)) {
      int limit = budget.switches(t).safetyMaxIterations();
      result.put("code", "safety_cap"); result.put("key", "iterations"); result.put("used", t.spentIterations); result.put("limit", limit);
      result.put("detail", "开发轮次达到安全上限：" + budgetNumber(t.spentIterations) + " / " + budgetNumber(limit) + "。请联系工作区管理员核对安全上限，再恢复执行。");
    } else {
      var worst = (Map<?, ?>) budget.taskUsage(t).get("worst");
      result.put("code", "budget_paused"); result.put("key", worst.get("key")); result.put("used", worst.get("used")); result.put("limit", worst.get("limit"));
      String usage = worst.isEmpty() ? "任务预算已用尽" : "单任务 " + worst.get("label") + " 已用尽：" + budgetNumber((Number) worst.get("used")) + " / " + budgetNumber((Number) worst.get("limit"));
      result.put("detail", usage + "。请先调整本任务预算设置，再恢复执行。");
    }
    return result;
  }
  private static String budgetNumber(Number value) { return java.text.NumberFormat.getNumberInstance(Locale.CHINA).format(value); }

  static Map<String, Object> sug(String kind, String title, String why, Map<String, Object> params, boolean rec) {
    Map<String, Object> m = new LinkedHashMap<>(); Spec sp = SPECS.get(kind);
    m.put("kind", kind); m.put("title", title); m.put("why", why); m.put("params", params); m.put("recommended", rec); m.put("danger", sp.danger()); m.put("minRole", sp.minRole()); return m;
  }

  // ───────── 自由文本 → 动作（规则优先；不匹配再问模型；结果都只是提议）
  public record Parsed(String kind, ObjectNode args, String reply, String via) {}
  static final Pattern Q = Pattern.compile("(为什么|为啥|怎么回事|在干嘛|什么情况|\\?|？)$|^(为什么|为啥)");
  public Parsed parse(String taskId, String text) {
    String x = text == null ? "" : text.strip(); Task t = task(taskId);
    if (x.isEmpty()) throw new IllegalArgumentException("说点什么吧");
    var r = rules(x); if (r != null) return r;
    if (Q.matcher(x).find()) return new Parsed(null, null, explain(t), "rules");
    try {
      var pv = llm.get();
      if (pv != null && !pv.model().startsWith("mock") && !pv.model().startsWith("rule")) {
        var res = pv.chat(new LlmProvider.Request("intake.chat", PARSE_SYSTEM, "任务状态：" + t.status + " 节点：" + t.currentStage + (t.paused != 0 ? "（已暂停）" : "") + "\n用户说：" + U.ellipsis(x, 1000), "task-action-v1"),
            j -> j.path("kind").isNull() || SPECS.containsKey(j.path("kind").asText()) ? null : "kind 只能是 " + SPECS.keySet() + " 或 null");
        var j = Json.tree(res.text().replaceAll("(?s)^```(json)?|```$", "").strip());
        String k = j.path("kind").asText(null);
        if (k != null && SPECS.containsKey(k)) return new Parsed(k, j.path("args").isObject() ? (ObjectNode) j.path("args") : Json.M.createObjectNode(), j.path("reply").asText(null), "llm");
        return new Parsed(null, null, j.path("reply").asText(explain(t)), "llm");
      }
    } catch (RuntimeException ignore) { /* 模型不可用：退回说明 */ }
    // 兜底：当成补充要求（改方向），仍要确认
    var a = Json.M.createObjectNode(); a.put("instruction", x); return new Parsed(REDIRECT, a, null, "rules");
  }
  static final String PARSE_SYSTEM = "你把用户在任务会话里的一句话解析成流程操作。只输出 JSON：{\"kind\": 操作或 null, \"args\": {...}, \"reply\": 一句中文说明}。"
      + "操作：task_resume / task_pause / node_retry{hint} / task_redirect{instruction} / node_skip{nodeId,reason} / answer_question{answer} / approval_decide{approve,comment} / task_abandon{reason} / repository_initialize_and_resume。"
      + "预算、密钥、治理、成员相关的话不要映射成操作（kind=null，reply 引导去对应页面）。只是提问时 kind=null。";
  static Parsed rules(String x) {
    var a = Json.M.createObjectNode();
    if (x.matches("^(请|帮我)?初始化仓库(并继续|并恢复|后继续)?[。！!]?$")) return new Parsed(INITIALIZE, a, null, "rules");
    if (x.matches("^(请)?(先)?(暂停|停一下|停下|先停)[。！!]?.*") && x.length() < 20) return new Parsed(PAUSE, a, null, "rules");
    if (x.matches("^(继续|恢复|接着(跑|做)|往下走)[。！!]?$")) return new Parsed(RESUME, a, null, "rules");
    var m = Pattern.compile("^(重试|再试一次|重跑|再跑一次)[，,：:\\s]*(.*)$").matcher(x); if (m.matches()) { a.put("hint", m.group(2)); return new Parsed(RETRY, a, null, "rules"); }
    m = Pattern.compile("^跳过\\s*([A-Z_]+)[，,：:\\s]*(.*)$").matcher(x); if (m.matches()) { a.put("nodeId", m.group(1)); a.put("reason", m.group(2)); return new Parsed(SKIP, a, null, "rules"); }
    m = Pattern.compile("^(放弃|不做了|取消任务)[，,：:\\s]*(.*)$").matcher(x); if (m.matches()) { a.put("reason", m.group(2).isBlank() ? "负责人在会话里放弃" : m.group(2)); return new Parsed(ABANDON, a, null, "rules"); }
    m = Pattern.compile("^(批准|同意合并|可以合并)[，,：:\\s]*(.*)$").matcher(x); if (m.matches()) { a.put("approve", true); a.put("comment", m.group(2)); return new Parsed(APPROVE, a, null, "rules"); }
    m = Pattern.compile("^(回答|答)[：:]\\s*(.+)$").matcher(x); if (m.matches()) { a.put("answer", m.group(2)); return new Parsed(ANSWER, a, null, "rules"); }
    if (x.matches(".*(预算|密钥|token|治理|删除任务|成员).*")) return new Parsed(null, null, "预算、密钥、治理和成员不在会话里改，请到对应页面操作。", "rules");
    return null;
  }
  String explain(Task t) {
    var b = blocked(t.id);
    if (Boolean.TRUE.equals(b.get("blocked"))) return "任务停在「" + t.currentStage + "」，原因：" + b.get("cause") + (b.get("detail") == null ? "" : "（" + b.get("detail") + "）") + "。可以选下面的建议，或直接告诉我怎么处理。";
    return "任务正在「" + t.currentStage + "」节点运行（第 " + t.currentIteration + " 轮）。";
  }
}
