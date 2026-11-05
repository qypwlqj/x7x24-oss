package com.x7x24.pipeline;

import com.x7x24.pipeline.Pipeline.*;

import java.util.*;

/** 校验规则（设计文档 §5）。保存模板 / 绑定时调用；返回错误列表，空表示通过。 */
public final class Validator {
  private Validator() {}
  public static final Set<String> EXECUTORS = Set.of("llm_maker", "llm_maker_checker", "llm_checker", "rule_check");

  public static boolean isCustomValid(StageCfg s) {
    return s != null && s.type != null && s.type.matches("[A-Z][A-Z0-9_]{1,30}")
        && s.anchorPhase != null && Catalog.stageType(s.anchorPhase) != null
        && s.executor != null && EXECUTORS.contains(s.executor);
  }

  public static List<String> template(Template t, Map<String, Role> roles) {
    List<String> e = new ArrayList<>();
    if (t.id == null || !t.id.matches("[a-z0-9][a-z0-9-]{1,40}")) e.add("模板 id 只能用小写字母、数字和 -（2~41 位）");
    if (t.stages == null || t.stages.isEmpty()) { e.add("模板必须包含节点"); return e; }
    List<String> types = t.stages.stream().map(s -> s.type).toList();
    // 规则 1/2：节点类型合法（内置节点或带合法 anchorPhase+executor 的自定义节点）、按固定顺序、锁定节点必须存在且开启
    for (var s : t.stages) {
      if (Catalog.stageType(s.type) == null && !isCustomValid(s)) e.add("未知节点类型 " + s.type + "（自定义节点需指定合法的 anchorPhase 与 executor）");
    }
    if (new HashSet<>(types).size() != types.size()) e.add("节点类型重复");
    List<String> known = t.stages.stream().map(StageCfg::phase).filter(x -> Catalog.stageType(x) != null).toList();
    List<String> sorted = new ArrayList<>(known); sorted.sort(Comparator.comparingInt(Catalog.ORDER::indexOf));
    if (!known.equals(sorted)) e.add("节点顺序必须是 " + String.join(" → ", Catalog.ORDER));
    for (var st : Catalog.STAGE_TYPES) {
      var s = t.stage(st.type());
      if (!st.optional() && (s == null || !s.on())) e.add("锁定节点 " + st.type() + "（" + st.name() + "）不能删除或关闭");
    }
    for (var s : t.stages) e.addAll(stage(s, roles, "节点 " + s.type));
    // 规则 3：schema 兼容。关掉 SPEC 时验收用例由 PRD 验收条件生成（ACCEPTANCE 已支持无 acceptance_cases），无需额外约束
    // 自愈计划
    if (t.heal != null) for (var en : t.heal.entrySet()) {
      if (en.getValue() == null || en.getValue().isEmpty()) e.add("自愈计划 " + en.getKey() + " 不能为空");
      else for (String a : en.getValue()) if (!Catalog.HEAL_ACTIONS.contains(a)) e.add("未知自愈动作 " + a);
    }
    if (t.maxHeals != null && (t.maxHeals < 1 || t.maxHeals > 10)) e.add("maxHeals 必须在 1~10");
    return e;
  }

  /** 单个节点参数（模板节点与业务线覆盖共用） */
  public static List<String> stage(StageCfg s, Map<String, Role> roles, String where) {
    List<String> e = new ArrayList<>(); var st = Catalog.stageType(s.phase()); if (st == null) return e;
    if (Catalog.stageType(s.type) != null && s.enabled != null && !s.enabled && !st.optional() && !"CHAT_DRAFT".equals(s.replacedFrom)) e.add(where + " 是锁定节点，不能关闭");
    // 规则 4：角色槽位类型匹配；规则 5：maker 与 checker 不能是同一角色
    slot(e, where, "maker", s.maker, "maker", st, roles); slot(e, where, "fixer", s.fixer, "maker", st, roles); slot(e, where, "checker", s.checker, "checker", st, roles);
    if (s.maker != null && s.maker.equals(s.checker)) e.add(where + "：maker 与 checker 不能是同一角色");
    if (s.gate != null) {
      if (s.gate.pass != null && (s.gate.pass < 0 || s.gate.pass > 1)) e.add(where + "：gate.pass 必须在 0~1");
      if (s.gate.revise != null && (s.gate.revise < 0 || s.gate.revise > 1)) e.add(where + "：gate.revise 必须在 0~1");
      if (s.gate.pass != null && s.gate.revise != null && s.gate.revise > s.gate.pass) e.add(where + "：gate.revise 不能高于 gate.pass");
    }
    if (s.maxLoops != null && (s.maxLoops < 1 || s.maxLoops > 10)) e.add(where + "：maxLoops 必须在 1~10");
    if (s.judgeSeats != null && s.judgeSeats < Catalog.MIN_JUDGE_SEATS) e.add(where + "：评审席位至少 " + Catalog.MIN_JUDGE_SEATS + " 席");
    if (s.maxChangeLines != null && (s.maxChangeLines < 1 || s.maxChangeLines > Catalog.MAX_CHANGE_LINES_CEILING)) e.add(where + "：maxChangeLines 必须在 1~" + Catalog.MAX_CHANGE_LINES_CEILING + "（全局上限）");
    if (s.checks != null) for (String c : s.checks) if (!Catalog.CHECKS.containsKey(c)) e.add(where + "：未知附加检查 " + c);
    if (s.checks != null && s.type.equals("QA") && !s.checks.contains("ci")) e.add(where + "：QA 必须保留 ci 检查（必需证据）");
    return e;
  }
  private static void slot(List<String> e, String where, String slot, String roleId, String kind, Catalog.StageType st, Map<String, Role> roles) {
    if (roleId == null) return;
    if (!st.slots().contains(slot)) { e.add(where + "：节点没有 " + slot + " 槽位"); return; }
    var r = roles.get(roleId); if (r == null) { e.add(where + "：未知角色 " + roleId); return; }
    if (!kind.equals(r.kind)) e.add(where + "：" + slot + " 槽位需要 " + kind + " 角色，" + roleId + " 是 " + r.kind);
  }

  /** 派生角色：只能加技能、换档位、追加指令，不能改 kind / schema（规则：节点间契约不变） */
  public static List<String> role(Role r, Map<String, Skill> skills) {
    List<String> e = new ArrayList<>();
    if (r.id == null || !r.id.matches("[a-z0-9][a-z0-9.-]{1,60}")) e.add("角色 id 不合法：" + r.id);
    else if (Catalog.ROLES.containsKey(r.id)) e.add("角色 id " + r.id + " 与内置角色重名");
    var base = r.extendsRole == null ? null : Catalog.ROLES.get(r.extendsRole);
    if (base == null) { e.add("角色 " + r.id + " 必须用 extendsRole 继承一个内置角色"); return e; }
    if (r.kind != null && !r.kind.equals(base.kind)) e.add("角色 " + r.id + " 不能修改 kind");
    if (r.schema != null && !r.schema.equals(base.schema)) e.add("角色 " + r.id + " 不能修改输出 schema");
    if (r.skills != null) for (String s : r.skills) if (!skills.containsKey(s)) e.add("角色 " + r.id + " 引用了不存在的技能 " + s);
    if (r.instructions != null && r.instructions.length() > 4000) e.add("角色 " + r.id + " 追加指令不能超过 4000 字");
    return e;
  }
}
