package com.x7x24.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.db.Db;
import com.x7x24.util.Json;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IntegrationIT {
  static final GitHubFake gh = new GitHubFake();
  /** fake test keys — synthetic */
  static final String ENV_TOKEN = "ghp_" + "ENVTOKEN0123456789abcdefghijklmnop", PAT = "ghp_" + "PASTED0123456789abcdefghijklmnopqr", OAUTH = "ghu_" + "OAUTH00123456789abcdefghijklmnopqr";
  static Path dataDir;
  static { try { dataDir = Files.createTempDirectory("x7data"); } catch (Exception e) { throw new RuntimeException(e); } }

  @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
    r.add("x7x24.auth.required", () -> "false");
    r.add("x7x24.github.api-url", gh::url); r.add("x7x24.github.web-url", gh::url); r.add("x7x24.github.token", () -> ENV_TOKEN); r.add("x7x24.github.oauth-client-id", () -> "Iv23test"); r.add("x7x24.data-dir", () -> dataDir.toString());
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:integ;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1");
  }
  @AfterAll static void stop() throws Exception { gh.server.shutdown(); }

  @Autowired TestRestTemplate http; @Autowired CredentialProvider creds; @Autowired GitHubAuth auth; @Autowired RuntimeSettings settings; @Autowired Db db;
  @Autowired com.x7x24.engine.X7Props config;
  long clock; ListAppender<ILoggingEvent> logs;

  @BeforeEach void reset() {
    creds.delete(); gh.validTokens.clear(); gh.validTokens.addAll(Set.of(ENV_TOKEN, PAT, OAUTH)); gh.deviceCodeResponses.clear(); gh.tokenResponses.clear(); gh.seen.clear(); gh.scopes = "repo, workflow"; gh.login = "octo";
    gh.hasWorkflowFile = true; gh.workflowDispatch = true; gh.protectionStatus = 404; gh.push = true; gh.installations = List.of("1"); gh.contentsDenied = false; gh.appNoPerms = false; gh.workflowLegacy = false;
    clock = System.currentTimeMillis(); auth.clock(() -> clock);
    config.github.privateKey = ""; config.github.appId = ""; gh.tokenStatus = 200; gh.installationTokenStatus = 201;
    settings.setAdapterMode("real"); settings.setDryRun(true);
    logs = new ListAppender<>(); logs.start(); ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
  }
  @AfterEach void detach() { ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs); }

  JsonNode call(HttpMethod m, String path, Object body) {
    var res = http.exchange(path, m, body == null ? null : new HttpEntity<>(body), String.class); return Json.tree(res.getBody() == null ? "{}" : res.getBody()).deepCopy().isMissingNode() ? Json.tree("{}") : withStatus(res);
  }
  JsonNode withStatus(ResponseEntity<String> res) { var n = (com.fasterxml.jackson.databind.node.ObjectNode) (Json.tree(res.getBody()).isObject() ? Json.tree(res.getBody()) : Json.M.createObjectNode()); n.put("_status", res.getStatusCode().value()); return n; }
  /** 断言整个内容里没有任何令牌明文 */
  void noLeak(String where, String content) { for (String t : List.of(ENV_TOKEN, PAT, OAUTH)) assertFalse(content.contains(t), where + " 泄露了令牌"); }

  // ── 设备码
  @Test void rejectedRepositoryTokenIsVisibleOutsidePicker() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT));
    gh.validTokens.remove(PAT);
    assertEquals(502, call(HttpMethod.GET, "/api/integrations/github/repos", null).path("_status").asInt());
    var connection = call(HttpMethod.GET, "/api/integrations/github", null);
    assertEquals("reauth_required", connection.path("connection").path("state").asText(), connection.toString());
    assertFalse(connection.path("connected").asBoolean());
  }
  @Test void expiredOAuthTokenRotatesBothTokensAndKeepsWorkspaceUsable() {
    auth.saveConnected("oauth", OAUTH, new GitHubAuth.Validated("octo", null, "", "", null), "2000-01-01T00:00:00Z");
    creds.saveRefresh(Json.tree("{\"refresh_token\":\"ghr_old_refresh\",\"refresh_token_expires_in\":3600}"));
    gh.tokenResponses.add("{\"access_token\":\"" + PAT + "\",\"expires_in\":28800,\"refresh_token\":\"ghr_rotated_refresh\",\"refresh_token_expires_in\":15897600}");
    assertEquals(200, call(HttpMethod.GET, "/api/integrations/github/repos", null).path("_status").asInt());
    assertEquals(PAT, creds.token());
    var row = db.row("SELECT * FROM integration_credentials WHERE id='github'");
    assertEquals("ghr_rotated_refresh", creds.box().decrypt(Db.str(row.get("refresh_ciphertext"))));
    assertFalse(row.toString().contains("ghr_rotated_refresh"));
    assertEquals(1, gh.reqs("POST", "/login/oauth/access_token").size());
  }
  @Test void refreshNetworkFailureIsRetryableAndDoesNotDiscardGrant() {
    auth.saveConnected("oauth", OAUTH, new GitHubAuth.Validated("octo", null, "", "", null), "2000-01-01T00:00:00Z");
    creds.saveRefresh(Json.tree("{\"refresh_token\":\"ghr_retry_refresh\",\"refresh_token_expires_in\":3600}"));
    gh.tokenStatus = 503;
    var connection = call(HttpMethod.GET, "/api/integrations/github?refresh=true", null);
    assertEquals("network_error", connection.path("connection").path("state").asText());
    assertEquals("ghr_retry_refresh", creds.box().decrypt(Db.str(db.row("SELECT refresh_ciphertext FROM integration_credentials WHERE id='github'").get("refresh_ciphertext"))));
    gh.tokenStatus = 200;
    gh.tokenResponses.add("{\"access_token\":\"" + OAUTH + "\",\"expires_in\":28800,\"refresh_token\":\"ghr_after_retry\",\"refresh_token_expires_in\":3600}");
    assertEquals("ready", call(HttpMethod.GET, "/api/integrations/github?refresh=true", null).path("connection").path("state").asText());
  }
  @Test void revokedRefreshRequiresAuthorizationButDoesNotExpireApplicationSession() {
    auth.saveConnected("oauth", OAUTH, new GitHubAuth.Validated("octo", null, "", "", null), "2000-01-01T00:00:00Z");
    creds.saveRefresh(Json.tree("{\"refresh_token\":\"ghr_revoked_refresh\",\"refresh_token_expires_in\":3600}"));
    gh.tokenResponses.add("{\"error\":\"bad_refresh_token\"}");
    var connection = call(HttpMethod.GET, "/api/integrations/github?refresh=true", null);
    assertEquals(200, connection.path("_status").asInt());
    assertEquals("reauth_required", connection.path("connection").path("state").asText());
    assertFalse(connection.path("connected").asBoolean());
  }
  @Test void listingDoesNotClaimInstallationAndExplicitBindingScopesBackgroundToken() throws Exception {
    auth.saveConnected("oauth", OAUTH, new GitHubAuth.Validated("octo", null, "", "", null), null);
    call(HttpMethod.GET, "/api/integrations/github/repos", null);
    assertEquals(0, db.count("SELECT COUNT(*) FROM github_installation"));
    var keyPair = java.security.KeyPairGenerator.getInstance("RSA"); keyPair.initialize(2048); var keys = keyPair.generateKeyPair();
    config.github.privateKey = ("-----BEGIN " + "PRIVATE KEY-----\n") + Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()) + ("\n-----END " + "PRIVATE KEY-----");
    config.github.appId = "123";
    assertEquals(400, call(HttpMethod.POST, "/api/integrations/github/repositories/bind", Map.of("fullName", "octo/other", "installationId", 1)).path("_status").asInt());
    assertEquals(0, db.count("SELECT COUNT(*) FROM github_installation"));
    assertEquals(200, call(HttpMethod.POST, "/api/integrations/github/repositories/bind", Map.of("fullName", "octo/app-repo", "installationId", 1)).path("_status").asInt());
    gh.validTokens.remove(OAUTH); // Automation does not depend on the user's session or user token.
    assertEquals("ghs_installation_test_token", creds.tokenForRepo("octo/app-repo"));
    assertEquals("ghs_installation_test_token", creds.tokenForRepo("octo/app-repo"));
    var tokenRequests = gh.reqs("POST", "/app/installations/1/access_tokens"); assertEquals(1, tokenRequests.size());
    assertEquals("{\"repository_ids\":[101]}", tokenRequests.getFirst().getBody().readUtf8());
    String jwt = tokenRequests.getFirst().getHeader("Authorization").substring(7); String[] parts = jwt.split("\\.");
    var verifier = java.security.Signature.getInstance("SHA256withRSA"); verifier.initVerify(keys.getPublic()); verifier.update((parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8)); assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
    assertTrue(Json.tree(new String(Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8)).path("exp").asLong() - System.currentTimeMillis() / 1000 <= 600);
  }
  @Test void deviceFlowPendingSlowDownThenConnected() {
    var s = call(HttpMethod.POST, "/api/integrations/github/device/start", Map.of());
    assertTrue(s.path("ok").asBoolean()); assertEquals("ABCD-1234", s.path("userCode").asText()); assertTrue(s.path("verificationUri").asText().contains("github.com/login/device")); String id = s.path("sessionId").asText();
    assertFalse(s.toString().contains("dc123"), "device_code 不能返回前端");
    int before = gh.reqs("POST", "/login/oauth/access_token").size();
    assertEquals("pending", call(HttpMethod.POST, "/api/integrations/github/device/poll?sessionId=" + id, null).path("status").asText());
    assertEquals(before, gh.reqs("POST", "/login/oauth/access_token").size(), "未到 interval 时不应该请求 GitHub");
    clock += 6000; gh.tokenResponses.add("{\"error\":\"authorization_pending\"}");
    assertEquals("pending", call(HttpMethod.POST, "/api/integrations/github/device/poll?sessionId=" + id, null).path("status").asText());
    clock += 6000; gh.tokenResponses.add("{\"error\":\"slow_down\",\"interval\":10}");
    var slow = call(HttpMethod.POST, "/api/integrations/github/device/poll?sessionId=" + id, null); assertEquals("pending", slow.path("status").asText()); assertEquals(10, slow.path("interval").asInt());
    clock += 3000; assertEquals(before + 2, gh.reqs("POST", "/login/oauth/access_token").size());
    call(HttpMethod.POST, "/api/integrations/github/device/poll?sessionId=" + id, null); assertEquals(before + 2, gh.reqs("POST", "/login/oauth/access_token").size(), "slow_down 之后按新的间隔节流");
    clock += 11000; gh.tokenResponses.add("{\"access_token\":\"" + OAUTH + "\",\"token_type\":\"bearer\",\"expires_in\":28800}");
    var ok = call(HttpMethod.POST, "/api/integrations/github/device/poll", Map.of("sessionId", id));
    assertEquals("connected", ok.path("status").asText()); assertEquals("octo", ok.path("login").asText()); assertEquals("oauth", ok.path("credential").path("source").asText());
    assertEquals(OAUTH, creds.token()); assertNotNull(creds.info().expiresAt());
    String cipher = (String) db.row("SELECT ciphertext FROM integration_credentials WHERE id='github'").get("ciphertext");
    assertTrue(cipher.startsWith("v1.")); assertFalse(cipher.contains(OAUTH));
    var st = http.getForObject("/api/integrations/github", String.class); noLeak("状态接口", st); assertTrue(st.contains("octo"));
    assertEquals("expired", call(HttpMethod.POST, "/api/integrations/github/device/poll?sessionId=" + id, null).path("status").asText(), "成功后会话作废");
  }
  @Test void deviceFlowDeniedAndExpired() {
    String id = call(HttpMethod.POST, "/api/integrations/github/device/start", Map.of()).path("sessionId").asText(); clock += 6000;
    gh.tokenResponses.add("{\"error\":\"access_denied\"}");
    var d = call(HttpMethod.POST, "/api/integrations/github/device/poll?sessionId=" + id, null); assertEquals("denied", d.path("status").asText()); assertTrue(d.path("message").asText().contains("拒绝"));
    String id2 = call(HttpMethod.POST, "/api/integrations/github/device/start", Map.of()).path("sessionId").asText(); clock += 6000;
    gh.tokenResponses.add("{\"error\":\"expired_token\"}");
    assertEquals("expired", call(HttpMethod.POST, "/api/integrations/github/device/poll?sessionId=" + id2, null).path("status").asText());
    String id3 = call(HttpMethod.POST, "/api/integrations/github/device/start", Map.of()).path("sessionId").asText(); clock += 901_000;
    assertEquals("expired", call(HttpMethod.POST, "/api/integrations/github/device/poll?sessionId=" + id3, null).path("status").asText(), "本地到期同样判定过期，不再请求 GitHub");
    assertFalse(creds.info().source().equals("oauth"));
  }
  @Test void deviceFlowDisabledGivesActionableMessageAndPasteFallback() {
    gh.deviceCodeResponses.add("{\"error\":\"device_flow_disabled\",\"error_description\":\"Device flow must be explicitly enabled for this App\"}");
    var s = call(HttpMethod.POST, "/api/integrations/github/device/start", Map.of());
    assertFalse(s.path("ok").asBoolean()); assertEquals("device_flow_disabled", s.path("error").asText()); assertTrue(s.path("message").asText().contains("Enable Device Flow")); assertEquals("paste_token", s.path("fallback").asText());
  }
  @Test void unknownSessionIsExpired() { assertEquals("expired", call(HttpMethod.POST, "/api/integrations/github/device/poll?sessionId=nope", null).path("status").asText()); }

  // ── 粘贴令牌
  @Test void pasteTokenValidatesStoresEncryptedAndReportsScopes() {
    var r = call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT));
    assertTrue(r.path("ok").asBoolean()); assertEquals("pat", r.path("credential").path("source").asText()); assertEquals("octo", r.path("credential").path("login").asText()); assertTrue(r.path("credential").path("permissionsSummary").asText().contains("repo, workflow"));
    assertEquals(PAT, creds.token()); noLeak("响应", r.toString());
    assertEquals("real", settings.adapterMode());
    gh.scopes = "read:user"; call(HttpMethod.DELETE, "/api/integrations/github", null);
    var w = call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT)); assertEquals(2, w.path("warnings").size());
  }
  @Test void pasteInvalidTokenIsRejectedWithoutStoring() {
    var r = call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", "ghp_" + "INVALID0123456789abcdefghijklmnopq"));
    assertEquals(400, r.path("_status").asInt()); assertTrue(r.path("error").asText().contains("拒绝")); assertNull(db.row("SELECT id FROM integration_credentials"));
    assertEquals(400, call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", "short")).path("_status").asInt());
  }

  // ── 优先级 / 断开
  @Test void databaseCredentialBeatsEnvAndDisconnectFallsBack() {
    assertEquals(ENV_TOKEN, creds.token()); assertEquals("env", creds.info().source()); assertFalse(creds.info().deletable());
    assertEquals(400, call(HttpMethod.DELETE, "/api/integrations/github", null).path("_status").asInt(), "env 来源不可在界面删除");
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT)); assertEquals(PAT, creds.token());
    gh.seen.clear(); call(HttpMethod.GET, "/api/integrations/github/repos", null);
    assertEquals("Bearer " + PAT, gh.reqs("GET", "/user/repos").get(0).getHeader("Authorization"), "运行时变更无需重启，请求立即使用新令牌");
    assertEquals(200, call(HttpMethod.DELETE, "/api/integrations/github", null).path("_status").asInt());
    assertEquals(ENV_TOKEN, creds.token()); assertEquals("env", creds.info().source());
  }

  // ── 仓库
  @Test void repoListSupportsSearchPagingAndBoundMarker() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT));
    var all = call(HttpMethod.GET, "/api/integrations/github/repos", null); assertEquals(2, all.path("total").asInt()); assertEquals("octo/alpha", all.path("items").get(0).path("fullName").asText());
    assertEquals(1, call(HttpMethod.GET, "/api/integrations/github/repos?q=BET", null).path("items").size());
    var p2 = call(HttpMethod.GET, "/api/integrations/github/repos?page=2&size=1", null); assertEquals("octo/beta", p2.path("items").get(0).path("fullName").asText());
  }
  @Test void oauthSourceListsInstallationReposAndHintsWhenNoneInstalled() {
    auth.saveConnected("oauth", OAUTH, new GitHubAuth.Validated("octo", null, "", "", null), null);
    var r = call(HttpMethod.GET, "/api/integrations/github/repos", null); assertEquals("octo/app-repo", r.path("items").get(0).path("fullName").asText()); assertFalse(r.path("needInstall").asBoolean());
    gh.installations = List.of(); var e = call(HttpMethod.GET, "/api/integrations/github/repos", null);
    assertTrue(e.path("needInstall").asBoolean()); assertTrue(e.path("hint").asText().contains("安装"));
  }

  // ── 体检
  String project(String repo) {
    var r = call(HttpMethod.POST, "/api/product-lines/sandbox/projects", Map.of("name", "p-" + System.nanoTime(), "config", Map.of("repo", repo))); assertEquals(200, r.path("_status").asInt(), r.toString()); return r.path("id").asText();
  }
  Map<String, JsonNode> items(JsonNode res) { Map<String, JsonNode> m = new HashMap<>(); for (var i : res.path("items")) m.put(i.path("id").asText(), i); return m; }
  static String pid;
  @Test void appWithoutRepositoryPermissionsGetsSpecificHint() {
    auth.saveConnected("oauth", OAUTH, new GitHubAuth.Validated("octo", null, "", "", null), null);
    gh.appNoPerms = true;
    var r = call(HttpMethod.GET, "/api/integrations/github/repos", null);
    assertEquals(0, r.path("total").asInt()); assertEquals("app_no_repo_permissions", r.path("reason").asText(), r.toString()); assertTrue(r.path("hint").asText().contains("Repository permissions"));
    gh.appNoPerms = false;
    var ok = call(HttpMethod.GET, "/api/integrations/github/repos", null); assertEquals(1, ok.path("total").asInt()); assertTrue(ok.path("reason").isMissingNode());
  }
  @Autowired com.x7x24.integration.StackDetectService stack; @Autowired com.x7x24.engine.ProjectRepo projectRepo;
  @Test void detectEndpointSuggestsAndLegacyProjectsAreRedetectedWithAudit() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT)); String id = projectOnce();
    var d = call(HttpMethod.POST, "/api/integrations/github/detect?repo=o/r&branch=main", null);
    assertTrue(d.path("suggestion").path("technology").asText().contains("Gin"), d.toString()); assertTrue(d.path("patch").path("allowedPaths").toString().contains("frontend/src/"));
    var dry = call(HttpMethod.POST, "/api/projects/" + id + "/detect", null); assertFalse(dry.path("applied").asBoolean());
    projectRepo.update(id, Json.tree("{\"config\":{\"technology\":\"Node.js CommonJS 工具函数项目\"}}"));
    stack.redetectLegacy();
    var cfg = projectRepo.require(id).cfg(); assertTrue(cfg.technology.contains("前后端分离"), cfg.technology); assertTrue(cfg.allowedPaths.contains("backend/"));
    assertTrue(db.count("SELECT COUNT(*) FROM task_event WHERE event_type='PROJECT_STACK_DETECTED'") >= 1);
  }
  @Test void healthCheckAllGreenIsReadOnly() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT)); String id = projectOnce(); gh.seen.clear();
    var h = call(HttpMethod.POST, "/api/projects/" + id + "/connection", null); var it = items(h);
    assertTrue(h.path("ok").asBoolean(), h.toString()); for (String k : List.of("repo_read", "branch", "ci_file", "ci_dispatch", "write_code", "write_pr", "write_issues", "write_actions")) assertEquals("ok", it.get(k).path("status").asText(), k);
    assertEquals("warn", it.get("protection").path("status").asText());
    assertTrue(gh.seen.stream().allMatch(r -> r.getMethod().equals("GET")), "体检只读，不发任何写请求");
  }
  @Test void healthCheckExplainsMissingCiFileWithFixAction() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT)); String id = projectOnce(); gh.hasWorkflowFile = false;
    var h = call(HttpMethod.POST, "/api/projects/" + id + "/connection", null); var it = items(h);
    assertFalse(h.path("ok").asBoolean()); assertEquals("fail", it.get("ci_file").path("status").asText()); assertEquals("create_ci_pr", it.get("ci_file").path("action").asText()); assertTrue(it.get("ci_file").path("fix").asText().contains("PR"));
    assertFalse(h.path("workflowExists").asBoolean());
  }
  @Test void healthCheckDistinguishesMissingContentsPermissionFromMissingFile() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT)); String id = projectOnce(); gh.contentsDenied = true;
    var h = call(HttpMethod.POST, "/api/projects/" + id + "/connection", null); var it = items(h);
    assertEquals("fail", it.get("ci_file").path("status").asText()); assertTrue(it.get("ci_file").path("detail").asText().contains("缺少 Contents 读权限"), it.get("ci_file").toString());
    assertTrue(it.get("ci_file").path("action").isMissingNode() || it.get("ci_file").path("action").isNull(), "权限问题不应引导去补 CI 模板");
    assertFalse(h.path("contentsReadable").asBoolean(true));
  }
  @Test void healthCheckWarnsWithoutDispatchAndReportsUnknownProtectionInsteadOfFailing() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT)); String id = projectOnce(); gh.workflowDispatch = false; gh.protectionStatus = 403; gh.scopes = "repo";
    var it = items(call(HttpMethod.POST, "/api/projects/" + id + "/connection", null));
    assertEquals("warn", it.get("ci_dispatch").path("status").asText()); assertEquals("unknown", it.get("protection").path("status").asText()); assertTrue(it.get("protection").path("detail").asText().contains("拿不到"));
  }
  @Test void healthCheckReportsReadOnlyTokenAsFailedWritePermissions() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT)); String id = projectOnce(); gh.push = false;
    var h = call(HttpMethod.POST, "/api/projects/" + id + "/connection", null); var it = items(h);
    assertEquals("fail", it.get("write_code").path("status").asText()); assertEquals("fail", it.get("write_pr").path("status").asText()); assertFalse(h.path("ok").asBoolean());
  }
  @Test void unreachableRepoGivesInstallHint() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT));
    var h = call(HttpMethod.POST, "/api/projects/" + project("nobody/missing") + "/connection", null); var it = items(h);
    assertEquals("fail", it.get("repo_read").path("status").asText()); assertTrue(it.get("repo_read").path("fix").asText().contains("安装")); assertEquals("unknown", it.get("branch").path("status").asText());
  }
  String projectOnce() { if (pid == null) pid = project("o/r"); return pid; }

  // ── CI 模板 PR（用户显式动作）
  @Test void ciTemplatePrIsPreviewInObserveModeAndCreatesOnlyAPrInExecuteMode() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT)); String id = projectOnce(); gh.hasWorkflowFile = false; gh.seen.clear();
    var pv = call(HttpMethod.POST, "/api/projects/" + id + "/ci-template-pr", null);
    assertEquals("preview", pv.path("mode").asText()); assertTrue(pv.path("content").asText().contains("workflow_dispatch")); assertTrue(gh.seen.stream().noneMatch(r -> !r.getMethod().equals("GET")));
    assertEquals(200, call(HttpMethod.PUT, "/api/integrations/mode", Map.of("mode", "execute", "confirm", true)).path("_status").asInt());
    var done = call(HttpMethod.POST, "/api/projects/" + id + "/ci-template-pr", null);
    assertEquals("created", done.path("mode").asText(), done.toString()); assertEquals("https://github.com/o/r/pull/9", done.path("prUrl").asText());
    assertEquals(1, gh.reqs("PUT", "/repos/o/r/contents/.github/workflows/ci.yml").size()); assertEquals(1, gh.reqs("POST", "/repos/o/r/pulls").size());
    assertTrue(gh.seen.stream().noneMatch(r -> r.getPath().contains("/merge")), "绝不自动合并");
    assertTrue(db.count("SELECT COUNT(*) FROM task_event WHERE event_type='INTEGRATION_CI_BOOTSTRAP'") >= 1);
    var again = call(HttpMethod.POST, "/api/projects/" + id + "/ci-template-pr", null); assertEquals("created", again.path("mode").asText()); assertEquals(1, gh.reqs("POST", "/repos/o/r/pulls").size(), "幂等：不重复开 PR");
  }

  @Test void outdatedSevenByTwentyFourTemplateIsFlaggedAndRepairedOnlyViaPr() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT)); String id = projectOnce(); gh.hasWorkflowFile = true; gh.workflowLegacy = true; gh.seen.clear();
    var it = items(call(HttpMethod.POST, "/api/projects/" + id + "/connection", null));
    assertEquals("fail", it.get("ci_template").path("status").asText()); assertEquals("repair_ci_pr", it.get("ci_template").path("action").asText());
    assertEquals(200, call(HttpMethod.PUT, "/api/integrations/mode", Map.of("mode", "execute", "confirm", true)).path("_status").asInt());
    assertEquals(400, call(HttpMethod.POST, "/api/projects/" + id + "/ci-template-pr", Map.of()).path("_status").asInt(), "不带 repair 时不覆盖已存在的文件");
    var done = call(HttpMethod.POST, "/api/projects/" + id + "/ci-template-pr", Map.of("repair", true));
    assertEquals("created", done.path("mode").asText(), done.toString()); assertEquals("x7x24/fix-ci-ci", done.path("branch").asText());
    assertEquals(1, gh.reqs("PUT", "/repos/o/r/contents/.github/workflows/ci.yml").size()); assertEquals(1, gh.reqs("POST", "/repos/o/r/pulls").size());
    assertTrue(gh.seen.stream().noneMatch(r -> r.getPath().contains("/merge")), "绝不自动合并");
    gh.workflowLegacy = false;
    assertEquals(400, call(HttpMethod.POST, "/api/projects/" + id + "/ci-template-pr", Map.of("repair", true)).path("_status").asInt(), "不是 7x24 生成的文件不覆盖");
  }

  // ── 模式
  @Test void modeSwitchNeedsConfirmationAndPersistsAcrossRuntimeSettingsReads() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT));
    assertEquals("observe", call(HttpMethod.GET, "/api/integrations/github", null).path("mode").asText());
    assertEquals(400, call(HttpMethod.PUT, "/api/integrations/mode", Map.of("mode", "execute")).path("_status").asInt());
    assertTrue(settings.dryRun());
    assertEquals("execute", call(HttpMethod.PUT, "/api/integrations/mode", Map.of("mode", "execute", "confirm", true)).path("mode").asText());
    assertEquals("false", db.row("SELECT value FROM settings WHERE skey='adapter_dry_run'").get("value")); assertFalse(settings.dryRun());
    assertEquals("observe", call(HttpMethod.PUT, "/api/integrations/mode", Map.of("mode", "observe")).path("mode").asText()); assertTrue(settings.dryRun());
    assertTrue(db.count("SELECT COUNT(*) FROM task_event WHERE event_type='INTEGRATION_MODE_CHANGED'") >= 2);
  }

  // ── 连接状态
  @Test void statusPageGivesThreeCardsPlainStatesAndFailuresFirst() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT)); projectOnce();
    var s = call(HttpMethod.GET, "/api/integrations/status", null); assertEquals(3, s.path("cards").size());
    for (var c : s.path("cards")) assertTrue(List.of("ok", "attention", "disconnected").contains(c.path("state").asText()));
    assertEquals("attention", s.path("cards").get(1).path("state").asText(), "观察模式下「能否写入」需要处理");
    assertTrue(s.path("projects").size() >= 1); assertTrue(s.has("diagnostics")); noLeak("状态页", s.toString());
    db.update("INSERT INTO adapter_call (at,adapter,op,method,path,status,ok,dry_run,duration_ms,op_key,line,project_id,note,workspace_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'ws_legacy')", "2026-10-12T00:00:00.000Z", "git", "git.create_ref", "POST", "/repos/o/r/git/refs", 403, 0, 0, 10, null, null, null, "Resource not accessible");
    var s2 = call(HttpMethod.GET, "/api/integrations/status", null); assertEquals("创建分支失败：没有权限（令牌或 App 缺少写入权限）", s2.path("recentCalls").get(0).path("text").asText());
  }

  @Test void optionalBranchProtectionProbeFailureDoesNotMakeConnectionLookBroken() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT)); projectOnce();
    db.update("DELETE FROM adapter_call");
    db.update("INSERT INTO adapter_call (at,adapter,op,method,path,status,ok,dry_run,duration_ms,op_key,line,project_id,note,workspace_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'ws_legacy')", "2026-10-12T00:00:00.000Z", "git", "git.get_repo", "GET", "/repos/o/r", 200, 1, 0, 10, null, null, null, null);
    db.update("INSERT INTO adapter_call (at,adapter,op,method,path,status,ok,dry_run,duration_ms,op_key,line,project_id,note,workspace_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'ws_legacy')", "2026-10-12T00:00:01.000Z", "git", "git.read_rulesets", "GET", "/repos/o/r/branches/main/protection", 403, 0, 0, 10, null, null, null, "free plan");
    var s = call(HttpMethod.GET, "/api/integrations/status", null);
    assertEquals("ok", s.path("cards").get(0).path("state").asText(), "分支保护读不到只是“无法确认”，不是连不上");
    assertFalse(s.path("alerts").toString().contains("分支保护")); assertTrue(s.path("diagnostics").path("failures").asInt() <= db.count("SELECT COUNT(*) FROM adapter_call WHERE ok=0 AND dry_run=0 AND op<>'git.read_rulesets'"), "失败次数不含可选探测");
  }

  // ── 不泄露
  @Test void tokensNeverAppearInAnyResponseAuditExportOrLogs() {
    call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", PAT)); String id = projectOnce();
    call(HttpMethod.POST, "/api/projects/" + id + "/connection", null); call(HttpMethod.GET, "/api/integrations/github/repos", null); call(HttpMethod.POST, "/api/integrations/github/token", Map.of("token", "ghp_" + "BADBADBAD0123456789abcdefghijkl"));
    for (String path : List.of("/api/integrations/github", "/api/integrations/status", "/api/audit?limit=500", "/api/meta", "/api/adapters", "/api/projects", "/api/budget"))
      noLeak(path, Objects.requireNonNullElse(http.getForObject(path, String.class), ""));
    assertFalse(db.rows("SELECT * FROM task_event").toString().contains("ghp_"));
    assertFalse(db.rows("SELECT * FROM adapter_call").toString().contains("ghp_"));
    for (var e : logs.list) { noLeak("日志", e.getFormattedMessage()); assertFalse(e.getFormattedMessage().contains("ghp_BADBAD")); }
  }
}
