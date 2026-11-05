package com.x7x24.agents;

import java.util.*;

/** 规划可以使用有界预览；返修使用完整的失败 job 日志。 */
public final class CiLogSummarizer {
  private CiLogSummarizer() {}
  public static String summarize(String log, int maxChars) {
    if (log == null || log.isBlank()) return "";
    String[] lines = forRepair(log).split("\n"); int n = lines.length; boolean[] keep = new boolean[n];
    for (int i = 0; i < n; i++)
      if (lines[i].matches("(?i).*(not ok|\\bfail|error|assert|expected|actual|exception|cannot find|undefined|exit code|✖|# fail).*"))
        for (int j = Math.max(0, i - 2); j <= Math.min(n - 1, i + 4); j++) keep[j] = true;
    for (int i = Math.max(0, n - 15); i < n; i++) keep[i] = true;
    StringBuilder out = new StringBuilder(); boolean gap = false;
    for (int i = 0; i < n; i++) {
      if (keep[i]) { if (gap) out.append("…\n"); out.append(lines[i]).append('\n'); gap = false; }
      else gap = true;
    }
    String s = out.toString();
    return s.length() <= maxChars ? s : s.substring(0, maxChars / 3) + "\n…（中间省略）…\n" + s.substring(s.length() - maxChars * 2 / 3);
  }
  /** 返修保留每个失败及其上下文，不按字符数丢弃中间的失败；超模型容量应明确停止。 */
  public static String forRepair(String log) {
    return com.x7x24.engine.CiFailure.cleanLog(log);
  }
}
