package com.x7x24.engine;

import com.x7x24.util.U;

import java.util.*;
import java.util.regex.*;

/**
 * CI 失败分类（纯函数）：
 * - setup：还没跑到测试就失败（安装依赖 / 检出 / 准备环境），例如仓库根目录没有 package.json 时 `npm ci` 直接报错；
 * - test：测试 / 检查本身失败。
 * signature = 去掉 run 编号、提交号、时间、耗时后的失败行指纹，用来判断「同一个错误又出现了」以及「主干上也是同样的失败」。
 */
public final class CiFailure {
  private CiFailure() {}
  public record Info(String kind, String signature, List<String> failedSteps, List<String> files, String headline) {
    public boolean setup() { return "setup".equals(kind); }
  }
  public record Diagnostic(String id, String step, String file, Integer line, String test, String message, String detail) {}
  public record Report(Info summary, List<Diagnostic> failures) {}
  private static final Pattern ANSI = Pattern.compile("\u001b(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007]*(?:\u0007|\u001b\\\\))");
  private static final Pattern CASE = Pattern.compile("(?i)^\\s*(?:FAIL(?:ED)?\\s+|not ok\\s+\\d+\\s*-?\\s*|--- FAIL:\\s*|[×✖]\\s+)(.+)");
  static final Pattern STEP = Pattern.compile("步骤失败：([^\\n；;\\]]+)");
  static final Pattern SETUP_STEP = Pattern.compile("(?i)^\\s*(install.*|setup.*|set up.*|checkout.*|cache.*|restore.*|download.*|prepare.*|bootstrap.*|deps|dependencies|安装.*|准备.*|run actions/.*)\\s*$");
  static final Pattern ENV = Pattern.compile("(?i)(ENOENT[^\\n]*package\\.json|`npm ci` command can only install|npm error code EUSAGE|command not found|Unable to locate executable|ERR_PNPM_NO_LOCKFILE|go: cannot find main module|go\\.mod file not found|exit code 127)");
  static final Pattern TEST_MARK = Pattern.compile("(?im)(^\\s*not ok\\b|✖|\\bFAIL\\b|AssertionError|expected .* (to|but)|Tests?:\\s+\\d+ failed|--- FAIL)");
  static final Pattern FILE = Pattern.compile("(?<![\\w/.-])((?:[\\w.@-]+/)+[\\w.-]+\\.(?:tsx?|jsx?|mjs|cjs|vue|go|py|java|kt|rb|rs|json|css|scss))(?::\\d+)?");

  public static Info classify(String log) {
    String l = cleanLog(log);
    List<String> steps = new ArrayList<>(); Matcher m = STEP.matcher(l); while (m.find()) { String s = m.group(1).trim(); if (!steps.contains(s)) steps.add(s); }
    boolean stepsSetup = !steps.isEmpty() && steps.stream().allMatch(s -> SETUP_STEP.matcher(s).matches());
    boolean env = ENV.matcher(l).find() && !TEST_MARK.matcher(l).find();
    String kind = stepsSetup || env ? "setup" : "test";
    List<String> files = new ArrayList<>(); Matcher f = FILE.matcher(l);
    while (f.find()) { String p = f.group(1).replaceFirst("^\\./", ""); if (!p.contains("node_modules/") && !p.startsWith("home/") && !files.contains(p)) files.add(p); }
    return new Info(kind, signature(l), steps, files, headline(l, kind, steps));
  }

  /** 只清理终端装饰和机器前缀；错误、用例、源码位置及 diff 原文均保留。 */
  public static String cleanLog(String log) {
    if (log == null) return "";
    return ANSI.matcher(log).replaceAll("").replace("\r\n", "\n").replace('\r', '\n')
        .replaceAll("(?m)^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z\\s?", "")
        .replaceAll("/home/runner/work/[^/\\s]+/[^/\\s]+/", "");
  }

