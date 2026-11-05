package com.x7x24.pipeline;

import com.x7x24.engine.ProjectCfg;
import com.x7x24.pipeline.Pipeline.*;
import com.x7x24.util.Json;

import java.util.*;

/**
 * 流程组装器（可组合流程 P2，设计 §4）：规则基线 + 治理必选 + 项目策略 + AI 提议 → 校验 ①–⑧ → v2 快照（带流程图）。
 * 底层仍用 {@link Plans#apply} 逐条应用调整（stages 有序表 = 串行拓扑序，决策 D2），组装器负责调整前的准入和调整后的校验与出图。
 */
public final class Assembler {
  private Assembler() {}

  /** 组装上下文：标签、涉及的技术栈、项目配置、治理必选节点（节点 id → 原因）、风险与自治等级 */
  public record Ctx(List<String> labels, List<String> stacks, ProjectCfg project, Map<String, String> mandatory, String risk, String autonomy, Map<String, Nodes.NodeDef> derived) {
    public Ctx(List<String> labels, List<String> stacks, ProjectCfg project, Map<String, String> mandatory, String risk, String autonomy) { this(labels, stacks, project, mandatory, risk, autonomy, Map.of()); }
    public static Ctx none() { return new Ctx(List.of(), List.of(), null, Map.of(), null, null); }
  }

