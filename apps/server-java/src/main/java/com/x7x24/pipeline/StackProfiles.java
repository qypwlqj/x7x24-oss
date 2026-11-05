package com.x7x24.pipeline;

import com.x7x24.engine.ProjectCfg;

import java.util.*;

/** 项目技术栈画像：目录 → 技术栈。手动设置的画像优先；否则由项目模块（自动识别结果）推断。 */
public final class StackProfiles {
  private StackProfiles() {}
  public static final List<String> STACKS = List.of("go", "java", "python", "node", "react", "vue", "web", "sql", "docs");
  public static final Map<String, String> LABEL = Map.of("go", "backend", "java", "backend", "python", "backend", "node", "backend", "react", "frontend", "vue", "frontend", "web", "frontend", "sql", "migration", "docs", "docs");
  public static final Map<String, String> SKILL = Map.of("go", "go-service@1", "java", "java-spring@1", "python", "python-service@1", "react", "react-ts@1", "vue", "vue-ts@1", "sql", "sql-migration@1", "docs", "docs-style@1");

  /** 技术栈画像：stacks 去重有序；modules 按目录前缀映射到栈；source = manual | auto */
  public static ProjectCfg.StackProfile of(ProjectCfg c) {
    if (c != null && c.stackProfile != null && "manual".equals(c.stackProfile.source) && c.stackProfile.modules != null && !c.stackProfile.modules.isEmpty()) return normalize(c.stackProfile);
    return derive(c);
  }
  static ProjectCfg.StackProfile normalize(ProjectCfg.StackProfile p) {
    var o = new ProjectCfg.StackProfile(); o.source = p.source; LinkedHashSet<String> st = new LinkedHashSet<>();
    for (var m : p.modules) { if (m == null || m.stack == null || !STACKS.contains(m.stack)) continue; var x = new ProjectCfg.ModuleStack(); x.path = norm(m.path); x.stack = m.stack; o.modules.add(x); st.add(m.stack); }
    if (p.stacks != null) for (String s : p.stacks) if (STACKS.contains(s)) st.add(s);
    o.stacks = new ArrayList<>(st); return o;
  }
  public static ProjectCfg.StackProfile derive(ProjectCfg c) {
    var o = new ProjectCfg.StackProfile(); o.source = "auto"; LinkedHashSet<String> st = new LinkedHashSet<>();
    if (c == null) { o.stacks = List.of(); return o; }
    for (var m : c.modules == null ? List.<ProjectCfg.Module>of() : c.modules) {
      for (String s : stacksOf(m.language, m.path)) { var x = new ProjectCfg.ModuleStack(); x.path = norm(m.path); x.stack = s; o.modules.add(x); st.add(s); }
    }
    for (var r : c.pathRules == null ? List.<ProjectCfg.PathRule>of() : c.pathRules) {
      String p = r.prefix == null ? "" : r.prefix.toLowerCase(Locale.ROOT);
      if (p.contains("migration") || p.contains("flyway") || p.contains("prisma")) { var x = new ProjectCfg.ModuleStack(); x.path = norm(r.prefix); x.stack = "sql"; o.modules.add(x); st.add("sql"); }
    }
    String tech = c.technology == null ? "" : c.technology.toLowerCase(Locale.ROOT);
    if (o.modules.isEmpty() && !tech.startsWith("未识别")) for (String s : stacksOf(tech, "")) st.add(s);
    if (tech.matches(".*(postgres|mysql|sqlite|sql server|mariadb|flyway|prisma|migration).*")) st.add("sql");
    o.stacks = new ArrayList<>(st); return o;
  }
  /** 语言描述（如 "Go (Gin)"、"TypeScript (React, Vite)"）→ 栈 */
  static List<String> stacksOf(String language, String path) {
    String l = language == null ? "" : language.toLowerCase(Locale.ROOT); String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
    List<String> out = new ArrayList<>();
    if (l.matches(".*\\bgo(lang)?\\b.*")) out.add("go");
    if (l.matches(".*(java|kotlin|spring).*")) out.add("java");
    if (l.matches(".*(python|django|fastapi|flask).*")) out.add("python");
    if (l.contains("react") || l.contains("next.js") || l.contains("nextjs")) out.add("react");
    else if (l.contains("vue") || l.contains("nuxt")) out.add("vue");
    else if (l.matches(".*(svelte|angular|html|css).*")) out.add("web");
    else if (l.matches(".*(node|express|nest|koa|fastify).*") || (l.matches(".*(typescript|javascript).*") && p.matches(".*(server|backend|api).*"))) out.add("node");
    else if (l.matches(".*(typescript|javascript).*") && p.matches(".*(web|frontend|client|ui|app).*")) out.add("web");
    if (l.matches(".*\\b(sql|postgres|mysql)\\b.*") || p.contains("migration")) out.add("sql");
    if (l.matches(".*(markdown|docs).*") || p.startsWith("docs")) out.add("docs");
    return out;
  }
  static String norm(String path) { if (path == null || path.isBlank() || path.equals("./") || path.equals(".")) return ""; String p = path.startsWith("./") ? path.substring(2) : path; return p.endsWith("/") ? p : p + "/"; }

  /** 路径所属的栈：最长前缀匹配的模块；没匹配到返回 null */
  public static String stackOfPath(ProjectCfg.StackProfile prof, String path) {
    if (prof == null || prof.modules == null || path == null) return null;
    String best = null; int len = -1;
    for (var m : prof.modules) { String pre = m.path == null ? "" : m.path; if ((pre.isEmpty() || path.startsWith(pre)) && pre.length() > len) { best = m.stack; len = pre.length(); } }
    return best;
  }
}
