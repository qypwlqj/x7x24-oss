package com.x7x24.it;
import com.x7x24.agents.*;
import com.x7x24.db.Db;
import com.x7x24.llm.*;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import com.x7x24.workflow.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
import static org.junit.jupiter.api.Assertions.*;
/** Real runtime/old adapter integration with a fake provider, never a real LLM or GitHub. */
@Import({TypedLegacyRuntimeIT.Fixture.class,TypedWorkflowIT.FixtureRegistry.class})
class TypedLegacyRuntimeIT extends Harness {
 @org.springframework.test.context.DynamicPropertySource static void fakeModelMetadata(org.springframework.test.context.DynamicPropertyRegistry r){r.add("x7x24.llm.model",()->"fixture-coder");}
 static final List<LlmProvider.Request> requests=new ArrayList<>();
 static boolean rejectedCandidate;
 @org.springframework.boot.test.context.TestConfiguration static class Fixture {
  @Bean @Primary Models.ExecAgents scriptedAgents(com.x7x24.gov.ConfigStore cfg){
   var provider=new MockProvider("fixture-coder",request->{requests.add(request);if(rejectedCandidate)return "{\"files\":[{\"path\":\"docs/unsafe.ts\",\"content\":\"console.log(process.env);\"},{\"path\":\"tests/unsafe.test.ts\",\"content\":\"export {};\"}],\"message\":\"feat: fixture\",\"summary\":\"fixture\"}";return "{\"files\":[{\"path\":\"docs/binding-proof.md\",\"content\":\"# fixture output\\n\"}],\"message\":\"docs: fixture\",\"summary\":\"synthetic adapter proof\"}";});
   return new LlmAgents(provider,provider,cfg);
  }
 }
 @Autowired WorkflowStore store;@Autowired com.x7x24.pipeline.PipelineService pipes;@Autowired com.x7x24.skills.SkillReleases releases;@Autowired ModelRouter modelRouter;@Autowired com.x7x24.engine.X7Props props;
 @BeforeEach void scope(){Tenant.set(Tenant.LEGACY);requests.clear();rejectedCandidate=false;modelRouter.resetForTest(ModelRouter.fromEnv(props));}
 @AfterEach void clear(){Tenant.clear();}
 ArtifactVersion artifact(String task,ArtifactType type,String content){var t=repo.get(task);var a=new ArtifactVersion(U.uuid(),t.workspaceId,t.projectId,task,type,1,Canonical.digest(Json.tree(content)),content,"FIXTURE",null,null,"a".repeat(40),List.of());store.publish(a,null,null);store.validate(new Validation(a.id(),"fixture-schema","schema-v1",ValidationResult.PASS,a.contentDigest(),"fixture:"+a.id(),clk.iso()));return a;}
 Binding pin(String port,ArtifactVersion a){return new Binding(port,port,0,a.id(),null,null);}
 record Prepared(String task,Plan plan,ArtifactVersion requirement,ArtifactVersion design) {}
 Prepared prepare(Map<String,Boolean> facts,Map<String,Object> config,String designText){
  String task=engine.ingest(new com.x7x24.engine.Engine.Ingest("sandbox","adapter fixture "+U.uuid(),"fixture","fixture",U.uuid(),null,false,null,"sandbox-default","",null)).taskId();db.update("UPDATE task SET pipeline_version='v3',next_run_at=NULL WHERE id=?",task);var t=repo.get(task);
  var requirement=artifact(task,ArtifactType.RequirementSpec,"{\"title\":\"BOUND_REQUIREMENT\",\"goal\":\"bounded fixture goal\"}");var acceptance=artifact(task,ArtifactType.AcceptanceCriteria,"{\"criteria\":[\"BOUND_ACCEPTANCE\"]}");var repository=artifact(task,ArtifactType.RepoSnapshot,"{\"sha\":\""+"a".repeat(40)+"\"}");var scope=artifact(task,ArtifactType.RepositoryProfile,Json.str(Map.of("features",facts)));var design=artifact(task,ArtifactType.DesignSpec,Json.str(Map.of("notes",designText)));store.approve(task,WorkflowStore.artifactApprovalDigest(design),"fixture",3600000);
  var bindings=new ArrayList<>(List.of(pin("requirements",requirement),pin("acceptance",acceptance),pin("repository",repository),pin("scopeEvidence",scope)));if(facts.get("requiresUiChange"))bindings.add(pin("design",design));if(facts.get("requiresApiChange"))bindings.add(pin("apiContract",artifact(task,ArtifactType.APIContract,"{\"schema\":{\"description\":\"BOUND_API\"}}")));
  var node=new Node("implementation","implementation","code.implement@1",config,bindings,Map.of());var plan=new Plan(3,U.uuid(),t.workspaceId,t.projectId,task,1,null,"a".repeat(40),"server-locks-this",1,List.of(node),List.of(),facts);var compiled=store.save(plan,t.version);assertTrue(compiled.valid(),compiled.diagnostics().toString());store.approve(task,compiled.hash(),"fixture",3600000);store.activate(task,plan.revisionId(),compiled.hash(),repo.get(task).version);return new Prepared(task,plan,requirement,design);
 }
 @Test void engineInvokesLegacyAdapterWithPinnedMultiSourceBindings(){
  var prepared=prepare(Map.of("requiresUiChange",true,"requiresApiChange",true),Map.of(),"BOUND_DESIGN");engine.tick();
  assertEquals(1,requests.size());var request=requests.getFirst();for(String marker:List.of("BOUND_REQUIREMENT","BOUND_ACCEPTANCE","BOUND_DESIGN","BOUND_API",prepared.requirement().id(),prepared.design().id(),prepared.plan().revisionId()))assertTrue(request.prompt().contains(marker),marker);
  assertEquals(1,db.count("SELECT COUNT(*) FROM node_run WHERE task_id=? AND state='SUCCEEDED'",prepared.task()));var output=db.row("SELECT body FROM artifact_version WHERE task_id=? AND producer_node='implementation'",prepared.task());assertNotNull(output);assertTrue(Db.str(output.get("body")).contains("LegacyJsonCodingExecutor@1"));assertEquals(0,db.count("SELECT COUNT(*) FROM tool_ops WHERE op_key LIKE ?",prepared.task()+":%"));assertEquals(1,db.count("SELECT COUNT(*) FROM context_manifest WHERE task_id=?",prepared.task()));
 }
 @Test void rejectedTypedPatchResumesTheSameNodeAndAcceptedPlanWithPinnedInputs(){
  var p=prepare(Map.of("requiresUiChange",true,"requiresApiChange",false),Map.of(),"BOUND_DESIGN");rejectedCandidate=true;engine.tick();
  var stopped=repo.get(p.task());assertTrue(com.x7x24.engine.RecoveryService.stopped(stopped));assertEquals("implementation",stopped.currentNode);
  assertEquals(1,db.count("SELECT COUNT(*) FROM node_run WHERE task_id=? AND state='FAILED'",p.task()));
  assertEquals(0,db.count("SELECT COUNT(*) FROM artifact_version WHERE task_id=? AND producer_node='implementation'",p.task()));
  stopped.loopCount=8;stopped.context.nodeLoops.put("implementation",5);stopped.context.reworkQuota.qaBase=2;assertTrue(repo.save(stopped));
  assertFalse(engine.recoveryInfo(p.task()).containsKey("rework"));
  rejectedCandidate=false;engine.resumeTask(p.task(),stopped.version,"fixture");engine.tick();
  var resumed=repo.get(p.task());assertEquals(8,resumed.loopCount);assertEquals(5,resumed.context.nodeLoops.get("implementation"));assertEquals(2,resumed.context.reworkQuota.qaBase);assertEquals(0,resumed.context.reworkQuota.round);assertNull(resumed.context.reworkQuota.pendingNode);
  assertEquals(1,db.count("SELECT COUNT(*) FROM node_run WHERE task_id=? AND state='SUCCEEDED'",p.task()));
  assertEquals(p.plan().revisionId(),db.row("SELECT active_plan_revision_id FROM task WHERE id=?",p.task()).get("active_plan_revision_id"));
  assertEquals(2,requests.size());assertNotNull(store.artifact(p.requirement().id()));assertNotNull(store.artifact(p.design().id()));
 }
 @Test void falseConditionalSkillIsRecordedButNotInjected(){
  var binding=new com.x7x24.skills.SkillResolver.Binding("conditional-ui","ws_legacy","sandbox-default",com.x7x24.skills.SkillResolver.Scope.NODE_INSTANCE,"implementation","maker","react-ts","1.0.0",com.x7x24.skills.SkillResolver.Enforcement.REQUIRED,"requiresApiChange",Map.of());
  var prepared=prepare(Map.of("requiresUiChange",true,"requiresApiChange",false),Map.of("skillBindings",List.of(binding)),"BOUND_DESIGN");engine.tick();assertEquals(1,requests.size());assertFalse(requests.getFirst().system().contains(com.x7x24.pipeline.Catalog.SKILLS.get("react-ts@1").instructions));assertTrue(Db.str(db.row("SELECT body FROM skill_evaluation WHERE task_id=?",prepared.task()).get("body")).contains("NOT_APPLICABLE"));
 }
 @Test void oversizedRequiredContextNeverCallsProvider(){
  var prepared=prepare(Map.of("requiresUiChange",true,"requiresApiChange",false),Map.of(),"large".repeat(20000));engine.tick();assertTrue(requests.isEmpty());assertTrue(Objects.toString(db.row("SELECT workflow_block_reason FROM task WHERE id=?",prepared.task()).get("workflow_block_reason")).contains("CONTEXT_BUDGET_EXCEEDED"));
 }
 @Test void revokedPinnedSkillBlocksBeforeProviderOrNodeDispatch(){
  var skill=new com.x7x24.pipeline.Pipeline.Skill();skill.id="fixture-revoke-"+U.uuid().substring(0,8);skill.instructions="fixture immutable instruction";pipes.putSkill(skill,"fixture",true);
  var binding=new com.x7x24.skills.SkillResolver.Binding("revoked","ws_legacy","sandbox-default",com.x7x24.skills.SkillResolver.Scope.NODE_INSTANCE,"implementation","maker",skill.id,skill.version+".0.0",com.x7x24.skills.SkillResolver.Enforcement.REQUIRED,null,Map.of());var prepared=prepare(Map.of("requiresUiChange",true,"requiresApiChange",false),Map.of("skillBindings",List.of(binding)),"BOUND_DESIGN");releases.revoke(skill.id+"@"+skill.version+".0.0","fixture restriction","fixture");engine.tick();assertTrue(requests.isEmpty());assertEquals(0,db.count("SELECT COUNT(*) FROM node_run WHERE task_id=?",prepared.task()));
 }
}
