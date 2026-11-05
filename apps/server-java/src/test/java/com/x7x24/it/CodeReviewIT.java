package com.x7x24.it;

import com.x7x24.adapters.DeliveryTools.FilePatch;
import com.x7x24.core.Status;
import com.x7x24.engine.Engine;
import com.x7x24.pipeline.PipelineService;
import com.x7x24.pipeline.Plans;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 逐行审查节点（open-code-review；测试里用离线规则版 RuleOcrRunner，格式与 ocr v1.13.5 相同）：测试之后、安全审计之前执行；观察期只记录；打开阻断后 critical 像测试失败一样回编码。 */
class CodeReviewIT extends Harness {
  @Autowired PipelineService pipes; @Autowired com.x7x24.chat.PlanService plans; @Autowired com.x7x24.chat.TaskActions actions; @Autowired com.x7x24.engine.ProjectRepo projects;
  @BeforeEach void quiet() { cfg.update(Json.tree("{\"autonomy\":{\"tightenDiscards\":10000,\"tightenRollbacks\":10000,\"globalRollbackKill\":10000}}")); }
  static final Set<Status> END = Set.of(Status.DELIVERED, Status.RELEASED, Status.DISCARDED, Status.DEGRADED);

  String snapshot() {
    var a = new Plans.Adj(); a.op = "insertStage"; a.stage = "SEC_AUDIT"; a.after = "QA"; a.label = "安全审计"; a.source = "governance";
    var ctx = new com.x7x24.pipeline.Assembler.Ctx(List.of("backend"), List.of(), null, new LinkedHashMap<>(Map.of("CODE_REVIEW", "测试：G1 逐行审查", "SEC_AUDIT", "测试：安全审计")), "mid", "L2");
    return Tenant.run(Tenant.LEGACY, () -> pipes.assemble("sandbox", List.of(a), "backend", ctx, true, true).eff().id);
  }
  String ingest(String name, String snap) {
    String body = "请新增 " + name + " 工具函数（src/" + name + ".js），并补充单元测试与文档。示例：" + name + "(\"a\", 3) => \"aaa\"";
    var r = engine.ingest(new Engine.Ingest("sandbox", "新增 " + name + " 工具函数及测试", body, "chat", "chat:cr-" + System.nanoTime(), null, false, null, "sandbox-default", "示例：" + body, snap));
    db.update("UPDATE task SET priority_score=0.9 WHERE id=?", r.taskId()); return r.taskId();
  }
  static final List<FilePatch> SECRET = List.of(new FilePatch("src/keys.js", "added", 2, 0, "@@ -0,0 +1,2 @@\n+export const KEY = \"sk-abcdefghijklmnopqrstuvwxyz0123\";\n+export const n = 1;"));

  @Test void assemblyPutsCodeReviewBeforeSecurityAudit() {
    var ef = Tenant.run(Tenant.LEGACY, () -> pipes.snapshot(snapshot(), null));
    var keys = new ArrayList<>(ef.stages.keySet());
    assertTrue(keys.indexOf("QA") < keys.indexOf("CODE_REVIEW") && keys.indexOf("CODE_REVIEW") < keys.indexOf("SEC_AUDIT") && keys.indexOf("SEC_AUDIT") < keys.indexOf("ACCEPTANCE"), keys.toString());
    assertEquals(List.of("ocr-review@1"), ef.stage("CODE_REVIEW").skills);
    assertTrue(ef.skillText.getOrDefault("stage:CODE_REVIEW", "").contains("open-code-review"));
  }
  @Test void plannerAddsCodeReviewOnlyFromG1() {
    var small = new com.x7x24.chat.Draft(); small.size = "S"; small.risk = "low"; small.title = "改按钮颜色";
    var big = new com.x7x24.chat.Draft(); big.size = "M"; big.risk = "low"; big.title = "订单导出";
    assertFalse(plans.mandatoryFor(small, List.of("frontend")).containsKey("CODE_REVIEW"), "G0 不加");
    assertTrue(plans.mandatoryFor(big, List.of("frontend")).containsKey("CODE_REVIEW"));
  }

