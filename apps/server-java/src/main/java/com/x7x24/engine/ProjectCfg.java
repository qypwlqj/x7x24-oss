package com.x7x24.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.x7x24.gov.Risk;
import java.util.*;

/** 项目执行上下文；不包含密钥。任务保存此配置的版本快照。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectCfg {
  public static class PathRule {
    public String prefix, level;
    public PathRule() {}
    public PathRule(String prefix, String level) { this.prefix = prefix; this.level = level; }
  }
  public static class BudgetLimits { public Double dailyUsd, dailyTokens, dailyCiMinutes; /** 本项目新任务的默认开关；null = 跟随治理默认 */ public Boolean taskUnlimited, taskDegrade; }
  public static class Module { public String name = "", path = "", language = "", testCommand = "", buildCommand = ""; }
  /** 技术栈画像（可组合流程 P1）：目录 → 栈；source = manual（手动设置）| auto（由模块推断，只用于展示） */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ModuleStack { public String path = "", stack = ""; }
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class StackProfile { public String source = "auto"; public List<String> stacks = new ArrayList<>(); public List<ModuleStack> modules = new ArrayList<>(); }
  /** 项目级节点策略：禁用可选节点（放松，需 owner / admin）；项目级必选节点（收紧） */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class NodePolicy { public List<String> disabled = new ArrayList<>(), mandatory = new ArrayList<>(); public String defaultPreset; }
  public static class Discovery {
    public boolean enabled = false, source = true, testFailures = true, autoCreateTasks = false;
    public int intervalMinutes = 60, maxFiles = 12;
    public List<String> roots = new ArrayList<>(List.of("src/", "tests/"));
  }
  public String id, name, productLineId, repo, baseBranch = "main", workflow = "ci.yml", autonomy = "L2";
  public String intakeLabel = "7x24:signal", deployWorkflow, releasePrefix = "x7x24";
  /** Existing projects retain repository-release delivery; platform targets require an implemented adapter. */
  public String releaseTarget = ReleasePolicy.GITHUB;
  public boolean intakeAll = false, intake = true;
  public String credentialRef = "GITHUB_TOKEN", technology = "未识别（请点「自动识别」）";
  public int maxConcurrentTasks = 4;
  /** 管理员授权在开发前为已有空仓库创建固定 README 首次提交；默认关闭。 */
  public boolean autoInitializeRepository = false;
  /** 开发方式：sandbox（默认）| legacy（快速方式）。只有 owner / admin 能改；旧数据没有此字段时按沙盒处理。 */
  public String devMode = "sandbox";
  public BudgetLimits budget = new BudgetLimits();
  public Discovery discovery = new Discovery();
  public List<Module> modules = new ArrayList<>();
  public StackProfile stackProfile;
  public NodePolicy nodePolicy = new NodePolicy();
  public List<String> allowedPaths = new ArrayList<>(List.of("src/", "tests/", "docs/", "examples/"));
  /** Variable names only; values never enter the agent context. Sensitive reads require a server path. */
  public List<String> allowedEnvNames = new ArrayList<>(), serverEnvPaths = new ArrayList<>();
  public List<String> allowedExtensions = new ArrayList<>(List.of("js", "jsx", "mjs", "cjs", "ts", "tsx", "css", "md", "txt"));
  public List<PathRule> pathRules = new ArrayList<>(List.of(new PathRule(".github/", "forbidden"), new PathRule("CODEOWNERS", "forbidden"), new PathRule("deploy/", "forbidden"), new PathRule("src/", "mid"), new PathRule("tests/", "low"), new PathRule("docs/", "low"), new PathRule("README", "low")));
  public Map<String, String> prometheusQueries = new LinkedHashMap<>();
  /** 页面验收（UI_ACCEPTANCE）：在 agent 服务器的干净容器里起应用、跑验收脚本、截关键页面。默认关闭。 */
  public UiAcceptance uiAcceptance = new UiAcceptance();
  public static class UiAcceptance {
    public boolean enabled = false;
    /** 安装依赖，如 npm ci；起应用，如 npm run dev -- --host 127.0.0.1 --port 5173 */
    public String setup = "", start = "";
    public String url = "http://127.0.0.1:5173";
    public int readySec = 180;
    /** 本任务验收脚本目录（相对仓库根，DEV 按验收标准写 *.spec.ts / *.spec.mjs） */
    public String specDir = "e2e/acceptance";
    public List<String> pages = new ArrayList<>(List.of("/"));
    /** 模型看截图的判定是否阻断（默认只记录）；脚本失败始终阻断 */
    public boolean visualBlocking = false;
  }
  public String owner() { return repo == null ? null : repo.split("/")[0]; }
  public String repoName() { return repo == null ? null : repo.split("/")[1]; }
  public String riskOf(String file) {
    String level = null;
    for (var rule : pathRules) if (file.startsWith(rule.prefix) || file.equals(rule.prefix)) level = Risk.max(level, rule.level);
    return level == null ? (lowByNature(file) ? "low" : "mid") : level;
  }
  // 翻译 / 语言包 / 文档：项目没单独配规则时按低风险算（显式路径规则仍然优先）
  private static final java.util.regex.Pattern LOW_NATURE = java.util.regex.Pattern.compile("(?i)(^|/)(locales?|i18n|l10n|lang|langs|languages|translations?)/.*\\.(json|ya?ml|po|pot|ftl|xliff|xlf|properties|toml|arb|ts|js)$|\\.(po|pot|ftl|xliff|xlf|md|mdx|txt|rst)$");
  public static boolean lowByNature(String file) {
    return file != null && LOW_NATURE.matcher(file).find() && !file.matches("(?i).*(^|/)(migrations?|db|database|sql)/.*");
  }
}
