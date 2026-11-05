package com.x7x24.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.CallLog;
import com.x7x24.adapters.GitHubClient;
import com.x7x24.adapters.GitHubClient.GitHubException;
import com.x7x24.adapters.Redactor;
import com.x7x24.engine.X7Props;
import com.x7x24.gov.AuditLog;
import com.x7x24.util.Json;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * GitHub 登录：设备码流程（device flow，只需要公开的 Client ID，不需要 client secret）与粘贴令牌（PAT）。
 * 设备码会话保存在内存里（带过期）；令牌只在服务端经过，保存时加密，永不返回前端。
 */
@Component
public class GitHubAuth {
  public record Validated(String login, String avatarUrl, String scopes, String summary, String expiresAt) {}
  static class Session { String ws; String deviceCode, userCode, verificationUri; long expiresAt, nextPollAt; int interval; }
  public static final String MIN_PERMS = "经典令牌：勾选 repo 与 workflow。细粒度令牌：选择目标仓库，Contents / Pull requests / Issues / Actions / Workflows 设为读写，Metadata 只读。";

  private final X7Props props; private final CredentialProvider creds; private final CallLog calls; private final AuditLog audit; private final RuntimeSettings settings;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
  private final SecureRandom rng = new SecureRandom();
  LongSupplier now = System::currentTimeMillis;

  public GitHubAuth(X7Props props, CredentialProvider creds, CallLog calls, AuditLog audit, RuntimeSettings settings) { this.props = props; this.creds = creds; this.calls = calls; this.audit = audit; this.settings = settings; }
  /** 测试用：替换时间源 */
  void clock(LongSupplier c) { this.now = c; }
  public int pendingSessions() { purge(); return sessions.size(); }
  private void purge() { long t = now.getAsLong(); sessions.values().removeIf(s -> s.expiresAt < t - 60_000); }

  // ── 设备码
  private JsonNode postForm(String path, Map<String, String> form) throws Exception {
    StringBuilder b = new StringBuilder(); form.forEach((k, v) -> b.append(b.length() > 0 ? "&" : "").append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
    var req = HttpRequest.newBuilder(URI.create(props.github.webUrl.replaceAll("/+$", "") + path)).timeout(Duration.ofSeconds(20)).header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "7x24-agent").POST(HttpRequest.BodyPublishers.ofString(b.toString())).build();
    var res = http.send(req, HttpResponse.BodyHandlers.ofString());
    try { return Json.M.readTree(res.body()); } catch (Exception e) { throw new IllegalStateException("GitHub 返回了无法解析的内容（HTTP " + res.statusCode() + "）"); }
  }

  public Map<String, Object> startDevice() {
    purge();
    String cid = props.github.oauthClientId;
    if (cid == null || cid.isBlank()) return Map.of("ok", false, "error", "not_configured", "message", "服务器没有配置 GITHUB_OAUTH_CLIENT_ID，请改用「粘贴令牌」。");
    try {
      JsonNode r = postForm("/login/device/code", Map.of("client_id", cid));
      if (r.has("error")) return Map.of("ok", false, "error", r.path("error").asText(), "message", Humanize.deviceError(r.path("error").asText(), r.path("error_description").asText()), "fallback", "paste_token");
      Session s = new Session(); s.deviceCode = r.path("device_code").asText(); s.userCode = r.path("user_code").asText(); s.verificationUri = r.path("verification_uri").asText("https://github.com/login/device");
      s.interval = Math.max(1, r.path("interval").asInt(5)); s.expiresAt = now.getAsLong() + r.path("expires_in").asLong(900) * 1000; s.nextPollAt = now.getAsLong() + s.interval * 1000L;
      s.ws = com.x7x24.tenant.Tenant.scope(); String id = newId(); sessions.put(id, s);
      Map<String, Object> o = new LinkedHashMap<>(); o.put("ok", true); o.put("sessionId", id); o.put("userCode", s.userCode); o.put("verificationUri", s.verificationUri); o.put("expiresIn", r.path("expires_in").asInt(900)); o.put("interval", s.interval);
      audit.sys("INTEGRATION_DEVICE_STARTED", "operator", Map.of("provider", "github"), null);
      return o;
    } catch (Exception e) { return Map.of("ok", false, "error", "network", "message", "无法连接 GitHub：" + Redactor.redact(e.getMessage()), "fallback", "paste_token"); }
  }
  private String newId() { byte[] b = new byte[16]; rng.nextBytes(b); return HexFormat.of().formatHex(b); }