  public static Plans.Result assemble(Effective base, List<Plans.Adj> adjs, String classification, Ctx ctx, Map<String, Role> roles, Map<String, Skill> skills) {
    return assemble(base, adjs, classification, ctx, roles, skills, null);
  }
  /** 重新钉快照：在最新业务线配置上重放已生效的调整；放松标记、提示沿用原计划，保证配置没变时得到同一个快照 */
  public static Plans.Result replay(Effective base, Plans.Plan old, Map<String, Role> roles, Map<String, Skill> skills) {
    var ctx = new Ctx(old.labels, List.of(), null, old.mandatory == null ? Map.of() : old.mandatory, null, null);
    return assemble(base, old.applied(), old.classification, ctx, roles, skills, old);
  }
  static Plans.Result assemble(Effective base, List<Plans.Adj> adjs, String classification, Ctx ctx, Map<String, Role> roles, Map<String, Skill> skills, Plans.Plan replayOf) { return assemble(base, adjs, classification, ctx, roles, skills, replayOf, null); }
  static Plans.Result assemble(Effective base, List<Plans.Adj> adjs, String classification, Ctx ctx, Map<String, Role> roles, Map<String, Skill> skills, Plans.Plan replayOf, Presets.Preset preset) {
    if (ctx == null) ctx = Ctx.none();
    Map<String, String> mandatory = new LinkedHashMap<>(ctx.mandatory() == null ? Map.of() : ctx.mandatory());
    var pol = ctx.project() == null ? null : ctx.project().nodePolicy;
    if (pol != null && pol.mandatory != null) for (String m : pol.mandatory) mandatory.putIfAbsent(m, "项目级必选");
    // 逐行审查最后插入：插在测试之后的节点后加的排在前面，保证它在安全审计等评审节点之前
    { String cr = mandatory.remove("CODE_REVIEW"); if (cr != null) mandatory.put("CODE_REVIEW", cr); }
    Set<String> disabled = pol == null || pol.disabled == null ? Set.of() : new HashSet<>(pol.disabled);
    Set<String> stacks = ctx.project() == null ? Set.of() : new HashSet<>(StackProfiles.of(ctx.project()).stacks);

    Map<String, Nodes.NodeDef> derived = ctx.derived() == null ? Map.of() : ctx.derived();
    List<Plans.Adj> in = new ArrayList<>();
    for (var a : adjs == null ? List.<Plans.Adj>of() : adjs) if (a != null) {
      var x = Json.convert(a, Plans.Adj.class);
      // 已批准的工作区派生节点（D6）：按节点定义补齐配置，AI 只需给出节点 id 与插入位置
      var dd = "insertStage".equals(x.op) && x.stage != null ? derived.get(x.stage) : null;
      if (dd != null) {
        x.label = dd.label; x.anchorPhase = dd.anchorPhase; x.executor = "review".equals(dd.executor) ? "llm_checker" : "llm_maker"; x.promptTemplate = dd.description;
        if (dd.skills != null && !dd.skills.isEmpty()) x.skills = new ArrayList<>(dd.skills); x.artifactKey = dd.outputs.isEmpty() ? null : dd.outputs.get(0);
        if (x.after == null) x.after = "review".equals(dd.executor) ? "QA" : base.stages.containsKey("SPEC") ? "SPEC" : "PLANNING";
      }
      in.add(x);
    }
    // 项目级必选节点：基线里没有、调整里也没加的，补一条治理调整
    for (var en : mandatory.entrySet()) {
      String id = en.getKey(); if (present(base, in, id)) continue;
      var add = governanceAdd(id, en.getValue()); if (add != null) { if ("SPEC".equals(add.after) && !base.stages.containsKey("SPEC")) add.after = "PLANNING"; in.add(add); }
    }

    // ① 准入：AI 只能用项目允许的目录节点；治理必选节点不能删；项目禁用的可选节点规则不再加入
    Map<Integer, String> pre = new HashMap<>(); Map<Integer, String> preWarn = new HashMap<>();
    for (int i = 0; i < in.size(); i++) {
      var a = in.get(i); String node = targetNode(base, a); var def = Nodes.def(node) != null ? Nodes.def(node) : node == null ? null : derived.get(node);
      boolean agent = !"rule".equals(a.source) && !"governance".equals(a.source);
      boolean removal = "removeStage".equals(a.op) || "disable".equals(a.op);
      if (removal && node != null && mandatory.containsKey(node)) { pre.put(i, "治理必选节点不能移除（" + mandatory.get(node) + "）"); continue; }
      if (removal || def == null || !isAdd(a)) continue;
      if (!def.available) { pre.put(i, "节点「" + def.label + "」的执行器尚未提供，暂不能使用"); continue; }
      if (def.optional && disabled.contains(def.id) && !mandatory.containsKey(def.id)) {
        if (agent) pre.put(i, "项目已禁用节点「" + def.label + "」"); else { pre.put(i, "项目已禁用节点「" + def.label + "」"); preWarn.put(i, "规则基线建议的「" + def.label + "」已被项目禁用"); }
        continue;
      }
      if (agent && ctx.project() != null && !Nodes.allowedFor(def, stacks, disabled)) pre.put(i, "节点「" + def.label + "」不适用于本项目技术栈（" + String.join("、", stacks) + "）");
    }

    Plans.Result r = null; List<Plans.Adj> items = null;
    for (int round = 0; round < 3; round++) {
      List<Plans.Adj> kept = new ArrayList<>();
      for (int i = 0; i < in.size(); i++) if (!pre.containsKey(i)) kept.add(in.get(i));
      r = Plans.apply(base, kept, classification, ctx.labels(), roles, skills);
      // 合并回原顺序：被准入拒绝的条目也展示给用户
      items = new ArrayList<>(); int k = 0;
      for (int i = 0; i < in.size(); i++) {
        if (pre.containsKey(i)) { var a = Json.convert(in.get(i), Plans.Adj.class); a.summary = Plans.describe(a); a.relax = null; a.rejected = pre.get(i); a.warning = preWarn.get(i); items.add(a); }
        else items.add(r.plan().items.get(k++));
      }
      // ③ 顺序：带 before 约束的节点必须排在目标之前，否则拒绝插入它的那条调整后重算
      boolean again = false; var order = new ArrayList<>(r.eff().stages.keySet());
      for (int i = 0; i < in.size(); i++) {
        var a = items.get(i); if (a.rejected != null || !"insertStage".equals(a.op)) continue;
        var def = Nodes.def(a.stage); if (def == null) continue;
        int at = order.indexOf(a.stage);
        for (String b : def.before) { int bi = order.indexOf(b); if (at >= 0 && bi >= 0 && at > bi) { pre.put(i, "节点「" + def.label + "」必须在「" + Catalog.stageLabel(b) + "」之前"); again = true; break; } }
      }
      if (!again) break;
    }

    Effective e = r.eff(); Plans.Plan plan = r.plan(); plan.items = items;
    // ⑤ 放松：AI 删掉有风险的目录节点、或插入目录外的自定义节点（决策 D6），都需要审批
    for (var a : plan.items) {
      if (a.rejected != null) continue;
      if ("rule".equals(a.source) || "governance".equals(a.source)) { a.relax = false; continue; } // 规则 / 治理决定的取舍不需要审批（如按改动类型关闭体验设计）
      String node = targetNode(base, a); var def = Nodes.def(node);
      if (("removeStage".equals(a.op) || "disable".equals(a.op)) && def != null && !"none".equals(def.risk) && !"low".equals(def.risk)) a.relax = true;
      if ("insertStage".equals(a.op) && def == null && Catalog.presetStage(a.stage) == null && !derived.containsKey(a.stage)) {
        a.relax = true; a.warning = (a.warning == null ? "" : a.warning + "；") + "目录外的自定义节点需要 owner / admin 审批";
      }
    }
    if (replayOf != null) { var old = replayOf.applied(); for (int i = 0; i < plan.items.size() && i < old.size(); i++) { var a = plan.items.get(i); if (a.rejected == null) { a.relax = old.get(i).relax; a.warning = old.get(i).warning; } } }
    plan.needsApproval = plan.items.stream().anyMatch(a -> a.rejected == null && Boolean.TRUE.equals(a.relax));
    plan.mandatory = mandatory.isEmpty() ? null : mandatory;
    if (replayOf != null) { plan.preset = replayOf.preset; plan.presetTemplate = replayOf.presetTemplate; plan.presetDisable = replayOf.presetDisable; }
    else if (preset != null) { plan.preset = preset.key(); plan.presetTemplate = preset.template; plan.presetDisable = preset.disable == null || preset.disable.isEmpty() ? null : new ArrayList<>(preset.disable); }
    plan.stacks = replayOf != null ? replayOf.stacks : ctx.stacks() == null || ctx.stacks().isEmpty() ? null : new ArrayList<>(ctx.stacks());
    if (plan.labels == null && ctx.labels() != null && !ctx.labels().isEmpty()) plan.labels = new ArrayList<>(ctx.labels());
    e.plan = plan;

    // ②⑦⑧ 依赖、预算、自治等级提示
    List<String> warns = new ArrayList<>();
    Graph g = graph(e, plan);
    Set<String> ids = new HashSet<>(); for (var n : g.nodes) ids.add(n.id);
    for (var n : g.nodes) { var d = Nodes.def(n.id); if (d == null) continue; for (String req : d.requires) if (!ids.contains(req) && !satisfied(e, req)) warns.add("「" + d.label + "」依赖「" + label(req) + "」，但流程里没有"); }
    var cap = ctx.project() == null || ctx.project().budget == null ? null : ctx.project().budget.dailyUsd;
    if (cap != null && cap > 0 && g.estimate.usd > cap) {
      List<String> trim = new ArrayList<>(); for (var n : g.nodes) { var d = Nodes.def(n.id); if (d != null && d.optional && n.mandatory == null && d.cost.usd > 0) trim.add(d.label); }
      warns.add("预计花费 $" + round(g.estimate.usd) + " 超过项目日预算 $" + cap + (trim.isEmpty() ? "" : "，可以裁掉：" + String.join("、", trim)));
    }
    if ("L2".equalsIgnoreCase(ctx.autonomy()) && "high".equalsIgnoreCase(ctx.risk())) warns.add("自治等级 L2：高风险需求需要人工确认后才会自动执行");
    plan.warnings = replayOf != null ? replayOf.warnings : warns.isEmpty() ? null : warns;
    g = graph(e, plan); g.warnings = plan.warnings;
    e.schema = 2; e.graph = g;
    e.id = null; e.id = PipelineService.hash(e);
    return new Plans.Result(e, plan);
  }

