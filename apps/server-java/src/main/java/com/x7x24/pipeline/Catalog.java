package com.x7x24.pipeline;

import com.x7x24.pipeline.Pipeline.*;

import java.util.*;

/** 全局目录：节点类型、内置角色、内置技能、自愈动作、治理下限、内置模板。全部由代码定义（决策 1A：不支持自定义节点类型）。 */
public final class Catalog {
  private Catalog() {}

  public record StageType(String type, String name, boolean optional, List<String> slots, String output, List<String> requiredEvidence) {}
  /** 节点类型按固定顺序；只有 SPEC / DESIGN 可关闭（决策 2A） */
  public static final List<StageType> STAGE_TYPES = List.of(
      new StageType("INTAKE", "需求收录", false, List.of(), "Signal", List.of()),
      new StageType("PRD_REVIEW", "PRD 评审", false, List.of("maker", "checker"), "Prd", List.of("prd")),
      new StageType("PLANNING", "任务规划", false, List.of("maker"), "Plan", List.of("plan")),
      new StageType("SPEC", "策划", true, List.of("maker"), "Cases", List.of("acceptance_cases")),
      new StageType("DESIGN", "设计", true, List.of("maker", "checker"), "Design", List.of("design")),
      new StageType("DEV", "开发执行", false, List.of("maker", "fixer"), "CodeChange", List.of("pr")),
      new StageType("QA", "测试", false, List.of(), "CiResult", List.of("ci_log")),
      new StageType("ACCEPTANCE", "验收", false, List.of("checker"), "Verdict", List.of("verdict")),
      new StageType("RELEASE", "发布", false, List.of(), "Release", List.of()));
  public static final List<String> ORDER = STAGE_TYPES.stream().map(StageType::type).toList();
  public static StageType stageType(String t) { return STAGE_TYPES.stream().filter(s -> s.type.equals(t)).findFirst().orElse(null); }

  /** 治理下限（决策 5） */
  public static final int MAX_CHANGE_LINES_CEILING = 3000;
  public static final String MAX_AUTONOMY = "L3";
  public static final int MIN_JUDGE_SEATS = 2;

  public static final Set<String> HEAL_ACTIONS = Set.of("retry_same", "switch_model", "replan", "split", "shrink", "rerun_ci", "rebase_branch", "trip_breaker", "wait_recover");
  public static final List<String> DEFAULT_HEAL = List.of("retry_same", "retry_same", "switch_model", "replan");
  /** 与改造前 Engine.onAnomaly 写死的 Map 逐项一致 */
  public static Map<String, List<String>> defaultHeal() {
    Map<String, List<String>> m = new LinkedHashMap<>();
    m.put("default", DEFAULT_HEAL);
    m.put("ci_flaky", List.of("rerun_ci", "replan")); m.put("change_too_large", List.of("shrink", "split", "replan")); m.put("spec_gap", List.of("replan"));
    m.put("tool_down", List.of("trip_breaker", "wait_recover", "replan")); m.put("merge_conflict", List.of("rebase_branch"));
    m.put("rework_exhausted", List.of("switch_model", "replan")); m.put("same_failure", List.of("switch_model", "replan"));
    return m;
  }

  /** 附加检查（check hook）：规则型，见 Checks */
  public static final Map<String, String> CHECKS = new LinkedHashMap<>();
  static {
    CHECKS.put("ci", "CI 通过（内置，始终执行）");
    CHECKS.put("i18n-keys-complete", "翻译 JSON 合法，且各语言文件的键集合一致");
    CHECKS.put("markdown-lint", "Markdown 基本规范：无空链接、标题层级不跳级");
    CHECKS.put("sql-safety-lint", "SQL 安全检查：拦截无条件 DROP TABLE / TRUNCATE 等高危语句");
  }

  private static Role role(String id, String kind, String schema, String profile) { var r = new Role(); r.id = id; r.kind = kind; r.schema = schema; r.profile = profile; r.builtin = true; return r; }
  public static final Map<String, Role> ROLES = new LinkedHashMap<>();
  static {
    for (var r : List.of(
        role("prd.draft", "maker", "Prd", "exec"), role("plan.make", "maker", "Plan", "exec"), role("spec.write", "maker", "Cases", "exec"),
        role("design.make", "maker", "Design", "exec"), role("dev.code", "maker", "CodeChange", "exec"), role("dev.fix", "maker", "CodeChange", "exec"),
        role("review.prd", "checker", "PrdVote", "judges"), role("intake.chat", "maker", "IntakeTurn", "exec"), role("review.design", "checker", "DesignVote", "judges"), role("review.accept", "checker", "AcceptVote", "judges"))) ROLES.put(r.id, r);
  }
  /** 节点 → 默认角色槽位 */
  public static final Map<String, Map<String, String>> DEFAULT_SLOTS = Map.of(
      "PRD_REVIEW", Map.of("maker", "prd.draft", "checker", "review.prd"), "PLANNING", Map.of("maker", "plan.make"), "SPEC", Map.of("maker", "spec.write"),
      "DESIGN", Map.of("maker", "design.make", "checker", "review.design"), "DEV", Map.of("maker", "dev.code", "fixer", "dev.fix"), "ACCEPTANCE", Map.of("checker", "review.accept"));

