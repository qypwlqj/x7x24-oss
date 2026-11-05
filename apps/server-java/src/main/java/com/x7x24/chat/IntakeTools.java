package com.x7x24.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.DeliveryTools;
import com.x7x24.adapters.Redactor;
import com.x7x24.db.Db;
import com.x7x24.engine.ProjectRepo;
import com.x7x24.gov.ConfigStore;
import com.x7x24.gov.Risk;
import com.x7x24.util.Json;
import com.x7x24.util.U;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 录入 agent 的只读工具。全部在当前工作区上下文里执行（项目不属于当前工作区 → 当作不存在）。
 * 工具返回的仓库内容一律用 {@link #fence} 包成不可信数据（防提示注入）；密钥路径禁读，输出再过一遍脱敏。
 * 注意：没有「创建任务」工具 —— 任务只能由用户在确认卡上创建（决策 2A）。
 */
public class IntakeTools {
  public static final List<String> NAMES = List.of("list_projects", "repo_tree", "read_file", "detect_stack", "find_duplicates", "estimate_risk_size", "propose_pipeline");
  static final Pattern DENY = Pattern.compile("(?i)(^|/)(\\.env(\\..*)?|[^/]*\\.(pem|key|p12|pfx|keystore|jks)|id_(rsa|ed25519|ecdsa)[^/]*|[^/]*(secret|credential|password)[^/]*|\\.npmrc|\\.pypirc|\\.netrc|\\.git-credentials)$");
  public static final int MAX_FILE_CHARS = 64_000, MAX_TREE = 300, MAX_DUPS = 5;

  public interface Planner { Object propose(Draft d, JsonNode args); }

  private final ProjectRepo projects; private final DeliveryTools tools; private final Db db; private final ConfigStore cfg; private final com.x7x24.integration.StackDetectService stack;
  /** 可选：产品线列表（让 agent 能把「放到 XX 产品线」对上 id） */
  public java.util.function.Supplier<List<Map<String, Object>>> lineLister = List::of;
  public List<Map<String, Object>> listLines() { return lineLister.get(); }
  public Planner planner = (d, a) -> Map.of("error", "流程计划在 C2 提供");
  public IntakeTools(ProjectRepo projects, DeliveryTools tools, Db db, ConfigStore cfg, com.x7x24.integration.StackDetectService stack) { this.projects = projects; this.tools = tools; this.db = db; this.cfg = cfg; this.stack = stack; }

  /** 一次工具调用的结果：full 只在本轮提示里使用；summary 持久化 */
  public record Out(String name, String full, String summary, boolean fileRead) {}

  /** 把外部内容包成不可信数据块；内容里伪造的开 / 闭标记会被转义，不能提前结束数据块 */
  public static String fence(String tool, String body) {
    String b = Redactor.redact(body == null ? "" : body).replaceAll("(?i)<(/?)\\s*repo_data", "<$1repo_data_escaped");
    return "<repo_data tool=\"" + tool + "\" untrusted=\"true\">\n" + b + "\n</repo_data>";
  }

  public Out run(String name, JsonNode args, Draft draft, int fileReadsSoFar, int maxFileReads) {
    JsonNode a = args == null || !args.isObject() ? Json.M.createObjectNode() : args;
    try {
      return switch (name) {
        case "list_projects" -> json(name, listProjects(), false);
        case "repo_tree" -> repoTree(a);
        case "read_file" -> {
          if (fileReadsSoFar >= maxFileReads) yield err(name, "本会话读取文件次数已达上限 " + maxFileReads);
          yield readFile(a);
        }
        case "detect_stack" -> json(name, detectStack(a), false);
        case "find_duplicates" -> json(name, duplicates(a.path("text").asText(draft.title == null ? "" : draft.title + " " + U.ellipsis(draft.goal, 200))), false);
        case "estimate_risk_size" -> json(name, estimate(a, draft), false);
        case "propose_pipeline" -> json(name, planner.propose(draft, a), false);
        default -> err(name, "没有这个工具。你只能使用：" + String.join(", ", NAMES) + "。你不能创建任务，任务只能由用户在确认卡上创建。");
      };
    } catch (IllegalArgumentException e) { return err(name, e.getMessage()); }
    catch (RuntimeException e) { return err(name, "工具执行失败：" + Redactor.redact(e.getMessage())); }
  }

  private Out err(String name, String m) { return new Out(name, "<tool_error tool=\"" + name + "\">" + m + "</tool_error>", "错误：" + U.ellipsis(m, 200), false); }
  private Out json(String name, Object o, boolean read) { String s = Json.str(o); return new Out(name, fence(name, s), brief(name, Json.M.valueToTree(o), s), read); }
  /** 给界面和历史看的一句话摘要（完整结果只进本轮提示词） */
  static String brief(String name, JsonNode n, String raw) {
    if (n.has("error")) return "失败：" + U.ellipsis(n.path("error").asText(), 120);
    return switch (name) {
      case "list_projects" -> "列出 " + n.size() + " 个项目";
      case "detect_stack" -> "识别技术栈：" + U.ellipsis(n.path("technology").asText("未识别"), 100);
      case "find_duplicates" -> n.isArray() ? "相似任务 " + n.size() + " 条" : U.ellipsis(raw, 160);
      case "estimate_risk_size" -> "风险评估：" + n.path("risk").asText("?") + (n.path("allowed").asBoolean(true) ? "" : "（超出自治等级）");
      case "propose_pipeline" -> "试算流程计划：" + n.path("adjustments").size() + " 条调整" + (n.path("needsApproval").asBoolean() ? " · 含放松项" : "");
      default -> U.ellipsis(raw, 160);
    };
  }

  List<Map<String, Object>> listProjects() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (var p : projects.list()) {
      if (!"ACTIVE".equals(p.state())) continue; var rt = projects.runtime(p.id());
      Map<String, Object> m = new LinkedHashMap<>(); m.put("id", p.id()); m.put("name", p.name()); m.put("productLine", p.productLineId()); m.put("repo", p.cfg().repo);
      m.put("baseBranch", p.cfg().baseBranch); m.put("autonomy", rt.autonomyLevel()); m.put("technology", p.cfg().technology); m.put("description", U.ellipsis(p.description(), 200)); out.add(m);
    }
    return out;
  }
  ProjectRepo.Project project(JsonNode a) {
    String id = a.path("project").asText(a.path("projectId").asText(""));
    var p = id.isBlank() ? null : projects.get(id);
    if (p == null) throw new IllegalArgumentException("项目不存在或不在当前工作区：" + id);
    return p;
  }
  static String cleanPath(String p) {
    String x = p == null ? "" : p.trim().replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
    if (x.contains("..")) throw new IllegalArgumentException("路径不能包含 ..");
    return x;
  }
  Out repoTree(JsonNode a) {
    var p = project(a); String path = cleanPath(a.path("path").asText(""));
    var items = tools.listDir(p.cfg(), p.cfg().baseBranch, path);
    List<String> list = items == null ? List.of() : items.stream().limit(MAX_TREE).toList();
    String body = (list.isEmpty() ? "（空目录或仓库不可访问）" : String.join("\n", list)) + (items != null && items.size() > MAX_TREE ? "\n…（截断，共 " + items.size() + " 项）" : "");
    return new Out("repo_tree", fence("repo_tree", p.cfg().repo + ":" + (path.isEmpty() ? "/" : path) + "\n" + body), "列目录 " + p.cfg().repo + "/" + path + "（" + list.size() + " 项）", false);
  }
  Out readFile(JsonNode a) {
    var p = project(a); String path = cleanPath(a.path("path").asText(""));
    if (path.isEmpty()) throw new IllegalArgumentException("缺少 path");
    if (DENY.matcher(path).find()) throw new IllegalArgumentException("出于安全原因不读取可能含密钥的文件：" + path);
    String body = tools.readFile(p.cfg(), p.cfg().baseBranch, path);
    if (body == null) return new Out("read_file", fence("read_file", path + "\n（文件不存在或仓库不可访问）"), "读取 " + path + "：不存在", true);
    boolean cut = body.length() > MAX_FILE_CHARS; String b = cut ? body.substring(0, MAX_FILE_CHARS) + "\n…（截断）" : body;
    return new Out("read_file", fence("read_file", path + "\n" + b), "读取 " + path + "（" + body.length() + " 字符" + (cut ? "，截断" : "") + "，sha " + U.sha256(body).substring(0, 8) + "）", true);
  }
  Map<String, Object> detectStack(JsonNode a) {
    var p = project(a); var c = p.cfg(); Map<String, Object> m = new LinkedHashMap<>();
    m.put("repo", c.repo); m.put("technology", c.technology); m.put("modules", c.modules); m.put("allowedPaths", c.allowedPaths); m.put("pathRules", c.pathRules);
    if ((c.technology == null || c.technology.startsWith("未识别")) && stack != null && c.repo != null) {
      try { var s = stack.detect(c.repo, c.baseBranch); m.put("detected", s); } catch (RuntimeException e) { m.put("detectError", Redactor.redact(e.getMessage())); }
    }
    return m;
  }
  /** 当前工作区里未关闭的任务（RLS + 应用层过滤） */
  public List<Map<String, Object>> duplicates(String text) {
    if (text == null || text.isBlank()) return List.of();
    String ws = com.x7x24.tenant.Tenant.readFilter();
    var rows = db.rows("SELECT id, title, status, project_id FROM task WHERE deleted_at IS NULL AND status NOT IN ('DISCARDED','RELEASED')" + (ws == null ? "" : " AND workspace_id=?") + " ORDER BY created_at DESC LIMIT 300", ws == null ? new Object[0] : new Object[]{ws});
    List<Map<String, Object>> out = new ArrayList<>();
    for (var r : rows) { double s = Precheck.similarity(text, (String) r.get("title")); if (s >= 0.45) { Map<String, Object> m = new LinkedHashMap<>(r); m.put("similarity", U.round(s, 2)); out.add(m); } }
    out.sort((x, y) -> Double.compare((double) y.get("similarity"), (double) x.get("similarity")));
    return out.size() > MAX_DUPS ? out.subList(0, MAX_DUPS) : out;
  }
  public Map<String, Object> estimate(JsonNode a, Draft d) {
    String pid = a.path("project").asText(d.projectId == null ? "" : d.projectId);
    var p = pid.isBlank() ? null : projects.get(pid); if (p == null) throw new IllegalArgumentException("项目不存在或不在当前工作区：" + pid);
    List<String> paths = new ArrayList<>(); if (a.has("paths") && a.get("paths").isArray()) a.get("paths").forEach(x -> paths.add(x.asText())); else paths.addAll(d.targetPaths);
    String text = a.path("summary").asText((d.title == null ? "" : d.title) + " " + (d.goal == null ? "" : d.goal));
    var risk = Risk.classify(cfg.get(), text, null, p.cfg()::riskOf, paths);
    String autonomy = projects.runtime(p.id()).autonomyLevel();
    Map<String, Object> m = new LinkedHashMap<>(); m.put("risk", risk.level()); m.put("basis", risk.basis()); m.put("matchedPaths", risk.matchedPaths()); m.put("reasons", risk.reasons());
    m.put("autonomy", autonomy); m.put("allowed", !"forbidden".equals(risk.level()) && Risk.autonomyAllows(autonomy, risk.level()));
    m.put("maxChangeLines", cfg.get().release.maxChangeLines); return m;
  }
}
