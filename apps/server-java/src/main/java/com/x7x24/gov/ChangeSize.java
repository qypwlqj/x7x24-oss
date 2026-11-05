package com.x7x24.gov;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 变更规模统一口径（开发前预检 / QA diff_within_limits / 上线 within_change_limits 共用）。
 * - 原始行数：新增文件 = 行数；edits / 整文件改写 = 与原文的行级 diff（增 + 删）；删除 = 1。
 * - 加权：release.sizeWeights（glob → 权重 0~1，先匹配先生效），翻译 JSON / 文档 / lock / 生成文件可打折。
 * - 防滥用：打折文件的原始行数合计超过 release.maxExemptLines 时，超出部分按权重 1 计。
 * - 文件数：release.weightFileCount=true 时按权重折算（0.1 权重的翻译文件记 0.1 个，合计向上取整）；否则权重 > 0 的文件各记 1 个。
 * - glob：不以 / 开头的模式在任意目录层级匹配（locales/**&#47;*.json 能匹配 frontend/src/locales/ja/a.json）；以 / 开头则锚定仓库根。
 */
public final class ChangeSize {
  private ChangeSize() {}

  /** deletedLines：其中纯删除的行（按 release.deletionWeight 计） */
  public record Item(String path, int rawLines, int deletedLines) { public Item(String path, int rawLines) { this(path, rawLines, 0); } }
  static final java.util.regex.Pattern TEST = java.util.regex.Pattern.compile("(^|.*/)(tests?|__tests__|e2e)/.*|.*\\.(test|spec)\\.[^/]+$");
  public static boolean isTest(String path) { return TEST.matcher(norm(path)).matches(); }
  public record Result(int files, int rawFiles, int rawLines, int weightedLines, int exemptRawLines, int maxLines, int maxFiles, boolean within, List<Map<String, Object>> details) {
    public String summary() { return (files != rawFiles ? "加权 " + files + " 个文件（实际 " + rawFiles + " 个）" : files + " 个文件") + " / 加权 " + weightedLines + " 行（原始 " + rawLines + " 行；上限 " + maxFiles + " 文件 / " + maxLines + " 行）"; }
    public int overLines() { return Math.max(0, weightedLines - maxLines); }
    public int overFiles() { return Math.max(0, files - maxFiles); }
    /** 超限说明：超出多少、哪些文件最大，用于让 agent 精确收缩 */
    public String overage() {
      StringBuilder b = new StringBuilder(summary());
      if (overLines() > 0) b.append("；超出 ").append(overLines()).append(" 行");
      if (overFiles() > 0) b.append("；超出 ").append(overFiles()).append(" 个文件");
      var top = details.stream().sorted((a, c) -> Double.compare(((Number) c.get("weightedLines")).doubleValue(), ((Number) a.get("weightedLines")).doubleValue())).limit(8).map(d -> d.get("path") + "=" + d.get("weightedLines") + " 行").toList();
      b.append("；最大的文件：").append(String.join("，", top));
      return b.toString();
    }
    public Map<String, Object> toMap() {
      Map<String, Object> m = new LinkedHashMap<>(); m.put("files", files); m.put("rawFiles", rawFiles); m.put("rawLines", rawLines); m.put("weightedLines", weightedLines); m.put("exemptRawLines", exemptRawLines);
      m.put("maxLines", maxLines); m.put("maxFiles", maxFiles); m.put("within", within); m.put("details", details); return m;
    }
  }

  public static int lineCount(String s) { if (s == null || s.isEmpty()) return 0; int n = s.split("\n", -1).length; return s.endsWith("\n") ? n - 1 : n; }

  /** 行级 diff 的增 + 删行数（LCS；超大文件退化为去掉公共前后缀后的上界）。 */
  public static int diffLines(String a, String b) {
    String[] x = a == null || a.isEmpty() ? new String[0] : a.split("\n", -1), y = b == null || b.isEmpty() ? new String[0] : b.split("\n", -1);
    int i0 = 0; while (i0 < x.length && i0 < y.length && x[i0].equals(y[i0])) i0++;
    int ex = x.length, ey = y.length; while (ex > i0 && ey > i0 && x[ex - 1].equals(y[ey - 1])) { ex--; ey--; }
    int n = ex - i0, m = ey - i0;
    if (n == 0 || m == 0) return n + m;
    if ((long) n * m > 4_000_000L) return n + m;
    int[] prev = new int[m + 1], cur = new int[m + 1];
    for (int i = 1; i <= n; i++) {
      for (int j = 1; j <= m; j++) cur[j] = x[i0 + i - 1].equals(y[i0 + j - 1]) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
      int[] t = prev; prev = cur; cur = t;
    }
    return n + m - 2 * prev[m];
  }

  /** 单个文件改动的原始行数：original==null 表示新文件。 */
  public static int rawLines(String original, String updated, boolean delete) {
    if (delete) return 1;
    if (original == null) return lineCount(updated);
    return diffLines(original, updated);
  }

