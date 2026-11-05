package com.x7x24.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.Redactor;
import com.x7x24.pipeline.Pipeline.*;
import com.x7x24.pipeline.PipelineService;
import com.x7x24.util.Json;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * 「接入模型」：供应商预设、测试连接（GET {base}/models + 可选对话 ping）、保存（密钥加密入库 → secretRef=db:<id>，
 * 自动登记模型并按需加入档位候选链）、删除。所有网络请求先过 {@link UrlGuard}，不跟随重定向。
 */
@Component
public class ProviderAdmin {
  public record KnownModel(String id, int contextTokens, int maxOutputTokens) {}
  public record Preset(String id, String name, String baseUrl, List<KnownModel> models) {}
  public static final List<Preset> PRESETS = List.of(
      new Preset("deepseek", "DeepSeek", "https://api.deepseek.com", List.of(new KnownModel("deepseek-chat", 64000, 8192), new KnownModel("deepseek-reasoner", 64000, 32000))),
      new Preset("openai", "OpenAI", "https://api.openai.com/v1", List.of(new KnownModel("gpt-4o", 128000, 16384), new KnownModel("gpt-4o-mini", 128000, 16384), new KnownModel("gpt-4.1", 1047576, 32768), new KnownModel("gpt-4.1-mini", 1047576, 32768))),
      new Preset("qwen", "通义千问（DashScope 兼容模式）", "https://dashscope.aliyuncs.com/compatible-mode/v1", List.of(new KnownModel("qwen-plus", 131072, 8192), new KnownModel("qwen-max", 32768, 8192), new KnownModel("qwen-turbo", 131072, 8192))),
      new Preset("moonshot", "Kimi（Moonshot）", "https://api.moonshot.cn/v1", List.of(new KnownModel("moonshot-v1-8k", 8192, 4096), new KnownModel("moonshot-v1-32k", 32768, 8192), new KnownModel("moonshot-v1-128k", 131072, 8192))),
      new Preset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", List.of(new KnownModel("glm-4-plus", 128000, 4096), new KnownModel("glm-4-flash", 128000, 4096))),
      new Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", List.of()),
      new Preset("custom", "自定义（OpenAI 兼容）", "", List.of()));
  public static KnownModel known(String modelId) { return PRESETS.stream().flatMap(p -> p.models().stream()).filter(m -> m.id().equals(modelId)).findFirst().orElse(null); }

  private final ModelRouter router; private final PipelineService pipes; private final ProviderKeys keys;
  HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
  public ProviderAdmin(ModelRouter router, PipelineService pipes, ProviderKeys keys) { this.router = router; this.pipes = pipes; this.keys = keys; }

  static String path(String base, String p) { String b = base.trim().replaceAll("/+$", ""); return b + p; }

  /** 供应商列表：只返回密钥来源、末 4 位、更新时间，不返回密钥 */
  static String scope() { var s = com.x7x24.tenant.Tenant.storeScope(); return s == null ? com.x7x24.tenant.Tenant.PLATFORM : s; }
  /** 平台（含遗留工作区）的密钥沿用原 id；其他工作区为 <工作区>/<供应商> */
  static String kid(String providerId) { var s = com.x7x24.tenant.Tenant.storeScope(); return s == null ? providerId : s + "/" + providerId; }
  boolean platform() { return com.x7x24.tenant.Tenant.storeScope() == null; }
  /** 当前范围自己保存的配置（工作区未配置时为空白模板） */
  ModelConfig own() { var c = router.ownConfig(); return c == null ? ModelRouter.blank() : Json.convert(c, ModelConfig.class); }
  void commit(ModelConfig cfg) {
    cfg.migratedFromEnv = false;
    var errs = ModelRouter.validateRegistration(cfg, platform()); if (!errs.isEmpty()) throw new IllegalArgumentException(String.join("；", errs));
    if (platform()) { router.reloadRegistration(cfg); pipes.putPlatformModels(cfg); } else { pipes.putModels(cfg); router.invalidate(); }
  }
  public List<Map<String, Object>> list() {
    var c = router.ownConfig(); List<Map<String, Object>> out = new ArrayList<>(); if (c == null) return out;
    for (var p : c.providers) {
      Map<String, Object> m = new LinkedHashMap<>(); m.put("id", p.id); m.put("name", p.name == null ? p.id : p.name); m.put("preset", p.preset); m.put("baseUrl", p.baseUrl);
      boolean db = p.secretRef != null && p.secretRef.startsWith("db:"); m.put("keySource", db ? "db" : "env");
      if (db) { var meta = keys.meta(kid(p.secretRef.substring(3))); m.put("last4", meta == null ? null : meta.last4()); m.put("keyUpdatedAt", meta == null ? null : meta.updatedAt()); }
      else m.put("envVar", p.secretRef == null ? null : p.secretRef.replaceFirst("^env:", ""));
      var models = c.models.stream().filter(x -> p.id.equals(x.provider)).map(x -> x.id).toList();
      m.put("available", router.available(p.id)); m.put("models", models);
      m.put("profiles", c.profiles.entrySet().stream().filter(en -> en.getValue().chain.stream().anyMatch(models::contains)
          || en.getValue().seats != null && en.getValue().seats.stream().anyMatch(s -> s.chain.stream().anyMatch(models::contains))).map(Map.Entry::getKey).toList());
      out.add(m);
    }
    return out;
  }

