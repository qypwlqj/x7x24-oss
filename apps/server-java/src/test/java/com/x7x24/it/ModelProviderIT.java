package com.x7x24.it;

import com.sun.net.httpserver.HttpServer;
import com.x7x24.adapters.Redactor;
import com.x7x24.engine.X7Props;
import com.x7x24.llm.LlmProvider;
import com.x7x24.llm.ModelRouter;
import com.x7x24.llm.ProviderAdmin;
import com.x7x24.llm.ProviderKeys;
import com.x7x24.pipeline.Pipeline.ModelConfig;
import com.x7x24.pipeline.PipelineService;
import com.x7x24.util.Json;
import com.x7x24.web.PipelineApi;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelProviderIT extends Harness {
  /** fake test key */
  static final String KEY = "sk-test-" + "SUPERSECRET-9f3a7c";
  @Autowired ModelRouter router; @Autowired ProviderAdmin admin; @Autowired ProviderKeys keys; @Autowired PipelineApi api; @Autowired PipelineService pipes;
  HttpServer server; String base; ModelConfig orig;

  @BeforeEach void start() throws Exception {
    orig = Json.convert(router.config(), ModelConfig.class);
    var p = new X7Props(); p.llm.baseUrl = "https://api.deepseek.com"; p.llm.model = "deepseek-chat"; p.llm.judgeModels = "deepseek-chat@0.2,deepseek-chat@0.7,deepseek-reasoner";
    router.reload(ModelRouter.fromEnv(p));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/models", ex -> { boolean ok = ("Bearer " + KEY).equals(ex.getRequestHeaders().getFirst("Authorization"));
      byte[] b = (ok ? "{\"object\":\"list\",\"data\":[{\"id\":\"mock-b\"},{\"id\":\"mock-a\"},{\"id\":\"deepseek-chat-x\"}]}" : "{\"error\":\"bad key\"}").getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(ok ? 200 : 401, b.length); ex.getResponseBody().write(b); ex.close(); });
    server.createContext("/v1/chat/completions", ex -> { byte[] b = "{\"model\":\"mock-a\",\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
    server.start(); base = "http://localhost:" + server.getAddress().getPort() + "/v1";
  }
  @AfterEach void stop() { server.stop(0); router.resetForTest(orig); pipes.putModels(null); keys.delete("mock"); }

  ProviderAdmin.SaveReq req(String key) { var r = new ProviderAdmin.SaveReq(); r.id = "mock"; r.name = "Mock"; r.preset = "custom"; r.baseUrl = base; r.apiKey = key;
    var m = new ProviderAdmin.ModelIn(); m.id = "mock-a"; m.contextTokens = 64000; r.models = List.of(m); r.addToProfiles = List.of("exec", "judges"); return r; }

  @Test void testConnectionListsModelsAndRejectsBadKey() {
    var ok = admin.test(new ProviderAdmin.TestReq(null, base, KEY, "mock-a"));
    assertEquals(true, ok.get("ok")); assertEquals(true, ok.get("modelsSupported"));
    assertEquals(List.of("deepseek-chat-x", "mock-a", "mock-b"), ((List<?>) ok.get("models")).stream().map(x -> ((Map<?, ?>) x).get("id")).toList());
    assertEquals(true, ((Map<?, ?>) ok.get("ping")).get("ok"));
    var bad = admin.test(new ProviderAdmin.TestReq(null, base, "sk-wrong-key-123", null)); assertEquals(false, bad.get("ok"));
    assertFalse(Json.str(ok).contains(KEY));
  }

  @Test void saveEncryptsKeyRegistersModelAndRoutes() {
    var saved = admin.save(req(KEY));
    assertEquals("7c".length() + 2, String.valueOf(saved.get("last4")).length()); assertEquals("7c", String.valueOf(saved.get("last4")).substring(2));
    var row = db.row("SELECT ciphertext FROM llm_provider_key WHERE id='mock'"); assertNotNull(row); assertFalse(((String) row.get("ciphertext")).contains("SUPERSECRET"), "密文入库");
    assertEquals(KEY, keys.get("mock"), "加解密往返");
    var c = router.config(); assertEquals("db:mock", c.providers.stream().filter(x -> x.id.equals("mock")).findFirst().orElseThrow().secretRef);
    assertTrue(c.profiles.get("exec").chain.contains("mock-a")); assertTrue(c.profiles.get("judges").seats.stream().allMatch(s -> s.chain.contains("mock-a")));
    assertEquals(64000, c.models.stream().filter(m -> m.id.equals("mock-a")).findFirst().orElseThrow().contextTokens);
    // 执行档位：deepseek-chat 无密钥（测试环境）被跳过 → 兜底到页面接入的 mock-a
    var res = router.maker("dev.code", null, null, false).chat(new LlmProvider.Request("dev.code", "s", "p", "v"));
    assertEquals("mock-a", res.model());
    assertFalse(Redactor.redact("leak " + KEY).contains(KEY), "日志脱敏");
  }

  @Test void apiNeverReturnsPlaintextKey() {
    var r = new MockHttpServletRequest();
    String saved = Json.str(api.saveProvider(req(KEY), "platform", r));
    String list = Json.str(api.providers("platform", r)); String models = Json.str(api.models()); String all = Json.str(api.all(r));
    for (String s : List.of(saved, list, models, all)) { assertFalse(s.contains(KEY)); assertFalse(s.contains("SUPERSECRET")); }
    assertTrue(list.contains("\"last4\":\"7c".substring(0, 9)));
    assertTrue(audit.list(null, null, 200, 0).stream().anyMatch(e -> "MODEL_PROVIDER_SAVED".equals(e.get("event_type")) && !String.valueOf(e.get("payload")).contains(KEY)));
  }

  @Test void replaceKeyKeepsOrRequiresKeyAndDeleteCleansUp() {
    admin.save(req(KEY));
    var keep = req(null); admin.save(keep); assertEquals(KEY, keys.get("mock"), "不填密钥则保留原密钥");
    var moved = req(null); moved.baseUrl = "https://api.openai.com/v1"; assertThrows(IllegalArgumentException.class, () -> admin.save(moved), "改地址必须重新填密钥");
    admin.save(req("sk-new-key-0000")); assertEquals("sk-new-key-0000", keys.get("mock"));
    admin.delete("mock");
    assertNull(db.row("SELECT id FROM llm_provider_key WHERE id='mock'")); assertFalse(router.config().profiles.get("exec").chain.contains("mock-a"));
    var evil = req(KEY); evil.id = "evil"; evil.baseUrl = "https://169.254.169.254/v1"; assertThrows(IllegalArgumentException.class, () -> admin.save(evil));
  }

  @Test void onlyAdminsManageProviders() {
    pipes.setAdminsForTest(List.of("boss"));
    try { assertThrows(PipelineService.Forbidden.class, () -> api.saveProvider(req(KEY), "platform", new MockHttpServletRequest())); }
    finally { pipes.setAdminsForTest(List.of("test", "operator")); }
  }

  @Test void registeringTwoJudgesInSeparateSavesBuildsADiverseCommittee() {
    router.resetForTest(ModelRouter.blank());
    admin.save(req(KEY));
    var second=req(null);var m=new ProviderAdmin.ModelIn();m.id="mock-b";second.models=List.of(second.models.getFirst(),m);
    admin.save(second);
    assertEquals(List.of("mock-a","mock-b","mock-a"),router.config().profiles.get("judges").seats.stream().map(s->s.chain.getFirst()).toList());
  }

  @Test void registeringTwoJudgesTogetherBuildsADiverseCommittee() {
    router.resetForTest(ModelRouter.blank());
    var request=req(KEY);var m=new ProviderAdmin.ModelIn();m.id="mock-b";request.models=List.of(request.models.getFirst(),m);
    admin.save(request);
    assertEquals(List.of("mock-a","mock-b","mock-a"),router.config().profiles.get("judges").seats.stream().map(s->s.chain.getFirst()).toList());
  }
  @Test void editingReplacesSelectedModelsAndRemovesEveryOldReference() {
    admin.save(req(KEY));
    var edit=req(null);var m=new ProviderAdmin.ModelIn();m.id="mock-b";edit.models=List.of(m);
    admin.save(edit);
    var c=router.config();
    assertFalse(c.models.stream().anyMatch(x->x.id.equals("mock-a")));
    for(var profile:c.profiles.values()) {
      assertFalse(profile.chain.contains("mock-a"));
      if(profile.seats!=null)profile.seats.forEach(s->assertFalse(s.chain.contains("mock-a")));
    }
    assertTrue(c.models.stream().anyMatch(x->x.id.equals("deepseek-chat")),"other providers are preserved");
    assertEquals(List.of("mock-b"),admin.list().stream().filter(x->"mock".equals(x.get("id"))).findFirst().orElseThrow().get("models"));
    assertFalse(Json.str(api.models()).contains("mock-a"),"advanced configuration is refreshed too");
    var row=admin.list().stream().filter(x->"mock".equals(x.get("id"))).findFirst().orElseThrow();
    assertTrue(((List<?>)row.get("profiles")).containsAll(List.of("exec","judges")),"editing retains the provider's model uses");
  }
  @Test void deletingProviderRemovesItsHealthAndRechecksCommitteeReadiness() {
    var request=req(KEY);var second=new ProviderAdmin.ModelIn();second.id="mock-b";request.models=List.of(request.models.getFirst(),second);admin.save(request);
    assertEquals(true,router.committeeStatus().get("ready"));
    router.maker("dev.code",null,null,false).chat(new LlmProvider.Request("dev.code","s","p","v"));
    assertTrue(router.breakerStatus().containsKey("mock-a"));
    admin.delete("mock");
    assertFalse(router.breakerStatus().containsKey("mock-a"));
    assertEquals(false,router.committeeStatus().get("ready"));
    assertFalse(Json.str(api.models()).contains("mock-a"));
  }

  @Test void registrationInitializesEmptyEnvironmentSeatsAndKeepsValidManualAssignments() {
    var p=new X7Props();p.llm.baseUrl="https://example.com";p.llm.model="maker";
    router.resetForTest(ModelRouter.fromEnv(p));
    var request=req(KEY);var second=new ProviderAdmin.ModelIn();second.id="mock-b";request.models=List.of(request.models.getFirst(),second);admin.save(request);
    var before=router.config().profiles.get("judges").seats.stream().map(s->s.chain.getFirst()).toList();
    assertEquals(List.of("mock-a","mock-b","mock-a"),before);
    var third=req(null);var m=new ProviderAdmin.ModelIn();m.id="mock-c";third.models=List.of(request.models.getFirst(),second,m);admin.save(third);
    assertEquals(before,router.config().profiles.get("judges").seats.stream().map(s->s.chain.getFirst()).toList());
  }

  @Test void applyingCommitteeRequiresUsableCredentialsAndRejectsNullSeatData() {
    router.resetForTest(ModelRouter.blank());
    var request=req(KEY);var m=new ProviderAdmin.ModelIn();m.id="mock-b";request.models=List.of(request.models.getFirst(),m);admin.save(request);
    var seats=router.config().profiles.get("judges").seats;
    keys.delete("mock");
    assertThrows(PipelineService.Invalid.class,()->api.saveCommittee(new PipelineApi.CommitteeRequest(seats,false),managerRequest()));
    var invalid=new com.x7x24.pipeline.Pipeline.Seat();invalid.chain=null;
    assertThrows(PipelineService.Invalid.class,()->api.previewCommittee(new PipelineApi.CommitteeRequest(List.of(invalid),true),managerRequest()));
  }

  MockHttpServletRequest managerRequest() {
    var request=new MockHttpServletRequest();
    request.setAttribute(com.x7x24.tenant.WorkspaceFilter.ATTR,new com.x7x24.tenant.WorkspaceService.Ws("ws_legacy","legacy","Legacy","personal","owner","active"));return request;
  }
  @Test void committeePreviewDoesNotWriteAndSaveValidatesAndPreservesOtherProfiles() {
    router.resetForTest(ModelRouter.blank());
    var request=req(KEY);var m=new ProviderAdmin.ModelIn();m.id="mock-b";request.models=List.of(request.models.getFirst(),m);admin.save(request);
    var before=Json.str(router.config());var seats=Json.convert(router.config(),ModelConfig.class).profiles.get("judges").seats;
    seats.forEach(s->s.chain=new java.util.ArrayList<>(List.of("mock-a","mock-b")));
    var duplicate=new PipelineApi.CommitteeRequest(seats,false);
    assertThrows(PipelineService.Invalid.class,()->api.saveCommittee(duplicate,managerRequest()));
    var preview=api.previewCommittee(new PipelineApi.CommitteeRequest(seats,true),managerRequest());
    assertEquals(before,Json.str(router.config()),"预览不修改当前配置");
    var profile=(com.x7x24.pipeline.Pipeline.Profile)preview.get("profile");
    var saved=api.saveCommittee(new PipelineApi.CommitteeRequest(profile.seats,false),managerRequest());
    assertEquals(List.of("mock-a","mock-b"),router.config().profiles.get("exec").chain);
    assertEquals(true,((Map<?,?>)saved.get("committee")).get("ready"));
    assertThrows(com.x7x24.tenant.WorkspaceService.Forbidden.class,()->api.saveCommittee(new PipelineApi.CommitteeRequest(profile.seats,false),new MockHttpServletRequest()));
  }
}
