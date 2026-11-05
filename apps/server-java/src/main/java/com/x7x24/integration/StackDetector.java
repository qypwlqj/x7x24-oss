package com.x7x24.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.engine.ProjectCfg;
import com.x7x24.util.Json;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 仓库技术栈识别：只看文件树 + 少量清单文件（package.json / go.mod / pom.xml / build.gradle / pyproject / requirements），纯函数，便于用夹具测试。
 * 产出建议的技术栈描述、模块（路径 / 语言 / 测试与构建命令）、允许目录、文件类型、路径风险规则与扫描目录。
 */
public final class StackDetector {
  private StackDetector() {}
  public static final String LEGACY_DEFAULT = "Node.js CommonJS 工具函数项目";
  public static final String UNDETECTED = "未识别（请点「自动识别」）";

  public record Suggestion(String technology, List<ProjectCfg.Module> modules, List<String> allowedPaths, List<String> allowedExtensions,
                           List<ProjectCfg.PathRule> pathRules, List<String> discoveryRoots, String workflow, List<String> evidence) {
    public Map<String, Object> configPatch() {
      Map<String, Object> m = new LinkedHashMap<>(); m.put("technology", technology); m.put("modules", modules); m.put("allowedPaths", allowedPaths);
      m.put("allowedExtensions", allowedExtensions); m.put("pathRules", pathRules); m.put("discovery", Map.of("roots", discoveryRoots)); if (workflow != null) m.put("workflow", workflow);
      return m;
    }
  }
  private static final Pattern SKIP = Pattern.compile("(^|/)(node_modules|vendor|dist|build|target|\\.venv|venv|__pycache__|\\.next|coverage)/");
  private static final List<String> MANIFESTS = List.of("package.json", "go.mod", "pom.xml", "build.gradle", "build.gradle.kts", "pyproject.toml", "requirements.txt", "Cargo.toml");
  private static final List<String> FORBIDDEN_DIRS = List.of(".github/", "deploy/", "infra/", "k8s/", "helm/", "terraform/", "charts/", "ops/");

  static final class Mod { String dir, name, lang, kind, test, build; final List<String> frameworks = new ArrayList<>(); final Set<String> exts = new LinkedHashSet<>(); final List<String> src = new ArrayList<>(); }