  public record TestReq(String providerId, String baseUrl, String apiKey, String pingModel) {}
  /** 测试连接：GET /models；/models 不支持时 modelsSupported=false（前端改为手动填写模型 id）。可选对话 ping。 */
  public Map<String, Object> test(TestReq r) {
    String key = r.apiKey() == null || r.apiKey().isBlank() ? storedKey(r.providerId(), r.baseUrl()) : r.apiKey().trim();
    if (key.isBlank()) throw new IllegalArgumentException("请填写 API Key");
    Redactor.register(key);
    UrlGuard.check(r.baseUrl(), router.allowLocalHttp);
    Map<String, Object> o = new LinkedHashMap<>(); long t0 = System.currentTimeMillis();
    try {
      var res = http.send(HttpRequest.newBuilder(URI.create(path(r.baseUrl(), "/models"))).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
      o.put("status", res.statusCode()); o.put("latencyMs", System.currentTimeMillis() - t0);
      if (res.statusCode() == 401 || res.statusCode() == 403) { o.put("ok", false); o.put("error", "API Key 无效或没有权限（HTTP " + res.statusCode() + "）"); return o; }
      if (res.statusCode() / 100 == 2) {
        JsonNode n = Json.lenient(res.body()); List<Map<String, Object>> ms = new ArrayList<>();
        JsonNode arr = n == null ? null : n.has("data") ? n.get("data") : n.has("models") ? n.get("models") : n;
        if (arr != null && arr.isArray()) for (var x : arr) { String id = x.isTextual() ? x.asText() : x.path("id").asText(""); if (id.isBlank()) continue; var k = known(id);
          Map<String, Object> m = new LinkedHashMap<>(); m.put("id", id); if (k != null) { m.put("contextTokens", k.contextTokens()); m.put("maxOutputTokens", k.maxOutputTokens()); } else if (x.path("context_length").isNumber()) m.put("contextTokens", x.get("context_length").asInt()); ms.add(m); }
        ms.sort(Comparator.comparing(m -> (String) m.get("id")));
        o.put("ok", true); o.put("modelsSupported", !ms.isEmpty()); o.put("models", ms.size() > 300 ? ms.subList(0, 300) : ms);
      } else { o.put("ok", res.statusCode() == 404 || res.statusCode() == 405); o.put("modelsSupported", false); o.put("models", List.of()); o.put("error", "该地址不支持 /models（HTTP " + res.statusCode() + "），请手动填写模型 id"); }
    } catch (Exception e) { o.put("ok", false); o.put("error", "连接失败：" + Redactor.redact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); return o; }
    if (r.pingModel() != null && !r.pingModel().isBlank()) o.put("ping", ping(r.baseUrl(), key, r.pingModel().trim()));
    return o;
  }
  Map<String, Object> ping(String base, String key, String model) {
    long t0 = System.currentTimeMillis(); Map<String, Object> o = new LinkedHashMap<>(); o.put("model", model);
    try {
      String body = Json.str(Map.of("model", model, "messages", List.of(Map.of("role", "user", "content", "ping，只回复 pong")), "max_tokens", 8));
      var res = http.send(HttpRequest.newBuilder(URI.create(path(base, "/chat/completions"))).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + key).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
      o.put("ok", res.statusCode() / 100 == 2); o.put("status", res.statusCode()); o.put("latencyMs", System.currentTimeMillis() - t0);
      if (res.statusCode() / 100 != 2) o.put("error", Redactor.redact(res.body().length() > 300 ? res.body().substring(0, 300) : res.body()));
    } catch (Exception e) { o.put("ok", false); o.put("error", Redactor.redact(String.valueOf(e.getMessage()))); }
    return o;
  }
  /** 已保存的密钥只能发往该供应商已保存的地址（改地址时必须重新填写密钥，防止把密钥发到别处） */
  private String storedKey(String providerId, String baseUrl) {
    if (providerId == null) return ""; var p = own().providers.stream().filter(x -> x.id.equals(providerId)).findFirst().orElse(null);
    if (p == null) return ""; p.scope = scope(); if (!Objects.equals(p.baseUrl == null ? null : p.baseUrl.replaceAll("/+$", ""), baseUrl == null ? null : baseUrl.trim().replaceAll("/+$", ""))) throw new IllegalArgumentException("修改了 Base URL，请重新填写 API Key");
    return router.apiKey(p);
  }

  public static class ModelIn { public String id; public Integer contextTokens, maxOutputTokens; public Double priceInPer1m, priceOutPer1m; }
  public static class SaveReq { public String id, name, preset, baseUrl, apiKey; public List<ModelIn> models = new ArrayList<>(); public List<String> addToProfiles = new ArrayList<>(); }

  /** 新建 / 更新供应商。apiKey 为空时保留原密钥（替换密钥只需填新 key）。返回不含密钥的列表项。 */
  public synchronized Map<String, Object> save(SaveReq r) {
    String id = r.id == null || r.id.isBlank() ? slug(r.name) : r.id.trim();
    if (!ProviderKeys.validId(id)) throw new IllegalArgumentException("供应商标识只能用小写字母、数字、- 和 _");
    UrlGuard.check(r.baseUrl, router.allowLocalHttp);
    var cfg = own();
    var existing = cfg.providers.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null);
    boolean hasKey = r.apiKey != null && !r.apiKey.isBlank();
    if (existing == null && !hasKey) throw new IllegalArgumentException("新供应商需要填写 API Key");
    if (existing != null && !hasKey && !Objects.equals(existing.baseUrl.replaceAll("/+$", ""), r.baseUrl.trim().replaceAll("/+$", ""))) throw new IllegalArgumentException("修改了 Base URL，请重新填写 API Key");
    var p = existing == null ? new Provider() : existing; if (existing == null) { p.id = id; cfg.providers.add(p); }
    p.name = r.name == null || r.name.isBlank() ? id : r.name.trim(); p.preset = r.preset; p.baseUrl = r.baseUrl.trim(); p.type = "openai-compat";
    if (hasKey) p.secretRef = "db:" + id;
    Set<String> selected = new HashSet<>(r.models.stream().filter(m -> m.id != null && !m.id.isBlank() && m.id.length() <= 200).map(m -> m.id.trim()).toList());
    Set<String> removed = new HashSet<>(cfg.models.stream().filter(m -> id.equals(m.provider) && !selected.contains(m.id)).map(m -> m.id).toList());
    removeModels(cfg, removed);
    for (var m : r.models) {
      if (m.id == null || m.id.isBlank() || m.id.length() > 200) continue; String mid = m.id.trim();
      var other = cfg.models.stream().filter(x -> x.id.equals(mid) && !id.equals(x.provider)).findFirst().orElse(null);
      if (other != null) throw new IllegalArgumentException("模型 " + mid + " 已登记在供应商 " + other.provider + " 下");
      var reg = cfg.models.stream().filter(x -> x.id.equals(mid)).findFirst().orElse(null); if (reg == null) { reg = new Model(); reg.id = mid; cfg.models.add(reg); }
      var k = known(mid); reg.provider = id; reg.family = ModelRouter.family(mid);
      reg.contextTokens = m.contextTokens != null ? m.contextTokens : k != null ? k.contextTokens() : reg.contextTokens != null ? reg.contextTokens : 32000;
      reg.maxOutputTokens = m.maxOutputTokens != null ? m.maxOutputTokens : k != null ? k.maxOutputTokens() : reg.maxOutputTokens != null ? reg.maxOutputTokens : 4096;
      if (m.priceInPer1m != null) reg.priceInPer1m = m.priceInPer1m; if (m.priceOutPer1m != null) reg.priceOutPer1m = m.priceOutPer1m;
      for (String prof : r.addToProfiles) {
        var pf = cfg.profiles.get(prof); if (pf == null) throw new IllegalArgumentException("档位不存在：" + prof);
        if (prof.equals("judges") && (pf.seats == null || pf.seats.isEmpty())) {
          pf.seats = ModelRouter.blank().profiles.get("judges").seats;
          for (var s : pf.seats) s.chain.addAll(pf.chain);
        }
        if (pf.seats != null) { for (var s : pf.seats) if (!s.chain.contains(mid)) s.chain.add(mid); } // 评审：加为每个席位的兜底（空席位则成为首选）
        else if (!pf.chain.contains(mid)) pf.chain.add(mid);
      }
    }
    if (r.addToProfiles.contains("judges") || !removed.isEmpty()) ModelRouter.assignJudges(cfg, false);
    var errs = ModelRouter.validateRegistration(cfg, platform()); if (!errs.isEmpty()) throw new IllegalArgumentException(String.join("；", errs));
    if (hasKey) keys.put(kid(id), r.apiKey);
    commit(cfg);
    router.forgetModels(removed);
    return list().stream().filter(x -> id.equals(x.get("id"))).findFirst().orElseThrow();
  }

