package com.x7x24.pipeline;

import com.x7x24.db.Db;
import com.x7x24.pipeline.Pipeline.*;
import com.x7x24.util.Clk;
import com.x7x24.util.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流程配置存取 + 解析器（Resolver）。
 * 存储：settings 表 skey='pipeline'（自定义模板 / 技能 / 业务线绑定 / 模型配置），内置目录在 {@link Catalog}。
 * 快照：四层合并后的生效配置按内容哈希存 config_snapshot，任务钉住 id（决策 4A）。
 */
@Component
public class PipelineService {
  public static class Invalid extends IllegalArgumentException { public final List<String> errors; public Invalid(List<String> e) { super(String.join("；", e)); errors = e; } }
  public static class Forbidden extends RuntimeException { public Forbidden(String m) { super(m); } }

  private final Db db; private final Clk clk; private final Set<String> admins = new HashSet<>();
  /** 平台文档：平台模板（owner=null）、平台默认模型配置。工作区文档：业务线绑定、私有模板、技能、工作区模型配置。 */
  private volatile Doc doc;
  private final Map<String, Doc> wsDocs = new ConcurrentHashMap<>();
  /** 模型配置变化回调（ModelRouter 清缓存） */
  public Runnable onModelsChanged = () -> {};
  private final Map<String, Effective> snapCache = new ConcurrentHashMap<>();

  public PipelineService(Db db, Clk clk, @Value("${x7x24.platform-admins:}") String admins) {
    this.db = db; this.clk = clk;
    for (String s : admins.split("[,\\s]+")) if (!s.isBlank()) this.admins.add(s.trim().toLowerCase(Locale.ROOT));
    doc = load("pipeline");
  }
  static String key(String ws) { return ws == null ? "pipeline" : "ws:" + ws + ":pipeline"; }
  private Doc load(String k) {
    var row = db.row("SELECT value FROM settings WHERE skey=?", k);
    Doc d = row == null ? new Doc() : Json.parse((String) row.get("value"), Doc.class);
    if (d.templates == null) d.templates = new LinkedHashMap<>(); if (d.skills == null) d.skills = new LinkedHashMap<>(); if (d.lines == null) d.lines = new LinkedHashMap<>();
    return d;
  }
  private void write(String k, Doc d) { String body = Json.str(d); if (db.update("UPDATE settings SET value=? WHERE skey=?", body, k) == 0) db.update("INSERT INTO settings(skey,value) VALUES(?,?)", k, body); }
  /** 当前上下文的文档：工作区 → 工作区文档；系统 / 平台 → 平台文档 */
  public Doc doc() { String ws = com.x7x24.tenant.Tenant.storeScope(); return ws == null ? doc : wsDocs.computeIfAbsent(ws, w -> load(key(w))); }
  public Doc platformDoc() { return doc; }
  /** 指定工作区自己的模型配置（ModelRouter.wsLoader 用） */
  public ModelConfig wsModels(String ws) { return wsDocs.computeIfAbsent(ws, w -> load(key(w))).models; }
  /** 平台管理员：X7_PLATFORM_ADMINS 列出的 GitHub 登录名；未配置时不授予任何人平台管理员权限 */
  public boolean isAdmin(String login) { return login != null && admins.contains(login.toLowerCase(Locale.ROOT)); }
  /** 测试用：替换管理员名单 */
  public void setAdminsForTest(Collection<String> a) { admins.clear(); a.forEach(x -> admins.add(x.toLowerCase(Locale.ROOT))); }
  public boolean adminsConfigured() { return !admins.isEmpty(); }

  // ── 目录视图
  /** 内置 + 平台模板（owner=null）+ 本工作区私有模板。平台文档同时是遗留工作区的文档，其私有模板 / 技能对其他工作区不可见。 */
  public Map<String, Template> templates() { Map<String, Template> m = new LinkedHashMap<>(Catalog.TEMPLATES); var d = doc(); if (d == doc) m.putAll(doc.templates); else { doc.templates.forEach((k, t) -> { if (t.owner == null) m.put(k, t); }); m.putAll(d.templates); } return m; }
  public Map<String, Skill> skills() { Map<String, Skill> m = new LinkedHashMap<>(Catalog.SKILLS); m.putAll(doc().skills); return m; }
  public LineBinding binding(String lineId) { return asLine(lineId, () -> { var b = doc().lines.get(lineId); return b == null ? new LineBinding() : b; }); }
  /** 业务线所属工作区（不受当前上下文过滤；由 Wiring 注入） */
  public java.util.function.Function<String, String> lineWorkspace = id -> null;
  /** 系统上下文（引擎 / 测试）里按业务线所属工作区解析：绑定、私有模板、技能都在该工作区文档里 */
  <T> T asLine(String lineId, java.util.function.Supplier<T> f) {
    if (com.x7x24.tenant.Tenant.scope() != null || lineId == null) return f.get();
    String ws = lineWorkspace.apply(lineId); return ws == null ? f.get() : com.x7x24.tenant.Tenant.run(ws, f);
  }

