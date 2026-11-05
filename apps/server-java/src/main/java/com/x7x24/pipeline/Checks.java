package com.x7x24.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.util.Json;

import java.util.*;
import java.util.function.Function;
import java.util.regex.*;

/** 节点内附加检查（check hook，决策 1A）。规则型、确定性；失败按 CI 失败处理（进入返修循环）。 */
public final class Checks {
  private Checks() {}
  public enum State { PASS, FAIL, UNKNOWN, SKIPPED, ERROR }
  public record Result(String check, State state, List<String> problems) {
    public Result(String check, boolean ok, List<String> problems) { this(check, ok ? State.PASS : State.FAIL, problems); }
    public boolean ok() { return state == State.PASS; }
    public boolean satisfied() { return ok() || state == State.SKIPPED && problems.equals(List.of("NO_APPLICABLE_FILES")); }
  }
  private static class MissingEvidence extends RuntimeException { MissingEvidence(String p) { super(p); } }
  static final Pattern LOCALE_DIR = Pattern.compile("^(.*/)?(locales|i18n|lang|langs|translations)/([A-Za-z]{2,3}([-_][A-Za-z0-9]{2,4})?)/(.+\\.json)$");
  static final Pattern LOCALE_FILE = Pattern.compile("^(.*/)?(locales|i18n|lang|langs|translations)/([A-Za-z]{2,3}([-_][A-Za-z0-9]{2,4})?)\\.json$");

  /** read(path) 读 PR 分支上的文件（不存在返回 null）；list(dir) 列目录 */
  public static List<Result> run(List<String> checks, List<String> files, Function<String, String> read, Function<String, List<String>> list) {
    List<Result> out = new ArrayList<>(); if (checks == null) return out;
    for (String c : checks) {
      if ("ci".equals(c)) continue; // CI has its own SHA-bound gate in Engine
      boolean applies = files != null && files.stream().anyMatch(f -> switch(c) {
        case "sql-safety-lint" -> f.toLowerCase(Locale.ROOT).endsWith(".sql");
        case "markdown-lint" -> f.toLowerCase(Locale.ROOT).endsWith(".md");
        case "i18n-keys-complete" -> LOCALE_DIR.matcher(f).matches() || LOCALE_FILE.matcher(f).matches();
        default -> true;
      });
      if (files == null) { out.add(new Result(c, State.UNKNOWN, List.of("MISSING_FILE_MANIFEST"))); continue; }
      if (!applies) { out.add(new Result(c, State.SKIPPED, List.of("NO_APPLICABLE_FILES"))); continue; }
      try { switch (c) {
      case "i18n-keys-complete" -> out.add(i18n(files, read, list));
      case "markdown-lint" -> out.add(markdown(files, read));
      case "sql-safety-lint" -> out.add(sqlSafety(files, read));
      default -> out.add(new Result(c, State.UNKNOWN, List.of("UNREGISTERED_CHECK")));
      } } catch (MissingEvidence e) { out.add(new Result(c, State.UNKNOWN, List.of(e.getMessage()))); }
      catch (Exception e) { out.add(new Result(c, State.ERROR, List.of("CHECK_ERROR: " + e.getClass().getSimpleName()))); }
    }
    return out;
  }

  static final Pattern SQL_DANGER = Pattern.compile("(?i)\\b(DROP\\s+TABLE|TRUNCATE\\s+TABLE|TRUNCATE\\s+[A-Za-z0-9_`\"\\.]+)\\b");
  static final Pattern SQL_UNCOND_DELETE = Pattern.compile("(?is)\\bDELETE\\s+FROM\\s+[A-Za-z0-9_`\"\\.]+\\s*;");
  static Result sqlSafety(List<String> files, Function<String, String> read) {
    List<String> probs = new ArrayList<>();
    for (String f : files) {
      if (!f.toLowerCase(Locale.ROOT).endsWith(".sql")) continue;
      String body = safe(read, f); if (body == null) continue;
      String stripped = body.replaceAll("(?m)--.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
      Matcher m = SQL_DANGER.matcher(stripped);
      while (m.find()) probs.add(f + "：包含高危语句 " + m.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT));
      if (SQL_UNCOND_DELETE.matcher(stripped).find()) probs.add(f + "：包含无 WHERE 条件的 DELETE FROM 语句");
    }
    return new Result("sql-safety-lint", probs.isEmpty(), probs);
  }

