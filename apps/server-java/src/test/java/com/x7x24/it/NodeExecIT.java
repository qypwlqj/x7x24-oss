package com.x7x24.it;

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

/** 测试之后的评审类工作流节点（安全审计）会真正执行：留评审记录、证据和节点执行记录；明文密钥被阻断并打回。 */
class NodeExecIT extends Harness {
  @Autowired PipelineService pipes; @Autowired org.springframework.boot.resttestclient.TestRestTemplate http;
  @BeforeEach void quiet() { cfg.update(Json.tree("{\"autonomy\":{\"tightenDiscards\":10000,\"tightenRollbacks\":10000,\"globalRollbackKill\":10000}}")); }

  String secSnapshot() { return secSnapshot(false); }
  String secSnapshot(boolean strict) {
    var a = new Plans.Adj(); a.op = "insertStage"; a.stage = "SEC_AUDIT"; a.after = "QA"; a.label = "安全审计"; a.source = "rule";
    List<Plans.Adj> adjs = new ArrayList<>(List.of(a));
    if (strict) {
      var gate = new Plans.Adj(); gate.op = "setParam"; gate.stage = "SEC_AUDIT"; gate.param = "gate.pass"; gate.value = 0.999; gate.source = "rule"; adjs.add(gate);
    }
    return Tenant.run(Tenant.LEGACY, () -> pipes.pinPlan("sandbox", adjs, "backend").eff().id);
  }
  String ingest(String title, String body, String snap) {
    var r = engine.ingest(new Engine.Ingest("sandbox", title, body, "chat", "chat:node-" + System.nanoTime(), null, false, null, "sandbox-default", "示例：" + body, snap));
    db.update("UPDATE task SET priority_score=0.9 WHERE id=?", r.taskId()); return r.taskId();
  }

  @Test void securityAuditRunsAfterQaBeforeAcceptance() {
    String id = ingest("新增 padQ 工具函数及测试", "请新增 padQ 工具函数（src/padQ.js），并补充单元测试与文档。示例：padQ(\"a\", 3) => \"aaa\"", secSnapshot());
    var t = run(id, x -> Set.of(Status.DELIVERED, Status.RELEASED, Status.DISCARDED, Status.DEGRADED).contains(x.st()), 200);
    assertTrue(Set.of(Status.DELIVERED, Status.RELEASED).contains(t.st()), t.status + " " + t.discardDetail + " " + t.context.failures);
    var v = db.rows("SELECT gate, decision FROM verdict WHERE task_id=? AND gate='sec_audit'", id);
    assertFalse(v.isEmpty(), "安全审计留下评审记录"); assertEquals("pass", v.get(0).get("decision"));
    assertTrue(db.count("SELECT COUNT(*) FROM evidence WHERE task_id=? AND stage='SEC_AUDIT' AND kind='verdict'", id) > 0);
    assertTrue(db.count("SELECT COUNT(*) FROM stage_execution WHERE task_id=? AND stage='SEC_AUDIT' AND state='SUCCEEDED'", id) > 0, "节点执行记录");
    var order = db.rows("SELECT gate FROM verdict WHERE task_id=? ORDER BY created_at, id", id).stream().map(r -> (String) r.get("gate")).toList();
    assertTrue(order.indexOf("sec_audit") < order.indexOf("acceptance_l2"), order.toString());
    assertTrue(repo.get(id).context.artifacts.containsKey("secAudit"));
  }

  String v2Snapshot(boolean strict) {
    var a = new Plans.Adj(); a.op = "insertStage"; a.stage = "SEC_AUDIT"; a.after = "QA"; a.label = "安全审计"; a.source = "governance";
    List<Plans.Adj> adjs = new ArrayList<>(List.of(a));
    if (strict) { var g = new Plans.Adj(); g.op = "setParam"; g.stage = "SEC_AUDIT"; g.param = "gate.pass"; g.value = 0.999; g.source = "rule"; adjs.add(g); }
    var ctx = new com.x7x24.pipeline.Assembler.Ctx(List.of("backend"), List.of(), null, Map.of("SEC_AUDIT", "测试：必须做安全审计"), "low", "L2");
    return Tenant.run(Tenant.LEGACY, () -> pipes.assemble("sandbox", adjs, "backend", ctx, true, true).eff().id);
  }

