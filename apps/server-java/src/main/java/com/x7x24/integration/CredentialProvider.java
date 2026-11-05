package com.x7x24.integration;

import com.x7x24.adapters.Redactor;
import com.x7x24.db.Db;
import com.x7x24.engine.X7Props;
import com.x7x24.util.Clk;
import com.x7x24.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.GitHubClient.GitHubException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

/**
 * 统一的 GitHub 凭据来源（运行时可变，无需重启）。优先级：数据库中已连接的凭据（oauth / pat）> 环境变量 GITHUB_TOKEN（来源 env，界面不可删除，只可被覆盖）。
 * 令牌明文只在内存中短暂存在；对外只暴露 {@link #info()} 的元数据。
 */
@Component
public class CredentialProvider {
  public record Info(String source, String login, String avatarUrl, String scopes, String permissionsSummary, String createdAt, String expiresAt, boolean deletable) {
    public Map<String, Object> toMap() {
      Map<String, Object> m = new LinkedHashMap<>(); m.put("source", source); m.put("login", login); m.put("avatarUrl", avatarUrl); m.put("scopes", scopes);
      m.put("permissionsSummary", permissionsSummary); m.put("createdAt", createdAt); m.put("expiresAt", expiresAt); m.put("deletable", deletable); return m;
    }
  }
  public static final String ID = "github";
  private final Db db; private final Clk clk; private final X7Props props; private final CryptoBox box; private final GitHubTokenVault tokens;
  private record Connection(String state, long checkedAt) {}
  private final Map<String, Connection> states = new java.util.concurrent.ConcurrentHashMap<>();
  /** 当前工作区（系统上下文 = 遗留工作区，保持旧行为） */
  static String ws() { String w = com.x7x24.tenant.Tenant.storeScope(); return w == null ? com.x7x24.tenant.Tenant.LEGACY : w; }
  /** 遗留工作区沿用原来的行 id=github */
  static String rowId() { String w = ws(); return com.x7x24.tenant.Tenant.LEGACY.equals(w) ? ID : ID + ":" + w; }
  /** 环境变量 GITHUB_TOKEN 只给遗留工作区兜底（决策 4A） */
  boolean envAllowed() { return com.x7x24.tenant.Tenant.LEGACY.equals(ws()); }

  public CredentialProvider(Db db, Clk clk, X7Props props, @Value("${x7x24.secret-key:}") String secret, @Value("${x7x24.data-dir:./data}") String dataDir, @Value("${x7x24.auth.client-secret:}") String clientSecret) {
    this.db = db; this.clk = clk; this.props = props; this.box = new CryptoBox(secret, Path.of(dataDir));
    this.tokens = new GitHubTokenVault(db, box, props, clk, clientSecret);
    Redactor.register(props.github.token);
  }
  public CryptoBox box() { return box; }

