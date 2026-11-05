package com.x7x24.gov;

import java.util.*;
import java.util.regex.Pattern;

/** 规则式风险分级 + 动作级策略闸门（deny 优先，无审批通道）。 */
public final class Risk {
  private Risk() {}
  public static final List<String> ORDER = List.of("low", "mid", "high", "forbidden");
  public static String label(String r) { return switch (r == null ? "" : r) { case "low" -> "低风险"; case "mid" -> "中风险"; case "high" -> "高风险"; case "forbidden" -> "禁区"; default -> "风险未知"; }; }
  public static String max(String... r) { String m = "low"; for (String x : r) if (x != null && ORDER.indexOf(x) > ORDER.indexOf(m)) m = x; return m; }

  public record Assessment(String level, List<String> forbiddenCategories, List<String> matched, List<String> governanceFileHits, List<String> reasons, List<String> matchedPaths, List<String> negated, String basis) {
    public Assessment(String level, List<String> forbiddenCategories, List<String> matched, List<String> governanceFileHits, List<String> reasons) { this(level, forbiddenCategories, matched, governanceFileHits, reasons, List.of(), List.of(), "keywords"); }
    /** 写进废弃详情 / 审计：命中的关键词、路径、被否定忽略的关键词 */
    public Map<String, Object> explain() {
      Map<String, Object> m = new LinkedHashMap<>(); m.put("level", level); m.put("basis", basis); m.put("matchedKeywords", matched);
      if (!matchedPaths.isEmpty()) m.put("matchedPaths", matchedPaths); if (!negated.isEmpty()) m.put("negatedKeywords", negated);
      if (!forbiddenCategories.isEmpty()) m.put("categories", forbiddenCategories); if (!reasons.isEmpty()) m.put("reasons", reasons); return m;
    }
  }

  // 否定词：与关键词处于同一分句、且出现在关键词之前（「除外」可在之后），则该关键词不计入风险
  static final List<String> NEG_BEFORE = List.of("不改", "不修改", "不改动", "不动", "不涉及", "不触及", "无需", "不需要", "不需", "不用", "不包含", "不包括", "不含", "不会", "不得", "禁止改", "避免", "无关", "没有",
      "不新增", "不增加", "不变更", "不调整", "不涉及到", "无需改", "无须", "无改动", "零改动",
      "without", "no changes to", "no change to", "not touch", "not change", "not modify", "don't", "do not", "doesn't", "does not", "no ");
  static final List<String> NEG_AFTER = List.of("除外", "以外不", "不变", "无改动", "无变更", "不改动", "不需要改", "不做改动", "保持不变", "不受影响", "excluded", "unchanged", "untouched");
  private static final Pattern CLAUSE = Pattern.compile("[，,。；;！!？?\n\r]|\\.(\\s|$)");

  /** 关键词在文本中「被肯定地」出现（至少一个分句里命中且未被否定）。 */
  public static boolean hasAffirmed(String text, String kw) { return hits(text, kw) == 1; }
  /** 0=未出现，1=肯定出现，2=只以否定形式出现 */
  public static int hits(String text, String kw) {
    if (text == null || !has(text, kw)) return 0;
    boolean neg = false;
    for (String clause : CLAUSE.split(text)) {
      if (!has(clause, kw)) continue;
      String c = clause.toLowerCase(); int at = c.indexOf(kw.toLowerCase()); if (at < 0) at = c.length();
      String before = c.substring(0, at), after = c.substring(Math.min(c.length(), at));
      boolean n = NEG_BEFORE.stream().anyMatch(before::contains) || NEG_AFTER.stream().anyMatch(after::contains);
      if (!n) return 1; neg = true;
    }
    return neg ? 2 : 0;
  }

