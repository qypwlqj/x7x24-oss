package com.x7x24.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.engine.ProjectRepo;
import com.x7x24.pipeline.Assembler;
import com.x7x24.pipeline.Catalog;
import com.x7x24.pipeline.Pipeline.Effective;
import com.x7x24.pipeline.PipelineService;
import com.x7x24.pipeline.Plans;
import com.x7x24.util.Json;

import java.util.*;

/**
 * C2 任务级流程计划与动态工作流编排：
 * 规则分类（结合预计改动路径、对话草稿分类与目标项目技术栈）→ 规则自动切换工作流（如后端自动切换为接口契约设计 + 后端技能 + SQL 安全检查，小需求自动极速直通）+ 录入助手提议的调整 → 治理校验 → 与模板的差异。
 * 实现 ChatService.PlanHook（确认时钉快照）和 IntakeTools.Planner（propose_pipeline 工具，只预览不持久化）。
 */
public class PlanService implements ChatService.PlanHook, IntakeTools.Planner {
  private final PipelineService pipes; private final ProjectRepo projects; private final com.x7x24.integration.RuntimeSettings settings;
  public PlanService(PipelineService pipes, ProjectRepo projects) { this(pipes, projects, null); }
  public PlanService(PipelineService pipes, ProjectRepo projects, com.x7x24.integration.RuntimeSettings settings) { this.pipes = pipes; this.projects = projects; this.settings = settings; }
  /** 治理配置（逐行审查的组装门槛）；没注入时用默认值 */
  public com.x7x24.gov.ConfigStore cfg;
  public com.x7x24.pipeline.WorkspaceNodes wsNodes; public com.x7x24.pipeline.Presets presets;
  /** 项目默认预设（P5）；没设时为 null，沿用业务线原来绑定的模板 */
  com.x7x24.pipeline.Presets.Preset presetOf(Draft d, String lineId) {
    if (presets == null || d == null || d.projectId == null || d.projectId.isBlank()) return null;
    var p = projects.get(d.projectId); var pol = p == null ? null : p.cfg().nodePolicy;
    return pol == null || pol.defaultPreset == null ? null : presets.get(pol.defaultPreset, lineId);
  }
  Map<String, com.x7x24.pipeline.Nodes.NodeDef> derived() { return wsNodes == null ? Map.of() : wsNodes.approved(); }
  boolean v2() { return settings != null && settings.pipelineV2(); }

  /** 按路径规则分类：docs / migration / infra / frontend / backend / fullstack；没有路径时返回 null */
  public static String classify(List<String> paths) {
    if (paths == null || paths.isEmpty()) return null;
    boolean fe = false, be = false, mig = false, infra = false, docs = true;
    for (String raw : paths) {
      String p = raw.toLowerCase(Locale.ROOT);
      boolean doc = p.endsWith(".md") || p.endsWith(".mdx") || p.startsWith("docs/");
      if (!doc) docs = false;
      if (p.contains("migration") || p.endsWith(".sql") || p.contains("prisma/schema") || p.contains("flyway")) mig = true;
      else if (p.startsWith(".github/workflows") || p.contains("dockerfile") || p.contains("terraform") || p.contains("helm/") || p.contains("k8s/") || p.endsWith(".tf")) infra = true;
      else if (isFrontendPath(p)) fe = true;
      else if (!doc) be = true;
    }
    // 主标签：前后端都改时是 fullstack（迁移、基础设施作为附加标签，见 labels）
    if (docs) return "docs"; if (fe && be) return "fullstack"; if (mig) return "migration"; if (infra) return "infra";
    if (fe && be) return "fullstack"; return fe ? "frontend" : "backend";
  }