  private static final Map<String, Pattern> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
  /** glob：** 跨目录，* 不跨 /，? 单字符；不含 / 的模式匹配任意目录下的文件名。 */
  public static boolean glob(String pattern, String path) {
    String g = pattern.strip().replace('\\', '/'); if (g.startsWith("./")) g = g.substring(2);
    String p = g.startsWith("/") ? g.substring(1) : g.startsWith("**/") ? g : "**/" + g;
    if (p.endsWith("/")) p = p + "**";
    return CACHE.computeIfAbsent(p, ChangeSize::compile).matcher(norm(path)).matches();
  }
  static String norm(String path) { String x = path == null ? "" : path.strip().replace('\\', '/'); while (x.startsWith("./")) x = x.substring(2); while (x.startsWith("/")) x = x.substring(1); return x; }
  static Pattern compile(String g) {
    StringBuilder r = new StringBuilder();
    for (int i = 0; i < g.length(); i++) {
      char c = g.charAt(i);
      if (c == '*') {
        if (i + 1 < g.length() && g.charAt(i + 1) == '*') { i++; if (i + 1 < g.length() && g.charAt(i + 1) == '/') { i++; r.append("(?:.*/)?"); } else r.append(".*"); }
        else r.append("[^/]*");
      } else if (c == '?') r.append("[^/]");
      else r.append(Pattern.quote(String.valueOf(c)));
    }
    return Pattern.compile(r.toString());
  }
  public static double weightOf(Governance.Release rel, String path) {
    if (rel.sizeWeights != null) for (var e : rel.sizeWeights.entrySet()) if (glob(e.getKey(), path)) return e.getValue();
    return isTest(path) ? Math.max(0, Math.min(1, rel.testWeight)) : 1.0;
  }

  public static Result measure(Governance.Release rel, List<Item> items) {
    int rawFiles = 0, raw = 0, exemptRaw = 0; double weighted = 0, fileUnits = 0; List<Map<String, Object>> details = new ArrayList<>();
    for (var it : items) {
      double w = weightOf(rel, it.path()); raw += it.rawLines();
      double dw = Math.max(0, Math.min(1, rel.deletionWeight)); int del = Math.max(0, Math.min(it.deletedLines(), it.rawLines()));
      double eff = it.rawLines() - del * (1 - dw); // 纯删除行打折
      double wl;
      if (w < 1.0) {
        int room = Math.max(0, rel.maxExemptLines - exemptRaw), disc = Math.min(room, it.rawLines()), full = it.rawLines() - disc;
        exemptRaw += disc; wl = (disc * w + full) * (it.rawLines() == 0 ? 1 : eff / it.rawLines());
      } else wl = eff;
      if (w > 0 || wl > 0) { rawFiles++; fileUnits += rel.weightFileCount && w > 0 ? w : 1; }
      weighted += wl;
      Map<String, Object> d = new LinkedHashMap<>(); d.put("path", it.path()); d.put("rawLines", it.rawLines()); d.put("weight", w); d.put("weightedLines", Math.round(wl * 10) / 10.0); details.add(d);
    }
    int wl = (int) Math.ceil(weighted - 1e-9), files = (int) Math.ceil(fileUnits - 1e-9);
    return new Result(files, rawFiles, raw, wl, exemptRaw, rel.maxChangeLines, rel.maxFiles, wl <= rel.maxChangeLines && files <= rel.maxFiles, details);
  }

  /** 可用的最大段数（不改变每段上限，只允许更多段） */
  public static int splitCap(Governance.Release rel) { return Math.max(1, Math.max(rel.maxSplitParts, rel.maxAutoSplitParts)); }
  /** 把文件按预估 / 实测行数装箱，每段加权行 ≤ lineCap、文件 ≤ fileCap（单个文件超了单独成段）。先放大的。 */
  public static List<List<String>> pack(Governance.Release rel, List<Item> items, double lineCap, double fileCap) {
    List<Item> sorted = new ArrayList<>(items); sorted.sort((a, b) -> Double.compare(b.rawLines() * weightOf(rel, b.path()), a.rawLines() * weightOf(rel, a.path())));
    List<List<String>> bins = new ArrayList<>(); List<double[]> load = new ArrayList<>();
    for (var it : sorted) {
      double w = weightOf(rel, it.path()), l = it.rawLines() * w, f = rel.weightFileCount && w > 0 ? w : 1; int k = -1;
      for (int i = 0; i < bins.size(); i++) if (load.get(i)[0] + l <= lineCap && load.get(i)[1] + f <= fileCap) { k = i; break; }
      if (k < 0) { bins.add(new ArrayList<>()); load.add(new double[2]); k = bins.size() - 1; }
      bins.get(k).add(it.path()); load.get(k)[0] += l; load.get(k)[1] += f;
    }
    return bins;
  }
  public static void validate(Governance.Release rel) {
    if (rel.maxChangeLines < 1 || rel.maxFiles < 1) throw new IllegalArgumentException("release.maxChangeLines / maxFiles 必须 >= 1");
    if (rel.maxExemptLines < 0) throw new IllegalArgumentException("release.maxExemptLines 不能为负");
    if (rel.maxSplitParts < 1 || rel.maxSplitParts > 10) throw new IllegalArgumentException("release.maxSplitParts 必须在 1~10");
    if (rel.maxAutoSplitParts < 1 || rel.maxAutoSplitParts > 20) throw new IllegalArgumentException("release.maxAutoSplitParts 必须在 1~20");
    if (rel.sizeWeights != null) for (var e : rel.sizeWeights.entrySet()) {
      if (e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > 200) throw new IllegalArgumentException("release.sizeWeights 的 glob 不能为空");
      if (e.getValue() == null || e.getValue() < 0 || e.getValue() > 1) throw new com.x7x24.web.UserError("bad_size_weight", "改动规模打折系数必须在 0~1 之间（路径 " + e.getKey() + "）");
    }
  }
}
