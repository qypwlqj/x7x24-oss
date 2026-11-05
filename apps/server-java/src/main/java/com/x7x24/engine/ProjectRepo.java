package com.x7x24.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.x7x24.db.Db;
import com.x7x24.gov.AuditLog;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** 统一提供项目归属、版本化执行上下文和上级治理约束。 */
@Component
public class ProjectRepo {
  public record Project(String id, String productLineId, String name, String description, String owner, String state, ProjectCfg cfg, int configVersion, String createdAt, String updatedAt, String workspaceId) {}
  public record Runtime(String id, String productLineId, String name, ProjectCfg cfg, int configVersion, String autonomyLevel, int paused, String intakePausedUntil) {}
  private final Db db; private final Clk clk; private final LineRepo lines; private final AuditLog audit;
  public ProjectRepo(Db db, Clk clk, LineRepo lines, AuditLog audit) { this.db = db; this.clk = clk; this.lines = lines; this.audit = audit; }
  private Project map(Map<String, Object> row) {
    var config = Json.parse((String) row.get("config"), ProjectCfg.class);
    config.id = Db.str(row.get("id")); config.productLineId = Db.str(row.get("product_line_id")); config.name = Db.str(row.get("name"));
    return new Project(config.id, config.productLineId, config.name, Db.str(row.get("description")), Db.str(row.get("owner")), Db.str(row.get("state")), config, Db.i(row.get("config_version")), Db.str(row.get("created_at")), Db.str(row.get("updated_at")), Db.str(row.get("workspace_id")));
  }
  public Project get(String id) { var row = db.row("SELECT * FROM project WHERE id=?" + com.x7x24.tenant.Tenant.and("workspace_id"), com.x7x24.tenant.Tenant.args(id)); return row == null ? null : map(row); }
  public Project require(String id) { var p = get(id); if (p == null) throw new IllegalArgumentException("项目不存在"); return p; }
  public List<Project> list() { return db.rows("SELECT * FROM project WHERE 1=1" + com.x7x24.tenant.Tenant.and("workspace_id") + " ORDER BY created_at,id", com.x7x24.tenant.Tenant.args()).stream().map(this::map).toList(); }
  public String resolve(String projectId, String legacyLine) {
    if (projectId != null && !projectId.isBlank()) {
      var p = require(projectId); if (legacyLine != null && !legacyLine.equals(p.productLineId())) throw new IllegalArgumentException("项目不属于指定产品线"); return p.id();
    }
    var matches = list().stream().filter(p -> Objects.equals(p.productLineId(), legacyLine) && !p.state().equals("ARCHIVED")).toList();
    if (matches.size() != 1) throw new IllegalArgumentException("请选择一个项目"); return matches.getFirst().id();
  }
  public Runtime runtime(String id) { return runtime(require(id), null); }
  public Runtime forTask(Task task) { return runtime(require(task.projectId), task.context.projectSnapshot); }
  public ProjectCfg constrained(ProjectCfg snapshot) { var project = get(snapshot.id); return project == null ? snapshot : runtime(project, snapshot).cfg(); }
  private Runtime runtime(Project p, ProjectCfg snapshot) {
    var line = lines.require(p.productLineId()); var config = snapshot == null ? Json.convert(p.cfg(), ProjectCfg.class) : Json.convert(snapshot, ProjectCfg.class);
    // 技术配置采用任务快照；禁止项和自治等级一律以项目当前设置为准（调高、调低都立即生效，不被旧快照卡住）。
    config.autonomy = p.cfg().autonomy == null ? "L2" : p.cfg().autonomy;
    config.autoInitializeRepository = p.cfg().autoInitializeRepository; // 授权撤回立即生效，不能被任务旧快照覆盖。
    // 发布目标属于交付约束，立即作用于在途任务；待配置任务仍需显式恢复。
    config.releaseTarget = p.cfg().releaseTarget; config.deployWorkflow = p.cfg().deployWorkflow;
    config.maxConcurrentTasks = Math.min(config.maxConcurrentTasks, p.cfg().maxConcurrentTasks);
    for (var rule : p.cfg().pathRules) if (config.pathRules.stream().noneMatch(r -> r.prefix.equals(rule.prefix) && r.level.equals(rule.level))) config.pathRules.add(rule);
    config.allowedPaths = narrowerPaths(config.allowedPaths, p.cfg().allowedPaths);
    // 文件类型以项目当前设置为准：负责人在项目设置里补上 tsx 等类型后，在途任务立即生效（禁区目录 / 文件名仍由 PathPolicy 固定拦截）。
    config.allowedExtensions = new ArrayList<>(p.cfg().allowedExtensions);
    config.devMode = p.cfg().devMode; // 开发方式以项目当前设置为准，下一轮开发生效。
    config.allowedEnvNames.retainAll(p.cfg().allowedEnvNames);
    config.serverEnvPaths=narrowerPaths(config.serverEnvPaths,p.cfg().serverEnvPaths);
    return new Runtime(p.id(), p.productLineId(), p.name(), config, p.configVersion(), minAutonomy(line.autonomyLevel(), config.autonomy), !p.state().equals("ACTIVE") || !line.state().equals("ACTIVE") || line.paused() != 0 ? 1 : 0, line.intakePausedUntil());
  }
  /**
   * 自治等级说明：生效等级 = 产品线等级和项目等级里较低的那个。
   * 返回各层等级、是哪一层卡住、以及系统最近有没有自动下调产品线等级，供废弃详情和界面使用。
   */
  public Map<String, Object> autonomyOf(Runtime rt) {
    Map<String, Object> m = new LinkedHashMap<>();
    var line = lines.get(rt.productLineId()); String lineLevel = line == null ? rt.autonomyLevel() : line.autonomyLevel(), lineName = line == null ? rt.productLineId() : line.name();
    String projLevel = rt.cfg() == null || rt.cfg().autonomy == null ? "L2" : rt.cfg().autonomy;
    String eff = minAutonomy(lineLevel, projLevel);
    boolean lineLimits = lineLevel.compareTo(eff) == 0, projLimits = projLevel.compareTo(eff) == 0;
    m.put("effective", eff); m.put("productLine", Map.of("id", rt.productLineId(), "name", lineName, "level", lineLevel)); m.put("project", Map.of("id", rt.id(), "name", rt.name(), "level", projLevel));
    m.put("limitedBy", lineLimits && projLimits ? "both" : lineLimits ? "productLine" : "project");
    String explain = "产品线「" + lineName + "」是 " + lineLevel + "，项目「" + rt.name() + "」是 " + projLevel + "，两者取较低的";
    String fix;
    if (lineLimits && projLimits) fix = "请把产品线「" + lineName + "」和项目「" + rt.name() + "」的自治等级都调到 {need}，然后点「重启」。";
    else if (lineLimits) fix = "请把产品线「" + lineName + "」的自治等级调到 {need}（在「产品线」页修改），然后点「重启」。";
    else fix = "请把项目「" + rt.name() + "」的自治等级调到 {need}（在项目页修改），然后点「重启」。";
    try {
      var ev = db.row("SELECT payload, occurred_at FROM task_event WHERE event_type='AUTONOMY_CHANGED' AND payload LIKE ? ORDER BY id DESC LIMIT 1", "%\"" + rt.productLineId() + "\"%");
      if (ev != null) {
        var pl = Json.parse(String.valueOf(ev.get("payload")), Map.class);
        if (pl != null && "tighten".equals(pl.get("direction")) && lineLevel.equals(pl.get("to"))) {
          m.put("autoTightened", Map.of("from", String.valueOf(pl.get("from")), "to", String.valueOf(pl.get("to")), "at", String.valueOf(ev.get("occurred_at"))));
          explain += "；产品线的等级是系统因 24 小时内自动废弃或回滚过多，从 " + pl.get("from") + " 自动降下来的";
        }
      }
    } catch (RuntimeException ignored) { /* 说明信息查不到不影响判定 */ }
    m.put("explain", explain); m.put("fix", fix); return m;
  }
  private static List<String> narrowerPaths(List<String> a, List<String> b) {
    Set<String> result = new LinkedHashSet<>(); for (String x : a) for (String y : b) { if (x.startsWith(y)) result.add(x); else if (y.startsWith(x)) result.add(y); } return new ArrayList<>(result);
  }
  private static String minAutonomy(String a, String b) { return a.compareTo(b) <= 0 ? a : b; }
  public synchronized Project create(String lineId, JsonNode body) {
    return db.tx(() -> {
      db.row("SELECT id FROM product_line WHERE id=? FOR UPDATE", lineId);
      var line = lines.require(lineId); if (line.state().equals("ARCHIVED")) throw new IllegalArgumentException("不能在已归档产品线中新增项目");
      String id = "project-" + U.uuid(), name = LineRepo.requiredName(body.path("name").asText());
      ProjectCfg config = body.has("config") ? Json.convert(body.get("config"), ProjectCfg.class) : new ProjectCfg();
      config.id = id; config.name = name; config.productLineId = lineId; validate(config); validateNodePolicy(config); ensureRepoAvailable(id, config.repo);
      db.update("INSERT INTO project (id,product_line_id,name,description,owner,config,repo_binding,created_at,updated_at,workspace_id) VALUES (?,?,?,?,?,?,?,?,?,(SELECT workspace_id FROM product_line WHERE id=?))", id, lineId, name, body.path("description").asText(""), body.path("owner").asText(""), Json.str(config), binding(config.repo), clk.iso(), clk.iso(), lineId);
      audit.sys("PROJECT_CREATED", "operator", Map.of("id", id, "productLine", lineId, "repo", config.repo == null ? "" : config.repo), null); return get(id);
    });
  }
  /** 节点策略：只能禁用目录里的可选节点；必选节点必须是目录里的节点；技术栈画像只接受已知栈 */
  static void validateNodePolicy(ProjectCfg c) {
    if (c.nodePolicy == null) c.nodePolicy = new ProjectCfg.NodePolicy();
    if (c.nodePolicy.disabled == null) c.nodePolicy.disabled = new ArrayList<>();
    if (c.nodePolicy.mandatory == null) c.nodePolicy.mandatory = new ArrayList<>();
    for (String n : c.nodePolicy.disabled) { var d = com.x7x24.pipeline.Nodes.def(n); if (d == null) throw new IllegalArgumentException("未知节点：" + n); if (!d.optional) throw new IllegalArgumentException("节点 " + d.label + " 是必需节点，不能禁用"); }
    for (String n : c.nodePolicy.mandatory) if (com.x7x24.pipeline.Nodes.def(n) == null) throw new IllegalArgumentException("未知节点：" + n);
    if (c.stackProfile != null && c.stackProfile.modules != null) for (var m : c.stackProfile.modules) if (m != null && !com.x7x24.pipeline.StackProfiles.STACKS.contains(m.stack)) throw new IllegalArgumentException("未知技术栈：" + m.stack + "（可用：" + String.join("、", com.x7x24.pipeline.StackProfiles.STACKS) + "）");
  }
  public synchronized Project update(String id, JsonNode body) {
    return db.tx(() -> {
      db.row("SELECT id FROM project WHERE id=? FOR UPDATE", id);
      var old = require(id); if (body.has("productLineId") && !old.productLineId().equals(body.path("productLineId").asText())) throw new IllegalArgumentException("项目归属不可修改");
      String state = body.path("state").asText(old.state()); LineRepo.validateState(state);
      if (old.state().equals("ARCHIVED") && !state.equals("ARCHIVED")) throw new IllegalArgumentException("已归档项目不可重新启用");
      if (state.equals("ACTIVE") && lines.require(old.productLineId()).state().equals("ARCHIVED")) throw new IllegalArgumentException("产品线已归档");
      long pending = db.count("SELECT COUNT(*) FROM task WHERE project_id=? AND " + TaskRepo.UNFINISHED, id);
      if (state.equals("ARCHIVED") && pending > 0) throw new IllegalArgumentException("项目仍有未结束任务，不能归档");
      ObjectNode merged = Json.M.valueToTree(old.cfg()); if (body.has("config")) merge(merged, body.get("config"));
      ProjectCfg config = Json.convert(merged, ProjectCfg.class); config.id = id; config.productLineId = old.productLineId(); config.name = LineRepo.requiredName(body.path("name").asText(old.name())); validate(config);
      if (pending > 0 && (!Objects.equals(config.repo, old.cfg().repo) || !Objects.equals(config.baseBranch, old.cfg().baseBranch) || !Objects.equals(config.credentialRef, old.cfg().credentialRef))) throw new IllegalArgumentException("任务尚未结束，不能切换仓库、分支或凭据引用");
      if (!state.equals("ARCHIVED")) ensureRepoAvailable(id, config.repo);
      db.update("UPDATE project SET name=?,description=?,owner=?,state=?,config=?,repo_binding=?,config_version=config_version+1,updated_at=? WHERE id=?", config.name, body.path("description").asText(old.description()), body.path("owner").asText(old.owner()), state, Json.str(config), state.equals("ARCHIVED") ? null : binding(config.repo), clk.iso(), id);
      validateNodePolicy(config);
      var before = old.cfg().nodePolicy == null ? List.<String>of() : old.cfg().nodePolicy.disabled;
      var relaxed = config.nodePolicy.disabled.stream().filter(x -> !before.contains(x)).toList();
      if (!relaxed.isEmpty()) audit.sys("NODE_POLICY_RELAXED", "operator", Map.of("id", id, "disabled", relaxed), null);
      audit.sys("PROJECT_UPDATED", "operator", Map.of("id", id, "state", state, "configVersion", old.configVersion() + 1), null); return get(id);
    });
  }
  public synchronized void bootstrap(LegacyLineCfg seed) {
    String id = seed.id + "-default";
    if (get(id) == null && db.count("SELECT COUNT(*) FROM project WHERE product_line_id=?", seed.id) == 0) {
      ProjectCfg config = Json.convert(seed, ProjectCfg.class); config.id = id; config.productLineId = seed.id; ensureRepoAvailable(id, config.repo);
      db.update("INSERT INTO project (id,product_line_id,name,config,repo_binding,created_at,updated_at,workspace_id) VALUES (?,?,?,?,?,?,?,(SELECT workspace_id FROM product_line WHERE id=?))", id, seed.id, seed.name, Json.str(config), binding(config.repo), clk.iso(), clk.iso(), seed.id);
    }
    // SQL 迁移保留旧配置；只补仓库标识，不从环境变量覆盖配置。
    for (var p : list()) if (db.count("SELECT COUNT(*) FROM project WHERE id=? AND repo_binding IS NULL AND state<>'ARCHIVED'", p.id()) > 0 && p.cfg().repo != null) {
      ensureRepoAvailable(p.id(), p.cfg().repo); db.update("UPDATE project SET repo_binding=? WHERE id=?", binding(p.cfg().repo), p.id());
    }
    // 旧任务使用升级时的项目快照，不修改历史事件或证据。
    for (var row : db.rows("SELECT id,project_id,context FROM task")) {
      var context = Json.parse(Db.str(row.get("context")), TaskCtx.class);
      if (context.projectSnapshot != null) continue;
      var project = require(Db.str(row.get("project_id"))); context.projectSnapshot = project.cfg(); context.projectConfigVersion = project.configVersion();
      db.update("UPDATE task SET context=? WHERE id=?", Json.str(context), row.get("id"));
    }
  }
  private void ensureRepoAvailable(String id, String repo) { if (repo == null) return; if (db.count("SELECT COUNT(*) FROM project WHERE repo_binding=? AND id<>?", binding(repo), id) > 0) throw new IllegalArgumentException("该仓库已绑定其他未归档项目"); }
  private static String binding(String repo) { return repo == null ? null : repo.toLowerCase(Locale.ROOT); }
  static void merge(ObjectNode target, JsonNode patch) {
    if (!patch.isObject()) throw new IllegalArgumentException("配置必须为对象");
    patch.fields().forEachRemaining(e -> { if (e.getValue().isObject() && target.path(e.getKey()).isObject()) merge((ObjectNode) target.get(e.getKey()), e.getValue()); else target.set(e.getKey(), e.getValue()); });
  }
  public static void validateBudget(ProjectCfg.BudgetLimits budget) {
    if (budget == null) throw new IllegalArgumentException("预算配置不能为空");
    for (Double n : Arrays.asList(budget.dailyUsd, budget.dailyTokens, budget.dailyCiMinutes)) if (n != null && (!Double.isFinite(n) || n <= 0)) throw new IllegalArgumentException("预算必须为正数");
  }
  private static void validate(ProjectCfg c) {
    // 仓库可以暂不绑定（对话里建项目时常见）；依赖仓库的任务在启动时提示「项目未绑定仓库」
    if (c.repo != null && c.repo.isBlank()) c.repo = null;
    if (c.repo != null) {
      c.repo = c.repo.trim().replaceFirst("^https://github\\.com/", "").replaceFirst("/$", "").replaceFirst("\\.git$", "");
      if (!c.repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("仓库格式应为 owner/repo");
    }
    if (c.baseBranch == null || !c.baseBranch.matches("[A-Za-z0-9_./-]+") || c.baseBranch.length() > 200 || c.baseBranch.contains("..") || c.baseBranch.startsWith("-") || c.baseBranch.endsWith("/") || c.baseBranch.contains("//")) throw new IllegalArgumentException("分支名称不合法");
    if (c.workflow == null || !c.workflow.matches("[A-Za-z0-9_.-]+\\.ya?ml")) throw new IllegalArgumentException("CI 文件名必须是 yml/yaml 文件");
    if (c.releaseTarget == null || !ReleasePolicy.TARGETS.contains(c.releaseTarget)) throw new IllegalArgumentException("发布目标只能是 GitHub Release 或微信小程序");
    if (!"GITHUB_TOKEN".equals(c.credentialRef)) throw new IllegalArgumentException("当前凭据引用仅支持 GITHUB_TOKEN；请在服务器环境配置密钥");
    LineRepo.validateAutonomy(c.autonomy); validateBudget(c.budget);
    if (c.maxConcurrentTasks < 1 || c.maxConcurrentTasks > 32) throw new IllegalArgumentException("项目并发数应为 1–32");
    if (c.discovery == null || c.discovery.intervalMinutes < 5 || c.discovery.maxFiles < 1 || c.discovery.maxFiles > 30) throw new IllegalArgumentException("扫描间隔至少 5 分钟，文件数应为 1–30");
    if (c.devMode == null || c.devMode.isBlank()) c.devMode = com.x7x24.execution.agent.DevMode.SANDBOX;
    if (!com.x7x24.execution.agent.DevMode.valid(c.devMode)) throw new IllegalArgumentException("开发方式只能是 sandbox（沙盒）或 legacy（快速方式）");
    if (c.allowedPaths == null || c.allowedPaths.isEmpty() || c.allowedExtensions == null || c.allowedExtensions.isEmpty() || c.pathRules == null || c.modules == null || c.discovery.roots == null) throw new IllegalArgumentException("项目目录、文件类型和模块配置不能为空");
    for (String path : c.allowedPaths) if (path == null || path.isBlank() || !path.endsWith("/") || path.startsWith("/") || path.contains("..") || path.contains("\\")) throw new IllegalArgumentException("允许目录必须为相对目录，以 / 结尾");
    if(c.allowedEnvNames==null || c.serverEnvPaths==null)throw new IllegalArgumentException("服务端变量名与入口配置不能为 null");
    for(String name:c.allowedEnvNames)if(name==null || !name.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("环境配置只接受明确的变量名，不能填写值或通配符");
    for(String prefix:c.serverEnvPaths)if(prefix==null || !prefix.endsWith("/") || prefix.contains("..") || prefix.contains("\\") || c.allowedPaths.stream().noneMatch(prefix::startsWith))throw new IllegalArgumentException("服务端配置入口必须是允许目录内的相对目录");
    for (String path : c.discovery.roots) if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\")) throw new IllegalArgumentException("扫描目录必须为相对路径");
    for (var rule : c.pathRules) if (rule.prefix == null || rule.prefix.isBlank() || !List.of("low", "mid", "high", "forbidden").contains(rule.level)) throw new IllegalArgumentException("风险路径规则不合法");
  }
}