  static Result i18n(List<String> files, Function<String, String> read, Function<String, List<String>> list) {
    List<String> probs = new ArrayList<>(); int reads = 0;
    for (String f : files) {
      Matcher md = LOCALE_DIR.matcher(f), mf = LOCALE_FILE.matcher(f);
      boolean dir = md.matches(), single = !dir && mf.matches(); if (!dir && !single) continue;
      String body = safe(read, f); if (body == null) continue; // 删除的文件
      JsonNode n; try { n = Json.M.readTree(body); } catch (Exception e) { probs.add(f + "：不是合法 JSON（" + e.getMessage().split("\n")[0] + "）"); continue; }
      Set<String> keys = flatten(n);
      // 找同组的其它语言文件，以 en / zh / 第一个为基准
      String root = (dir ? (md.group(1) == null ? "" : md.group(1)) + md.group(2) : (mf.group(1) == null ? "" : mf.group(1)) + mf.group(2));
      String lang = dir ? md.group(3) : mf.group(3); String rel = dir ? md.group(5) : null;
      List<String> entries = safeList(list, root); if (entries.isEmpty()) continue;
      List<String> langs = new ArrayList<>(); for (String e : entries) { String name = e.contains("/") ? e.substring(e.lastIndexOf('/') + 1) : e; String l = dir ? name : name.endsWith(".json") ? name.substring(0, name.length() - 5) : null; if (l != null && !l.equals(lang) && l.matches("[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,4})?")) langs.add(l); }
      String base = langs.contains("en") ? "en" : langs.contains("zh") ? "zh" : langs.contains("zh-CN") ? "zh-CN" : langs.isEmpty() ? null : langs.get(0);
      if (base == null || reads++ > 40) throw new MissingEvidence("MISSING_LOCALE_BASELINE: " + f);
      String basePath = root + "/" + (dir ? base + "/" + rel : base + ".json");
      String bb = safe(read, basePath); if (bb == null) continue;
      Set<String> want; try { want = flatten(Json.M.readTree(bb)); } catch (Exception e) { throw new MissingEvidence("INVALID_LOCALE_BASELINE: " + basePath); }
      List<String> missing = want.stream().filter(k -> !keys.contains(k)).sorted().toList();
      if (!missing.isEmpty()) probs.add(f + "：相对 " + basePath + " 缺少 " + missing.size() + " 个键，例如 " + String.join(", ", missing.subList(0, Math.min(8, missing.size()))));
    }
    return new Result("i18n-keys-complete", probs.isEmpty(), probs);
  }
  static Set<String> flatten(JsonNode n) { Set<String> s = new TreeSet<>(); flat("", n, s); return s; }
  private static void flat(String p, JsonNode n, Set<String> s) {
    if (n != null && n.isObject()) n.fields().forEachRemaining(e -> flat(p.isEmpty() ? e.getKey() : p + "." + e.getKey(), e.getValue(), s)); else s.add(p);
  }

  static final Pattern EMPTY_LINK = Pattern.compile("\\[[^\\]]*\\]\\(\\s*\\)|\\[\\s*\\]\\([^)]+\\)");
  static Result markdown(List<String> files, Function<String, String> read) {
    List<String> probs = new ArrayList<>();
    for (String f : files) {
      if (!f.toLowerCase(Locale.ROOT).endsWith(".md")) continue; String body = safe(read, f); if (body == null) continue;
      boolean code = false; int last = 0, ln = 0;
      for (String line : body.split("\n", -1)) { ln++;
        if (line.trim().startsWith("```")) { code = !code; continue; } if (code) continue;
        if (EMPTY_LINK.matcher(line).find()) probs.add(f + ":" + ln + "：空链接或链接缺少文字");
        Matcher h = Pattern.compile("^(#{1,6})\\s").matcher(line);
        if (h.find()) { int lv = h.group(1).length(); if (last > 0 && lv > last + 1) probs.add(f + ":" + ln + "：标题层级从 H" + last + " 跳到 H" + lv); last = lv; }
      }
    }
    return new Result("markdown-lint", probs.isEmpty(), probs);
  }
  private static String safe(Function<String, String> r, String p) { try { String body = r.apply(p); if (body == null) throw new MissingEvidence("UNREADABLE_FILE: " + p); return body; } catch (Exception e) { throw new MissingEvidence("UNREADABLE_FILE: " + p); } }
  private static List<String> safeList(Function<String, List<String>> l, String d) { try { var x = l.apply(d); if (x == null || x.isEmpty()) throw new MissingEvidence(d); return x; } catch (Exception e) { throw new MissingEvidence("UNREADABLE_DIRECTORY: " + d); } }
}
