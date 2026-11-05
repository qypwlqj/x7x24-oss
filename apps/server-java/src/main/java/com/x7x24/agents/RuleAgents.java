package com.x7x24.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.DeliveryTools.FileChange;
import com.x7x24.agents.Models.*;
import com.x7x24.gov.Risk;
import com.x7x24.gov.ConfigStore;
import com.x7x24.util.*;

import java.util.*;
import java.util.regex.*;

/**
 * 规则型本地 agent（没有 LLM 凭据时的默认实现）。不是占位：它根据 Issue 内容确定性地生成真实的 JS 模块 + node:test 单元测试 + Markdown 文档，
 * 或更新 README 的「使用说明」段。读取 CI 日志后能切换实现（读日志 → 返修）。
 * 支持的模板：util-function（slugify / wordCount / clamp / capitalize / isPalindrome）、readme-update、doc（文档提案）；其余为 generic（不支持）。
 */
public class RuleAgents implements ExecAgents {
  private final ConfigStore cfg;
  public RuleAgents(ConfigStore cfg) { this.cfg = cfg; }
  @Override public String mode() { return "rule"; }
  @Override public String model() { return "rule-agent"; }

  record Fn(String name, String file, String sig, String doc, String naive, String robust, List<String[]> examples) {}
  static final Map<String, Fn> CATALOG = new LinkedHashMap<>();
  static {
    add("slugify", "input", "把任意字符串转成 URL 友好的 slug：小写、去重音、非字母数字替换为单个 `-`、去掉首尾 `-`。",
        "return String(input).toLowerCase().replace(/ /g, '-');",
        "return String(input).normalize('NFKD').replace(/[\\u0300-\\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');",
        new String[][] {{"[\"Hello World\"]", "\"hello-world\""}, {"[\"  Foo   Bar!! \"]", "\"foo-bar\""}, {"[\"Crème Brûlée\"]", "\"creme-brulee\""}});
    add("wordCount", "input", "统计字符串中的单词数（以任意空白分隔，忽略首尾空白，空串为 0）。",
        "return String(input).split(' ').length;",
        "const t = String(input).trim();\n  return t === '' ? 0 : t.split(/\\s+/).length;",
        new String[][] {{"[\"hello world\"]", "2"}, {"[\"  a  b  \"]", "2"}, {"[\"\"]", "0"}});
    add("clamp", "n, min, max", "把数值限制在 [min, max] 区间内。",
        "return Math.min(max, n);", "return Math.min(Math.max(n, min), max);",
        new String[][] {{"[5,1,3]", "3"}, {"[-1,0,10]", "0"}, {"[7,0,10]", "7"}});
    add("capitalize", "input", "把首字母大写，其余保持不变；空串返回空串。",
        "return input[0].toUpperCase() + input.slice(1);", "const s = String(input);\n  return s === '' ? '' : s[0].toUpperCase() + s.slice(1);",
        new String[][] {{"[\"hello\"]", "\"Hello\""}, {"[\"\"]", "\"\""}, {"[\"x\"]", "\"X\""}});
    add("isPalindrome", "input", "判断字符串是否为回文（忽略大小写与非字母数字字符，空串为 true）。",
        "const s = String(input);\n  return s === [...s].reverse().join('');",
        "const s = String(input).toLowerCase().replace(/[^a-z0-9]/g, '');\n  return s === [...s].reverse().join('');",
        new String[][] {{"[\"A man, a plan, a canal: Panama\"]", "true"}, {"[\"hello\"]", "false"}, {"[\"\"]", "true"}});
  }
  private static void add(String n, String sig, String doc, String naive, String robust, String[][] ex) {
    CATALOG.put(n.toLowerCase(), new Fn(n, "src/" + n + ".js", sig, doc, naive, robust, Arrays.asList(ex)));
  }

