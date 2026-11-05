package com.x7x24.it;

import com.x7x24.agents.Models.*;
import com.x7x24.adapters.DeliveryTools.FileChange;
import com.x7x24.core.Status;
import com.x7x24.engine.*;
import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real engine + in-memory GitHub. Never contacts a model or a remote repository. */
class RecoveryIT extends Harness {
  @org.springframework.beans.factory.annotation.Autowired com.x7x24.chat.TaskActions actions;
  @org.springframework.test.context.DynamicPropertySource static void isolated(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",()->"jdbc:h2:mem:recovery;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1");
  }
  @org.junit.jupiter.api.BeforeEach void thresholds() {cfg.update(Json.tree("{\"autonomy\":{\"tightenDiscards\":10000,\"tightenRollbacks\":10000,\"globalRollbackKill\":10000}}"));}
  String development() {
    String id = ingestIssue("新增 slugify 工具函数及测试", "新增 slugify 工具函数 src/slugify.js，slugify(Hello World) => hello-world");
    assertEquals(Status.DEV, runTo(id, Status.DEV).st()); return id;
  }
  Task qaBlocked(boolean exhausted) {
    cfg.update(Json.tree("{\"loops\":{\"maxLoops\":"+(exhausted?1:3)+"}}"));
    tools.ciAlwaysFail=true;
    String id=development();var t=run(id,x->RecoveryService.stopped(x),200);
    assertBlocked(t);assertEquals("QA",t.currentStage);
    assertEquals(exhausted?"rework_exhausted":"same_failure",engine.recoveryInfo(id).get("code"));
    return t;
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void qualityRecoveryStartsActualRepairWithoutRecountingOldCi(boolean exhausted) throws Exception {
    var stopped=qaBlocked(exhausted);String id=stopped.id;
    int commits=tools.commits.get(),failures=stopped.context.failures.size();
    long candidates=db.count("SELECT COUNT(*) FROM task_step_checkpoint WHERE task_id=?",id);
    var resumed=engine.resumeTask(id,stopped.version,"fixture");
    assertEquals(stopped.loopCount,resumed.loopCount);assertEquals(stopped.loopCount,resumed.context.reworkQuota.qaBase);
    assertEquals(1,resumed.context.reworkQuota.round);assertEquals("QA",resumed.context.reworkQuota.pendingNode);
    assertNull(resumed.context.lastCiSig);assertEquals(0,resumed.context.sameFailStreak);
    assertEquals(stopped.plan,resumed.plan);assertEquals(stopped.context.pr.headSha,resumed.context.pr.headSha);
    assertEquals(stopped.spentUsd,resumed.spentUsd);assertEquals(stopped.spentTokens,resumed.spentTokens);assertEquals(stopped.spentIterations,resumed.spentIterations);
    assertThrows(IllegalArgumentException.class,()->engine.resumeTask(id,stopped.version,"fixture"));
    // Any attempt to poll the old CI would fail this transition. Read the task back as after a restart.
    tools.ciFailure=new IllegalStateException("must not read the already consumed failure");
    var dev=runTo(id,Status.DEV);tools.ciFailure=null;
    assertEquals(Status.DEV,dev.st());assertNull(dev.context.reworkQuota.pendingNode);
    assertEquals(stopped.currentIteration+1,dev.currentIteration);
    assertEquals(stopped.context.qaRuns,dev.context.qaRuns);assertEquals(failures,dev.context.failures.size());
    assertEquals(stopped.loopCount+1,dev.loopCount,"派发返修占用一次额度，但不制造新失败");
    assertEquals(stopped.plan.path("subtasks").size(),dev.context.subtaskIdx);
    assertEquals("RESUMING",db.row("SELECT state FROM task_recovery WHERE id=?",stopped.context.recoveryId).get("state"));
    var field=Engine.class.getDeclaredField("agents");field.setAccessible(true);var original=(ExecAgents)field.get(engine);
    var codeCalls=new java.util.concurrent.atomic.AtomicInteger();
    var replacement=(ExecAgents)java.lang.reflect.Proxy.newProxyInstance(ExecAgents.class.getClassLoader(),new Class<?>[]{ExecAgents.class},(proxy,method,args)->{
      if(method.getName().equals("code")){codeCalls.incrementAndGet();assertEquals(stopped.context.lastCiLog,((Ctx)args[0]).task().context.lastCiLog);}
      return method.invoke(original,args);
    });
    field.set(engine,replacement);
    try { assertEquals(Status.QA,runTo(id,Status.QA).st()); } finally { field.set(engine,original); }
    assertEquals(1,codeCalls.get(),"必须重新调用编码助手，不能重用旧候选");
    assertEquals(commits+1,tools.commits.get());assertEquals(1,tools.prOpened.get());
    assertEquals(candidates+1,db.count("SELECT COUNT(*) FROM task_step_checkpoint WHERE task_id=?",id));
    tools.ciAlwaysFail=false;
    var passed=run(id,x->Set.of(Status.DELIVERED,Status.RELEASED).contains(x.st()),100);
    assertTrue(Set.of(Status.DELIVERED,Status.RELEASED).contains(passed.st()),passed.context.failures.toString());
    var event=Json.tree((String)db.row("SELECT payload FROM task_event WHERE task_id=? AND event_type='TASK_RESUMED' ORDER BY id DESC LIMIT 1",id).get("payload"));
    assertEquals(stopped.loopCount,event.path("reworkReset").path("totals").path("qa").asInt());
    assertEquals(exhausted?1:3,event.path("reworkReset").path("after").path("limits").path("QA").asInt());
    assertEquals(exhausted?"rework_exhausted":"same_failure",event.path("reworkReset").path("after").path("cause").asText());
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void aRenewedRoundIsStillBoundedAndCanBeExplicitlyRenewedAgain(boolean exhausted) {
    var stopped=qaBlocked(exhausted);String id=stopped.id;
    for(int round=1;round<=2;round++) {
      int commits=tools.commits.get(),qaRuns=stopped.context.qaRuns;
      engine.resumeTask(id,stopped.version,"fixture");
      stopped=run(id,RecoveryService::stopped,100);assertBlocked(stopped);
      assertEquals(round,stopped.context.reworkQuota.round);
      assertEquals(exhausted?1:3,tools.commits.get()-commits,"实际修复次数受本轮额度约束");
      assertEquals(exhausted?1:3,stopped.context.qaRuns-qaRuns,"只计新检查结果");
      assertEquals(exhausted?"rework_exhausted":"same_failure",engine.recoveryInfo(id).get("code"));
    }
  }
  @Test void pauseAfterRecoveryKeepsCurrentPositionAndDoesNotRenewQuotaAgain() {
    var stopped=qaBlocked(false);String id=stopped.id;engine.resumeTask(id,stopped.version,"fixture");
    var dev=runTo(id,Status.DEV);dev.paused=1;assertTrue(repo.save(dev));
    assertFalse(engine.recoveryInfo(id).containsKey("rework"));
    var resumed=engine.resumeTask(id,dev.version,"fixture");
    assertEquals(Status.DEV,resumed.st(),"不能用上次停止的 QA 检查点覆盖当前开发位置");
    assertEquals(dev.currentIteration,resumed.currentIteration);assertEquals(dev.loopCount,resumed.loopCount);
    assertEquals(Json.str(dev.context.reworkQuota),Json.str(resumed.context.reworkQuota));
  }
  @Test void legacyContextWithoutQuotaMetadataCanResumeWithoutMigration() {
    var stopped=qaBlocked(true);String id=stopped.id;
    var context=(com.fasterxml.jackson.databind.node.ObjectNode)Json.M.valueToTree(stopped.context);context.remove("reworkQuota");
    db.update("UPDATE task SET context=? WHERE id=?",context.toString(),id);
    var resumed=engine.resumeTask(id,stopped.version,"fixture");
    assertEquals(stopped.loopCount,resumed.context.reworkQuota.qaBase);assertEquals(1,resumed.context.reworkQuota.round);
    assertEquals(Status.DEV,runTo(id,Status.DEV).st());assertEquals(Status.QA,runTo(id,Status.QA).st());
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"agent_error","timeout","ci_broken"})
  void nonQualityRecoveryOnlyRenewsExecutionAttempts(String cause) {
    String id=development();var task=repo.get(id);
    task.loopCount=7;task.context.reworkQuota.round=1;task.context.reworkQuota.qaBase=4;
    task.context.lastCiSig="prior failure";task.context.sameFailStreak=1;task.stepAttempts=2;assertTrue(repo.save(task));
    engine.apply(task,Decision.block(cause,"fixture"));var stopped=repo.get(id);
    assertFalse(engine.recoveryInfo(id).containsKey("rework"));
    var resumed=engine.resumeTask(id,stopped.version,"fixture");
    assertEquals(7,resumed.loopCount);assertEquals(Json.str(stopped.context.reworkQuota),Json.str(resumed.context.reworkQuota));
    assertEquals("prior failure",resumed.context.lastCiSig);assertEquals(1,resumed.context.sameFailStreak);assertEquals(0,resumed.stepAttempts);
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"budget","head","external"})
  void rejectedRecoveryNeverRenewsOrSchedulesARepair(String restriction) {
    var stopped=qaBlocked(false);String id=stopped.id;
    if(restriction.equals("budget")){
      stopped.budgetUsd=0.01;stopped.context.budgetOff=false;assertTrue(repo.save(stopped));
      budget.charge(id+":fixture-cost",id,stopped.productLine,"fixture","fixture",0,1,0);
      stopped=repo.get(id);assertEquals("hard",budget.taskStatus(stopped));
    }
    if(restriction.equals("head"))tools.branches.get(stopped.context.pr.branch).put("src/external.js","external change");
    if(restriction.equals("external"))db.update("INSERT INTO tool_ops(op_key,result,state,created_at) VALUES(?,'null','OUTCOME_UNKNOWN',?)",id+":unknown",clk.iso());
    int version=stopped.version;String quota=Json.str(stopped.context.reworkQuota);
    assertThrows(IllegalArgumentException.class,()->engine.resumeTask(id,version,"fixture"));
    var after=repo.get(id);assertBlocked(after);assertEquals(version,after.version);assertEquals(quota,Json.str(after.context.reworkQuota));
    assertEquals(stopped.loopCount,after.loopCount);assertEquals(stopped.context.lastCiSig,after.context.lastCiSig);
    assertEquals(0,db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='TASK_RESUMED'",id));
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"task_resume","node_retry"})
  void conversationResumeAndHintedRetryShareTheSameReworkRules(String kind) {
    var stopped=qaBlocked(true);String id=stopped.id;
    com.x7x24.tenant.Tenant.run(com.x7x24.tenant.Tenant.LEGACY,()->{
      var who=new com.x7x24.chat.TaskActions.Ctx("fixture","member");
      var proposal=actions.propose(id,kind,Json.tree("{\"hint\":\"先读失败用例\",\"rework\":{\"limit\":999}}"),"button",null,who);
      assertEquals(1,Json.M.valueToTree(proposal.get("params")).path("rework").path("limit").asInt(),"额度由服务端决定");
      String action=(String)proposal.get("id");actions.confirm(id,action,null,who);actions.confirm(id,action,null,who);
      return null;
    });
    assertEquals(1,repo.get(id).context.reworkQuota.round);assertEquals(Status.DEV,runTo(id,Status.DEV).st());
    if(kind.equals("node_retry"))assertTrue(repo.get(id).context.reviewNotes.stream().anyMatch(s->s.contains("先读失败用例")));
    assertEquals(Status.QA,runTo(id,Status.QA).st());
  }
  @Test void rejectedEleventhSegmentKeepsTheFirstTenAndTheIssueOpen() throws Exception {
    String id = development(); var t = repo.get(id); var plan = Json.convert(t.plan, Plan.class);
    var parts = new ArrayList<Map<String,Object>>();
    for (int i=0;i<14;i++) parts.add(Map.of("title", "segment-"+i, "files", List.of("src/part"+i+".js")));
    t.plan = Json.M.valueToTree(new Plan(plan.path(),plan.template(),plan.strategy(),plan.scopeLevel(),plan.risk(),plan.files(),parts)); assertTrue(repo.save(t));
    var field = Engine.class.getDeclaredField("agents"); field.setAccessible(true); var original = (ExecAgents)field.get(engine);
    var repaired=new java.util.concurrent.atomic.AtomicBoolean();
    var replacement = (ExecAgents)java.lang.reflect.Proxy.newProxyInstance(ExecAgents.class.getClassLoader(), new Class<?>[]{ExecAgents.class}, (proxy, method, args) -> {
      if (!method.getName().equals("code")) return method.invoke(original,args);
      int p = ((Ctx)args[0]).task().context.subtaskIdx;
      return new CodeChange(List.of(new FileChange("src/part"+p+".js", p==10 && !repaired.get() ? "console.log(process.env);" : "module.exports = "+p+";", false, 1)), "feat: segment", "segment", "fixture");
    });
    field.set(engine,replacement);
    try {
      var dev=Engine.class.getDeclaredMethod("onDev",Task.class); dev.setAccessible(true);
      for(int i=0;i<10;i++) { var cur=repo.get(id); engine.apply(cur,(Decision)dev.invoke(engine,cur)); }
      for(int i=0;i<4;i++) { var cur=repo.get(id); if(cur.nextRunAt==null)break; engine.apply(cur,(Decision)dev.invoke(engine,cur)); }
      var blocked=repo.get(id);
      assertEquals(Status.DEV,blocked.st(),"错误必须停在开发节点");
      assertEquals("BLOCKED",blocked.executionState); assertNull(blocked.nextRunAt);
      assertEquals(10,blocked.context.subtaskIdx); assertEquals(10,tools.commits.get());
      assertEquals(1,tools.prOpened.get()); assertFalse(tools.closed.contains(issueNo(id)));
      assertEquals(10,tools.branches.get(blocked.context.pr.branch).size());
      repaired.set(true);String snapshot=blocked.context.configSnapshot;int version=blocked.version;
      var resumed=engine.resumeTask(id,version,"fixture");
      assertEquals(10,resumed.context.subtaskIdx);assertEquals(snapshot,resumed.context.configSnapshot);
      assertThrows(IllegalArgumentException.class,()->engine.resumeTask(id,version,"fixture"));
      var cur=repo.get(id);engine.apply(cur,(Decision)dev.invoke(engine,cur));
      assertEquals(11,repo.get(id).context.subtaskIdx);assertEquals(11,tools.commits.get());assertEquals(1,tools.prOpened.get());
      assertEquals("RESOLVED",db.row("SELECT state FROM task_recovery WHERE id=?",blocked.context.recoveryId).get("state"));
    } finally { field.set(engine,original); }
  }
  @Test void anUnknownFailureAndExhaustedDegradationNeverEndTheTask() {
    String id=development(); var before=repo.get(id);
    engine.apply(before,Decision.anomaly("new_unclassified_error","fixture failure"));
    var stopped=repo.get(id); assertEquals(Status.DEV,stopped.st()); assertEquals("BLOCKED",stopped.executionState); assertNull(stopped.nextRunAt);
    for(int i=0;i<10;i++){clk.advance(120000);engine.tick();}
    assertEquals(Status.DEV,repo.get(id).st());
    engine.apply(repo.get(id),Decision.discard("loop_exhausted","auto_discard",Map.of("level","D3")));
    assertEquals(Status.DEV,repo.get(id).st()); assertFalse(tools.closed.contains(issueNo(id)));
  }
  @Test void manualAbandonAndHistoricalAutoDiscardResumeAtTheSavedNode() {
    String id=development();var t=repo.get(id);int iteration=t.currentIteration;
    engine.apply(t,new Decision.Discard("manual_abandon","abandon",Map.of("actor","fixture"),Decision.NOOP));
    var ended=repo.get(id);assertEquals(Status.DISCARDED,ended.st());
    var restored=engine.resumeTask(id,ended.version,"fixture");assertEquals(Status.DEV,restored.st());assertEquals(iteration,restored.currentIteration);
    // Simulate a pre-upgrade row. Recovery uses append-only events, without rewriting history.
    db.update("UPDATE task SET status='DISCARDED',current_stage='DISCARDED',execution_state='DISCARDED',discard_reason='forbidden_scope',next_run_at=NULL WHERE id=?",id);
    var legacy=repo.get(id);legacy.context.recoveryId=null;assertTrue(repo.save(legacy));
    var r=engine.resumeTask(id,legacy.version,"fixture");assertEquals(Status.DEV,r.st());assertEquals(iteration,r.currentIteration);
    assertEquals(r.st(),engine.describe(id).replayStatus());
  }
  @Test void unresolvedExternalOperationPreventsStartingAnotherAttempt() {
    String id=development();engine.apply(repo.get(id),Decision.block("timeout","response unknown"));
    var t=repo.get(id);
    db.update("INSERT INTO tool_ops(op_key,result,state,created_at) VALUES(?,'null','OUTCOME_UNKNOWN',?)",id+":commit",clk.iso());
    assertThrows(IllegalArgumentException.class,()->engine.resumeTask(id,t.version,"fixture"));
    assertNull(repo.get(id).nextRunAt);assertEquals("BLOCKED",repo.get(id).executionState);
  }
  @Test void aCommittedCandidateSurvivesFailureBeforeThePrWithoutRegeneratingOrCommittingAgain() throws Exception {
    String id=development();var single=repo.get(id);var plan=Json.convert(single.plan,Plan.class);single.plan=Json.M.valueToTree(new Plan(plan.path(),plan.template(),plan.strategy(),plan.scopeLevel(),plan.risk(),plan.files(),List.of()));assertTrue(repo.save(single));var field=Engine.class.getDeclaredField("tools");field.setAccessible(true);var original=field.get(engine);
    var failed=new java.util.concurrent.atomic.AtomicBoolean();
    var proxy=java.lang.reflect.Proxy.newProxyInstance(com.x7x24.adapters.DeliveryTools.class.getClassLoader(),new Class<?>[]{com.x7x24.adapters.DeliveryTools.class},(self,method,args)->{
      if(method.getName().equals("openPr") && !failed.getAndSet(true))throw new IllegalStateException("fixture: stopped after commit");
      return method.invoke(original,args);
    });
    field.set(engine,proxy);
    try {
      var dev=Engine.class.getDeclaredMethod("onDev",Task.class);dev.setAccessible(true);var before=repo.get(id);
      assertThrows(java.lang.reflect.InvocationTargetException.class,()->dev.invoke(engine,before));
      assertEquals(1,tools.commits.get());assertEquals(0,tools.prOpened.get());
      engine.apply(before,Decision.block("agent_error","stopped after commit"));
      var stopped=repo.get(id);
      assertTrue(db.count("SELECT COUNT(*) FROM task_step_checkpoint WHERE task_id=?",id)>0);
      // Use a freshly loaded task, exactly as a worker does after a process restart.
      var resumed=engine.resumeTask(id,stopped.version,"fixture");
      var cp=Json.tree((String)db.row("SELECT body FROM task_step_checkpoint WHERE task_id=?",id).get("body"));
      assertEquals(cp.path("headSha").asText(),tools.ensureBranch(new com.x7x24.engine.ProjectCfg(),"fixture",tools.branches.keySet().iterator().next()), "checkpoint="+cp.path("headSha")+" branches="+tools.branches.keySet());
      var branch=tools.branches.values().iterator().next();branch.put("src/external.js","external change");
      for(int attempt=0;attempt<2;attempt++) {
        var current=repo.get(id);
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->dev.invoke(engine,current));
        var saved=Json.tree((String)db.row("SELECT body FROM task_step_checkpoint WHERE task_id=?",id).get("body"));
        assertEquals(cp.path("headSha"),saved.path("headSha"),"分支冲突不能覆盖已提交检查点");
        assertEquals(1,tools.commits.get());assertEquals(0,tools.prOpened.get());
        engine.apply(current,Decision.block("head_changed","fixture: external branch change"));
        resumed=engine.resumeTask(id,repo.get(id).version,"fixture");
      }
      branch.remove("src/external.js");
      engine.apply(resumed,(Decision)dev.invoke(engine,repo.get(id)));
      assertEquals(Status.QA,repo.get(id).st());assertEquals(1,tools.commits.get());assertEquals(1,tools.prOpened.get());
    }finally{field.set(engine,original);}
  }
  @Test void concurrentResumeRequestsStartOnlyOneRecoveryAttempt() throws Exception {
    var stopped=qaBlocked(false);String id=stopped.id;int version=stopped.version;
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var go=new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Boolean> attempt=()->{go.await();try{engine.resumeTask(id,version,"fixture");return true;}catch(IllegalArgumentException expected){return false;}};
      var a=pool.submit(attempt);var b=pool.submit(attempt);go.countDown();
      assertNotEquals(a.get(),b.get());
    }
    assertEquals(1,db.count("SELECT COUNT(*) FROM task_event WHERE task_id=? AND event_type='TASK_RESUMED'",id));
    assertEquals(1,repo.get(id).context.recoveryGeneration);
    assertEquals(1,repo.get(id).context.reworkQuota.round);assertEquals(stopped.loopCount,repo.get(id).context.reworkQuota.qaBase);
  }
}
