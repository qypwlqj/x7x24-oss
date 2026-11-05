package com.x7x24.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.db.Db;
import com.x7x24.engine.X7Props;
import com.x7x24.gov.AuditLog;
import com.x7x24.integration.CredentialProvider;
import com.x7x24.util.Clk;
import com.x7x24.util.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 账号体系：GitHub OAuth 网页授权（authorization code）+ 设备码兜底。
 * 会话 = 无状态签名令牌（HMAC-SHA256，密钥同 X7_SECRET_KEY），既可放 HttpOnly Cookie，也可作 Bearer。
 * GitHub 授权加密保存到个人授权库；工作区只保存引用，登录不覆盖团队连接。
 */
@Component
public class AuthService {
  public record User(String id, long githubId, String login, String name, String avatarUrl) {}
  public static class Denied extends RuntimeException { public final String code; public Denied(String code, String msg) { super(msg); this.code = code; } }
  static class DeviceSession { String deviceCode, expectedUser; long expiresAt, nextPollAt; int interval; }

  public final boolean required; public final String clientId; private final String clientSecret; public final String publicUrl; public final List<String> allowedOrigins; private final Set<String> allowedLogins; private final long sessionMs;
  private final X7Props props; private final Db db; private final CredentialProvider creds; private final AuditLog audit;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
  private final Map<String, DeviceSession> devices = new ConcurrentHashMap<>();
  private final SecureRandom rng = new SecureRandom();
  private final com.x7x24.tenant.WorkspaceService workspaces;
  private final com.x7x24.integration.RuntimeSettings settings;
  LongSupplier now = System::currentTimeMillis;

  public AuthService(X7Props props, Db db, CredentialProvider creds, AuditLog audit,
                     @Value("${x7x24.auth.required:true}") boolean required, @Value("${x7x24.auth.client-secret:}") String clientSecret,
                     @Value("${x7x24.auth.allowed-logins:}") String allowedLogins, @Value("${x7x24.auth.allowed-origins:}") String allowedOrigins,
                     @Value("${x7x24.auth.public-url:}") String publicUrl, @Value("${x7x24.auth.session-hours:168}") long sessionHours,
                     com.x7x24.tenant.WorkspaceService workspaces, com.x7x24.integration.RuntimeSettings settings) {
    this.workspaces = workspaces; this.settings = settings;
    this.props = props; this.db = db; this.creds = creds; this.audit = audit; this.required = required;
    this.clientId = props.github.oauthClientId == null ? "" : props.github.oauthClientId; this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    this.allowedLogins = new HashSet<>(); for (String s : allowedLogins.split("[,\\s]+")) if (!s.isBlank()) this.allowedLogins.add(s.trim().toLowerCase(Locale.ROOT));
    this.allowedOrigins = Arrays.stream(allowedOrigins.split("[,\\s]+")).map(String::trim).filter(s -> !s.isBlank()).map(s -> s.replaceAll("/+$", "")).toList();
    this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", ""); this.sessionMs = sessionHours * 3_600_000L;
  }
  public boolean webFlowEnabled() { return !clientId.isBlank() && !clientSecret.isBlank(); }
  public boolean allowlistEnabled() { return !allowedLogins.isEmpty(); }