  public static Suggestion detect(List<String> allPaths, Function<String, String> read) {
    List<String> paths = allPaths.stream().filter(p -> !SKIP.matcher(p).find()).toList();
    Set<String> set = new HashSet<>(paths); List<String> evidence = new ArrayList<>();
    // 1) 找清单：根目录或最多 2 层子目录
    Map<String, List<String>> byDir = new TreeMap<>();
    for (String p : paths) {
      int slash = p.lastIndexOf('/'); String dir = slash < 0 ? "" : p.substring(0, slash + 1), file = p.substring(slash + 1);
      if (MANIFESTS.contains(file) && dir.chars().filter(c -> c == '/').count() <= 2) byDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(file);
    }
    List<Mod> mods = new ArrayList<>();
    for (var e : byDir.entrySet()) {
      String dir = e.getKey(); List<String> files = e.getValue(); Mod m = new Mod(); m.dir = dir; m.name = dir.isEmpty() ? "root" : dir.replaceAll("/$", "").replace('/', '-');
      if (files.contains("go.mod")) goMod(m, read.apply(dir + "go.mod"));
      else if (files.contains("pom.xml")) { m.lang = "Java"; m.kind = "backend"; String pom = Objects.toString(read.apply(dir + "pom.xml"), ""); if (pom.contains("spring-boot")) m.frameworks.add("Spring Boot"); m.test = "mvn -q test"; m.build = "mvn -q -DskipTests package"; m.exts.addAll(List.of("java")); }
      else if (files.contains("build.gradle") || files.contains("build.gradle.kts")) { m.lang = files.contains("build.gradle.kts") ? "Kotlin/Java" : "Java"; m.kind = "backend"; String g = Objects.toString(read.apply(dir + (files.contains("build.gradle") ? "build.gradle" : "build.gradle.kts")), ""); if (g.contains("spring")) m.frameworks.add("Spring Boot"); String w = set.contains(dir + "gradlew") ? "./gradlew" : "gradle"; m.test = w + " test"; m.build = w + " build -x test"; m.exts.addAll(List.of("java", "kt")); }
      else if (files.contains("pyproject.toml") || files.contains("requirements.txt")) { m.lang = "Python"; m.kind = "backend"; String t = Objects.toString(read.apply(dir + (files.contains("pyproject.toml") ? "pyproject.toml" : "requirements.txt")), "").toLowerCase(Locale.ROOT); for (String f : List.of("django", "fastapi", "flask")) if (t.contains(f)) m.frameworks.add(cap(f)); m.test = "pytest -q"; m.build = null; m.exts.add("py"); }
      else if (files.contains("Cargo.toml")) { m.lang = "Rust"; m.kind = "backend"; m.test = "cargo test"; m.build = "cargo build"; m.exts.add("rs"); }
      else if (files.contains("package.json")) { if (!node(m, read.apply(dir + "package.json"), set, paths)) continue; }
      else continue;
      // 源码目录
      for (String s : List.of("src/", "lib/", "app/", "internal/", "cmd/", "pkg/", "tests/", "test/", "__tests__/")) if (hasPrefix(paths, dir + s)) m.src.add(dir + s);
      if (m.src.isEmpty() || m.lang.startsWith("Go") || "Python".equals(m.lang)) { if (!dir.isEmpty()) { m.src.clear(); m.src.add(dir); } }
      if (m.src.isEmpty()) m.src.add(dir.isEmpty() ? "src/" : dir);
      mods.add(m); evidence.add((dir.isEmpty() ? "根目录" : dir) + " " + String.join("+", files));
    }
    // 根目录是 workspace 壳（有子模块且根本身无源码）→ 去掉
    if (mods.size() > 1) mods.removeIf(m -> m.dir.isEmpty() && "workspace".equals(m.kind));
    if (mods.isEmpty()) {
      Mod m = new Mod(); m.dir = ""; m.name = "root"; m.lang = "未知"; m.kind = "lib"; m.src.add(hasPrefix(paths, "src/") ? "src/" : "docs/"); mods.add(m); evidence.add("未找到已知清单文件");
    }
    // 2) 汇总
    List<ProjectCfg.Module> modules = new ArrayList<>(); LinkedHashSet<String> allowed = new LinkedHashSet<>(), exts = new LinkedHashSet<>(), roots = new LinkedHashSet<>();
    List<String> desc = new ArrayList<>();
    for (Mod m : mods) {
      var pm = new ProjectCfg.Module(); pm.name = m.name; pm.path = m.dir.isEmpty() ? "./" : m.dir; pm.language = m.lang + (m.frameworks.isEmpty() ? "" : " (" + String.join(", ", m.frameworks) + ")");
      pm.testCommand = cmd(m.dir, m.test); pm.buildCommand = cmd(m.dir, m.build); modules.add(pm);
      allowed.addAll(m.src); exts.addAll(m.exts);
      for (String s : m.src) if (!s.matches(".*(tests?|__tests__)/$")) roots.add(s);
      desc.add((m.dir.isEmpty() ? "" : m.dir + "：") + m.lang + (m.frameworks.isEmpty() ? "" : " + " + String.join(" + ", m.frameworks)) + (m.test == null ? "，无测试命令" : "，测试 `" + pm.testCommand + "`"));
    }
    // 源码目录里的样式 / 本地化 JSON
    for (String p : paths) if (allowed.stream().anyMatch(p::startsWith)) { String ext = ext(p); if (List.of("css", "scss", "less", "vue", "svelte", "sql", "json").contains(ext)) exts.add(ext); }
    if (hasPrefix(paths, "docs/")) allowed.add("docs/");
    exts.addAll(List.of("md", "txt"));
    List<ProjectCfg.PathRule> rules = new ArrayList<>();
    for (String f : FORBIDDEN_DIRS) rules.add(new ProjectCfg.PathRule(f, "forbidden"));
    rules.add(new ProjectCfg.PathRule("CODEOWNERS", "forbidden"));
    for (String p : paths) { String file = p.substring(p.lastIndexOf('/') + 1); if (file.equals("Dockerfile") || file.startsWith("docker-compose") || file.equals("nginx.conf") || file.startsWith(".env")) rules.add(new ProjectCfg.PathRule(p, "forbidden")); }
    for (String p : paths) if (p.matches("(.*/)?migrations/.*")) { String d = p.substring(0, p.indexOf("migrations/") + "migrations/".length()); if (rules.stream().noneMatch(r -> r.prefix.equals(d))) rules.add(new ProjectCfg.PathRule(d, "high")); }
    for (String a : allowed) rules.add(new ProjectCfg.PathRule(a, a.matches(".*(tests?|__tests__)/$") ? "low" : a.equals("docs/") ? "low" : "mid"));
    rules.add(new ProjectCfg.PathRule("README", "low"));
    String shape = mods.size() > 1 ? (mods.stream().anyMatch(m -> "frontend".equals(m.kind)) && mods.stream().anyMatch(m -> "backend".equals(m.kind)) ? "前后端分离全栈仓库" : "多模块仓库") : "单模块仓库";
    String technology = shape + "：" + String.join("；", desc);
    // CI 文件
    String workflow = null; List<String> wfs = paths.stream().filter(p -> p.matches("\\.github/workflows/[^/]+\\.ya?ml")).map(p -> p.substring(p.lastIndexOf('/') + 1)).sorted().toList();
    if (!wfs.isEmpty()) workflow = wfs.contains("ci.yml") ? "ci.yml" : wfs.get(0);
    return new Suggestion(technology, modules, new ArrayList<>(allowed), new ArrayList<>(exts), rules, roots.isEmpty() ? new ArrayList<>(allowed) : new ArrayList<>(roots), workflow, evidence);
  }

