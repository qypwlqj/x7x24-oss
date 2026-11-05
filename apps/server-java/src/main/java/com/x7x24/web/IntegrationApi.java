package com.x7x24.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.*;
import com.x7x24.db.Db;
import com.x7x24.engine.*;
import com.x7x24.gov.AuditLog;
import com.x7x24.integration.*;
import com.x7x24.util.Clk;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 集成：连接 GitHub（设备码 / 粘贴令牌）、仓库选择、写入模式、连接状态。令牌永远不出现在任何响应里。 */
@RestController
@RequestMapping("/api")
public class IntegrationApi {
  private final CredentialProvider creds; private final GitHubAuth auth; private final RuntimeSettings settings; private final AuditLog audit; private final ProjectRepo projects;
  private final DeliveryTools tools; private final CallLog calls; private final Db db; private final Clk clk; private final X7Props props;
  public IntegrationApi(CredentialProvider creds, GitHubAuth auth, RuntimeSettings settings, AuditLog audit, ProjectRepo projects, DeliveryTools tools, CallLog calls, Db db, Clk clk, X7Props props, com.x7x24.auth.AuthService authSvc) {
    this.authSvc = authSvc; this.creds = creds; this.auth = auth; this.settings = settings; this.audit = audit; this.projects = projects; this.tools = tools; this.calls = calls; this.db = db; this.clk = clk; this.props = props;
  }
  private final com.x7x24.auth.AuthService authSvc;
  /** 安全提示：如实反映当前鉴权配置 */
  public static String securityNotice(boolean required, boolean allowlist) {
    String tok = "GitHub 令牌在服务端以 AES-GCM 加密保存，页面和接口永远不会显示明文。";
    if (!required) return "登录鉴权已关闭（X7_AUTH_REQUIRED=false），任何能访问本服务的人都能操作，请只在本机或内网使用。" + tok;
    return (allowlist ? "已启用 GitHub 登录，只有白名单内的 GitHub 账号可以访问。" : "已启用 GitHub 登录，但未设置账号白名单（X7_ALLOWED_GITHUB_LOGINS），任何 GitHub 用户都能登录，建议尽快设置。") + tok;
  }
  private GitHubTools gh() { return tools instanceof GitHubTools g ? g : null; }
  private String mode() { return !creds.hasToken() ? "experience" : settings.dryRun() || !"real".equalsIgnoreCase(settings.adapterMode()) ? "observe" : "execute"; }

