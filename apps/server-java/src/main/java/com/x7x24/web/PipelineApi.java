package com.x7x24.web;

import com.x7x24.auth.AuthFilter;
import com.x7x24.auth.AuthService;
import com.x7x24.engine.LineRepo;
import com.x7x24.gov.AuditLog;
import com.x7x24.pipeline.Catalog;
import com.x7x24.pipeline.Pipeline.*;
import com.x7x24.pipeline.PipelineService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 流程模板 / 角色 / 技能 / 业务线绑定（决策 6A：P1–P3 先以 JSON API 配置，页面在 P4）。 */
@RestController
@RequestMapping("/api/pipeline")
public class PipelineApi {
  private final PipelineService svc; private final LineRepo lines; private final AuditLog audit; private final com.x7x24.llm.ModelRouter router; private final com.x7x24.llm.Providers providers;
  @org.springframework.beans.factory.annotation.Autowired com.x7x24.engine.TaskRepo tasks; @org.springframework.beans.factory.annotation.Autowired com.x7x24.db.Db db;
  public PipelineApi(PipelineService svc, LineRepo lines, AuditLog audit, com.x7x24.llm.ModelRouter router, com.x7x24.llm.Providers providers) { this.svc = svc; this.lines = lines; this.audit = audit; this.router = router; this.providers = providers; }