  private static Skill skill(String id, String name, String text, String... pats) { var s = new Skill(); s.id = id; s.name = name; s.instructions = text; s.filePatterns = List.of(pats); s.builtin = true; return s; }
  public static final Map<String, Skill> SKILLS = new LinkedHashMap<>();
  static {
    for (var s : List.of(
        skill("i18n-json", "多语言 JSON", "多语言规范：翻译文件按语言拆分（例如 locales/{lang}/*.json 或 i18n/{lang}.json，以仓库现有结构为准）；新增语言时键集合必须与基准语言（通常是 en 或 zh）完全一致；只新增或修改键，不删除已有键；文件必须是合法 JSON、2 空格缩进；不要翻译占位符（{name}、{{count}}、%s）。一次只改必要的语言文件，避免整文件重写。", "**/locales/**", "**/i18n/**"),
        skill("react-ts", "React + TypeScript", "前端规范：React 函数组件 + TypeScript 严格类型；文案走 i18n（t('key')），不要硬编码可见文案；组件改动用 search/replace 小块编辑；保持现有样式方案（如 Tailwind 类名）；新增逻辑补对应单元测试。", "**/*.tsx", "**/*.ts"),
        skill("docs-style", "文档写作", "文档规范：Markdown；标题层级不跳级、不超过 3 级；示例代码标注语言；链接必须有文字；只改与任务相关的段落。", "docs/**", "**/*.md"),
        skill("java-spring", "Java + Spring Boot", "后端规范：遵循 Spring Boot 分层结构（Controller/Service/Repository）；入参校验与统一异常处理；数据库读写注意事务边界；新增或修改逻辑补充 JUnit 5 单元测试，禁止硬编码敏感配置。", "**/*.java"),
        skill("go-service", "Go 服务", "后端规范：遵循 Go 标准包结构；显式处理 error 并传递 context.Context；注意并发安全；新增或修改函数补充表驱动单元测试（*_test.go）。", "**/*.go"),
        skill("sql-migration", "SQL 数据迁移", "数据库变更规范：迁移脚本按版本号递增命名，只增不改历史脚本；保持向后兼容，禁止无条件 DROP TABLE / TRUNCATE；大表新增字段设置合理默认值或允许 NULL，并注明回滚方案。", "**/*.sql", "**/migrations/**"),
        skill("api-contract", "接口契约设计", "接口规范：RESTful 路径与状态码语义一致；请求与响应字段类型、必填项、错误码明确；保持向后兼容，不随意删除或重命名已有响应字段。", "**/*.java", "**/*.ts", "**/*.go", "**/openapi*"),
        skill("sec-review", "安全审计", "安全评审规范：逐项检查越权访问（接口是否校验登录与资源归属 / 角色）、注入（SQL / 命令 / 模板 / 路径拼接）、敏感数据（密钥、令牌、API Key、密码、个人信息是否明文存储、写进日志或返回给前端）、配置与依赖风险（默认口令、关闭校验、引入高危依赖）；密钥类配置必须加密或脱敏存储，接口只返回掩码。发现可被利用的问题必须阻断，并写明文件与修复建议。"),
        skill("ocr-review", "逐行审查", "逐行审查：在 runner 沙箱里按开源项目 alibaba/open-code-review（Apache-2.0）官方 skill「open-code-review」运行 ocr v1.13.5，由 ocr 自己挑文件、分组、读上下文、定位行号并复核；每条意见有文件、新文件行号、严重程度（critical / high / medium / low）、类别、说明与修复建议；行号为 0 的是没定位到的意见，单独列出、不阻断。", "**/*"),
        skill("vue-ts", "Vue + TypeScript", "前端规范：Vue 3 组合式 API + TypeScript；文案走 i18n，不硬编码可见文案；保持现有组件库与样式方案；新增组件补充单元测试。", "**/*.vue", "**/*.ts"),
        skill("python-service", "Python 服务", "后端规范：遵循项目现有框架（FastAPI / Django / Flask）分层；类型注解；显式处理异常；新增或修改逻辑补充 pytest 单元测试。", "**/*.py"))) SKILLS.put(s.key(), s);
  }