  // ── 签名令牌：base64url(json).base64url(hmac)
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  String sign(Map<String, Object> claims) { String body = B64.encodeToString(Json.str(claims).getBytes(StandardCharsets.UTF_8)); return body + "." + B64.encodeToString(creds.box().mac(body)); }
  JsonNode verify(String token, String typ) {
    if (token == null) return null; int i = token.indexOf('.'); if (i <= 0) return null;
    String body = token.substring(0, i);
    byte[] sig; try { sig = Base64.getUrlDecoder().decode(token.substring(i + 1)); } catch (IllegalArgumentException e) { return null; }
    if (!MessageDigest.isEqual(sig, creds.box().mac(body))) return null;
    JsonNode c; try { c = Json.tree(new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8)); } catch (Exception e) { return null; }
    if (!typ.equals(c.path("typ").asText()) || c.path("exp").asLong() < now.getAsLong()) return null;
    return c;
  }
  public String issueSession(User u) { return sign(Map.of("typ", "session", "uid", u.id(), "login", u.login(), "exp", now.getAsLong() + sessionMs)); }
  public long sessionSeconds() { return sessionMs / 1000; }
  /** 校验会话；用户被移出白名单后旧会话立即失效 */
  public User userFromToken(String token) {
    JsonNode c = verify(token, "session"); if (c == null) return null;
    var r = db.row("SELECT * FROM app_user WHERE id=?", c.path("uid").asText()); if (r == null) return null;
    User u = new User((String) r.get("id"), ((Number) r.get("github_id")).longValue(), (String) r.get("login"), (String) r.get("name"), (String) r.get("avatar_url"));
    return loginAllowed(u.login()) ? u : null;
  }
  // ── SSE 一次性票据：EventSource 不能带请求头，URL 里只放 60 秒有效、只能用一次的票据，不放长期会话令牌
  public static final long TICKET_MS = 60_000;
  private final Map<String, Long> usedTickets = new ConcurrentHashMap<>();
  public String issueSseTicket(User u) { return sign(Map.of("typ", "sse", "uid", u.id(), "n", Long.toHexString(rng.nextLong()) + Long.toHexString(rng.nextLong()), "exp", now.getAsLong() + TICKET_MS)); }
  /** 校验并消费票据；过期、伪造或已用过都返回 null */
  public User userFromTicket(String ticket) {
    JsonNode c = verify(ticket, "sse"); if (c == null) return null;
    long t = now.getAsLong(); usedTickets.values().removeIf(exp -> exp < t);
    if (usedTickets.putIfAbsent(c.path("n").asText(), c.path("exp").asLong()) != null) return null;
    var r = db.row("SELECT * FROM app_user WHERE id=?", c.path("uid").asText()); if (r == null) return null;
    User u = new User((String) r.get("id"), ((Number) r.get("github_id")).longValue(), (String) r.get("login"), (String) r.get("name"), (String) r.get("avatar_url"));
    return loginAllowed(u.login()) ? u : null;
  }
  public boolean loginAllowed(String login) { return allowedLogins.isEmpty() || allowedLogins.contains(login.toLowerCase(Locale.ROOT)); }

  // ── 回跳地址：只允许白名单前端源（或同源相对路径），防开放重定向
  public String safeRedirect(String r) {
    if (r == null || r.isBlank()) return allowedOrigins.isEmpty() ? "/" : allowedOrigins.get(0) + "/";
    if (r.startsWith("/") && !r.startsWith("//")) return r;
    try {
      URI u = URI.create(r); String origin = u.getScheme() + "://" + u.getRawAuthority();
      if ((u.getScheme().equals("https") || u.getScheme().equals("http")) && originAllowed(origin)) return r;
    } catch (Exception ignore) { }
    throw new Denied("bad_redirect", "回跳地址不在 X7_ALLOWED_ORIGINS 白名单内");
  }
  /** 支持通配：https://*.vercel.app、https://x7x24-*-team.vercel.app（* 只匹配一段子域字符，不跨 / 和 :） */
  public boolean originAllowed(String origin) {
    for (String p : allowedOrigins) {
      if (p.equals(origin)) return true;
      if (p.contains("*") && origin.matches(java.util.Arrays.stream(p.split("\\*", -1)).map(java.util.regex.Pattern::quote).collect(java.util.stream.Collectors.joining("[a-zA-Z0-9-]+")))) return true;
    }
    return false;
  }
  public String callbackUrl(String requestBase) { return (publicUrl.isBlank() ? requestBase : publicUrl) + "/api/auth/callback"; }
  public String callbackRedirect(String state) { var claims = verify(state, "state"); return claims == null ? safeRedirect(null) : safeRedirect(claims.path("r").asText()); }
  public String authorizeUrl(String redirect, String requestBase) {
    return authorizeUrl(redirect, requestBase, null);
  }
  public String authorizeUrl(String redirect, String requestBase, String expectedUser) {
    if (!webFlowEnabled()) throw new Denied("web_flow_disabled", "服务器没有配置 GITHUB_OAUTH_CLIENT_SECRET，请用设备码登录");
    String state = sign(Map.of("typ", "state", "r", safeRedirect(redirect), "uid", expectedUser == null ? "" : expectedUser, "n", Long.toHexString(rng.nextLong()), "exp", now.getAsLong() + 600_000));
    return props.github.webUrl.replaceAll("/+$", "") + "/login/oauth/authorize?client_id=" + enc(clientId) + "&redirect_uri=" + enc(callbackUrl(requestBase)) + "&state=" + enc(state) + "&allow_signup=false";
  }
  /** 返回 [回跳地址, 会话令牌] */
  public String[] callback(String code, String state, String requestBase) {
    JsonNode s = verify(state, "state"); if (s == null) throw new Denied("bad_state", "登录请求已过期或被篡改，请重新登录");
    String redirect = s.path("r").asText("/");
    if (code == null || code.isBlank()) throw new Denied("no_code", "GitHub 没有返回授权码");
    JsonNode t = postForm("/login/oauth/access_token", Map.of("client_id", clientId, "client_secret", clientSecret, "code", code, "redirect_uri", callbackUrl(requestBase)));
    if (t.has("error")) throw new Denied(t.path("error").asText(), "GitHub 拒绝了授权：" + t.path("error_description").asText(t.path("error").asText()));
    User u = finish(t, s.path("uid").asText(""));
    return new String[]{redirect, issueSession(u)};
  }

  // ── 设备码兜底（无 client secret 时也可用；需要在 GitHub App 里开启 Device Flow）
  public Map<String, Object> deviceStart() {
    return deviceStart(null);
  }
  public Map<String, Object> deviceStart(String expectedUser) {
    if (clientId.isBlank()) throw new Denied("not_configured", "服务器没有配置 GITHUB_OAUTH_CLIENT_ID");
    devices.values().removeIf(d -> d.expiresAt < now.getAsLong());
    JsonNode r = postForm("/login/device/code", Map.of("client_id", clientId));
    if (r.has("error")) throw new Denied(r.path("error").asText(), r.path("error").asText().equals("device_flow_disabled") ? "GitHub App 未开启 Device Flow：请到 App 设置 General 勾选 Enable Device Flow，或由管理员配置网页授权" : "GitHub 拒绝了设备码请求：" + r.path("error_description").asText(r.path("error").asText()));
    DeviceSession d = new DeviceSession(); d.deviceCode = r.path("device_code").asText(); d.interval = Math.max(5, r.path("interval").asInt(5));
    d.expectedUser = expectedUser;
    d.expiresAt = now.getAsLong() + r.path("expires_in").asLong(900) * 1000; d.nextPollAt = 0;
    String sid = Long.toHexString(rng.nextLong()) + Long.toHexString(rng.nextLong()); devices.put(sid, d);
    return Map.of("sessionId", sid, "userCode", r.path("user_code").asText(), "verificationUri", r.path("verification_uri").asText("https://github.com/login/device"), "interval", d.interval, "expiresIn", r.path("expires_in").asLong(900));
  }
  public Map<String, Object> devicePoll(String sid) {
    DeviceSession d = sid == null ? null : devices.get(sid);
    if (d == null || d.expiresAt < now.getAsLong()) { if (sid != null) devices.remove(sid); return Map.of("status", "expired"); }
    if (now.getAsLong() < d.nextPollAt) return Map.of("status", "pending", "interval", d.interval);
    d.nextPollAt = now.getAsLong() + d.interval * 1000L;
    JsonNode r = postForm("/login/oauth/access_token", Map.of("client_id", clientId, "device_code", d.deviceCode, "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
    String err = r.path("error").asText("");
    if (err.equals("authorization_pending")) return Map.of("status", "pending", "interval", d.interval);
    if (err.equals("slow_down")) { d.interval += 5; return Map.of("status", "pending", "interval", d.interval); }
    devices.remove(sid);
    if (err.equals("expired_token")) return Map.of("status", "expired");
    if (err.equals("access_denied")) return Map.of("status", "denied");
    if (!err.isEmpty()) return Map.of("status", "error", "message", r.path("error_description").asText(err));
    User u = finish(r, d.expectedUser);
    return Map.of("status", "ok", "token", issueSession(u), "user", view(u));
  }

  /** Both login paths retain the same grant, after identity and allowlist verification. */
  private User finish(JsonNode grant, String expectedUser) {
    String accessToken = grant.path("access_token").asText("");
    if (accessToken == null || accessToken.isBlank()) throw new Denied("no_token", "GitHub 没有返回访问令牌");
    com.x7x24.adapters.Redactor.register(accessToken);
    JsonNode me;
    try {
      var req = HttpRequest.newBuilder(URI.create(props.github.apiUrl.replaceAll("/+$", "") + "/user")).timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json").header("Authorization", "Bearer " + accessToken).header("User-Agent", "7x24-agent").GET().build();
      var res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) throw new Denied("user_failed", "读取 GitHub 用户信息失败（HTTP " + res.statusCode() + "）");
      me = Json.tree(res.body());
    } catch (Denied e) { throw e; } catch (Exception e) { throw new Denied("network", "连接 GitHub 失败：" + e.getClass().getSimpleName()); }
    String login = me.path("login").asText(); long gid = me.path("id").asLong();
    if (expectedUser != null && !expectedUser.isBlank() && db.count("SELECT COUNT(*) FROM app_user WHERE id=? AND github_id=?", expectedUser, gid) != 1) throw new Denied("account_mismatch", "请使用当前登录的 GitHub 账号更新授权");
    if (login.isBlank()) throw new Denied("user_failed", "GitHub 没有返回用户名");
    if (!loginAllowed(login)) { audit.sys("LOGIN_DENIED", "auth", Map.of("login", login), "login-denied:" + login + ":" + now.getAsLong()); throw new Denied("not_allowed", "账号 " + login + " 不在允许登录的名单里（X7_ALLOWED_GITHUB_LOGINS）"); }
    String iso = Clk.iso(now.getAsLong());
    var row = db.row("SELECT id FROM app_user WHERE github_id=?", gid); String id;
    if (row == null) { id = "u_" + Long.toHexString(rng.nextLong()); db.update("INSERT INTO app_user(id, github_id, login, name, avatar_url, created_at, last_login_at) VALUES (?,?,?,?,?,?,?)", id, gid, login, me.path("name").asText(null), me.path("avatar_url").asText(null), iso, iso); }
    else { id = (String) row.get("id"); db.update("UPDATE app_user SET login=?, name=?, avatar_url=?, last_login_at=? WHERE id=?", login, me.path("name").asText(null), me.path("avatar_url").asText(null), iso, id); }
    creds.saveUser(id, grant);
    for (var workspace : workspaces.ensure(id, login)) {
      if ("personal".equals(workspace.kind()) && "owner".equals(workspace.role())) {
        creds.attachPersonal(id, workspace.id());
        com.x7x24.tenant.Tenant.run(workspace.id(), () -> { if (creds.hasToken() && !"real".equalsIgnoreCase(settings.adapterMode())) { settings.setDryRun(true); settings.setAdapterMode("real"); } });
      }
    }
    audit.sys("USER_LOGIN", "auth", Map.of("login", login), "login:" + id + ":" + now.getAsLong());
    return new User(id, gid, login, me.path("name").asText(null), me.path("avatar_url").asText(null));
  }
  public static Map<String, Object> view(User u) { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", u.id()); m.put("login", u.login()); m.put("name", u.name()); m.put("avatarUrl", u.avatarUrl()); return m; }

  public String installationState(String userId, String workspace, String redirect) {
    return sign(Map.of("typ", "github-install", "uid", userId, "ws", workspace, "r", safeRedirect(redirect), "exp", now.getAsLong() + 3_600_000));
  }
  public String installationCallback(String state, String action) {
    var claims = verify(state, "github-install");
    if (claims == null) throw new Denied("bad_state", "仓库授权请求已过期，请重新选择仓库");
    String workspace = claims.path("ws").asText(); String userId = claims.path("uid").asText();
    if (db.count("SELECT COUNT(*) FROM workspace_member WHERE workspace_id=? AND user_id=? AND role IN ('owner','admin')", workspace, userId) != 1) throw new Denied("not_allowed", "只有工作区管理员可以管理仓库授权");
    com.x7x24.tenant.Tenant.run(workspace, () -> { if ("request".equals(action)) creds.state("approval_pending"); else creds.state("unchecked"); });
    return claims.path("r").asText();
  }

  private JsonNode postForm(String path, Map<String, String> form) {
    StringBuilder b = new StringBuilder(); form.forEach((k, v) -> b.append(b.length() > 0 ? "&" : "").append(enc(k)).append('=').append(enc(v)));
    try {
      var req = HttpRequest.newBuilder(URI.create(props.github.webUrl.replaceAll("/+$", "") + path)).timeout(Duration.ofSeconds(20)).header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "7x24-agent").POST(HttpRequest.BodyPublishers.ofString(b.toString())).build();
      return Json.tree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
    } catch (Exception e) { throw new Denied("network", "连接 GitHub 失败：" + e.getClass().getSimpleName()); }
  }
  private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