  /** 旧快照（schema 1）没有 graph：从线性 stages 派生，来源记为 preset */
  public static Graph graphOf(Effective e) { return e.graph != null ? e.graph : graph(e, e.plan); }
  public static Graph graphOf(Effective e, Plans.Plan plan) { return e.graph != null ? e.graph : graph(e, plan != null ? plan : e.plan); }

  static Graph graph(Effective e, Plans.Plan plan) {
    Graph g = new Graph(); g.estimate = new Estimate();
    Map<String, Plans.Adj> why = new HashMap<>();
    if (plan != null) for (var a : plan.items) {
      if (a.rejected != null || a.stage == null) continue;
      if ("addCheck".equals(a.op) || "removeCheck".equals(a.op)) continue;
      if (!why.containsKey(a.stage) || "governance".equals(a.source)) why.put(a.stage, a);
    }
    Map<String, String> mand = plan == null || plan.mandatory == null ? Map.of() : plan.mandatory;
    String prev = null;
    for (var en : e.stages.entrySet()) {
      var s = en.getValue(); if (s != null && !s.on()) continue;
      String node = Nodes.nodeOfStage(en.getKey(), s); var d = Nodes.def(node);
      GNode n = new GNode(); n.id = node; n.stage = en.getKey();
      n.def = d == null ? null : d.key();
      n.label = s != null && s.label != null ? s.label : d != null ? d.label : Catalog.stageLabel(en.getKey());
      n.module = d != null ? d.module : "production"; n.family = d != null ? d.family : ("ACCEPTANCE".equals(s == null ? null : s.phase()) ? "delivery" : "design");
      n.executor = d != null ? d.executor : s != null && "llm_checker".equals(s.executor) ? "review" : "agent";
      if (s != null && s.skills != null && !s.skills.isEmpty()) n.skills = new ArrayList<>(s.skills);
      if ("QA".equals(en.getKey())) n.checks = s == null || s.checks == null ? List.of("ci") : new ArrayList<>(s.checks);
      String src = String.valueOf(e.sources.get("stages." + en.getKey()));
      var a = why.get(en.getKey());
      n.source = a != null && "governance".equals(a.source) ? "governance" : src.startsWith("plan:rule") ? "rule" : src.startsWith("plan:governance") ? "governance" : src.startsWith("plan:") ? "agent" : "preset";
      if (a != null) n.reason = a.reason;
      n.mandatory = mand.get(node); if (n.mandatory == null && d != null && "always".equals(d.mandatory)) n.mandatory = "始终必选";
      var t = Catalog.stageType(en.getKey());
      if (t != null && t.optional() && (s == null || !Boolean.TRUE.equals(s.required))) n.optional = true;
      g.nodes.add(n);
      if (prev != null) g.edges.add(List.of(prev, node));
      prev = node;
      if (d != null) { g.estimate.llmCalls += d.cost.llmCalls; g.estimate.usd += d.cost.usd; g.estimate.minutes += d.cost.minutes; }
      else if (s != null && s.executor != null && s.executor.startsWith("llm")) { g.estimate.llmCalls += 2; g.estimate.usd += 0.02; g.estimate.minutes += 2; }
      // QA 的附加检查以检查类节点列出（在 QA 内执行）
      if ("QA".equals(en.getKey()) && n.checks != null) for (String c : n.checks) {
        if ("ci".equals(c)) continue;
        var cd = Nodes.NODES.values().stream().filter(x -> c.equals(x.check)).findFirst().orElse(null);
        GNode cn = new GNode(); cn.id = cd != null ? cd.id : "CHECK:" + c; cn.def = cd == null ? null : cd.key(); cn.stage = "QA"; cn.parent = node;
        cn.label = cd != null ? cd.label : c; cn.module = "production"; cn.family = "check"; cn.executor = "check"; cn.checks = List.of(c);
        var ca = plan == null ? null : plan.items.stream().filter(x -> x.rejected == null && "addCheck".equals(x.op) && c.equals(x.check)).findFirst().orElse(null);
        cn.source = ca == null ? "preset" : "rule".equals(ca.source) ? "rule" : "governance".equals(ca.source) ? "governance" : "agent";
        if (ca != null) cn.reason = ca.reason;
        cn.mandatory = cd == null ? null : mand.get(cd.id);
        g.nodes.add(cn);
      }
    }
    g.estimate.usd = round(g.estimate.usd); g.estimate.minutes = round(g.estimate.minutes);
    return g;
  }

