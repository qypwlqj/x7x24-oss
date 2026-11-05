package com.x7x24.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;

/** 任务上下文（JSON 存于 task.context）。包含真实世界的引用：Issue / PR / CI run / 合并 / Release，以及工作流节点产物总线 artifacts。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskCtx {
  public String recoveryId;
  /** 本任务的开发方式覆盖：null = 跟随项目；sandbox / legacy。成员可改，下一轮开发生效。 */
  public String devModeOverride;
  /** 可重试的沙盒失败（clone / 连不上 runner）在同一开发轮次内已自动重试的次数；key 不同则重新计数。 */
  public int sandboxRetries; public String sandboxRetryKey;
  /** 逐行审查「跳过并继续」：只对这一轮（轮次:该节点返修次数）有效，没审完的文件按「没审」处理，结论标成部分审查 */
  public String codeReviewSkipFailedKey;
  /** 最近一次成功恢复、阶段推进或分段提交；普通轮询和失败重试不刷新。 */
  public String lastProgressAt;
  public int recoveryGeneration, policyAttempts;
  /** 手动恢复只补充本轮额度，累计计数及历史证据保持不变。旧任务的基线默认为零。 */
  public ReworkQuota reworkQuota = new ReworkQuota();
  public static class ReworkQuota {
    public int round, qaBase;
    public Integer rejectBase;
    public Map<String, Integer> nodeBases = new LinkedHashMap<>();
    public Map<String, Integer> limits = new LinkedHashMap<>();
    public String cause;
    /** 已授权、尚未派发的返修；消费它时不重新读取或累计旧检查结果。 */
    public String pendingNode;
  }
  public Map<String,Object> policyFeedback;
  /** 失败记录：code + params 供前端翻译成人话；detail 为原始信息，只放进「详情」 */
  public static class Failure {
    public String stage, category, detail, at, code; public Map<String, Object> params = new LinkedHashMap<>();
    public Failure() {}
    public Failure(String s, String c, String d, String a) {
      stage = s; category = c; detail = d; at = a;
      if (c != null) { int i = c.indexOf(':'); code = i < 0 ? c : c.substring(0, i); if (i >= 0) params.put("sub", c.substring(i + 1)); }
    }
    public Failure with(Map<String, ?> p) { if (p != null) p.forEach((k, v) -> { if (v != null) params.put(k, v); }); return this; }
  }
  public static class Sig {
    public String type, anomalyType, detail;
    /** 超时观察绑定当时的节点、进展时间和恢复代次；旧信号允许没有这些字段。 */
    public String observedStage, observedNode, observedProgressAt;
    public Integer observedRecoveryGeneration;
    public Sig() {}
    public Sig(String t, String a, String d) { type = t; anomalyType = a; detail = d; }
    public boolean timeout() { return "ANOMALY".equals(type) && (anomalyType == null || "timeout".equals(anomalyType)); }
  }
  public static class Issue { public int number; public String url; public String title; public String author; }
  public static class Pr { public int number; public String url, branch, headSha, baseSha; public List<String> files = new ArrayList<>(); public int lines, weightedLines = -1, splitParts; public int commits; }
  public static class Ci {
    public String headSha, state = "none"; public Long runId; public String url, log, startedAt; public boolean dispatched, rerun; public double minutes; public int total, failed; public double coverage;
    public List<Long> runIds = new ArrayList<>(), staleRunIds = new ArrayList<>(); public String conclusion;
  }
  public static class Merge { public String sha, at; public String mainCiState; public Long mainRunId; public String mainRunUrl; }
  public static class Rel { public String tag, url, name, sha, verifiedAt; public Long id; public boolean verified; public String revertPr; }

  public ProjectCfg projectSnapshot;
  /** 需求对话确认时给出的目标路径（风险按路径判，PRD 起草没给路径时兜底用） */
  public List<String> targetPaths = new ArrayList<>();
  public int projectConfigVersion;
  public List<Failure> failures = new ArrayList<>();
  public List<Sig> signals = new ArrayList<>();
  public int qaRuns, designAttempts, devRuns, branchGen;
  public List<String> reviewNotes = new ArrayList<>();
  /** 指挥台 M3：负责人在会话里确认跳过的附加检查（check 名）；安全审计 / SQL 安全 / 验收不在此列（服务端硬规则） */
  public List<String> skippedChecks = new ArrayList<>();
  /** 可组合流程 v2：每个节点被打回返修的次数（超过节点 maxLoops 转降级） */
  public Map<String, Integer> nodeLoops = new LinkedHashMap<>();
  /** 节点结果失效的任务版本；只更新当前投影，不删除历史执行与证据。 */
  public Map<String, Integer> nodeInvalidations = new LinkedHashMap<>();
  /** 任务涉及的技术栈（钉流程时写入，看板按栈分段用） */
  public List<String> stacks = new ArrayList<>();
  /** 中途修订次数（决策 D9：最多 3 次） */
  public int planRevisions;
  public String lastFailureCategory, lastCiLog, template;
  /** 返修 / 省钱模式回到规划时，直接接着做的步骤（如 DEV）与原因：已完成的上游产物（需求策划、体验设计）保留，不重做 */
  public String resumeAt, resumeWhy;
  public boolean nudged, spawnedRemainder, dryRunNoted;
  public Issue issue; public Pr pr; public Ci ci; public Merge merge; public Rel rel;
  /** change_too_large 时记录的超限明细（文件 / 加权行 / 预算），交给拆分规划与重规划 */
  public Map<String, Object> sizeFeedback;
  public boolean splitRequested; public int subtaskIdx;
  /** 改动超出建议大小后已接受（不再收缩 / 拆段），合并前按合并关卡等级把关 */
  public boolean sizeAccepted;
  /** 上一次 CI 失败的指纹与「同样的失败连续出现」次数：同一个错误反复出现时换做法，不再原样返修 */
  public String lastCiSig; public int sameFailStreak;
  /** CI 日志里提到的文件：返修时优先读进上下文 */
  public List<String> ciFiles = new ArrayList<>();
  /** 任务级开关（null = 跟随项目 / 治理默认）：budgetOff = 不限预算（仍记录花费、仍有安全上限）；degradeOff = 不进入省钱模式 */
  public Boolean budgetOff, degradeOff; /** 负责人在安全上限处确认继续后追加的开发轮次 */ public int safetyExtra;
  /** 钉住的生效配置快照 id（流程模板 + 业务线绑定 + 模型配置），见 PipelineService */
  public String configSnapshot;
  /** switch_model 自愈后钉住的模型：角色 → 模型 id */
  public Map<String, String> modelPins = new LinkedHashMap<>();
  /** 动态工作流节点通用产物总线：artifactKey → {stage, label, data}，自动传递给下游规划 / 开发 / 验收 */
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public Map<String, Map<String, Object>> artifacts = new LinkedHashMap<>();
  public Map<String, Object> extra = new LinkedHashMap<>();
  public Map<String, Object> baseline = new LinkedHashMap<>();
}