  /** 按测试标题、类型诊断或独立断言分组；完整日志仍单独作为证据保存。 */
  public static Report report(String log) {
    String clean = cleanLog(log), step = "", test = null; List<String> block = new ArrayList<>(); List<Diagnostic> failures = new ArrayList<>();
    for (String line : clean.split("\n")) {
      if (line.matches("^\\[[^\\]]+\\] (?:步骤失败：|检查状态：).*")) {
        addDiagnostic(failures, step, test, block); block.clear(); test = null; step = line; continue;
      }
      Matcher title = CASE.matcher(line);
      boolean named = title.matches();
      boolean sourceError = FILE.matcher(line).find() && line.matches("(?i).*(error TS\\d+|\\berror:|\\[ERROR\\]|：).*");
      boolean assertion = test == null && line.matches("(?i).*AssertionError.*");
      if (named || sourceError || assertion) {
        addDiagnostic(failures, step, test, block); block.clear(); test = named ? title.group(1).trim() : null;
      }
      if (!block.isEmpty() || named || sourceError || line.matches("(?i).*(not ok|fail|error|assert|expected|actual|exception|cannot find|undefined|exit code|enoent|未通过|失败).*")) block.add(line);
    }
    addDiagnostic(failures, step, test, block);
    return new Report(classify(clean), List.copyOf(failures));
  }
  private static void addDiagnostic(List<Diagnostic> out, String step, String test, List<String> block) {
    if (block.isEmpty()) return;
    String detail = String.join("\n", block).strip(), file = null; Integer row = null;
    Matcher location = FILE.matcher(detail);
    while (location.find()) if (!location.group(1).contains("node_modules/")) { file = location.group(1); break; }
    if (file != null) {
      Matcher n = Pattern.compile(Pattern.quote(file) + "(?::|\\()(\\d+)").matcher(detail);
      if (n.find()) { try { row = Integer.valueOf(n.group(1)); } catch (NumberFormatException ignored) { } }
    }
    String message = block.stream().filter(s -> s.matches("(?i).*(error|assert|expected|actual|exception|cannot|undefined|enoent).*")).findFirst().orElse(block.getFirst()).strip();
    out.add(new Diagnostic(signature(step + "\n" + detail), step, file, row, test, message, detail));
  }

  /** 失败指纹：只看失败相关的行，去掉 run 编号 / 提交号 / 时间 / 耗时 / 临时目录，顺序无关 */
  public static String signature(String log) {
    if (log == null || log.isBlank()) return "";
    TreeSet<String> keep = new TreeSet<>();
    for (String raw : cleanLog(log).split("\n")) {
      String s = raw.replaceFirst("^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z\\s?", "").trim();
      if (s.isEmpty() || s.startsWith("run #") || s.startsWith("…")) continue;
      if (!s.matches("(?i).*(not ok|fail|error|assert|expected|actual|exception|cannot|undefined|exit code|✖|步骤失败|enoent).*") && !FILE.matcher(s).find()) continue;
      s = s.replaceAll("\\b[0-9a-f]{7,40}\\b", "#").replaceAll("\\d+(\\.\\d+)?\\s*(ms|s|秒)\\b", "T").replaceAll("/tmp/[^\\s]+|/home/runner/[^\\s]+|_temp/[^\\s]+", "P").replaceAll("\\d{4}-\\d\\d-\\d\\d[T_ ][\\d:._]+Z?", "D").replaceAll("\\s+", " ");
      keep.add(s);
    }
    return keep.isEmpty() ? "" : U.sha256(String.join("\n", keep)).substring(0, 16);
  }

  static String headline(String log, String kind, List<String> steps) {
    if ("setup".equals(kind)) {
      if (log.contains("package.json") && (log.contains("ENOENT") || log.contains("npm ci"))) return "CI 在「" + (steps.isEmpty() ? "安装依赖" : steps.get(0)) + "」这一步就失败了：仓库根目录没有 package.json（npm 找不到项目），测试根本没有开始运行";
      return "CI 在「" + (steps.isEmpty() ? "准备环境" : String.join("、", steps)) + "」这一步就失败了，测试还没有开始运行";
    }
    return steps.isEmpty() ? "测试没通过" : "测试没通过（" + String.join("、", steps) + "）";
  }
}
