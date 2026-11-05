package com.x7x24.gov;

import java.util.*;
import java.util.regex.*;

/** Canary Judge：纯规则/统计，不使用 LLM。Argo AnalysisTemplate 语义：每个指标采样 count 次，失败采样数 > failureLimit 即失败；样本不足 => inconclusive。 */
public final class CanaryJudge {
  private CanaryJudge() {}
  private static final Pattern COND = Pattern.compile("^result\\s*(<=|>=|<|>|==|!=)\\s*(-?\\d+(?:\\.\\d+)?)$");

  public static boolean eval(String cond, double result) {
    Matcher m = COND.matcher(cond.trim()); if (!m.matches()) throw new com.x7x24.web.UserError("bad_canary_condition", "灰度发布的成功条件写法不对：" + cond);
    double v = Double.parseDouble(m.group(2));
    return switch (m.group(1)) { case "<" -> result < v; case "<=" -> result <= v; case ">" -> result > v; case ">=" -> result >= v; case "==" -> result == v; default -> result != v; };
  }
  /** high 风险按严格度收紧 < / <= 阈值 */
  public static String tighten(String cond, double strictness) {
    Matcher m = Pattern.compile("^result\\s*(<=|<)\\s*(-?\\d+(?:\\.\\d+)?)$").matcher(cond.trim());
    if (!m.matches() || strictness >= 1) return cond;
    double v = Double.parseDouble(m.group(2)) * strictness;
    return "result " + m.group(1) + " " + (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v));
  }

  public record MetricResult(String name, int samples, int failed, int failureLimit, boolean ok, String condition) {}
  public record Judgement(String decision, List<MetricResult> metrics, List<String> reasons) {}

  public static Judgement judge(Map<String, List<Double>> samples, List<Governance.Metric> specs, double strictness) {
    List<MetricResult> out = new ArrayList<>(); List<String> reasons = new ArrayList<>(); boolean inconclusive = false;
    for (var s : specs) {
      String cond = tighten(s.successCondition, strictness); int limit = strictness < 1 ? 0 : s.failureLimit;
      List<Double> xs = samples.getOrDefault(s.name, List.of());
      int failed = (int) xs.stream().filter(x -> !eval(cond, x)).count(); boolean ok = failed <= limit;
      out.add(new MetricResult(s.name, xs.size(), failed, limit, ok, cond));
      if (!ok) reasons.add(s.name + ": " + failed + "/" + xs.size() + " 次不满足 " + cond + "（failureLimit=" + limit + "）");
      if (xs.size() < s.count) inconclusive = true;
    }
    if (!reasons.isEmpty()) return new Judgement("fail", out, reasons);
    return new Judgement(inconclusive ? "inconclusive" : "pass", out, inconclusive ? List.of("样本不足") : List.of());
  }
}
