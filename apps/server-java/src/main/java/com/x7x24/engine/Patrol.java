package com.x7x24.engine;

import com.x7x24.db.Db;
import com.x7x24.gov.*;
import com.x7x24.util.*;

import java.util.*;

/** 巡查 / 自愈：只向工作流发信号，不直接改任务状态；也负责自动 kill switch、审计锚点与自治等级调整。 */
public class Patrol {
  private static final Map<String, Integer> TIMEOUTS_H = Map.of("SPEC", 4, "DESIGN", 4, "DEV", 8, "QA", 2);
  private static final List<String> LEVELS = List.of("L1", "L2", "L3");
  private final Db db; private final Clk clk; private final ConfigStore cfg; private final AuditLog audit; private final KillSwitch kill; private final LineRepo lines; private final Engine engine;
  private int runs = 0;

  Patrol(Db db, Clk clk, ConfigStore cfg, AuditLog audit, KillSwitch kill, LineRepo lines, Engine engine) {
    this.db = db; this.clk = clk; this.cfg = cfg; this.audit = audit; this.kill = kill; this.lines = lines; this.engine = engine;
  }

  public synchronized void run() {
    runs++; timeouts(); autoKillSwitches(); autonomy(); anchor();
    if (runs % 48 == 0) verifyChain();
  }

  private void timeouts() {
    for (var phase : TIMEOUTS_H.keySet())
      for (var r : db.rows("SELECT id,version,workspace_id FROM task WHERE status=? AND paused=0 AND pipeline_version IN ('v1','v2') AND next_run_at>?", phase, clk.iso()))
        com.x7x24.tenant.Tenant.run((String) r.get("workspace_id"), () -> engine.sendTimeoutSignal(Db.str(r.get("id")), Db.i(r.get("version"))));
  }

  /** Old tasks have no progress field. Derive it from successful events within the current phase. */
  String progressAt(Task t) {
    String at = t.statusChangedAt;
    if (t.context.lastProgressAt != null)
      return Clk.parse(t.context.lastProgressAt) > Clk.parse(at) ? t.context.lastProgressAt : at;
    for (var e : db.rows("SELECT event_type,payload,occurred_at FROM task_event WHERE task_id=? AND occurred_at>? AND event_type IN ('TASK_RESUMED','SPLIT_PART_COMMITTED') ORDER BY occurred_at DESC,id DESC", t.id, at)) {
      var p = Json.tree(Db.str(e.get("payload")));
      boolean resumed = "TASK_RESUMED".equals(e.get("event_type")) && Objects.equals(t.currentStage, p.path("node").asText(null));
      boolean committed = "SPLIT_PART_COMMITTED".equals(e.get("event_type")) && "DEV".equals(t.status) && p.path("part").asInt(Integer.MAX_VALUE) <= t.context.subtaskIdx;
      if (resumed || committed) return Db.str(e.get("occurred_at"));
    }
    return at;
  }

  private boolean overdue(Task t, String progress) {
    Integer hours = TIMEOUTS_H.get(t.status);
    return hours != null && Set.of("v1", "v2").contains(t.pipelineVersion) && t.paused == 0
        && !RecoveryService.stopped(t) && clk.now() - Clk.parse(progress) > hours * U.HOUR;
  }

  TaskCtx.Sig timeoutSignal(Task t) {
    if (!Set.of("v1", "v2").contains(t.pipelineVersion) || t.nextRunAt == null || Clk.parse(t.nextRunAt) <= clk.now()
        || t.context.signals.stream().anyMatch(s -> "ANOMALY".equals(s.type))) return null;
    String progress = progressAt(t);
    if (!overdue(t, progress) || engine.projects.runtime(t.projectId).paused() != 0
        || kill.isBlocked(t.productLine, t.projectId, "write", null) != null) return null;
    var signal = new TaskCtx.Sig("ANOMALY", "timeout", t.status + " 连续 " + TIMEOUTS_H.get(t.status) + "h 没有有效进展（最近进展：" + progress + "）");
    signal.observedStage = t.currentStage; signal.observedNode = t.currentNode;
    signal.observedProgressAt = progress; signal.observedRecoveryGeneration = t.context.recoveryGeneration;
    return signal;
  }

  boolean currentTimeout(Task t, TaskCtx.Sig signal) {
    if (signal.observedStage != null && !Objects.equals(signal.observedStage, t.currentStage)) return false;
    if (signal.observedNode != null && !Objects.equals(signal.observedNode, t.currentNode)) return false;
    if (signal.observedRecoveryGeneration != null && signal.observedRecoveryGeneration != t.context.recoveryGeneration) return false;
    String progress = progressAt(t);
    return (signal.observedProgressAt == null || signal.observedProgressAt.equals(progress)) && overdue(t, progress);
  }

