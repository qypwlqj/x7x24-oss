package com.x7x24.learning;

import com.x7x24.agents.Models;
import com.x7x24.engine.*;
import com.x7x24.it.Harness;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.*;
import static com.x7x24.learning.Learning.*;
import static org.junit.jupiter.api.Assertions.*;

@Import(LearningLoopIT.Fixtures.class)
class LearningLoopIT extends Harness {
  @Autowired LearningService learning;@Autowired EpisodeProjector projector;@Autowired LearningPolicies policies;
  @Autowired LearningStore store;@Autowired FixtureModel model;
  @Autowired com.x7x24.pipeline.PipelineService pipes;
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("x7x24.learning.enabled",()->"true");p.add("x7x24.learning.project-delay-ms",()->"3600000");
    p.add("spring.datasource.url",()->"jdbc:h2:mem:x7learning;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1");
  }
  @TestConfiguration static class Fixtures {
    @Bean @Primary FixtureModel fixtureModel(){return new FixtureModel();}
    @Bean @Primary Evaluator fixtureEvaluator(){return (c,files,scope)->{
      // This is an explicit test double, never a production claim of project test execution.
      var r=new ReplayEvaluator().evaluate(c,files,"FILE_ASSERTIONS");
      return new Evaluation(r.passed(),scope,r.evidenceDigest(),r.failures(),r.millis());
    };}
  }
  static class FixtureModel implements Model {
    @Autowired LearningModel boundary;
    final List<String> prompts=new ArrayList<>();boolean failCandidate,changeConfig;int calls;
    public Completion call(String project,String system,String prompt){
      prompts.add(prompt);calls++;
      if(system.contains("改进器"))return new Completion("{\"candidates\":[{\"instructions\":\"先核对输入类型，再修复实现并运行检查\",\"hypothesis\":\"输入类型诊断可以修复该类故障\"}]}","fixture",boundary.fingerprint(project),20,.001);
      boolean candidate=system.contains("先核对输入类型");
      return new Completion("{\"files\":{\"src/value.json\":\"{\\\"value\\\":"+(candidate&&!failCandidate?1:0)+"}\"}}","fixture",changeConfig?"changed":boundary.fingerprint(project),10,.001);
    }
  }
  @BeforeEach void tenant(){Tenant.set(Tenant.LEGACY);model.prompts.clear();model.calls=0;model.failCandidate=false;model.changeConfig=false;}
  @AfterEach void clear(){Tenant.clear();}
  String task(){return engine.ingest(new Engine.Ingest("sandbox","learning "+U.uuid(),"synthetic failure","fixture",U.uuid(),null,false,null,"sandbox-default","",null)).taskId();}
  Case testCase(String id,String family,Split split){return new Case(id,id,family,split,"fixed-base","修复值","value error",Map.of("src/value.json","{\"value\":0}"),List.of("src/value.json"),List.of(new Check("src/value.json","JSON_EQUALS","{\"value\":1}")));}
  Suite suite(String scope,int n){var cases=new ArrayList<Case>();cases.add(testCase("d-"+U.uuid(),"discovery-"+U.uuid(),Split.DISCOVERY));for(Split split:List.of(Split.VALIDATION,Split.TEST))for(int i=0;i<n;i++)cases.add(testCase(split.name()+i+U.uuid(),split.name()+U.uuid(),split));return new Suite("synthetic suite",scope,cases);}
  String suite(Suite suite){return learning.createSuite("sandbox-default",suite).get("id").toString();}
  String candidate(String suite){return learning.propose("sandbox-default",suite,1).getFirst().get("id").toString();}
  Models.Ctx ctx(Task t){return new Models.Ctx(t,t.context.projectSnapshot,t.context.issue,null,null,null,null,"failure",false,null,Models.ExecutionMode.REPAIR,null);}

  @Test void projectionIsIdempotentAndDoesNotPromoteExecutionSuccessToTestSuccess(){
    String id=task();db.update("INSERT INTO stage_execution(id,task_id,iteration_seq,stage,attempt,operation_key,state,input,output,started_at,ended_at) VALUES(?,?,1,'DEV',1,?,'SUCCEEDED','{}','{\"outcome\":\"PASSED\"}',?,?)",U.uuid(),id,U.uuid(),clk.iso(),clk.iso());
    db.update("INSERT INTO stage_execution(id,task_id,iteration_seq,stage,attempt,operation_key,state,input,output,started_at,ended_at) VALUES(?,?,1,'QA',1,?,'SUCCEEDED','{}','{\"outcome\":\"FAILED\",\"edge\":\"qa_loop\"}',?,?)",U.uuid(),id,U.uuid(),clk.iso(),clk.iso());
    projector.project("sandbox-default");assertEquals(0,projector.project("sandbox-default").get("added"));
    var rows=db.rows("SELECT body FROM learning_episode WHERE task_id=?",id);assertEquals(2,rows.size());
    var dev=rows.stream().map(LearningStore::body).filter(b->"DEV".equals(b.path("node").asText())).findFirst().orElseThrow();assertEquals("UNKNOWN",dev.path("testOutcome").asText());assertEquals("INCOMPLETE",dev.path("replayability").asText());
    var qa=rows.stream().map(LearningStore::body).filter(b->"QA".equals(b.path("node").asText())).findFirst().orElseThrow();assertEquals("FAIL",qa.path("testOutcome").asText());assertEquals("SUCCEEDED",qa.path("executionOutcome").asText());
    assertThrows(RuntimeException.class,()->db.update("UPDATE learning_episode SET body='{}' WHERE task_id=?",id));
  }
  @Test void suiteRejectsLeakageAndChangesToTests(){
    var good=suite("FILE_ASSERTIONS",1);var cases=new ArrayList<>(good.cases());Case first=cases.getFirst();
    cases.set(1,new Case("other",first.rootTaskId(),"other",Split.VALIDATION,"base","goal","error",first.files(),first.editablePaths(),first.checks()));
    assertThrows(IllegalArgumentException.class,()->suite(new Suite("leaky","FILE_ASSERTIONS",cases)));
    var bad=new Case("bad","bad","bad",Split.DISCOVERY,"base","goal","error",Map.of("src/value.test.js","test"),List.of("src/value.test.js"),List.of(new Check("src/value.test.js","CONTAINS","test")));
    var finalCases=new ArrayList<>(good.cases());finalCases.set(0,bad);
    assertThrows(IllegalArgumentException.class,()->suite(new Suite("test tampering","FILE_ASSERTIONS",finalCases)));
  }
  @Test void fileAssertionsAndSmallSamplesNeverAuthorizeRollout(){
    String suite=suite(suite("FILE_ASSERTIONS",1)),policy=candidate(suite);
    var result=LearningStore.body(learning.evaluate("sandbox-default",policy,Split.VALIDATION));
    assertFalse(result.path("eligible").asBoolean());assertEquals("INSUFFICIENT_SCOPE",result.path("decision").asText());
    assertThrows(IllegalArgumentException.class,()->learning.evaluate("sandbox-default",policy,Split.TEST));
    assertThrows(IllegalArgumentException.class,()->learning.release("sandbox-default",policy,"CANARY",5,"fixture"));
    learning.release("sandbox-default",policy,"SHADOW",0,"仅隔离观察");
  }
  @Test void pairedAuditStableAssignmentAndRevocationFormACompleteLoop(){
    String suite=suite(suite("PROJECT_TESTS",20)),policy=candidate(suite);
    assertTrue(LearningStore.body(learning.evaluate("sandbox-default",policy,Split.VALIDATION)).path("eligible").asBoolean());
    var result=learning.evaluate("sandbox-default",policy,Split.TEST);assertTrue(LearningStore.body(result).path("eligible").asBoolean());
    assertEquals(20,LearningStore.body(result).path("wins").asInt());assertFalse(LearningStore.body(result).path("recursionVerified").asBoolean());
    int calls=model.calls;assertEquals(result,learning.evaluate("sandbox-default",policy,Split.TEST));assertEquals(calls,model.calls);
    assertThrows(IllegalArgumentException.class,()->learning.propose("sandbox-default",suite,1));
    var release=learning.release("sandbox-default",policy,"CANARY",10,"已完成合成对照");
    String selected=null;Task chosen=null;
    for(int i=0;i<200;i++){String id=task();if(LearningPolicies.bucket(id,release.get("id").toString())<10){selected=id;chosen=repo.get(id);break;}}
    assertNotNull(selected);policies.attach(ctx(chosen));assertEquals(policy,chosen.context.extra.get("learningPolicy"));
    assertTrue(LearningPolicies.instructions(chosen).contains("先核对输入类型"));
    learning.release("sandbox-default",policy,"SHADOW",0,"继续观察");policies.attach(ctx(chosen));assertEquals(policy,chosen.context.extra.get("learningPolicy"));
    learning.release("sandbox-default",policy,"REVOKED",0,"发现新的反例");Task fixed=chosen;
    assertThrows(IllegalArgumentException.class,()->policies.attach(ctx(fixed)));
    assertThrows(IllegalArgumentException.class,()->learning.release("sandbox-default",policy,"CANARY",5,"重新启用"));
  }
  @Test void configurationChangesAndFailuresCannotBePublished(){
    String suite=suite(suite("PROJECT_TESTS",20)),policy=candidate(suite);model.changeConfig=true;
    var result=LearningStore.body(learning.evaluate("sandbox-default",policy,Split.VALIDATION));assertFalse(result.path("eligible").asBoolean());assertTrue(result.path("errors").asInt()>0);
    assertThrows(IllegalArgumentException.class,()->learning.release("sandbox-default",policy,"CANARY",5,"fixture"));
  }
  @Test void testChecksAreNotExposedToTheOptimizerOrOverview(){
    Suite original=suite("FILE_ASSERTIONS",1);var cases=new ArrayList<>(original.cases());Case test=cases.getLast();
    cases.set(cases.size()-1,new Case(test.id(),test.rootTaskId(),test.family(),test.split(),test.baseSha(),test.goal(),test.failure(),test.files(),test.editablePaths(),List.of(new Check("src/value.json","EXACT","SEALED_EXPECTATION"))));
    String s=suite(new Suite("sealed","FILE_ASSERTIONS",cases));candidate(s);
    assertTrue(model.prompts.stream().noneMatch(p->p.contains("SEALED_EXPECTATION")));
    assertFalse(Json.str(learning.overview("sandbox-default")).contains("SEALED_EXPECTATION"));
  }
  @Test void crossWorkspaceReferencesFailClosedAndProtectionStopsExperiments(){
    String s=suite(suite("FILE_ASSERTIONS",1));Tenant.run("unrelated",()->{
      assertThrows(IllegalArgumentException.class,()->learning.propose("sandbox-default",s,1));assertThrows(IllegalArgumentException.class,()->learning.overview("sandbox-default"));
    });
    kill.activate("project:sandbox-default","HALT","manual","fixture",U.uuid());
    assertThrows(com.x7x24.gov.KillSwitch.Blocked.class,()->learning.propose("sandbox-default",s,1));assertEquals(0,model.calls);
  }
  @Test void actualDispatchRecordsPolicyAndClearsInstructionsWhenDisabled(){
    Task t=repo.get(task());var base=new com.x7x24.agents.LlmAgents((com.x7x24.llm.LlmProvider)null,null,cfg){
      @Override public Models.CodeChange code(Models.Ctx c){return new Models.CodeChange(List.of(),"fixture","fixture","fixture");}
    };
    policies.wrap(base).code(ctx(t));
    var row=db.row("SELECT body FROM learning_episode WHERE task_id=? AND source_kind='POLICY_USE'",t.id);
    assertNotNull(row);assertEquals(BASELINE,LearningStore.body(row).path("policyId").asText());
    var outcome=db.row("SELECT body FROM learning_outcome WHERE task_id=?",t.id);assertEquals("UNKNOWN",LearningStore.body(outcome).path("testOutcome").asText());
    t.context.extra.put("learningInstructions","stale");store.enabled=false;
    try{policies.attach(ctx(t));assertFalse(t.context.extra.containsKey("learningInstructions"));}finally{store.enabled=true;}
  }
  @Test void paidAndFailedCallsAreMeteredAndUnknownUsageRetainsItsReservation(){
    var props=new X7Props();props.llm.model="fixture-model";props.llm.apiKey="synthetic-key";props.llm.baseUrl="https://models.invalid";props.llm.maxRetries=0;
    var failure=new java.util.concurrent.atomic.AtomicReference<RuntimeException>();var calls=new java.util.concurrent.atomic.AtomicInteger();
    var router=new com.x7x24.llm.ModelRouter(props,null,(p,key,m,temp,persona)->new com.x7x24.llm.LlmProvider(){
      public String model(){return m.id;}public Response chat(Request request){calls.incrementAndGet();if(failure.get()!=null)throw failure.get();return new Response("{}",m.id,request.promptVersion(),10,.02);}
    });
    var boundary=new LearningModel(store,router,budget,cfg,kill,pipes,lines);boundary.modelEnabled=true;boundary.maxCallUsd=1;boundary.dailyUsd=2;
    var response=boundary.call("sandbox-default","system","prompt");assertEquals(.02,response.usd(),1e-9);
    failure.set(new com.x7x24.llm.LlmProvider.OutputException("fixture","fixture-model",20,.03));
    assertThrows(com.x7x24.llm.LlmProvider.OutputException.class,()->boundary.call("sandbox-default","system","prompt"));
    assertEquals(.05,budget.projectDaily("sandbox-default").usd(),1e-9);
    failure.set(new IllegalStateException("unknown provider usage"));
    assertThrows(RuntimeException.class,()->boundary.call("sandbox-default","system","prompt"));
    assertTrue(db.count("SELECT COUNT(*) FROM learning_call WHERE workspace_id=? AND state='UNKNOWN' AND reserved_usd>0",Tenant.LEGACY)>0);
    int before=calls.get();boundary.dailyUsd=.01;assertThrows(IllegalArgumentException.class,()->boundary.call("sandbox-default","system","prompt"));assertEquals(before,calls.get());
  }
  @Test void viewerCannotInvokeTheLearningControlPlane(){
    var request=new org.springframework.mock.web.MockHttpServletRequest();
    request.setAttribute(com.x7x24.tenant.WorkspaceFilter.ATTR,new com.x7x24.tenant.WorkspaceService.Ws(Tenant.LEGACY,"fixture","fixture","personal","viewer","active"));
    var api=new LearningApi(learning,projector);
    assertThrows(com.x7x24.tenant.WorkspaceService.Forbidden.class,()->api.propose("sandbox-default",new LearningApi.Proposal("unknown",1),request));
    assertThrows(com.x7x24.tenant.WorkspaceService.Forbidden.class,()->api.release("sandbox-default","unknown",new LearningApi.ReleaseRequest("CANARY",5,"fixture"),request));
    assertEquals(0,model.calls);
  }
}
