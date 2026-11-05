package com.x7x24.unit;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 文案守护（后端）：抛给用户的业务错误必须有中文说明，且不含禁用术语（规范见 docs/copy-guide.md）。
 * 检查 new UserError / IllegalArgumentException / Forbidden / Denied / GateBlocked / CapReached / NotMember / PolicyDenied / Blocked 的字符串参数。
 * 行尾写 // copy-ok 可豁免（仅限确实不会到达用户的内部断言）。
 */
class UserMessageLintTest {
  static final Pattern CALL = Pattern.compile("new [\\w.]*(UserError|IllegalArgumentException|Forbidden|Denied|GateBlocked|CapReached|NotMember|PolicyDenied|Blocked)\\((.*)");
  static final Pattern ERR = Pattern.compile("\"error\",\\s*\"([^\"]*)\"");
  static final Pattern LIT = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
  static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
  static final List<String> BANNED = List.of("Agent", "agent", "dry-run", "Kill switch", "熔断", "降级", "自愈", "快照", "门槛", "fallback", "tick", "Loop", "继承", "拼进", "裁决");

  static List<String> lint(String file, List<String> lines) {
    List<String> bad = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      String l = lines.get(i); if (l.contains("copy-ok")) continue;
      Matcher e = ERR.matcher(l); while (e.find()) if (!CJK.matcher(e.group(1)).find() && !e.group(1).matches("[a-z][a-z0-9_]*")) bad.add(file + ":" + (i + 1) + " 错误响应缺少中文说明：" + e.group(1));
      Matcher m = CALL.matcher(l); if (!m.find()) continue;
      List<String> lits = new ArrayList<>(); Matcher q = LIT.matcher(m.group(2)); while (q.find()) { String v = q.group(1); if (v.matches("[A-Za-z0-9_.:-]*") || !v.matches("(?s).*[A-Za-z\\u4e00-\\u9fff].*")) continue; lits.add(v); } // 纯标识符（错误码 / 键名）与分隔符不算文案
      if (lits.isEmpty()) continue; // 透传上游消息 / 只有错误码，由 ApiErrors 统一兜底成中文
      if (lits.stream().noneMatch(s -> CJK.matcher(s).find())) bad.add(file + ":" + (i + 1) + " 缺少中文说明：" + lits);
      for (String s : lits) for (String b : BANNED) if (CJK.matcher(s).find() && s.contains(b)) bad.add(file + ":" + (i + 1) + " 禁用词「" + b + "」：" + s);
    }
    return bad;
  }
  @Test void selfCheck() {
    assertEquals(1, lint("x", List.of("throw new IllegalArgumentException(\"bad mode\");")).size());
    assertEquals(1, lint("x", List.of("throw new UserError(\"c\", \"已触发熔断\");")).size());
    assertEquals(0, lint("x", List.of("throw new UserError(\"c\", \"标题太短\");")).size());
    assertEquals(1, lint("x", List.of("body(Map.of(\"error\", \"not found\"));")).size());
    assertEquals(0, lint("x", List.of("throw new IllegalArgumentException(e.getMessage());")).size());
    assertEquals(0, lint("x", List.of("throw new Denied(\"no_token\", \"GitHub 没有返回访问令牌\");")).size());
  }
  @Test void userFacingErrorsAreChineseAndPlain() throws Exception {
    List<String> bad = new ArrayList<>(); Path root = Path.of("src/main/java");
    try (var s = Files.walk(root)) { for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) bad.addAll(lint(root.relativize(p).toString(), Files.readAllLines(p))); }
    assertEquals(List.of(), bad, String.join("\n", bad));
  }
}