  public void autoKillSwitches() {
    String since = Clk.iso(clk.now() - U.DAY);
    // 回滚过多 → 冻结发布：按工作区分别统计与冻结（不影响其他工作区）
    Set<String> wss = new LinkedHashSet<>();
    for (var r : db.rows("SELECT DISTINCT workspace_id FROM task_event WHERE event_type='ROLLBACK' AND occurred_at>?", since)) wss.add((String) r.get("workspace_id"));
    for (var r : db.rows("SELECT DISTINCT workspace_id FROM kill_switch WHERE active=1 AND trigger_key='rollbacks'")) wss.add((String) r.get("workspace_id"));
    String only = com.x7x24.tenant.Tenant.readFilter(); if (only != null) wss.retainAll(Set.of(only));
    for (String ws : wss) com.x7x24.tenant.Tenant.run(ws, () -> {
      long rb = db.count("SELECT COUNT(*) FROM task_event WHERE event_type='ROLLBACK' AND occurred_at>? AND workspace_id=?", since, ws);
      int th = cfg.get().autonomy.globalRollbackKill;
      var act = kill.active().stream().filter(k -> "rollbacks".equals(k.get("trigger_key")) && ws.equals(k.get("workspace_id"))).findFirst().orElse(null);
      if (rb >= th) kill.activate("global", "FREEZE_RELEASE", "auto", "24h 内回滚 " + rb + " 次 ≥ " + th, "rollbacks");
      else if (act != null) kill.release((String) act.get("id"), "system");
    });
    var chain = kill.active().stream().filter(k -> "audit:chain".equals(k.get("trigger_key"))).findFirst().orElse(null);
    if (chain != null && Boolean.TRUE.equals(audit.verify().get("ok"))) com.x7x24.tenant.Tenant.run(com.x7x24.tenant.Tenant.PLATFORM, () -> kill.release((String) chain.get("id"), "system"));
  }

  public Map<String, Object> verifyChain() {
    var v = audit.verify();
    if (!Boolean.TRUE.equals(v.get("ok"))) com.x7x24.tenant.Tenant.run(com.x7x24.tenant.Tenant.PLATFORM, () -> kill.activate("global", "HALT", "auto", "审计哈希链校验失败 @" + v.get("brokenAt") + ": " + v.get("reason"), "audit:chain"));
    return v;
  }

  private void anchor() {
    var last = db.row("SELECT anchored_at FROM audit_anchor ORDER BY id DESC LIMIT 1");
    if (last != null && clk.now() - Clk.parse((String) last.get("anchored_at")) < U.HOUR) return;
    var h = audit.head();
    if (h != null && h.get("id") != null) db.update("INSERT INTO audit_anchor (head_hash,event_id,anchored_at) VALUES (?,?,?)", h.get("hash"), h.get("id"), clk.iso());
  }

  /** 只自动收紧；放宽需连续 N 天达标 */
  public void autonomy() {
    var a = cfg.get().autonomy; long now = clk.now(); String since24 = Clk.iso(now - U.DAY);
    for (var l : lines.list()) {
      long discards = db.count("SELECT COUNT(*) FROM task_event e JOIN task t ON t.id=e.task_id WHERE t.product_line=? AND e.edge='auto_discard' AND e.occurred_at>?", l.id(), since24);
      long rbs = db.count("SELECT COUNT(*) FROM task_event e JOIN task t ON t.id=e.task_id WHERE t.product_line=? AND e.event_type='ROLLBACK' AND e.occurred_at>?", l.id(), since24);
      long lastChange = Clk.parse(l.lastTightenedAt() != null ? l.lastTightenedAt() : l.createdAt());
      int idx = LEVELS.indexOf(l.autonomyLevel());
      if ((discards > a.tightenDiscards || rbs > a.tightenRollbacks) && (l.lastTightenedAt() == null || now - lastChange > U.DAY)) {
        String to = LEVELS.get(Math.max(0, idx - 1));
        db.update("UPDATE product_line SET autonomy_level=?, last_tightened_at=?, intake_paused_until=? WHERE id=?", to, clk.iso(), Clk.iso(now + (long) (a.pauseIntakeHours * U.HOUR)), l.id());
        audit.sys("AUTONOMY_CHANGED", "patrol", Map.of("line", l.id(), "from", l.autonomyLevel(), "to", to, "direction", "tighten", "discards", discards, "rollbacks", rbs), "autonomy:" + l.id() + ":" + clk.iso());
        continue;
      }
      if (idx < LEVELS.size() - 1 && now - lastChange >= a.loosenAfterDays * U.DAY) {
        String sinceI = Clk.iso(lastChange);
        long rel = db.count("SELECT COUNT(*) FROM task_event e JOIN task t ON t.id=e.task_id WHERE t.product_line=? AND e.edge='release' AND e.occurred_at>?", l.id(), sinceI);
        long rb = db.count("SELECT COUNT(*) FROM task_event e JOIN task t ON t.id=e.task_id WHERE t.product_line=? AND e.event_type='ROLLBACK' AND e.occurred_at>?", l.id(), sinceI);
        if (rel >= a.loosenMinReleased && rb == 0) {
          String to = LEVELS.get(idx + 1);
          db.update("UPDATE product_line SET autonomy_level=?, last_tightened_at=? WHERE id=?", to, clk.iso(), l.id());
          audit.sys("AUTONOMY_CHANGED", "patrol", Map.of("line", l.id(), "from", l.autonomyLevel(), "to", to, "direction", "loosen", "released", rel), "autonomy:" + l.id() + ":" + clk.iso());
        }
      }
    }
  }
}
