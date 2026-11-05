package com.x7x24.gov;

import java.util.*;

/** 治理配置（热更新、写入 settings 表）。字段名与前端「治理配置」页一致。 */
public class Governance {
  public static class Thresholds { public double priority = 0.6, prdPass = 0.75, prdRevise = 0.5, disagreementStd = 0.2, acceptancePass = 0.8, designPass = 0.6, similarityDup = 0.85; }
  public static class HighRisk { public double prdPassBonus = 0.1, acceptanceBonus = 0.05, canaryStrictness = 0.5, minObserveMultiplier = 4; }
  public static class Loops {
    public int maxPolicyRepairAttempts = 2;
    public int maxLoops = 3, maxRejects = 2, maxRollbacks = 2, maxReviewRetry = 2, maxRequeue = 2, maxHeals = 4, maxStepAttempts = 3, maxConcurrentPerLine = 12;
    public double cooldownDays = 7, staleBacklogDays = 10;
    public double[] degradeBudgetBoost = {0.5, 0.3};
  }
  public static class Window { public List<String> days = new ArrayList<>(List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")); public double[] hours = {0, 24}; }
  public static class Freeze { public String name, from, to; }
  public static class Release {
    public List<Window> windows = new ArrayList<>(List.of(new Window())); public List<Freeze> freeze = new ArrayList<>();
    public int maxChangeLines = 500, maxFiles = 20; public double observeHours = 72;
    /** 变更规模加权（glob → 0~1，先匹配先生效）：翻译 JSON / 文档 / lock / 生成文件打折，见 ChangeSize */
    public LinkedHashMap<String, Double> sizeWeights = new LinkedHashMap<>();
    /** 打折文件原始行数合计上限，超出部分按权重 1 计（防滥用）；拆分时最多几段顺序提交 */
    public int maxExemptLines = 5000, maxSplitParts = 4;
    /** 文件数按权重折算；开发 agent 超限后在开发步骤内自行收缩的次数；规划预估超过预算该比例即预先拆分 */
    /** 运行时填写：本任务预算上限来自哪里（治理配置 / 流程收紧），只用于提示与审计 */
    @com.fasterxml.jackson.annotation.JsonIgnore public String limitSource = "治理配置 release.maxChangeLines / maxFiles";
    public boolean weightFileCount = true; public int maxShrinkAttempts = 2; public double planSplitRatio = 0.8;
    /** 预算很小时自动多拆几段：实际可用段数 = max(maxSplitParts, maxAutoSplitParts)，按预估 / 单段预算向上取整 */
    public int maxAutoSplitParts = 12;
    /** 每段 / 每次提交上限是否硬性拒绝：默认否（只作为方便审阅的建议大小，超出只记录、提高合并关卡等级） */
    public boolean segmentHardLimit = false;
    /** 纯删除行的权重；测试文件（未被 sizeWeights 匹配时）的权重 */
    public double deletionWeight = 0.5, testWeight = 0.5;
    public String sizeNote = "变更规模 = 按文件的真实 diff 行（新文件=行数，修改=增删行，删除=1）× sizeWeights 权重；打折文件原始行合计超过 maxExemptLines 的部分按 1 计；权重 0 的文件不计文件数。文件数按权重折算（weightFileCount）。规划预估超过 planSplitRatio 预先拆段；开发超限先在步骤内收缩（maxShrinkAttempts 次）→ 拆分（同分支顺序提交，每段不超限，最多 maxSplitParts 段）→ 重规划；预算不放宽。"; public List<String> requireEvidence = new ArrayList<>(List.of("prd", "pr", "ci_log", "test_report"));
  }
  public static class Metric {
    public String name, successCondition, interval = "5m", initialDelay = "0m"; public int failureLimit = 0, count = 3;
    public Metric() {}
    public Metric(String n, String c, int f, int count) { name = n; successCondition = c; failureLimit = f; this.count = count; }
  }
  public static class Canary {
    public int[] steps = {1, 5, 25, 100}; public double minObserveMinutes = 30, highMinObserveMinutes = 120, rollbackTargetMinutes = 5;
    public List<Metric> metrics = new ArrayList<>(List.of(
        new Metric("error_rate_delta_pp", "result < 0.5", 1, 3), new Metric("error_rate_abs_pct", "result < 1", 1, 3),
        new Metric("p95_regression_pct", "result < 10", 1, 3), new Metric("restart_count", "result <= 4", 0, 3)));
  }
  public static class Autonomy { public String defaultLevel = "L1"; public int tightenDiscards = 5, tightenRollbacks = 2, loosenMinReleased = 5, globalRollbackKill = 12; public double pauseIntakeHours = 12, loosenAfterDays = 30, iterateRate = 0; }
  public static class TaskBudget { public double tokens = 2_000_000, usd = 3, ciMinutes = 60, iterations = 50, nudgeRatio = 0.8;
    /** 默认开关（项目 / 任务可覆盖）：unlimited = 不限预算（仍记账）；degrade = 预算触顶时进入省钱模式（关掉则直接暂停等负责人） */
    public boolean unlimited = false, degrade = true;
    /** 不限预算时的安全上限：开发轮次达到此数暂停等负责人（防止无限循环烧钱） */
    public int safetyMaxIterations = 150; }
  public static class LineBudget { public double dailyUsd = 50, dailyCiMinutes = 200, dailyTokens = 40_000_000, softRatio = 0.8; }
  public static class GlobalBudget { public double dailyUsd = 500, dailyTokens = 400_000_000, dailyCiMinutes = 2000, monthlyUsd = 10_000, monthlyAlertRatio = 0.8; }
  public static class Budgets { public TaskBudget task = new TaskBudget(); public LineBudget line = new LineBudget(); public GlobalBudget global = new GlobalBudget(); }
  public static class Keywords { public List<String> low = new ArrayList<>(), mid = new ArrayList<>(), high = new ArrayList<>(); }
  public static class Network { public List<String> allowlist = new ArrayList<>(List.of("registry.npmjs.org", "github.com", "api.github.com", "api.internal.example.com", "pypi.org")); }
  public static class Committee { public Map<String, Double> weights = new LinkedHashMap<>(Map.of("value", 0.3, "clarity", 0.2, "feasibility", 0.3, "risk", 0.2)); }

  public Thresholds thresholds = new Thresholds();
  public HighRisk highRisk = new HighRisk();
  public Loops loops = new Loops();
  /** 各阶段「步骤间隔」（分钟）。真实闭环由外部事件（CI、合并、发布）驱动，所以默认只有几秒。 */
  public Map<String, Double> stageDurationsMin = new LinkedHashMap<>();
  public Map<String, List<String>> forbidden = new LinkedHashMap<>();
  public Keywords riskKeywords = new Keywords();
  public List<String> governanceFiles = new ArrayList<>(List.of(".github/", "CODEOWNERS", "acceptance/", ".gitleaks", "semgrep", "branch-protection", "deploy/"));
  public Network network = new Network();
  public Budgets budgets = new Budgets();
  public Release release = new Release();
  public Canary canary = new Canary();
  public Autonomy autonomy = new Autonomy();
  public Committee committee = new Committee();
  /** 对话式需求录入：单会话美元上限、最多轮数、每轮最多工具步数、单会话最多读文件次数（决策 8A） */
  /**
   * 合并关卡（大改动的替代审阅）：按 PR 加权行数和风险分 G0–G3；各自治等级能自动合并的等级见 autoMerge，其余合并前需要负责人一键确认。
   * 加权行数 > g1Lines → G1，> g2Lines → G2，> g3Lines → G3。
   */
  public static class Gate {
    public int g1Lines = 200, g2Lines = 400, g3Lines = 1000;
    public Map<String, String> riskTier = new LinkedHashMap<>(Map.of("low", "G0", "mid", "G1", "high", "G2"));
    public Map<String, List<String>> autoMerge = new LinkedHashMap<>(Map.of("L1", List.of(), "L2", List.of("G0", "G1"), "L3", List.of("G0", "G1", "G2")));
    public boolean highRiskAlwaysApprove = true;
    public double approvalTimeoutHours = 72;
  }
  public Gate gate = new Gate();
  public static class Chat { public double sessionUsd = 0.5; public int maxTurns = 60, maxStepsPerTurn = 5, maxFileReads = 40; }
  public Chat chat = new Chat();
  /**
   * 逐行审查（CODE_REVIEW 节点）：在 runner 沙箱里跑 alibaba/open-code-review 的 ocr v1.13.5（官方 skill「open-code-review」，ocr 自己调模型）。
   * 文件挑选、分组、定位、复核都由 ocr 自己做；这里只管模型、时限、预算和判定。
   * blocking=false：观察期，只记录发现、不退回；打开后 critical 或 bug / security 类的 high 会像测试失败一样退回开发（定位失败的意见不阻断）。
   */
  public static class CodeReview {
    public boolean enabled = true, blocking = true;
    /** ocr --max-tokens-budget：整次审查的 Token 上限（输入 + 输出）；用完后没审的文件标成「部分审查」 */
    public long maxTokens = 600_000;
    /** ocr --timeout（分钟，每组每轮）；默认 medium 档 2 轮 */
    public int timeoutMin = 15;
    /** 整次审查（一个模型）最长多少分钟，超时取消并保留会话，下次 --resume */
    public int wallClockMin = 60;
    /** ocr --concurrency：同时审几组文件 */
    public int concurrency = 8;
    /** 候选链用完后再试的备用模型（须已在模型配置里注册、拿得到密钥；本任务执行方用过的不加，保持审查避开执行模型）。换模型时带上一次的会话 --resume */
    public List<String> fallbackModels = new ArrayList<>(List.of("deepseek-chat"));
    /** 从哪个合并关卡等级起组装（默认 G1）：预估等级 = max（草稿规模档位，gate.riskTier），见 CodeReviewGate */
    public String minTier = "G1";
  }
  public CodeReview codeReview = new CodeReview();

  public static Governance defaults() {
    Governance g = new Governance();
    for (String s : List.of("BACKLOG", "PRD_REVIEW", "PLANNING", "SPEC", "DESIGN", "DEV", "QA", "ACCEPTANCE", "DELIVERED", "RELEASED", "ANOMALY", "DEGRADED", "DISCARDED")) g.stageDurationsMin.put(s, 0.05);
    g.stageDurationsMin.put("RELEASED", 0.25); g.stageDurationsMin.put("DISCARDED", 1440.0); g.stageDurationsMin.put("ANOMALY", 0.5);
    g.release.sizeWeights.put("**/locales/**/*.json", 0.1); g.release.sizeWeights.put("**/i18n/**/*.json", 0.1);
    g.release.sizeWeights.put("docs/**", 0.25); g.release.sizeWeights.put("*.md", 0.25);
    g.release.sizeWeights.put("**/package-lock.json", 0.0); g.release.sizeWeights.put("**/yarn.lock", 0.0); g.release.sizeWeights.put("**/pnpm-lock.yaml", 0.0);
    g.release.sizeWeights.put("**/generated/**", 0.0); g.release.sizeWeights.put("**/*.generated.*", 0.0);
    g.forbidden.put("payment", List.of("支付", "退款", "改价", "结算", "payment", "refund", "billing config"));
    g.forbidden.put("permission", List.of("iam", "授权策略", "角色授权", "密钥", "凭证", "权限变更", "secret", "credential", "rbac policy"));
    g.forbidden.put("production_data", List.of("生产库", "生产数据", "删除数据", "批量导出", "drop table", "production database", "prod db"));
    g.forbidden.put("release", List.of("跳过流水线", "绕过发布", "直接推送主分支", "修改发布配置", "push main", "skip pipeline"));
    g.forbidden.put("network", List.of("未白名单", "外部域名出口", "egress to", "unlisted domain"));
    g.forbidden.put("repo", List.of("force push", "强制推送", "删除分支", "删除 tag", "保护规则", "branch protection", ".github/workflows", ".github/", "codeowners"));
    g.riskKeywords.low = List.of("文案", "样式", "文档", "注释", "测试补全", "单元测试", "配置", "ui", "按钮", "patch", "typo", "docs", "readme", "工具函数", "util", "slugify", "使用说明", "测试");
    g.riskKeywords.mid = List.of("接口", "api", "页面", "依赖升级", "新增功能", "搜索", "推荐", "通知", "minor", "业务逻辑");
    g.riskKeywords.high = List.of("schema", "数据库", "核心链路", "跨服务", "性能", "鉴权相关", "迁移", "高并发", "database");
    return g;
  }
}
