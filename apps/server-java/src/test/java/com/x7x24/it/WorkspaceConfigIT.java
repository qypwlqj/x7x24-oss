package com.x7x24.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.auth.AuthService;
import com.x7x24.integration.CredentialProvider;
import com.x7x24.integration.GitHubAuth;
import com.x7x24.integration.RuntimeSettings;
import com.x7x24.llm.ModelRouter;
import com.x7x24.llm.ProviderKeys;
import com.x7x24.tenant.Tenant;
import com.x7x24.tenant.WorkspaceService;
import com.x7x24.util.Json;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.MediaType;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 多工作区 P2：治理配置、模型供应商与密钥、GitHub 凭据、运行模式、角色权限按工作区隔离。 */
class WorkspaceConfigIT extends Harness {
  static final String B = "ws_cfg_b", C = "ws_cfg_c";
  @Autowired TestRestTemplate http; @Autowired WorkspaceService workspaces; @Autowired ModelRouter router; @Autowired ProviderKeys keys;
  @Autowired CredentialProvider creds; @Autowired RuntimeSettings settings; @Autowired GitHubAuth gh; @Autowired AuthService auth;

  @BeforeEach void ensure() {
    for (String w : List.of(B, C)) if (!workspaces.exists(w)) db.update("INSERT INTO workspace(id,slug,name,kind,plan_quota,status,created_at) VALUES(?,?,?,?,?,?,?)", w, w.replace('_', '-'), w, "team", "{}", "active", clk.iso());
  }
  JsonNode call(String ws, String token, HttpMethod m, String path, Object body) {
    var h = new HttpHeaders(); if (ws != null) h.set("X-Workspace", ws); if (token != null) h.setBearerAuth(token); h.setContentType(MediaType.APPLICATION_JSON);
    var res = http.exchange(path, m, new HttpEntity<>(body, h), String.class);
    var o = Json.M.createObjectNode(); o.set("body", res.getBody() == null || res.getBody().isBlank() ? Json.M.createObjectNode() : Json.tree(res.getBody())); o.put("_status", res.getStatusCode().value()); o.put("_raw", String.valueOf(res.getBody())); return o;
  }
  String member(String ws, String role) {
    long g = System.nanoTime(); String uid = "u_t" + g;
    db.update("INSERT INTO app_user(id,github_id,login,created_at,last_login_at) VALUES(?,?,?,?,?)", uid, g, "user" + g, clk.iso(), clk.iso());
    db.update("INSERT INTO workspace_member(workspace_id,user_id,role,created_at) VALUES(?,?,?,?)", ws, uid, role, clk.iso());
    return auth.issueSession(new AuthService.User(uid, g, "user" + g, null, null));
  }

  @Test void governanceIsPerWorkspace() {
    double before = cfg.get().budgets.task.usd;
    var r = call(B, null, HttpMethod.PUT, "/api/config", Map.of("budgets", Map.of("task", Map.of("usd", 1.23))));
    assertEquals(200, r.path("_status").asInt(), r.toString());
    assertEquals(1.23, Tenant.run(B, () -> cfg.get().budgets.task.usd));
    assertEquals(before, cfg.get().budgets.task.usd, "平台 / 遗留工作区不受影响");
    assertEquals(before, Tenant.run(Tenant.LEGACY, () -> cfg.get().budgets.task.usd));
    assertNotEquals(1.23, Tenant.run(C, () -> cfg.get().budgets.task.usd), "新工作区用默认值");
  }

  @Test void providersKeysAndFallbackArePerWorkspace() {
    String key = "sk-wsb-SECRET-" + System.nanoTime();
    var save = Map.of("name", "B 供应商", "baseUrl", "http://127.0.0.1:9/v1", "apiKey", key, "models", List.of(Map.of("id", "b-model")), "addToProfiles", List.of("exec", "judges"));
    var r = call(B, null, HttpMethod.POST, "/api/pipeline/providers", save); assertEquals(200, r.path("_status").asInt(), r.toString());
    assertFalse(r.path("_raw").asText().contains(key));
    String pid = r.path("body").path("id").asText();
    assertEquals(key, keys.get(B + "/" + pid), "密钥存在 B 范围下"); assertEquals("", keys.get(pid) == null ? "" : keys.get(pid), "平台范围没有这把密钥");
    // B 看得到自己的，遗留 / C 看不到
    assertTrue(call(B, null, HttpMethod.GET, "/api/pipeline/providers", null).path("_raw").asText().contains(pid));
    assertFalse(call(null, null, HttpMethod.GET, "/api/pipeline/providers", null).path("_raw").asText().contains("127.0.0.1:9"));
    assertFalse(call(C, null, HttpMethod.GET, "/api/pipeline/models", null).path("_raw").asText().contains("b-model"));
    // B 的有效配置：没开平台兜底 → 只有 B 自己的模型
    var bc = Tenant.run(B, () -> router.config());
    assertEquals(List.of("b-model"), bc.profiles.get("exec").chain);
    assertTrue(bc.providers.stream().noneMatch(p -> p.secretRef != null && p.secretRef.startsWith("env:")));
    // C 删除不了 B 的供应商（C 里不存在）
    assertNotEquals(200, call(C, null, HttpMethod.DELETE, "/api/pipeline/providers/" + pid, null).path("_status").asInt());
    assertNotNull(Tenant.run(B, () -> router.ownConfig()));
    // 工作区配置不能引用服务器环境变量
    var envCfg = Json.convert(router.platformConfig(), Map.class);
    var bad = call(B, null, HttpMethod.PUT, "/api/pipeline/models", envCfg);
    assertEquals(400, bad.path("_status").asInt(), bad.toString());
    // 平台管理员开启兜底 → B 的链末尾追加平台模型
    workspaces.putQuota(B, new LinkedHashMap<>(Map.of("llmFallback", true)));
    var merged = Tenant.run(B, () -> router.config());
    assertEquals("b-model", merged.profiles.get("exec").chain.get(0));
    assertTrue(merged.profiles.get("exec").chain.size() > 1, "兜底链追加了平台模型");
    workspaces.putQuota(B, new LinkedHashMap<>());
    assertEquals(1, Tenant.run(B, () -> router.config()).profiles.get("exec").chain.size());
  }

