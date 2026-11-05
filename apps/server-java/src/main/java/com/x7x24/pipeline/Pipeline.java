package com.x7x24.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.*;

/**
 * 流程模板 / 角色 / 技能 / 模型配置的数据模型（见 docs/design/model-config.md）。
 * 四层：全局目录（Catalog，代码内置）→ 流程模板（Template，版本化）→ 业务线绑定（LineBinding）→ 项目覆盖（ProjectCfg，只能收紧）。
 * 字段为 null 表示「沿用上一层 / 治理配置」，因此内置 default@1 全部为 null，行为与改造前完全一致。
 */
public final class Pipeline {
  private Pipeline() {}

  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Gate { public Double pass, revise; public Gate() {} public Gate(Double p, Double r) { pass = p; revise = r; } }

  /** 模板里的一个节点：对固定语义的节点类型（StageType）的一次使用，或动态工作流中的自定义节点 */
  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class StageCfg {
    public String type; public Boolean enabled;
    public String maker, checker, fixer;
    public Gate gate; public Integer maxLoops, judgeSeats;
    /** 节点内附加检查（check hook），失败视为该节点失败 */
    public List<String> checks;
    public Integer maxChangeLines;
    /** 锁定：任务级流程计划不能调整这个节点（模板 / 业务线可设） */
    public Boolean locked;
    /** 强制进入（可选节点）：规划器没选也要走；任务级流程计划「开启」节点时设置 */
    public Boolean required;
    /** 动态工作流扩展：显示名称、映射到的内核阶段锚点（PRD_REVIEW / DESIGN / ACCEPTANCE 等）、执行器类型、提示词与产物键 */
    public String label, anchorPhase, executor, promptTemplate, artifactKey, replacedFrom, afterStage;
    public List<String> requiredFields, skills;
    public String instructions;
    public StageCfg() {}
    public StageCfg(String type) { this.type = type; }
    public boolean on() { return enabled == null || enabled; }
    public String phase() { return anchorPhase != null ? anchorPhase : (replacedFrom != null ? replacedFrom : type); }
  }

  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Template {
    public String id; public int version = 1; public String name, description;
    /** null = 平台模板；否则为业务线私有模板（需平台管理员审批，决策 3B） */
    public String owner;
    /** approved | pending | rejected */
    public String status = "approved";
    public boolean builtin;
    public List<StageCfg> stages = new ArrayList<>();
    public Map<String, List<String>> heal;
    public Integer maxHeals;
    public String submittedBy, reviewedBy, reviewNote, at;
    public String key() { return id + "@" + version; }
    public StageCfg stage(String type) { return stages.stream().filter(s -> type.equals(s.type)).findFirst().orElse(null); }
  }

  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Skill {
    public String id; public int version = 1; public String name, instructions; public List<String> filePatterns; public boolean builtin;
    public String key() { return id + "@" + version; }
  }

  /** 角色 = 技能 + 工具 + 输出 schema + 校验器 + 模型档位 */
  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Role {
    public String id; public String kind; public String schema; public String profile;
    /** 派生角色：继承的内置角色 id；只能加技能、换档位、追加指令 */
    public String extendsRole;
    public List<String> skills; public String instructions;
    public boolean builtin;
  }

  /** 业务线绑定 */
  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class LineBinding {
    public String template = "default@1";
    public Map<String, Role> roles;
    public Map<String, StageCfg> stageOverrides;
    /** 角色 id → 模型档位 id */
    public Map<String, String> models;
    public int version; public String updatedBy, at;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Doc {
    public int version;
    public Map<String, Template> templates = new LinkedHashMap<>();
    public Map<String, Skill> skills = new LinkedHashMap<>();
    public Map<String, LineBinding> lines = new LinkedHashMap<>();
    public ModelConfig models;
  }

  // ── 模型配置（P2）
  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Provider {
    public String id, type = "openai-compat", baseUrl;
    /** 展示名与预设（DeepSeek / OpenAI / …），由「接入模型」表单写入 */
    public String name, preset;
    /** 运行时：密钥所属范围（工作区 id / _platform），不持久化 */
    @com.fasterxml.jackson.annotation.JsonIgnore public transient String scope;
    /** 密钥引用：env:NAME。只存引用，不存密钥本身 */
    public String secretRef;
    public Integer maxConcurrency, timeoutSec, maxRetries; public Long minIntervalMs;
  }
  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Model {
    public String id, provider, family; public Integer contextTokens, maxOutputTokens;
    public List<String> capabilities; public Double priceInPer1m, priceOutPer1m;
  }
  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Seat { public List<String> chain = new ArrayList<>(); public Double temperature; public String persona; }
  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Profile {
    public List<String> chain = new ArrayList<>(); public Double temperature; public Integer minOutputTokens;
    /** 评审档位：多个席位，最后一席兼任仲裁 */
    public List<Seat> seats;
  }
  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Breaker { public int window = 10; public double failureRate = 0.6; public int openSeconds = 300; }
  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ModelConfig {
    public List<Provider> providers = new ArrayList<>();
    public List<Model> models = new ArrayList<>();
    public Map<String, Profile> profiles = new LinkedHashMap<>();
    /** 角色 → 档位（全局默认绑定） */
    public Map<String, String> roles = new LinkedHashMap<>();
    /** 预算到 soft 线时的降级档位 */
    public String budgetDowngradeProfile;
    public Breaker breaker = new Breaker();
    public boolean migratedFromEnv;
  }

  /** 流程图节点（v2 快照）：source = preset（模板 / 业务线）| rule（规则基线）| governance（治理必选）| agent（AI 提议） */
  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class GNode {
    public String id, def, stage, label, module, family, executor, source, reason, mandatory;
    /** 检查类节点挂在哪个节点内执行（QA）；顶层节点为 null */
    public String parent;
    /** 可选阶段且未强制：由规划器按需求决定是否执行 */
    public Boolean optional;
    public List<String> skills, checks;
  }
  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Estimate { public int llmCalls; public double usd, minutes; }
  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Graph {
    public int schema = 2;
    public List<GNode> nodes = new ArrayList<>();
    public List<List<String>> edges = new ArrayList<>();
    public Estimate estimate;
    public List<String> warnings;
  }

  /** 生效快照：四层合并后的结果，任务钉住它的 id */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Effective {
    public String id, lineId, template; public int docVersion;
    public Map<String, StageCfg> stages = new LinkedHashMap<>();
    public Map<String, List<String>> heal = new LinkedHashMap<>();
    public Integer maxHeals;
    public Map<String, Role> roles = new LinkedHashMap<>();
    /** 角色 → 拼好的技能指令（P3 注入系统提示） */
    public Map<String, String> skillText = new LinkedHashMap<>();
    public Map<String, Skill> skillVersions = new LinkedHashMap<>();
    /** 角色 → 档位 id（业务线覆盖后） */
    public Map<String, String> models = new LinkedHashMap<>();
    /** 每个值来自哪一层：catalog / template / line */
    public Map<String, String> sources = new LinkedHashMap<>();
    /** 任务级流程计划（对话录入 C2）：已生效的调整，重新钉快照时会重放 */
    public Plans.Plan plan;
    /** 快照格式：null / 1 = 线性 stages；2 = 另带组装出的流程图 graph（可组合流程 P2） */
    public Integer schema;
    public Graph graph;
    public boolean stageOn(String type) { var s = stages.get(type); return s == null || s.on(); }
    public StageCfg stage(String type) { return stages.get(type); }
  }
}