  /** 当前生效令牌；没有则返回空字符串 */
  public String token() {
    var row = db.row("SELECT user_id FROM integration_credentials WHERE id=?", rowId());
    if (row != null) {
      try { return row.get("user_id") == null ? tokens.workspace(rowId(), false) : tokens.user(Db.str(row.get("user_id")), false); }
      catch (GitHubException e) { observe(e.status); throw e; }
    }
    return envAllowed() && props.github.token != null ? props.github.token : "";
  }
  /** Configuration check only. Health checks and rotation must not break unrelated pages. */
  public boolean hasToken() { return !"none".equals(info().source()) || installationEnabled() && !installations().isEmpty(); }
  public boolean installationEnabled() { return tokens.installationEnabled(); }
  public String userToken(String userId) { return userId == null ? "" : tokens.user(userId, false); }
  public boolean hasUser(String userId) { return userId != null && db.count("SELECT COUNT(*) FROM github_user_authorization WHERE user_id=?", userId) > 0; }
  public Info userInfo(String userId) {
    var row = db.row("SELECT u.login,u.avatar_url,a.expires_at,a.updated_at FROM app_user u JOIN github_user_authorization a ON a.user_id=u.id WHERE u.id=?", userId);
    return row == null ? new Info("none", null, null, "", "", null, null, false) : new Info("oauth", Db.str(row.get("login")), Db.str(row.get("avatar_url")), "", "使用登录时的 GitHub 授权", Db.str(row.get("updated_at")), Db.str(row.get("expires_at")), false);
  }
  public void saveUser(String userId, JsonNode grant) { tokens.saveUser(userId, grant); states.clear(); }
  /** Explicit workspace attachment, or automatic attachment to the user's own personal workspace only. */
  public synchronized void attachUser(String userId) {
    if (!hasUser(userId)) throw new IllegalArgumentException("请先更新 GitHub 登录授权");
    var info = userInfo(userId);
    db.tx(() -> {
      save("oauth", "", info.login(), info.avatarUrl(), "", "使用登录时的 GitHub 授权，自动续期", info.expiresAt());
      db.update("UPDATE integration_credentials SET user_id=? WHERE id=?", userId, rowId());
    });
  }
  public void attachPersonal(String userId, String workspaceId) {
    com.x7x24.tenant.Tenant.run(workspaceId, () -> {
      if (db.count("SELECT COUNT(*) FROM workspace WHERE id=? AND kind='personal' AND owner_user_id=?", workspaceId, userId) != 1) return;
      var existing = info();
      if ("none".equals(existing.source())) attachUser(userId);
      else if ("oauth".equals(existing.source()) && Objects.equals(existing.login(), userInfo(userId).login()) && db.count("SELECT COUNT(*) FROM integration_credentials WHERE id=? AND user_id=?", rowId(), userId) == 0) attachUser(userId);
    });
  }
  public void saveRefresh(JsonNode grant) {
    db.update("UPDATE integration_credentials SET refresh_ciphertext=?,refresh_expires_at=? WHERE id=?", tokens.encrypted(grant, "refresh_token"), GitHubTokenVault.expiry(grant, "refresh_token_expires_in", clk.now()), rowId());
  }
  public String connectionState() {
    var row = db.row("SELECT connection_state,checked_at FROM integration_credentials WHERE id=?", rowId());
    if (row != null) return Db.str(row.get("connection_state"));
    var state = states.get(ws()); return state == null ? "unchecked" : state.state;
  }
  public long checkedAt() {
    var row = db.row("SELECT checked_at FROM integration_credentials WHERE id=?", rowId());
    if (row != null && row.get("checked_at") != null) return GitHubTokenVault.timestamp(row.get("checked_at"));
    var state = states.get(ws()); return state == null ? 0 : state.checkedAt;
  }
  public void state(String state) { states.put(ws(), new Connection(state, clk.now())); db.update("UPDATE integration_credentials SET connection_state=?,checked_at=? WHERE id=?", state, clk.iso(), rowId()); }
  public void observe(int status) {
    if (status == 401) state("reauth_required");
    else if (status == 0 || status >= 500) state("network_error");
    // A successful unrelated call must not hide an earlier authorization failure.
  }
  public void observeRepository(String repo, int status) {
    if (status == 401 && installationEnabled() && installationFor(repo) != null) state("permissions_required");
    else observe(status);
  }
  public boolean refreshRejected(String repo) {
    var installation = installationFor(repo);
    if (installation != null && installationEnabled()) { tokens.invalidateInstallation(installationKey(installation, repo)); state("unchecked"); return true; }
    var row = db.row("SELECT user_id,refresh_ciphertext FROM integration_credentials WHERE id=?", rowId());
    if (row == null) return false;
    if (row.get("user_id") != null) tokens.user(Db.str(row.get("user_id")), true);
    else if (row.get("refresh_ciphertext") != null) tokens.workspace(rowId(), true);
    else return false;
    state("unchecked"); return true;
  }
  public List<Map<String, Object>> installations() { return db.rows("SELECT * FROM github_installation WHERE workspace_id=?", ws()); }
  private Map<String, Object> installationFor(String repo) {
    if (repo == null) return null;
    for (var installation : installations()) if (Json.tree(Db.str(installation.get("repositories"))).has(repo.toLowerCase(Locale.ROOT))) return installation;
    return null;
  }
  private String installationKey(Map<String, Object> installation, String repo) { return ws() + ":" + installation.get("installation_id") + ":" + repo.toLowerCase(Locale.ROOT); }
  public String tokenForRepo(String repo) {
    var installation = installationFor(repo);
    if (installation != null && installationEnabled()) {
      long repositoryId = Json.tree(Db.str(installation.get("repositories"))).path(repo.toLowerCase(Locale.ROOT)).asLong();
      try { return tokens.installation(installationKey(installation, repo), Db.l(installation.get("installation_id")), repositoryId); }
      catch (GitHubException e) {
        if (e.status == 401 || e.status == 403 || e.status == 404) { state("permissions_required"); throw new GitHubException(403, "GitHub 仓库授权需要更新", "/app/installations"); }
        observe(e.status); throw e;
      }
    }
    return token();
  }

  public Info info() {
    var r = db.row("SELECT * FROM integration_credentials WHERE id=?", rowId());
    if (r != null) return new Info(Db.str(r.get("source")), Db.str(r.get("github_login")), Db.str(r.get("avatar_url")), Db.str(r.get("scopes")), Db.str(r.get("permissions_summary")), Db.str(r.get("created_at")), Db.str(r.get("expires_at")), true);
    if (envAllowed() && props.github.token != null && !props.github.token.isBlank()) return new Info("env", null, null, "", "由服务器环境变量 GITHUB_TOKEN 提供，权限在体检中逐项验证", null, null, false);
    return new Info("none", null, null, "", "", null, null, false);
  }

  public synchronized void save(String source, String token, String login, String avatar, String scopes, String perms, String expiresAt) {
    Redactor.register(token);
    String enc = box.encrypt(token);
    db.tx(() -> {
      db.update("DELETE FROM integration_credentials WHERE id=?", rowId());
      db.update("INSERT INTO integration_credentials (id,provider,source,github_login,avatar_url,scopes,permissions_summary,ciphertext,created_at,expires_at,connection_state,checked_at) VALUES (?,?,?,?,?,?,?,?,?,?,'ready',?)", rowId(), "github", source, login, avatar, scopes == null ? "" : scopes, perms == null ? "" : perms, enc, clk.iso(), expiresAt, clk.iso());
    });
    states.remove(ws());
  }
  public synchronized boolean delete() {
    int n = db.update("DELETE FROM integration_credentials WHERE id=?", rowId()); db.update("DELETE FROM github_installation WHERE workspace_id=?", ws()); states.remove(ws()); return n > 0;
  }
}