  @Test void v2TaskRunsThroughNodesWithNodeEvidenceAndCurrentNode() {
    String snap = v2Snapshot(false);
    assertEquals(2, Tenant.run(Tenant.LEGACY, () -> pipes.snapshot(snap, null)).schema);
    String id = ingest("新增 padV 工具函数及测试", "请新增 padV 工具函数（src/padV.js），并补充单元测试与文档。示例：padV(\"a\", 3) => \"aaa\"", snap);
    assertEquals("v2", repo.get(id).pipelineVersion); assertEquals("INTAKE", repo.get(id).currentNode);
    var seen = new LinkedHashSet<String>();
    var t = run(id, x -> { if (x.currentNode != null) seen.add(x.currentNode); return Set.of(Status.DELIVERED, Status.RELEASED, Status.DISCARDED, Status.DEGRADED).contains(x.st()); }, 200);
    assertTrue(Set.of(Status.DELIVERED, Status.RELEASED).contains(t.st()), t.status + " " + t.discardDetail + " " + t.context.failures);
    var nodes = new ArrayList<>(seen);
    assertTrue(nodes.containsAll(List.of("DEV", "QA", "SEC_AUDIT", "ACCEPTANCE")), nodes.toString());
    assertTrue(nodes.indexOf("QA") < nodes.indexOf("SEC_AUDIT") && nodes.indexOf("SEC_AUDIT") < nodes.indexOf("ACCEPTANCE"), nodes.toString());
    assertEquals("pass", db.rows("SELECT decision FROM verdict WHERE task_id=? AND gate='sec_audit'", id).get(0).get("decision"));
    assertTrue(db.count("SELECT COUNT(*) FROM stage_execution WHERE task_id=? AND stage='SEC_AUDIT' AND state='SUCCEEDED'", id) > 0);
    assertEquals(List.of(), evidenceMissing(id), "v2 按节点核对证据");
  }
  @Test void v2LargeChangeWaitsForOwnerConfirmationBeforeMerge() {
    var c = cfg.get(); c.gate.g1Lines = 1; c.gate.g2Lines = 2; cfg.update(Json.M.valueToTree(c));
    String id = ingest("合并关卡：大改动需负责人确认 rjustGate", "合并关卡验证：请新增 rjustGate（src/rjustGate.js）右对齐补齐，附测试与文档。示例：rjustGate(\"b\", 2) => \"bb\"", v2Snapshot(false));
    var t = run(id, x -> db.count("SELECT COUNT(*) FROM merge_approval WHERE task_id=? AND status='pending'", id) > 0 || Set.of(Status.RELEASED, Status.DISCARDED, Status.DEGRADED).contains(x.st()), 300);
    assertEquals("v2", t.pipelineVersion); assertEquals(Status.DELIVERED, t.st(), t.status + " " + t.context.failures);
    assertTrue(tools.merged.isEmpty(), "v2 也要等负责人确认");
    engine.decideMerge(id, "approve", t.context.pr.headSha, "", "owner");
    assertEquals(Status.RELEASED, run(id, x -> Set.of(Status.RELEASED, Status.DISCARDED).contains(x.st()), 200).st());
  }
  List<String> evidenceMissing(String id) { return Tenant.run(Tenant.LEGACY, () -> engine.nodeNeedsMissing(id)); }