  /** 业务线可见的角色：内置 + 本业务线派生 */
  public Map<String, Role> rolesFor(LineBinding b) { Map<String, Role> m = new LinkedHashMap<>(Catalog.ROLES); if (b != null && b.roles != null) b.roles.forEach((k, r) -> { r.id = k; m.put(k, resolveRole(r)); }); return m; }
  private static Role resolveRole(Role r) {
    var base = Catalog.ROLES.get(r.extendsRole); if (base == null) return r;
    var o = Json.convert(r, Role.class); o.kind = base.kind; o.schema = base.schema; if (o.profile == null) o.profile = base.profile; o.builtin = false; return o;
  }

  // ── 解析
  public Effective resolve(String lineId) { return asLine(lineId, () -> resolve(lineId, binding(lineId))); }
  public Effective resolve(String lineId, LineBinding b) { return asLine(lineId, () -> resolve0(lineId, b)); }
  private Effective resolve0(String lineId, LineBinding b) {
    var t = templates().get(b.template);
    if (t == null) throw new Invalid(List.of("业务线绑定的模板不存在：" + b.template));
    if (!"approved".equals(t.status)) throw new Invalid(List.of("模板 " + b.template + " 尚未通过平台管理员审批"));
    if (t.owner != null && !t.owner.equals(lineId)) throw new Invalid(List.of("模板 " + b.template + " 是业务线 " + t.owner + " 的私有模板"));
    var roles = rolesFor(b);
    Effective e = new Effective(); e.lineId = lineId; e.template = t.key(); e.docVersion = doc.version;
    for (var s : t.stages) { e.stages.put(s.type, Json.convert(s, StageCfg.class)); e.sources.put("stages." + s.type, "template:" + t.key()); }
    // 默认角色槽位来自目录
    for (var en : Catalog.DEFAULT_SLOTS.entrySet()) { var s = e.stages.get(en.getKey()); if (s == null) continue; var sl = en.getValue();
      if (s.maker == null && sl.containsKey("maker")) s.maker = sl.get("maker"); if (s.checker == null && sl.containsKey("checker")) s.checker = sl.get("checker"); if (s.fixer == null && sl.containsKey("fixer")) s.fixer = sl.get("fixer"); }
    List<String> errs = new ArrayList<>();
    if (b.stageOverrides != null) for (var en : b.stageOverrides.entrySet()) {
      var base = e.stages.get(en.getKey()); var ov = en.getValue(); if (ov == null) continue; ov.type = en.getKey();
      if (base == null) { errs.add("业务线覆盖了模板里不存在的节点 " + en.getKey()); continue; }
      errs.addAll(Validator.stage(ov, roles, "业务线覆盖 " + en.getKey()));
      // 只能收紧：阈值不能低于模板、改动上限不能高于模板
      if (ov.gate != null && ov.gate.pass != null && base.gate != null && base.gate.pass != null && ov.gate.pass < base.gate.pass) errs.add("业务线覆盖 " + en.getKey() + "：gate.pass 只能收紧（模板 " + base.gate.pass + "）");
      if (ov.maxChangeLines != null && base.maxChangeLines != null && ov.maxChangeLines > base.maxChangeLines) errs.add("业务线覆盖 " + en.getKey() + "：maxChangeLines 只能收紧（模板 " + base.maxChangeLines + "）");
      if (ov.judgeSeats != null && base.judgeSeats != null && ov.judgeSeats < base.judgeSeats) errs.add("业务线覆盖 " + en.getKey() + "：评审席位只能增加");
      StageCfg merged; try { merged = Json.M.readerForUpdating(Json.convert(base, StageCfg.class)).readValue((com.fasterxml.jackson.databind.JsonNode) Json.M.valueToTree(ov)); } catch (Exception ex) { throw new Invalid(List.of("业务线覆盖 " + en.getKey() + " 无法合并：" + ex.getMessage())); } e.stages.put(en.getKey(), merged);
      e.sources.put("stages." + en.getKey(), "line:" + lineId);
    }
    if (!errs.isEmpty()) throw new Invalid(errs);
    e.heal = t.heal == null ? Catalog.defaultHeal() : new LinkedHashMap<>(t.heal); e.maxHeals = t.maxHeals;
    // 角色与技能（P3 注入提示词 + 节点级动态技能与提示词模板）
    var sk = skills(); sk.forEach((key, value) -> e.skillVersions.put(key, Json.convert(value, Skill.class)));
    for (var s : e.stages.values()) {
      for (String rid : new String[]{s.maker, s.checker, s.fixer}) {
        if (rid == null) continue; var r = roles.get(rid); if (r == null) continue; e.roles.put(rid, r);
        if (r.skills != null || r.instructions != null) {
          StringBuilder sb = new StringBuilder();
          if (r.skills != null) for (String k : r.skills) { var x = sk.get(k); if (x != null) sb.append("【技能 ").append(x.name == null ? x.id : x.name).append("】").append(x.instructions).append("\n"); }
          if (r.instructions != null && !r.instructions.isBlank()) sb.append("【业务线补充要求】").append(r.instructions).append("\n");
          e.skillText.put(rid, sb.toString().trim());
        }
      }
      Plans.bakeStageSkills(e, s, sk);
    }
    if (b.models != null) e.models.putAll(b.models);
    e.id = hash(e);
    return e;
  }
  public static String hash(Effective e) {
    try { var copy = Json.convert(e, Effective.class); copy.id = null; copy.docVersion = 0;
      byte[] d = MessageDigest.getInstance("SHA-256").digest(Json.str(copy).getBytes(StandardCharsets.UTF_8));
      return "S" + HexFormat.of().formatHex(d).substring(0, 16); } catch (Exception ex) { throw new IllegalStateException(ex); }
  }