  /** 删除供应商：同时移除其模型与档位候选链中的引用；会导致档位为空时拒绝 */
  public synchronized void delete(String id) {
    var cfg = own();
    var p = cfg.providers.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null); if (p == null) throw new IllegalArgumentException("供应商不存在：" + id);
    Set<String> ms = new HashSet<>(cfg.models.stream().filter(m -> id.equals(m.provider)).map(m -> m.id).toList());
    cfg.providers.remove(p); cfg.models.removeIf(m -> ms.contains(m.id));
    for (var en : cfg.profiles.entrySet()) { var pf = en.getValue(); pf.chain.removeIf(ms::contains); if (pf.seats != null) pf.seats.forEach(s -> s.chain.removeIf(ms::contains));
      if (!platform()) continue; // 工作区：允许删到空（相关档位给出提示）
      if (pf.seats == null && pf.chain.isEmpty()) throw new IllegalArgumentException("删除后档位 " + en.getKey() + " 将没有可用模型，请先给它加入其它模型");
      if (pf.seats != null && pf.seats.stream().anyMatch(s -> s.chain.isEmpty())) throw new IllegalArgumentException("删除后评审档位有席位没有模型，请先调整评审席位"); }
    commit(cfg);
    router.forgetModels(ms);
    if (p.secretRef != null && p.secretRef.startsWith("db:")) keys.delete(kid(p.secretRef.substring(3)));
  }
  private static void removeModels(ModelConfig cfg, Set<String> models) {
    cfg.models.removeIf(m -> models.contains(m.id));
    for (var pf : cfg.profiles.values()) {
      pf.chain.removeIf(models::contains);
      if (pf.seats != null) pf.seats.forEach(s -> s.chain.removeIf(models::contains));
    }
  }
  static String slug(String name) { String s = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", ""); return s.isBlank() ? "provider-" + Long.toString(System.currentTimeMillis() % 100000, 36) : s.length() > 40 ? s.substring(0, 40) : s; }
}
