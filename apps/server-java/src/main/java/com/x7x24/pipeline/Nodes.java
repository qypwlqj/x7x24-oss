package com.x7x24.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.x7x24.engine.ProjectCfg;

import java.util.*;

/**
 * 节点目录（可组合流程 P1）：覆盖全部可能节点。感知、交付模块通用；生产模块按技术栈区分。
 * 每个节点映射到引擎里的一个阶段键（stage）：内置阶段直接复用，专属节点（接口契约设计等）是挂在某个阶段上的自定义节点。
 * available=false 的节点已列入目录，但执行器尚未提供（原 C3 / C4），组装时不能选。
 */
public final class Nodes {
  private Nodes() {}

  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Cost { public int llmCalls; public double usd; public double minutes; public Cost() {} public Cost(int c, double u, double m) { llmCalls = c; usd = u; minutes = m; } }

  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class NodeDef {
    public String id; public int version = 1; public String label, description;
    /** sensing | production | delivery */
    public String module;
    /** 看板列族：sensing | design | build | check | delivery */
    public String family;
    /** agent | review | check | system */
    public String executor;
    /** 引擎里的阶段键：内置阶段（PRD_REVIEW / DESIGN / DEV / QA …）或自定义节点 id */
    public String stage;
    /** 自定义节点挂在哪个内置阶段上执行（DESIGN / ACCEPTANCE），内置阶段为 null */
    public String anchorPhase;
    public List<String> inputs = List.of(), outputs = List.of(), requires = List.of(), before = List.of();
    /** 适用技术栈；含 "*" = 通用 */
    public List<String> stacks = List.of("*");
    /** 规则基线：命中这些分类标签时自动加入 */
    public List<String> triggerLabels = List.of();
    public List<String> skills = List.of();
    /** 检查类节点对应的 QA 检查 id */
    public String check;
    /** 去掉这个节点的风险：none | low | mid | high（决定放松是否需要审批） */
    public String risk = "low";
    public Cost cost = new Cost();
    /** 治理必选规则：always | migration | security | backend-api | null */
    public String mandatory;
    /** 可选节点（规划器 / 组装可以不选） */
    public boolean optional = true;
    public boolean available = true;
    public String key() { return id + "@" + version; }
  }

