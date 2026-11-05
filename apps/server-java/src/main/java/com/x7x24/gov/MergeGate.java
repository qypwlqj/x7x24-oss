package com.x7x24.gov;

import java.util.*;

/**
 * 合并关卡（设计 large-task-decomposition.md §5）：改动大小不决定能不能做，只决定合并前要过哪一道关。
 * 等级 = max(按 PR 加权行数, 按风险)；自治等级允许自动合并的等级之外，合并前需要负责人一键确认（绑定 PR 头提交）。
 */
public final class MergeGate {
  private MergeGate() {}
  public static final List<String> TIERS = List.of("G0", "G1", "G2", "G3");
  public static final Map<String, String> TIER_NAME = Map.of("G0", "小改动", "G1", "较大", "G2", "大", "G3", "很大");

  public record Result(String tier, String sizeTier, String riskTier, int weightedLines, String risk, String autonomy, boolean needsApproval, String reason) {
    public Map<String, Object> toMap() {
      Map<String, Object> m = new LinkedHashMap<>(); m.put("tier", tier); m.put("tierName", TIER_NAME.get(tier)); m.put("sizeTier", sizeTier); m.put("riskTier", riskTier);
      m.put("weightedLines", weightedLines); m.put("risk", risk); m.put("autonomy", autonomy); m.put("needsApproval", needsApproval); m.put("reason", reason); return m;
    }
  }

  public static String sizeTier(Governance.Gate g, int weightedLines) {
    return weightedLines > g.g3Lines ? "G3" : weightedLines > g.g2Lines ? "G2" : weightedLines > g.g1Lines ? "G1" : "G0";
  }
  static String max(String a, String b) { return TIERS.indexOf(a) >= TIERS.indexOf(b) ? a : b; }

  public static Result evaluate(Governance.Gate g, int weightedLines, String risk, String autonomy) {
    if (g == null) g = new Governance.Gate();
    String r = risk == null ? "low" : risk, st = sizeTier(g, weightedLines), rt = g.riskTier.getOrDefault(r, "high".equals(r) ? "G2" : "G0");
    if (!TIERS.contains(rt)) rt = "G0";
    String tier = max(st, rt);
    List<String> auto = g.autoMerge.getOrDefault(autonomy == null ? "L1" : autonomy, List.of());
    boolean high = "high".equals(r) && g.highRiskAlwaysApprove;
    boolean need = high || !auto.contains(tier);
    String why;
    if (!need) why = "改动规模在自动合并范围内";
    else if (high) why = "高风险改动";
    else if (auto.isEmpty()) why = "当前自治等级（" + autonomy + "）的改动都要确认后合并";
    else why = "改动" + TIER_NAME.get(tier) + "（加权 " + weightedLines + " 行" + (TIERS.indexOf(rt) > TIERS.indexOf(st) ? "，风险" + ("mid".equals(r) ? "中" : "高") : "") + "），超过自动合并范围";
    return new Result(tier, st, rt, weightedLines, r, autonomy, need, why);
  }
}
