package com.x7x24.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.db.Db;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.Clk;
import com.x7x24.util.Json;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工作区派生节点（决策 D6）：只能基于内置执行方式（智能体产出 / 评审）派生，填显示名、职责说明（提示词）、技能与锚定阶段；
 * 管理员提交为草稿，owner 批准后才能被组装使用。不允许自定义执行命令。
 */
@Component
public class WorkspaceNodes {
  private final Db db; private final Clk clk; private final PipelineService pipes;
  public WorkspaceNodes(Db db, Clk clk, PipelineService pipes) { this.db = db; this.clk = clk; this.pipes = pipes; }
  public record Row(String id, int version, Nodes.NodeDef def, String status, String createdBy, String reviewedBy, String createdAt) {}

  static String ws() { return Tenant.forInsert(); }
  public List<Row> list() {
    List<Row> out = new ArrayList<>();
    for (var r : db.rows("SELECT * FROM pipeline_node WHERE workspace_id=? ORDER BY id, version", ws()))
      out.add(new Row((String) r.get("id"), ((Number) r.get("version")).intValue(), Json.parse((String) r.get("body"), Nodes.NodeDef.class), (String) r.get("status"), (String) r.get("created_by"), (String) r.get("reviewed_by"), (String) r.get("created_at")));
    return out;
  }
  /** 已批准的派生节点：每个 id 取最新批准的版本 */
  public Map<String, Nodes.NodeDef> approved() {
    Map<String, Nodes.NodeDef> m = new LinkedHashMap<>();
    try { for (var r : list()) if ("approved".equals(r.status())) m.put(r.id(), r.def()); } catch (RuntimeException e) { /* 表未就绪（迁移前）时视为没有 */ }
    return m;
  }
  public Row create(JsonNode b, String actor) {
    String id = b.path("id").asText("").trim().toUpperCase(Locale.ROOT);
    List<String> errs = new ArrayList<>();
    if (!id.matches("[A-Z][A-Z0-9_]{2,40}")) errs.add("节点标识需为大写字母开头的字母、数字或下划线（3~41 位）");
    if (Nodes.def(id) != null || Catalog.stageType(id) != null || Catalog.presetStage(id) != null) errs.add("节点标识与内置节点重名");
    String exec = b.path("executor").asText("review");
    if (!Set.of("agent", "review").contains(exec)) errs.add("派生节点只能基于内置执行方式：agent（产出设计类产物）或 review（评审）");
    String label = b.path("label").asText("").trim(), desc = b.path("description").asText("").trim();
    if (label.isEmpty() || label.length() > 40) errs.add("显示名称必填，最多 40 字");
    if (desc.isEmpty() || desc.length() > 2000) errs.add("职责说明必填，最多 2000 字");
    List<String> skills = new ArrayList<>(); var known = pipes.skills().keySet();
    for (var s : b.path("skills")) { if (!known.contains(s.asText())) errs.add("未知技能 " + s.asText() + "（可用：" + String.join(", ", known) + "）"); else skills.add(s.asText()); }
    List<String> stacks = new ArrayList<>(); for (var s : b.path("stacks")) { if (!StackProfiles.STACKS.contains(s.asText())) errs.add("未知技术栈 " + s.asText()); else stacks.add(s.asText()); }
    if (!errs.isEmpty()) throw new IllegalArgumentException(String.join("；", errs));
    var d = new Nodes.NodeDef(); d.id = id; d.label = label; d.description = desc; d.executor = exec; d.stage = id;
    boolean review = "review".equals(exec);
    d.module = review ? "delivery" : "production"; d.family = review ? "delivery" : "design"; d.anchorPhase = review ? "ACCEPTANCE" : "DESIGN";
    d.requires = review ? List.of("QA") : List.of("PLANNING"); d.before = review ? List.of("ACCEPTANCE") : List.of("DEV");
    d.outputs = List.of(id.toLowerCase(Locale.ROOT)); d.skills = skills; d.stacks = stacks.isEmpty() ? List.of("*") : stacks; d.cost = new Nodes.Cost(2, 0.02, 2);
    Number max = (Number) db.row("SELECT COALESCE(MAX(version),0) AS v FROM pipeline_node WHERE workspace_id=? AND id=?", ws(), id).get("v");
    d.version = (max == null ? 0 : max.intValue()) + 1;
    db.update("INSERT INTO pipeline_node(workspace_id,id,version,body,status,created_by,created_at) VALUES(?,?,?,?,?,?,?)", ws(), id, d.version, Json.str(d), "draft", actor, clk.iso());
    return list().stream().filter(r -> r.id().equals(id) && r.version() == d.version).findFirst().orElseThrow();
  }
  public Row review(String id, int version, boolean approve, String actor) {
    int n = db.update("UPDATE pipeline_node SET status=?, reviewed_by=? WHERE workspace_id=? AND id=? AND version=? AND status='draft'", approve ? "approved" : "rejected", actor, ws(), id, version);
    if (n == 0) throw new IllegalArgumentException("没有待审批的节点 " + id + "@" + version);
    return list().stream().filter(r -> r.id().equals(id) && r.version() == version).findFirst().orElseThrow();
  }
}