  @Test void legacySecurityRejectRepairsTheSamePrAndRerunsChecks() {
    String id = ingest("新增 padLegacy 工具函数", "新增 padLegacy（src/padLegacy.js），补充测试与文档。示例：padLegacy(\"a\", 3) => \"aaa\"", secSnapshot(true));
    assertEquals("v1", repo.get(id).pipelineVersion);
    var firstReview = run(id, t -> "SEC_AUDIT".equals(t.currentStage), 200);
    var repaired = run(id, t -> "SEC_AUDIT".equals(t.currentStage) && t.currentIteration > firstReview.currentIteration, 200);
    assertTrue(repaired.currentIteration > firstReview.currentIteration);
    assertEquals(firstReview.plan, repaired.plan);
    assertEquals(firstReview.context.pr.number, repaired.context.pr.number);
    assertEquals(firstReview.context.pr.branch, repaired.context.pr.branch);
    assertEquals(1, repaired.context.nodeLoops.get("SEC_AUDIT"));
    assertTrue(db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND edge='node_fix' AND to_status='DEV'", id) > 0);
    assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND from_status='ACCEPTANCE' AND to_status='PLANNING'", id));
    var event = Json.tree(db.row("SELECT payload FROM task_event WHERE task_id=? AND event_type='NODE_REJECTED'", id).get("payload").toString());
    assertEquals("DEV", event.path("returnTo").asText());
    assertEquals(1, event.path("loops").asInt());
  }

  @Test void reviewScopeChangeWaitsForConfirmationThenReturnsToTheAffectedDesign() {
    var ctx = new com.x7x24.pipeline.Assembler.Ctx(List.of("backend", "migration"), List.of(), null,
        Map.of("DB_SCHEMA", "测试：涉及迁移", "SEC_AUDIT", "测试：需要审计"), "low", "L2");
    String snapshot = Tenant.run(Tenant.LEGACY, () -> pipes.assemble("sandbox", List.of(), "backend", ctx, true, true).eff().id);
    String id = ingest("新增 padDesign 工具函数", "新增 padDesign（src/padDesign.js），补充测试与文档。示例：padDesign(\"a\", 3) => \"aaa\"", snapshot);
    var audit = run(id, t -> "SEC_AUDIT".equals(t.currentStage), 200);
    assertEquals("SEC_AUDIT", audit.currentStage);
    engine.apply(audit, com.x7x24.engine.Decision.block("review_plan_adjustment", "数据库设计需要补充资源归属关系"));
    var stopped = repo.get(id);
    assertBlocked(stopped);
    assertThrows(IllegalArgumentException.class, () -> engine.resumeTask(id, stopped.version, "owner"));
    var confirmed = engine.adjustPlan(id, stopped.version, "DB_SCHEMA", "owner");
    assertEquals(Status.DESIGN, confirmed.st());
    assertEquals("DB_SCHEMA", confirmed.currentStage);
    assertEquals(audit.plan, confirmed.plan);
    assertEquals(audit.context.pr.number, confirmed.context.pr.number);
    assertTrue(confirmed.context.lastCiLog.contains("资源归属关系"));
    var reviewed = run(id, t -> "SEC_AUDIT".equals(t.currentStage), 200);
    assertEquals("SEC_AUDIT", reviewed.currentStage);
    assertEquals(audit.context.pr.number, reviewed.context.pr.number);
  }

  @Test void v2ReviewRejectRoutesBackToDevAndEscalatesAfterMaxLoops() {
    String id = ingest("新增 padW 工具函数及测试", "请新增 padW 工具函数（src/padW.js），并补充单元测试与文档。示例：padW(\"a\", 3) => \"aaa\"", v2Snapshot(true));
    var t = run(id, x -> Set.of(Status.DELIVERED, Status.RELEASED, Status.DISCARDED, Status.DEGRADED, Status.ANOMALY).contains(x.st()), 300);
    assertBlocked(t);
    assertEquals("rework_exhausted", db.rows("SELECT type FROM anomaly WHERE task_id=?", id).get(0).get("type"), "返修用完 → 换方案，不进省钱模式");
    assertEquals(3, t.context.nodeLoops.get("SEC_AUDIT"), "超过节点 maxLoops（默认 2）才换方案");
    assertTrue(db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND edge='node_fix' AND to_status='DEV'", id) >= 2, "评审不通过回编码返修，而不是回规划");
    assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND from_status='ACCEPTANCE' AND to_status='PLANNING'", id));
    assertEquals(3, db.count("SELECT COUNT(*) FROM verdict WHERE task_id=? AND gate='sec_audit' AND decision='reject'", id));
  }
  @Test void exhaustedReviewResumeRenewsOnlyItsRepairChainAndCreatesFreshCandidates() {
    String id=ingest("新增 padResume 工具函数及测试","请新增 padResume 工具函数（src/padResume.js），并补充单元测试与文档。示例：padResume(\"a\", 3) => \"aaa\"",v2Snapshot(true));
    var stopped=run(id,com.x7x24.engine.RecoveryService::stopped,200);assertBlocked(stopped);
    assertEquals("SEC_AUDIT",stopped.currentStage);assertEquals(3,stopped.context.nodeLoops.get("SEC_AUDIT"));
    stopped.loopCount=5;stopped.context.nodeLoops.put("OTHER_REVIEW",8);stopped.context.reworkQuota.nodeBases.put("OTHER_REVIEW",2);assertTrue(repo.save(stopped));
    long candidates=db.count("SELECT COUNT(*) FROM task_step_checkpoint WHERE task_id=?",id);int commits=tools.commits.get();
    var resumed=engine.resumeTask(id,stopped.version,"fixture");
    assertEquals(5,resumed.context.reworkQuota.qaBase);assertEquals(3,resumed.context.reworkQuota.nodeBases.get("SEC_AUDIT"));
    assertEquals(2,resumed.context.reworkQuota.nodeBases.get("OTHER_REVIEW"));assertEquals(8,resumed.context.nodeLoops.get("OTHER_REVIEW"));
    assertTrue(resumed.context.lastCiLog.contains("安全审计"));
    var dev=runTo(id,Status.DEV);assertEquals(Status.DEV,dev.st());assertEquals(stopped.currentIteration+1,dev.currentIteration);
    assertEquals("RESUMING",db.row("SELECT state FROM task_recovery WHERE id=?",stopped.context.recoveryId).get("state"));
    var again=run(id,com.x7x24.engine.RecoveryService::stopped,200);assertBlocked(again);
    assertEquals("SEC_AUDIT",again.currentStage);assertEquals(6,again.context.nodeLoops.get("SEC_AUDIT"));
    assertEquals(commits+2,tools.commits.get(),"新一轮最多两次实际返修");
    assertEquals(candidates+2,db.count("SELECT COUNT(*) FROM task_step_checkpoint WHERE task_id=?",id),"不能复用先前提交的候选");
    assertEquals(5,again.loopCount,"成功的 QA 不消耗测试返修额度");
    assertEquals(stopped.plan,again.plan);assertEquals(1,tools.prOpened.get());
  }
  @Test void uiAcceptanceExhaustionResumesThroughDevelopmentAndNewUiChecks() throws Exception {
    var definition=com.x7x24.pipeline.Nodes.def("UI_ACCEPTANCE");boolean available=definition.available;
    var serviceField=Engine.class.getDeclaredField("uiAcceptance");serviceField.setAccessible(true);var original=serviceField.get(engine);
    var checks=new java.util.concurrent.atomic.AtomicInteger();var failing=new java.util.concurrent.atomic.AtomicBoolean(true);
    var service=new com.x7x24.execution.agent.UiAcceptanceService(null,null,null,null,null,null,null,null) {
      @Override public Outcome run(com.x7x24.engine.Task task,com.x7x24.engine.ProjectCfg project,String branch,List<String> criteria) {
        checks.incrementAndGet();String verdict=failing.get()?"FAIL":"PASS";
        return new Outcome("fixture-ui-"+checks.get(),"SUCCEEDED",new com.x7x24.execution.agent.AcceptanceJudge.Verdict(verdict,"deterministic","页面断言结果",List.of(),0,false),failing.get(),Map.of("summary","页面断言结果"));
      }
    };
    definition.available=true;serviceField.set(engine,service);
    try {
      var ctx=new com.x7x24.pipeline.Assembler.Ctx(List.of("frontend"),List.of(),null,Map.of("UI_ACCEPTANCE","测试：页面验收"),"low","L2");
      String snapshot=Tenant.run(Tenant.LEGACY,()->pipes.assemble("sandbox",List.of(),"backend",ctx,true,true).eff().id);
      String id=ingest("新增 padUiResume 工具函数及测试","请新增 padUiResume 工具函数（src/padUiResume.js），并补充单元测试与文档。示例：padUiResume(\"a\", 3) => \"aaa\"",snapshot);
      var task=runTo(id,Status.DEV);assertEquals(Status.DEV,task.st());task.context.projectSnapshot.uiAcceptance.enabled=true;assertTrue(repo.save(task));
      var stopped=run(id,com.x7x24.engine.RecoveryService::stopped,200);assertBlocked(stopped);
      assertEquals("UI_ACCEPTANCE",stopped.currentStage);assertEquals(3,checks.get());int commits=tools.commits.get();
      engine.resumeTask(id,stopped.version,"fixture");assertEquals(Status.DEV,runTo(id,Status.DEV).st());
      assertEquals(3,checks.get(),"旧页面验收结果只作为修复依据");
      failing.set(false);var done=run(id,x->Set.of(Status.DELIVERED,Status.RELEASED).contains(x.st()),150);
      assertTrue(Set.of(Status.DELIVERED,Status.RELEASED).contains(done.st()),done.context.failures.toString());
      assertEquals(commits+1,tools.commits.get());assertEquals(4,checks.get());assertEquals(3,done.context.reworkQuota.nodeBases.get("UI_ACCEPTANCE"));
    } finally { definition.available=available;serviceField.set(engine,original); }
  }

  static final Set<Status> END = Set.of(Status.DELIVERED, Status.RELEASED, Status.DISCARDED, Status.DEGRADED);
  @Test void nodeBoardAndTaskPipelineTabAndRevision() {
    String id = ingest("新增 padX 工具函数及测试", "请新增 padX 工具函数（src/padX.js），并补充单元测试与文档。示例：padX(\"a\", 3) => \"aaa\"", v2Snapshot(false));
    run(id, x -> x.currentNode != null && Set.of("DEV", "QA").contains(x.currentNode) || END.contains(x.st()), 200);
    String cur = repo.get(id).currentNode;
    // 方案 A：工作区汇总看板，按 采集 / 生产（设计 / 构建 / 检查） / 交付 三段
    Map<?, ?> b = http.getForObject("/api/board/pipeline?productLine=sandbox&days=30", Map.class);
    Map<?, ?> mods = (Map<?, ?>) b.get("modules");
    assertTrue(mods.containsKey("sensing") && mods.containsKey("production") && mods.containsKey("delivery"), b.toString());
    var fams = (List<?>) mods.get("production");
    var cells = java.util.stream.Stream.concat(((List<?>) mods.get("delivery")).stream().map(c -> Map.of("nodes", List.of(c))), fams.stream()).flatMap(f -> ((List<?>) ((Map<?, ?>) f).get("nodes")).stream()).map(c -> (Map<?, ?>) c).toList();
    var hit = cells.stream().filter(c -> cur.equals(c.get("node"))).findFirst().orElseThrow(() -> new AssertionError(cur + " 不在看板 " + cells));
    assertTrue(((Number) hit.get("current")).intValue() >= 1, hit.toString());
    Map<?, ?> n = http.getForObject("/api/board/pipeline/node/" + cur + "?productLine=sandbox", Map.class);
    assertTrue(((List<?>) n.get("tasks")).stream().anyMatch(x -> id.equals(((Map<?, ?>) x).get("id"))), n.toString());
    // 任务流程页签：图 + 当前节点 + 每个节点的执行记录
    Map<?, ?> p = http.getForObject("/api/tasks/" + id + "/plan", Map.class);
    assertEquals(cur, p.get("currentNode")); assertEquals(2, ((Number) p.get("schema")).intValue()); assertNotNull(p.get("graph"));
    assertTrue(((Map<?, ?>) p.get("records")).containsKey("PLANNING"), p.get("records").toString());
    // 中途修订：加一个检查，生效后重新钉住快照，记审计
    var r = http.postForEntity("/api/tasks/" + id + "/plan/revise", Map.of("adjustments", List.of(Map.of("op", "addCheck", "stage", "QA", "check", "markdown-lint", "reason", "补一次代码规范检查"))), Map.class);
    assertEquals(200, r.getStatusCode().value(), String.valueOf(r.getBody()));
    assertEquals(true, r.getBody().get("ok"), String.valueOf(r.getBody())); assertEquals(1, ((Number) r.getBody().get("revisions")).intValue());
    assertEquals(1, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='PLAN_REVISED'", id));
    // 不能去掉强制节点
    var bad = http.postForEntity("/api/tasks/" + id + "/plan/revise", Map.of("adjustments", List.of(Map.of("op", "removeStage", "stage", "SEC_AUDIT", "reason", "不要了"))), Map.class);
    assertEquals(false, bad.getBody().get("ok"), String.valueOf(bad.getBody()));
    var t = run(id, x -> END.contains(x.st()), 200);
    assertTrue(Set.of(Status.DELIVERED, Status.RELEASED).contains(t.st()), t.status + " " + t.context.failures);
    assertTrue(db.count("SELECT COUNT(*) FROM stage_execution WHERE task_id=? AND stage='SEC_AUDIT'", id) > 0);
  }

  /** 回归（真实 lms 运行中发现）：设计类必选节点排在需求策划之后，任务路径按阶段排序，不出现 DESIGN → SPEC 的非法迁移 */
  @Test void designNodesRunAfterSpecWhenBothPresent() {
    var en = new Plans.Adj(); en.op = "enable"; en.stage = "SPEC"; en.source = "rule"; en.reason = "测试：需要需求策划";
    var ctx = new com.x7x24.pipeline.Assembler.Ctx(List.of("backend", "migration"), List.of(), null, Map.of("DB_SCHEMA", "测试：涉及迁移"), "low", "L2");
    var r = Tenant.run(Tenant.LEGACY, () -> pipes.assemble("sandbox", List.of(en), "backend", ctx, true, true));
    var ids = new ArrayList<>(r.eff().stages.keySet());
    assertTrue(ids.indexOf("SPEC") < ids.indexOf("DB_SCHEMA"), ids.toString());
    String id = ingest("新增 padY 工具函数及测试", "请新增 padY 工具函数（src/padY.js），并补充单元测试与文档。示例：padY(\"a\", 3) => \"aaa\"", r.eff().id);
    var seen = new LinkedHashSet<String>();
    var t = run(id, x -> { if (x.currentNode != null) seen.add(x.currentNode); return END.contains(x.st()); }, 200);
    assertTrue(Set.of(Status.DELIVERED, Status.RELEASED).contains(t.st()), t.status + " " + seen + " " + t.context.failures);
    var nodes = new ArrayList<>(seen);
    assertTrue(nodes.contains("DB_SCHEMA") && nodes.indexOf("DB_SCHEMA") < nodes.indexOf("DEV"), nodes.toString());
    if (nodes.contains("SPEC")) assertTrue(nodes.indexOf("SPEC") < nodes.indexOf("DB_SCHEMA"), nodes.toString());
  }

  @Test void oldSnapshotWithoutReviewNodeIsUnchanged() {
    String id = ingest("新增 trimQ 工具函数及测试", "请新增 trimQ 工具函数（src/trimQ.js），并补充单元测试与文档。示例：trimQ(\" a \") => \"a\"", null);
    var t = run(id, x -> Set.of(Status.DELIVERED, Status.RELEASED, Status.DISCARDED, Status.DEGRADED).contains(x.st()), 200);
    assertTrue(Set.of(Status.DELIVERED, Status.RELEASED).contains(t.st()), t.status + " " + t.discardDetail);
    assertEquals(0, db.count("SELECT COUNT(*) FROM verdict WHERE task_id=? AND gate='sec_audit'", id));
  }
}
