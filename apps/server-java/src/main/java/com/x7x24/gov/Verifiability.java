package com.x7x24.gov;

import java.util.*;
import java.util.regex.Pattern;

/** 验收条件可验证性：每条都要能被测试 / 人工明确判定通过与否。数字不是必须——「返回 403」「显示「コース」」「npm test 通过」都可验证。 */
public final class Verifiability {
  private Verifiability() {}
  private static final Pattern NUM = Pattern.compile("\\d");
  private static final Pattern LITERAL = Pattern.compile("[「『“\"'`][^」』”\"'`]{1,80}[」』”\"'`]|(?<![\\w])/[\\w\\-./{}:]+|\\b[\\w\\-]+\\.(?:json|ts|tsx|js|go|java|py|md|yml|yaml|css|html)\\b|\\b[A-Z][A-Za-z]+(?:Error|Exception)\\b");
  private static final Pattern OBSERVABLE = Pattern.compile("(?i)(返回|显示|展示|呈现|跳转|重定向|包含|不包含|等于|一致|相同|保持|保存|持久化|写入|记录|拒绝|允许|禁止|禁用|启用|可见|不可见|隐藏|出现|消失|存在|不存在|通过|失败|报错|抛出|提示|校验|切换为|切换到|变为|改为|生效|无变化|不变|成功|状态码|日志|\\b(?:return(?:s|ed)?|show(?:s|n)?|display(?:s|ed)?|contain(?:s|ed)?|equals?|throw(?:s|n)?|pass(?:es|ed)?|fail(?:s|ed)?|redirect(?:s|ed)?|persist(?:s|ed)?|saved?|render(?:s|ed)?|visible|respond(?:s|ed)?|remain(?:s|ed)?)\\b)");
  private static final Pattern VAGUE = Pattern.compile("(良好|友好|合理|优化|提升|改善|更好|更快|易用|美观|流畅|尽量|适当|尽可能|高效|稳定|完善|规范|清晰|\\b(?:better|nice|good|user-friendly|robust|clean)\\b)", Pattern.CASE_INSENSITIVE);

  /** null=可验证；否则返回不可验证的原因 */
  public static String why(String c) {
    if (c == null || c.isBlank()) return "空条目";
    String s = c.strip();
    boolean anchor = NUM.matcher(s).find() || LITERAL.matcher(s).find() || OBSERVABLE.matcher(s).find();
    if (anchor) return null;
    var v = VAGUE.matcher(s);
    if (v.find()) return "只有主观描述「" + v.group(1) + "」，没有可观察的结果（应写成：输入/操作 → 预期输出、状态码、显示文案、测试命令通过等）";
    return "没有可观察的判定点（应写明操作后返回 / 显示 / 保存了什么，或给出具体数值、状态码、文案、测试命令）";
  }
  public record Issue(int index, String criterion, String reason) {}
  public static List<Issue> check(List<String> criteria) {
    List<Issue> out = new ArrayList<>();
    for (int i = 0; i < criteria.size(); i++) { String w = why(criteria.get(i)); if (w != null) out.add(new Issue(i + 1, criteria.get(i), w)); }
    return out;
  }
  /** 把对话 / Issue 里的「1. xxx」「- xxx」文本拆成条目 */
  public static List<String> parseList(String text) {
    if (text == null || text.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    for (String l : text.split("\\r?\\n")) { String x = l.strip().replaceFirst("^(?:\\d+[.、)]|[-*•]|\\[[ xX]\\])\\s*", "").strip(); if (!x.isEmpty()) out.add(x); }
    return out;
  }
}
