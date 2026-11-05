package com.x7x24.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.x7x24.adapters.Redactor;
import com.x7x24.db.Db;
import com.x7x24.engine.LineRepo;
import com.x7x24.engine.ProjectRepo;
import com.x7x24.gov.AuditLog;
import com.x7x24.tenant.MemberService;
import com.x7x24.tenant.Tenant;
import com.x7x24.tenant.WorkspaceService;
import com.x7x24.util.Clk;
import com.x7x24.util.Json;
import com.x7x24.util.U;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求对话里的「操作」（docs/design/agent-chat-actions.md，A 类）。
 * agent（或离线规则）只能「提议」：服务端校验并规范化参数后存成待确认卡片；
 * 只有用户点「确认」才执行，执行时用当前登录用户的身份和角色走现有服务的权限检查，绝不提权。
 * 确认是幂等的：同一张卡片重复确认只返回第一次的结果。
 */
public class ChatActions {
  public static final String CREATE_LINE = "create_product_line", CREATE_PROJECT = "create_project", INVITE = "invite_member", BIND_REPO = "bind_repo";
  public static final List<String> KINDS = List.of(CREATE_LINE, CREATE_PROJECT, BIND_REPO, INVITE);
  static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9-]{1,39}"), REPO = Pattern.compile("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
  public static final int MAX_PENDING = 5;

  /** 执行人：login 用于审计；userId 为空表示未登录的开发模式（不能邀请成员）；role 是当前工作区角色 */
  public record Ctx(String login, String userId, String role) {}
  public record Done(Map<String, Object> action, boolean executed, String inviteToken) {}
  /** 参数校验没通过：卡片保持待确认、可在卡片里改参数后再确认。field = 出问题的字段（name / productLineId / repo / baseBranch …） */
  public static class Invalid extends IllegalArgumentException {
    public final String field; public final Map<String, Object> action;
    public Invalid(String msg, String field, Map<String, Object> action) { super(msg); this.field = field; this.action = action; }
  }
  /** 仓库可访问性检查（用已绑定的 GitHub 凭据）：TRUE 可访问 / FALSE 不存在或无权限 / null 无法判断（没连 GitHub 等，不拦） */
  public java.util.function.Function<String, Boolean> repoAccessible;
  /** 当前工作区已绑定的 GitHub 账号 / 组织（github_installation.account_login），按绑定时间排序 */
  public List<String> owners() { return db.rows("SELECT account_login FROM github_installation WHERE workspace_id=? AND account_login IS NOT NULL ORDER BY created_at", Tenant.id()).stream().map(r -> (String) r.get("account_login")).distinct().toList(); }
  static String fieldOf(String msg) {
    String m = msg == null ? "" : msg;
    if (m.contains("仓库")) return "repo"; if (m.contains("分支")) return "baseBranch"; if (m.contains("产品线")) return "productLineId";
    if (m.contains("名称")) return "name"; if (m.contains("角色")) return "role"; if (m.contains("登录名")) return "login"; return null;
  }

  private final Db db; private final Clk clk; private final AuditLog audit; private final LineRepo lines; private final ProjectRepo projects; private final MemberService members;
  private final Map<String, Object> locks = new ConcurrentHashMap<>();
  public ChatActions(Db db, Clk clk, AuditLog audit, LineRepo lines, ProjectRepo projects, MemberService members) { this.db = db; this.clk = clk; this.audit = audit; this.lines = lines; this.projects = projects; this.members = members; }

  // ───────── 提议
  /** 校验并规范化参数，存成待确认卡片。参数不合法 → IllegalArgumentException（原因会反馈给 agent / 用户） */
  public Map<String, Object> propose(String sessionId, String kind, JsonNode raw, String by) {
    if (kind == null || !KINDS.contains(kind)) throw new IllegalArgumentException("不支持的操作：" + kind + "。对话里只能提议：" + String.join(", ", KINDS));
    if (db.count("SELECT COUNT(*) FROM chat_action WHERE session_id=? AND workspace_id=? AND status='proposed'", sessionId, Tenant.id()) >= MAX_PENDING)
      throw new IllegalArgumentException("待确认的操作已有 " + MAX_PENDING + " 个，请先确认或取消");
    JsonNode in = raw == null || !raw.isObject() ? Json.M.createObjectNode() : raw;
    if (CREATE_PROJECT.equals(kind) && in.path("productLineId").asText("").isBlank() && in.path("productLineName").asText(in.path("productLine").asText("")).isBlank()) {
      var s = db.row("SELECT product_line_id FROM chat_session WHERE id=? AND workspace_id=?", sessionId, Tenant.id()); // D3：没说产品线就用会话当前产品线
      if (s != null && s.get("product_line_id") != null) { in = in.deepCopy(); ((ObjectNode) in).put("productLineId", (String) s.get("product_line_id")); }
    }
    if (BIND_REPO.equals(kind) && in.path("projectId").asText("").isBlank() && in.path("projectName").asText("").isBlank()) {
      var s = db.row("SELECT project_id FROM chat_session WHERE id=? AND workspace_id=?", sessionId, Tenant.id()); // 没说项目就用会话当前项目
      if (s != null && s.get("project_id") != null) { in = in.deepCopy(); ((ObjectNode) in).put("projectId", (String) s.get("project_id")); }
    }
    var p = normalize(kind, in);
    String id = "act_" + Long.toHexString(new java.security.SecureRandom().nextLong() & Long.MAX_VALUE);
    db.update("INSERT INTO chat_action(id,session_id,workspace_id,kind,params,status,proposed_by,created_at) VALUES(?,?,?,?,?,?,?,?)", id, sessionId, Tenant.id(), kind, Json.str(p), "proposed", "rules".equals(by) || "user".equals(by) ? by : "agent", clk.iso());
    return get(sessionId, id);
  }

  /** 只接受白名单字段；LLM 给的其它字段一律丢弃 */
  Map<String, Object> normalize(String kind, JsonNode a) {
    Map<String, Object> p = new LinkedHashMap<>();
    switch (kind) {
      case CREATE_LINE -> {
        p.put("name", name(a.path("name").asText(""))); p.put("description", U.ellipsis(a.path("description").asText("").trim(), 500));
      }
      case CREATE_PROJECT -> {
        p.put("name", name(a.path("name").asText("")));
        var line = resolveLine(a.path("productLineId").asText(""), a.path("productLineName").asText(a.path("productLine").asText("")));
        p.put("productLineId", line.id()); p.put("productLineName", line.name());
        repoParams(a.path("repo").isNull() || a.path("bindRepo").asBoolean(true) == false ? "" : a.path("repo").asText(""), a, p);
        p.put("description", U.ellipsis(a.path("description").asText("").trim(), 500));
      }
      case BIND_REPO -> {
        String pid = a.path("projectId").asText("").trim(), pname = a.path("projectName").asText("").trim();
        ProjectRepo.Project pr = pid.isEmpty() ? null : projects.get(pid);
        if (pr == null && !pname.isEmpty()) { var m = projects.list().stream().filter(x -> x.name().equalsIgnoreCase(pname) && !"ARCHIVED".equals(x.state())).toList(); if (m.size() == 1) pr = m.getFirst(); else if (m.size() > 1) throw new IllegalArgumentException("有多个叫「" + pname + "」的项目，请指明"); }
        if (pr == null) throw new IllegalArgumentException(pid.isEmpty() && pname.isEmpty() ? "请说明给哪个项目绑定仓库" : "当前工作区没有这个项目：" + (pname.isEmpty() ? pid : pname));
        if ("ARCHIVED".equals(pr.state())) throw new IllegalArgumentException("项目已归档，不能绑定仓库");
        p.put("projectId", pr.id()); p.put("projectName", pr.name()); p.put("previousRepo", pr.cfg().repo);
        repoParams(a.path("repo").asText(""), a, p);
        if (p.get("repo") == null) throw new IllegalArgumentException("请填写要绑定的仓库（owner/仓库名）");
      }
      case INVITE -> {
        String role = role(a.path("role").asText("member"));
        String login = a.path("login").asText("").trim().replaceFirst("^@", "");
        if (!login.isEmpty() && !LOGIN.matcher(login).matches()) throw new IllegalArgumentException("GitHub 登录名格式不对");
        p.put("login", login.isEmpty() ? null : login); p.put("role", role);
        String pid = a.path("projectId").asText("").trim(); // 记录「想邀请到哪个项目」：权限按工作区生效（见设计文档）
        if (!pid.isEmpty()) { var pr = projects.get(pid); if (pr == null) throw new IllegalArgumentException("项目不存在或不在当前工作区：" + pid); p.put("projectId", pr.id()); p.put("projectName", pr.name()); }
      }
      default -> throw new IllegalArgumentException("不支持的操作");
    }
    return p;
  }
  /** 仓库参数：去掉 github.com 前缀和 .git；只给仓库名时 owner 默认工作区绑定的 GitHub 账号，没有就确认时用确认人的登录名 */
  void repoParams(String raw, JsonNode a, Map<String, Object> p) {
    String repo = raw.trim().replaceFirst("^https?://github\\.com/", "").replaceFirst("\\.git$", "").replaceFirst("/$", "");
    String owner = a.path("owner").asText("").trim().replaceFirst("^@", "");
    if (!repo.isEmpty() && !repo.contains("/")) { if (owner.isEmpty()) { var os = owners(); owner = os.isEmpty() ? "" : os.get(0); } repo = owner.isEmpty() ? repo : owner + "/" + repo; }
    if (!repo.isEmpty() && !repo.contains("/")) p.put("repoNeedsOwner", true);
    else if (!repo.isEmpty() && !REPO.matcher(repo).matches()) throw new IllegalArgumentException("仓库格式应为 owner/name：" + U.ellipsis(repo, 80));
    p.put("repo", repo.isEmpty() ? null : repo);
    String branch = a.path("baseBranch").asText("main").trim(); if (branch.isEmpty()) branch = "main";
    if (!branch.matches("[A-Za-z0-9._/-]{1,100}")) throw new IllegalArgumentException("分支名不合法");
    p.put("baseBranch", branch);
  }
  static String name(String s) { String n = s == null ? "" : s.trim(); if (n.isEmpty() || n.length() > 200) throw new IllegalArgumentException("名称不能为空，且不能超过 200 字"); return n; }
  static String role(String r) {
    String x = r == null ? "" : r.trim().toLowerCase(Locale.ROOT);
    x = switch (x) { case "管理员" -> "admin"; case "成员", "开发", "" -> "member"; case "只读", "访客", "只读成员" -> "viewer"; default -> x; };
    if (!List.of("admin", "member", "viewer").contains(x)) throw new IllegalArgumentException("角色只能是 管理员 / 成员 / 只读（所有者请到成员页转让）");
    return x;
  }
  LineRepo.Line resolveLine(String id, String name) {
    if (!id.isBlank()) { var l = lines.get(id.trim()); if (l != null) return check(l); }
    String n = (name.isBlank() ? id : name).trim();
    if (n.isEmpty()) { var all = lines.list().stream().filter(l -> !"ARCHIVED".equals(l.state())).toList(); if (all.size() == 1) return all.getFirst(); throw new IllegalArgumentException("请说明项目放在哪条产品线下"); }
    var m = lines.list().stream().filter(l -> l.name().equalsIgnoreCase(n) || l.id().equals(n)).toList();
    if (m.size() != 1) throw new IllegalArgumentException(m.isEmpty() ? "当前工作区没有叫「" + n + "」的产品线" : "有多条叫「" + n + "」的产品线，请指明 id");
    return check(m.getFirst());
  }
  private static LineRepo.Line check(LineRepo.Line l) { if ("ARCHIVED".equals(l.state())) throw new IllegalArgumentException("产品线已归档，不能新增项目"); return l; }

  // ───────── 查询
  public List<Map<String, Object>> list(String sessionId) { return db.rows("SELECT * FROM chat_action WHERE session_id=? AND workspace_id=? ORDER BY created_at, id", sessionId, Tenant.id()).stream().map(ChatActions::dto).toList(); }
  public Map<String, Object> get(String sessionId, String id) {
    var r = db.row("SELECT * FROM chat_action WHERE id=? AND session_id=? AND workspace_id=?", id, sessionId, Tenant.id());
    if (r == null) throw new WorkspaceService.NotMember(); return dto(r);
  }
  static Map<String, Object> dto(Map<String, Object> r) {
    Map<String, Object> m = new LinkedHashMap<>(); m.put("id", r.get("id")); m.put("kind", r.get("kind")); m.put("params", Json.tree(Db.str(r.get("params")))); m.put("status", r.get("status"));
    m.put("result", r.get("result") == null ? null : Json.tree(Db.str(r.get("result")))); m.put("error", r.get("error")); m.put("blockedRole", r.get("blocked_role")); m.put("proposedBy", r.get("proposed_by")); m.put("decidedBy", r.get("decided_by")); m.put("createdAt", r.get("created_at")); m.put("decidedAt", r.get("decided_at"));
    return m;
  }

  // ───────── 确认 / 取消
  public Done confirm(String sessionId, String id, Ctx who) {
    synchronized (locks.computeIfAbsent(id, k -> new Object())) {
      var a = get(sessionId, id); String st = (String) a.get("status");
      if ("executed".equals(st)) return new Done(a, false, null); // 幂等
      if (!"proposed".equals(st)) throw new ChatService.Conflict("这个操作已" + ("cancelled".equals(st) ? "取消" : "失败") + "，不能再确认");
      JsonNode p = (JsonNode) a.get("params"); String kind = (String) a.get("kind"); String now = clk.iso();
      Map<String, Object> result = new LinkedHashMap<>(); String token = null;
      try {
        switch (kind) {
          case CREATE_LINE -> {
            requireManager(who, "创建产品线");
            ObjectNode b = Json.M.createObjectNode(); b.put("name", p.path("name").asText()); b.put("description", p.path("description").asText("")); b.put("owner", who.login());
            var l = lines.create(b); result.put("productLineId", l.id()); result.put("name", l.name());
          }
          case CREATE_PROJECT -> {
            requireManager(who, "创建项目");
            if (p.path("repoNeedsOwner").asBoolean(false)) {
              if (who.login() == null || who.login().isBlank()) throw new IllegalArgumentException("仓库需要写成 owner/仓库名（还没有绑定 GitHub 账号）");
              p = ((ObjectNode) p.deepCopy()).put("repo", who.login() + "/" + p.path("repo").asText()); ((ObjectNode) p).remove("repoNeedsOwner");
            }
            if (p.hasNonNull("repo") && repoAccessible != null && Boolean.FALSE.equals(repoAccessible.apply(p.path("repo").asText())))
              throw new IllegalArgumentException("仓库 " + p.path("repo").asText() + " 不存在，或已绑定的 GitHub 账号没有权限。可以换一个仓库，或先「暂不绑定」");
            var line = resolveLine(p.path("productLineId").asText(), ""); // 执行时再查一次：产品线可能已被归档 / 删除
            ObjectNode b = Json.M.createObjectNode(); b.put("name", p.path("name").asText()); b.put("description", p.path("description").asText("")); b.put("owner", who.login());
            ObjectNode c = b.putObject("config"); if (p.hasNonNull("repo")) c.put("repo", p.path("repo").asText()); c.put("baseBranch", p.path("baseBranch").asText("main"));
            var pr = projects.create(line.id(), b); result.put("projectId", pr.id()); result.put("name", pr.name()); result.put("productLineId", line.id());
          }
          case BIND_REPO -> {
            requireManager(who, "绑定仓库");
            if (p.path("repoNeedsOwner").asBoolean(false)) {
              if (who.login() == null || who.login().isBlank()) throw new IllegalArgumentException("仓库需要写成 owner/仓库名（还没有绑定 GitHub 账号）");
              p = ((ObjectNode) p.deepCopy()).put("repo", who.login() + "/" + p.path("repo").asText()); ((ObjectNode) p).remove("repoNeedsOwner");
            }
            String repo = p.path("repo").asText();
            if (repoAccessible != null && Boolean.FALSE.equals(repoAccessible.apply(repo)))
              throw new IllegalArgumentException("仓库 " + repo + " 不存在，或已绑定的 GitHub 账号没有权限。请确认仓库名，或到「集成」里给 GitHub App 加上这个仓库");
            var pr = projects.require(p.path("projectId").asText()); // 执行时再查：项目可能已归档
            if ("ARCHIVED".equals(pr.state())) throw new IllegalArgumentException("项目已归档，不能绑定仓库");
            ObjectNode b = Json.M.createObjectNode(); ObjectNode c = b.putObject("config"); c.put("repo", repo); c.put("baseBranch", p.path("baseBranch").asText("main"));
            projects.update(pr.id(), b); // 复用项目更新校验：仓库是否被别的项目占用、有未结束任务时不能换仓库
            result.put("projectId", pr.id()); result.put("name", pr.name()); result.put("repo", repo); result.put("productLineId", pr.productLineId());
          }
          case INVITE -> {
            if (who.userId() == null) throw new WorkspaceService.Forbidden("邀请成员需要登录");
            // 权限完全复用 MemberService：只有所有者 / 管理员能邀请，只有所有者能邀请管理员
            var c = members.invite(Tenant.id(), new MemberService.Actor(who.userId(), who.login(), who.role()), p.path("role").asText(), p.path("login").isNull() ? null : p.path("login").asText(null));
            token = c.token(); result.put("inviteId", c.id()); result.put("role", p.path("role").asText()); result.put("login", p.path("login").isNull() ? null : p.path("login").asText()); result.put("expiresAt", c.expiresAt());
          }
          default -> throw new IllegalArgumentException("不支持的操作");
        }
      } catch (WorkspaceService.Forbidden e) {
        String need = INVITE.equals(kind) && "admin".equals(p.path("role").asText()) ? "owner" : "admin";
        db.update("UPDATE chat_action SET blocked_role=? WHERE id=? AND workspace_id=? AND status='proposed'", who.userId() == null && INVITE.equals(kind) ? "login" : need, id, Tenant.id());
        audit.sys("CHAT_ACTION_DENIED", who.login(), Map.of("chat", sessionId, "action", id, "kind", kind, "reason", String.valueOf(e.getMessage())), "chat-action-denied:" + id + ":" + now);
        throw e; // 没权限：卡片保持待确认，换有权限的人可以确认
      } catch (IllegalArgumentException e) {
        // 参数问题不再是终态：卡片保持待确认，记下错误，前端在对应字段下提示，改了参数可以再确认
        String msg = Redactor.redact(String.valueOf(e.getMessage()));
        db.update("UPDATE chat_action SET error=? WHERE id=? AND workspace_id=? AND status='proposed'", U.ellipsis(msg, 500), id, Tenant.id());
        audit.sys("CHAT_ACTION_INVALID", who.login(), Map.of("chat", sessionId, "action", id, "kind", kind, "error", U.ellipsis(msg, 200)), "chat-action-invalid:" + id + ":" + now);
        throw new Invalid(msg, fieldOf(msg), get(sessionId, id));
      }
      db.update("UPDATE chat_action SET status='executed', result=?, decided_by=?, decided_at=? WHERE id=? AND workspace_id=? AND status='proposed'", Json.str(result), who.login(), now, id, Tenant.id());
      // 对话归属：建了项目 / 产品线的会话挂到该对象下（只升不降，已绑定任务的不动），侧栏「最近对话」和对象的「对话」页签都能找到
      if (CREATE_PROJECT.equals(kind) || BIND_REPO.equals(kind)) db.update("UPDATE chat_session SET anchor_kind='project', anchor_id=?, project_id=?, product_line_id=?, updated_at=? WHERE id=? AND workspace_id=? AND (anchor_kind IS NULL OR anchor_kind IN ('none','line','project'))", result.get("projectId"), result.get("projectId"), result.get("productLineId"), now, sessionId, Tenant.id());
      else if (CREATE_LINE.equals(kind)) db.update("UPDATE chat_session SET anchor_kind='line', anchor_id=?, product_line_id=COALESCE(product_line_id, ?), updated_at=? WHERE id=? AND workspace_id=? AND (anchor_kind IS NULL OR anchor_kind='none')", result.get("productLineId"), result.get("productLineId"), now, sessionId, Tenant.id());
      Map<String, Object> ap = new LinkedHashMap<>(); ap.put("chat", sessionId); ap.put("action", id); ap.put("kind", kind); ap.put("params", Json.M.convertValue(p, Map.class)); ap.put("result", result);
      audit.sys("CHAT_ACTION_EXECUTED", who.login(), ap, "chat-action:" + id);
      message(sessionId, summary(kind, p, result));
      return new Done(get(sessionId, id), true, token);
    }
  }
  /** 在卡片里改参数（只限待确认）：合并后重新规范化校验，清掉上次的错误 */
  public Map<String, Object> editParams(String sessionId, String id, JsonNode patch, String actor) {
    synchronized (locks.computeIfAbsent(id, k -> new Object())) {
      var a = get(sessionId, id); String st = (String) a.get("status");
      if (!"proposed".equals(st)) throw new ChatService.Conflict("这个操作状态已变化（" + st + "），不能再修改");
      if (patch == null || !patch.isObject()) throw new IllegalArgumentException("参数必须是对象");
      ObjectNode merged = ((JsonNode) a.get("params")).deepCopy(); merged.remove("repoNeedsOwner");
      patch.fields().forEachRemaining(e -> { if (Set.of("name", "description", "productLineId", "productLineName", "repo", "owner", "bindRepo", "baseBranch", "role", "login", "projectId").contains(e.getKey())) merged.set(e.getKey(), e.getValue()); });
      if (merged.has("productLineId") && patch.has("productLineId")) merged.remove("productLineName");
      Map<String, Object> np;
      try { np = normalize((String) a.get("kind"), merged); }
      catch (IllegalArgumentException e) { String msg = Redactor.redact(String.valueOf(e.getMessage())); throw new Invalid(msg, fieldOf(msg), a); }
      db.update("UPDATE chat_action SET params=?, error=NULL WHERE id=? AND workspace_id=? AND status='proposed'", Json.str(np), id, Tenant.id());
      audit.sys("CHAT_ACTION_EDITED", actor, Map.of("chat", sessionId, "action", id), "chat-action-edit:" + id + ":" + clk.iso());
      return get(sessionId, id);
    }
  }
  /** 失败 / 已取消的卡片「重新发起」：用原参数（可附带修改）新建一张待确认卡 */
  public Map<String, Object> repropose(String sessionId, String id, JsonNode patch) {
    var a = get(sessionId, id); String st = (String) a.get("status");
    if ("proposed".equals(st) || "executed".equals(st)) throw new ChatService.Conflict("这个操作" + ("executed".equals(st) ? "已完成" : "还在待确认") + "，不需要重新发起");
    ObjectNode merged = ((JsonNode) a.get("params")).deepCopy(); merged.remove("repoNeedsOwner");
    if (patch != null && patch.isObject()) patch.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
    try { return propose(sessionId, (String) a.get("kind"), merged, "user"); }
    catch (IllegalArgumentException e) { String msg = Redactor.redact(String.valueOf(e.getMessage())); throw new Invalid(msg, fieldOf(msg), a); }
  }
  public Map<String, Object> cancel(String sessionId, String id, String actor) {
    var a = get(sessionId, id);
    if ("proposed".equals(a.get("status"))) {
      db.update("UPDATE chat_action SET status='cancelled', decided_by=?, decided_at=? WHERE id=? AND workspace_id=? AND status='proposed'", actor, clk.iso(), id, Tenant.id());
      audit.sys("CHAT_ACTION_CANCELLED", actor, Map.of("chat", sessionId, "action", id, "kind", a.get("kind")), "chat-action-cancel:" + id);
    }
    return get(sessionId, id);
  }
  private static void requireManager(Ctx who, String what) { if (!WorkspaceService.manager(who.role())) throw new WorkspaceService.Forbidden("只有工作区所有者 / 管理员可以" + what); }
  private void message(String sessionId, String text) { db.update("INSERT INTO chat_message(session_id,workspace_id,role,content,created_at) VALUES(?,?,?,?,?)", sessionId, Tenant.id(), "assistant", text, clk.iso()); }
  static final Map<String, String> ROLE_ZH = Map.of("admin", "管理员", "member", "成员", "viewer", "只读");
  public static String summary(String kind, JsonNode p, Map<String, Object> r) {
    return switch (kind) {
      case CREATE_LINE -> "已新增产品线「" + r.get("name") + "」。";
      case CREATE_PROJECT -> "已在产品线「" + p.path("productLineName").asText() + "」下新增项目「" + r.get("name") + "」" + (p.hasNonNull("repo") ? "，仓库 " + p.path("repo").asText() : "，还没绑定仓库，可以到项目页补上") + "。";
      case BIND_REPO -> "已把仓库 " + r.get("repo") + " 绑定到项目「" + r.get("name") + "」（分支 " + p.path("baseBranch").asText("main") + "）。";
      case INVITE -> "已创建邀请（角色：" + ROLE_ZH.getOrDefault(p.path("role").asText(), p.path("role").asText()) + (p.hasNonNull("login") ? "，只能由 @" + p.path("login").asText() + " 接受" : "") + "），7 天内有效。邀请链接只在确认后显示一次，请复制发给对方。";
      default -> "操作已完成。";
    };
  }

  // ───────── 离线规则（模型不可用 / 只记录模式也能用）
  public record Intent(String kind, ObjectNode args) {}
  static final Pattern R_LINE = Pattern.compile("^(?:请)?(?:帮我)?(?:新增|新建|创建|添加|加)(?:一个|一条)?产品线\\s*[：:]?\\s*[「“\"']?([^」”\"'\\n]{1,200}?)[」”\"']?\\s*$");
  static final Pattern R_PROJECT = Pattern.compile("^(?:请)?(?:帮我)?(?:新增|新建|创建|添加|加)(?:一个)?项目\\s*[：:]?\\s*[「“\"']?([^」”\"'\\s，,]{1,200})[」”\"']?(.*)$");
  static final Pattern R_INVITE = Pattern.compile("^(?:请)?(?:帮我)?邀请\\s*@?([A-Za-z0-9-]{1,39})?\\s*(?:加入)?(?:工作区)?\\s*(?:为|作为|当|成为|做)?\\s*(管理员|成员|只读成员|只读|admin|member|viewer)?\\s*$", Pattern.CASE_INSENSITIVE);
  static final Pattern R_BIND = Pattern.compile("^(?:请)?(?:帮我)?(?:给|为)?(?:项目)?\\s*[「“\"']?([^」”\"'\\s]{0,200}?)[」”\"']?\\s*(?:绑定|关联|绑)(?:到)?(?:仓库|repo)\\s*[：:]?\\s*(\\S+)$", Pattern.CASE_INSENSITIVE);
  static final Pattern R_IN_LINE = Pattern.compile("(?:到|在|放在|属于)?\\s*产品线\\s*[：:]?\\s*[「“\"']?([^」”\"'\\s，,]+)[」”\"']?"), R_REPO = Pattern.compile("仓库\\s*[：:]?\\s*(\\S+)");
  /** 只认很明确的命令句式，其它一律交给模型，不会误伤普通需求描述 */
  public static Intent parse(String text) {
    String t = text == null ? "" : text.trim().replaceAll("[。.！!]+$", "");
    Matcher m;
    if ((m = R_LINE.matcher(t)).matches()) { var a = Json.M.createObjectNode(); a.put("name", m.group(1).trim()); return new Intent(CREATE_LINE, a); }
    if ((m = R_PROJECT.matcher(t)).matches()) {
      var a = Json.M.createObjectNode(); a.put("name", m.group(1).trim()); String rest = m.group(2) == null ? "" : m.group(2);
      Matcher l = R_IN_LINE.matcher(rest); if (l.find()) a.put("productLineName", l.group(1));
      Matcher r = R_REPO.matcher(rest); if (r.find()) a.put("repo", r.group(1).replaceAll("[，,。]$", ""));
      return new Intent(CREATE_PROJECT, a);
    }
    if ((m = R_BIND.matcher(t)).matches()) { var a = Json.M.createObjectNode(); if (!m.group(1).isBlank()) a.put("projectName", m.group(1).trim()); a.put("repo", m.group(2).replaceAll("[，,。]$", "")); return new Intent(BIND_REPO, a); }
    if ((m = R_INVITE.matcher(t)).matches() && (m.group(1) != null || m.group(2) != null)) {
      var a = Json.M.createObjectNode(); if (m.group(1) != null) a.put("login", m.group(1)); a.put("role", m.group(2) == null ? "member" : m.group(2)); return new Intent(INVITE, a);
    }
    return null;
  }
}