  public static boolean has(String text, String kw) {
    String t = text.toLowerCase(), k = kw.toLowerCase();
    if (k.length() <= 3 && k.chars().allMatch(c -> c < 128)) return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(k) + "(?![a-z0-9])").matcher(t).find();
    return t.contains(k);
  }

  // 「不包含 / 非目标 / 评估」这类段落：里面列的是不做的事或 AI 自己的估计，不能拿来判风险
  private static final Pattern EXCLUDED_HEADING = Pattern.compile("(?i)^\\s*(#{1,6}\\s*)?(\\*\\*)?\\s*(不包含|不包括|不做|不在范围|范围外|非目标|排除项?|不涉及|out of scope|out-of-scope|non-goals?|not included|评估)\\s*(\\*\\*)?\\s*[:：]?\\s*$");
  private static final Pattern ANY_HEADING = Pattern.compile("^\\s*(#{1,6}\\s+\\S.*|\\*\\*[^*]+\\*\\*\\s*[:：]?\\s*)$");
  /** 去掉「不包含 / 非目标 / 评估」段落，只留真正要做的内容 */
  public static String scopeText(String text) {
    if (text == null || text.isEmpty()) return "";
    StringBuilder b = new StringBuilder(); boolean skip = false;
    for (String line : text.split("\\R", -1)) {
      if (EXCLUDED_HEADING.matcher(line).matches()) { skip = true; continue; }
      if (ANY_HEADING.matcher(line).matches()) skip = false;
      if (!skip) b.append(line).append('\n');
    }
    return b.toString();
  }

  public static Assessment classify(Governance cfg, String text, List<String> files) { return classify(cfg, text, files, null, null); }

  /**
   * 风险分级：有目标路径（PRD 起草推测 / plan / PR 文件）且给了项目 pathRules 时以路径为主，
   * 关键词为辅（带否定识别；路径模式下关键词最多把等级抬到「关键词等级降一档」）。没有路径时只看关键词。
   */
  public static Assessment classify(Governance cfg, String text, List<String> files, java.util.function.Function<String, String> pathRisk, List<String> targetPaths) {
    String txt = scopeText(text);
    List<String> cats = new ArrayList<>(); List<String> negated = new ArrayList<>();
    cfg.forbidden.forEach((c, kws) -> { for (String k : kws) { int h = hits(txt, k); if (h == 1) { if (!cats.contains(c)) cats.add(c); } else if (h == 2) negated.add(k); } });
    List<String> gf = new ArrayList<>();
    if (files != null) for (String f : files) if (cfg.governanceFiles.stream().anyMatch(f::contains)) gf.add(f);
    List<String> matched = new ArrayList<>(); String kwLevel;
    java.util.function.Function<List<String>, List<String>> pick = kws -> { List<String> r = new ArrayList<>(); for (String k : kws) { int h = hits(txt, k); if (h == 1) r.add(k); else if (h == 2 && !negated.contains(k)) negated.add(k); } return r; };
    var hh = pick.apply(cfg.riskKeywords.high); var hm = pick.apply(cfg.riskKeywords.mid); var hl = pick.apply(cfg.riskKeywords.low);
    if (!hh.isEmpty()) { kwLevel = "high"; matched.addAll(hh); }
    else if (!hm.isEmpty()) { kwLevel = "mid"; matched.addAll(hm); }
    else if (!hl.isEmpty()) { kwLevel = "low"; matched.addAll(hl); }
    else { kwLevel = "mid"; matched.add("(未命中白名单，按 mid 处理)"); }
    String level = kwLevel; String basis = "keywords"; List<String> matchedPaths = new ArrayList<>();
    List<String> tp = targetPaths == null ? List.of() : targetPaths.stream().filter(x -> x != null && !x.isBlank()).toList();
    if (pathRisk != null && !tp.isEmpty()) {
      String pl = "low"; for (String f : tp) { String r = pathRisk.apply(f); matchedPaths.add(f + " → " + r); pl = max(pl, "forbidden".equals(r) ? "high" : r); } // 推测路径不直接判禁区，真实改动在开发阶段由 PathPolicy 强制
      int ki = ORDER.indexOf(kwLevel); String kwCap = hh.isEmpty() && hm.isEmpty() && hl.isEmpty() ? "low" : ORDER.get(Math.max(0, Math.min(ki, 2) - 1));
      level = max(pl, kwCap); basis = "paths";
    }
    List<String> reasons = new ArrayList<>();
    if (!cats.isEmpty()) { level = "forbidden"; reasons.add("触及禁区：" + String.join(",", cats)); }
    if (!gf.isEmpty()) { level = "forbidden"; reasons.add("改动治理文件：" + String.join(",", gf)); }
    return new Assessment(level, cats, matched, gf, reasons, matchedPaths, negated, basis);
  }

  private static final Map<String, List<String>> ALLOWED = Map.of("L1", List.of("low"), "L2", List.of("low", "mid"), "L3", List.of("low", "mid", "high"));
  public static boolean autonomyAllows(String level, String risk) { return ALLOWED.getOrDefault(level, List.of()).contains(risk); }

  // ── 动作级策略 ──
  public record Decision(boolean allowed, String category, String reason) {}
  private static final Map<String, String> FORBIDDEN_ACTIONS = Map.ofEntries(
      Map.entry("payment.charge", "payment"), Map.entry("payment.refund", "payment"), Map.entry("iam.update", "permission"), Map.entry("secret.read", "permission"),
      Map.entry("secret.export", "permission"), Map.entry("db.prod.write", "production_data"), Map.entry("db.prod.ddl", "production_data"), Map.entry("data.bulk_export", "production_data"),
      Map.entry("git.force_push", "repo"), Map.entry("git.delete_branch", "repo"), Map.entry("git.delete_tag", "repo"), Map.entry("git.change_protection", "repo"),
      Map.entry("release.direct", "release"), Map.entry("release.config_change", "release"), Map.entry("git.bypass_protection", "release"));
  private static final List<String> PROTECTED = List.of("main", "master", "release");

  public static Decision evaluateAction(Governance cfg, String type, String target) {
    String cat = FORBIDDEN_ACTIONS.get(type);
    if (cat != null) return new Decision(false, cat, "禁区动作 " + type + "（" + cat + "）：系统直接拒绝，无审批通道");
    String t = target == null ? "" : target.replaceFirst("^refs/heads/", "");
    if (type.equals("git.initialize_repository")) return t.isBlank()
        ? new Decision(false, "repo", "初始化仓库必须指定目标分支")
        : new Decision(true, null, "仅由受控初始化工具创建空仓库的首个 README 提交");
    if (type.equals("git.push") && PROTECTED.contains(t)) return new Decision(false, "release", "禁止直接推送受保护分支 " + target);
    if (type.equals("git.push") && !t.startsWith("agent/")) return new Decision(false, "repo", "开发助手只能推送到 agent/ 开头的分支");
    if (type.equals("net.egress")) {
      String host = target == null ? "" : target.replaceFirst("^https?://", "").split("/")[0];
      if (!cfg.network.allowlist.contains(host)) return new Decision(false, "network", "出口域名 " + host + " 不在白名单");
    }
    return new Decision(true, null, "ok");
  }

  public static class PolicyDenied extends RuntimeException {
    public final Decision decision; public final String action;
    public PolicyDenied(Decision d, String action) { super(d.reason()); this.decision = d; this.action = action; }
  }
}
