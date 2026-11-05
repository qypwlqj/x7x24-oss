package com.x7x24.review;

import com.x7x24.gov.Governance;
import com.x7x24.gov.MergeGate;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 逐行审查从 G1 起才组装（G0 小改动不加）。G1 里它替代设计中的「第二轮委员会评审」。
 * 组装发生在规划时，此时还没有 PR，所以按预估等级：max（预估规模档位，风险档位）——
 * 规模档位来自对话草稿的规模（XS / S → G0，M → G1，L → G2，XL → G3），风险档位与合并关卡共用 gate.riskTier（默认 mid → G1、high → G2）。
 * 实际 PR 的等级（MergeGate.evaluate，按加权行数）在节点执行时记进结果，供观察期对照。
 */
public final class CodeReviewGate {
  private CodeReviewGate() {}
  public static final String NODE = "CODE_REVIEW";
  static final Map<String, String> SIZE_TIER = Map.of("XS", "G0", "S", "G0", "M", "G1", "L", "G2", "XL", "G3");

  /** 预估等级（G0~G3） */
  public static String estimatedTier(String size, String risk, Governance.Gate gate) {
    if (gate == null) gate = new Governance.Gate();
    String s = size == null ? "" : size.trim().toUpperCase(Locale.ROOT), r = norm(risk);
    String st = SIZE_TIER.getOrDefault(s, "G0"), rt = gate.riskTier.getOrDefault(r, "G0");
    if (!MergeGate.TIERS.contains(rt)) rt = "G0";
    return MergeGate.TIERS.indexOf(st) >= MergeGate.TIERS.indexOf(rt) ? st : rt;
  }
  /** 组装原因；null = 不组装 */
  public static String reason(String size, String risk, Governance g) {
    var c = g == null || g.codeReview == null ? new Governance.CodeReview() : g.codeReview;
    if (!c.enabled) return null;
    String tier = estimatedTier(size, risk, g == null ? null : g.gate);
    int min = Math.max(0, MergeGate.TIERS.indexOf(c.minTier == null ? "G1" : c.minTier));
    if (MergeGate.TIERS.indexOf(tier) < min) return null;
    return "预估改动" + MergeGate.TIER_NAME.get(tier) + "（" + tier + "）：测试后做逐行审查";
  }
  static String norm(String risk) { String r = risk == null ? "low" : risk.trim().toLowerCase(Locale.ROOT); return "medium".equals(r) ? "mid" : r; }
  public static final List<String> TIERS = MergeGate.TIERS;
}
