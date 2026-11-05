package com.x7x24.execution.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.Redactor;
import com.x7x24.db.Db;
import com.x7x24.gov.AuditLog;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.Json;
import com.x7x24.util.U;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 指挥台 v2 M4：把 runner 日志里的精简运行明细写成任务事件（进时间线 / SSE）。
 * runner 已经脱敏、合并、限流（kind=tools 一批一条）；这里再脱敏一次、限长，并给 agent 的文字说明再限一次频。
 * 只有 AGENT_RUNTIME=remote-docker 时才会被调用（Wiring），开关关闭时不写任何事件。
 */
public class AgentTimeline implements AgentJobService.LogSink {
  static final int MAX_ITEMS = 50, ARGS_MAX = 200, OUT_MAX = 600, NOTE_MAX = 300;
  static final long NOTE_EVERY_MS = 20_000;
  private final Db db; private final AuditLog audit; private final LongSupplier now;
  private final Map<String, Long> lastNote = new ConcurrentHashMap<>(); private final Map<String, Integer> skipped = new ConcurrentHashMap<>();
  public AgentTimeline(Db db, AuditLog audit) { this(db, audit, System::currentTimeMillis); }
  AgentTimeline(Db db, AuditLog audit, LongSupplier now) { this.db = db; this.audit = audit; this.now = now; }

  @Override public void accept(String ws, String taskId, String jobId, AgentRuntime.LogEvent e) {
    Map<String, Object> p = map(jobId, e); if (p == null) return;
    String type = (String) p.remove("_type");
    Runnable w = () -> {
      var t = db.row("SELECT current_stage, current_iteration FROM task WHERE id=?", taskId); if (t == null) return;
      p.put("node", t.get("current_stage"));
      audit.task(taskId, t.get("current_iteration") instanceof Number n ? n.intValue() : null, type, "agent", p, "agent-ev:" + jobId + ":" + e.seq());
    };
    if (ws == null) w.run(); else Tenant.run(ws, w);
  }

  /** 日志 → 事件载荷；null 表示不进时间线（system / stderr / acceptance 只留在 agent_job_log） */
  Map<String, Object> map(String jobId, AgentRuntime.LogEvent e) {
    Map<String, Object> p = new LinkedHashMap<>(); p.put("jobId", jobId); p.put("seq", e.seq());
    switch (String.valueOf(e.kind())) {
      case "tools" -> {
        JsonNode j; try { j = Json.tree(e.text()); } catch (RuntimeException ex) { return null; }
        List<Map<String, Object>> items = new ArrayList<>(); boolean browser = false; int fail = 0;
        for (var it : j.path("items")) {
          if (items.size() >= MAX_ITEMS) break;
          Map<String, Object> m = new LinkedHashMap<>(); m.put("name", U.ellipsis(it.path("name").asText("?"), 80)); m.put("args", clean(it.path("args").asText(""), ARGS_MAX));
          m.put("ms", it.path("ms").asLong(0)); m.put("ok", it.path("ok").isNull() || it.path("ok").isMissingNode() ? null : it.path("ok").asBoolean());
          String out = clean(it.path("out").asText(""), OUT_MAX); if (!out.isEmpty()) m.put("out", out);
          if (it.path("truncated").asBoolean(false)) m.put("truncated", true); if (it.path("count").asInt(1) > 1) m.put("count", it.path("count").asInt());
          if (it.path("browser").asBoolean(false)) { m.put("browser", true); browser = true; }
          if (Boolean.FALSE.equals(m.get("ok"))) fail++;
          items.add(m);
        }
        if (items.isEmpty()) return null;
        p.put("items", items); p.put("failed", fail); if (j.path("dropped").asInt(0) > 0) p.put("dropped", j.path("dropped").asInt());
        p.put("_type", browser ? "AGENT_BROWSER_CHECK" : "AGENT_TOOLS");
      }
      case "tool" -> { // 旧版 runner：只有开始，没有耗时 / 结果
        String t = clean(e.text(), ARGS_MAX); int i = t.indexOf(": ");
        p.put("items", List.of(Map.of("name", i > 0 ? t.substring(0, i) : t, "args", i > 0 ? t.substring(i + 2) : "")));
        p.put("_type", "AGENT_TOOLS");
      }
      case "text" -> {
        long n = now.getAsLong(); Long last = lastNote.get(jobId);
        if (last != null && n - last < NOTE_EVERY_MS) { skipped.merge(jobId, 1, Integer::sum); return null; }
        lastNote.put(jobId, n); p.put("text", clean(e.text(), NOTE_MAX)); Integer s = skipped.remove(jobId); if (s != null) p.put("skipped", s);
        p.put("_type", "AGENT_NOTE");
      }
      case "result" -> { lastNote.remove(jobId); skipped.remove(jobId); p.put("text", clean(e.text(), NOTE_MAX)); p.put("_type", "AGENT_RESULT"); }
      default -> { return null; }
    }
    return p;
  }
  static String clean(String s, int max) { return U.ellipsis(Redactor.redact(s == null ? "" : s).strip(), max); }
}
