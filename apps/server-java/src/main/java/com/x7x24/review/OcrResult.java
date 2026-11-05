package com.x7x24.review;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * open-code-review（ocr v1.13.5）`--format json` 结果的解析与判定。字段见上游 cmd/opencodereview/output.go 与 internal/model/review.go：
 * status（complete / partial / failed / skipped / completed_with_*）、comments[]（path、content、start_line、end_line、category、severity、
 * suggestion_code、existing_code）、summary（tokens、budget_exceeded）、session_id、manifest.coverage（selected / completed / reused / failed / waived）。
 * <p>start_line、end_line 都是 0 表示 ocr 没能定位到行：单独列出，不参与阻断判定。
 */
public record OcrResult(String status, String message, String sessionId, String model, long inputTokens, long outputTokens, long totalTokens, boolean budgetExceeded,
                        List<Finding> findings, List<Finding> unlocated, List<String> selected, List<String> reviewed, List<FileFailure> failed, List<FileFailure> waived,
                        int groups, String resumedFrom) {
  public static final List<String> SEVERITIES = List.of("critical", "high", "medium", "low");
  public static final List<String> CATEGORIES = List.of("bug", "security", "performance", "maintainability", "test", "style", "documentation", "other");

  public record Finding(String path, int startLine, int endLine, String severity, String category, String content, String existingCode, String suggestion) {
    /** 阻断规则：critical，或 high 且是 bug / security；定位失败的意见不阻断 */
    public boolean blocking() { return located() && ("critical".equals(severity) || "high".equals(severity) && ("bug".equals(category) || "security".equals(category))); }
    public boolean located() { return startLine > 0 || endLine > 0; }
    public Map<String, Object> map() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("path", path); m.put("startLine", startLine); m.put("endLine", endLine); m.put("severity", severity); m.put("category", category); m.put("content", content);
      if (existingCode != null && !existingCode.isBlank()) m.put("existingCode", existingCode);
      if (suggestion != null && !suggestion.isBlank()) m.put("suggestion", suggestion);
      m.put("blocking", blocking()); if (!located()) m.put("unlocated", true);
      return m;
    }
  }
  /** 没审成的文件：classification 来自 ocr（timeout / budget / llm / …），reason 是原文 */
  public record FileFailure(String path, String classification, String reason) {
    public Map<String, Object> map() { Map<String, Object> m = new LinkedHashMap<>(); m.put("path", path); m.put("classification", classification); m.put("reason", reason); return m; }
  }

  /** 全部选中的文件都审完了（或者没有需要审的文件） */
  public boolean complete() { return failed.isEmpty() && ("complete".equals(status) || "skipped".equals(status) || "success".equals(status) || "completed_with_warnings".equals(status)); }
  /** 只因为 Token 预算用完而没审完：重试也审不完，按「部分审查」继续 */
  public boolean onlyBudget() { return !failed.isEmpty() && failed.stream().allMatch(f -> "budget".equals(f.classification())); }
  public List<Finding> blocking() { return findings.stream().filter(Finding::blocking).toList(); }
  public Map<String, Integer> bySeverity() {
    Map<String, Integer> m = new LinkedHashMap<>(); for (String s : SEVERITIES) m.put(s, 0);
    for (var f : findings) m.merge(f.severity(), 1, Integer::sum);
    for (var f : unlocated) m.merge(f.severity(), 1, Integer::sum);
    return m;
  }
  public int total() { return findings.size() + unlocated.size(); }

  public static OcrResult parse(JsonNode n) {
    if (n == null || !n.isObject()) throw new IllegalArgumentException("ocr 没有返回 JSON 结果");
    var sum = n.path("summary");
    List<Finding> located = new ArrayList<>(), unloc = new ArrayList<>(); Set<String> seen = new HashSet<>();
    for (var c : n.path("comments")) {
      String path = text(c, "path"), content = text(c, "content");
      if (path.isBlank() || content.isBlank()) continue;
      int s = Math.max(0, c.path("start_line").asInt(0)), e = Math.max(0, c.path("end_line").asInt(0));
      if (s == 0 && e > 0) s = e; if (e < s) e = s;
      String sev = text(c, "severity").toLowerCase(Locale.ROOT); if (!SEVERITIES.contains(sev)) sev = "medium";
      String cat = text(c, "category").toLowerCase(Locale.ROOT); if (!CATEGORIES.contains(cat)) cat = "other";
      // ocr 多轮审查可能报出完全相同的意见：按位置 + 内容去重
      if (!seen.add(path + "\u0000" + s + "\u0000" + e + "\u0000" + content)) continue;
      var f = new Finding(path, s, e, sev, cat, content, blank(text(c, "existing_code")), blank(text(c, "suggestion_code")));
      (f.located() ? located : unloc).add(f);
    }
    Comparator<Finding> order = Comparator.<Finding>comparingInt(f -> SEVERITIES.indexOf(f.severity())).thenComparing(Finding::path).thenComparingInt(Finding::startLine);
    located.sort(order); unloc.sort(order);
    var cov = n.path("manifest").path("coverage");
    List<String> selected = paths(cov.path("selected")), reviewed = new ArrayList<>(paths(cov.path("completed")));
    for (String p : paths(cov.path("reused"))) if (!reviewed.contains(p)) reviewed.add(p);
    List<FileFailure> failed = failures(cov.path("failed")), waived = failures(cov.path("waived"));
    String status = text(n, "status"); if (status.isBlank()) status = "failed";
    if (!cov.isObject()) {
      // 没有 manifest（例如没有需要审的文件）：用 groups 和 files_reviewed 估计
      for (var g : n.path("groups")) for (var f : g.path("files")) if (!reviewed.contains(f.asText())) reviewed.add(f.asText());
      if (selected.isEmpty()) selected = List.copyOf(reviewed);
      if ("failed".equals(status) && failed.isEmpty()) for (String p : selected) failed.add(new FileFailure(p, "unknown", text(n, "message")));
    }
    String model = text(n.path("llm"), "model"); if (model.isBlank()) model = text(n.path("manifest").path("execution"), "model");
    return new OcrResult(status, text(n, "message"), blank(text(n, "session_id")), blank(model), sum.path("input_tokens").asLong(0), sum.path("output_tokens").asLong(0),
        sum.path("total_tokens").asLong(0), sum.path("budget_exceeded").asBoolean(false), List.copyOf(located), List.copyOf(unloc), List.copyOf(selected), List.copyOf(reviewed),
        List.copyOf(failed), List.copyOf(waived), n.path("groups").size(), blank(text(n.path("resume"), "resumed_from")));
  }
  private static List<String> paths(JsonNode arr) { List<String> out = new ArrayList<>(); for (var x : arr) { String p = text(x, "path"); if (!p.isBlank() && !out.contains(p)) out.add(p); } return out; }
  private static List<FileFailure> failures(JsonNode arr) { List<FileFailure> out = new ArrayList<>(); for (var x : arr) out.add(new FileFailure(text(x, "path"), blank(text(x, "classification")), com.x7x24.util.U.ellipsis(text(x, "reason"), 300))); return out; }
  private static String text(JsonNode n, String k) { var v = n == null ? null : n.get(k); return v == null || v.isNull() ? "" : v.asText(""); }
  private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
}
