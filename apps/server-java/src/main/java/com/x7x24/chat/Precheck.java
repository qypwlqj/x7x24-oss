package com.x7x24.chat;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 提交前预检（本地、确定性，不调模型）：必填槽位 + 验收标准可量化 + 自治等级。
 * 与 PRD 评审的硬规则对齐，目的是在源头避免「需补充 / low_value」废弃（决策 3A：不通过不能提交）。
 */
public final class Precheck {
  private Precheck() {}
  public record Result(boolean ok, List<String> problems, List<String> warnings, String risk, List<Map<String, Object>> duplicates, List<Integer> vagueAcceptance) {}

  /** 可量化：数字 / 比较符 / 命令或代码 / 可观察结果的动词 */
  static final Pattern MEASURABLE = Pattern.compile("(?i)(\\d|[<>≤≥=]|`[^`]+`|\\b(test|spec|assert|returns?|status|http|api|cli|exit code|p\\d\\d)\\b|返回|状态码|显示|出现|提示|跳转|包含|不包含|可见|不可见|通过|失败|报错|成功|生成|写入|记录|禁用|启用|按钮|页面|字段|列|行|单元测试|测试用例|断言|日志)");
  /** 只有形容词的说法（单独出现时视为不可验证） */
  static final Pattern VAGUE_ONLY = Pattern.compile("^(?i)\\s*(更|很|比较|尽量|足够)?\\s*(快|好|好用|友好|稳定|流畅|美观|简洁|清晰|优化|提升|改进|完善|易用|fast|better|nicer|clean|improve[ds]?)\\s*[。.!！]?\\s*$");

  public static boolean measurable(String s) {
    if (s == null) return false; String t = s.trim(); if (t.length() < 6 || VAGUE_ONLY.matcher(t).matches()) return false;
    return MEASURABLE.matcher(t).find();
  }

  /**
   * @param projectOk     目标项目在当前工作区内存在且可用
   * @param computedRisk  按规则计算出的风险（Risk.classify）；草稿里的风险只能调高
   * @param riskAllowed   计算后的风险是否在项目自治等级内
   */
  public static Result check(Draft d, boolean projectOk, String computedRisk, java.util.function.Predicate<String> riskAllowed, List<Map<String, Object>> duplicates) {
    List<String> p = new ArrayList<>(), w = new ArrayList<>(); List<Integer> vague = new ArrayList<>();
    if (d.title == null || d.title.trim().length() < 4) p.add("缺少标题（至少 4 个字）");
    else if (d.title.length() > 200) p.add("标题过长（最多 200 字）");
    if (d.goal == null || d.goal.trim().length() < 6) p.add("缺少目标：说明谁、要什么、为什么");
    if (d.scope.isEmpty()) p.add("缺少范围：至少 1 条要做的事");
    if (d.outOfScope.isEmpty()) p.add("缺少「不包含」：至少 1 条明确不做的事");
    if (d.openQuestions != null && !d.openQuestions.isEmpty()) p.add("还有 " + d.openQuestions.size() + " 个待用户确认的问题：" + String.join("；", d.openQuestions));
    if (d.acceptance.isEmpty()) p.add("缺少验收标准：至少 1 条可验证的条件");
    for (int i = 0; i < d.acceptance.size(); i++) if (!measurable(d.acceptance.get(i))) vague.add(i);
    if (!vague.isEmpty()) p.add("验收标准第 " + String.join("、", vague.stream().map(i -> String.valueOf(i + 1)).toList()) + " 条不可验证：请写成数值阈值、可执行的测试 / 命令，或可观察的界面结果");
    if (d.projectId == null || d.projectId.isBlank()) p.add("未选择目标项目");
    else if (!projectOk) p.add("目标项目不存在或不在当前工作区");
    String risk = com.x7x24.gov.Risk.max(computedRisk, normRisk(d.risk));
    if ("forbidden".equals(risk)) p.add("需求触及禁区（风险 forbidden），不能提交");
    else if (projectOk && !riskAllowed.test(risk)) p.add("风险 " + risk + " 超出项目自治等级，不能提交（可拆小范围，或由管理员调整自治等级）");
    if (d.risk != null && normRisk(d.risk) != null && com.x7x24.gov.Risk.ORDER.indexOf(normRisk(d.risk)) < com.x7x24.gov.Risk.ORDER.indexOf(computedRisk)) w.add("草稿风险低于规则判定，已按规则取 " + computedRisk);
    if (duplicates != null && !duplicates.isEmpty()) w.add("可能与已有任务重复：" + String.join("、", duplicates.stream().map(x -> "「" + x.get("title") + "」").toList()));
    if (d.acceptance.size() > 12) w.add("验收标准超过 12 条，建议拆分任务");
    return new Result(p.isEmpty(), p, w, risk, duplicates == null ? List.of() : duplicates, vague);
  }
  static String normRisk(String r) { if (r == null) return null; String x = r.trim().toLowerCase(Locale.ROOT); return com.x7x24.gov.Risk.ORDER.contains(x) ? x : switch (x) { case "低" -> "low"; case "中" -> "mid"; case "高" -> "high"; default -> null; }; }

  /** 简单相似度：字符二元组 Jaccard（中英文都适用） */
  public static double similarity(String a, String b) {
    Set<String> x = grams(a), y = grams(b); if (x.isEmpty() || y.isEmpty()) return 0;
    Set<String> i = new HashSet<>(x); i.retainAll(y); Set<String> u = new HashSet<>(x); u.addAll(y); return (double) i.size() / u.size();
  }
  private static Set<String> grams(String s) {
    String t = s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？、：；（）【】“”‘’]+", ""); Set<String> g = new HashSet<>();
    for (int i = 0; i + 2 <= t.length(); i++) g.add(t.substring(i, i + 2)); return g;
  }
}
