package com.x7x24.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.x7x24.pipeline.Pipeline.*;
import com.x7x24.util.Json;

import java.util.*;

/**
 * 任务级流程计划与动态工作流编排（设计 §5，决策 4A + 动态工作流扩展）：
 * 在业务线生效配置上叠加调整，或按项目类型 / 需求规模动态替换、新增、裁剪工作流节点与技能。
 * 每条调整都过治理校验：安全底线节点（INTAKE / DEV / QA / ACCEPTANCE / RELEASE）不可绕过、QA 不能去掉 ci、参数越界直接拒绝；
 * 每条调整分成「收紧 / 专业化适配」或「放松」，含放松项时需要 owner / admin 审批。
 */
public final class Plans {
  private Plans() {}
  public static final int MAX_ADJUSTMENTS = 12;
  public static final Set<String> OPS = Set.of(
      "enable", "disable", "addCheck", "removeCheck", "setParam", "setRole",
      "fastTrack", "replaceStage", "insertStage", "removeStage", "bindSkills");
  public static final Set<String> PARAMS = Set.of("gate.pass", "maxLoops", "judgeSeats", "maxChangeLines");
  public static final Set<String> FAST_TRACKABLE = Set.of("PRD_REVIEW", "PLANNING");
  public static final Set<String> REPLACEABLE = Set.of("PRD_REVIEW", "SPEC", "DESIGN");

  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Adj {
    public String op, stage, check, param, slot, role, reason, source;
    public Double value;
    /** 动态工作流扩展字段：替换目标节点、显示标签、插入位置、映射阶段锚点、执行器、提示词模板、产物键、挂载技能与指令 */
    public String targetStage, label, after, anchorPhase, executor, promptTemplate, artifactKey, instructions;
    public List<String> skills, requiredFields;
    /** 结果：一句话说明；是否放松；被拒原因（null = 生效） */
    public String summary; public Boolean relax; public String rejected;
    /** 生效但有提示（例如忽略了未知技能） */
    public String warning;
  }
  @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Plan {
    public String classification;
    /** 多标签分类（例如 backend + frontend + migration）；classification 是其中的主标签 */
    public List<String> labels;
    public List<Adj> items = new ArrayList<>();
    public boolean needsApproval;
    /** 组装器（P2）：治理 / 项目必选节点 → 原因；组装提示（依赖、预算、自治等级） */
    public Map<String, String> mandatory;
    /** 改动涉及的技术栈（组装时按画像 + 路径得出） */
    public List<String> stacks;
    public List<String> warnings;
    /** 组装起点（P5 预设）：预设 key、种子模板与额外关闭的步骤；重新钉快照时按同一起点重放 */
    public String preset, presetTemplate; public List<String> presetDisable;
    public List<Adj> applied() { return items.stream().filter(a -> a.rejected == null).toList(); }
    public List<String> violations() { return items.stream().filter(a -> a.rejected != null).map(a -> a.summary + "：" + a.rejected).toList(); }
  }
  public record Result(Effective eff, Plan plan) {}

  /** 在 base 的拷贝上应用调整；base 不变 */
  public static Result apply(Effective base, List<Adj> adjs, String classification, Map<String, Role> roles) {
    return apply(base, adjs, classification, roles, Catalog.SKILLS);
  }
  public static Result apply(Effective base, List<Adj> adjs, String classification, Map<String, Role> roles, Map<String, Skill> skills) {
    return apply(base, adjs, classification, null, roles, skills);
  }
  public static Result apply(Effective base, List<Adj> adjs, String classification, List<String> labels, Map<String, Role> roles, Map<String, Skill> skills) {
    Effective e = Json.convert(base, Effective.class); e.plan = null;
    Map<String, Skill> allSkills = new LinkedHashMap<>(Catalog.SKILLS);
    if (skills != null) allSkills.putAll(skills);
    Plan plan = new Plan(); plan.classification = classification; plan.labels = labels == null || labels.isEmpty() ? null : new ArrayList<>(labels);
    int n = 0;
    for (Adj in : adjs == null ? List.<Adj>of() : adjs) {
      if (in == null) continue;
      Adj a = Json.convert(in, Adj.class); a.summary = null; a.relax = null; a.rejected = null; a.warning = null;
      // 规则基线项不占 AI / 用户调整的条数上限
      if (!"rule".equals(a.source) && !"governance".equals(a.source) && ++n > MAX_ADJUSTMENTS) { a.summary = describe(a); a.rejected = "单个任务最多 " + MAX_ADJUSTMENTS + " 条调整"; plan.items.add(a); continue; }
      applyOne(e, a, roles, allSkills); plan.items.add(a);
    }
    plan.needsApproval = plan.items.stream().anyMatch(a -> a.rejected == null && Boolean.TRUE.equals(a.relax));
    if (!plan.applied().isEmpty() || classification != null) e.plan = plan;
    e.id = null; e.id = PipelineService.hash(e);
    return new Result(e, plan);
  }