  private static void goMod(Mod m, String gomod) {
    m.lang = "Go"; m.kind = "backend"; m.test = "go test ./..."; m.build = "go build ./..."; m.exts.add("go");
    String t = Objects.toString(gomod, ""); var ver = Pattern.compile("(?m)^go (\\S+)").matcher(t); if (ver.find()) m.lang = "Go " + ver.group(1);
    for (String[] f : new String[][]{{"gin-gonic/gin", "Gin"}, {"labstack/echo", "Echo"}, {"gofiber/fiber", "Fiber"}, {"go-chi/chi", "chi"}, {"gorm.io/gorm", "GORM"}, {"go-sql-driver/mysql", "MySQL"}, {"jackc/pgx", "PostgreSQL"}, {"lib/pq", "PostgreSQL"}})
      if (t.contains(f[0]) && !m.frameworks.contains(f[1])) m.frameworks.add(f[1]);
  }
  /** @return false 表示不是有效模块（如解析失败） */
  private static boolean node(Mod m, String raw, Set<String> set, List<String> paths) {
    JsonNode pkg; try { pkg = Json.tree(raw); } catch (Exception e) { return false; }
    if (pkg == null || !pkg.isObject()) return false;
    Set<String> deps = new HashSet<>(); for (String k : List.of("dependencies", "devDependencies", "peerDependencies")) pkg.path(k).fieldNames().forEachRemaining(deps::add);
    boolean ts = deps.contains("typescript") || set.contains(m.dir + "tsconfig.json");
    m.lang = ts ? "TypeScript" : "JavaScript";
    String[][] fw = {{"next", "Next.js", "frontend"}, {"nuxt", "Nuxt", "frontend"}, {"react", "React", "frontend"}, {"vue", "Vue", "frontend"}, {"svelte", "Svelte", "frontend"}, {"@angular/core", "Angular", "frontend"},
        {"@nestjs/core", "NestJS", "backend"}, {"express", "Express", "backend"}, {"koa", "Koa", "backend"}, {"fastify", "Fastify", "backend"}, {"vite", "Vite", null}, {"tailwindcss", "Tailwind", null},
        {"vitest", "Vitest", null}, {"jest", "Jest", null}, {"@playwright/test", "Playwright", null}};
    for (String[] f : fw) if (deps.contains(f[0])) { m.frameworks.add(f[1]); if (f[2] != null && m.kind == null) m.kind = f[2]; }
    if (m.frameworks.contains("Next.js")) m.frameworks.remove("React");
    boolean workspace = pkg.has("workspaces") || set.contains(m.dir + "pnpm-workspace.yaml") && m.dir.isEmpty();
    if (m.kind == null) m.kind = workspace ? "workspace" : "lib";
    String pm = set.contains(m.dir + "pnpm-lock.yaml") && !set.contains(m.dir + "package-lock.json") ? "pnpm" : set.contains(m.dir + "yarn.lock") ? "yarn" : "npm";
    var scripts = pkg.path("scripts");
    m.test = scripts.has("test") && !scripts.path("test").asText().contains("no test specified") ? (pm.equals("npm") ? "npm test" : pm + " test") : null;
    m.build = scripts.has("build") ? pm + " run build" : null;
    if ("JavaScript".equals(m.lang) && !"frontend".equals(m.kind) && !deps.contains("@babel/core") && !pkg.path("type").asText().equals("module")) m.lang = "JavaScript (CommonJS)";
    if (ts) m.exts.addAll(List.of("ts", "tsx")); m.exts.addAll(List.of("js", "jsx", "mjs", "cjs"));
    if (!ts) m.exts.removeAll(List.of("ts", "tsx"));
    return true;
  }
  private static String cmd(String dir, String c) { return c == null ? "" : dir.isEmpty() ? c : "cd " + dir.replaceAll("/$", "") + " && " + c; }
  private static boolean hasPrefix(List<String> paths, String prefix) { for (String p : paths) if (p.startsWith(prefix)) return true; return false; }
  private static String ext(String p) { int i = p.lastIndexOf('.'), s = p.lastIndexOf('/'); return i > s ? p.substring(i + 1).toLowerCase(Locale.ROOT) : ""; }
  private static String cap(String s) { return s.equals("fastapi") ? "FastAPI" : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