  public static final Map<String, NodeDef> NODES = new LinkedHashMap<>();
  private static NodeDef n(String id, String label, String module, String family, String executor, String stage, String desc) {
    var d = new NodeDef(); d.id = id; d.label = label; d.module = module; d.family = family; d.executor = executor; d.stage = stage; d.description = desc; NODES.put(id, d); return d;
  }
  static {
    var BE = List.of("go", "java", "python", "node"); var FE = List.of("react", "vue", "web");
    var x = n("SIGNAL_SCAN", "信号扫描", "sensing", "sensing", "system", null, "扫描仓库与 Issue，发现候选需求"); x.outputs = List.of("signal"); x.risk = "none";
    x = n("INTAKE", "需求收录", "sensing", "sensing", "system", "INTAKE", "对话录入或手动建任务"); x.optional = false; x.outputs = List.of("draft"); x.mandatory = "always";
    x = n("PRD_REVIEW", "PRD 评审", "sensing", "sensing", "agent", "PRD_REVIEW", "起草 PRD 并由评审组打分；小规模低风险需求可走极速通道"); x.optional = false; x.inputs = List.of("draft"); x.outputs = List.of("prd"); x.mandatory = "always"; x.risk = "mid"; x.cost = new Cost(4, 0.02, 2);
    x = n("PLANNING", "任务规划", "sensing", "sensing", "agent", "PLANNING", "在已组装的流程内拆分子任务、估算规模"); x.optional = false; x.inputs = List.of("prd"); x.outputs = List.of("plan"); x.requires = List.of("PRD_REVIEW"); x.mandatory = "always"; x.cost = new Cost(1, 0.01, 1);
    x = n("SPEC", "需求策划", "production", "design", "agent", "SPEC", "把验收条件写成可执行的验收用例"); x.inputs = List.of("prd"); x.outputs = List.of("acceptance_cases"); x.requires = List.of("PLANNING"); x.before = List.of("DEV"); x.cost = new Cost(1, 0.01, 1);
    x = n("API_DESIGN", "接口契约设计", "production", "design", "agent", "API_DESIGN", "定义接口路径、出入参、错误码与兼容性"); x.anchorPhase = "DESIGN"; x.stacks = BE; x.triggerLabels = List.of("backend"); x.skills = List.of("api-contract@1");
    x.inputs = List.of("prd"); x.outputs = List.of("apiContract"); x.requires = List.of("PLANNING"); x.before = List.of("DEV"); x.mandatory = "backend-api"; x.cost = new Cost(2, 0.02, 2);
    x = n("DB_SCHEMA", "数据库变更设计", "production", "design", "agent", "DB_SCHEMA", "表结构、迁移脚本、回滚方案与锁表风险"); x.anchorPhase = "DESIGN"; x.stacks = List.of("sql", "go", "java", "python", "node"); x.triggerLabels = List.of("migration"); x.skills = List.of("sql-migration@1");
    x.inputs = List.of("prd"); x.outputs = List.of("dbSchema"); x.requires = List.of("PLANNING"); x.before = List.of("DEV"); x.mandatory = "migration"; x.risk = "mid"; x.cost = new Cost(2, 0.02, 2);
    x = n("UX_DESIGN", "体验设计", "production", "design", "agent", "DESIGN", "页面结构、交互与文案"); x.stacks = FE; x.triggerLabels = List.of("frontend"); x.inputs = List.of("prd"); x.outputs = List.of("design"); x.requires = List.of("PLANNING"); x.before = List.of("DEV"); x.cost = new Cost(2, 0.02, 2);
    x = n("ARCH_REVIEW", "架构评审", "production", "design", "agent", "ARCH_REVIEW", "跨模块或大改动时先出架构方案并评审"); x.anchorPhase = "DESIGN"; x.inputs = List.of("prd"); x.outputs = List.of("archReview"); x.requires = List.of("PLANNING"); x.before = List.of("DEV"); x.cost = new Cost(2, 0.02, 2);
    x = n("DEV", "编码", "production", "build", "agent", "DEV", "按栈挂技能编码，提交分支并开 PR（同一 PR）"); x.optional = false; x.inputs = List.of("plan"); x.outputs = List.of("pr"); x.requires = List.of("PLANNING"); x.mandatory = "always"; x.risk = "high"; x.cost = new Cost(4, 0.08, 5);
    x = n("CHECK_SQL_SAFETY", "SQL 安全检查", "production", "check", "check", "QA", "拦截无条件 DROP TABLE / TRUNCATE 等高危语句"); x.check = "sql-safety-lint"; x.stacks = List.of("sql", "go", "java", "python", "node"); x.triggerLabels = List.of("backend", "migration"); x.requires = List.of("DEV"); x.mandatory = "migration";
    x = n("CHECK_I18N", "多语言检查", "production", "check", "check", "QA", "翻译 JSON 合法，各语言键集合一致"); x.check = "i18n-keys-complete"; x.stacks = FE; x.requires = List.of("DEV");
    x = n("CHECK_MD", "Markdown 检查", "production", "check", "check", "QA", "无空链接、标题层级不跳级"); x.check = "markdown-lint"; x.stacks = List.of("*"); x.triggerLabels = List.of("docs"); x.requires = List.of("DEV");
    x = n("CHECK_MIGRATION_REVERSIBLE", "迁移可回滚检查", "production", "check", "check", "QA", "迁移脚本 up-down-up 可逆（需要 CI 型检查执行器）"); x.stacks = List.of("sql"); x.available = false;
    x = n("CHECK_OPENAPI_DIFF", "接口兼容性检查", "production", "check", "check", "QA", "对比 OpenAPI 定义，拦截破坏性变更（规划中）"); x.stacks = BE; x.available = false;
    x = n("QA", "测试", "delivery", "delivery", "system", "QA", "CI（必选，ci 检查不能去掉）加附加检查"); x.optional = false; x.inputs = List.of("pr"); x.outputs = List.of("ci_log", "test_report"); x.requires = List.of("DEV"); x.mandatory = "always"; x.risk = "high"; x.cost = new Cost(0, 0, 8);
    x = n("CODE_REVIEW", "逐行审查", "delivery", "delivery", "review", "CODE_REVIEW", "用 open-code-review 逐行审查 PR 的改动，给出带行号的问题"); x.anchorPhase = "ACCEPTANCE"; x.skills = List.of("ocr-review@1"); x.inputs = List.of("pr", "test_report"); x.outputs = List.of("codeReview"); x.requires = List.of("QA"); x.before = List.of("ACCEPTANCE"); x.mandatory = "code-review"; x.risk = "mid"; x.cost = new Cost(2, 0.02, 2);
    x = n("UI_ACCEPTANCE", "页面验收", "delivery", "delivery", "review", "UI_ACCEPTANCE", "在干净容器里从 PR 分支起应用，跑验收脚本、截关键页面，由独立判定 agent 对照验收标准给结论（需要 agent runner）"); x.anchorPhase = "ACCEPTANCE"; x.stacks = FE; x.triggerLabels = List.of("frontend", "fullstack");
    x.inputs = List.of("pr", "test_report"); x.outputs = List.of("ui_report"); x.requires = List.of("QA"); x.before = List.of("ACCEPTANCE"); x.risk = "low"; x.cost = new Cost(2, 0.03, 15); x.available = false;
    x = n("SEC_AUDIT", "安全审计", "delivery", "delivery", "review", "SEC_AUDIT", "越权、注入、敏感数据、密钥与依赖风险评审"); x.anchorPhase = "ACCEPTANCE"; x.skills = List.of("sec-review@1"); x.inputs = List.of("pr", "test_report"); x.outputs = List.of("secAudit"); x.requires = List.of("QA"); x.before = List.of("ACCEPTANCE"); x.mandatory = "security"; x.risk = "high"; x.cost = new Cost(3, 0.03, 2);
    x = n("PERF_BUDGET", "性能预算检查", "delivery", "delivery", "check", "QA", "接口延迟 / 包体积预算（需要 CI 型检查执行器）"); x.available = false;
    x = n("INTEGRATION", "集成测试", "delivery", "delivery", "check", "QA", "跨服务集成 / 契约测试（需要 CI 型检查执行器）"); x.available = false;
    x = n("ACCEPTANCE", "验收", "delivery", "delivery", "review", "ACCEPTANCE", "验收评审组 + 发布闸门"); x.optional = false; x.inputs = List.of("prd", "pr", "test_report"); x.outputs = List.of("verdict"); x.requires = List.of("QA"); x.mandatory = "always"; x.risk = "high"; x.cost = new Cost(4, 0.03, 2);
    x = n("RELEASE", "发布", "delivery", "delivery", "system", "RELEASE", "合并、灰度、观察与回滚"); x.optional = false; x.requires = List.of("ACCEPTANCE"); x.mandatory = "always"; x.risk = "high";
  }