  // ───────── 解析 ─────────
  static String textOf(Ctx c) { return c.task().title + "\n" + c.task().problem; }
  static Optional<Fn> detectFn(String text) {
    String t = text.toLowerCase();
    for (var e : CATALOG.entrySet()) if (Pattern.compile("(?<![a-z0-9])" + e.getKey() + "(?![a-z0-9])").matcher(t).find()) return Optional.of(e.getValue());
    if (t.contains("word count")) return Optional.of(CATALOG.get("wordcount"));
    if (t.contains("palindrome") || t.contains("回文")) return Optional.of(CATALOG.get("ispalindrome"));
    return Optional.empty();
  }
  static String template(String title, String text) {
    boolean readmeTitle = Pattern.compile("readme|使用说明", Pattern.CASE_INSENSITIVE).matcher(title).find();
    if (readmeTitle && detectFn(title).isEmpty()) return "readme-update";
    if (detectFn(text).isPresent()) return "util-function";
    if (Pattern.compile("readme|使用说明", Pattern.CASE_INSENSITIVE).matcher(text).find()) return "readme-update";
    if (Pattern.compile("文档|docs?\\b|设计说明|proposal|说明", Pattern.CASE_INSENSITIVE).matcher(text).find()) return "doc";
    return "generic";
  }
  static final Pattern EX = Pattern.compile("([A-Za-z_]\\w*)\\(([^()\\n]*)\\)\\s*(?:=>|->|→|={2,3}|返回|returns?)\\s*(\"[^\"\\n]*\"|'[^'\\n]*'|-?\\d+(?:\\.\\d+)?|true|false)");
  static List<Example> examples(String text, String fn) {
    List<Example> out = new ArrayList<>(); Matcher m = EX.matcher(text);
    while (m.find()) {
      if (!m.group(1).equalsIgnoreCase(fn)) continue;
      List<String> args = splitArgs(m.group(2)); List<String> js = new ArrayList<>(); boolean ok = true;
      for (String a : args) { String lit = literal(a.trim()); if (lit == null) { ok = false; break; } js.add(lit); }
      String exp = literal(m.group(3)); if (!ok || exp == null) continue;
      out.add(new Example(fn, js, exp, m.group(0)));
    }
    return out;
  }
  static List<String> splitArgs(String s) {
    List<String> out = new ArrayList<>(); StringBuilder b = new StringBuilder(); char q = 0;
    for (char ch : s.toCharArray()) {
      if (q != 0) { b.append(ch); if (ch == q) q = 0; }
      else if (ch == '"' || ch == '\'') { q = ch; b.append(ch); }
      else if (ch == ',') { out.add(b.toString()); b.setLength(0); }
      else b.append(ch);
    }
    if (b.length() > 0 || !out.isEmpty()) out.add(b.toString());
    return out.stream().filter(x -> !x.isBlank()).toList();
  }
  /** 只接受字符串/数字/布尔字面量，并用 JSON 重新序列化——Issue 文本不会作为代码被注入测试文件 */
  static String literal(String s) {
    if (s.matches("-?\\d+(\\.\\d+)?") || s.equals("true") || s.equals("false")) return s;
    if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) return Json.str(s.substring(1, s.length() - 1));
    return null;
  }

  // ───────── Drafter ─────────
  @Override public Prd draftPrd(Ctx c) {
    var t = c.task(); String text = textOf(c); String tpl = template(t.title, text);
    var fn = detectFn(text); List<Example> ex = fn.map(f -> examples(text, f.name())).orElse(List.of());
    boolean naive = Pattern.compile("x7x24:\\s*first-attempt\\s*=\\s*naive", Pattern.CASE_INSENSITIVE).matcher(text).find();
    int nEx = fn.isPresent() ? (ex.isEmpty() ? fn.get().examples().size() : ex.size()) : 0;
    List<String> crit = new ArrayList<>();
    switch (tpl) {
      case "util-function" -> {
        crit.add("新增 `" + fn.get().file() + "` 并导出 " + fn.get().name() + "，`node --test` 失败数 = 0");
        crit.add("单元测试覆盖 " + nEx + " 个示例用例（≥ 3 个）并随 PR 提交");
        crit.add("PR 变更行数 ≤ " + cfg.get().release.maxChangeLines + " 且触及治理文件数 = 0");
      }
      case "readme-update" -> {
        crit.add("README.md 的「使用说明」段被新增或更新（1 处，位于 x7x24:usage 标记之间）");
        crit.add("说明段列出 src/ 下现有全部模块（≥ 0 项）并给出 1 条测试运行命令");
        crit.add("PR 变更行数 ≤ " + cfg.get().release.maxChangeLines + " 且触及治理文件数 = 0");
      }
      case "doc" -> { crit.add("新增 1 份文档 docs/proposals/ 下的提案并引用原 Issue"); crit.add("PR 变更行数 ≤ " + cfg.get().release.maxChangeLines + " 且触及治理文件数 = 0"); crit.add("CI 失败数 = 0"); }
      default -> { crit.add("规则型 agent 无法实现该需求（需要 LLM 驱动），验收条件数 = 0"); }
    }
    var risk = Risk.classify(cfg.get(), text, null);
    return new Prd(t.reviewRetry + 1, t.title, t.problem == null || t.problem.isBlank() ? t.title + " 的需求背景" : U.ellipsis(t.problem.strip(), 600),
        "作为仓库维护者，我希望" + t.title + "，以便减少重复劳动。", crit, List.of("ci_pass", "tests_added", "diff_lines"), 0, t.context.reviewNotes == null ? List.of() : t.context.reviewNotes,
        tpl, risk.level(), fn.map(Fn::name).orElse(null), ex, naive);
  }

  // ───────── Planner ─────────
  @Override public Plan plan(Ctx c) {
    var t = c.task(); var prd = c.prd(); String text = textOf(c);
    var risk = Risk.classify(cfg.get(), text, null);
    boolean ui = Pattern.compile("页面|界面|\\bui\\b|按钮|样式|前端", Pattern.CASE_INSENSITIVE).matcher(text).find();
    List<String> path = "high".equals(risk.level()) ? List.of("SPEC", "DESIGN", "DEV") : ui && !"low".equals(risk.level()) ? List.of("SPEC", "DESIGN", "DEV") : List.of("SPEC", "DEV");
    List<String> files = new ArrayList<>();
    switch (prd.template()) {
      case "util-function" -> { var f = CATALOG.get(prd.functionName().toLowerCase()); files.add(f.file()); files.add("tests/" + f.name() + ".test.js"); files.add("docs/" + f.name() + ".md"); }
      case "readme-update" -> files.add("README.md");
      default -> files.add("docs/proposals/" + U.slug(t.title) + ".md");
    }
    List<Map<String, Object>> sub = new ArrayList<>(); for (int i = 0; i < path.size(); i++) sub.add(Map.of("id", "st" + (i + 1), "title", path.get(i) + " 子任务", "deps", i == 0 ? List.of() : List.of("st" + i)));
    return new Plan(path, prd.template(), "rule-template", t.scopeLevel, risk.level(), files, sub);
  }

  @Override public List<Case> specCases(Ctx c) {
    var prd = c.prd(); List<Case> out = new ArrayList<>(); int i = 1;
    if (prd.template().equals("util-function")) {
      var fn = CATALOG.get(prd.functionName().toLowerCase());
      List<Example> ex = prd.examples().isEmpty() ? defaults(fn) : prd.examples();
      for (var e : ex) out.add(new Case("AC-" + i++, "Given 模块 " + fn.file() + "\nWhen 调用 " + e.fn() + "(" + String.join(", ", e.argsJs()) + ")\nThen 返回 " + e.expectedJs()));
    }
    out.add(new Case("AC-" + i++, "Given PR 的 head 提交\nWhen 仓库 CI 运行\nThen 全部 job 成功"));
    out.add(new Case("AC-" + i, "Given PR 的文件列表\nWhen 检查变更范围\nThen 不含治理文件且变更行数不超限"));
    return out;
  }
  @Override public Map<String, Object> design(Ctx c) { return Map.of("frames", List.of("现状", "目标"), "notes", "规则型设计：沿用现有设计系统"); }

  static List<Example> defaults(Fn f) {
    List<Example> out = new ArrayList<>();
    for (String[] e : f.examples()) { out.add(new Example(f.name(), Json.parse(e[0], new com.fasterxml.jackson.core.type.TypeReference<List<JsonNode>>() {}).stream().map(JsonNode::toString).toList(), e[1], "default")); }
    return out;
  }

  // ───────── Dev ─────────
  @Override public CodeChange code(Ctx c) {
    var prd = c.prd(); var t = c.task();
    switch (prd.template()) {
      case "util-function": return codeFunction(c);
      case "readme-update": return codeReadme(c);
      default: {
        String path = "docs/proposals/" + U.slug(t.title) + ".md";
        String body = "# " + t.title + "\n\n> 来源 Issue：" + (c.issue() == null ? "-" : "#" + c.issue().number) + "\n\n## 背景\n\n" + prd.problem() + "\n\n## 验收条件\n\n" + String.join("\n", prd.acceptanceCriteria().stream().map(x -> "- " + x).toList()) + "\n";
        return new CodeChange(List.of(FileChange.put(path, body)), "docs: " + t.title + "（7x24 提案）", "新增提案文档 " + path, "doc");
      }
    }
  }

  @SuppressWarnings("unchecked")
  private CodeChange codeFunction(Ctx c) {
    var prd = c.prd(); var t = c.task(); var fn = CATALOG.get(prd.functionName().toLowerCase());
    List<String> variants = prd.naiveFirst() ? List.of("naive", "robust") : List.of("robust");
    // 变体由「已发生的 CI 失败次数」确定（状态来自持久化的任务上下文，重放结果一致）
    long ciFails = t.context.failures.stream().filter(f -> "test_fail".equals(f.category)).count();
    int idx = (int) Math.max(ciFails, c.ciLog() != null && !c.ciLog().isBlank() ? 1 : 0);
    idx = Math.min(idx, variants.size() - 1);
    String variant = variants.get(idx);
    List<String> failing = failingTests(c.ciLog());
    String body = variant.equals("naive") ? fn.naive() : fn.robust();
    String src = "'use strict';\n\n/**\n * " + fn.doc() + "\n * 由 7x24（任务 " + t.id.substring(0, 8) + (c.issue() != null ? "，Issue #" + c.issue().number : "") + "）生成。\n */\nfunction " + fn.name() + "(" + fn.sig() + ") {\n  " + body + "\n}\n\nmodule.exports = { " + fn.name() + " };\n";
    List<Example> exs = prd.examples().isEmpty() ? defaults(fn) : prd.examples();
    StringBuilder test = new StringBuilder("'use strict';\nconst test = require('node:test');\nconst assert = require('node:assert/strict');\nconst { " + fn.name() + " } = require('../src/" + fn.name() + ".js');\n\n");
    for (var e : exs) {
      String call = fn.name() + "(" + String.join(", ", e.argsJs()) + ")";
      test.append("test(").append(Json.str(call + " => " + e.expectedJs())).append(", () => {\n  assert.deepStrictEqual(").append(call).append(", ").append(e.expectedJs()).append(");\n});\n\n");
    }
    String doc = "# " + fn.name() + "\n\n" + fn.doc() + "\n\n```js\nconst { " + fn.name() + " } = require('./src/" + fn.name() + ".js');\n```\n\n## 示例\n\n" + String.join("\n", exs.stream().map(e -> "- `" + fn.name() + "(" + String.join(", ", e.argsJs()) + ")` → `" + e.expectedJs() + "`").toList()) + "\n";
    String msg = failing.isEmpty() ? "feat: " + t.title : "fix: " + t.title + "（CI 失败用例：" + String.join("; ", failing.stream().limit(3).toList()) + " → 改用 " + variant + " 实现）";
    return new CodeChange(List.of(FileChange.put(fn.file(), src), FileChange.put("tests/" + fn.name() + ".test.js", test.toString()), FileChange.put("docs/" + fn.name() + ".md", doc)), msg,
        failing.isEmpty() ? "新增 " + fn.file() + " 与单元测试（实现：" + variant + "）" : "读取 CI 日志后返修：失败 " + failing + "，改用 " + variant, variant);
  }
  static List<String> failingTests(String log) {
    List<String> out = new ArrayList<>(); if (log == null) return out;
    Matcher m = Pattern.compile("not ok \\d+ - (.+)").matcher(log); while (m.find()) out.add(m.group(1).trim());
    return out.stream().distinct().toList();
  }

  private CodeChange codeReadme(Ctx c) {
    var t = c.task(); String base = c.line().baseBranch;
    String readme = c.tools().readFile(c.line(), base, "README.md"); if (readme == null) readme = "# " + c.line().repoName() + "\n";
    List<String> mods = c.tools().listDir(c.line(), base, "src").stream().filter(x -> x.endsWith(".js")).sorted().toList();
    StringBuilder sec = new StringBuilder("<!-- x7x24:usage:start -->\n## 使用说明\n\n");
    List<String> bullets = new ArrayList<>(); for (String line : (c.task().problem == null ? "" : c.task().problem).split("\n")) { String s = line.strip(); if (s.startsWith("- ") || s.startsWith("* ")) { String b = s.substring(2).strip(); if (!b.isEmpty() && !b.startsWith("x7x24:") && !EX.matcher(b).find()) bullets.add(b); } }
    if (!bullets.isEmpty()) { for (String b : bullets) sec.append("- ").append(b).append("\n"); sec.append("\n"); }
    else sec.append(U.ellipsis(c.prd().problem().replace("\n", " "), 300)).append("\n\n");
    sec.append("### 模块\n\n"); if (mods.isEmpty()) sec.append("（暂无模块）\n"); else for (String m : mods) sec.append("- `").append(m).append("`\n");
    sec.append("\n### 运行测试\n\n```bash\nnode --test tests/*.test.js\n```\n<!-- x7x24:usage:end -->");
    String out; int a = readme.indexOf("<!-- x7x24:usage:start -->"), b = readme.indexOf("<!-- x7x24:usage:end -->");
    if (a >= 0 && b > a) out = readme.substring(0, a) + sec + readme.substring(b + "<!-- x7x24:usage:end -->".length());
    else out = readme.stripTrailing() + "\n\n" + sec + "\n";
    return new CodeChange(List.of(FileChange.put("README.md", out)), "docs: " + t.title, "更新 README 使用说明（模块 " + mods.size() + " 个）", "readme");
  }
}