  /** 解析并持久化业务线当前快照，返回 id（任务创建 / 重新规划时钉住） */
  public String pin(String lineId) {
    Effective e;
    try { e = resolve(lineId); } catch (Invalid ex) { e = resolve(lineId, new LineBinding()); } // 绑定失效时退回 default@1，不阻塞任务
    persist(e); return e.id;
  }
  /** 钉任务级流程计划：业务线生效配置 + 调整，持久化并返回 */
  public Plans.Result pinPlan(String lineId, List<Plans.Adj> adjs, String classification) { return pinPlan(lineId, adjs, classification, null); }
  public Plans.Result pinPlan(String lineId, List<Plans.Adj> adjs, String classification, List<String> labels) {
    var r = Plans.apply(current(lineId), adjs, classification, labels, rolesFor(safeBinding(lineId)), skills()); persist(r.eff()); return r;
  }
  public Plans.Result previewPlan(String lineId, List<Plans.Adj> adjs, String classification) { return previewPlan(lineId, adjs, classification, null); }
  public Plans.Result previewPlan(String lineId, List<Plans.Adj> adjs, String classification, List<String> labels) { return Plans.apply(current(lineId), adjs, classification, labels, rolesFor(safeBinding(lineId)), skills()); }
  /** 组装器（P2）：规则基线 + 治理必选 + 项目策略 + AI 提议 → v2 快照；persist=false 时只预览 */
  public Plans.Result assemble(String lineId, List<Plans.Adj> adjs, String classification, Assembler.Ctx ctx, boolean persist, boolean v2) { return assemble(lineId, adjs, classification, ctx, persist, v2, null); }
  /** preset 只在 v2 下生效（v1 任务仍以业务线绑定的模板为起点，D10） */
  public Plans.Result assemble(String lineId, List<Plans.Adj> adjs, String classification, Assembler.Ctx ctx, boolean persist, boolean v2, Presets.Preset preset) {
    var base = v2 && preset != null ? seed(lineId, preset.template, preset.disable) : current(lineId);
    var r = Assembler.assemble(base, adjs, classification, ctx, rolesFor(safeBinding(lineId)), skills(), null, v2 && preset != null && base.template.equals(preset.template) ? preset : null);
    // 工作区没开 pipeline.v2：钉成 v1 快照（不带流程图），任务按旧逻辑执行
    if (!v2) {
      // 用组装后生效的调整按 v1 规则重新生成，重新钉快照（Plans.apply 重放）时得到同一个快照；返回的计划视图仍是组装结果
      var p = r.plan(); var v1 = Plans.apply(current(lineId), p.applied(), classification, p.labels, rolesFor(safeBinding(lineId)), skills());
      if (persist) persist(v1.eff()); return new Plans.Result(v1.eff(), p);
    }
    if (persist) persist(r.eff()); return r;
  }
  /** 重新钉快照（重启 / 重新规划 / 升级）：任务带计划时在最新业务线配置上重放已生效的调整 */
  public String repin(String lineId, String currentSnapshot) { var e = latestFor(lineId, currentSnapshot); persist(e); return e.id; }
  public Effective latestFor(String lineId, String currentSnapshot) {
    Effective old = currentSnapshot == null ? null : snapshot(currentSnapshot, lineId);
    if (old == null || old.plan == null) return current(lineId);
    if (old.schema != null && old.schema >= 2)
      return Assembler.replay(old.plan.presetTemplate != null ? seed(lineId, old.plan.presetTemplate, old.plan.presetDisable) : current(lineId), old.plan, rolesFor(safeBinding(lineId)), skills()).eff();
    var r = Plans.apply(current(lineId), old.plan.applied(), old.plan.classification, old.plan.labels, rolesFor(safeBinding(lineId)), skills());
    return r.eff();
  }
  /** 以预设为起点解析：沿用业务线的角色 / 模型 / 参数覆盖，模板换成预设的种子模板，再关闭预设指定的可选步骤；解析失败退回业务线当前配置 */
  public Effective seed(String lineId, String template, List<String> disable) {
    if (template == null) return current(lineId);
    try {
      var t = templates().get(template); if (t == null) return current(lineId);
      var b = Json.convert(safeBinding(lineId), LineBinding.class); b.template = template;
      Set<String> types = new HashSet<>(); for (var s : t.stages) types.add(s.type);
      Map<String, StageCfg> ov = new LinkedHashMap<>(); if (b.stageOverrides != null) b.stageOverrides.forEach((k, v) -> { if (types.contains(k)) ov.put(k, v); });
      if (disable != null) for (String d : disable) if (types.contains(d)) { var s = ov.computeIfAbsent(d, k -> new StageCfg()); s.enabled = false; }
      b.stageOverrides = ov.isEmpty() ? null : ov;
      return resolve(lineId, b);
    } catch (Invalid ex) { return current(lineId); }
  }
  private Effective current(String lineId) { try { return resolve(lineId); } catch (Invalid ex) { return resolve(lineId, new LineBinding()); } }
  private LineBinding safeBinding(String lineId) { try { resolve(lineId); return binding(lineId); } catch (Invalid ex) { return new LineBinding(); } }
  private void persist(Effective e) {
    if (snapCache.containsKey(e.id)) return;
    if (db.row("SELECT id FROM config_snapshot WHERE id=?", e.id) == null) {
      try { db.update("INSERT INTO config_snapshot(id,line_id,body,created_at) VALUES(?,?,?,?)", e.id, e.lineId, Json.str(e), clk.iso()); } catch (Exception dup) { /* 并发插入同一哈希 */ }
    }
    snapCache.put(e.id, e);
  }
  /** 按 id 取快照；null / 不存在时返回业务线当前生效配置（旧任务） */
  public Effective snapshot(String id, String lineId) {
    if (id != null) {
      var c = snapCache.get(id); if (c != null) return c;
      var row = db.row("SELECT body FROM config_snapshot WHERE id=?", id);
      if (row != null) { var e = Json.parse((String) row.get("body"), Effective.class); snapCache.put(id, e); return e; }
    }
    try { return resolve(lineId); } catch (Invalid ex) { return resolve(lineId, new LineBinding()); }
  }