  /** 前端轮询：status = pending | connected | expired | denied | error。后端按 interval 节流，不会比 GitHub 允许的更频繁。 */
  public Map<String, Object> pollDevice(String sessionId) {
    Session s = sessions.get(sessionId); if (s != null && !Objects.equals(s.ws, com.x7x24.tenant.Tenant.scope())) s = null; // 设备码会话绑定发起它的工作区
    if (s == null) return Map.of("status", "expired", "message", "授权会话不存在或已过期，请重新获取验证码");
    long t = now.getAsLong();
    if (t >= s.expiresAt) { sessions.remove(sessionId); return Map.of("status", "expired", "message", Humanize.deviceError("expired_token", null)); }
    if (t < s.nextPollAt) return pending(s, "等待你在 GitHub 上输入验证码并授权…");
    s.nextPollAt = t + s.interval * 1000L;
    try {
      JsonNode r = postForm("/login/oauth/access_token", Map.of("client_id", props.github.oauthClientId, "device_code", s.deviceCode, "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
      String err = r.path("error").asText("");
      switch (err) {
        case "authorization_pending": return pending(s, "等待你在 GitHub 上输入验证码并授权…");
        case "slow_down": s.interval = Math.max(s.interval + 5, r.path("interval").asInt(0)); s.nextPollAt = now.getAsLong() + s.interval * 1000L; return pending(s, "GitHub 要求放慢轮询，已自动降低频率…");
        case "expired_token": sessions.remove(sessionId); return Map.of("status", "expired", "message", Humanize.deviceError(err, null));
        case "access_denied": sessions.remove(sessionId); audit.sys("INTEGRATION_DEVICE_DENIED", "operator", Map.of("provider", "github"), null); return Map.of("status", "denied", "message", Humanize.deviceError(err, null));
        case "": break;
        default: sessions.remove(sessionId); return Map.of("status", "error", "error", err, "message", Humanize.deviceError(err, r.path("error_description").asText()));
      }
      String token = r.path("access_token").asText("");
      if (token.isBlank()) { sessions.remove(sessionId); return Map.of("status", "error", "message", "GitHub 没有返回令牌，请重试"); }
      Redactor.register(token);
      String exp = r.has("expires_in") ? java.time.Instant.ofEpochMilli(now.getAsLong() + r.path("expires_in").asLong() * 1000).toString() : null;
      Validated v = validate(token);
      saveConnected("oauth", token, v, exp); sessions.remove(sessionId);
      creds.saveRefresh(r);
      return Map.of("status", "connected", "login", v.login(), "avatarUrl", String.valueOf(v.avatarUrl()));
    } catch (GitHubException e) { sessions.remove(sessionId); return Map.of("status", "error", "message", "授权成功，但校验令牌失败：" + Humanize.reason(e.status, e.getMessage())); }
    catch (Exception e) { return Map.of("status", "pending", "message", "暂时连不上 GitHub，稍后自动重试", "interval", s.interval); }
  }
  private Map<String, Object> pending(Session s, String msg) { return Map.of("status", "pending", "interval", s.interval, "message", msg, "remainingSeconds", Math.max(0, (s.expiresAt - now.getAsLong()) / 1000)); }

  // ── 令牌校验 / 保存
  GitHubClient clientFor(String token) {
    return new GitHubClient(props.github.apiUrl, () -> token, calls, h -> { }, 1, 5000, null, null);
  }
  /** GET /user 校验令牌；失败抛 GitHubException（消息已脱敏） */
  public Validated validate(String token) {
    Redactor.register(token);
    var res = clientFor(token).get("/user", "user.get", "git");
    String scopes = res.headers().firstValue("x-oauth-scopes").orElse("").trim();
    String exp = res.headers().firstValue("github-authentication-token-expiration").orElse(null);
    String summary = scopes.isBlank() ? "细粒度令牌 / App 令牌：具体权限取决于创建时的选择，将在「仓库体检」中逐项验证" : "经典令牌权限范围：" + scopes + (Arrays.asList(scopes.split("[ ,]+")).contains("repo") ? "" : "（缺少 repo）") + (Arrays.asList(scopes.split("[ ,]+")).contains("workflow") ? "" : "（缺少 workflow，无法触发 CI）");
    return new Validated(res.data().path("login").asText(), res.data().path("avatar_url").asText(null), scopes, summary, exp);
  }
  public void saveConnected(String source, String token, Validated v, String expiresAt) {
    creds.save(source, token, v.login(), v.avatarUrl(), v.scopes(), v.summary(), expiresAt != null ? expiresAt : v.expiresAt());
    if (!"real".equalsIgnoreCase(settings.adapterMode())) settings.setAdapterMode("real"); // 连接后进入观察模式（默认 dry-run 仍开启）
    audit.sys("INTEGRATION_CONNECTED", "operator", Map.of("provider", "github", "source", source, "login", v.login()), null);
  }

  /** Health is independent from token presence and from the configured write mode. */
  public Map<String, Object> connection(String userId, boolean force) {
    boolean personalGrant = !creds.hasToken() && creds.hasUser(userId);
    if (!creds.hasToken() && !personalGrant) return connectionView("disconnected");
    String state = creds.connectionState();
    var info = personalGrant ? creds.userInfo(userId) : creds.info();
    boolean due = GitHubTokenVault.timestamp(info.expiresAt()) <= now.getAsLong() + 60_000;
    if (personalGrant || force || due || "unchecked".equals(state) || now.getAsLong() - creds.checkedAt() >= 60_000) {
      try {
        String token = personalGrant ? creds.userToken(userId) : creds.token();
        if (token.isBlank()) return connectionView("disconnected");
        validate(token);
        // Installation/approval problems are not fixed by a successful /user request.
        if (!List.of("repositories_required", "approval_pending", "permissions_required").contains(state)) state = "ready";
        creds.state(state);
      } catch (GitHubException e) {
        state = e.status == 401 ? "reauth_required" : e.status == 403 ? "permissions_required" : "network_error";
        creds.state(state);
      } catch (IllegalStateException e) { state = "reauth_required"; creds.state(state); }
    }
    return connectionView(state);
  }
  private Map<String, Object> connectionView(String state) {
    String message = switch (state) {
      case "ready" -> "GitHub 授权正常";
      case "reauth_required" -> "GitHub 授权需要更新，请重新授权";
      case "repositories_required" -> "尚未获得目标仓库的访问权限，请选择可访问的仓库";
      case "approval_pending" -> "仓库授权正在等待组织管理员批准";
      case "permissions_required" -> "GitHub 仓库权限需要更新";
      case "network_error" -> "暂时无法连接 GitHub，请稍后重试";
      default -> "登录 GitHub 后，选择要交付的仓库";
    };
    return Map.of("state", state, "message", message, "checkedAt", com.x7x24.util.Clk.iso(now.getAsLong()));
  }

  private boolean installationAvailable(long installationId) {
    if (db == null) return true;
    var existing = com.x7x24.tenant.Tenant.unscoped(() -> db.row("SELECT workspace_id FROM github_installation WHERE installation_id=?", installationId));
    return existing == null || CredentialProvider.ws().equals(existing.get("workspace_id"));
  }

  /** Verify the exact repository against the acting user's grant before delegating automation. */
  public void bindRepository(String userId, String fullName, long installationId) {
    if (fullName == null || !fullName.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("仓库格式应为 owner/repo");
    String token = userId != null && creds.hasUser(userId) ? creds.userToken(userId) : creds.token();
    var c = clientFor(token);
    var installations = c.get("/user/installations?per_page=100", "user.installations", "git").data().path("installations");
    JsonNode selected = null;
    for (var installation : installations) if (installation.path("id").asLong() == installationId) selected = installation;
    if (selected == null || selected.hasNonNull("suspended_at")) throw new IllegalArgumentException("该仓库授权不可用，请重新选择仓库");
    JsonNode repository = null;
    for (int page = 1; page <= 100; page++) {
      var result = c.get("/user/installations/" + installationId + "/repositories?per_page=100&page=" + page, "user.repos", "git").data().path("repositories");
      for (var candidate : result) if (fullName.equalsIgnoreCase(candidate.path("full_name").asText())) repository = candidate;
      if (repository != null || result.size() < 100) break;
    }
    if (repository == null || repository.path("id").asLong() <= 0) throw new IllegalArgumentException("当前账号没有这个仓库的授权，请重新选择仓库");
    if (!repository.path("permissions").path("push").asBoolean()) throw new IllegalArgumentException("当前账号对这个仓库只有读权限，不能授权后台交付");
    if (!claimInstallation(installationId, selected.path("account").path("login").asText(null))) throw new IllegalArgumentException("该 GitHub 安装已连接到其他工作区");
    final long repoId = repository.path("id").asLong();
    db.tx(() -> {
      var row = db.row("SELECT repositories FROM github_installation WHERE installation_id=? AND workspace_id=? FOR UPDATE", installationId, CredentialProvider.ws());
      if (row == null) throw new IllegalArgumentException("仓库授权归属已变化，请重新选择仓库");
      var names = (com.fasterxml.jackson.databind.node.ObjectNode) Json.tree(DbString(row.get("repositories")));
      names.put(fullName.toLowerCase(Locale.ROOT), repoId);
      db.update("UPDATE github_installation SET repositories=?,verified_by_user=? WHERE installation_id=? AND workspace_id=?", names.toString(), userId == null ? "github-token" : userId, installationId, CredentialProvider.ws());
    });
    if ("none".equals(creds.info().source()) && userId != null) { creds.attachUser(userId); if (!"real".equalsIgnoreCase(settings.adapterMode())) settings.setAdapterMode("real"); }
    creds.state("ready");
  }
  private static String DbString(Object value) { return value == null ? "{}" : value.toString(); }

  @org.springframework.beans.factory.annotation.Autowired(required = false) com.x7x24.db.Db db;
  /**
   * GitHub App 安装与工作区一对一；仅供显式绑定及兼容测试使用，列表查询不会调用它。
   */
  public boolean claimInstallationForTest(long id, String account) { return claimInstallation(id, account); }
  boolean claimInstallation(long installationId, String account) {
    if (db == null) return true; String ws = com.x7x24.tenant.Tenant.scope(); if (ws == null) ws = com.x7x24.tenant.Tenant.LEGACY;
    var row = com.x7x24.tenant.Tenant.unscoped(() -> db.row("SELECT workspace_id FROM github_installation WHERE installation_id=?", installationId));
    if (row != null) return ws.equals(row.get("workspace_id"));
    try { db.update("INSERT INTO github_installation(installation_id,workspace_id,account_login,verified_by_user,created_at) VALUES(?,?,?,?,?)", installationId, ws, account, "github-token", com.x7x24.util.Clk.iso(System.currentTimeMillis()));
      audit.sys("GITHUB_INSTALLATION_BOUND", "integration", Map.of("installation", installationId, "account", String.valueOf(account), "workspace", ws), "gh-install:" + installationId); }
    catch (Exception race) { return ws.equals(com.x7x24.tenant.Tenant.unscoped(() -> db.row("SELECT workspace_id FROM github_installation WHERE installation_id=?", installationId)).get("workspace_id")); }
    return true;
  }

  // ── 仓库列表
  public Map<String, Object> repos(String q, int page, int size, Map<String, String> bound) {
    return repos(q, page, size, bound, null);
  }
  public Map<String, Object> repos(String q, int page, int size, Map<String, String> bound, String userId) {
    boolean personalGrant = !creds.hasToken() && creds.hasUser(userId);
    java.util.function.Supplier<String> supplier = personalGrant ? () -> creds.userToken(userId) : creds::token;
    String token = supplier.get(); if (token.isBlank()) throw new IllegalArgumentException("请先登录 GitHub");
    GitHubClient c = new GitHubClient(props.github.apiUrl, supplier, calls, h -> { }, 1, 5000, null, null).authorization(status -> { if (status == 403) creds.state("permissions_required"); else creds.observe(status); }, () -> !personalGrant && creds.refreshRejected(null));
    List<JsonNode> all = new ArrayList<>(); int skippedInstallations = 0; boolean needInstall = false, noRepoPerms = false; String via = "user";
    Map<String, Long> installationByRepo = new HashMap<>();
    if (personalGrant || "oauth".equals(creds.info().source())) {
      via = "installation";
      var ins = c.get("/user/installations?per_page=100", "user.installations", "git").data();
      var arr = ins.path("installations");
      if (arr.isEmpty()) needInstall = true;
      int claimedElsewhere = 0;
      for (var i : arr) {
        if (!installationAvailable(i.path("id").asLong()) || i.hasNonNull("suspended_at")) { claimedElsewhere++; continue; }
        var perms = i.path("permissions");
        if (!perms.has("contents") && !perms.has("metadata")) noRepoPerms = true;
        for (int pg = 1; pg <= 10; pg++) {
          var r = c.get("/user/installations/" + i.path("id").asLong() + "/repositories?per_page=100&page=" + pg, "user.repos", "git").data();
          var rs = r.path("repositories"); for (var x : rs) { all.add(x); installationByRepo.put(x.path("full_name").asText().toLowerCase(Locale.ROOT), i.path("id").asLong()); }
          if (rs.size() < 100) break;
        }
      }
      if (claimedElsewhere > 0) needInstall = needInstall || all.isEmpty();
      skippedInstallations = claimedElsewhere;
    } else {
      for (int pg = 1; pg <= 3; pg++) {
        var r = c.get("/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member&page=" + pg, "user.repos", "git").data();
        if (r == null || !r.isArray() || r.isEmpty()) break; for (var x : r) all.add(x); if (r.size() < 100) break;
      }
    }
    String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
    List<Map<String, Object>> items = new ArrayList<>();
    for (var x : all) {
      String full = x.path("full_name").asText(); if (!needle.isEmpty() && !full.toLowerCase(Locale.ROOT).contains(needle)) continue;
      Map<String, Object> m = new LinkedHashMap<>(); m.put("fullName", full); m.put("private", x.path("private").asBoolean()); m.put("defaultBranch", x.path("default_branch").asText("main")); m.put("description", x.path("description").asText(""));
      m.put("pushedAt", x.path("pushed_at").asText(null)); m.put("canPush", x.path("permissions").path("push").asBoolean()); m.put("boundProjectId", bound.get(full.toLowerCase(Locale.ROOT))); items.add(m);
      m.put("installationId", installationByRepo.get(full.toLowerCase(Locale.ROOT)));
    }
    int from = Math.max(0, (page - 1) * size), to = Math.min(items.size(), from + size);
    Map<String, Object> o = new LinkedHashMap<>(); o.put("items", from >= items.size() ? List.of() : items.subList(from, to)); o.put("total", items.size()); o.put("page", page); o.put("size", size); o.put("via", via);
    o.put("needInstall", needInstall); if (skippedInstallations > 0) o.put("installationsInOtherWorkspaces", skippedInstallations);
    if (needInstall || (all.isEmpty() && "installation".equals(via))) {
      if (!"approval_pending".equals(creds.connectionState())) creds.state("repositories_required");
      String slug = props.github.appSlug;
      o.put("installUrl", slug == null || slug.isBlank() ? null : "https://github.com/apps/" + slug + "/installations/new");
      if (noRepoPerms) {
        o.put("reason", "app_no_repo_permissions"); o.put("settingsUrl", slug == null || slug.isBlank() ? "https://github.com/settings/apps" : "https://github.com/settings/apps/" + slug + "/permissions");
        o.put("hint", "还没有可用的仓库：GitHub App 没有配置任何仓库权限，安装后也看不到仓库。请在 App 设置 → Permissions & events → Repository permissions 里把 Contents、Pull requests、Issues、Actions、Workflows 设为 Read and write（Metadata 会自动设为只读）并保存，然后到 github.com/settings/installations 接受新权限，并确认选择了要交付的仓库。");
      } else
      o.put("hint", "还没有可用的仓库：授权登录用的是 GitHub App，需要先在 GitHub 上安装该 App 并选择要授权的仓库。" + (slug == null || slug.isBlank() ? "请打开该 GitHub App 的页面点 Install（服务器可通过 GITHUB_APP_SLUG 配置安装链接）。" : ""));
    }
    else creds.state("ready");
    return o;
  }
}
