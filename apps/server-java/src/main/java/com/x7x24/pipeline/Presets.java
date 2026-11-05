package com.x7x24.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.db.Db;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.Clk;
import com.x7x24.util.Json;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 流程预设（可组合流程 P5，决策 D5）：模板降级为预设，只是组装的起点（种子），不再绑定到业务线。
 * - 内置模板（default / web-fullstack / backend-service / docs-only）与工作区已生效的业务线模板自动以预设形式出现（读取时转换，原模板数据保留不删，可随时回退）；
 * - 管理员可以基于某个预设另存工作区预设（只能关闭可选步骤），存 pipeline_preset；
 * - 项目在「流程设置」里选默认预设（nodePolicy.defaultPreset），手动建任务时也可以临时选一个；不选时沿用业务线原来绑定的模板。
 */
@Component
public class Presets {
  private final Db db; private final Clk clk; private final PipelineService pipes;
  public Presets(Db db, Clk clk, PipelineService pipes) { this.db = db; this.clk = clk; this.pipes = pipes; }

  public static class Preset {
    public String id; public int version = 1; public String name, description;
    /** 种子模板（template key） */
    public String template;
    /** 在种子模板上额外关闭的可选步骤 */
    public List<String> disable = new ArrayList<>();
    /** builtin | template（由业务线模板转换） | workspace */
    public String source; public String owner; public String createdBy, createdAt;
    /** 展示用：起点包含的流程步骤（按顺序） */
    public List<String> nodes = new ArrayList<>();
    public String key() { return id + "@" + version; }
  }
  /** 计划里记下的组装起点（重放 / 中途修订用同一起点） */
  public static Preset fromPlan(Plans.Plan p) {
    if (p == null || p.presetTemplate == null || p.preset == null) return null;
    var o = new Preset(); int at = p.preset.lastIndexOf('@');
    o.id = at < 0 ? p.preset : p.preset.substring(0, at);
    try { o.version = at < 0 ? 1 : Integer.parseInt(p.preset.substring(at + 1)); } catch (NumberFormatException e) { o.version = 1; }
    o.template = p.presetTemplate; o.disable = p.presetDisable == null ? new ArrayList<>() : new ArrayList<>(p.presetDisable); return o;
  }
  static final Map<String, String> NAME = Map.of("default", "标准", "web-fullstack", "Web 全栈", "backend-service", "后端服务", "docs-only", "纯文档");

  /** 本工作区可用的预设；lineId 非空时只返回这条业务线能用的（排除别的业务线的私有模板） */
  public List<Preset> list(String lineId) {
    List<Preset> out = new ArrayList<>();
    for (var t : pipes.templates().values()) {
      if (!"approved".equals(t.status)) continue;
      if (lineId != null && t.owner != null && !t.owner.equals(lineId)) continue;
      var p = new Preset(); p.id = t.id; p.version = t.version; p.template = t.key(); p.owner = t.owner;
      p.name = t.builtin ? NAME.getOrDefault(t.id, t.name) : t.name == null ? t.id : t.name; p.description = t.description;
      p.source = t.builtin ? "builtin" : "template"; p.nodes = nodes(t, List.of()); out.add(p);
    }
    try {
      for (var r : db.rows("SELECT * FROM pipeline_preset WHERE workspace_id=? AND status='approved' ORDER BY id, version", Tenant.forInsert())) {
        var p = Json.parse(Db.str(r.get("body")), Preset.class); p.id = Db.str(r.get("id")); p.version = ((Number) r.get("version")).intValue(); p.source = "workspace";
        p.createdBy = Db.str(r.get("created_by")); p.createdAt = Db.str(r.get("created_at"));
        var t = pipes.templates().get(p.template); if (t == null) continue;
        if (lineId != null && t.owner != null && !t.owner.equals(lineId)) continue;
        p.nodes = nodes(t, p.disable); out.add(p);
      }
    } catch (RuntimeException e) { /* 表未就绪 */ }
    // 同 id 只保留最新版本
    Map<String, Preset> latest = new LinkedHashMap<>(); for (var p : out) { var o = latest.get(p.id); if (o == null || o.version <= p.version) latest.put(p.id, p); }
    return new ArrayList<>(latest.values());
  }
  /** 按 key（id@version）或 id（取最新）找预设 */
  public Preset get(String key, String lineId) {
    if (key == null || key.isBlank()) return null;
    var all = list(lineId);
    for (var p : all) if (p.key().equals(key)) return p;
    for (var p : all) if (p.id.equals(key)) return p;
    return null;
  }
  static List<String> nodes(Pipeline.Template t, List<String> disable) {
    List<String> n = new ArrayList<>(); for (var s : t.stages) if (!Boolean.FALSE.equals(s.enabled) && !disable.contains(s.type)) n.add(s.type); return n;
  }
  public Preset create(JsonNode b, String actor) {
    String id = b.path("id").asText("").trim().toLowerCase(Locale.ROOT); List<String> errs = new ArrayList<>();
    if (!id.matches("[a-z][a-z0-9-]{2,40}")) errs.add("预设标识需为小写字母开头的字母、数字或连字符（3~41 位）");
    if (pipes.templates().values().stream().anyMatch(t -> t.id.equals(id))) errs.add("预设标识与已有模板重名");
    String name = b.path("name").asText("").trim(); if (name.isEmpty() || name.length() > 40) errs.add("名称必填，最多 40 字");
    var base = get(b.path("base").asText(""), null); if (base == null) errs.add("找不到起点预设 " + b.path("base").asText(""));
    List<String> disable = new ArrayList<>(base == null ? List.of() : base.disable);
    for (var s : b.path("disable")) { String ty = s.asText(); var st = Catalog.stageType(ty); if (st == null || !st.optional()) errs.add("只能关闭可选步骤：" + ty); else if (!disable.contains(ty)) disable.add(ty); }
    if (!errs.isEmpty()) throw new IllegalArgumentException(String.join("；", errs));
    var p = new Preset(); p.id = id; p.name = name; p.description = b.path("description").asText("").trim(); p.template = base.template; p.disable = disable;
    Number max = (Number) db.row("SELECT COALESCE(MAX(version),0) AS v FROM pipeline_preset WHERE workspace_id=? AND id=?", Tenant.forInsert(), id).get("v");
    p.version = (max == null ? 0 : max.intValue()) + 1;
    Map<String, Object> body = new LinkedHashMap<>(); body.put("name", p.name); body.put("description", p.description); body.put("template", p.template); body.put("disable", p.disable);
    db.update("INSERT INTO pipeline_preset(workspace_id,id,version,body,status,created_by,created_at) VALUES(?,?,?,?,?,?,?)", Tenant.forInsert(), id, p.version, Json.str(body), "approved", actor, clk.iso());
    return get(p.key(), null);
  }
}
