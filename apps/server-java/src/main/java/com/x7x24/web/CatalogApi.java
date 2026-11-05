package com.x7x24.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.*;
import com.x7x24.db.Db;
import com.x7x24.engine.*;
import com.x7x24.gov.AuditLog;
import com.x7x24.util.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class CatalogApi {
  private final LineRepo lines; private final ProjectRepo projects; private final Db db;
  private final com.x7x24.gov.BudgetService budget; private final DeliveryTools tools; private final Engine engine; private final AuditLog audit; private final TaskRepo tasks; private final Clk clk;
  public CatalogApi(LineRepo lines, ProjectRepo projects, Db db, DeliveryTools tools, Engine engine, AuditLog audit, TaskRepo tasks, Clk clk, com.x7x24.gov.BudgetService budget) {
    this.budget = budget;
    this.lines = lines; this.projects = projects; this.db = db; this.tools = tools; this.engine = engine; this.audit = audit; this.tasks = tasks; this.clk = clk;
  }
  private Map<String, Object> lineDto(LineRepo.Line line) {
    var out = new LinkedHashMap<String, Object>(); out.put("id", line.id()); out.put("name", line.name()); out.put("description", line.description()); out.put("owner", line.owner());
    out.put("state", line.state()); out.put("autonomy", line.autonomyLevel()); out.put("autonomy_level", line.autonomyLevel()); out.put("config", line.cfg()); out.put("paused", line.paused() != 0);
    out.put("createdAt", line.createdAt()); out.put("projectCount", db.count("SELECT COUNT(*) FROM project WHERE product_line_id=? AND state<>'ARCHIVED'", line.id()));
    out.put("taskCount", db.count("SELECT COUNT(*) FROM task WHERE product_line=? AND deleted_at IS NULL", line.id()));
    out.put("spentUsd", db.dbl("SELECT COALESCE(SUM(usd),0) FROM budget_ledger WHERE product_line=?", line.id())); return out;
  }
  public static Map<String, Object> projectDto(ProjectRepo.Project project, ProjectRepo.Runtime context) {
    var out = new LinkedHashMap<String, Object>(); out.put("id", project.id()); out.put("productLineId", project.productLineId()); out.put("name", project.name());
    out.put("description", project.description()); out.put("owner", project.owner()); out.put("state", project.state()); out.put("paused", context.paused() != 0);
    out.put("autonomy", project.cfg().autonomy); out.put("effectiveAutonomy", context.autonomyLevel()); out.put("config", project.cfg());
    out.put("releaseCapability", com.x7x24.engine.ReleasePolicy.check(project.cfg()));
    out.put("configVersion", project.configVersion()); out.put("createdAt", project.createdAt()); out.put("updatedAt", project.updatedAt()); return out;
  }
  private Map<String, Object> projectDto(ProjectRepo.Project project) {
    var out = projectDto(project, projects.runtime(project.id())); out.put("productLineName", lines.require(project.productLineId()).name());
    out.put("taskCount", db.count("SELECT COUNT(*) FROM task WHERE project_id=? AND deleted_at IS NULL", project.id()));
    out.put("activeTasks", db.count("SELECT COUNT(*) FROM task WHERE project_id=? AND " + TaskRepo.UNFINISHED, project.id()));
    out.put("spentUsd", db.dbl("SELECT COALESCE(SUM(usd),0) FROM budget_ledger WHERE project_id=?", project.id()));
    out.put("lastScan", db.row("SELECT * FROM discovery_scan WHERE project_id=? ORDER BY started_at DESC LIMIT 1", project.id())); return out;
  }
  @GetMapping("/product-lines") public Object lines() { return lines.list().stream().map(this::lineDto).toList(); }
  @GetMapping("/product-lines/{id}") public Object line(@PathVariable String id) { return lineDto(lines.require(id)); }
  @PostMapping("/product-lines") public Object createLine(@RequestBody JsonNode body, jakarta.servlet.http.HttpServletRequest req) { com.x7x24.tenant.WorkspaceFilter.requireManager(req, "创建产品线"); return lineDto(lines.create(body)); }
  @PutMapping("/product-lines/{id}") public Object updateLine(@PathVariable String id, @RequestBody JsonNode body, jakarta.servlet.http.HttpServletRequest req) { com.x7x24.tenant.WorkspaceFilter.requireManager(req, "修改产品线"); return lineDto(lines.update(id, body)); }
  @GetMapping("/projects") public Object projects(@RequestParam(required = false) String productLineId) {
    if (productLineId != null) lines.require(productLineId);
    return projects.list().stream().filter(p -> productLineId == null || productLineId.equals(p.productLineId())).map(this::projectDto).toList();
  }
  @GetMapping("/product-lines/{id}/projects") public Object lineProjects(@PathVariable String id) { return projects(id); }
  @PostMapping("/product-lines/{id}/projects") public Object createProject(@PathVariable String id, @RequestBody JsonNode body, jakarta.servlet.http.HttpServletRequest req) { com.x7x24.tenant.WorkspaceFilter.requireManager(req, "创建项目"); return projectDto(projects.create(id, body)); }
  @GetMapping("/projects/{id}") public Object project(@PathVariable String id) { return projectDto(projects.require(id)); }
  @PutMapping("/projects/{id}") public Object updateProject(@PathVariable String id, @RequestBody JsonNode body, jakarta.servlet.http.HttpServletRequest req) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(req, "修改项目");
    var dp = body.path("config").path("nodePolicy").path("defaultPreset");
    if (dp.isTextual() && !dp.asText().isBlank() && presets.get(dp.asText(), projects.require(id).productLineId()) == null) throw new IllegalArgumentException("找不到流程预设 " + dp.asText() + "，或它不属于这条业务线");
    return projectDto(projects.update(id, body)); }
  @org.springframework.beans.factory.annotation.Autowired private com.x7x24.pipeline.Presets presets;
  @org.springframework.beans.factory.annotation.Autowired private com.x7x24.pipeline.PipelineService pipes;
  @org.springframework.beans.factory.annotation.Autowired private com.x7x24.integration.RuntimeSettings runtimeSettings;
  /** 手动建任务的起点（P5）：开启可组合流程时，按所选预设（不选则项目默认预设）组装 v2 流程；未开启时沿用业务线配置 */
  String manualSnapshot(com.x7x24.engine.ProjectRepo.Project project, JsonNode body) {
    if (!runtimeSettings.pipelineV2()) return null;
    String key = body.path("preset").asText(""); var pol = project.cfg().nodePolicy;
    if (key.isBlank() && pol != null && pol.defaultPreset != null) key = pol.defaultPreset;
    var preset = key.isBlank() ? null : presets.get(key, project.productLineId());
    if (!key.isBlank() && preset == null && body.hasNonNull("preset")) throw new IllegalArgumentException("找不到流程预设 " + key);
    var cfg = project.cfg(); var prof = com.x7x24.pipeline.StackProfiles.of(cfg);
    var ctx = new com.x7x24.pipeline.Assembler.Ctx(List.of(), prof.stacks, cfg, Map.of(), null, cfg.autonomy);
    return pipes.assemble(project.productLineId(), List.of(), null, ctx, true, true, preset).eff().id;
  }
  @PostMapping("/projects/{id}/detect") public Object detect(@PathVariable String id, @RequestParam(defaultValue = "false") boolean apply) { return stack.detectProject(id, apply, "operator"); }
  @PostMapping("/integrations/github/detect") public Object detectRepo(@RequestParam String repo, @RequestParam(required = false) String branch) { var s = stack.detect(repo, branch); return Map.of("suggestion", s, "patch", s.configPatch()); }
  @org.springframework.beans.factory.annotation.Autowired private com.x7x24.integration.StackDetectService stack;
  @PostMapping("/projects/{id}/connection") public Object connection(@PathVariable String id) {
    var project = projects.require(id);
    return tools instanceof GitHubTools github ? github.verifyProject(project.cfg()) : Map.of("ok", tools.live() && project.cfg().repo != null, "repo", project.cfg().repo == null ? "" : project.cfg().repo);
  }
  @PostMapping("/projects/{id}/tasks") public Object createTask(@PathVariable String id, @RequestBody JsonNode body) {
    var project = projects.require(id); budget.assertAdmission(id); String title = body.path("title").asText().trim();
    if (title.length() < 2 || title.length() > 500) throw new IllegalArgumentException("任务标题应为 2–500 字");
    String snap = manualSnapshot(project, body);
    String mode = com.x7x24.execution.agent.DevMode.normalize(body.path("devMode").asText(null));
    var r = engine.ingest(new Engine.Ingest(project.productLineId(), title, body.path("content").asText(""), "manual-task", null, null, tools.live(), null, id, body.path("acceptanceCriteria").asText(""), snap));
    if (mode != null && r.taskId() != null && !r.duplicate()) engine.setDevMode(r.taskId(), null, mode, "operator");
    return r;
  }
  @PutMapping("/tasks/{id}/pause") public Object pauseTask(@PathVariable String id, @RequestBody JsonNode body) {
    var task = tasks.get(id); if (task == null) throw new IllegalArgumentException("任务不存在");
    if (body.path("paused").asBoolean(true)) com.x7x24.chat.TaskActions.assertCanPause(task);
    task.paused = body.path("paused").asBoolean(true) ? 1 : 0;
    if (task.paused == 0 && task.nextRunAt != null) task.nextRunAt = clk.iso();
    if (!tasks.save(task)) throw new IllegalArgumentException("任务已发生变化，请刷新后重试");
    audit.task(id, task.currentIteration, "TASK_PAUSED", "operator", Map.of("paused", task.paused != 0, "projectId", task.projectId), null);
    return Map.of("id", id, "paused", task.paused != 0);
  }
}
