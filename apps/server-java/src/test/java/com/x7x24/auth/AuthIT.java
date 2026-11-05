package com.x7x24.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.util.Json;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthIT {
  static final String PAGES = "https://demo.example.invalid", SECRET = "test-client-secret-value";
  static final MockWebServer gh = new MockWebServer();
  static volatile String login = "octo"; static volatile long gid = 42;
  static final ConcurrentLinkedQueue<String> tokenBodies = new ConcurrentLinkedQueue<>(), tokenResponses = new ConcurrentLinkedQueue<>();
  static {
    gh.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest r) {
        var js = new MockResponse().setHeader("Content-Type", "application/json");
        String p = r.getPath();
        if (p.equals("/login/oauth/access_token")) { tokenBodies.add(r.getBody().readUtf8()); String b = tokenResponses.poll(); return js.setBody(b != null ? b : "{\"access_token\":\"gho_user_token\"}"); }
        if (p.equals("/login/device/code")) return js.setBody("{\"device_code\":\"dc\",\"user_code\":\"WXYZ-0000\",\"verification_uri\":\"https://github.com/login/device\",\"expires_in\":900,\"interval\":5}");
        if (p.equals("/user")) return "Bearer gho_user_token".equals(r.getHeader("Authorization")) ? js.setBody("{\"login\":\"" + login + "\",\"id\":" + gid + ",\"name\":\"N\",\"avatar_url\":\"https://a/u.png\"}") : js.setResponseCode(401).setBody("{}");
        if (p.startsWith("/user/installations?")) return js.setBody("{\"installations\":[{\"id\":42,\"permissions\":{\"contents\":\"write\"}}]}");
        if (p.startsWith("/user/installations/42/repositories")) return js.setBody("{\"repositories\":[{\"id\":99,\"full_name\":\"octo/repo\",\"permissions\":{\"push\":true}}]}");
        return js.setResponseCode(404).setBody("{}");
      }
    });
    try { gh.start(); } catch (Exception e) { throw new RuntimeException(e); }
  }
  @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
    String u = "http://localhost:" + gh.getPort();
    r.add("x7x24.github.api-url", () -> u); r.add("x7x24.github.web-url", () -> u); r.add("x7x24.github.token", () -> "");
    r.add("x7x24.github.oauth-client-id", () -> "Iv23test"); r.add("x7x24.auth.required", () -> "true"); r.add("x7x24.auth.client-secret", () -> SECRET);
    r.add("x7x24.auth.allowed-origins", () -> PAGES + ",https://x7x24-*.vercel.app"); r.add("x7x24.auth.allowed-logins", () -> "octo, Admin2");
    r.add("x7x24.data-dir", () -> System.getProperty("java.io.tmpdir") + "/x7auth"); r.add("spring.datasource.url", () -> "jdbc:h2:mem:auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1");
  }
  @AfterAll static void stop() throws Exception { gh.shutdown(); }
  @LocalServerPort int port; @Autowired AuthService auth;
  @Autowired com.x7x24.db.Db db; @Autowired com.x7x24.integration.CredentialProvider creds;
  final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  @BeforeEach void reset() { login = "octo"; gid = 42; tokenBodies.clear(); tokenResponses.clear(); auth.now = System::currentTimeMillis; }

  HttpResponse<String> req(String method, String path, Map<String, String> headers) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).method(method, HttpRequest.BodyPublishers.noBody());
    headers.forEach(b::header); return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }
  static Map<String, String> query(String q) { Map<String, String> m = new HashMap<>(); for (String kv : q.split("&")) { int i = kv.indexOf('='); m.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8)); } return m; }
  /** 走一遍网页授权，返回回跳 Location */
  String webLogin() throws Exception { return webLogin(PAGES + "/7x24/"); }
  String webLogin(String destination) throws Exception {
    var l = req("GET", "/api/auth/login?redirect=" + java.net.URLEncoder.encode(destination, StandardCharsets.UTF_8), Map.of()); assertEquals(302, l.statusCode());
    URI auth = URI.create(l.headers().firstValue("location").orElseThrow());
    var q = query(auth.getRawQuery()); assertEquals("Iv23test", q.get("client_id")); assertTrue(q.get("redirect_uri").endsWith("/api/auth/callback"));
    assertFalse(auth.toString().contains(SECRET), "authorize 地址不能带 client secret");
    var cb = req("GET", "/api/auth/callback?code=c1&state=" + java.net.URLEncoder.encode(q.get("state"), StandardCharsets.UTF_8), Map.of()); assertEquals(302, cb.statusCode());
    lastCookie = cb.headers().firstValue("set-cookie").orElse("");
    return cb.headers().firstValue("location").orElseThrow();
  }
  String lastCookie = "";
  static String tokenOf(String location) { return query(URI.create(location).getRawFragment()).get("token"); }

  @Test void apiIsProtectedButHealthAndAuthAreOpen() throws Exception {
    assertEquals(401, req("GET", "/api/meta", Map.of()).statusCode());
    assertEquals(401, req("GET", "/api/tasks", Map.of()).statusCode());
    assertEquals(200, req("GET", "/api/health", Map.of()).statusCode());
    JsonNode c = Json.tree(req("GET", "/api/auth/config", Map.of()).body());
    assertTrue(c.path("required").asBoolean()); assertTrue(c.path("webFlow").asBoolean()); assertFalse(c.toString().contains(SECRET));
  }
  @Test void webFlowReturnsToHistoryDeepLinkWithoutPuttingTokenInQuery() throws Exception {
    URI location = URI.create(webLogin(PAGES + "/p/project_1/detail?from=s%2Fchat_1"));
    assertEquals("/p/project_1/detail", location.getPath());
    assertEquals("from=s%2Fchat_1", location.getRawQuery());
    assertTrue(location.getRawFragment().startsWith("token="));
    assertNotNull(auth.userFromToken(tokenOf(location.toString())));
  }
  @Test void webFlowIssuesSessionUsableAsBearerAndCookie() throws Exception {
    String loc = webLogin(); assertTrue(loc.startsWith(PAGES + "/7x24/#token="), loc);
    assertTrue(tokenBodies.peek().contains("client_secret=" + SECRET) && tokenBodies.peek().contains("code=c1"));
    String tok = tokenOf(loc);
    assertEquals(200, req("GET", "/api/meta", Map.of("Authorization", "Bearer " + tok)).statusCode());
    var me = Json.tree(req("GET", "/api/auth/me", Map.of("Authorization", "Bearer " + tok)).body()); assertEquals("octo", me.path("login").asText());
    assertTrue(lastCookie.contains("HttpOnly")); String cookie = lastCookie.split(";")[0];
    assertEquals(200, req("GET", "/api/meta", Map.of("Cookie", cookie)).statusCode());
    assertFalse(me.toString().contains("gho_user_token"), "不返回 GitHub 访问令牌");
    var out = req("POST", "/api/auth/logout", Map.of("Authorization", "Bearer " + tok)); assertTrue(out.headers().firstValue("set-cookie").orElse("").contains("Max-Age=0"));
  }
  @Test void loginAlsoConnectsPersonalWorkspaceWithoutAnotherAuthorization() throws Exception {
    String tok = tokenOf(webLogin());
    var connection = Json.tree(req("GET", "/api/integrations/github", Map.of("Authorization", "Bearer " + tok)).body());
    assertTrue(connection.path("connected").asBoolean(), connection.toString());
    assertEquals("octo", connection.path("credential").path("login").asText());
    assertFalse(connection.toString().contains("gho_user_token"));
  }
  @Test void loginPreservesTeamConnectionAndMemberCannotReplaceIt() throws Exception {
    String tok = tokenOf(webLogin()); String uid = auth.userFromToken(tok).id(); String workspace = "ws_team_" + System.nanoTime();
    db.update("INSERT INTO workspace(id,slug,name,kind,plan_quota,status,created_at) VALUES(?,?,?,'team','{}','active',?)", workspace, workspace, "Team", java.time.Instant.now().toString());
    db.update("INSERT INTO workspace_member(workspace_id,user_id,role,created_at) VALUES(?,?,'member',?)", workspace, uid, java.time.Instant.now().toString());
    com.x7x24.tenant.Tenant.run(workspace, () -> creds.save("pat", "team-owned-test-token", "team-bot", null, "", "", null));
    webLogin();
    com.x7x24.tenant.Tenant.run(workspace, () -> assertEquals("team-owned-test-token", creds.token()));
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/integrations/github/repositories/bind")).header("Authorization", "Bearer " + tok).header("X-Workspace", workspace).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"fullName\":\"octo/repo\",\"installationId\":42}")).build();
    assertEquals(403, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
  }
  @Test void loginRefreshGrantIsEncryptedAndConcurrentReadsRotateOnce() throws Exception {
    tokenResponses.add("{\"access_token\":\"gho_user_token\",\"expires_in\":28800,\"refresh_token\":\"ghr_user_refresh\",\"refresh_token_expires_in\":15897600}");
    String tok = tokenOf(webLogin()); String uid = auth.userFromToken(tok).id();
    var saved = db.row("SELECT * FROM github_user_authorization WHERE user_id=?", uid);
    assertFalse(saved.toString().contains("ghr_user_refresh"));
    db.update("UPDATE github_user_authorization SET expires_at='2000-01-01T00:00:00Z' WHERE user_id=?", uid);
    tokenResponses.add("{\"access_token\":\"gho_user_token\",\"expires_in\":28800,\"refresh_token\":\"ghr_user_rotated\",\"refresh_token_expires_in\":15897600}");
    var one = java.util.concurrent.CompletableFuture.supplyAsync(() -> creds.userToken(uid));
    var two = java.util.concurrent.CompletableFuture.supplyAsync(() -> creds.userToken(uid));
    assertEquals("gho_user_token", one.get()); assertEquals("gho_user_token", two.get());
    assertEquals(2, tokenBodies.size(), "one login exchange and one serialized refresh");
    assertEquals("ghr_user_rotated", creds.box().decrypt(com.x7x24.db.Db.str(db.row("SELECT refresh_ciphertext FROM github_user_authorization WHERE user_id=?", uid).get("refresh_ciphertext"))));
  }
  @Test void reauthorizationCannotReplaceTheSignedInIdentity() throws Exception {
    String tok = tokenOf(webLogin()); String uid = auth.userFromToken(tok).id();
    URI authorize = URI.create(auth.authorizeUrl(PAGES + "/?github_reconnect=1", "http://localhost:" + port, uid));
    login = "ADMIN2"; gid = 8;
    var denied = assertThrows(AuthService.Denied.class, () -> auth.callback("reauth-code", query(authorize.getRawQuery()).get("state"), "http://localhost:" + port));
    assertEquals("account_mismatch", denied.code);
    assertEquals("octo", auth.userFromToken(tok).login());
  }
  @Test void installationApprovalCallbackChecksSignedWorkspaceAndManagerRole() throws Exception {
    String tok = tokenOf(webLogin()); String uid = auth.userFromToken(tok).id();
    String workspace = com.x7x24.db.Db.str(db.row("SELECT workspace_id FROM workspace_member WHERE user_id=? AND role='owner'", uid).get("workspace_id"));
    String state = auth.installationState(uid, workspace, PAGES + "/?github_setup=1");
    assertEquals(PAGES + "/?github_setup=1", auth.installationCallback(state, "request"));
    com.x7x24.tenant.Tenant.run(workspace, () -> assertEquals("approval_pending", creds.connectionState()));
    assertThrows(AuthService.Denied.class, () -> auth.installationCallback(state + "tampered", "install"));
    db.update("UPDATE workspace_member SET role='member' WHERE workspace_id=? AND user_id=?", workspace, uid);
    try { assertThrows(AuthService.Denied.class, () -> auth.installationCallback(state, "install")); }
    finally { db.update("UPDATE workspace_member SET role='owner' WHERE workspace_id=? AND user_id=?", workspace, uid); }
  }
  @Test void tamperedOrExpiredTokenIsRejected() throws Exception {
    String tok = tokenOf(webLogin());
    String bad = tok.substring(0, tok.length() - 2) + (tok.endsWith("AA") ? "BB" : "AA");
    assertEquals(401, req("GET", "/api/meta", Map.of("Authorization", "Bearer " + bad)).statusCode());
    auth.now = () -> System.currentTimeMillis() + 400L * 3600_000; // 超过 168h
    assertEquals(401, req("GET", "/api/meta", Map.of("Authorization", "Bearer " + tok)).statusCode());
  }
  @Test void openRedirectAndForgedStateAreRejected() throws Exception {
    assertEquals(400, req("GET", "/api/auth/login?redirect=https://evil.example/", Map.of()).statusCode());
    var cb = req("GET", "/api/auth/callback?code=c1&state=forged.state", Map.of());
    assertTrue(cb.headers().firstValue("location").orElseThrow().contains("error=bad_state"));
  }
  @Test void wildcardOriginsMatchOnlyOneLabel() throws Exception {
    assertTrue(auth.originAllowed("https://x7x24-git-codex-qy.vercel.app")); assertFalse(auth.originAllowed("https://x7x24-a.evil.vercel.app.attacker.com"));
    assertFalse(auth.originAllowed("https://other.vercel.app")); assertFalse(auth.originAllowed("http://x7x24-a.vercel.app"));
    var ok = req("OPTIONS", "/api/meta", Map.of("Origin", "https://x7x24-pr1.vercel.app", "Access-Control-Request-Method", "GET"));
    assertEquals("https://x7x24-pr1.vercel.app", ok.headers().firstValue("access-control-allow-origin").orElse(""));
    assertEquals(302, req("GET", "/api/auth/login?redirect=https://x7x24-pr1.vercel.app/", Map.of()).statusCode());
  }
  @Test void allowlistBlocksOtherAccounts() throws Exception {
    login = "mallory"; gid = 7;
    String loc = webLogin(); assertTrue(loc.contains("error=not_allowed"), loc); assertNull(tokenOf(loc));
    login = "ADMIN2"; gid = 8; assertNotNull(tokenOf(webLogin()), "白名单大小写不敏感");
  }
  @Test void deviceFlowFallbackIssuesSession() throws Exception {
    var s = Json.tree(req("POST", "/api/auth/device/start", Map.of()).body()); assertEquals("WXYZ-0000", s.path("userCode").asText());
    tokenResponses.add("{\"error\":\"authorization_pending\"}");
    assertEquals("pending", Json.tree(req("POST", "/api/auth/device/poll?sessionId=" + s.path("sessionId").asText(), Map.of()).body()).path("status").asText());
    long t0 = System.currentTimeMillis(); auth.now = () -> t0 + 10_000;
    var ok = Json.tree(req("POST", "/api/auth/device/poll?sessionId=" + s.path("sessionId").asText(), Map.of()).body());
    assertEquals("ok", ok.path("status").asText(), ok.toString());
    assertEquals(200, req("GET", "/api/meta", Map.of("Authorization", "Bearer " + ok.path("token").asText())).statusCode());
  }
  @Test void sseUsesOneTimeTicketNotSessionToken() throws Exception {
    String tok = tokenOf(webLogin());
    assertEquals(401, req("GET", "/api/board?access_token=" + tok, Map.of()).statusCode(), "查询参数令牌对普通接口无效");
    assertEquals(401, req("GET", "/api/events?access_token=" + tok, Map.of()).statusCode(), "SSE 不再接受 URL 里的会话令牌");
    assertEquals(401, req("POST", "/api/events/ticket", Map.of()).statusCode(), "签发票据需要登录");
    String t = Json.tree(req("POST", "/api/events/ticket", Map.of("Authorization", "Bearer " + tok)).body()).path("ticket").asText();
    assertFalse(t.isBlank());
    assertEquals(401, req("GET", "/api/board?ticket=" + t, Map.of()).statusCode(), "票据只对 SSE 生效");
    assertEquals(401, req("GET", "/api/events?ticket=" + tok, Map.of()).statusCode(), "会话令牌不能冒充票据");
    assertNotNull(auth.userFromTicket(t), "首次使用有效"); assertNull(auth.userFromTicket(t), "票据只能用一次");
    String t2 = auth.issueSseTicket(auth.userFromToken(tok)); long t0 = System.currentTimeMillis();
    auth.now = () -> t0 + AuthService.TICKET_MS + 1000; assertNull(auth.userFromTicket(t2), "过期票据无效");
    auth.now = System::currentTimeMillis;
  }
  @Test void corsAllowsOnlyConfiguredOriginWithCredentials() throws Exception {
    var ok = req("OPTIONS", "/api/meta", Map.of("Origin", PAGES, "Access-Control-Request-Method", "GET", "Access-Control-Request-Headers", "authorization"));
    assertEquals(PAGES, ok.headers().firstValue("access-control-allow-origin").orElse("")); assertEquals("true", ok.headers().firstValue("access-control-allow-credentials").orElse(""));
    var unauth = req("GET", "/api/meta", Map.of("Origin", PAGES)); assertEquals(401, unauth.statusCode()); assertEquals(PAGES, unauth.headers().firstValue("access-control-allow-origin").orElse(""), "401 也要带 CORS 头");
    var evil = req("OPTIONS", "/api/meta", Map.of("Origin", "https://evil.example", "Access-Control-Request-Method", "GET"));
    assertTrue(evil.headers().firstValue("access-control-allow-origin").isEmpty());
  }
  @Test void corsAllowsPatchButStillRequiresAuthenticationAndAnAllowedOrigin() throws Exception {
    String path = "/api/tasks/T-budget/budget-switches";
    var preflight = req("OPTIONS", path, Map.of("Origin", PAGES, "Access-Control-Request-Method", "PATCH", "Access-Control-Request-Headers", "authorization,content-type,x-workspace"));
    assertEquals(200, preflight.statusCode(), preflight.body());
    assertTrue(preflight.headers().firstValue("access-control-allow-methods").orElse("").contains("PATCH"));
    assertEquals(PAGES, preflight.headers().firstValue("access-control-allow-origin").orElse(""));
    assertEquals("true", preflight.headers().firstValue("access-control-allow-credentials").orElse(""));
    var unauthenticated = req("PATCH", path, Map.of("Origin", PAGES));
    assertEquals(401, unauthenticated.statusCode(), unauthenticated.body());
    assertEquals("unauthorized", Json.tree(unauthenticated.body()).path("error").asText());
    assertEquals(PAGES, unauthenticated.headers().firstValue("access-control-allow-origin").orElse(""));
    for (String method : List.of("OPTIONS", "PATCH")) {
      var denied = req(method, path, Map.of("Origin", "https://evil.example", "Access-Control-Request-Method", "PATCH"));
      assertEquals(403, denied.statusCode()); assertEquals("Invalid CORS request", denied.body());
    }
  }
}