  // ── 写入
  private synchronized void save(java.util.function.Consumer<Doc> change) { save(com.x7x24.tenant.Tenant.storeScope(), change); }
  private synchronized void save(String ws, java.util.function.Consumer<Doc> change) {
    Doc cur = ws == null ? doc : wsDocs.computeIfAbsent(ws, w -> load(key(w)));
    Doc next = Json.convert(cur, Doc.class); change.accept(next); next.version = cur.version + 1;
    write(key(ws), next);
    if (ws == null) doc = next; else wsDocs.put(ws, next);
  }

  public LineBinding putBinding(String lineId, LineBinding b, String actor) { return asLine(lineId, () -> putBinding0(lineId, b, actor)); }
  private LineBinding putBinding0(String lineId, LineBinding b, String actor) {
    if (b.template == null) b.template = "default@1";
    var sk = skills(); List<String> errs = new ArrayList<>();
    if (b.roles != null) b.roles.forEach((k, r) -> { r.id = k; errs.addAll(Validator.role(r, sk)); });
    if (!errs.isEmpty()) throw new Invalid(errs);
    resolve(lineId, b); // 校验：模板存在且已审批、覆盖合法
    var prev = doc().lines.get(lineId); b.version = prev == null ? 1 : prev.version + 1; b.updatedBy = actor; b.at = clk.iso();
    save(d -> d.lines.put(lineId, b));
    return b;
  }