  /** 模型配置：供应商 / 模型注册表 / 档位 / 角色绑定。只返回密钥引用与是否可用，不返回密钥。 */
  @GetMapping("/models") public Map<String, Object> models() {
    var c = router.config(); Map<String, Object> o = new LinkedHashMap<>();
    boolean platform = com.x7x24.tenant.Tenant.storeScope() == null;
    o.put("scope", platform ? "platform" : "workspace"); o.put("own", platform ? c : router.ownConfig()); o.put("platformFallback", router.fallbackEnabled());
    o.put("warnings", platform ? List.of() : com.x7x24.llm.ModelRouter.warnings(router.ownConfig()));
    o.put("mode", providers.mode()); o.put("config", c);
    Map<String, Object> av = new LinkedHashMap<>(); for (var p : c.providers) av.put(p.id, router.available(p.id)); o.put("providerAvailable", av);
    o.put("breakers", router.breakerStatus());
    o.put("committee", router.committeeStatus());
    o.put("errors", platform ? com.x7x24.llm.ModelRouter.validate(c, null) : com.x7x24.llm.ModelRouter.validate(router.ownConfig() == null ? com.x7x24.llm.ModelRouter.blank() : router.ownConfig(), null, false));
    Map<String, Object> roles = new LinkedHashMap<>();
    for (var r : Catalog.ROLES.values()) if ("maker".equals(r.kind)) { String prof = router.profileFor(r.id, null, false); roles.put(r.id, Map.of("profile", prof, "chain", router.chain(prof))); }
    o.put("roles", roles);
    return o;
  }
  public record CommitteeRequest(List<Seat> seats, boolean autoAssign) {}
  private ModelConfig committeeDraft(CommitteeRequest request) {
    var own = router.ownConfig(); var c = own == null ? com.x7x24.llm.ModelRouter.blank() : com.x7x24.util.Json.convert(own, ModelConfig.class);
    c.migratedFromEnv = false;
    if (request.seats() != null) {
      if (request.seats().stream().anyMatch(s -> s == null || s.chain == null || s.chain.stream().anyMatch(Objects::isNull))) throw new PipelineService.Invalid(List.of("评审席位需要有效的模型列表"));
      c.profiles.get("judges").seats = com.x7x24.util.Json.M.convertValue(request.seats(), new com.fasterxml.jackson.core.type.TypeReference<List<Seat>>() {});
    }
    if (request.autoAssign()) com.x7x24.llm.ModelRouter.assignJudges(c, true);
    var errors = com.x7x24.llm.ModelRouter.validateRegistration(c, com.x7x24.tenant.Tenant.storeScope() == null);
    if (!errors.isEmpty()) throw new PipelineService.Invalid(errors);
    return c;
  }
  /** 预览仅计算草稿，不写配置；已有的有效手动分配只有显式点击后才调整。 */
  @PostMapping("/models/committee/preview") public Map<String,Object> previewCommittee(@RequestBody CommitteeRequest request, HttpServletRequest r) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(r,"配置评审委员会");
    var c = committeeDraft(request); return Map.of("profile",c.profiles.get("judges"),"errors",com.x7x24.llm.ModelRouter.judgeErrors(c));
  }
  @PutMapping("/models/committee") public Map<String,Object> saveCommittee(@RequestBody CommitteeRequest request, HttpServletRequest r) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(r,"配置评审委员会");
    var c = committeeDraft(request); var errors = com.x7x24.llm.ModelRouter.judgeErrors(c);
    if (!errors.isEmpty()) throw new PipelineService.Invalid(errors);
    var status = router.committeeStatus(c);
    if (!Boolean.TRUE.equals(status.get("ready"))) throw new PipelineService.Invalid(com.x7x24.llm.ModelRouter.judgeModelErrors((List<String>)status.get("models")));
    if (com.x7x24.tenant.Tenant.storeScope() == null) { router.reloadRegistration(c); svc.putPlatformModels(c); } else svc.putModels(c);
    audit.sys("MODEL_COMMITTEE_UPDATED",actor(r),Map.of("seats",c.profiles.get("judges").seats),null);
    return models();
  }
  // ── 接入模型（供应商）：工作区 owner/admin 管理本工作区；scope=platform 时平台管理员管理平台默认供应商。密钥只写不读
  @org.springframework.beans.factory.annotation.Autowired com.x7x24.llm.ProviderAdmin providerAdmin;
  boolean platformScope(String scope) { return "platform".equals(scope); }
  /** 按 scope 执行：平台范围需要平台管理员，在平台上下文里执行；否则需要工作区管理员 */
  <T> T scoped(String scope, HttpServletRequest r, String what, java.util.function.Supplier<T> f) {
    if (platformScope(scope)) { if (!svc.isAdmin(actor(r))) throw new PipelineService.Forbidden("只有平台管理员可以" + what + "（平台默认）"); return com.x7x24.tenant.Tenant.run(com.x7x24.tenant.Tenant.PLATFORM, f); }
    com.x7x24.tenant.WorkspaceFilter.requireManager(r, what); return f.get();
  }
  @GetMapping("/providers") public Map<String, Object> providers(@RequestParam(required = false) String scope, HttpServletRequest r) { String me = actor(r);
    java.util.function.Supplier<Map<String, Object>> f = () -> { Map<String, Object> o = new LinkedHashMap<>(); o.put("providers", providerAdmin.list()); o.put("presets", com.x7x24.llm.ProviderAdmin.PRESETS);
      var own = router.ownConfig(); o.put("profiles", (own == null ? com.x7x24.llm.ModelRouter.blank() : own).profiles.keySet()); o.put("platformFallback", router.fallbackEnabled()); o.put("scope", platformScope(scope) ? "platform" : "workspace"); return o; };
    Map<String, Object> o = platformScope(scope) ? (svc.isAdmin(me) ? com.x7x24.tenant.Tenant.run(com.x7x24.tenant.Tenant.PLATFORM, f) : new LinkedHashMap<>(Map.of("providers", List.of()))) : f.get();
    o.put("admin", platformScope(scope) ? svc.isAdmin(me) : com.x7x24.tenant.WorkspaceFilter.manager(r)); o.put("platformAdmin", svc.isAdmin(me)); o.put("adminsConfigured", svc.adminsConfigured()); return o; }
  @PostMapping("/providers/test") public Map<String, Object> testProvider(@RequestBody com.x7x24.llm.ProviderAdmin.TestReq b, @RequestParam(required = false) String scope, HttpServletRequest r) { return scoped(scope, r, "管理模型供应商", () -> providerAdmin.test(b)); }
  @PostMapping("/providers") public Map<String, Object> saveProvider(@RequestBody com.x7x24.llm.ProviderAdmin.SaveReq b, @RequestParam(required = false) String scope, HttpServletRequest r) {
    String who = actor(r); boolean keyChanged = b.apiKey != null && !b.apiKey.isBlank(); var saved = scoped(scope, r, "管理模型供应商", () -> providerAdmin.save(b));
    audit.sys("MODEL_PROVIDER_SAVED", who, Map.of("provider", saved.get("id"), "scope", platformScope(scope) ? "platform" : "workspace", "baseUrl", String.valueOf(saved.get("baseUrl")), "keyChanged", keyChanged, "models", b.models.stream().map(m -> String.valueOf(m.id)).toList(), "addToProfiles", b.addToProfiles), null);
    return saved;
  }
  @DeleteMapping("/providers/{id}") public Map<String, Object> deleteProvider(@PathVariable String id, @RequestParam(required = false) String scope, HttpServletRequest r) {
    String who = actor(r); scoped(scope, r, "管理模型供应商", () -> { providerAdmin.delete(id); return null; }); audit.sys("MODEL_PROVIDER_DELETED", who, Map.of("provider", id, "scope", platformScope(scope) ? "platform" : "workspace"), null); return Map.of("ok", true);
  }

  @PutMapping("/models") public Map<String, Object> putModels(@RequestBody ModelConfig m, @RequestParam(required = false) String scope, HttpServletRequest r) {
    String who = actor(r); m.migratedFromEnv = false;
    if (platformScope(scope)) {
      if (!svc.isAdmin(who)) throw new PipelineService.Forbidden("只有平台管理员可以修改平台默认模型配置");
      try { router.reload(m); } catch (IllegalArgumentException e) { throw new PipelineService.Invalid(List.of(e.getMessage().split("；"))); }
      svc.putPlatformModels(m);
    } else {
      com.x7x24.tenant.WorkspaceFilter.requireManager(r, "修改模型配置");
      var errs = com.x7x24.llm.ModelRouter.validate(m, null, false); if (!errs.isEmpty()) throw new PipelineService.Invalid(errs);
      svc.putModels(m);
    }
    audit.sys("MODEL_CONFIG_UPDATED", who, Map.of("profiles", m.profiles.keySet(), "models", m.models.stream().map(x -> x.id).toList(), "providers", m.providers.stream().map(x -> x.id).toList()), null);
    return models();
  }
  @org.springframework.beans.factory.annotation.Autowired(required = false) void wire(LineRepo lr) { svc.lineInWorkspace = id -> lr.get(id) != null; }

  @ExceptionHandler(PipelineService.Invalid.class) ResponseEntity<Map<String, Object>> invalid(PipelineService.Invalid e) { return ResponseEntity.badRequest().body(Map.of("error", "invalid", "message", e.getMessage(), "errors", e.errors)); }
  @ExceptionHandler(PipelineService.Forbidden.class) ResponseEntity<Map<String, Object>> forbidden(PipelineService.Forbidden e) { return ResponseEntity.status(403).body(Map.of("error", "forbidden", "message", e.getMessage())); }
  @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", "invalid", "message", String.valueOf(e.getMessage()))); }

  static String actor(HttpServletRequest r) { return r.getAttribute(AuthFilter.ATTR) instanceof AuthService.User u ? u.login() : "operator"; }

  @GetMapping public Map<String, Object> all(HttpServletRequest r) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("version", svc.doc().version);
    Map<String, Object> cat = new LinkedHashMap<>();
    cat.put("stageTypes", Catalog.STAGE_TYPES); cat.put("stagePresets", Catalog.STAGE_PRESETS.values());
    cat.put("roles", Catalog.ROLES.values()); cat.put("skills", svc.skills().values());
    cat.put("healActions", Catalog.HEAL_ACTIONS.stream().sorted().toList()); cat.put("checks", Catalog.CHECKS);
    cat.put("floor", Map.of("maxChangeLinesCeiling", Catalog.MAX_CHANGE_LINES_CEILING, "maxAutonomy", Catalog.MAX_AUTONOMY, "minJudgeSeats", Catalog.MIN_JUDGE_SEATS, "optionalStages", List.of("SPEC", "DESIGN")));
    o.put("catalog", cat);
    o.put("templates", svc.templates().values());
    Map<String, Object> ls = new LinkedHashMap<>(); for (var l : lines.list()) ls.put(l.id(), svc.binding(l.id())); o.put("lines", ls);
    String me = actor(r); o.put("me", Map.of("login", me, "admin", com.x7x24.tenant.WorkspaceFilter.manager(r), "platformAdmin", svc.isAdmin(me), "adminsConfigured", svc.adminsConfigured(), "role", String.valueOf(com.x7x24.tenant.WorkspaceFilter.role(r))));
    return o;
  }

  @org.springframework.beans.factory.annotation.Autowired com.x7x24.engine.ProjectRepo projectRepo;
  @org.springframework.beans.factory.annotation.Autowired com.x7x24.integration.RuntimeSettings runtimeSettings;
  @org.springframework.beans.factory.annotation.Autowired com.x7x24.gov.AuditLog auditLog;
  @org.springframework.beans.factory.annotation.Autowired com.x7x24.pipeline.WorkspaceNodes wsNodes;
  @org.springframework.beans.factory.annotation.Autowired com.x7x24.pipeline.Presets presets;
  /** 流程预设（P5，决策 D5）：内置与业务线模板自动转换成预设，管理员可另存工作区预设 */
  @GetMapping("/presets") public Map<String, Object> presets(@RequestParam(required = false) String line) { if (line != null && !line.isBlank()) lines.require(line); return Map.of("presets", presets.list(line == null || line.isBlank() ? null : line), "pipelineV2", runtimeSettings.pipelineV2()); }
  @org.springframework.web.bind.annotation.PostMapping("/presets") public Object createPreset(@org.springframework.web.bind.annotation.RequestBody com.fasterxml.jackson.databind.JsonNode body, HttpServletRequest r) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(r, "保存流程预设");
    var p = presets.create(body, actor(r)); auditLog.sys("PIPELINE_PRESET_SAVED", actor(r), Map.of("id", p.id, "version", p.version, "template", p.template, "disable", p.disable), null); return p;
  }
  /** 工作区派生节点（决策 D6）：管理员提交草稿，owner 批准后可用 */
  @GetMapping("/workspace-nodes") public Map<String, Object> workspaceNodes() { return Map.of("nodes", wsNodes.list()); }
  @org.springframework.web.bind.annotation.PostMapping("/workspace-nodes") public Object createWorkspaceNode(@org.springframework.web.bind.annotation.RequestBody com.fasterxml.jackson.databind.JsonNode body, HttpServletRequest r) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(r, "提交工作区节点");
    var row = wsNodes.create(body, actor(r)); auditLog.sys("WORKSPACE_NODE_SUBMITTED", actor(r), Map.of("id", row.id(), "version", row.version()), null); return row;
  }
  @org.springframework.web.bind.annotation.PostMapping("/workspace-nodes/{id}/{version}/review") public Object reviewWorkspaceNode(@PathVariable String id, @PathVariable int version, @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body, HttpServletRequest r) {
    if (!"owner".equals(com.x7x24.tenant.WorkspaceFilter.role(r))) throw new com.x7x24.tenant.WorkspaceService.Forbidden("只有工作区所有者可以审批工作区节点");
    boolean ok = Boolean.TRUE.equals(body.get("approve"));
    var row = wsNodes.review(id, version, ok, actor(r)); auditLog.sys(ok ? "WORKSPACE_NODE_APPROVED" : "WORKSPACE_NODE_REJECTED", actor(r), Map.of("id", id, "version", version), null); return row;
  }
  /** 可组合流程功能开关（按工作区） */
  @GetMapping("/features") public Map<String, Object> features() { return Map.of("pipelineV2", runtimeSettings.pipelineV2()); }
  @org.springframework.web.bind.annotation.PutMapping("/features") public Map<String, Object> setFeatures(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body, HttpServletRequest r) {
    com.x7x24.tenant.WorkspaceFilter.requireManager(r, "切换可组合流程");
    if (!(body.get("pipelineV2") instanceof Boolean on)) throw new IllegalArgumentException("pipelineV2 必须是 true / false");
    boolean before = runtimeSettings.pipelineV2(); runtimeSettings.setPipelineV2(on);
    if (before != on) auditLog.sys("PIPELINE_V2_TOGGLED", actor(r), Map.of("from", before, "to", on), null);
    return features();
  }
  /** 节点目录（可组合流程 P1）：带 project 时附项目技术栈画像与每个节点是否允许 */
  @GetMapping("/nodes") public Map<String, Object> nodes(@RequestParam(required = false) String project) {
    Map<String, Object> o = new LinkedHashMap<>();
    var p = project == null || project.isBlank() ? null : projectRepo.require(project);
    o.put("nodes", com.x7x24.pipeline.Nodes.view(p == null ? null : p.cfg()));
    o.put("stacks", com.x7x24.pipeline.StackProfiles.STACKS);
    o.put("modules", List.of(Map.of("id", "sensing", "label", "感知"), Map.of("id", "production", "label", "生产"), Map.of("id", "delivery", "label", "交付")));
    if (p != null) { o.put("profile", com.x7x24.pipeline.StackProfiles.of(p.cfg())); o.put("policy", p.cfg().nodePolicy); o.put("project", p.id()); }
    return o;
  }
  @GetMapping("/lines/{lineId}/effective") public Effective effective(@PathVariable String lineId) { lines.require(lineId); return svc.resolve(lineId); }
  /** 快照只能读本工作区业务线的（快照按内容哈希全局去重，但读取要经业务线归属校验） */
  @GetMapping("/snapshots/{id}") public ResponseEntity<Object> snapshot(@PathVariable String id) {
    var row = db.row("SELECT line_id FROM config_snapshot WHERE id=?", id);
    if (row == null || row.get("line_id") == null || lines.get((String) row.get("line_id")) == null) return ResponseEntity.status(404).body(Map.of("error", "找不到这条记录，可能已被删除", "code", "not_found"));
    return ResponseEntity.ok(svc.snapshot(id, null)); }

  /** 预览：不保存，只解析 + 校验，返回生效快照（P5 的 dry-run 基础） */
  @PostMapping("/lines/{lineId}/preview") public Effective preview(@PathVariable String lineId, @RequestBody LineBinding b) { lines.require(lineId); var sk = svc.skills(); List<String> errs = new ArrayList<>();
    if (b.roles != null) b.roles.forEach((k, x) -> { x.id = k; errs.addAll(com.x7x24.pipeline.Validator.role(x, sk)); }); if (!errs.isEmpty()) throw new PipelineService.Invalid(errs); return svc.resolve(lineId, b); }

  /**
   * 试运行（dry-run）：用草稿绑定（不传则用当前绑定）对一个历史任务做「解析 + 校验 + 路由」，不调用模型、不改任何数据。
   * 输出：会走哪些节点、每个节点用哪个角色 / 档位 / 候选链、注入了哪些技能、该任务历史花费作为成本参考。
   */
  @PostMapping("/lines/{lineId}/dry-run") public Map<String, Object> dryRun(@PathVariable String lineId, @RequestBody Map<String, Object> body) {
    lines.require(lineId);
    LineBinding b = body.get("binding") == null ? svc.binding(lineId) : com.x7x24.util.Json.convert(body.get("binding"), LineBinding.class);
    var e = svc.resolve(lineId, b); String taskId = (String) body.get("taskId");
    List<String> planned = new ArrayList<>(List.of("SPEC", "DESIGN", "DEV")); Map<String, Object> o = new LinkedHashMap<>(); double usd = 0;
    if (taskId != null && !taskId.isBlank()) {
      var t = tasks.get(taskId); if (t == null) throw new IllegalArgumentException("任务不存在：" + taskId);
      if (!lineId.equals(t.productLine)) throw new IllegalArgumentException("任务不属于业务线 " + lineId);
      if (t.plan != null && t.plan.has("path")) { planned.clear(); t.plan.get("path").forEach(x -> planned.add(x.asText())); }
      usd = db.rows("SELECT COALESCE(SUM(usd),0) AS u FROM budget_ledger WHERE task_id=?", taskId).stream().mapToDouble(r -> ((Number) r.get("u")).doubleValue()).sum();
      o.put("task", Map.of("id", t.id, "title", t.title, "status", t.status, "pinned", String.valueOf(t.context.configSnapshot)));
    }
    List<String> path = new ArrayList<>(List.of("PRD_REVIEW", "PLANNING")); planned.stream().filter(e::stageOn).forEach(path::add); if (!path.contains("DEV")) path.add("DEV"); path.addAll(List.of("QA", "ACCEPTANCE", "RELEASE"));
    Map<String, String> roleOf = Map.of("PRD_REVIEW", "prd.draft", "PLANNING", "plan.make", "SPEC", "spec.write", "DESIGN", "design.make", "DEV", "dev.code");
    List<Map<String, Object>> steps = new ArrayList<>();
    for (String st : path) {
      var sc = e.stage(st); Map<String, Object> m = new LinkedHashMap<>(); m.put("stage", st);
      String base = roleOf.get(st);
      if (base != null && providers.llm()) {
        Map<String, String> lm = new HashMap<>(e.models); var bound = sc == null || sc.maker == null ? null : e.roles.get(sc.maker); if (bound != null && !bound.builtin && bound.profile != null) lm.putIfAbsent(base, bound.profile);
        String prof = router.profileFor(base, lm, false); m.put("role", sc != null && sc.maker != null ? sc.maker : base); m.put("profile", prof); m.put("chain", router.chain(prof));
      } else if (base != null) { m.put("role", sc != null && sc.maker != null ? sc.maker : base); m.put("profile", "rule"); m.put("chain", List.of("rule-agent")); }
      if (sc != null && sc.maker != null && e.skillText.containsKey(sc.maker)) m.put("skills", e.roles.get(sc.maker).skills);
      if (sc != null && sc.checker != null) m.put("checker", sc.checker);
      if (sc != null && sc.checks != null) m.put("checks", sc.checks);
      if (sc != null && sc.gate != null) m.put("gate", sc.gate);
      steps.add(m);
    }
    List<String> skipped = planned.stream().filter(x -> !e.stageOn(x)).toList();
    o.put("effective", e.id); o.put("template", e.template); o.put("path", path); o.put("skipped", skipped); o.put("steps", steps);
    o.put("baselineUsd", com.x7x24.util.U.round(usd, 4)); o.put("note", "配置预览：不调用模型或工具，不执行验收；所示费用仅为历史花费，非本次报价");
    return o;
  }

  @PutMapping("/lines/{lineId}") public Map<String, Object> putLine(@PathVariable String lineId, @RequestBody LineBinding b, HttpServletRequest r) {
    lines.require(lineId); com.x7x24.tenant.WorkspaceFilter.requireManager(r, "修改业务线流程"); String who = actor(r); var prev = svc.binding(lineId);
    var saved = svc.putBinding(lineId, b, who); var eff = svc.resolve(lineId);
    audit.sys("PIPELINE_LINE_UPDATED", who, Map.of("line", lineId, "from", prev, "to", saved, "snapshot", eff.id), null);
    return Map.of("binding", saved, "effective", eff);
  }

  @PostMapping("/templates") public Template submit(@RequestBody Template t, HttpServletRequest r) {
    String who = actor(r); if (t.owner != null) lines.require(t.owner);
    com.x7x24.tenant.WorkspaceFilter.requireMember(r);
    var saved = svc.submitTemplate(t, who, com.x7x24.tenant.WorkspaceFilter.manager(r));
    audit.sys("PIPELINE_TEMPLATE_SUBMITTED", who, Map.of("template", saved.key(), "owner", String.valueOf(saved.owner), "status", saved.status), null);
    return saved;
  }
  @PostMapping("/templates/{key}/approve") public Template approve(@PathVariable String key, @RequestBody(required = false) Map<String, String> b, HttpServletRequest r) { return review(key, true, b, r); }
  @PostMapping("/templates/{key}/reject") public Template reject(@PathVariable String key, @RequestBody(required = false) Map<String, String> b, HttpServletRequest r) { return review(key, false, b, r); }
  private Template review(String key, boolean ok, Map<String, String> b, HttpServletRequest r) {
    String who = actor(r); var t = svc.review(key, ok, b == null ? null : b.get("note"), who, com.x7x24.tenant.WorkspaceFilter.manager(r));
    audit.sys(ok ? "PIPELINE_TEMPLATE_APPROVED" : "PIPELINE_TEMPLATE_REJECTED", who, Map.of("template", key), null); return t;
  }
  @PostMapping("/skills") public Skill skill(@RequestBody Skill s, HttpServletRequest r) { String who = actor(r); var saved = svc.putSkill(s, who, com.x7x24.tenant.WorkspaceFilter.manager(r)); audit.sys("PIPELINE_SKILL_SAVED", who, Map.of("skill", saved.key()), null); return saved; }
}