  /** 判断路径列表是否包含明确的文件后缀或前后端特征目录（区分泛化目录如 src/grades/） */
  static boolean hasExplicitPathSignal(List<String> paths) {
    if (paths == null || paths.isEmpty()) return false;
    for (String raw : paths) {
      String p = raw.toLowerCase(Locale.ROOT);
      if (p.endsWith(".md") || p.endsWith(".mdx") || p.startsWith("docs/")
          || p.contains("migration") || p.endsWith(".sql") || p.contains("prisma/schema") || p.contains("flyway")
          || p.startsWith(".github/workflows") || p.contains("dockerfile") || p.contains("terraform") || p.contains("helm/") || p.contains("k8s/") || p.endsWith(".tf")
          || p.matches(".*\\.(tsx|jsx|vue|svelte|css|scss|less|html|java|go|py|rs|kt|rb|php|cs)$")
          || p.matches("(^|.*/)(web|frontend|client|ui|components|pages|server|backend|api|controllers?|services?|repositories|dao|pkg|cmd|internal)/.*")) return true;
    }
    return false;
  }

  /** 多标签分类（决策：P0 前置修复 ①）：按路径同时标出 docs / migration / infra / frontend / backend，不再互相吞掉 */
  public static List<String> labels(List<String> paths) { return labels(paths, null); }
  /** 有技术栈画像时：路径先按画像模块（最长前缀）归栈，再落到标签；没匹配到的路径按后缀规则 */
  public static List<String> labels(List<String> paths, com.x7x24.engine.ProjectCfg.StackProfile prof) {
    if (paths == null || paths.isEmpty()) return List.of();
    boolean fe = false, be = false, mig = false, infra = false, docs = true;
    for (String raw : paths) {
      String p = raw.toLowerCase(Locale.ROOT);
      boolean doc = p.endsWith(".md") || p.endsWith(".mdx") || p.startsWith("docs/");
      if (!doc) docs = false;
      if (p.contains("migration") || p.endsWith(".sql") || p.contains("prisma/schema") || p.contains("flyway")) { mig = true; continue; }
      if (p.startsWith(".github/workflows") || p.contains("dockerfile") || p.contains("terraform") || p.contains("helm/") || p.contains("k8s/") || p.endsWith(".tf")) { infra = true; continue; }
      if (doc) continue;
      // 后缀明确的文件（.tsx/.vue/.css 或 .go/.java/.py）按后缀；.ts/.js 这类两边都可能的按画像目录归属
      String lb = p.matches(".*\\.(tsx|jsx|vue|svelte|css|scss|less|html)$") ? "frontend" : p.matches(".*\\.(go|java|kt|py|rb|rs|php|cs)$") ? "backend" : null;
      if (lb == null) { String st = com.x7x24.pipeline.StackProfiles.stackOfPath(prof, raw); lb = st == null ? null : com.x7x24.pipeline.StackProfiles.LABEL.get(st); }
      if ("frontend".equals(lb)) fe = true; else if ("backend".equals(lb)) be = true; else if ("migration".equals(lb)) mig = true;
      else if (isFrontendPath(p)) fe = true; else be = true;
    }
    if (docs) return List.of("docs");
    List<String> l = new ArrayList<>();
    if (fe) l.add("frontend"); if (be) l.add("backend"); if (mig) l.add("migration"); if (infra) l.add("infra");
    return l;
  }
  /** 路径涉及的技术栈（画像模块匹配 + 后缀推断），用来给编码节点挂技能 */
  public static List<String> stacksOf(List<String> paths, com.x7x24.engine.ProjectCfg.StackProfile prof) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String raw : paths == null ? List.<String>of() : paths) {
      String p = raw.toLowerCase(Locale.ROOT); String st = com.x7x24.pipeline.StackProfiles.stackOfPath(prof, raw);
      if (st != null && (p.endsWith(".ts") || p.endsWith(".js") || p.endsWith(".mjs")) && !List.of("node", "react", "vue", "web").contains(st)) st = null;
      if (st != null && p.endsWith(".md") && !"docs".equals(st)) st = null;
      if (p.endsWith(".go")) st = "go"; else if (p.endsWith(".java") || p.endsWith(".kt")) st = "java"; else if (p.endsWith(".py")) st = "python";
      else if (p.endsWith(".tsx") || p.endsWith(".jsx")) st = "react"; else if (p.endsWith(".vue")) st = "vue";
      else if (p.contains("migration") || p.endsWith(".sql")) st = "sql";
      if (st != null) out.add(st);
    }
    return new ArrayList<>(out);
  }
  static boolean isFrontendPath(String p) {
    return p.matches(".*\\.(tsx|jsx|vue|svelte|css|scss|less|html)$") || p.matches("(^|.*/)(web|frontend|client|ui|components|pages|views|app/\\(.*\\))/.*");
  }
  /** 由主分类（助手给出或项目技术栈推断）展开成标签 */
  static List<String> expand(String cls) {
    if (cls == null || cls.isBlank()) return List.of();
    return switch (cls.trim().toLowerCase(Locale.ROOT)) {
      case "fullstack" -> List.of("frontend", "backend");
      case "frontend", "backend", "docs", "migration", "infra" -> List.of(cls.trim().toLowerCase(Locale.ROOT));
      default -> List.of();
    };
  }
  /** 草稿的标签：有明确路径信号时按路径；否则按助手分类 / 项目技术栈；助手分类里的 migration 等会补进路径标签 */
  List<String> labelsOf(Draft d, String primary) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    List<String> byPath = labels(d == null ? null : d.targetPaths, profileOf(d));
    if (!byPath.isEmpty() && (hasExplicitPathSignal(d.targetPaths) || d.classification == null || d.classification.isBlank())) out.addAll(byPath);
    else out.addAll(expand(primary));
    if (d != null && "migration".equalsIgnoreCase(d.classification == null ? "" : d.classification.trim())) out.add("migration");
    if (out.contains("docs") && out.size() > 1) out.remove("docs");
    return new ArrayList<>(out);
  }

  static final java.util.regex.Pattern SENSITIVE = java.util.regex.Pattern.compile(
      "(?i)(auth|login|oauth|session|permission|rbac|acl|role|secret|token|password|passwd|credential|api[-_ ]?key|apikey|private[-_ ]?key|payment|billing|refund|pii|"
      + "鉴权|认证|登录|权限|角色|密钥|秘钥|令牌|密码|凭据|凭证|支付|退款|计费|个人信息|隐私|敏感)");
  /** 治理必选：风险 ≥ 中，或路径 / 描述涉及鉴权、权限、密钥、支付、个人数据 → 必须做安全审计（决策 D4） */
  public static boolean needsSecurityAudit(Draft d) {
    if (d == null) return false;
    String r = d.risk == null ? "" : d.risk.trim().toLowerCase(Locale.ROOT);
    if (r.equals("mid") || r.equals("medium") || r.equals("high")) return true;
    StringBuilder b = new StringBuilder();
    if (d.title != null) b.append(d.title).append(' '); if (d.goal != null) b.append(d.goal).append(' ');
    if (d.scope != null) b.append(String.join(" ", d.scope)).append(' ');
    if (d.targetPaths != null) b.append(String.join(" ", d.targetPaths));
    return SENSITIVE.matcher(b).find();
  }

  /** 规则基线（均为收紧或专业化项，自动生效且无需审批）：按标签叠加，不依赖模型 */
  static List<Plans.Adj> ruleAdjustments(String cls, List<String> paths) { return ruleAdjustments(expandOrLabels(cls, paths), paths, null, null); }
  private static List<String> expandOrLabels(String cls, List<String> paths) { var l = labels(paths); return l.isEmpty() ? expand(cls) : l; }
  static List<Plans.Adj> ruleAdjustments(List<String> labels, List<String> paths, Draft d) { return ruleAdjustments(labels, paths, d, null); }
  static List<Plans.Adj> ruleAdjustments(List<String> labels, List<String> paths, Draft d, List<String> stacks) {
    List<Plans.Adj> l = new ArrayList<>();
    Set<String> lb = new LinkedHashSet<>(labels == null ? List.of() : labels);
    List<String> ps = paths == null ? List.of() : paths.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
    boolean fe = lb.contains("frontend"), be = lb.contains("backend"), mig = lb.contains("migration"), infra = lb.contains("infra"), docs = lb.contains("docs") && lb.size() == 1;
    if (docs) {
      l.add(adj("addCheck", "QA", "markdown-lint", "文档类改动：加 Markdown 检查"));
      l.add(bind(List.of("docs-style@1"), "文档类改动：挂载文档写作规范"));
    }
    // 可选步骤在组装时一次决定（需要 → 强制执行；不需要 → 不组装），不再留给运行时跳过
    Boolean ux = uxDesignNeeded(lb, ps, d), spec = specNeeded(lb, ps, d);
    String apiStage = null;
    if (be && !fe) {
      var rep = adj("replaceStage", "DESIGN", null, "后端需求：将体验设计切换为接口契约设计"); rep.targetStage = "API_DESIGN"; rep.label = "接口契约设计"; l.add(rep); apiStage = "DESIGN";
    } else if (be) {
      if (!Boolean.FALSE.equals(ux)) l.add(adj("enable", "DESIGN", null, "前后端都要改，且涉及界面：保留体验设计"));
      var ins = adj("insertStage", "API_DESIGN", null, "前后端都要改：先对齐接口契约"); ins.after = "PLANNING"; ins.label = "接口契约设计"; l.add(ins); apiStage = "API_DESIGN";
    }
    if (mig) {
      var ins = adj("insertStage", "DB_SCHEMA", null, "数据迁移：先做数据库变更设计（表结构、回滚方案、锁表影响）"); ins.after = apiStage != null ? apiStage : "PLANNING"; ins.label = "数据库变更设计"; l.add(ins);
    }
    if (be || mig) l.add(adj("addCheck", "QA", "sql-safety-lint", mig ? "数据迁移：附加 SQL 安全检查" : "后端需求：附加 SQL 安全检查"));
    if (infra) l.add(adj("enable", "DESIGN", null, "基础设施改动：先出变更方案"));
    else if (!(be && !fe)) {
      if (Boolean.TRUE.equals(ux) && !(fe && be)) l.add(adj("enable", "DESIGN", null, "涉及页面 / 组件 / 样式改动：需要体验设计"));
      if (Boolean.FALSE.equals(ux)) l.add(adj("disable", "DESIGN", null, uxSkipReason(lb, ps, d)));
    }
    if (Boolean.TRUE.equals(spec)) l.add(adj("enable", "SPEC", null, "规模较大：先做需求策划（拆用例）"));
    if (Boolean.FALSE.equals(spec)) l.add(adj("disable", "SPEC", null, contentOnly(lb, ps, d) ? "只改文案 / 翻译 / 文档：不需要需求策划" : "小规模需求：验收条件已在对话中确认，不需要需求策划"));
    // 编码节点按技术栈挂技能（决策 D1：一个编码节点挂多个栈的技能）
    LinkedHashSet<String> sk = new LinkedHashSet<>();
    List<String> st = stacks != null ? stacks : stacksOf(paths, null);
    for (String x : List.of("go", "java", "python")) if (st.contains(x)) sk.add(com.x7x24.pipeline.StackProfiles.SKILL.get(x));
    if (be && sk.isEmpty() && !st.contains("node") && ps.stream().noneMatch(p -> p.endsWith(".ts") || p.endsWith(".js") || p.endsWith(".rs") || p.endsWith(".rb") || p.endsWith(".php") || p.endsWith(".cs"))) sk.add("java-spring@1");
    for (String x : List.of("react", "vue")) if (st.contains(x)) sk.add(com.x7x24.pipeline.StackProfiles.SKILL.get(x));
    if (be) sk.add("api-contract@1");
    if (mig) sk.add("sql-migration@1");
    if (!sk.isEmpty()) l.add(bind(new ArrayList<>(sk), "按技术栈挂载编码规范"));
    if (ps.stream().anyMatch(p -> p.matches(".*(locales?|i18n|lang)/.*")))
      l.add(adj("addCheck", "QA", "i18n-keys-complete", "涉及多语言文件：检查翻译 key 完整"));
    if (needsSecurityAudit(d)) {
      var sec = adj("insertStage", "SEC_AUDIT", null, "涉及风险中及以上，或鉴权 / 权限 / 密钥 / 支付 / 个人数据：测试后做安全审计"); sec.after = "QA"; sec.label = "安全审计"; sec.source = "governance"; l.add(sec);
    }
    return l;
  }
  static final java.util.regex.Pattern UI_FILE = java.util.regex.Pattern.compile(".*\\.(tsx|jsx|vue|svelte|css|scss|less|sass|html)$");
  static final java.util.regex.Pattern I18N_FILE = java.util.regex.Pattern.compile(".*((^|/)(locales?|i18n|lang|langs|translations?)/|i18n|locale|language|lang[-_.]|translation).*");
  static final java.util.regex.Pattern TEST_FILE = java.util.regex.Pattern.compile(".*(\\.(test|spec)\\.|__tests__/|(^|/)tests?/|(^|/)e2e/).*");
  static final java.util.regex.Pattern I18N_TEXT = java.util.regex.Pattern.compile("(?i)(i18n|l10n|多语言|多語言|翻译|翻譯|语言包|語言包|本地化|日语|日文|英文|japanese|translation|locale)");
  static final java.util.regex.Pattern UI_TEXT = java.util.regex.Pattern.compile("(?i)(新页面|新增页面|页面布局|界面改版|改版|交互|布局|样式|视觉|配色|新组件|弹窗|表单|列表页|详情页|看板|redesign|layout|ux|ui design)");
  static String text(Draft d) { if (d == null) return ""; StringBuilder b = new StringBuilder(); if (d.title != null) b.append(d.title).append(' '); if (d.goal != null) b.append(d.goal).append(' '); if (d.scope != null) b.append(String.join(" ", d.scope)); return b.toString(); }
  /** 只改文案 / 翻译 / 文档 / 测试，不动界面结构 */
  static boolean contentOnly(Set<String> lb, List<String> ps, Draft d) {
    if (lb.contains("docs") && lb.size() == 1) return true;
    boolean i18nText = I18N_TEXT.matcher(text(d)).find() && !UI_TEXT.matcher(text(d)).find();
    if (ps.isEmpty()) return i18nText;
    return ps.stream().allMatch(p -> I18N_FILE.matcher(p).matches() || TEST_FILE.matcher(p).matches() || p.endsWith(".md") || p.endsWith(".json") || p.endsWith(".yaml") || p.endsWith(".yml") || i18nText && !UI_FILE.matcher(p).matches());
  }
  /** 体验设计是否需要：true = 涉及页面 / 组件 / 样式；false = 只改翻译、数据、文档、测试或纯后端 / 迁移；null = 信息不足，交给规划器 */
  static Boolean uxDesignNeeded(Set<String> lb, List<String> ps, Draft d) {
    if (lb.contains("infra")) return true;
    boolean fe = lb.contains("frontend");
    if (lb.isEmpty() && ps.isEmpty()) return null;
    if (!fe) return false;
    if (contentOnly(lb, ps, d)) return false;
    boolean ui = ps.stream().anyMatch(p -> UI_FILE.matcher(p).matches() && !I18N_FILE.matcher(p).matches() && !TEST_FILE.matcher(p).matches());
    if (ui || UI_TEXT.matcher(text(d)).find()) return true;
    return ps.isEmpty() ? null : false;
  }
  static String uxSkipReason(Set<String> lb, List<String> ps, Draft d) {
    if (contentOnly(lb, ps, d)) return "只改翻译 / 文案 / 数据 / 文档：不涉及界面结构，不需要体验设计";
    if (!lb.contains("frontend")) return "不涉及前端界面：不需要体验设计";
    return "改动不涉及页面、组件或样式：不需要体验设计";
  }
  /** 需求策划是否需要：规模 L 及以上、或 M 且涉及代码改动 → 需要；XS / S 或只改内容 → 不需要；其余交给规划器 */
  static Boolean specNeeded(Set<String> lb, List<String> ps, Draft d) {
    if (d == null || d.size == null) return contentOnly(lb, ps, d) && !(lb.isEmpty() && ps.isEmpty()) ? false : null;
    String sz = d.size.trim().toUpperCase(Locale.ROOT);
    if (contentOnly(lb, ps, d)) return false;
    if (sz.equals("XS") || sz.equals("S")) return false;
    if (sz.equals("L") || sz.equals("XL")) return true;
    return null;
  }
  private static Plans.Adj adj(String op, String stage, String check, String reason) { var a = new Plans.Adj(); a.op = op; a.stage = stage; a.check = check; a.reason = reason; a.source = "rule"; return a; }
  private static Plans.Adj bind(List<String> skills, String reason) { var a = adj("bindSkills", "DEV", null, reason); a.skills = skills; return a; }

  /** 规则基线 + 小规模低风险极速通道 + 助手提议（同一 op/stage/check 只保留规则那条） */
  List<Plans.Adj> adjustments(Draft d, String cls) {
    var prof = profileOf(d);
    List<Plans.Adj> out = new ArrayList<>(ruleAdjustments(labelsOf(d, cls), d == null ? null : d.targetPaths, d, stacksOf(d == null ? null : d.targetPaths, prof)));
    if (d != null && ("S".equalsIgnoreCase(d.size) || "XS".equalsIgnoreCase(d.size)) && "low".equalsIgnoreCase(d.risk)) {
      out.add(adj("fastTrack", "PRD_REVIEW", null, "小规模低风险需求：已在对话中确认验收条件，直通规划与开发"));
    }
    Set<String> seen = new HashSet<>();
    for (var a : out) {
      seen.add(a.op + "|" + a.stage + "|" + a.check + "|" + a.targetStage);
      if ("replaceStage".equals(a.op) && a.targetStage != null) seen.add("insertStage|" + a.targetStage + "|null|null");
    }
    if (d != null && d.adjustments != null && d.adjustments.isArray()) for (JsonNode n : d.adjustments) {
      Plans.Adj a; try { a = Json.M.treeToValue(n, Plans.Adj.class); } catch (Exception ex) { continue; }
      if (a == null || !seen.add(a.op + "|" + a.stage + "|" + a.check + "|" + a.targetStage)) continue;
      // 规则已插入同名节点时，助手的同名 insertStage 视为重复
      if ("insertStage".equals(a.op) && out.stream().anyMatch(x -> "insertStage".equals(x.op) && Objects.equals(x.stage, a.stage))) continue;
      // 若助手对同一节点显式提出了 replaceStage，覆盖默认规则里的同节点 replaceStage
      if ("replaceStage".equals(a.op) && a.stage != null) out.removeIf(x -> "rule".equals(x.source) && "replaceStage".equals(x.op) && a.stage.equals(x.stage));
      a.source = "agent"; a.summary = null; a.relax = null; a.rejected = null; a.warning = null; out.add(a);
    }
    return out;
  }

  com.x7x24.engine.ProjectCfg.StackProfile profileOf(Draft d) {
    if (d == null || d.projectId == null || d.projectId.isBlank()) return null;
    var p = projects.get(d.projectId); return p == null ? null : com.x7x24.pipeline.StackProfiles.of(p.cfg());
  }
  /** 主分类：① 具有明确后缀/目录信号的路径分类 → ② 对话草稿显式分类 → ③ 目标项目技术栈分类 */
  String cls(Draft d) {
    if (d == null) return null;
    String byPath = classify(d.targetPaths);
    if (byPath != null && (hasExplicitPathSignal(d.targetPaths) || d.classification == null || d.classification.isBlank())) return byPath;
    if (d.classification != null && !d.classification.isBlank()) return d.classification.trim().toLowerCase(Locale.ROOT);
    if (d.projectId != null && !d.projectId.isBlank()) {
      var p = projects.get(d.projectId);
      if (p != null && p.cfg() != null) {
        String tech = (p.cfg().technology == null ? "" : p.cfg().technology).toLowerCase(Locale.ROOT);
        boolean hasBe = tech.contains("java") || tech.contains("spring") || tech.contains("go") || tech.contains("python") || tech.contains("rust")
            || (p.cfg().modules != null && p.cfg().modules.stream().anyMatch(m -> m.language != null && m.language.toLowerCase(Locale.ROOT).matches(".*(java|go|python|rust).*")));
        boolean hasFe = tech.contains("react") || tech.contains("vue") || tech.contains("frontend")
            || (p.cfg().modules != null && p.cfg().modules.stream().anyMatch(m -> m.language != null && m.language.toLowerCase(Locale.ROOT).matches(".*(tsx|jsx|vue|html|css).*")));
        if (hasBe && !hasFe) return "backend";
        if (hasBe && hasFe) return "fullstack";
      }
    }
    return byPath;
  }

  /** 给界面看的计划：节点列表（包含内置节点与动态插入/替换的自定义节点）+ 每条调整的结果 */
  public static Map<String, Object> view(Effective e, Plans.Plan plan, String snapshotId) {
    Map<String, Object> m = new LinkedHashMap<>(); m.put("template", e.template); m.put("preset", plan == null ? null : plan.preset); m.put("classification", plan == null ? null : plan.classification); m.put("labels", plan == null || plan.labels == null ? List.of() : plan.labels);
    List<Map<String, Object>> st = new ArrayList<>();
    for (var en : e.stages.entrySet()) {
      String type = en.getKey(); var s = en.getValue();
      Map<String, Object> x = new LinkedHashMap<>();
      x.put("type", type);
      x.put("label", s != null && s.label != null ? s.label : Catalog.stageLabel(type));
      x.put("on", s == null || s.on());
      x.put("changed", String.valueOf(e.sources.get("stages." + type)).startsWith("plan:"));
      x.put("custom", Catalog.stageType(type) == null);
      if (s != null && s.replacedFrom != null) x.put("replacedFrom", s.replacedFrom);
      if (s != null && s.skills != null && !s.skills.isEmpty()) x.put("skills", s.skills);
      x.put("checks", s == null || s.checks == null ? List.of() : s.checks);
      x.put("locked", s != null && Boolean.TRUE.equals(s.locked));
      x.put("required", s != null && Boolean.TRUE.equals(s.required));
      st.add(x);
    }
    m.put("stages", st); m.put("adjustments", plan == null ? List.of() : plan.items); m.put("needsApproval", plan != null && plan.needsApproval);
    m.put("violations", plan == null ? List.of() : plan.violations()); m.put("snapshotId", snapshotId);
    m.put("schema", e.schema == null ? 1 : e.schema); m.put("graph", Assembler.graphOf(e, plan));
    m.put("warnings", plan == null || plan.warnings == null ? List.of() : plan.warnings);
    return m;
  }

  static final java.util.regex.Pattern API_FILE = java.util.regex.Pattern.compile("(?i).*(handler|controller|router|routes?|openapi|swagger|\\.proto$|/api/).*");
  /** 治理必选节点（§5 / 决策 D4）：节点 id → 原因 */
  /** 治理必选节点（只读，供测试与诊断） */
  public Map<String, String> mandatoryFor(Draft d, List<String> labels) { return mandatory(d, labels); }
  Map<String, String> mandatory(Draft d, List<String> labels) {
    Map<String, String> m = new LinkedHashMap<>();
    if (labels.contains("migration")) { m.put("DB_SCHEMA", "涉及数据迁移：必须做数据库变更设计"); m.put("CHECK_SQL_SAFETY", "涉及数据迁移：必须做 SQL 安全检查"); }
    if (needsSecurityAudit(d)) m.put("SEC_AUDIT", "风险 ≥ 中，或涉及鉴权 / 权限 / 密钥 / 支付 / 个人数据：必须做安全审计");
    if (labels.contains("backend") && d != null && d.targetPaths != null && d.targetPaths.stream().anyMatch(p -> API_FILE.matcher(p).matches())) m.put("API_DESIGN", "后端改动涉及接口文件：必须做接口契约设计");
    if (d != null && !(labels.contains("docs") && labels.size() == 1)) {
      String cr = com.x7x24.review.CodeReviewGate.reason(d.size, d.risk, cfg == null ? null : cfg.get());
      if (cr != null) m.put(com.x7x24.review.CodeReviewGate.NODE, cr);
    }
    return m;
  }
  Assembler.Ctx ctx(Draft d, List<String> labels) {
    var p = d == null || d.projectId == null || d.projectId.isBlank() ? null : projects.get(d.projectId);
    var cfg = p == null ? null : p.cfg();
    return new Assembler.Ctx(labels, stacksOf(d == null ? null : d.targetPaths, cfg == null ? null : com.x7x24.pipeline.StackProfiles.of(cfg)), cfg, mandatory(d, labels), d == null ? null : d.risk, cfg == null ? null : cfg.autonomy, derived());
  }
  @Override public Pinned pin(Draft d, String lineId, String projectId) {
    String c = cls(d); var lb = labelsOf(d, c); var r = pipes.assemble(lineId, adjustments(d, c), c, ctx(d, lb), true, v2(), presetOf(d, lineId));
    return new Pinned(r.eff().id, r.plan().needsApproval, view(r.eff(), r.plan(), r.eff().id), r.plan().violations());
  }
  @Override public Object preview(Draft d, String lineId) {
    String c = cls(d); var lb = labelsOf(d, c); var r = pipes.assemble(lineId, adjustments(d, c), c, ctx(d, lb), false, true, v2() ? presetOf(d, lineId) : null); return view(r.eff(), r.plan(), null);
  }
  /** propose_pipeline 工具：助手可以带 adjustments 试算；结果只预览，真正生效要写进草稿并由用户确认 */
  @Override public Object propose(Draft d, JsonNode args) {
    if (d.projectId == null) return Map.of("error", "先确定目标项目");
    var p = projects.get(d.projectId); if (p == null) return Map.of("error", "项目不存在或不在当前工作区");
    Draft tmp = Json.convert(d, Draft.class);
    if (args != null && args.has("adjustments")) tmp.adjustments = args.get("adjustments");
    if (args != null && args.hasNonNull("classification")) tmp.classification = args.get("classification").asText();
    var v = (Map<?, ?>) preview(tmp, p.productLineId());
    Map<String, Object> out = new LinkedHashMap<>(); out.put("classification", v.get("classification")); out.put("template", v.get("template"));
    out.put("stages", v.get("stages")); out.put("adjustments", v.get("adjustments")); out.put("needsApproval", v.get("needsApproval"));
    out.put("allowed", Map.of(
        "ops", Plans.OPS,
        "checks", Catalog.CHECKS.keySet(),
        "skills", pipes.skills().keySet(),
        "stagePresets", Catalog.STAGE_PRESETS.keySet(),
        "optionalStages", List.of("SPEC", "DESIGN"),
        "fastTrackStages", Plans.FAST_TRACKABLE,
        "replaceableStages", Plans.REPLACEABLE,
        "params", Plans.PARAMS,
        "nodes", com.x7x24.pipeline.Nodes.view(p.cfg()).stream().filter(n -> Boolean.TRUE.equals(n.get("allowed")))
            .map(n -> Map.of("id", n.get("id"), "label", n.get("label"), "family", n.get("family"), "executor", n.get("executor"), "anchorPhase", String.valueOf(n.get("anchorPhase")))).toList(),
        "workspaceNodes", derived().values().stream().map(n -> Map.of("id", n.id, "label", n.label, "executor", n.executor, "description", n.description)).toList()));
    out.put("graph", v.get("graph")); out.put("warnings", v.get("warnings"));
    out.put("hint", "满意的工作流调整请写进 draft.adjustments；支持 fastTrack（小需求跳过 PRD_REVIEW/PLANNING）、replaceStage（如将 PRD_REVIEW 或 DESIGN 替换为 API_DESIGN 接口契约设计）、insertStage（从 allowed.nodes 里选目录节点插入，例如 API_DESIGN / DB_SCHEMA / ARCH_REVIEW / SEC_AUDIT；目录外的自定义节点需要审批）、removeStage、bindSkills（动态绑定 java-spring@1 等技能）。含放松项（关可选节点 / 去检查 / 放宽参数 / 换角色）需管理员审批");
    return out;
  }
}
