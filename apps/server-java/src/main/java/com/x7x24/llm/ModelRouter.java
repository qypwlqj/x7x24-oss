package com.x7x24.llm;

import com.x7x24.engine.Task;
import com.x7x24.engine.X7Props;
import com.x7x24.pipeline.Pipeline.*;
import com.x7x24.util.U;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色 → 模型档位 → 候选链（兜底）+ 每模型熔断器（设计文档 §6）。
 * - 无模型配置时从环境变量迁移（LLM_MODEL / LLM_CHEAP_MODEL / LLM_JUDGE_MODELS），行为与改造前一致：每个档位只有一个模型，没有兜底。
 * - switch_model 自愈写 task.context.modelPins，后续调用优先用钉住的模型。
 * - 密钥只通过 secretRef（env:NAME）引用；只允许 *_API_KEY 形式的变量，且排除平台自身凭据。
 */
public class ModelRouter {
  private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);
  public static final List<String> PERSONAS = List.of("你的席位风格：务实的产品负责人，重点质疑价值与范围是否合理。", "你的席位风格：保守的工程与安全评审员，重点质疑可行性、越权与注入风险，宁严勿松。", "你的席位风格：对抗式评审员，主动构造反例与边界情况，并在席位分歧时担任仲裁，独立复核其它意见。");
  static final Set<String> DENY_SECRETS = Set.of("RENDER_API_KEY", "X7_SECRET_KEY", "GITHUB_TOKEN", "VERCEL_TOKEN", "GITHUB_OAUTH_CLIENT_SECRET");

  /** 构造真实 provider 的工厂（测试可替换） */
  public interface Factory { LlmProvider create(Provider p, String apiKey, Model m, double temperature, String persona); }

  private final X7Props props; private final Factory factory;
  /** 平台默认配置（迁移自环境变量 / 平台模型配置）；工作区自己的配置由 wsLoader 提供 */
  private volatile ModelConfig cfg;
  private final Map<String, ModelConfig> composed = new ConcurrentHashMap<>();
  /** 工作区自己的模型配置（null = 未配置） */
  public java.util.function.Function<String, ModelConfig> wsLoader = ws -> null;
  /** 工作区是否允许兜底到平台默认供应商（决策 5A：默认关闭，平台管理员按工作区开启） */
  public java.util.function.Predicate<String> fallbackAllowed = ws -> false;
  private final Map<String, LlmProvider> cache = new ConcurrentHashMap<>();
  private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();
  java.util.function.LongSupplier now = System::currentTimeMillis;

  public ModelRouter(X7Props props, ModelConfig cfg, Factory factory) { this.props = props; this.factory = factory; this.cfg = cfg == null ? fromEnv(props) : cfg; }
  /** 当前工作区生效的配置：工作区自己的配置（+ 允许时追加平台兜底）；系统 / 平台上下文 = 平台配置 */
  private final ThreadLocal<String> expectedConfig = new ThreadLocal<>();
  public ModelConfig config() {
    String ws = com.x7x24.tenant.Tenant.storeScope(); var current = ws == null ? cfg : composed.computeIfAbsent(ws, this::compose);
    String expected = expectedConfig.get();
    if (expected != null && !expected.equals(com.x7x24.workflow.Canonical.digest(current))) throw new IllegalStateException("模型配置已变化，请重新批准计划后执行");
    return current;
  }
  public <T> T withExpectedConfig(String digest, java.util.function.Supplier<T> action) {
    String previous=expectedConfig.get(); expectedConfig.set(digest);
    try { config(); return action.get(); } finally { if(previous==null)expectedConfig.remove();else expectedConfig.set(previous); }
  }
  public ModelConfig platformConfig() { return cfg; }
  /** 工作区自己保存的配置（不含平台兜底）；未配置返回 null */
  public ModelConfig ownConfig() { String ws = com.x7x24.tenant.Tenant.storeScope(); return ws == null ? cfg : wsLoader.apply(ws); }
  public boolean fallbackEnabled() { String ws = com.x7x24.tenant.Tenant.storeScope(); return ws == null || fallbackAllowed.test(ws); }
  /** 配置变更（任一工作区 / 平台 / 兜底开关）后清缓存 */
  public synchronized void invalidate() { composed.clear(); cache.clear(); }
  ModelConfig compose(String ws) {
    ModelConfig own = wsLoader.apply(ws); boolean fb = fallbackAllowed.test(ws);
    if (own == null) { if (fb) { var pc = com.x7x24.util.Json.convert(cfg, ModelConfig.class); pc.providers.forEach(p -> p.scope = com.x7x24.tenant.Tenant.PLATFORM); return pc; } var e = new ModelConfig(); e.migratedFromEnv = false; var ex = new Profile(); e.profiles.put("exec", ex); var j = new Profile(); j.seats = new ArrayList<>(); e.profiles.put("judges", j); return e; }
    var c = com.x7x24.util.Json.convert(own, ModelConfig.class);
    for (var p : c.providers) p.scope = ws;
    if (!fb) return c;
    // 平台兜底：平台供应商 / 模型追加到末尾（同名供应商改名，模型同名时以工作区为准），各档位链末尾追加平台对应档位的链
    var plat = com.x7x24.util.Json.convert(cfg, ModelConfig.class); Map<String, String> rename = new HashMap<>();
    for (var p : plat.providers) { String id = p.id; if (c.providers.stream().anyMatch(x -> x.id.equals(id))) { rename.put(id, id + "@platform"); p.id = id + "@platform"; } p.scope = com.x7x24.tenant.Tenant.PLATFORM; c.providers.add(p); }
    Set<String> own_ = new HashSet<>(c.models.stream().map(m -> m.id).toList());
    for (var m : plat.models) { if (own_.contains(m.id)) continue; m.provider = rename.getOrDefault(m.provider, m.provider); c.models.add(m); }
    for (var en : plat.profiles.entrySet()) {
      var mine = c.profiles.get(en.getKey()); var pp = en.getValue();
      if (mine == null) { c.profiles.put(en.getKey(), pp); continue; }
      if (pp.seats != null && pp.seats.isEmpty()) continue;
      if (pp.seats != null && mine.seats != null) { for (int i = 0; i < mine.seats.size(); i++) { var add = pp.seats.get(Math.min(i, pp.seats.size() - 1)).chain; for (String m : add) if (!mine.seats.get(i).chain.contains(m)) mine.seats.get(i).chain.add(m); } if (mine.seats.isEmpty()) mine.seats = pp.seats; }
      else if (pp.chain != null) for (String m : pp.chain) if (!mine.chain.contains(m)) mine.chain.add(m);
    }
    return c;
  }
  /** 测试用：不校验直接替换平台配置 */
  public synchronized void resetForTest(ModelConfig c) { cfg = c; invalidate(); }
  public synchronized void reload(ModelConfig next) { var errs = validate(next, props, true); if (!errs.isEmpty()) throw new IllegalArgumentException(String.join("；", errs)); cfg = next; invalidate(); }
  /** 登记供应商允许逐步补齐配置；开始评审时再检查委员会是否就绪。 */
  public synchronized void reloadRegistration(ModelConfig next) { var errs = validateRegistration(next, true); if (!errs.isEmpty()) throw new IllegalArgumentException(String.join("；", errs)); cfg = next; invalidate(); }

  /** 从环境变量迁移出 v1 模型配置（与改造前 Wiring.providers 等价） */
  public static ModelConfig fromEnv(X7Props p) {
    var l = p.llm; ModelConfig c = new ModelConfig(); c.migratedFromEnv = true;
    var pv = new Provider(); pv.id = "default"; pv.baseUrl = l.baseUrl; pv.secretRef = "env:LLM_API_KEY"; pv.maxConcurrency = l.maxConcurrency; pv.timeoutSec = l.timeoutSec; pv.maxRetries = l.maxRetries; pv.minIntervalMs = l.minIntervalMs; c.providers.add(pv);
    Set<String> ids = new LinkedHashSet<>(); ids.add(l.model); if (!l.cheapModel.isBlank()) ids.add(l.cheapModel);
    List<String[]> seats = new ArrayList<>(); for (String spec : l.judgeModels.split(",")) if (!spec.isBlank()) { String[] mt = spec.trim().split("@"); seats.add(mt); ids.add(mt[0].trim()); }
    for (String id : ids) { if (id.isBlank()) continue; var m = new Model(); m.id = id; m.provider = "default"; m.family = family(id); m.priceInPer1m = l.priceInPer1m; m.priceOutPer1m = l.priceOutPer1m; m.maxOutputTokens = id.contains("reasoner") ? 32000 : 8192; c.models.add(m); }
    var exec = new Profile(); exec.chain.add(l.model); exec.temperature = l.temperature; c.profiles.put("exec", exec);
    var cheap = new Profile(); cheap.chain.add(l.cheapModel.isBlank() ? l.model : l.cheapModel); cheap.temperature = l.temperature; c.profiles.put("cheap", cheap);
    var judges = new Profile(); judges.seats = new ArrayList<>(); int i = 0;
    for (String[] mt : seats) { var s = new Seat(); s.chain.add(mt[0].trim()); s.temperature = mt.length > 1 ? Double.parseDouble(mt[1]) : (i == 0 ? 0.2 : i == 1 ? 0.0 : 0.7); s.persona = PERSONAS.get(Math.min(i, PERSONAS.size() - 1)); judges.seats.add(s); i++; }
    c.profiles.put("judges", judges); c.budgetDowngradeProfile = "cheap";
    return c;
  }
  static String family(String id) { String s = id.toLowerCase(Locale.ROOT); for (String f : List.of("deepseek", "claude", "gpt", "qwen", "gemini", "glm", "kimi", "moonshot", "llama", "mistral")) if (s.contains(f)) return f; return s.split("[-_/]")[0]; }

  public static List<String> validate(ModelConfig c, X7Props p) { return validate(c, p, true); }
  /** platform=false：工作区配置，禁止 env: 引用（只能用本工作区页面保存的密钥） */
  public static List<String> validate(ModelConfig c, X7Props p, boolean platform) {
    var errors = validateRegistration(c, platform);
    errors.addAll(judgeErrors(c));
    return errors;
  }
  public static List<String> validateRegistration(ModelConfig c, boolean platform) {
    List<String> e = new ArrayList<>(); Map<String, Provider> pv = new HashMap<>(); Map<String, Model> ms = new HashMap<>();
    for (var x : c.providers) {
      if (x.id == null || x.baseUrl == null) { e.add("供应商需要 id 和 baseUrl"); continue; } pv.put(x.id, x);
      if (!x.baseUrl.startsWith("https://") && !x.baseUrl.startsWith("http://localhost") && !x.baseUrl.startsWith("http://127.0.0.1")) e.add("供应商 " + x.id + " 的 baseUrl 必须是 https");
      boolean dbRef = x.secretRef != null && x.secretRef.matches("db:[a-z0-9][a-z0-9_-]{0,60}");
      if (!platform && !dbRef) { e.add("供应商 " + x.id + " 的密钥只能在页面填写（工作区不能引用服务器环境变量）"); continue; }
      if (!dbRef && (x.secretRef == null || !x.secretRef.matches("env:[A-Z][A-Z0-9_]*_API_KEY") || DENY_SECRETS.contains(x.secretRef.substring(4)))) e.add("供应商 " + x.id + " 的密钥引用必须是页面保存的密钥（db:…）或 env:XXX_API_KEY（平台自身凭据不可引用）");
    }
    for (var m : c.models) { if (m.id == null || !pv.containsKey(m.provider)) e.add("模型 " + m.id + " 引用了不存在的供应商 " + m.provider); else ms.put(m.id, m); }
    for (var en : c.profiles.entrySet()) {
      var pf = en.getValue();
      if (pf.seats != null) { for (var s : pf.seats) for (String id : s.chain) if (!ms.containsKey(id)) e.add("档位 " + en.getKey() + " 席位引用了未注册模型 " + id); }
      else { for (String id : pf.chain) if (!ms.containsKey(id)) e.add("档位 " + en.getKey() + " 引用了未注册模型 " + id); }
    }
    for (String need : List.of("exec", "judges")) if (!c.profiles.containsKey(need)) e.add("缺少档位 " + need);
    for (var en : c.roles.entrySet()) if (!c.profiles.containsKey(en.getValue())) e.add("角色 " + en.getKey() + " 绑定了不存在的档位 " + en.getValue());
    return e;
  }
  /** 保存、预览与运行时共用的双模型约束；空值不计作模型。 */
  public static List<String> judgeModelErrors(List<String> models) {
    List<String> errors = new ArrayList<>();
    if (models.size() < 2) errors.add("评审委员会至少需要 2 个席位");
    if (models.stream().anyMatch(m -> m == null || m.isBlank())) errors.add("有评审席位没有可用模型，请配置主模型或供应商密钥");
    if (models.stream().filter(Objects::nonNull).filter(m -> !m.isBlank()).distinct().count() < 2) errors.add("评审委员会至少需要 2 个不同模型");
    return errors;
  }
  public static List<String> judgeErrors(ModelConfig c) {
    var j = c.profiles.get("judges");
    return judgeModelErrors(j == null || j.seats == null ? List.of() : j.seats.stream().map(s -> s.chain.isEmpty() ? "" : s.chain.getFirst()).toList());
  }
  /** 只重排候选链，保留温度、人设和已合法的人工分配。force 用于用户显式的一键分配。 */
  public static void assignJudges(ModelConfig c, boolean force) {
    var j = c.profiles.get("judges"); if (j == null) return;
    if (!force && judgeErrors(c).isEmpty()) return;
    if (j.seats == null || j.seats.size() < 2) {
      var previous = j.seats; j.seats = blank().profiles.get("judges").seats;
      for (int i=0;i<j.seats.size();i++) j.seats.get(i).chain.addAll(previous != null && !previous.isEmpty() ? previous.get(Math.min(i,previous.size()-1)).chain : j.chain);
    }
    List<String> pool = j.seats.stream().flatMap(s -> s.chain.stream()).distinct().toList();
    if (pool.isEmpty() && force) pool = c.models.stream().map(m -> m.id).toList();
    if (pool.isEmpty()) return;
    for (int i = 0; i < j.seats.size(); i++) {
      var s = j.seats.get(i); String primary = pool.get(i % pool.size());
      var chain = new LinkedHashSet<String>(); chain.add(primary); chain.addAll(s.chain); chain.addAll(pool); s.chain = new ArrayList<>(chain);
    }
  }
  /** 工作区配置的提示（不阻止保存）：执行档位为空、评审席位不足或与执行模型相同 */
  public static List<String> warnings(ModelConfig c) {
    List<String> w = new ArrayList<>(); if (c == null) return List.of("还没有接入模型：请在「接入模型」里添加供应商" );
    var ex = c.profiles.get("exec");
    if (ex == null || ex.chain.isEmpty()) w.add("执行档位还没有模型");
    w.addAll(judgeErrors(c));
    return w;
  }
  /** 新工作区的空白配置：执行 / 降级 / 评审（3 席）档位，链为空 */
  public static ModelConfig blank() {
    var c = new ModelConfig(); c.migratedFromEnv = false;
    var exec = new Profile(); c.profiles.put("exec", exec); var cheap = new Profile(); c.profiles.put("cheap", cheap);
    var j = new Profile(); j.seats = new ArrayList<>(); for (int i = 0; i < 3; i++) { var s = new Seat(); s.temperature = i == 0 ? 0.2 : i == 1 ? 0.0 : 0.7; s.persona = PERSONAS.get(i); j.seats.add(s); } c.profiles.put("judges", j);
    c.budgetDowngradeProfile = "cheap"; return c;
  }

  // ── 密钥解析：只看 secretRef 指向的环境变量；LLM_API_KEY 取已加载的配置值
  /** db:<id> 密钥的解析器（ProviderKeys），由 Wiring 注入 */
  public java.util.function.Function<String, String> keyStore = id -> "";
  public boolean allowLocalHttp;
  String apiKey(Provider p) {
    String scope = p.scope != null ? p.scope : Optional.ofNullable(com.x7x24.tenant.Tenant.storeScope()).orElse(com.x7x24.tenant.Tenant.PLATFORM);
    String keyId = com.x7x24.tenant.Tenant.PLATFORM.equals(scope) ? null : scope;
    if (p.secretRef != null && p.secretRef.startsWith("db:")) { String k = keyStore.apply(keyId == null ? p.secretRef.substring(3) : keyId + "/" + p.secretRef.substring(3)); return k == null ? "" : k; }
    if (p.secretRef == null || !p.secretRef.startsWith("env:")) return "";
    if (!com.x7x24.tenant.Tenant.PLATFORM.equals(scope)) return ""; // 工作区配置不能读服务器环境变量
    String name = p.secretRef.substring(4); if (DENY_SECRETS.contains(name)) return "";
    if (name.equals("LLM_API_KEY")) return props.llm.apiKey == null ? "" : props.llm.apiKey;
    String v = System.getenv(name); return v == null ? "" : v;
  }
  /** 供应商是否可用（有密钥）。未配置密钥的供应商（例如 P4 预留的第二供应商）自动跳过，不影响启动。 */
  public boolean available(String providerId) { var p = provider(providerId); return p != null && !apiKey(p).isBlank(); }
  Provider provider(String id) { return config().providers.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null); }
  Model model(String id) { return config().models.stream().filter(m -> m.id.equals(id)).findFirst().orElse(null); }

  LlmProvider instance(String modelId, Double temperature, String persona) {
    var m = model(modelId); if (m == null) return null; var p = provider(m.provider); if (p == null) return null;
    String key = apiKey(p); if (key.isBlank()) return null;
    if (p.secretRef.startsWith("db:")) { try { UrlGuard.check(p.baseUrl, allowLocalHttp); } catch (IllegalArgumentException e) { log.warn("供应商 {} 地址被拒绝：{}", p.id, e.getMessage()); return null; } }
    double t = temperature == null ? props.llm.temperature : temperature;
    return cache.computeIfAbsent(String.valueOf(p.scope) + "|" + p.id + "|" + modelId + "|" + t + "|" + (persona == null ? "" : persona.hashCode()), k -> factory.create(p, key, m, t, persona));
  }

  // ── 熔断器：滑动窗口失败率
  static class Breaker { final Deque<Boolean> win = new ArrayDeque<>(); final Deque<Boolean> shown = new ArrayDeque<>(); long openUntil; long lastErrorAt; String lastError; Integer lastStatus; }
  private static final java.util.regex.Pattern HTTP_STATUS = java.util.regex.Pattern.compile("HTTP (\\d{3})");
  /** 失败原因：异常类型 + 脱敏后的信息，最多 max 字 */
  static String reason(Throwable e, int max) {
    String msg = com.x7x24.adapters.Redactor.redact(e == null ? "" : String.valueOf(e.getMessage())).replaceAll("\\s+", " ").strip();
    msg = msg.replaceFirst("^LLM 调用失败（已重试 \\d+ 次）: ", "");
    if (msg.isBlank() || "null".equals(msg)) msg = e == null ? "未知错误" : e.getClass().getSimpleName();
    return U.ellipsis(msg, max);
  }
  static Integer httpStatus(Throwable e) { if (e == null || e.getMessage() == null) return null; var mt = HTTP_STATUS.matcher(e.getMessage()); return mt.find() ? Integer.valueOf(mt.group(1)) : null; }
  synchronized void recordError(String modelId, Throwable e) {
    var b = breakers.computeIfAbsent(bkey(modelId), k -> new Breaker()); b.lastErrorAt = now.getAsLong(); b.lastError = reason(e, 300); b.lastStatus = httpStatus(e);
  }
  static String bkey(String modelId) { String ws = com.x7x24.tenant.Tenant.storeScope(); return (ws == null ? "" : ws + "|") + modelId; }
  public synchronized boolean open(String modelId) { var b = breakers.get(bkey(modelId)); return b != null && b.openUntil > now.getAsLong(); }
  synchronized void record(String modelId, boolean ok) {
    var c0 = config(); var bc = c0.breaker == null ? new com.x7x24.pipeline.Pipeline.Breaker() : c0.breaker;
    var b = breakers.computeIfAbsent(bkey(modelId), k -> new Breaker()); b.win.addLast(ok); while (b.win.size() > bc.window) b.win.removeFirst(); b.shown.addLast(ok); while (b.shown.size() > Math.max(bc.window, 1)) b.shown.removeFirst();
    long fails = b.win.stream().filter(x -> !x).count();
    if (b.win.size() >= Math.min(bc.window, 4) && fails >= Math.ceil(b.win.size() * bc.failureRate)) { b.openUntil = now.getAsLong() + bc.openSeconds * 1000L; b.win.clear(); log.warn("模型 {} 熔断 {} 秒", modelId, bc.openSeconds); }
  }
  public synchronized Map<String, Object> breakerStatus() { Map<String, Object> m = new LinkedHashMap<>(); String ws = com.x7x24.tenant.Tenant.storeScope(); String pre = ws == null ? "" : ws + "|";
    Set<String> registered = new HashSet<>(config().models.stream().map(x -> x.id).toList());
    breakers.forEach((k0, b) -> { if (ws == null ? k0.contains("|") : !k0.startsWith(pre)) return; String k = k0.substring(pre.length()); if (!registered.contains(k)) return; Map<String, Object> v = new LinkedHashMap<>(); v.put("open", b.openUntil > now.getAsLong()); v.put("openUntil", b.openUntil); v.put("recent", b.shown.size()); v.put("failures", b.shown.stream().filter(x -> !x).count());
      if (b.lastError != null) { v.put("lastError", b.lastError); v.put("lastErrorAt", b.lastErrorAt); if (b.lastStatus != null) v.put("lastStatus", b.lastStatus); }
      m.put(k, v); }); return m; }
  /** 当前范围内暂停使用的模型及最近错误，例如「kimi 暂停：LLM HTTP 401 …」 */
  public synchronized List<String> pausedReasons() { List<String> out = new ArrayList<>();
    breakerStatus().forEach((k, v) -> { @SuppressWarnings("unchecked") var x = (Map<String, Object>) v; if (Boolean.TRUE.equals(x.get("open"))) out.add(k + " 暂停" + (x.get("lastError") == null ? "" : "：" + U.ellipsis(String.valueOf(x.get("lastError")), 120))); }); return out; }
  /** 配置移除模型后清除当前范围的运行记录，重新接入时不沿用旧供应商的健康状态。 */
  public synchronized void forgetModels(Collection<String> models) { models.forEach(id -> breakers.remove(bkey(id))); }

  // ── 路由
  /** 角色的档位：业务线覆盖（快照 models）→ 全局角色绑定 → 默认（maker=exec，预算 soft 线降级到 budgetDowngradeProfile） */
  public String profileFor(String role, Map<String, String> lineModels, boolean cheap) {
    if (cheap && config().budgetDowngradeProfile != null && config().profiles.containsKey(config().budgetDowngradeProfile)) return config().budgetDowngradeProfile;
    if (lineModels != null && lineModels.containsKey(role) && config().profiles.containsKey(lineModels.get(role))) return lineModels.get(role);
    String base = role.contains(".") && lineModels != null && lineModels.containsKey("*") ? lineModels.get("*") : null;
    if (base != null && config().profiles.containsKey(base)) return base;
    return config().roles.getOrDefault(role, "exec");
  }
  public List<String> chain(String profile) { var p = config().profiles.get(profile); return p == null || p.chain.isEmpty() ? config().profiles.get("exec").chain : p.chain; }

  /** 执行角色的 provider（带兜底链）。pins：switch_model 钉住的模型，优先尝试。 */
  public LlmProvider maker(String role, Task t, Map<String, String> lineModels, boolean cheap) {
    String pin = pinOf(t, role);
    return new Chain(role, () -> { var C = config(); String prof = profileFor(role, lineModels, cheap); var p = C.profiles.get(prof);
      List<String> c = new ArrayList<>(chain(prof)); if (pin != null && model(pin) != null) { c.remove(pin); c.add(0, pin); }
      return new Pick(c, p == null ? null : p.temperature, null); }, Set.of());
  }
  /** 评审席位 i（最后一席兼任仲裁）。exclude：本任务执行方实际用过的模型（运行时隔离复核）。 */
  public LlmProvider seat(int i, String role, Set<String> exclude) {
    return seat(i, role, exclude, null);
  }
  public LlmProvider seat(int i, String role, Set<String> exclude, String profile) {
    return seat(config(),i,role,exclude,profile);
  }
  /**
   * 带备用模型的席位（逐行审查用）：配置的候选链之后追加 fallback 里的模型。
   * 只追加当前配置里已注册、拿得到密钥的模型；在 exclude 里（本任务执行方用过）的不追加，保持「审查避开执行模型」，此时按原候选链走。
   */
  public LlmProvider seat(int i, String role, Set<String> exclude, String profile, List<String> fallback) {
    var base = seat(config(),i,role,exclude,profile);
    if (fallback == null || fallback.isEmpty() || !(base instanceof Chain ch)) return base;
    var pick = ch.pick.get(); List<String> models = new ArrayList<>(pick.models()); Map<String,LlmProvider> bound = new HashMap<>(ch.bound);
    for (String m : fallback) {
      if (m == null || m.isBlank() || models.contains(m) || exclude.contains(m)) continue;
      var provider = instance(m, pick.temperature(), pick.persona()); if (provider == null) continue;
      models.add(m); bound.put(m, provider);
    }
    if (models.size() == pick.models().size()) return base;
    var extended = new Pick(List.copyOf(models), pick.temperature(), pick.persona());
    return new Chain(role, () -> extended, Set.copyOf(exclude), bound);
  }
  /** 逐行审查交给 ocr 用的模型端点（地址 + 密钥 + 模型名 + 单价），不经过 Java 侧的 LLM 客户端 */
  public record Endpoint(String model, String baseUrl, String apiKey, String protocol, double priceInPer1m, double priceOutPer1m) {
    @Override public String toString() { return "Endpoint[" + model + "]"; }
  }
  /**
   * 席位 i 的候选链（跳过本任务执行方用过的、熔断中的、拿不到密钥的），末尾追加 fallback 里已注册的模型（同样避开执行方用过的）。
   * 顺序就是 ocr 的尝试顺序：前一个没审完，带着会话 --resume 换下一个。
   */
  public List<Endpoint> reviewEndpoints(int i, String role, Set<String> exclude, String profile, List<String> fallback) {
    var c = config(); var pf = c.profiles.get(profile == null ? c.roles.getOrDefault(role, "judges") : profile);
    List<String> order = new ArrayList<>();
    if (pf != null) {
      if (pf.seats == null) order.addAll(pf.chain);
      else if (!pf.seats.isEmpty()) order.addAll(pf.seats.get(Math.min(i, pf.seats.size() - 1)).chain);
    }
    if (fallback != null) for (String m : fallback) if (m != null && !m.isBlank() && !order.contains(m)) order.add(m);
    List<Endpoint> out = new ArrayList<>();
    for (String id : order) {
      if (exclude.contains(id) || open(id)) continue;
      var m = model(id); if (m == null) continue; var p = provider(m.provider); if (p == null || p.baseUrl == null) continue;
      String key = apiKey(p); if (key.isBlank()) continue;
      if (p.secretRef != null && p.secretRef.startsWith("db:")) { try { UrlGuard.check(p.baseUrl, allowLocalHttp); } catch (IllegalArgumentException e) { continue; } }
      String protocol = p.type != null && p.type.toLowerCase(Locale.ROOT).contains("anthropic") ? "anthropic" : "openai";
      out.add(new Endpoint(id, p.baseUrl.trim().replaceAll("/+$", "").replaceAll("/chat/completions$", ""), key, protocol,
          m.priceInPer1m == null ? props.llm.priceInPer1m : m.priceInPer1m, m.priceOutPer1m == null ? props.llm.priceOutPer1m : m.priceOutPer1m));
    }
    return out;
  }
  private LlmProvider seat(ModelConfig c, int i, String role, Set<String> exclude, String profile) {
    var pf = c.profiles.get(profile == null ? c.roles.getOrDefault(role,"judges") : profile);
    Pick pick;
    if (pf == null) pick = new Pick(List.of(), null, null);
    else if (pf.seats == null) pick = new Pick(List.copyOf(pf.chain), pf.temperature, null);
    else if (pf.seats.isEmpty()) pick = new Pick(List.of(), null, null);
    else { var s = pf.seats.get(Math.min(i, pf.seats.size() - 1)); pick = new Pick(List.copyOf(s.chain), s.temperature, s.persona); }
    Map<String,LlmProvider> bound = new HashMap<>();
    for (String id : pick.models()) { var provider = instance(id,pick.temperature(),pick.persona()); if (provider != null) bound.put(id,provider); }
    return new Chain(role, () -> pick, Set.copyOf(exclude), bound);
  }
  public Map<String, Object> committeeStatus() {
    return committeeStatus(config());
  }
  public Map<String, Object> committeeStatus(ModelConfig c) {
    var judges = c.profiles.get("judges"); int count = judges == null || judges.seats == null ? 0 : judges.seats.size();
    List<LlmProvider> seats = new ArrayList<>(); for (int i = 0; i < count; i++) seats.add(seat(c,i,"judge-" + (i+1),Set.of(),"judges"));
    var models = diverseSeats(seats).stream().map(LlmProvider::model).toList();
    var errors = judgeModelErrors(models); return Map.of("ready",errors.isEmpty(),"models",models,"errors",errors);
  }
  /** 先保留各席位的避让偏好；全部落到同一模型时，从现有可用候选中为一席保留第二种模型。 */
  public static List<LlmProvider> diverseSeats(List<LlmProvider> seats) {
    var chosen = seats.stream().map(LlmProvider::model).filter(m -> m != null && !m.isBlank()).collect(java.util.stream.Collectors.toSet());
    if (chosen.size() >= 2) return seats;
    for (int i = 0; i < seats.size(); i++) {
      if (!(seats.get(i) instanceof ModelRouter.Chain chain)) continue;
      var pick = chain.pick.get();
      for (String id : chain.candidates(pick)) {
        if (chosen.contains(id) || chain.unavailable(id, pick)) continue;
        var order = new ArrayList<>(chain.candidates(pick)); order.remove(id); order.addFirst(id);
        var result = new ArrayList<>(seats);
        result.set(i, chain.preferred(new Pick(List.copyOf(order), pick.temperature(), pick.persona())));
        if (result.stream().map(LlmProvider::model).filter(m -> m != null && !m.isBlank()).distinct().count() >= 2) return result;
      }
    }
    return seats;
  }
  public int seatCount() { var j = config().profiles.get("judges"); return j == null || j.seats == null ? 1 : Math.max(1, j.seats.size()); }
  static String pinOf(Task t, String role) { if (t == null || t.context == null || t.context.modelPins == null) return null; var m = t.context.modelPins; return m.getOrDefault(role, m.get("*")); }

  /** switch_model：在执行档位链里选当前首选之后的下一个可用模型；没有可切的返回 null */
  public String nextModel(Task t, Map<String, String> lineModels) {
    var c = chain(profileFor("dev.code", lineModels, false)); if (c.isEmpty()) return null; String cur = pinOf(t, "*"); if (cur == null) cur = c.get(0);
    int i = c.indexOf(cur);
    for (int k = 1; k <= c.size(); k++) { String m = c.get((Math.max(i, 0) + k) % c.size()); if (!m.equals(cur) && instance(m, null, null) != null && !open(m)) return m; }
    return null;
  }

  /** 候选链 provider：按顺序尝试，跳过熔断 / 无密钥 / 隔离排除的模型；失败调用的用量累加到最终结果里 */
  record Pick(List<String> models, Double temperature, String persona) {}
  class Chain implements LlmProvider {
    final String role; final java.util.function.Supplier<Pick> pick; final Set<String> exclude; final Map<String,LlmProvider> bound;
    java.util.function.Supplier<CallListener> listeners;
    Chain(String role, java.util.function.Supplier<Pick> pick, Set<String> exclude) { this(role,pick,exclude,null); }
    Chain(String role, java.util.function.Supplier<Pick> pick, Set<String> exclude, Map<String,LlmProvider> bound) { this.role = role; this.pick = pick; this.exclude = exclude; this.bound = bound; }
    private LlmProvider providerFor(String model, Pick pick) { return bound == null ? instance(model,pick.temperature(),pick.persona()) : bound.get(model); }
    private boolean unavailable(String model, Pick pick) { return open(model) || providerFor(model,pick) == null; }
    private Chain preferred(Pick preferred) { return new Chain(role, () -> preferred, Set.of(), bound); }
    @Override public LlmProvider observing(java.util.function.Supplier<CallListener> observers) {
      var copy = new Chain(role, pick, exclude, bound); copy.listeners = observers; return copy;
    }
    @Override public String model() {
      var p = pick.get(); if (bound == null) return p.models().isEmpty() ? "" : p.models().getFirst();
      var c = candidates(p); for (int i=0;i<c.size();i++) if ((!open(c.get(i)) || i==c.size()-1) && providerFor(c.get(i),p)!=null) return c.get(i); return "";
    }
    private List<String> candidates(Pick pick) {
      List<String> candidates = new ArrayList<>(pick.models().stream().filter(m -> !exclude.contains(m)).toList());
      pick.models().stream().filter(exclude::contains).forEach(candidates::add); return candidates;
    }
    @Override public Response chat(Request req) { return chat(req, null); }
    @Override public Response chat(Request req, Validator v) { return run(req, v, null); }
    @Override public Response chatStream(Request req, Validator v, Stream s) { return run(req, v, s); }
    private Response run(Request req, Validator v, Stream s) {
      var pk = pick.get(); List<String> models = pk.models(); Double temperature = pk.temperature(); String persona = pk.persona();
      var listener = listeners == null ? null : listeners.get();
      long tok = 0; double usd = 0; RuntimeException last = null; List<String> skipped = new ArrayList<>();
      // 运行时隔离复核：优先用执行方没用过的模型；全部用过时仍按配置调用（与改造前一致），由保存时的隔离校验兜底
      List<String> cands = candidates(pk);
      for (int i = 0; i < cands.size(); i++) {
        String m = cands.get(i); boolean lastOne = i == cands.size() - 1;
        if (open(m) && !lastOne) { skipped.add(m + "(熔断)"); continue; }
        var p = providerFor(m, pk); if (p == null) { skipped.add(m + "(无密钥或地址不可用)"); continue; }
        if (listener != null) p = p.observing(() -> listener);
        try {
          if (s != null && i > 0) s.reset(); // 换模型：之前的片段作废
          var r = s != null ? p.chatStream(req, v, s) : v == null ? p.chat(req) : p.chat(req, v); record(m, true);
          if (!skipped.isEmpty() || tok > 0) log.info("角色 {} 兜底到模型 {}（跳过 {}）", role, m, skipped);
          return new Response(r.text(), r.model(), r.promptVersion(), r.tokens() + tok, U.round(r.usd() + usd, 6));
        } catch (Cancelled c) { record(m, true); throw new Cancelled(c.partial, c.model, c.tokens + tok, U.round(c.usd + usd, 6));
        } catch (com.x7x24.workflow.WorkflowStore.Blocked stopped) { if(tok>0 || usd>0)throw new OutputException("调用已被治理阻断："+stopped.getMessage(),null,tok,usd);throw stopped;
        } catch (OutputException e) { recordError(m, e); record(m, false); tok += e.tokens; usd += e.usd; last = e; skipped.add(m + "(输出不合格)"); log.warn("角色 {} 模型 {} 输出不合格：{}", role, m, reason(e, 300)); }
        catch (RuntimeException e) { recordError(m, e); record(m, false); last = e; skipped.add(m + "(调用失败: " + reason(e, 80) + ")"); log.warn("角色 {} 模型 {} 调用失败 type={} status={}：{}", role, m, e.getClass().getSimpleName(), httpStatus(e), reason(e, 300)); }
      }
      if (last instanceof OutputException oe) throw new OutputException(oe.getMessage() + (skipped.size() > 1 ? "（候选链：" + skipped + "）" : ""), oe.model, tok, usd);
      if (last != null) { String why = skipped.size() > 1 ? "（候选链：" + skipped + "）" : ""; if(tok>0 || usd>0)throw new OutputException(last.getMessage() + why,null,tok,usd); throw last; }
      if (listener != null) listener.finished(null, "FAILED");
      throw new OutputException("角色 " + role + " 没有可用模型（" + skipped + "）", null, tok, usd);
    }
  }

  /** 生产工厂：OpenAI 兼容 */
  public static Factory openAi(X7Props props, LlmGate gate) {
    return (p, key, m, t, persona) -> {
      var l = props.llm;
      var o = new OpenAiCompatProvider.Options().temperature(t).persona(persona).gate(gate)
          .retries(p.maxRetries == null ? l.maxRetries : p.maxRetries, 800, 20_000).timeouts(Duration.ofSeconds(10), Duration.ofSeconds(p.timeoutSec == null ? l.timeoutSec : p.timeoutSec))
          .price(m.priceInPer1m == null ? l.priceInPer1m : m.priceInPer1m, m.priceOutPer1m == null ? l.priceOutPer1m : m.priceOutPer1m);
      return new OpenAiCompatProvider(p.baseUrl, key, m.id, o);
    };
  }
}
