package com.x7x24.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.Redactor;
import com.x7x24.db.Db;
import com.x7x24.engine.Engine;
import com.x7x24.engine.ProjectRepo;
import com.x7x24.gov.AuditLog;
import com.x7x24.gov.BudgetService;
import com.x7x24.gov.ConfigStore;
import com.x7x24.gov.Risk;
import com.x7x24.llm.LlmProvider;
import com.x7x24.llm.OpenAiCompatProvider;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.Clk;
import com.x7x24.util.Json;
import com.x7x24.util.U;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 对话式需求录入（docs/design/chat-intake-and-adaptive-pipeline.md，C1）。
 * 会话 / 消息 / 草稿按工作区隔离；每轮由录入 agent（角色 intake.chat）多步调用只读工具，产出回复与草稿补丁；
 * 任务只能由 {@link #confirm} 创建（用户在确认卡上点击），并照常进入 PRD 评审。
 */
public class ChatService {
  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  public static final String ROLE = "intake.chat", PROMPT_VERSION = "command-v1";

  public static class Conflict extends RuntimeException { public Conflict(String m) { super(m); } }
  public static class CapReached extends RuntimeException { public CapReached(String m) { super(m); } }
  public record Event(String type, Object data) {}
  /** 确认结果：created=false 表示幂等返回已创建的任务；pendingApproval 由 C2 使用 */
  public record Confirmed(String taskId, boolean created, boolean pendingApproval, Object plan) {}

  /** C2：确认前解析 / 校验任务级流程计划；返回快照 id 与是否需要审批 */
  public interface PlanHook {
    record Pinned(String snapshotId, boolean needsApproval, Object plan, List<String> violations) {}
    Pinned pin(Draft d, String lineId, String projectId);
    default Object preview(Draft d, String lineId) { return null; }
  }

  private final Db db; private final Clk clk; private final ConfigStore cfg; private final AuditLog audit; private final BudgetService budget;
  private final ProjectRepo projects; private final Engine engine; private final com.x7x24.llm.ModelRouter router; private final IntakeTools tools;
  public PlanHook planHook = null;
  /** A 类对话操作（新增产品线 / 项目、邀请成员）；为空时不支持 */
  public ChatActions actions = null;
  /** 当前工作区里存在且未归档的产品线（D3 上下文校验） */
  public java.util.function.Predicate<String> lineExists = x -> false;
  /** 测试：替换录入 agent 的模型 */
  public volatile LlmProvider providerOverride;
  private final Set<String> running = ConcurrentHashMap.newKeySet(); private final Set<String> stopRequested = ConcurrentHashMap.newKeySet();
  private final Map<String, Object> locks = new ConcurrentHashMap<>();

  public ChatService(Db db, Clk clk, ConfigStore cfg, AuditLog audit, BudgetService budget, ProjectRepo projects, Engine engine, com.x7x24.llm.ModelRouter router, IntakeTools tools) {
    this.db = db; this.clk = clk; this.cfg = cfg; this.audit = audit; this.budget = budget; this.projects = projects; this.engine = engine; this.router = router; this.tools = tools;
    audit.subscribe(r -> { if ("STATUS_CHANGED".equals(r.get("event_type")) && "DISCARDED".equals(r.get("to_status")) && r.get("task_id") != null) { String tid = (String) r.get("task_id"); clarifier.schedule(() -> backToChat(tid, 0), 300, java.util.concurrent.TimeUnit.MILLISECONDS); } });
  }
  private final java.util.concurrent.ScheduledExecutorService clarifier = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { var th = new Thread(r, "chat-clarify"); th.setDaemon(true); return th; });

  /** PRD 评审因缺信息转「需澄清」：回到来源对话，把要补充的问题发给需求方，并重新打开会话；补完再确认即重启原任务 */
  public void backToChat(String taskId, int attempt) {
    try {
      var t = Tenant.unscoped(() -> db.row("SELECT workspace_id, status, discard_reason, discard_detail, source_chat_id FROM task WHERE id=?", taskId));
      if (t == null) return;
      if (!"DISCARDED".equals(t.get("status")) || t.get("discard_reason") == null) { if (attempt < 20) clarifier.schedule(() -> backToChat(taskId, attempt + 1), 500, java.util.concurrent.TimeUnit.MILLISECONDS); return; } // 外层事务尚未提交
      if (!"needs_clarification".equals(t.get("discard_reason")) || t.get("source_chat_id") == null) return;
      String ws = (String) t.get("workspace_id"), chat = (String) t.get("source_chat_id");
      var dd = t.get("discard_detail") == null ? null : Json.tree((String) t.get("discard_detail"));
      List<String> qs = new ArrayList<>(); if (dd != null) dd.path("questions").forEach(q -> qs.add(q.asText()));
      StringBuilder msg = new StringBuilder("PRD 评审没有通过，原因是需求信息还不够明确（不是价值问题）。请补充以下内容，我会更新草稿，你再次确认后原任务会自动重启：\n");
      for (int i = 0; i < qs.size(); i++) msg.append(i + 1).append(". ").append(qs.get(i)).append("\n");
      if (dd != null && dd.hasNonNull("notes")) msg.append("\n评审记录：").append(U.ellipsis(dd.path("notes").asText(), 1500));
      Tenant.run(ws, () -> {
        if (db.update("UPDATE chat_session SET status='open', updated_at=? WHERE id=? AND workspace_id=? AND task_id=? AND status='confirmed'", clk.iso(), chat, ws, taskId) == 0) return;
        db.update("INSERT INTO chat_message(session_id,workspace_id,role,content,created_at) VALUES(?,?,?,?,?)", chat, ws, "assistant", msg.toString(), clk.iso());
        audit.task(taskId, null, "CHAT_CLARIFICATION_REQUESTED", "system", Map.of("chat", chat, "questions", qs), taskId + ":clarify:" + chat + ":" + clk.iso());
      });
    } catch (Exception e) { org.slf4j.LoggerFactory.getLogger(ChatService.class).warn("回到对话澄清失败 task={}：{}", taskId, e.toString()); }
  }

  // ───────── 会话
  public Map<String, Object> create(String actor, String projectId) { return create(actor, projectId, null); }
  public Map<String, Object> create(String actor, String projectId, String presetId) { return create(actor, projectId, presetId, null); }
  public Map<String, Object> create(String actor, String projectId, String presetId, String productLineId) {
    if (presetId != null && !java.util.Set.of("backend", "frontend", "migration", "fast").contains(presetId)) throw new IllegalArgumentException("未知场景");
    String id = "chat_" + Long.toHexString(new java.security.SecureRandom().nextLong() & Long.MAX_VALUE); String now = clk.iso();
    if (projectId != null && !projectId.isBlank() && projects.get(projectId) == null) throw new IllegalArgumentException("项目不存在或不在当前工作区");
    String pid = projectId == null || projectId.isBlank() ? null : projectId;
    String line = lineOf(pid, productLineId);
    db.update("INSERT INTO chat_session(id,workspace_id,created_by,title,status,project_id,product_line_id,draft_version,created_at,updated_at,anchor_kind,anchor_id,phase) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", id, Tenant.id(), actor, null, "open", pid, line, 0, now, now,
        pid != null ? "project" : line != null ? "line" : "none", pid != null ? pid : line, pid != null ? "drafting" : "setup");
    var d = new Draft(); d.projectId = pid; d.presetId = presetId; d.classification = "fast".equals(presetId) ? null : presetId; saveDraft(id, 0, d, "user");
    return session(id);
  }
  public List<Map<String, Object>> list() { return list(null, null); }
  /** 「待我处理」：工作区里还没确认 / 取消的操作卡，带所在会话，点了回到原会话 */
  public List<Map<String, Object>> pendingActions() {
    return db.rows("SELECT a.id,a.session_id,a.kind,a.params,a.created_at,a.error,s.title AS session_title,s.created_by,s.anchor_kind,s.anchor_id,s.task_id FROM chat_action a JOIN chat_session s ON s.id=a.session_id AND s.workspace_id=a.workspace_id"
      + " WHERE a.workspace_id=? AND a.status='proposed' ORDER BY a.created_at DESC LIMIT 100", Tenant.id()).stream().map(r -> {
        Map<String, Object> m = new LinkedHashMap<>(r); try { m.put("params", Json.tree(String.valueOf(r.get("params")))); } catch (Exception e) { m.put("params", Map.of()); } return m; }).toList();
  }
  /** 会话列表（最近对话 / 对象的「对话」页签）。anchorKind+anchorId 过滤某个对象下的会话；产品线页同时包含其下项目的会话 */
  public List<Map<String, Object>> list(String anchorKind, String anchorId) {
    String sel = "SELECT s.id,s.title,s.status,s.project_id,s.product_line_id,s.task_id,s.created_by,s.turns,s.usd_spent,s.created_at,s.updated_at,s.anchor_kind,s.anchor_id,"
      + "CASE s.anchor_kind WHEN 'task' THEN (SELECT t.title FROM task t WHERE t.id=s.anchor_id) WHEN 'project' THEN (SELECT p.name FROM project p WHERE p.id=s.anchor_id) WHEN 'line' THEN (SELECT l.name FROM product_line l WHERE l.id=s.anchor_id) END AS anchor_name,"
      + "(SELECT COUNT(*) FROM chat_action a WHERE a.session_id=s.id AND a.workspace_id=s.workspace_id) AS action_count,"
      + "(SELECT COUNT(*) FROM chat_action a WHERE a.session_id=s.id AND a.workspace_id=s.workspace_id AND a.status='proposed') AS pending_actions FROM chat_session s WHERE s.workspace_id=?";
    if (anchorKind == null || anchorKind.isBlank()) return db.rows(sel + " ORDER BY s.updated_at DESC LIMIT 200", Tenant.id());
    if (!Set.of("task", "project", "line", "none").contains(anchorKind)) throw new IllegalArgumentException("未知归属");
    if ("none".equals(anchorKind)) return db.rows(sel + " AND s.anchor_kind='none' ORDER BY s.updated_at DESC LIMIT 200", Tenant.id());
    if ("line".equals(anchorKind)) return db.rows(sel + " AND ((s.anchor_kind='line' AND s.anchor_id=?) OR (s.anchor_kind='project' AND s.anchor_id IN (SELECT p.id FROM project p WHERE p.product_line_id=?))) ORDER BY s.updated_at DESC LIMIT 200", Tenant.id(), anchorId, anchorId);
    return db.rows(sel + " AND s.anchor_kind=? AND s.anchor_id=? ORDER BY s.updated_at DESC LIMIT 200", Tenant.id(), anchorKind, anchorId);
  }
  static int rank(Object kind) { return kind == null ? 0 : switch (kind.toString()) { case "task" -> 3; case "project" -> 2; case "line" -> 1; default -> 0; }; }
  /** 把会话挂到更具体的对象上（只升不降：任务 > 项目 > 产品线 > 未归属）。建对象 / 建任务后调用 */
  public void anchor(String id, String kind, String anchorId) {
    var s = db.row("SELECT anchor_kind FROM chat_session WHERE id=? AND workspace_id=?", id, Tenant.id()); if (s == null) return;
    if (rank(kind) < rank(s.get("anchor_kind"))) return;
    db.update("UPDATE chat_session SET anchor_kind=?, anchor_id=?, updated_at=? WHERE id=? AND workspace_id=?", kind, anchorId, clk.iso(), id, Tenant.id());
  }
  /** 改名（侧栏 ⋯ 菜单） */
  public Map<String, Object> rename(String id, String title) {
    String t = title == null ? "" : title.trim(); if (t.isEmpty() || t.length() > 200) throw new IllegalArgumentException("标题不能为空，且不能超过 200 字");
    session(id); db.update("UPDATE chat_session SET title=?, updated_at=? WHERE id=? AND workspace_id=?", t, clk.iso(), id, Tenant.id()); return session(id);
  }
  /** 产品线上下文：给了项目就用项目所属产品线；否则校验给的产品线在当前工作区 */
  String lineOf(String projectId, String productLineId) {
    if (projectId != null) { var p = projects.get(projectId); if (p != null) return p.productLineId(); }
    if (productLineId == null || productLineId.isBlank()) return null;
    if (!lineExists.test(productLineId)) throw new IllegalArgumentException("产品线不存在或不在当前工作区");
    return productLineId;
  }
  /** 切换会话上下文（D3）：只改产品线 / 项目，不改草稿内容；项目必须属于该产品线 */
  public Map<String, Object> setContext(String id, String productLineId, String projectId) {
    synchronized (lock(id)) {
      var s = session(id); requireOpen(s);
      String pid = projectId == null || projectId.isBlank() ? null : projectId;
      if (pid != null && projects.get(pid) == null) throw new IllegalArgumentException("项目不存在或不在当前工作区");
      String line = pid != null ? projects.get(pid).productLineId() : lineOf(null, productLineId);
      if (pid != null && productLineId != null && !productLineId.isBlank() && !productLineId.equals(line)) throw new IllegalArgumentException("项目不属于这条产品线");
      db.update("UPDATE chat_session SET product_line_id=?, project_id=?, updated_at=? WHERE id=? AND workspace_id=?", line, pid, clk.iso(), id, Tenant.id());
      if (rank(s.get("anchor_kind")) < 3) db.update("UPDATE chat_session SET anchor_kind=?, anchor_id=? WHERE id=? AND workspace_id=?", pid != null ? "project" : line != null ? "line" : "none", pid != null ? pid : line, id, Tenant.id());
      if (pid != null) enterDrafting(id, pid);
      return session(id);
    }
  }
  // ───────── 阶段：setup（先定产品线 / 项目，草稿只暂存）→ drafting（展示草稿面板）
  public static boolean drafting(Map<String, Object> s) { return !"setup".equals(s.get("phase")); }
  public boolean drafting(String id) { return drafting(session(id)); }
  /** 进入整理需求阶段（只进不退）：草稿没项目就补上；真正切换时返回 true，并写一句提示 */
  public boolean enterDrafting(String id, String projectId) {
    synchronized (lock(id)) {
      var d = draft(id);
      if (projectId != null && (d.projectId == null || d.projectId.isBlank()) && projects.get(projectId) != null) { d.projectId = projectId; saveDraft(id, ((Number) session(id).get("draft_version")).intValue() + 1, d, "system"); }
      if (db.update("UPDATE chat_session SET phase='drafting', updated_at=? WHERE id=? AND workspace_id=? AND phase='setup'", clk.iso(), id, Tenant.id()) == 0) return false;
      boolean has = (d.title != null && !d.title.isBlank()) || (d.goal != null && !d.goal.isBlank()) || !d.scope.isEmpty() || !d.acceptance.isEmpty();
      db.update("INSERT INTO chat_message(session_id,workspace_id,role,content,model,created_at) VALUES(?,?,?,?,?,?)", id, Tenant.id(), "assistant",
        has ? "我已把刚才的需求整理到右侧，请检查。" : "项目定好了。说说你想做什么，我会整理到右侧的需求草稿。", "rules", clk.iso());
      return true;
    }
  }
  // ───────── 输入建议与操作后的「下一步」
  /** 清洗模型给的建议：最多 3 条、每条 40 字内、去重；保存在会话上并推送 suggest 事件 */
  static List<String> cleanSuggestions(JsonNode n) {
    List<String> out = new ArrayList<>(); if (n == null || !n.isArray()) return out;
    for (var x : n) { String t = x.asText("").strip().replaceAll("\\s+", " "); if (t.isEmpty() || t.length() > 40 || out.contains(t)) continue; out.add(t); if (out.size() == 3) break; }
    return out;
  }
  void suggest(String id, JsonNode n, Consumer<Event> sink) { suggest(id, cleanSuggestions(n), sink); }
  void suggest(String id, List<String> items, Consumer<Event> sink) {
    if (items.isEmpty()) return;
    db.update("UPDATE chat_session SET suggestions=? WHERE id=? AND workspace_id=?", Json.str(items), id, Tenant.id());
    sink.accept(new Event("suggest", Map.of("items", items)));
  }
  /** 认领「下一步」提示（同步，返回 HTTP 错误）：只限已完成、且没续过的操作 */
  public void claimFollowup(String id, String actionId) {
    var s = session(id); requireOpen(s); var a = actions.get(id, actionId);
    if (!"executed".equals(a.get("status"))) throw new Conflict("这个操作还没有完成");
    if (running.contains(id)) throw new Conflict("上一条消息还在处理中");
    if (db.update("UPDATE chat_action SET followed_up_at=? WHERE id=? AND session_id=? AND workspace_id=? AND followed_up_at IS NULL", clk.iso(), actionId, id, Tenant.id()) == 0) throw new Conflict("已经给过下一步提示");
  }
  /** 操作完成后续一轮：告诉用户下一步可以做什么。每个操作只续一次；模型失败用规则兜底 */
  public void followup(String id, String actionId, Consumer<Event> sink) {
    if (actions == null) throw new IllegalArgumentException("当前没有可用的操作");
    var a = actions.get(id, actionId);
    if (!"executed".equals(a.get("status"))) throw new Conflict("这个操作还没有完成");
    if (!running.add(id)) throw new Conflict("上一条消息还在处理中");
    try {
      stopRequested.remove(id);
      String kind = (String) a.get("kind"); JsonNode p = (JsonNode) a.get("params"); JsonNode res = (JsonNode) a.get("result");
      String note = "用户刚确认并完成了操作：" + ChatActions.summary(kind, p, res == null ? Map.of() : Json.M.convertValue(res, Map.class))
        + "。请用一两句话告诉用户下一步可以做什么（不要重复操作结果、不要再提议同样的操作），并在 suggestions 里给 1~3 条用户可以直接发送的输入。";
      Map<String, Object>[] held = new Map[1]; boolean[] failed = {false};
      Consumer<Event> wrapped = e -> { if ("done".equals(e.type())) held[0] = (Map<String, Object>) e.data(); else if ("error".equals(e.type())) failed[0] = true; else sink.accept(e); };
      String reply = null;
      try { reply = run(id, wrapped, false, note); } catch (RuntimeException e) { reply = null; } // 模型未配置 / 调用失败：走规则兜底
      if (reply == null || failed[0]) {
        var fb = nextStep(kind, p, res);
        db.update("INSERT INTO chat_message(session_id,workspace_id,role,content,model,created_at) VALUES(?,?,?,?,?,?)", id, Tenant.id(), "assistant", fb.getKey(), "rules", clk.iso());
        sink.accept(new Event("reset", Map.of())); sink.accept(new Event("delta", Map.of("text", fb.getKey()))); suggest(id, fb.getValue(), sink);
        sink.accept(new Event("done", doneEvent(id, true)));
      } else {
        if (session(id).get("suggestions") == null) suggest(id, nextStep(kind, p, res).getValue(), sink); // 模型没给建议：补规则建议
        sink.accept(new Event("done", held[0] != null ? held[0] : doneEvent(id, true)));
      }
    } finally { running.remove(id); stopRequested.remove(id); }
  }
  /** 规则兜底的下一步提示与建议输入 */
  static Map.Entry<String, List<String>> nextStep(String kind, JsonNode p, JsonNode res) {
    String name = p == null ? "" : p.path("name").asText("");
    return switch (String.valueOf(kind)) {
      case "create_product_line" -> Map.entry("下一步：在「" + name + "」下新建项目，比如官网、后台。", List.of("在「" + name + "」下新建项目 官网"));
      case "create_project" -> {
        boolean noRepo = p == null || !p.hasNonNull("repo") || p.path("repo").asText("").isBlank();
        yield Map.entry("下一步：说说想在「" + name + "」里做什么，我会整理成需求草稿。" + (noRepo ? "开发前记得到项目里绑定仓库。" : ""),
          List.of("我想在「" + name + "」里加一个功能：", "先说说「" + name + "」的首页要有什么"));
      }
      case "bind_repo" -> Map.entry("仓库绑好了。说说想在「" + (res == null ? "" : res.path("name").asText()) + "」里做什么，我来整理需求。", List.of("我想在「" + (res == null ? "" : res.path("name").asText()) + "」里加一个功能："));
      case "invite_member" -> Map.entry("邀请链接已生成，发给对方即可。还可以继续提需求。", List.of("我想要"));
      default -> Map.entry("完成了。还需要什么，直接告诉我。", List.of());
    };
  }
  /** 本轮结束的 done 事件：setup 阶段不跑预检、不给计划 */
  private Map<String, Object> doneEvent(String id, boolean replied) {
    var d = draft(id); var s = session(id); boolean dr = drafting(s);
    Map<String, Object> done = new LinkedHashMap<>(); done.put("version", s.get("draft_version")); done.put("draft", d); done.put("precheck", dr ? precheck(d) : null);
    done.put("usd", s.get("usd_spent")); done.put("turns", s.get("turns")); done.put("replied", replied); done.put("phase", s.get("phase")); done.put("plan", dr ? planPreview(d) : null);
    return done;
  }
  /** 当前工作区的会话；别的工作区的会话视为不存在（404） */
  public Map<String, Object> session(String id) {
    var s = db.row("SELECT * FROM chat_session WHERE id=? AND workspace_id=?", id, Tenant.id());
    if (s == null) throw new com.x7x24.tenant.WorkspaceService.NotMember();
    return s;
  }
  public Map<String, Object> get(String id) {
    var s = session(id); Map<String, Object> out = new LinkedHashMap<>(s);
    out.put("messages", db.rows("SELECT id,role,content,tool_name,tool_summary,model,usd,created_at FROM chat_message WHERE session_id=? AND workspace_id=? ORDER BY id", id, Tenant.id()));
    var d = draft(id); boolean dr = drafting(s); out.put("draft", d); out.put("precheck", dr ? precheck(d) : null); out.put("running", running.contains(id));
    var c = cfg.get().chat; out.put("limits", Map.of("sessionUsd", c.sessionUsd, "maxTurns", c.maxTurns));
    out.put("plan", dr ? planPreview(d) : null);
    try { out.put("suggestions", s.get("suggestions") == null ? List.of() : Json.tree(String.valueOf(s.get("suggestions")))); } catch (Exception e) { out.put("suggestions", List.of()); }
    out.put("actions", actions == null ? List.of() : actions.list(id));
    return out;
  }
  /** 草稿当前对应的流程计划（只预览，不持久化） */
  public Object planPreview(Draft d) {
    if (planHook == null || d.projectId == null) return null;
    var p = projects.get(d.projectId); if (p == null) return null;
    try { return planHook.preview(d, p.productLineId()); } catch (RuntimeException ex) { return Map.of("error", Redactor.redact(String.valueOf(ex.getMessage()))); }
  }
  public Draft draft(String id) {
    var r = db.row("SELECT body FROM chat_draft WHERE session_id=? AND workspace_id=? ORDER BY version DESC LIMIT 1", id, Tenant.id());
    return r == null ? new Draft() : Json.parse((String) r.get("body"), Draft.class);
  }
  private int saveDraft(String id, int version, Draft d, String by) {
    db.update("INSERT INTO chat_draft(session_id,version,workspace_id,body,edited_by,created_at) VALUES(?,?,?,?,?,?)", id, version, Tenant.id(), Json.str(d), by, clk.iso());
    db.update("UPDATE chat_session SET draft_version=?, title=COALESCE(?, title), project_id=COALESCE(?, project_id), updated_at=? WHERE id=? AND workspace_id=?", version, d.title == null ? null : U.ellipsis(d.title, 190), d.projectId, clk.iso(), id, Tenant.id());
    return version;
  }

  /** 用户在卡片上编辑草稿：必须基于最新版本（乐观锁） */
  public Map<String, Object> editDraft(String id, int baseVersion, JsonNode patch) {
    synchronized (lock(id)) {
      var s = session(id);
      if ("pending_approval".equals(s.get("status"))) { // 改草稿即撤回审批申请，回到编辑态
        db.update("UPDATE chat_session SET status='open', updated_at=? WHERE id=? AND workspace_id=?", clk.iso(), id, Tenant.id()); s = session(id);
      }
      requireOpen(s);
      int cur = ((Number) s.get("draft_version")).intValue();
      if (baseVersion != cur) throw new Conflict("草稿已被更新（当前版本 " + cur + "），请刷新后再改");
      var d = draft(id); d.merge(patch); validateProject(d);
      int v = saveDraft(id, cur + 1, d, "user");
      Map<String, Object> out = new LinkedHashMap<>(); out.put("version", v); out.put("draft", d); out.put("precheck", precheck(d)); out.put("plan", planPreview(d)); return out;
    }
  }
  private void validateProject(Draft d) { if (d.projectId != null && !d.projectId.isBlank() && projects.get(d.projectId) == null) throw new IllegalArgumentException("项目不存在或不在当前工作区"); }
  private static void requireOpen(Map<String, Object> s) { if (!"open".equals(s.get("status"))) throw new Conflict("会话已" + ("confirmed".equals(s.get("status")) ? "创建任务" : "关闭") + "，不能再修改"); }
  private Object lock(String id) { return locks.computeIfAbsent(id, k -> new Object()); }

  // ───────── 预检
  public Precheck.Result precheck(Draft d) {
    var p = d.projectId == null || d.projectId.isBlank() ? null : projects.get(d.projectId);
    String text = (d.title == null ? "" : d.title) + " " + (d.goal == null ? "" : d.goal) + " " + String.join(" ", d.scope);
    String computed = p == null ? Risk.classify(cfg.get(), text, null).level() : Risk.classify(cfg.get(), text, null, p.cfg()::riskOf, d.targetPaths).level();
    String autonomy = p == null ? "L1" : projects.runtime(p.id()).autonomyLevel();
    var dups = d.title == null ? List.<Map<String, Object>>of() : tools.duplicates(d.title);
    var res = Precheck.check(d, p != null && "ACTIVE".equals(p.state()), computed, r -> Risk.autonomyAllows(autonomy, r), dups);
    if (p != null && p.cfg().repo == null) { var probs = new ArrayList<>(res.problems()); probs.add("项目未绑定仓库：先到项目「仓库与上下文」绑定 GitHub 仓库"); return new Precheck.Result(false, probs, res.warnings(), res.risk(), res.duplicates(), res.vagueAcceptance()); }
    return res;
  }

  // ───────── 一轮对话
  public void stop(String id) { session(id); stopRequested.add(id); }
  /** 连接断开时停止（心跳 / 超时线程里调用，没有工作区上下文，不查会话） */
  public void stopQuiet(String id) { if (running.contains(id)) stopRequested.add(id); }
  public boolean busy(String id) { return running.contains(id); }

  /** 同步执行一轮（调用方在线程池里调用，并把事件转成 SSE）。 */
  public void turn(String id, String actor, String text, Consumer<Event> sink) { turn(id, actor, text, sink, false); }
  /** readOnly：只读成员（D5）可以提问、查询；这一轮里的草稿补丁、操作提议、规则指令一律忽略 */
  public void turn(String id, String actor, String text, Consumer<Event> sink, boolean readOnly) {
    if (text == null || text.isBlank()) throw new IllegalArgumentException("消息不能为空");
    if (text.length() > 8000) throw new IllegalArgumentException("单条消息最多 8000 字");
    preflight(id);
    if (!running.add(id)) throw new Conflict("上一条消息还在处理中");
    stopRequested.remove(id);
    try {
      db.update("INSERT INTO chat_message(session_id,workspace_id,role,content,created_at) VALUES(?,?,?,?,?)", id, Tenant.id(), "user", text, clk.iso());
      db.update("UPDATE chat_session SET turns=turns+1, suggestions=NULL, updated_at=? WHERE id=? AND workspace_id=?", clk.iso(), id, Tenant.id());
      // 标题兜底：没有草稿标题时用第一条消息的前 20 个字（以后草稿有标题会覆盖）
      db.update("UPDATE chat_session SET title=? WHERE id=? AND workspace_id=? AND title IS NULL", U.ellipsis(text.strip().replaceAll("\\s+", " "), 20), id, Tenant.id());
      if (!ruleGuide(id, text, sink) && !(readOnly ? ruleReadOnly(id, text, sink) : ruleAction(id, text, sink))) run(id, sink, readOnly);
    } finally { running.remove(id); stopRequested.remove(id); }
  }
  /** 发消息前的检查（会话状态、轮数 / 费用上限、工作区预算、是否正在处理），失败直接返回 HTTP 错误 */
  public void preflight(String id) {
    var s = session(id); requireOpen(s); var c = cfg.get().chat;
    if (running.contains(id)) throw new Conflict("上一条消息还在处理中");
    if (((Number) s.get("turns")).intValue() >= c.maxTurns) throw new CapReached("这条指令记录已经聊到 " + c.maxTurns + " 轮上限了，新开一条继续吧，或者直接确认草稿");
    if (com.x7x24.db.Db.d(s.get("usd_spent")) >= c.sessionUsd) throw new CapReached("本会话模型费用已达上限 $" + c.sessionUsd + "（治理配置 chat.sessionUsd）");
    if ("hard".equals(budget.globalStatus().get("level"))) throw new CapReached("工作区预算已触顶，暂停对话");
  }

  /** 明确的命令句式（如「新增产品线 教育」）不调用模型，直接生成确认卡；模型不可用 / 离线模式下也能用 */
  /** B 类操作（删除、改权限、预算…）：不生成卡片，给引导卡 + 固定链接 */
  private boolean ruleGuide(String id, String text, Consumer<Event> sink) {
    var g = Guides.parse(text); if (g == null) return false;
    guide(id, g, sink); finishRule(id, Guides.reply(g), sink); return true;
  }
  private void guide(String id, Guides.Guide g, Consumer<Event> sink) {
    db.update("INSERT INTO chat_message(session_id,workspace_id,role,tool_name,tool_summary,created_at) VALUES(?,?,?,?,?,?)", id, Tenant.id(), "tool", "guide", g.topic(), clk.iso());
    sink.accept(new Event("guide", g.toMap()));
  }
  /** 只读成员发了明确的操作指令：直接说明，不生成卡片 */
  private boolean ruleReadOnly(String id, String text, Consumer<Event> sink) {
    if (ChatActions.parse(text) == null) return false;
    finishRule(id, READ_ONLY_REPLY, sink); return true;
  }
  public static final String READ_ONLY_REPLY = "你是只读成员，不能发起操作，也不能修改需求草稿。可以继续问我问题或查进度；需要权限请找工作区管理员。";
  private void finishRule(String id, String reply, Consumer<Event> sink) {
    db.update("INSERT INTO chat_message(session_id,workspace_id,role,content,model,created_at) VALUES(?,?,?,?,?,?)", id, Tenant.id(), "assistant", reply, "rules", clk.iso());
    sink.accept(new Event("delta", Map.of("text", reply)));
    sink.accept(new Event("done", doneEvent(id, true)));
  }
  private boolean ruleAction(String id, String text, Consumer<Event> sink) {
    if (actions == null) return false; var intent = ChatActions.parse(text); if (intent == null) return false;
    String reply;
    try {
      var a = actions.propose(id, intent.kind(), intent.args(), "rules"); sink.accept(new Event("action", a));
      reply = "我把操作卡准备好了，检查没问题就点「确认」。";
    } catch (IllegalArgumentException e) { reply = "这个操作还不能执行：" + Redactor.redact(e.getMessage()); }
    db.update("INSERT INTO chat_message(session_id,workspace_id,role,content,model,created_at) VALUES(?,?,?,?,?,?)", id, Tenant.id(), "assistant", reply, "rules", clk.iso());
    sink.accept(new Event("delta", Map.of("text", reply)));
    sink.accept(new Event("done", doneEvent(id, true)));
    return true;
  }
  /** 模型提议的操作：只当作「提议」，参数由服务端重新校验；不合法的提议记成工具错误反馈给模型 */
  private void proposeFromModel(String id, JsonNode list, Consumer<Event> sink, List<String> turnResults) {
    if (actions == null || !list.isArray()) return; int n = 0;
    for (var x : list) {
      if (n++ >= 2) break; String kind = x.path("kind").asText(x.path("name").asText(""));
      try { var a = actions.propose(id, kind, x.path("params").isObject() ? x.path("params") : x.path("args"), "agent"); sink.accept(new Event("action", a));
        db.update("INSERT INTO chat_message(session_id,workspace_id,role,tool_name,tool_summary,created_at) VALUES(?,?,?,?,?,?)", id, Tenant.id(), "tool", "propose_action", "提议操作 " + kind + "（等待用户确认）", clk.iso());
      } catch (IllegalArgumentException e) {
        String m = Redactor.redact(e.getMessage()); turnResults.add("<tool_error tool=\"propose_action\">" + m + "</tool_error>");
        db.update("INSERT INTO chat_message(session_id,workspace_id,role,tool_name,tool_summary,created_at) VALUES(?,?,?,?,?,?)", id, Tenant.id(), "tool", "propose_action", "提议被拒绝：" + U.ellipsis(m, 200), clk.iso());
        sink.accept(new Event("tool", Map.of("name", "propose_action", "summary", "提议被拒绝：" + U.ellipsis(m, 200))));
      }
    }
  }

  private LlmProvider provider() { return providerOverride != null ? providerOverride : router.maker(ROLE, null, null, false); }

  private String run(String id, Consumer<Event> sink, boolean readOnly) { return run(id, sink, readOnly, null); }
  /** note：给模型的系统事件（例如「用户刚确认完成了某个操作」），不作为用户消息保存。返回最终回复，模型失败时为 null */
  private String run(String id, Consumer<Event> sink, boolean readOnly, String note) {
    var c = cfg.get().chat; List<String> turnResults = new ArrayList<>(); String reply = null;
    if (note != null) turnResults.add("<system_event>" + note + "</system_event>");
    for (int step = 0; step < c.maxStepsPerTurn; step++) {
      if (stopRequested.contains(id)) { sink.accept(new Event("stopped", Map.of())); break; }
      var s = session(id);
      if (com.x7x24.db.Db.d(s.get("usd_spent")) >= c.sessionUsd) { sink.accept(new Event("error", Map.of("message", "本会话模型费用已达上限 $" + c.sessionUsd))); break; }
      boolean last = step == c.maxStepsPerTurn - 1; var d = draft(id);
      LlmProvider.Response r;
      // 真流式：边收边从 JSON 里取出 reply（只在 tool_calls 为空的最终步骤逐段推送 delta）
      var ex = new ReplyStreamExtractor(t -> sink.accept(new Event("delta", Map.of("text", t))));
      class ChatStream implements LlmProvider.Stream {
        final StringBuilder thought = new StringBuilder(); String reasoningId = U.uuid(); boolean reasoningActive;
        @Override public void token(String t) { reasoningDone(); ex.accept(t); }
        @Override public void reset() {
          if (!ex.sent().isEmpty() || !thought.isEmpty()) sink.accept(new Event("reset", Map.of("reasoningId", reasoningId)));
          ex.reset(); thought.setLength(0); reasoningActive = false; reasoningId = U.uuid();
        }
        @Override public void thinking() { sink.accept(new Event("status", Map.of("text", "思考中…"))); }
        @Override public void reasoning(String t) {
          if (t.isEmpty()) return;
          thought.append(t); reasoningActive = true;
          sink.accept(new Event("reasoning", Map.of("id", reasoningId, "text", t)));
        }
        @Override public void reasoningDone() {
          if (!reasoningActive) return; reasoningActive = false;
          sink.accept(new Event("reasoning_done", Map.of("id", reasoningId)));
        }
        void save() {
          if (!thought.isEmpty()) db.update("INSERT INTO chat_message(session_id,workspace_id,role,content,created_at) VALUES(?,?,?,?,?)", id, Tenant.id(), "reasoning", thought.toString(), clk.iso());
        }
        @Override public boolean cancelled() { return stopRequested.contains(id); }
      }
      var st = new ChatStream();
      try {
        r = provider().chatStream(new LlmProvider.Request(ROLE, IntakePrompt.SYSTEM, IntakePrompt.prompt(d, precheck(d), projectsBrief(), linesBrief(), history(id), turnResults, step, c.maxStepsPerTurn) + (drafting(id) ? IntakePrompt.PHASE_DRAFTING : IntakePrompt.PHASE_SETUP) + (readOnly ? IntakePrompt.READ_ONLY : ""), PROMPT_VERSION,
            "reply 控制在 300 字以内，draft 只写变化的字段，tool_calls 最多 3 个。"), IntakePrompt::validate, st);
      } catch (LlmProvider.Cancelled e) {
        st.save();
        charge(id, step, e.model, e.tokens, e.usd);
        String part = ex.sent().trim();
        if (!part.isEmpty()) db.update("INSERT INTO chat_message(session_id,workspace_id,role,content,model,tokens,usd,created_at) VALUES(?,?,?,?,?,?,?,?)", id, Tenant.id(), "assistant", part + "\n\n（已中断）", e.model, e.tokens, e.usd, clk.iso());
        sink.accept(new Event("stopped", Map.of())); break;
      } catch (LlmProvider.OutputException e) {
        st.save();
        charge(id, step, e.model, e.tokens, e.usd);
        if (!ex.sent().isEmpty()) sink.accept(new Event("reset", Map.of()));
        sink.accept(new Event("error", Map.of("message", "模型输出不合格：" + Redactor.redact(e.getMessage())))); break;
      } catch (RuntimeException e) {
        st.save();
        log.warn("录入 agent 调用失败：{}", Redactor.redact(e.getMessage()));
        String part = ex.sent().trim();
        if (!part.isEmpty()) db.update("INSERT INTO chat_message(session_id,workspace_id,role,content,created_at) VALUES(?,?,?,?,?)", id, Tenant.id(), "assistant", part + "\n\n（已中断）", clk.iso());
        sink.accept(new Event("error", Map.of("message", "模型调用失败：" + Redactor.redact(e.getMessage())))); break;
      } finally { st.reasoningDone(); }
      st.save();
      charge(id, step, r.model(), r.tokens(), r.usd());
      JsonNode out = OpenAiCompatProvider.parseObject(r.text()); if (out == null) out = Json.M.createObjectNode();
      // 草稿补丁
      if (!readOnly && out.has("draft") && out.get("draft").isObject() && out.get("draft").size() > 0) {
        synchronized (lock(id)) {
          var cur = draft(id); var before = Json.str(cur); cur.merge(out.get("draft"));
          if (cur.projectId != null && projects.get(cur.projectId) == null) cur.projectId = null; // agent 只能选当前工作区的项目
          if (!Json.str(cur).equals(before)) { int v = saveDraft(id, ((Number) session(id).get("draft_version")).intValue() + 1, cur, "agent"); boolean was = drafting(id); if (!was && cur.projectId != null) was = enterDrafting(id, cur.projectId) || drafting(id); if (was) sink.accept(new Event("draft", Map.of("version", v, "draft", cur, "precheck", precheck(cur)))); }
        }
      }
      if (!readOnly) proposeFromModel(id, out.path("actions"), sink, turnResults);
      var gt = Guides.of(out.path("guide").path("topic").asText(null));
      if (gt != null && !(calls(out) && !last)) guide(id, gt, sink);
      var calls = out.path("tool_calls");
      if (!last && calls.isArray() && calls.size() > 0) {
        int n = 0;
        for (var call : calls) {
          if (n++ >= 3) break;
          String name = call.path("name").asText(""); var s2 = session(id);
          var res = tools.run(name, call.path("args"), draft(id), ((Number) s2.get("file_reads")).intValue(), c.maxFileReads);
          if (res.fileRead()) db.update("UPDATE chat_session SET file_reads=file_reads+1 WHERE id=? AND workspace_id=?", id, Tenant.id());
          db.update("INSERT INTO chat_message(session_id,workspace_id,role,tool_name,tool_summary,tool_hash,created_at) VALUES(?,?,?,?,?,?,?)", id, Tenant.id(), "tool", U.ellipsis(name, 60), res.summary(), U.sha256(res.full()), clk.iso());
          turnResults.add(res.full()); sink.accept(new Event("tool", Map.of("name", name, "summary", res.summary())));
        }
        String partial = out.path("reply").asText("");
        if (!partial.isBlank()) sink.accept(new Event("status", Map.of("text", U.ellipsis(partial, 200))));
        continue;
      }
      reply = out.path("reply").asText("").trim();
      if (reply.isEmpty()) reply = "我更新了右侧的需求卡，你看看。";
      db.update("INSERT INTO chat_message(session_id,workspace_id,role,content,model,tokens,usd,created_at) VALUES(?,?,?,?,?,?,?,?)", id, Tenant.id(), "assistant", reply, r.model(), r.tokens(), r.usd(), clk.iso());
      // 已经流式推送过的部分不再重复；最终回复和已推送的对不上（模型没按顺序、兜底文案）就 reset 后整段补发
      String sent = ex.sent();
      if (!sent.isEmpty() && !reply.startsWith(sent.strip()) && !reply.equals(sent.strip())) { sink.accept(new Event("reset", Map.of())); sent = ""; }
      String rest = sent.isEmpty() ? reply : reply.substring(Math.min(reply.length(), sent.strip().length()));
      if (!rest.isEmpty()) sink.accept(new Event("delta", Map.of("text", rest)));
      if (!readOnly) suggest(id, out.path("suggestions"), sink);
      break;
    }
    sink.accept(new Event("done", doneEvent(id, reply != null)));
    return reply;
  }

  private static boolean calls(JsonNode out) { var c = out.path("tool_calls"); return c.isArray() && c.size() > 0; }
  private void charge(String id, int step, String model, long tokens, double usd) {
    if (tokens == 0 && usd == 0) return;
    var s = session(id); String pid = (String) s.get("project_id"); String line = null;
    if (pid != null) { var p = projects.get(pid); if (p != null) line = p.productLineId(); else pid = null; }
    budget.chargeProject("chat:" + id + ":" + s.get("turns") + ":" + step + ":" + U.uuid().substring(0, 8), null, line, pid, ROLE, model, tokens, usd, 0);
    db.update("UPDATE chat_session SET usd_spent=usd_spent+?, tokens=tokens+? WHERE id=? AND workspace_id=?", usd, tokens, id, Tenant.id());
  }

  private List<Map<String, Object>> history(String id) {
    var rows = db.rows("SELECT role,content,tool_name,tool_summary FROM chat_message WHERE session_id=? AND workspace_id=? AND role<>'reasoning' ORDER BY id DESC LIMIT 30", id, Tenant.id());
    Collections.reverse(rows); return rows;
  }
  private List<Map<String, Object>> projectsBrief() { return tools.listProjects(); }
  private List<Map<String, Object>> linesBrief() { return tools.listLines(); }

  // ───────── 确认（唯一的建任务入口）
  public Confirmed confirm(String id, int baseVersion, String actor, boolean approver) {
    synchronized (lock(id)) {
      var s = session(id);
      if (s.get("task_id") != null) {
        String tid = (String) s.get("task_id"); var tr = db.row("SELECT status, discard_reason FROM task WHERE id=?", tid);
        // 需澄清的任务：用补充后的草稿重启原任务（不新建）
        if (tr != null && "open".equals(s.get("status")) && "DISCARDED".equals(tr.get("status")) && "needs_clarification".equals(tr.get("discard_reason"))) {
          int cur = ((Number) s.get("draft_version")).intValue();
          if (baseVersion != cur) throw new Conflict("草稿已被更新（当前版本 " + cur + "），请检查最新草稿后再确认");
          var d = draft(id); var pc = precheck(d);
          if (!pc.ok()) throw new IllegalArgumentException("预检未通过：" + String.join("；", pc.problems()));
          engine.editDiscarded(tid, d.title.trim(), d.prdMarkdown(), d.acceptanceText(), true, actor);
          db.update("UPDATE chat_session SET status='confirmed', updated_at=? WHERE id=? AND workspace_id=?", clk.iso(), id, Tenant.id());
          audit.task(tid, null, "CHAT_CONFIRMED", actor, Map.of("chat", id, "draftVersion", cur, "restart", true), tid + ":chat-confirm:v" + cur);
          return new Confirmed(tid, false, false, null);
        }
        return new Confirmed(tid, false, false, null); // 幂等
      }
      if (!"open".equals(s.get("status")) && !"pending_approval".equals(s.get("status"))) throw new Conflict("会话已关闭");
      int cur = ((Number) s.get("draft_version")).intValue();
      if (baseVersion != cur) throw new Conflict("草稿已被更新（当前版本 " + cur + "），请检查最新草稿后再确认");
      var d = draft(id); var pc = precheck(d);
      if (!pc.ok()) throw new IllegalArgumentException("预检未通过：" + String.join("；", pc.problems()));
      var p = projects.require(d.projectId);
      String snapshot = null; Object plan = null;
      if (planHook != null) {
        var pin = planHook.pin(d, p.productLineId(), p.id()); snapshot = pin.snapshotId(); plan = pin.plan();
        if (pin.needsApproval() && !approver) {
          db.update("UPDATE chat_session SET status='pending_approval', updated_at=? WHERE id=? AND workspace_id=?", clk.iso(), id, Tenant.id());
          audit.sys("CHAT_PLAN_APPROVAL_REQUESTED", actor, Map.of("chat", id, "draftVersion", cur), "chat-approval:" + id + ":" + cur);
          return new Confirmed(null, false, true, plan);
        }
      }
      budget.assertAdmission(p.id());
      var res = engine.ingest(new Engine.Ingest(p.productLineId(), d.title.trim(), d.prdMarkdown(), "chat", "chat:" + id, null, false, null, p.id(), d.acceptanceText(), snapshot, d.targetPaths));
      db.update("UPDATE task SET source_chat_id=? WHERE id=?", id, res.taskId());
      db.update("UPDATE chat_session SET status='confirmed', task_id=?, project_id=?, anchor_kind='task', anchor_id=?, updated_at=? WHERE id=? AND workspace_id=?", res.taskId(), p.id(), res.taskId(), clk.iso(), id, Tenant.id());
      Map<String, Object> ap = new LinkedHashMap<>(); ap.put("chat", id); ap.put("draftVersion", cur); ap.put("risk", pc.risk()); ap.put("acceptance", d.acceptance.size()); if (snapshot != null) ap.put("planSnapshot", snapshot);
      audit.task(res.taskId(), 1, "CHAT_CONFIRMED", actor, ap, res.taskId() + ":chat-confirm");
      return new Confirmed(res.taskId(), !res.duplicate(), false, plan);
    }
  }
  /** 删除结果：keptTaskId = 由这个会话创建、已保留的任务；waitingClarification = 该任务原本在等对话里补充信息 */
  public record Deleted(String keptTaskId, boolean waitingClarification) {}
  /**
   * 删除会话（连同消息、草稿一起物理删除）。只有发起人或工作区所有者 / 管理员可以删；正在回复时不能删。
   * 由会话创建的任务保留，只解除与会话的关联；等补充信息的任务以后只能在任务详情里修改后重启。
   */
  public Deleted delete(String id, String actor, boolean manager) {
    synchronized (lock(id)) {
      var s = session(id);
      if (!manager && !Objects.equals(actor, s.get("created_by"))) throw new com.x7x24.tenant.WorkspaceService.Forbidden("只有发起人或工作区管理员可以删除这个需求对话");
      if (running.contains(id)) throw new Conflict("AI 正在回复，请先停止或等回复结束再删除");
      String tid = (String) s.get("task_id"); String ws = Tenant.id();
      boolean waiting = false;
      if (tid != null) { var tr = db.row("SELECT status, discard_reason FROM task WHERE id=?", tid); waiting = tr != null && "DISCARDED".equals(tr.get("status")) && "needs_clarification".equals(tr.get("discard_reason")); }
      final boolean w = waiting;
      db.tx(() -> {
        db.update("UPDATE task SET source_chat_id=NULL WHERE source_chat_id=? AND workspace_id=?", id, ws);
        db.update("DELETE FROM chat_message WHERE session_id=? AND workspace_id=?", id, ws);
        db.update("DELETE FROM chat_action WHERE session_id=? AND workspace_id=?", id, ws);
        db.update("DELETE FROM chat_draft WHERE session_id=? AND workspace_id=?", id, ws);
        db.update("DELETE FROM chat_session WHERE id=? AND workspace_id=?", id, ws);
      });
      Map<String, Object> ap = new LinkedHashMap<>(); ap.put("chat", id); ap.put("title", s.get("title") == null ? "" : s.get("title")); if (tid != null) ap.put("task", tid); if (w) ap.put("waitingClarification", true);
      if (tid != null) audit.task(tid, null, "CHAT_DELETED", actor, ap, tid + ":chat-deleted:" + id);
      else audit.sys("CHAT_DELETED", actor, ap, "chat-deleted:" + id);
      locks.remove(id); stopRequested.remove(id);
      return new Deleted(tid, w);
    }
  }
  /** 关闭会话（放弃） */
  public void abandon(String id) {
    var s = session(id); if (s.get("task_id") != null) return;
    db.update("UPDATE chat_session SET status='abandoned', updated_at=? WHERE id=? AND workspace_id=?", clk.iso(), id, Tenant.id());
  }
}