  private String userId(jakarta.servlet.http.HttpServletRequest req) { return req.getAttribute(com.x7x24.auth.AuthFilter.ATTR) instanceof com.x7x24.auth.AuthService.User user ? user.id() : null; }
  @GetMapping("/integrations/github") public Map<String, Object> github(@RequestParam(defaultValue = "false") boolean refresh, jakarta.servlet.http.HttpServletRequest req) {
    String userId = userId(req); var connection = auth.connection(userId, refresh);
    boolean available = creds.hasToken() || creds.hasUser(userId);
    Map<String, Object> o = new LinkedHashMap<>(); o.put("connected", available && !List.of("reauth_required", "network_error", "disconnected").contains(connection.get("state"))); o.put("credential", (creds.hasToken() ? creds.info() : creds.userInfo(userId)).toMap()); o.put("mode", mode());
    o.put("connection", connection); o.put("authorizationAvailable", available); o.put("signedIn", userId != null); o.put("canManage", com.x7x24.tenant.WorkspaceFilter.manager(req)); o.put("backgroundIdentity", creds.installationEnabled() ? "app" : "user");
    o.put("oauthAvailable", props.github.oauthClientId != null && !props.github.oauthClientId.isBlank()); o.put("oauthClientId", props.github.oauthClientId);
    o.put("minPermissions", GitHubAuth.MIN_PERMS); o.put("appInstallUrl", props.github.appSlug == null || props.github.appSlug.isBlank() ? null : "https://github.com/apps/" + props.github.appSlug + "/installations/new");
    o.put("secretKeyOrigin", creds.box().origin()); o.put("notice", securityNotice(authSvc.required, authSvc.allowlistEnabled()));
    return o;
  }
  @PostMapping("/integrations/github/device/start") public Map<String, Object> deviceStart(jakarta.servlet.http.HttpServletRequest req) { com.x7x24.tenant.WorkspaceFilter.requireManager(req, "连接 GitHub"); return auth.startDevice(); }
  @RequestMapping(value = "/integrations/github/device/poll", method = {RequestMethod.POST, RequestMethod.GET})
  public Map<String, Object> devicePoll(@RequestParam(required = false) String sessionId, @RequestBody(required = false) JsonNode body, jakarta.servlet.http.HttpServletRequest req) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(req, "连接 GitHub");
    String id = sessionId != null ? sessionId : body == null ? null : body.path("sessionId").asText(null);
    if (id == null) throw new IllegalArgumentException("缺少 sessionId");
    var r = new LinkedHashMap<>(auth.pollDevice(id)); if ("connected".equals(r.get("status"))) { r.put("credential", creds.info().toMap()); if (gh() != null) gh().invalidateConnections(); } return r;
  }
  @PostMapping("/integrations/github/token") public Map<String, Object> pasteToken(@RequestBody JsonNode body, jakarta.servlet.http.HttpServletRequest req) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(req, "连接 GitHub");
    String token = body.path("token").asText("").trim();
    if (token.length() < 20 || token.contains(" ")) throw new IllegalArgumentException("令牌格式不正确：请粘贴完整的 GitHub 令牌（不要包含空格）");
    GitHubAuth.Validated v;
    try { v = auth.validate(token); } catch (GitHubClient.GitHubException e) { throw new IllegalArgumentException(e.status == 401 ? "GitHub 拒绝了这个令牌：无效、已过期或已被撤销" : Humanize.reason(e.status, e.getMessage())); }
    auth.saveConnected("pat", token, v, null); if (gh() != null) gh().invalidateConnections();
    Map<String, Object> o = new LinkedHashMap<>(); o.put("ok", true); o.put("credential", creds.info().toMap()); o.put("minPermissions", GitHubAuth.MIN_PERMS);
    List<String> warn = new ArrayList<>(); if (!v.scopes().isBlank()) { var sc = Arrays.asList(v.scopes().split("[ ,]+")); if (!sc.contains("repo")) warn.add("缺少 repo 权限范围，无法读写私有仓库"); if (!sc.contains("workflow")) warn.add("缺少 workflow 权限范围，无法触发 CI"); }
    o.put("warnings", warn); return o;
  }
  @DeleteMapping("/integrations/github") public Map<String, Object> disconnect(jakarta.servlet.http.HttpServletRequest req) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(req, "断开 GitHub");
    var info = creds.info();
    if ("env".equals(info.source())) throw new IllegalArgumentException("当前令牌由服务器环境变量 GITHUB_TOKEN 提供，不能在界面删除。连接自己的账号即可覆盖它。");
    if (!creds.delete()) throw new IllegalArgumentException("当前没有已保存的 GitHub 连接");
    audit.sys("INTEGRATION_DISCONNECTED", "operator", Map.of("provider", "github", "login", String.valueOf(info.login())), null);
    if (gh() != null) gh().invalidateConnections();
    return Map.of("ok", true, "credential", creds.info().toMap(), "mode", mode());
  }
  @GetMapping("/integrations/github/repos") public Map<String, Object> repos(@RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size, jakarta.servlet.http.HttpServletRequest req) {
    Map<String, String> bound = new HashMap<>(); for (var p : projects.list()) if (!"ARCHIVED".equals(p.state()) && p.cfg().repo != null) bound.put(p.cfg().repo.toLowerCase(Locale.ROOT), p.id());
    return auth.repos(q, Math.max(1, page), Math.max(1, Math.min(size, 100)), bound, userId(req));
  }
  public record RepositoryGrant(String fullName, long installationId) {}
  @PostMapping("/integrations/github/use-login") public Map<String, Object> useLogin(jakarta.servlet.http.HttpServletRequest req) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(req, "更新工作区 GitHub 授权");
    creds.attachUser(userId(req));
    if (!"real".equalsIgnoreCase(settings.adapterMode())) { settings.setDryRun(true); settings.setAdapterMode("real"); }
    audit.sys("INTEGRATION_CONNECTED", "operator", Map.of("provider", "github", "source", "login", "user", userId(req)), null);
    if (gh() != null) gh().invalidateConnections();
    return Map.of("ok", true);
  }
  public record InstallRequest(String redirect) {}
  @PostMapping("/integrations/github/install") public Map<String, Object> installation(@RequestBody InstallRequest body, jakarta.servlet.http.HttpServletRequest req) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(req, "管理仓库授权");
    if (props.github.appSlug == null || props.github.appSlug.isBlank()) throw new IllegalArgumentException("管理员尚未配置 GitHub App 的安装地址");
    String user = userId(req);
    if (user == null) return Map.of("url", "https://github.com/apps/" + props.github.appSlug + "/installations/new");
    String state = authSvc.installationState(user, com.x7x24.tenant.Tenant.id(), body.redirect());
    return Map.of("url", "https://github.com/apps/" + props.github.appSlug + "/installations/new?state=" + java.net.URLEncoder.encode(state, java.nio.charset.StandardCharsets.UTF_8));
  }
  @PostMapping("/integrations/github/repositories/bind") public Map<String, Object> bindRepository(@RequestBody RepositoryGrant body, jakarta.servlet.http.HttpServletRequest req) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(req, "授权工作区仓库");
    auth.bindRepository(userId(req), body.fullName(), body.installationId());
    if (gh() != null) gh().invalidateConnections();
    return Map.of("ok", true);
  }
  public record ModeBody(String mode, Boolean confirm) {}
  @PutMapping("/integrations/mode") public Map<String, Object> setMode(@RequestBody ModeBody b, jakarta.servlet.http.HttpServletRequest req) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(req, "切换运行模式");
    if (!List.of("observe", "execute").contains(b.mode())) throw new IllegalArgumentException("模式只能是 observe（观察）或 execute（执行）");
    if (!creds.hasToken()) throw new IllegalArgumentException("还没有连接 GitHub，当前是体验模式，请先连接");
    if ("execute".equals(b.mode()) && !Boolean.TRUE.equals(b.confirm())) throw new IllegalArgumentException("切换到执行模式需要确认：7x24 将真实创建 Issue、分支、PR 并发布");
    String before = mode(); settings.setAdapterMode("real"); settings.setDryRun("observe".equals(b.mode()));
    audit.sys("INTEGRATION_MODE_CHANGED", "operator", Map.of("from", before, "to", mode()), null);
    return Map.of("mode", mode());
  }
  @PostMapping("/projects/{id}/ci-template-pr") public Map<String, Object> ciPr(@PathVariable String id, @RequestBody(required = false) com.fasterxml.jackson.databind.JsonNode body) {
    var project = projects.require(id); if (gh() == null) throw new IllegalArgumentException("当前适配器不支持");
    return gh().bootstrapCi(project.cfg(), body != null && body.path("repair").asBoolean(false));
  }

  // ── 连接状态（原适配器页）
  private static Map<String, Object> card(String id, String title, String state, String text, String fixLabel, String fixTo) {
    Map<String, Object> m = new LinkedHashMap<>(); m.put("id", id); m.put("title", title); m.put("state", state); m.put("text", text); m.put("fixLabel", fixLabel); m.put("fixTo", fixTo); return m;
  }
  @GetMapping("/integrations/status") public Map<String, Object> status() {
    boolean has = creds.hasToken(); String mode = mode(); var info = creds.info();
    Map<String, Object> o = new LinkedHashMap<>(); o.put("mode", mode); o.put("credential", info.toMap()); List<Map<String, Object>> cards = new ArrayList<>();
    // 1 连通
    Map<String, Object> reach = null;
    if (!has) cards.add(card("connected", "GitHub 是否连通", "disconnected", "还没有连接 GitHub，当前是体验模式，不会访问 GitHub。", "去连接", "integrations/connect"));
    else {
      var connection = auth.connection(null, false);
      long fail = db.count("SELECT COUNT(*) FROM adapter_call WHERE ok=0 AND dry_run=0 AND op<>'git.read_rulesets' AND id > COALESCE((SELECT MAX(id) FROM adapter_call WHERE ok=1 AND dry_run=0),0)");
      var last = db.row("SELECT * FROM adapter_call WHERE dry_run=0 AND op<>'git.read_rulesets' ORDER BY id DESC LIMIT 1");
      boolean bad = last != null && Db.i(last.get("ok")) == 0 && fail >= 1; boolean unauthorized = last != null && Db.i(last.get("ok")) == 0 && last.get("status") != null && Db.i(last.get("status")) == 401;
      cards.add(!"ready".equals(connection.get("state")) ? card("connected", "GitHub 是否连通", "attention", String.valueOf(connection.get("message")), "处理授权", "integrations/connect") : bad ? card("connected", "GitHub 是否连通", "attention", "最近一次访问失败：" + Humanize.reason(last.get("status") == null ? null : Db.i(last.get("status")), Db.str(last.get("note"))), unauthorized ? "重新连接" : "运行体检", unauthorized ? "integrations/connect" : "integrations/projects")
          : card("connected", "GitHub 是否连通", "ok", "已用 " + (info.login() != null ? info.login() : "服务器令牌") + " 连接 GitHub。", null, null));
    }
    // 2 写入
    if (!has) cards.add(card("writable", "能否写入", "disconnected", "未连接，所有写操作都不会发出。", "去连接", "integrations/connect"));
    else if ("observe".equals(mode)) cards.add(card("writable", "能否写入", "attention", "当前是观察模式：只看不改，不会创建 Issue、PR 或发布。", "切换到执行模式", "integrations/connect"));
    else cards.add(card("writable", "能否写入", "ok", "执行模式已开启：会真实创建 Issue、PR 并发布。", null, null));
    // 3 最近成功
    var ok = db.row("SELECT at FROM adapter_call WHERE ok=1 AND dry_run=0 ORDER BY id DESC LIMIT 1");
    cards.add(!has ? card("lastSuccess", "最近一次成功", "disconnected", "尚无记录。", null, null) : ok == null ? card("lastSuccess", "最近一次成功", "attention", "已连接，但还没有成功访问过 GitHub。", "运行体检", "integrations/projects") : card("lastSuccess", "最近一次成功", "ok", "最近一次成功访问：" + Db.str(ok.get("at")).replace('T', ' ').substring(0, 19) + "（UTC）", null, null));
    o.put("cards", cards);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var p : projects.list()) {
      if ("ARCHIVED".equals(p.state())) continue;
      Map<String, Object> r = new LinkedHashMap<>(); var health = gh() == null ? null : gh().lastHealth(p.id());
      r.put("id", p.id()); r.put("name", p.name()); r.put("repo", p.cfg().repo); r.put("branch", p.cfg().baseBranch); r.put("workflow", p.cfg().workflow);
      r.put("connection", !has ? "disconnected" : health == null ? "unchecked" : Boolean.TRUE.equals(health.get("ok")) ? "ok" : "attention");
      r.put("ci", !has || health == null ? "unchecked" : Boolean.TRUE.equals(health.get("workflowExists")) ? "exists" : "missing");
      var sync = db.row("SELECT at FROM adapter_call WHERE project_id=? AND ok=1 AND dry_run=0 ORDER BY id DESC LIMIT 1", p.id()); r.put("lastSync", sync == null ? null : sync.get("at")); r.put("checkedAt", health == null ? null : health.get("checkedAt"));
      rows.add(r);
    }
    o.put("projects", rows);
    // 高级诊断
    Integer limit = null, remaining = null; String resetAt = null; int retries = 0; long throttled = 0;
    if (gh() != null) for (var p : projects.list()) { var ls = gh().lineStatus(p.cfg()); if (ls.get("rate") instanceof Map<?, ?> rt) { if (rt.get("limit") != null) limit = (Integer) rt.get("limit"); if (rt.get("remaining") != null) remaining = (Integer) rt.get("remaining"); resetAt = (String) rt.get("resetAt"); retries += (Integer) rt.get("retries"); throttled += (Long) rt.get("throttledMs"); } }
    long total = db.count("SELECT COUNT(*) FROM adapter_call"), failed = db.count("SELECT COUNT(*) FROM adapter_call WHERE ok=0 AND dry_run=0 AND op<>'git.read_rulesets'"), observed = db.count("SELECT COUNT(*) FROM adapter_call WHERE dry_run=1");
    Map<String, Object> diag = new LinkedHashMap<>(); diag.put("limit", limit); diag.put("remaining", remaining); diag.put("resetAt", resetAt); diag.put("retries", retries); diag.put("throttledMs", throttled); diag.put("calls", total); diag.put("failures", failed); diag.put("observedOnly", observed);
    o.put("diagnostics", diag);
    List<String> alerts = new ArrayList<>();
    if (limit != null && remaining != null && limit > 0 && remaining < limit * 0.1) alerts.add("GitHub 请求额度快用完了（剩余 " + remaining + " / " + limit + "），7x24 会自动放慢速度。");
    var recentFail = db.rows("SELECT * FROM adapter_call WHERE ok=0 AND dry_run=0 AND op<>'git.read_rulesets' ORDER BY id DESC LIMIT 1");
    if (!recentFail.isEmpty() && has) { var f = recentFail.get(0); var lastAny = db.row("SELECT id FROM adapter_call WHERE dry_run=0 AND op<>'git.read_rulesets' ORDER BY id DESC LIMIT 1"); if (lastAny != null && Db.l(lastAny.get("id")) == Db.l(f.get("id"))) alerts.add(Humanize.call(Db.str(f.get("op")), f.get("status") == null ? null : Db.i(f.get("status")), false, false, Db.str(f.get("note")))); }
    o.put("alerts", alerts);
    List<Map<String, Object>> recent = new ArrayList<>();
    for (var c : db.rows("SELECT * FROM adapter_call ORDER BY ok ASC, id DESC LIMIT 30")) {
      Map<String, Object> m = new LinkedHashMap<>(); boolean dry = Db.i(c.get("dry_run")) == 1, good = Db.i(c.get("ok")) == 1; Integer st = c.get("status") == null ? null : Db.i(c.get("status"));
      m.put("id", c.get("id")); m.put("at", c.get("at")); m.put("ok", good); m.put("observed", dry); m.put("op", Db.str(c.get("op"))); m.put("projectId", c.get("project_id")); m.put("status", st); m.put("durationMs", c.get("duration_ms"));
      m.put("text", Humanize.call(Db.str(c.get("op")), st, good, dry, Db.str(c.get("note")))); m.put("path", c.get("path")); recent.add(m);
    }
    o.put("recentCalls", recent);
    return o;
  }
}