  static String describe(Adj a) {
    String st = a.stage == null ? "?" : a.stage; String nm = Catalog.stageLabel(st);
    return switch (a.op == null ? "" : a.op) {
      case "enable" -> "强制执行「" + nm + "」";
      case "disable" -> "关闭「" + nm + "」";
      case "addCheck" -> "「" + nm + "」增加检查 " + a.check;
      case "removeCheck" -> "「" + nm + "」去掉检查 " + a.check;
      case "setParam" -> "「" + nm + "」" + a.param + " = " + (a.value == null ? "?" : fmt(a.value));
      case "setRole" -> "「" + nm + "」" + a.slot + " 换成 " + a.role;
      case "fastTrack" -> "极速通道：对话确认直通，跳过「" + nm + "」";
      case "replaceStage" -> "将「" + nm + "」替换为「" + (a.label != null ? a.label : Catalog.stageLabel(a.targetStage != null ? a.targetStage : st)) + "」";
      case "insertStage" -> "在「" + Catalog.stageLabel(a.after != null ? a.after : "PLANNING") + "」后新增节点「" + (a.label != null ? a.label : nm) + "」";
      case "removeStage" -> "移除工作流节点「" + nm + "」";
      case "bindSkills" -> "为「" + nm + "」挂载技能 " + (a.skills == null || a.skills.isEmpty() ? "及补充要求" : String.join(", ", a.skills));
      default -> "未知调整 " + a.op;
    };
  }
  private static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }

  private static void applyOne(Effective e, Adj a, Map<String, Role> roles, Map<String, Skill> skills) {
    if ("bindSkills".equals(a.op) && a.stage == null) a.stage = "DEV";
    a.summary = describe(a);
    if (a.op == null || !OPS.contains(a.op)) { a.rejected = "不支持的调整类型"; return; }

    // 1. 新增自定义工作流节点（insertStage）
    if ("insertStage".equals(a.op)) {
      if (a.stage == null || !a.stage.matches("[A-Z][A-Z0-9_]{1,30}")) { a.rejected = "自定义节点标识需为大写字母、数字或下划线（2~31 位）"; return; }
      if (Catalog.stageType(a.stage) != null || e.stages.containsKey(a.stage)) { a.rejected = "节点 " + a.stage + " 已存在，请使用 enable 或 replaceStage"; return; }
      String after = a.after != null ? a.after : "PLANNING";
      if (!e.stages.containsKey(after)) { a.rejected = "插入位置的前置节点不存在：" + after; return; }
      StageCfg preset = Catalog.presetStage(a.stage);
      if (preset == null) preset = fromNode(Nodes.def(a.stage));
      StageCfg s = preset != null ? preset : new StageCfg(a.stage);
      if (a.label != null && !a.label.isBlank()) s.label = a.label.trim();
      if (s.label == null) s.label = a.stage;
      s.afterStage = after;
      if (a.anchorPhase != null) s.anchorPhase = a.anchorPhase;
      if (s.anchorPhase == null) s.anchorPhase = ("QA".equals(after) || "ACCEPTANCE".equals(after)) ? "ACCEPTANCE" : "DESIGN";
      if (a.executor != null) s.executor = a.executor;
      if (s.executor == null) s.executor = "ACCEPTANCE".equals(s.anchorPhase) ? "llm_checker" : "llm_maker";
      if ("llm_checker".equals(s.executor) && s.checker == null) s.checker = "review.accept";
      if (!"llm_checker".equals(s.executor) && s.maker == null) s.maker = "design.make";
      if (a.promptTemplate != null && !a.promptTemplate.isBlank()) s.promptTemplate = a.promptTemplate.trim();
      if (a.artifactKey != null && !a.artifactKey.isBlank()) s.artifactKey = a.artifactKey.trim();
      if (s.artifactKey == null) s.artifactKey = a.stage.toLowerCase(Locale.ROOT);
      if (a.requiredFields != null && !a.requiredFields.isEmpty()) s.requiredFields = new ArrayList<>(a.requiredFields);
      if (a.skills != null && !a.skills.isEmpty()) {
        var ok = knownSkills(a, skills); if (!ok.isEmpty()) s.skills = ok;
      }
      if (a.instructions != null && !a.instructions.isBlank()) s.instructions = a.instructions.trim();
      s.enabled = true; s.required = true;
      if ("ACCEPTANCE".equals(s.anchorPhase) && !"llm_checker".equals(s.executor)) { a.rejected = "测试之后只能插入评审类节点（executor 为 llm_checker）"; return; }
      if (!Validator.isCustomValid(s)) { a.rejected = "自定义节点配置不合法（anchorPhase 或 executor 无效）"; return; }
      var errs = Validator.stage(s, roles, "节点 " + s.type);
      if (!errs.isEmpty()) { a.rejected = String.join("；", errs); return; }
      a.relax = false;
      insertAfter(e, after, s);
      e.sources.put("stages." + s.type, "plan:" + (a.source == null ? "chat" : a.source));
      bakeStageSkills(e, s, skills);
      return;
    }

    // 2. 移除节点（removeStage）：可移除已插入的自定义节点，或关闭可选节点
    if ("removeStage".equals(a.op)) {
      if (a.stage == null || !e.stages.containsKey(a.stage)) { a.rejected = "工作流中不存在节点 " + a.stage; return; }
      var type = Catalog.stageType(a.stage);
      if (type == null) {
        e.stages.remove(a.stage); e.sources.remove("stages." + a.stage); e.skillText.remove("stage:" + a.stage);
        a.relax = false; return;
      }
      if (!type.optional()) { a.rejected = "安全底线锁定节点不能移除"; return; }
      StageCfg s = Json.convert(e.stages.get(a.stage), StageCfg.class);
      s.enabled = false; s.required = null; a.relax = true;
      e.stages.put(a.stage, s); e.sources.put("stages." + a.stage, "plan:" + (a.source == null ? "chat" : a.source));
      return;
    }

    var type = a.stage == null ? null : Catalog.stageType(a.stage);
    StageCfg cur = a.stage == null ? null : e.stages.get(a.stage);
    if (type == null && cur == null) { a.rejected = "未知节点（不支持自定义节点）"; return; }
    if (cur != null && Boolean.TRUE.equals(cur.locked)) { a.rejected = "该节点已被模板 / 业务线锁定，不能按任务调整"; return; }
    StageCfg s = cur == null ? new StageCfg(a.stage) : Json.convert(cur, StageCfg.class);

    switch (a.op) {
      case "enable" -> {
        if (type == null || !type.optional()) { a.rejected = "锁定节点类型总是会执行，无需开启"; return; }
        if (s.on() && Boolean.TRUE.equals(s.required)) { a.rejected = "节点已经强制执行"; return; }
        a.relax = false; s.enabled = true; s.required = true; // 可选节点：开启 = 规划器没选也要走
      }
      case "disable" -> {
        if (type != null && !type.optional()) { a.rejected = "锁定节点类型不能关闭"; return; }
        if (!s.on()) { a.rejected = "节点已经关闭"; return; }
        a.relax = true; s.enabled = false; s.required = null;
      }
      case "fastTrack" -> {
        if (a.stage == null || !FAST_TRACKABLE.contains(a.stage)) { a.rejected = "极速通道仅支持跳过 PRD_REVIEW 或 PLANNING，安全底线节点不可跳过"; return; }
        s.enabled = false; s.required = null; s.replacedFrom = "CHAT_DRAFT";
        a.relax = false; // 对话草稿已通过完整性与量化验收标准预检，直通开发无需额外审批
      }
      case "replaceStage" -> {
        if (a.stage == null || !REPLACEABLE.contains(a.stage)) { a.rejected = "只能替换 PRD_REVIEW / SPEC / DESIGN 节点，编码与质量验收底线节点不可替换"; return; }
        String target = a.targetStage != null ? a.targetStage : a.stage;
        StageCfg preset = Catalog.presetStage(target);
        if (preset != null) {
          s.label = preset.label; s.executor = preset.executor; s.artifactKey = preset.artifactKey;
          s.requiredFields = preset.requiredFields != null ? new ArrayList<>(preset.requiredFields) : null;
          s.skills = preset.skills != null ? new ArrayList<>(preset.skills) : null;
          s.promptTemplate = preset.promptTemplate;
        }
        if (a.label != null && !a.label.isBlank()) s.label = a.label.trim();
        if (s.label == null) s.label = Catalog.stageLabel(target);
        if (a.promptTemplate != null && !a.promptTemplate.isBlank()) s.promptTemplate = a.promptTemplate.trim();
        if (a.artifactKey != null && !a.artifactKey.isBlank()) s.artifactKey = a.artifactKey.trim();
        if (s.artifactKey == null) s.artifactKey = target.toLowerCase(Locale.ROOT);
        if (a.skills != null && !a.skills.isEmpty()) {
          var ok = knownSkills(a, skills); if (!ok.isEmpty()) s.skills = ok;
        }
        if (a.instructions != null && !a.instructions.isBlank()) s.instructions = a.instructions.trim();
        s.replacedFrom = a.stage; s.anchorPhase = a.stage; s.enabled = true; s.required = true;
        if ("PRD_REVIEW".equals(a.stage) && s.executor == null) s.executor = "llm_maker_checker";
        else if (s.executor == null) s.executor = "llm_maker";
        a.relax = false;
      }
      case "bindSkills" -> {
        List<String> ok = knownSkills(a, skills);
        boolean hasSkills = !ok.isEmpty();
        boolean hasInstr = a.instructions != null && !a.instructions.isBlank();
        if (!hasSkills && !hasInstr) { a.rejected = "没有可用的技能或补充要求（可用技能：" + String.join(", ", skills.keySet()) + "）"; a.warning = null; return; }
        if (hasSkills) {
          LinkedHashSet<String> merged = new LinkedHashSet<>(s.skills == null ? List.of() : s.skills);
          merged.addAll(ok); s.skills = new ArrayList<>(merged);
        }
        if (hasInstr) s.instructions = a.instructions.trim();
        a.relax = false;
      }
      case "addCheck" -> {
        if (a.check == null || !Catalog.CHECKS.containsKey(a.check)) { a.rejected = "未知检查（可用：" + String.join(", ", Catalog.CHECKS.keySet()) + "）"; return; }
        var l = checksOf(s); if (l.contains(a.check)) { a.rejected = "检查已存在"; return; }
        l.add(a.check); s.checks = l; a.relax = false;
      }
      case "removeCheck" -> {
        if ("QA".equals(a.stage) && "ci".equals(a.check)) { a.rejected = "QA 必须保留 ci 检查（必需证据）"; return; }
        var l = checksOf(s); if (!l.remove(a.check)) { a.rejected = "节点没有这个检查"; return; }
        s.checks = l; a.relax = true;
      }
      case "setParam" -> {
        if (a.param == null || !PARAMS.contains(a.param) || a.value == null || a.value.isNaN()) { a.rejected = "参数只能是 " + String.join(" / ", new TreeSet<>(PARAMS)); return; }
        double v = a.value;
        switch (a.param) {
          case "gate.pass" -> { double old = s.gate == null || s.gate.pass == null ? 1.0 : s.gate.pass; /* 没有显式阈值时无法判断是否收紧，按放松处理（需审批） */ if (s.gate == null) s.gate = new Gate(); s.gate.pass = v; a.relax = v < old; }
          case "judgeSeats" -> { int old = s.judgeSeats == null ? Catalog.MIN_JUDGE_SEATS : s.judgeSeats; s.judgeSeats = (int) v; a.relax = v < old; }
          case "maxChangeLines" -> { int old = s.maxChangeLines == null ? Catalog.MAX_CHANGE_LINES_CEILING : s.maxChangeLines; s.maxChangeLines = (int) v; a.relax = v > old; }
          default -> { int old = s.maxLoops == null ? 3 : s.maxLoops; s.maxLoops = (int) v; a.relax = v > old; }
        }
      }
      case "setRole" -> {
        if (a.slot == null || !Set.of("maker", "checker", "fixer").contains(a.slot)) { a.rejected = "slot 只能是 maker / checker / fixer"; return; }
        switch (a.slot) { case "maker" -> s.maker = a.role; case "checker" -> s.checker = a.role; default -> s.fixer = a.role; }
        a.relax = true; // 换角色可能降低把关强度，一律需要审批
      }
      default -> { a.rejected = "不支持的调整类型"; return; }
    }
    var errs = Validator.stage(s, roles, "节点 " + a.stage);
    if (!errs.isEmpty()) { a.rejected = String.join("；", errs); a.relax = null; return; }
    e.stages.put(a.stage, s);
    if (cur == null) reorder(e);
    e.sources.put("stages." + a.stage, "plan:" + (a.source == null ? "chat" : a.source));
    if ("setRole".equals(a.op) && a.role != null && roles.containsKey(a.role)) e.roles.put(a.role, roles.get(a.role));
    bakeStageSkills(e, s, skills);
  }

  /** 目录节点 → 可插入的节点配置（只限评审 / 智能体类节点；检查类节点用 addCheck） */
  static StageCfg fromNode(Nodes.NodeDef d) {
    if (d == null || !d.available || !("agent".equals(d.executor) || "review".equals(d.executor)) || Catalog.stageType(d.id) != null) return null;
    var s = new StageCfg(d.id); s.label = d.label;
    s.anchorPhase = d.anchorPhase != null ? d.anchorPhase : "review".equals(d.executor) ? "ACCEPTANCE" : "DESIGN";
    s.executor = "review".equals(d.executor) ? "llm_checker" : "llm_maker";
    s.artifactKey = d.outputs == null || d.outputs.isEmpty() ? d.id.toLowerCase(Locale.ROOT) : d.outputs.get(0);
    if (d.skills != null && !d.skills.isEmpty()) s.skills = new ArrayList<>(d.skills);
    s.promptTemplate = d.description;
    return s;
  }
  /** 决策 D7：未知技能只剔除并给出提示（附可用清单），节点保留 */
  static List<String> knownSkills(Adj a, Map<String, Skill> skills) {
    List<String> ok = new ArrayList<>(), bad = new ArrayList<>();
    if (a.skills != null) for (String sk : a.skills) { if (sk == null || sk.isBlank()) continue; (skills.containsKey(sk) ? ok : bad).add(sk); }
    if (!bad.isEmpty()) a.warning = "已忽略未知技能 " + String.join(", ", bad) + "（可用：" + String.join(", ", skills.keySet()) + "）";
    return ok;
  }

  public static void bakeStageSkills(Effective e, StageCfg s, Map<String, Skill> skills) {
    if (s == null || (s.skills == null || s.skills.isEmpty()) && (s.instructions == null || s.instructions.isBlank()) && (s.promptTemplate == null || s.promptTemplate.isBlank())) return;
    StringBuilder sb = new StringBuilder();
    if (s.promptTemplate != null && !s.promptTemplate.isBlank()) sb.append("【节点任务规约（").append(s.label != null ? s.label : s.type).append("）】").append(s.promptTemplate).append("\n");
    if (s.skills != null) for (String k : s.skills) { var x = skills.get(k); if (x != null) sb.append("【技能 ").append(x.name == null ? x.id : x.name).append("】").append(x.instructions).append("\n"); }
    if (s.instructions != null && !s.instructions.isBlank()) sb.append("【节点补充要求】").append(s.instructions).append("\n");
    String txt = sb.toString().trim();
    if (!txt.isEmpty()) e.skillText.put("stage:" + s.type, txt);
  }

  private static void insertAfter(Effective e, String after, StageCfg s) {
    Map<String, StageCfg> next = new LinkedHashMap<>();
    boolean inserted = false;
    for (var en : e.stages.entrySet()) {
      next.put(en.getKey(), en.getValue());
      if (en.getKey().equals(after)) { next.put(s.type, s); inserted = true; }
    }
    if (!inserted) next.put(s.type, s);
    e.stages = next;
  }

  /** QA 未显式配置检查时默认就是 ci */
  private static List<String> checksOf(StageCfg s) { return new ArrayList<>(s.checks != null ? s.checks : "QA".equals(s.type) ? List.of("ci") : List.<String>of()); }
  private static void reorder(Effective e) {
    var m = new LinkedHashMap<String, StageCfg>();
    for (String t : Catalog.ORDER) {
      if (e.stages.containsKey(t)) m.put(t, e.stages.get(t));
      for (var en : e.stages.entrySet()) if (Catalog.stageType(en.getKey()) == null && t.equals(en.getValue().afterStage)) m.put(en.getKey(), en.getValue());
    }
    for (var en : e.stages.entrySet()) m.putIfAbsent(en.getKey(), en.getValue());
    e.stages = m;
  }
}