  /** 引擎阶段键 → 节点 id（DESIGN → UX_DESIGN；QA 的附加检查不单列） */
  public static String nodeOfStage(String stage, com.x7x24.pipeline.Pipeline.StageCfg sc) {
    if (stage == null) return null;
    if ("DESIGN".equals(stage) || "SPEC".equals(stage) || "PRD_REVIEW".equals(stage)) {
      String art = sc == null ? null : sc.artifactKey;
      if ("apiContract".equals(art)) return "API_DESIGN";
      if ("dbSchema".equals(art)) return "DB_SCHEMA";
      return "DESIGN".equals(stage) ? "UX_DESIGN" : stage;
    }
    return stage; // 内置节点 id 与阶段键相同；工作区派生 / 自定义节点用自己的 id
  }
  /** 节点 id → 定义（UX_DESIGN 等别名也能查到）；目录里没有返回 null */
  public static NodeDef def(String id) { return id == null ? null : NODES.get(id); }

  /** 项目允许的节点：已提供执行器、适用技术栈与画像相交（画像为空时全部允许）、未被项目禁用 */
  public static List<NodeDef> allowed(ProjectCfg c) {
    var prof = StackProfiles.of(c); Set<String> stacks = new HashSet<>(prof.stacks);
    Set<String> disabled = c == null || c.nodePolicy == null || c.nodePolicy.disabled == null ? Set.of() : new HashSet<>(c.nodePolicy.disabled);
    List<NodeDef> out = new ArrayList<>();
    for (var d : NODES.values()) if (allowedFor(d, stacks, disabled)) out.add(d);
    return out;
  }
  public static boolean allowedFor(NodeDef d, Set<String> stacks, Set<String> disabled) {
    if (!d.available) return false;
    if (d.optional && disabled.contains(d.id)) return false;
    return stacks.isEmpty() || d.stacks.contains("*") || d.stacks.stream().anyMatch(stacks::contains);
  }
  /** 目录视图：每个节点附 allowed 与原因 */
  public static List<Map<String, Object>> view(ProjectCfg c) {
    var prof = c == null ? null : StackProfiles.of(c); Set<String> stacks = prof == null ? Set.of() : new HashSet<>(prof.stacks);
    Set<String> disabled = c == null || c.nodePolicy == null || c.nodePolicy.disabled == null ? Set.of() : new HashSet<>(c.nodePolicy.disabled);
    Set<String> mandatory = c == null || c.nodePolicy == null || c.nodePolicy.mandatory == null ? Set.of() : new HashSet<>(c.nodePolicy.mandatory);
    List<Map<String, Object>> out = new ArrayList<>();
    for (var d : NODES.values()) {
      Map<String, Object> m = new LinkedHashMap<>(com.x7x24.util.Json.M.convertValue(d, Map.class));
      boolean ok = c == null ? d.available : allowedFor(d, stacks, disabled);
      m.put("allowed", ok);
      m.put("why", !d.available ? "执行器尚未提供" : d.optional && disabled.contains(d.id) ? "项目已禁用" : ok ? (d.stacks.contains("*") ? "通用节点" : "适用于本项目技术栈") : "不适用于本项目技术栈");
      m.put("projectMandatory", mandatory.contains(d.id));
      out.add(m);
    }
    return out;
  }
}