  /** 提交模板：平台模板只有管理员能提交；业务线私有模板非管理员提交时进入待审批（决策 3B） */
  public Template submitTemplate(Template t, String actor) { return submitTemplate(t, actor, isAdmin(actor)); }
  /** wsAdmin：当前工作区 owner/admin（决策 3A：私有模板由工作区管理员审批）；平台模板仍需平台管理员 */
  public Template submitTemplate(Template t, String actor, boolean wsAdmin) { return asLine(t.owner, () -> submitTemplate0(t, actor, wsAdmin)); }
  private Template submitTemplate0(Template t, String actor, boolean wsAdmin) {
    if (t.id == null) throw new Invalid(List.of("缺少模板 id"));
    if (Catalog.TEMPLATES.keySet().stream().anyMatch(k -> k.startsWith(t.id + "@"))) throw new Invalid(List.of("不能覆盖内置模板 " + t.id + "，请换一个 id（可复制后修改）"));
    boolean platform = t.owner == null, admin = platform ? isAdmin(actor) : wsAdmin;
    if (platform && !admin) throw new Forbidden("只有平台管理员可以提交平台模板");
    if (!platform && !doc().lines.containsKey(t.owner) && !lineInWorkspace.test(t.owner)) throw new Invalid(List.of("业务线不存在：" + t.owner));
    var errs = Validator.template(t, t.owner == null ? Catalog.ROLES : rolesFor(binding(t.owner))); if (!errs.isEmpty()) throw new Invalid(errs);
    int v = templates().values().stream().filter(x -> x.id.equals(t.id)).mapToInt(x -> x.version).max().orElse(0) + 1;
    var existing = templates().values().stream().filter(x -> x.id.equals(t.id)).findFirst().orElse(null);
    if (existing != null && !Objects.equals(existing.owner, t.owner)) throw new Invalid(List.of("模板 id " + t.id + " 已被其他业务线使用"));
    t.version = v; t.builtin = false; t.submittedBy = actor; t.at = clk.iso();
    t.status = t.owner == null || admin ? "approved" : "pending"; if ("approved".equals(t.status)) t.reviewedBy = actor;
    save(platform ? null : com.x7x24.tenant.Tenant.storeScope(), d -> d.templates.put(t.key(), t));
    return t;
  }
  /** 业务线是否属于当前工作区（LineRepo 注入） */
  public java.util.function.Predicate<String> lineInWorkspace = id -> true;
  public Template review(String key, boolean approve, String note, String actor) { return review(key, approve, note, actor, isAdmin(actor)); }
  public Template review(String key, boolean approve, String note, String actor, boolean wsAdmin) {
    var t = doc().templates.get(key); if (t == null) { var pt = doc.templates.get(key); if (pt != null && pt.owner == null) t = pt; }
    boolean platform = t == null || t.owner == null;
    if (platform ? !isAdmin(actor) : !wsAdmin) throw new Forbidden(platform ? "只有平台管理员可以审批平台模板" : "只有工作区管理员可以审批私有模板");
    if (t == null) throw new Invalid(List.of("模板不存在：" + key));
    if (!"pending".equals(t.status)) throw new Invalid(List.of("模板 " + key + " 不在待审批状态"));
    var n = Json.convert(t, Template.class); n.status = approve ? "approved" : "rejected"; n.reviewedBy = actor; n.reviewNote = note;
    save(platform ? null : com.x7x24.tenant.Tenant.storeScope(), d -> d.templates.put(key, n)); return n;
  }
  public Skill putSkill(Skill s, String actor) { return putSkill(s, actor, isAdmin(actor)); }
  /** 技能库归工作区；工作区 owner/admin 维护 */
  public Skill putSkill(Skill s, String actor, boolean wsAdmin) {
    if (!wsAdmin) throw new Forbidden("只有工作区管理员可以维护技能库");
    if (s.id == null || !s.id.matches("[a-z0-9][a-z0-9-]{1,40}") || s.instructions == null || s.instructions.isBlank()) throw new Invalid(List.of("技能需要 id（小写字母数字-）和 instructions"));
    if (s.instructions.length() > 4000) throw new Invalid(List.of("技能指令不能超过 4000 字"));
    s.version = skills().values().stream().filter(x -> x.id.equals(s.id)).mapToInt(x -> x.version).max().orElse(0) + 1; s.builtin = false;
    save(d -> d.skills.put(s.key(), s)); return s;
  }
  /** 当前工作区的模型配置（系统 / 平台上下文 = 平台默认配置） */
  public void putModels(ModelConfig m) { save(d -> d.models = m); onModelsChanged.run(); }
  public void putPlatformModels(ModelConfig m) { save(null, d -> d.models = m); onModelsChanged.run(); }
}