  @Test void observeOnlyRecordsFindingsAndPasses() {
    cfg.update(Json.tree("{\"codeReview\":{\"blocking\":false}}"));
    tools.patches = SECRET;
    String id = ingest("padR", snapshot());
    var seen = new LinkedHashSet<String>();
    var t = run(id, x -> { if (x.currentNode != null) seen.add(x.currentNode); return END.contains(x.st()); }, 250);
    assertTrue(Set.of(Status.DELIVERED, Status.RELEASED).contains(t.st()), t.status + " " + t.context.failures);
    var nodes = new ArrayList<>(seen);
    assertTrue(nodes.indexOf("QA") < nodes.indexOf("CODE_REVIEW") && nodes.indexOf("CODE_REVIEW") < nodes.indexOf("SEC_AUDIT"), nodes.toString());
    var ev = evidence.latest(id, "code_review");
    assertNotNull(ev); assertTrue(ev.path("observeOnly").asBoolean()); assertTrue(ev.path("wouldBlock").asBoolean()); assertEquals("pass", ev.path("decision").asText());
    assertEquals("critical", ev.path("findings").get(0).path("severity").asText()); assertEquals("src/keys.js", ev.path("findings").get(0).path("path").asText());
    assertEquals("pass", db.rows("SELECT decision FROM verdict WHERE task_id=? AND gate='code_review'", id).get(0).get("decision"));
    assertEquals(1, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='CODE_REVIEW_DONE'", id));
    var progress = db.rows("SELECT payload FROM task_event WHERE task_id=? AND event_type='CODE_REVIEW_PROGRESS' ORDER BY id", id).stream().map(r -> Json.tree(r.get("payload").toString())).toList();
    assertEquals(List.of("REVIEWING", "COMPLETED"), progress.stream().map(p -> p.path("state").asText()).toList());
    assertTrue(progress.stream().allMatch(p -> "ocr".equals(p.path("engine").asText())));
    var done = Json.tree(db.row("SELECT payload FROM task_event WHERE task_id=? AND event_type='CODE_REVIEW_DONE'", id).get("payload").toString());
    assertEquals(progress.getFirst().path("runId").asText(), done.path("runId").asText());
    assertEquals("全部 1 个文件都审了", ev.path("coverageText").asText()); assertTrue(ev.path("engine").asText().startsWith("open-code-review v1.13.5"));
    assertTrue(tools.comments.values().stream().flatMap(List::stream).anyMatch(c -> c.contains("open-code-review v1.13.5")), "PR 评论写明审查工具");
    assertTrue(t.context.artifacts.containsKey("codeReview"), "发现交给安全审计和验收");
    assertEquals(List.of(), Tenant.run(Tenant.LEGACY, () -> engine.nodeNeedsMissing(id)));
  }

  @Test void blockingSendsBackToDevLikeTestFailure() {
    assertTrue(cfg.get().codeReview.blocking);
    tools.patches = SECRET;
    String id = ingest("padS", snapshot());
    var t = run(id, x -> END.contains(x.st()) || x.st() == Status.ANOMALY, 350);
    assertBlocked(t);
    assertEquals("rework_exhausted", db.rows("SELECT type FROM anomaly WHERE task_id=?", id).get(0).get("type"), "返修用完是返修卡住，不是省钱模式");
    assertEquals(3, t.context.nodeLoops.get("CODE_REVIEW"));
    assertTrue(db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND edge='node_fix' AND to_status='DEV'", id) >= 2);
    assertEquals(0, db.count("SELECT COUNT(*) FROM verdict WHERE task_id=? AND gate='sec_audit'", id), "被逐行审查拦下，不进安全审计");
  }
  @Test void cleanDiffPassesWithBlockingOn() {
    cfg.update(Json.tree("{\"codeReview\":{\"blocking\":true}}"));
    String id = ingest("padT", snapshot());
    var t = run(id, x -> END.contains(x.st()), 250);
    assertTrue(Set.of(Status.DELIVERED, Status.RELEASED).contains(t.st()), t.status + " " + t.context.failures);
    var ev = evidence.latest(id, "code_review"); assertFalse(ev.path("wouldBlock").asBoolean(), ev.toString());
  }
  @Test void manualResumeAfterCodeReviewExhaustionRepairsThenRunsReviewAgain() {
    cfg.update(Json.tree("{\"codeReview\":{\"blocking\":true}}"));tools.patches=SECRET;
    String id=ingest("padReviewResume",snapshot());
    var stopped=run(id,com.x7x24.engine.RecoveryService::stopped,200);assertBlocked(stopped);
    int commits=tools.commits.get();assertEquals("CODE_REVIEW",stopped.currentStage);
    engine.resumeTask(id,stopped.version,"fixture");assertEquals(Status.DEV,runTo(id,Status.DEV).st());
    tools.patches=null;
    var done=run(id,x->END.contains(x.st()),200);
    assertTrue(Set.of(Status.DELIVERED,Status.RELEASED).contains(done.st()),done.context.failures.toString());
    assertEquals(commits+1,tools.commits.get());assertEquals(3,done.context.reworkQuota.nodeBases.get("CODE_REVIEW"));
    assertTrue(db.count("SELECT COUNT(*) FROM verdict WHERE task_id=? AND gate='code_review' AND decision='pass'",id)>0);
  }

  @Test void unlocatedFindingIsListedButNeverBlocks() {
    cfg.update(Json.tree("{\"codeReview\":{\"blocking\":true}}"));
    tools.patches = List.of(new FilePatch("src/u.js", "added", 1, 0, "@@ -0,0 +1,1 @@\n+export const u = 1; // " + com.x7x24.review.RuleOcrRunner.UNLOCATED_FIXTURE));
    String id = ingest("padUL", snapshot());
    var t = run(id, x -> END.contains(x.st()), 250);
    assertTrue(Set.of(Status.DELIVERED, Status.RELEASED).contains(t.st()), "行号 0 的 critical 不阻断：" + t.status + " " + t.context.failures);
    var ev = evidence.latest(id, "code_review");
    assertEquals(0, ev.path("findings").size()); assertEquals(1, ev.path("unlocated").size()); assertEquals(0, ev.path("unlocated").get(0).path("startLine").asInt());
    assertFalse(ev.path("wouldBlock").asBoolean()); assertTrue(ev.path("reason").asText().contains("1 个没定位到行"), ev.path("reason").asText());
  }

  // ───────── 没审完：停下、不占返修次数；「重试」接着 ocr 会话审（--resume），「跳过并继续」按部分审查往下走
  static final String OK_PATCH = "@@ -0,0 +1,1 @@\n+export const ok = 1;";
  static List<FilePatch> unreachable(boolean fail) {
    return List.of(new FilePatch("src/ok/a.js", "added", 1, 0, OK_PATCH),
        new FilePatch("src/net/b.js", "added", 1, 0, "@@ -0,0 +1,1 @@\n+export const b = \"" + (fail ? com.x7x24.review.RuleOcrRunner.UNREACHABLE_FIXTURE : "fine") + "\";"));
  }
  com.fasterxml.jackson.databind.JsonNode session(String id) {
    var rows = db.rows("SELECT body FROM task_step_checkpoint WHERE task_id=? AND id LIKE 'cr:s:%'", id);
    return rows.isEmpty() ? null : Json.tree(String.valueOf(rows.get(0).get("body")));
  }
  com.x7x24.engine.Task stopOnFailedReview(String name) {
    cfg.update(Json.tree("{\"codeReview\":{\"blocking\":true}}")); tools.patches = unreachable(true);
    String id = ingest(name, snapshot());
    var t = run(id, x -> END.contains(x.st()), 250); assertBlocked(t);
    assertEquals("CODE_REVIEW", t.currentStage);
    assertEquals(com.x7x24.engine.Engine.CODE_REVIEW_FAILED, db.rows("SELECT type FROM anomaly WHERE task_id=?", id).get(0).get("type"));
    assertEquals(0, t.context.nodeLoops.getOrDefault("CODE_REVIEW", 0), "没审完不占返修次数");
    var s = session(id); assertNotNull(s, "ocr 会话已保存"); assertFalse(s.path("sessionId").asText().isBlank()); assertEquals(t.context.pr.headSha, s.path("head").asText());
    assertEquals(0, db.count("SELECT COUNT(*) FROM verdict WHERE task_id=? AND gate='code_review'", id), "没审完不判过也不判驳回");
    var failed = Json.tree(db.row("SELECT payload FROM task_event WHERE task_id=? AND event_type='CODE_REVIEW_FAILED'", id).get("payload").toString());
    assertEquals("src/net/b.js", failed.path("failedFiles").get(0).path("path").asText()); assertEquals(2, failed.path("totalFiles").asInt()); assertEquals(1, failed.path("reviewedFiles").asInt());
    var kinds = ((List<?>) Tenant.run(Tenant.LEGACY, () -> actions.blocked(id)).get("suggestions")).stream().map(x -> ((Map<?, ?>) x).get("kind")).toList();
    assertEquals(List.of(com.x7x24.chat.TaskActions.RESUME, com.x7x24.chat.TaskActions.REVIEW_SKIP_FAILED), kinds);
    return t;
  }
  @Test void unreachableModelStopsThenRetryResumesTheOcrSession() {
    var t = stopOnFailedReview("padU"); String id = t.id; String before = session(id).path("sessionId").asText();
    tools.patches = unreachable(false);
    engine.resumeTask(id, t.version, "fixture");
    var done = run(id, x -> END.contains(x.st()), 250);
    assertTrue(Set.of(Status.DELIVERED, Status.RELEASED).contains(done.st()), done.status + " " + done.context.failures);
    var progress = db.rows("SELECT payload FROM task_event WHERE task_id=? AND event_type='CODE_REVIEW_PROGRESS' ORDER BY id", id).stream().map(r -> Json.tree(r.get("payload").toString())).toList();
    assertTrue(progress.stream().anyMatch(p -> p.path("resumed").asBoolean()), "重试带 --resume");
    assertNotEquals(before, session(id).path("sessionId").asText());
    var ev = evidence.latest(id, "code_review"); assertFalse(ev.path("partial").asBoolean()); assertEquals(2, ev.path("reviewedFiles").asInt());
    assertEquals(0, done.context.nodeLoops.getOrDefault("CODE_REVIEW", 0));
  }
  @Test void skipFailedNeedsManagerAndMarksUnreviewed() {
    var t = stopOnFailedReview("padV"); String id = t.id;
    var member = new com.x7x24.chat.TaskActions.Ctx("m1", "member"); var admin = new com.x7x24.chat.TaskActions.Ctx("a1", "admin");
    String a = (String) Tenant.run(Tenant.LEGACY, () -> actions.propose(id, com.x7x24.chat.TaskActions.REVIEW_SKIP_FAILED, Json.tree("{}"), "button", null, member)).get("id");
    assertThrows(com.x7x24.tenant.WorkspaceService.Forbidden.class, () -> Tenant.run(Tenant.LEGACY, () -> actions.confirm(id, a, null, member)), "成员不能跳过");
    assertEquals("executed", Tenant.run(Tenant.LEGACY, () -> actions.confirm(id, a, null, admin)).get("status"));
    assertEquals(1, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='CODE_REVIEW_SKIP_FAILED'", id), "记审计");
    long progressBefore = db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='CODE_REVIEW_PROGRESS'", id);
    var done = run(id, x -> END.contains(x.st()), 250);
    assertTrue(Set.of(Status.DELIVERED, Status.RELEASED).contains(done.st()), done.status + " " + done.context.failures);
    assertEquals(progressBefore, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='CODE_REVIEW_PROGRESS'", id), "跳过不再跑 ocr");
    var ev = evidence.latest(id, "code_review");
    assertTrue(ev.path("partial").asBoolean()); assertEquals("src/net/b.js", ev.path("unreviewed").get(0).path("path").asText());
    assertEquals(com.x7x24.engine.Engine.SKIPPED_FAILED, ev.path("unreviewed").get(0).path("reason").asText());
    assertTrue(ev.path("reason").asText().startsWith("部分审查：只审了 1/2 个文件"), ev.path("reason").asText());
    assertTrue(tools.comments.values().stream().flatMap(List::stream).anyMatch(c -> c.contains("逐行审查**（部分审查）")), tools.comments.toString());
    assertEquals(0, done.context.nodeLoops.getOrDefault("CODE_REVIEW", 0));
  }
  com.x7x24.engine.Task untilCodeReview(String id) { return run(id, x -> "CODE_REVIEW".equals(x.currentStage) || END.contains(x.st()), 250); }

  // ───────── 暂停 / 版本号冲突 / 同一 head 已通过
  @Test void pausedBeforeReviewStartsThenResumeReviewsOnce() {
    cfg.update(Json.tree("{\"codeReview\":{\"blocking\":false}}")); tools.patches = unreachable(false);
    String id = ingest("padP", snapshot());
    var at = untilCodeReview(id); assertEquals("CODE_REVIEW", at.currentStage);
    db.update("UPDATE task SET paused=1, version=version+1 WHERE id=?", id);
    clk.advance(2 * 60_000L); engine.tick();
    assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='CODE_REVIEW_DONE'", id), "暂停时不审、不下结论");
    var paused = repo.get(id); engine.resumeTask(id, paused.version, "fixture");
    var done = run(id, x -> END.contains(x.st()), 250);
    assertTrue(Set.of(Status.DELIVERED, Status.RELEASED).contains(done.st()), done.status + " " + done.context.failures);
    assertEquals(1, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='CODE_REVIEW_DONE'", id));
  }
  @Test void versionConflictThenFinishedRunAdvancesWithoutReReview() {
    cfg.update(Json.tree("{\"codeReview\":{\"blocking\":false}}")); tools.patches = unreachable(false);
    String id = ingest("padC", snapshot());
    untilCodeReview(id);
    // 审查途中任务版本号被别处改了（如点了暂停又马上继续）：推进决定被放弃，但整轮结果在
    tools.onPrPatches = () -> db.update("UPDATE task SET version=version+1 WHERE id=?", id);
    clk.advance(2 * 60_000L); engine.tick();
    var still = repo.get(id); assertEquals("CODE_REVIEW", still.currentStage, "推进决定因版本冲突被放弃");
    assertEquals(1, db.count("SELECT COUNT(*) FROM task_step_checkpoint WHERE task_id=? AND id LIKE 'cr:d:%'", id));
    long runs = db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='CODE_REVIEW_PROGRESS'", id);
    var done = run(id, x -> END.contains(x.st()), 250);
    assertTrue(Set.of(Status.DELIVERED, Status.RELEASED).contains(done.st()), done.status + " " + done.context.failures);
    assertEquals(runs, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='CODE_REVIEW_PROGRESS'", id), "同一 head 已通过，直接推进不重审");
    assertEquals(1, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='CODE_REVIEW_DONE'", id));
  }
}
