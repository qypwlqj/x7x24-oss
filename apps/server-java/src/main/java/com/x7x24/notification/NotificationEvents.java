package com.x7x24.notification;

import com.x7x24.db.Db;
import com.x7x24.engine.Task;
import com.x7x24.util.Clk;
import com.x7x24.util.U;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 只写数据库 outbox；随调用方事务提交，不在任务执行线程中发信。 */
@Component
public class NotificationEvents {
  private final Db db; private final Clk clk;
  public NotificationEvents(Db db, Clk clk) { this.db = db; this.clk = clk; }

  public void task(Task t, String kind, String key, String summary) {
    String title = switch (kind) {
      case "completed" -> "任务已完成"; case "published" -> "GitHub Release 已创建，正在观测 CI";
      case "anomaly" -> "任务出现异常"; case "action_required" -> "需要你处理";
      case "recovered" -> "任务已恢复"; case "discarded" -> "任务已废弃";
      default -> throw new IllegalArgumentException("未知通知类型");
    };
    StringBuilder body = new StringBuilder("任务：").append(t.title).append("\n项目：").append(t.projectId).append("\n").append(summary == null ? "" : summary);
    if (t.context.issue != null) link(body, "Issue", t.context.issue.url);
    if (t.context.pr != null) link(body, "PR", t.context.pr.url);
    if (t.context.rel != null) link(body, "Release", t.context.rel.url);
    if (t.context.ci != null) link(body, "CI", t.context.ci.url);
    body.append("\n迭代：").append(t.currentIteration).append("\n用量：").append(t.spentTokens).append(" Token · $").append(String.format(java.util.Locale.ROOT, "%.4f", t.spentUsd));
    put(key, t.workspaceId, t.projectId, t.id, kind, title + " · " + t.title, body.toString());
  }
  private static void link(StringBuilder b, String label, String url) { if (url != null && url.matches("(?s)^https?://[^\\r\\n]+$")) b.append("\n").append(label).append("：").append(url); }

  public void fromAudit(Map<String, Object> event) {
    String type = String.valueOf(event.get("event_type"));
    if (!java.util.Set.of("OWNER_NEEDED", "MERGE_APPROVAL_REQUESTED", "MERGE_APPROVAL_STALE").contains(type) || event.get("task_id") == null) return;
    var t = db.row("SELECT id,workspace_id,project_id,title FROM task WHERE id=?", event.get("task_id")); if (t == null) return;
    String detail = "OWNER_NEEDED".equals(type) ? "任务已暂停，等待负责人处理。" : "合并需要确认，请查看任务中的合并检查与审批。";
    if (event.get("payload") instanceof com.fasterxml.jackson.databind.JsonNode p && p.hasNonNull("detail")) detail += "\n" + U.ellipsis(p.path("detail").asText(), 600);
    put("audit:" + event.get("id"), Db.str(t.get("workspace_id")), Db.str(t.get("project_id")), Db.str(t.get("id")), "action_required", "需要你处理 · " + t.get("title"), detail);
  }
  private void put(String key, String ws, String project, String task, String kind, String subject, String body) {
    db.update("INSERT INTO notification_event(id,workspace_id,project_id,task_id,kind,subject,message,created_at) VALUES(?,?,?,?,?,?,?,?)", key, ws, project, task, kind, U.ellipsis(subject.replaceAll("[\\r\\n]", " "), 480), body, clk.iso());
  }
}