  @Test void githubCredentialAndModeArePerWorkspace() {
    String legacyBefore = creds.token();
    Tenant.run(B, () -> creds.save("pat", "ghp_workspaceB_token_" + System.nanoTime(), "bob", null, "repo", "", null));
    assertTrue(Tenant.run(B, () -> creds.token()).startsWith("ghp_workspaceB_token_"));
    assertEquals(legacyBefore, creds.token(), "遗留工作区不受影响");
    assertEquals("", Tenant.run(C, () -> creds.token()), "C 没有凭据，也不能用服务器 GITHUB_TOKEN");
    assertEquals("none", Tenant.run(C, () -> creds.info().source()));
    // 运行模式：新工作区默认观察（dry run）
    assertTrue(Tenant.run(C, () -> settings.dryRun()));
    Tenant.run(B, () -> settings.setDryRun(false));
    assertFalse(Tenant.run(B, () -> settings.dryRun())); assertTrue(Tenant.run(C, () -> settings.dryRun()));
    Tenant.run(B, () -> { creds.delete(); });
  }

  @Test void githubInstallationBindsToOneWorkspace() {
    long id = System.nanoTime();
    assertTrue(Tenant.run(B, () -> gh.claimInstallationForTest(id, "org-b")));
    assertTrue(Tenant.run(B, () -> gh.claimInstallationForTest(id, "org-b")), "幂等");
    assertFalse(Tenant.run(C, () -> gh.claimInstallationForTest(id, "org-b")), "已绑定到 B 的安装不能被 C 使用");
  }

  @Test void rolesControlWrites() {
    String viewer = member(B, "viewer"), mem = member(B, "member"), admin = member(B, "admin");
    assertEquals(403, call(B, viewer, HttpMethod.POST, "/api/product-lines", Map.of("name", "x")).path("_status").asInt(), "viewer 只读");
    assertEquals(200, call(B, viewer, HttpMethod.GET, "/api/product-lines", null).path("_status").asInt());
    assertEquals(403, call(B, mem, HttpMethod.POST, "/api/product-lines", Map.of("name", "x")).path("_status").asInt(), "member 不能建产品线");
    assertEquals(403, call(B, mem, HttpMethod.PUT, "/api/config", Map.of()).path("_status").asInt(), "member 不能改治理");
    assertEquals(403, call(B, mem, HttpMethod.POST, "/api/pipeline/providers", Map.of("name", "x", "baseUrl", "https://a.example.com/v1", "apiKey", "k".repeat(20), "models", List.of(), "addToProfiles", List.of())).path("_status").asInt());
    assertEquals(200, call(B, admin, HttpMethod.POST, "/api/product-lines", Map.of("name", "admin-line")).path("_status").asInt());
    // 不是成员的工作区：404
    assertEquals(404, call(C, admin, HttpMethod.GET, "/api/product-lines", null).path("_status").asInt());
    assertEquals(404, call(Tenant.LEGACY, admin, HttpMethod.GET, "/api/tasks", null).path("_status").asInt());
    // 平台配额只有平台管理员
    pipes().setAdminsForTest(List.of("boss"));
    try { assertEquals(403, call(B, admin, HttpMethod.PUT, "/api/platform/workspaces/" + B + "/quota", Map.of("llmFallback", true)).path("_status").asInt()); }
    finally { pipes().setAdminsForTest(List.of("test", "operator")); }
  }
  @Autowired com.x7x24.pipeline.PipelineService pipesBean; com.x7x24.pipeline.PipelineService pipes() { return pipesBean; }
}
