package com.x7x24.review;

import com.x7x24.adapters.DeliveryTools.FilePatch;
import com.x7x24.util.Json;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 没有 runner / 没有模型凭据时（离线演示、测试）的逐行审查：不调模型，按确定性规则产出和 ocr v1.13.5 同样格式的 JSON，
 * 再走同一套解析与判定。规则：新增行里的明文密钥 → critical / security；console.log → low / maintainability。
 * 会话在内存里：--resume 时上次审完、改动没变的文件直接沿用（reused），只重审没审完的。
 */
public class RuleOcrRunner implements OcrRunner {
  /** diff 里带这个标记时模拟「连不上模型接口」（该文件审查失败） */
  public static final String UNREACHABLE_FIXTURE = "X7_FIXTURE_REVIEW_UNREACHABLE";
  /** 带这个标记的行产出一条定位失败的意见（start_line = 0） */
  public static final String UNLOCATED_FIXTURE = "X7_FIXTURE_REVIEW_UNLOCATED";
  private final Map<String, Map<String, Done>> sessions = new ConcurrentHashMap<>();
  record Done(String fingerprint, List<Map<String, Object>> comments) {}
  @Override public boolean usesModels() { return false; }

  @Override public Outcome review(Request r, BooleanSupplier cancelled, Consumer<String> log) {
    if (cancelled.getAsBoolean()) return Outcome.failed("CANCELLED", "已暂停");
    String sid = UUID.randomUUID().toString();
    var prev = r.resume() == null ? Map.<String, Done>of() : sessions.getOrDefault(r.resume(), Map.of());
    Map<String, Done> now = new LinkedHashMap<>();
    List<Map<String, Object>> comments = new ArrayList<>(), selected = new ArrayList<>(), completed = new ArrayList<>(), reused = new ArrayList<>(), failed = new ArrayList<>();
    List<Map<String, Object>> groups = new ArrayList<>();
    for (FilePatch p : r.patches() == null ? List.<FilePatch>of() : r.patches()) {
      if ("removed".equals(p.status()) || p.patch() == null) continue;
      String fp = Integer.toHexString(p.patch().hashCode()); var item = Map.<String, Object>of("path", p.path(), "fingerprint", fp);
      selected.add(item); groups.add(Map.of("label", p.path(), "files", List.of(p.path())));
      var old = prev.get(p.path());
      if (old != null && old.fingerprint().equals(fp)) { reused.add(item); comments.addAll(old.comments()); now.put(p.path(), old); continue; }
      if (p.patch().contains(UNREACHABLE_FIXTURE)) {
        Map<String, Object> f = new LinkedHashMap<>(item); f.put("classification", "timeout"); f.put("reason", "LLM request timed out（离线规则版模拟：连不上接口）"); failed.add(f); continue;
      }
      var cs = scan(p); comments.addAll(cs); completed.add(item); now.put(p.path(), new Done(fp, cs));
    }
    sessions.put(sid, now);
    String status = selected.isEmpty() ? "skipped" : failed.isEmpty() ? "complete" : completed.isEmpty() && reused.isEmpty() ? "failed" : "partial";
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("status", status); doc.put("llm", Map.of("model", "rule-ocr")); doc.put("message", "离线规则版逐行审查");
    doc.put("summary", Map.of("files_reviewed", completed.size() + reused.size(), "comments", comments.size(), "total_tokens", 0, "input_tokens", 0, "output_tokens", 0, "elapsed", "0s"));
    doc.put("comments", comments); doc.put("groups", groups); doc.put("session_id", sid);
    if (r.resume() != null) doc.put("resume", Map.of("resumed_from", r.resume(), "reused_files", reused.size(), "rerun_files", completed.size() + failed.size()));
    doc.put("manifest", Map.of("schema_version", "ocr.run-manifest/v1", "run_id", sid, "terminal_state", status,
        "coverage", Map.of("selected", selected, "completed", completed, "reused", reused, "failed", failed, "waived", List.of())));
    return new Outcome("SUCCEEDED", null, OcrResult.parse(Json.M.valueToTree(doc)));
  }

  static List<Map<String, Object>> scan(FilePatch p) {
    List<Map<String, Object>> out = new ArrayList<>(); int line = 0;
    for (String row : p.patch().split("\n", -1)) {
      var m = java.util.regex.Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@").matcher(row);
      if (m.find()) { line = Integer.parseInt(m.group(1)); continue; }
      if (row.startsWith("-") || row.startsWith("\\")) continue;
      if (row.startsWith("+")) {
        String code = row.substring(1);
        if (com.x7x24.llm.RuleJudgeProvider.SECRET.matcher(code).find()) out.add(c(p.path(), line, "security", "critical", "规则检查：新增代码里有疑似明文密钥或口令，提交后会泄露。", code, "改为从环境变量或密钥管理读取，并作废已泄露的密钥。"));
        else if (code.contains("console.log(")) out.add(c(p.path(), line, "maintainability", "low", "规则检查：新增了调试输出 console.log，上线后会在浏览器控制台打印内部信息。", code, "删除这行，或改用项目统一的日志工具。"));
        if (code.contains(UNLOCATED_FIXTURE)) out.add(c(p.path(), 0, "bug", "critical", "规则检查：示例意见，找不到对应的行。", "（不存在的代码）", null));
      }
      line++;
    }
    return out;
  }
  private static Map<String, Object> c(String path, int line, String cat, String sev, String content, String code, String suggestion) {
    Map<String, Object> m = new LinkedHashMap<>(); m.put("path", path); m.put("content", content); if (suggestion != null) m.put("suggestion_code", suggestion);
    m.put("existing_code", code.trim()); m.put("start_line", line); m.put("end_line", line); m.put("category", cat); m.put("severity", sev); return m;
  }
}