  /** 内置可复用动态工作流节点原型（供对话编排与自定义工作流直接引用或派生） */
  public static final Map<String, StageCfg> STAGE_PRESETS = new LinkedHashMap<>();
  static {
    var api = new StageCfg("API_DESIGN"); api.label = "接口契约设计"; api.anchorPhase = "DESIGN"; api.executor = "llm_maker"; api.maker = "design.make"; api.checker = "review.design";
    api.artifactKey = "apiContract"; api.requiredFields = List.of("endpoints"); api.skills = List.of("api-contract@1");
    api.promptTemplate = "你是接口架构师。根据需求输出接口契约设计 JSON：{\"endpoints\":[{\"method\":str,\"path\":str,\"request\":str,\"response\":str,\"errorCodes\":[str]}],\"compatibility\":str,\"notes\":str,\"frames\":[str]}。";
    var db = new StageCfg("DB_SCHEMA"); db.label = "数据库变更设计"; db.anchorPhase = "DESIGN"; db.executor = "llm_maker"; db.maker = "design.make"; db.checker = "review.design";
    db.artifactKey = "dbSchema"; db.requiredFields = List.of("tables", "rollbackPlan"); db.skills = List.of("sql-migration@1");
    db.promptTemplate = "你是数据库架构师。根据需求输出表结构与迁移方案 JSON：{\"tables\":[{\"name\":str,\"changes\":[str],\"indexes\":[str]}],\"migrationSql\":str,\"rollbackPlan\":str,\"lockRisk\":str,\"notes\":str,\"frames\":[str]}。";
    var sec = new StageCfg("SEC_AUDIT"); sec.label = "安全专项评审"; sec.anchorPhase = "ACCEPTANCE"; sec.executor = "llm_checker"; sec.checker = "review.accept";
    sec.artifactKey = "secAudit"; sec.skills = List.of("sec-review@1"); sec.promptTemplate = "你是安全审计评审员，重点审查变更是否涉及越权访问、注入风险、敏感数据泄露或高危配置改动。";
    var cr = new StageCfg("CODE_REVIEW"); cr.label = "逐行审查"; cr.anchorPhase = "ACCEPTANCE"; cr.executor = "llm_checker"; cr.checker = "review.accept";
    cr.artifactKey = "codeReview"; cr.skills = List.of("ocr-review@1"); cr.promptTemplate = "用 open-code-review（ocr review --audience agent --format json）逐行审查本次 PR 相对目标分支的改动，找出真实缺陷。";
    var ua = new StageCfg("UI_ACCEPTANCE"); ua.label = "页面验收"; ua.anchorPhase = "ACCEPTANCE"; ua.executor = "llm_checker"; ua.checker = "review.accept"; ua.artifactKey = "uiAcceptance";
    ua.promptTemplate = "页面验收：脚本结果为确定性依据，判定 agent 对照验收标准看页面文字与截图。";
    STAGE_PRESETS.put(api.type, api); STAGE_PRESETS.put(db.type, db); STAGE_PRESETS.put(sec.type, sec); STAGE_PRESETS.put(cr.type, cr); STAGE_PRESETS.put(ua.type, ua);
  }
  public static StageCfg presetStage(String type) { var p = STAGE_PRESETS.get(type); return p == null ? null : com.x7x24.util.Json.convert(p, StageCfg.class); }
  public static String stageLabel(String type) {
    var st = stageType(type); if (st != null) return st.name();
    var pr = STAGE_PRESETS.get(type); return pr != null && pr.label != null ? pr.label : type;
  }

  private static StageCfg sc(String type, Boolean enabled) { var s = new StageCfg(type); s.enabled = enabled; return s; }
  private static Template tpl(String id, String name, String desc, Map<String, Boolean> enabled, Map<String, List<String>> heal) {
    var t = new Template(); t.id = id; t.name = name; t.description = desc; t.builtin = true; t.status = "approved";
    for (String ty : ORDER) t.stages.add(sc(ty, enabled.get(ty))); t.heal = heal; return t;
  }
  /** 内置模板。default@1 所有参数为 null → 沿用治理配置与改造前写死的值（P1 行为不变）。 */
  public static final Map<String, Template> TEMPLATES = new LinkedHashMap<>();
  static {
    var d = tpl("default", "默认流程", "与改造前完全一致：SPEC / DESIGN 由规划器按需决定，阈值沿用治理配置。", Map.of(), defaultHeal());
    var w = tpl("web-fullstack", "Web 全栈", "完整流程：策划、设计都允许（由规划器按需进入）；QA 附加多语言键完整性检查。适合有前端页面与多语言的 Web 项目。", Map.of(), defaultHeal());
    w.stages.stream().filter(s -> s.type.equals("QA")).findFirst().ifPresent(s -> s.checks = List.of("ci", "i18n-keys-complete"));
    var be = tpl("backend-service", "后端服务", "后端接口与服务开发：默认关闭体验设计，QA 附加 SQL 安全检查。适合 Java / Go / Node 等后端项目。", Map.of("DESIGN", false), defaultHeal());
    be.stages.stream().filter(s -> s.type.equals("QA")).findFirst().ifPresent(s -> s.checks = List.of("ci", "sql-safety-lint"));
    Map<String, List<String>> dh = new LinkedHashMap<>(defaultHeal()); dh.put("default", List.of("retry_same", "switch_model", "replan"));
    var docs = tpl("docs-only", "纯文档", "只改文档：关闭策划与设计，QA 附加 Markdown 检查。", Map.of("SPEC", false, "DESIGN", false), dh);
    docs.stages.stream().filter(s -> s.type.equals("QA")).findFirst().ifPresent(s -> s.checks = List.of("ci", "markdown-lint"));
    docs.maxHeals = 3;
    for (var t : List.of(d, w, be, docs)) TEMPLATES.put(t.key(), t);
  }
}