  static boolean isAdd(Plans.Adj a) { return "insertStage".equals(a.op) || "replaceStage".equals(a.op) || "enable".equals(a.op) || "addCheck".equals(a.op); }
  /** 调整作用到的目录节点 id */
  static String targetNode(Effective base, Plans.Adj a) {
    if (a == null || a.op == null) return null;
    return switch (a.op) {
      case "insertStage" -> a.stage;
      case "replaceStage" -> a.targetStage != null ? a.targetStage : a.stage;
      case "addCheck", "removeCheck" -> a.check == null ? null : Nodes.NODES.values().stream().filter(x -> a.check.equals(x.check)).map(x -> x.id).findFirst().orElse(null);
      default -> a.stage == null ? null : Nodes.nodeOfStage(a.stage, base == null ? null : base.stages.get(a.stage));
    };
  }
  static boolean present(Effective base, List<Plans.Adj> adjs, String id) {
    for (var en : base.stages.entrySet()) {
      if (!id.equals(Nodes.nodeOfStage(en.getKey(), en.getValue()))) continue;
      var t = Catalog.stageType(en.getKey()); var s = en.getValue();
      if (t == null ? s == null || s.on() : !t.optional() || s != null && s.on() && Boolean.TRUE.equals(s.required)) return true;
    }
    for (var a : adjs) if (isAdd(a) && id.equals(targetNode(base, a))) return true;
    return false;
  }
  /** 治理 / 项目必选节点 → 一条治理调整；内置可选阶段用 enable，检查类用 addCheck，其余插入 */
  static Plans.Adj governanceAdd(String id, String reason) {
    var d = Nodes.def(id); if (d == null || !d.available) return null;
    var a = new Plans.Adj(); a.source = "governance"; a.reason = reason;
    if ("check".equals(d.executor)) { a.op = "addCheck"; a.stage = "QA"; a.check = d.check; return d.check == null ? null : a; }
    if (Catalog.stageType(d.stage) != null) { a.op = "enable"; a.stage = d.stage; return Catalog.stageType(d.stage).optional() ? a : null; }
    a.op = "insertStage"; a.stage = d.id; a.label = d.label; a.after = "ACCEPTANCE".equals(d.anchorPhase) ? "QA" : "SPEC"; // 设计类节点排在需求策划之后（状态机 SPEC → DESIGN 只能前进）
    return a;
  }
  static boolean satisfied(Effective e, String req) {
    var s = e.stages.get(req); return s != null && "CHAT_DRAFT".equals(s.replacedFrom); // 极速通道：对话草稿替代 PRD 评审 / 规划
  }
  static String label(String id) { var d = Nodes.def(id); return d == null ? Catalog.stageLabel(id) : d.label; }
  static double round(double v) { return Math.round(v * 100) / 100.0; }
}
