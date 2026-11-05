package com.x7x24.it;
import com.x7x24.workflow.*;
import com.x7x24.llm.MockProvider;
import com.x7x24.util.*;
import com.x7x24.tenant.Tenant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
import static org.junit.jupiter.api.Assertions.*;
@Import(TypedWorkflowIT.FixtureRegistry.class)
class WorkflowCandidateIT extends Harness {
 @Autowired CandidateService candidates;@Autowired WorkflowStore store;
 @BeforeEach void scope(){Tenant.set(Tenant.LEGACY);}@AfterEach void clear(){Tenant.clear();}
 String task(){String id=engine.ingest(new com.x7x24.engine.Engine.Ingest("sandbox","candidate fixture "+U.uuid(),"synthetic goal","fixture",U.uuid(),null,false,null,"sandbox-default","",null)).taskId();db.update("UPDATE task SET pipeline_version='v3',next_run_at=NULL WHERE id=?",id);return id;}
 String artifact(String task,ArtifactType type,String body){var t=repo.get(task);var a=new ArtifactVersion(U.uuid(),t.workspaceId,t.projectId,task,type,1,Canonical.digest(Json.tree(body)),body,"FIXTURE",null,null,"base",List.of());store.publish(a,null,null);store.validate(new Validation(a.id(),"fixture","schema-v1",ValidationResult.PASS,a.contentDigest(),"fixture",clk.iso()));return a.id();}
 Map<String,String> inputs(String t){return Map.of("requirements",artifact(t,ArtifactType.RequirementSpec,"{\"title\":\"fixture\",\"goal\":\"goal\"}"),"acceptance",artifact(t,ArtifactType.AcceptanceCriteria,"{\"criteria\":[\"fixture\"]}"),"repository",artifact(t,ArtifactType.RepoSnapshot,"{\"sha\":\"base\"}"),"scopeEvidence",artifact(t,ArtifactType.RepositoryProfile,"{\"features\":{\"requiresUiChange\":false,\"requiresApiChange\":false}}"));}
 @Test void deterministicCandidateIsIdempotentCompiledAndApprovalIsSeparate(){
  String t=task();var request=new CandidateService.Request(repo.get(t).version,"fixture-key-1",inputs(t),1,false);var c=candidates.generate(t,request);assertTrue(c.compiled().valid(),c.compiled().diagnostics().toString());assertEquals(c.id(),candidates.generate(t,request).id());
  candidates.accept(t,c.id(),repo.get(t).version,c.compiled().hash());assertEquals(0,db.count("SELECT COUNT(*) FROM node_run WHERE task_id=?",t));
  assertThrows(WorkflowStore.Blocked.class,()->store.activate(t,c.compiled().plan().revisionId(),c.compiled().hash(),repo.get(t).version));
  store.approve(t,c.compiled().hash(),"fixture",3600000);store.activate(t,c.compiled().plan().revisionId(),c.compiled().hash(),repo.get(t).version);
 }
 @Test void fakeLlmCannotInventExecutorOrOverwriteScopeBudget(){
  String t=task();var fake=new MockProvider("fake-planner",req->"{\"nodes\":[{\"id\":\"release\",\"logicalKey\":\"release\",\"definitionRef\":\"deploy.everywhere@99\",\"bindings\":[],\"config\":{},\"roles\":{}}],\"controls\":[]}");
  var service=new CandidateService(store,db,clk,new CandidatePlanner.Llm(fake));var c=service.generate(t,new CandidateService.Request(repo.get(t).version,"fixture-malicious",inputs(t),1,false));assertFalse(c.compiled().valid());assertTrue(c.compiled().diagnostics().stream().anyMatch(d->d.code().equals("CAPABILITY_UNAVAILABLE")));
  assertThrows(WorkflowStore.Blocked.class,()->service.accept(t,c.id(),repo.get(t).version,c.compiled().hash()));assertEquals(0,db.count("SELECT COUNT(*) FROM node_run WHERE task_id=?",t));
  assertThrows(WorkflowStore.Blocked.class,()->service.generate(t,new CandidateService.Request(repo.get(t).version,"fixture-budget",inputs(t),100000,false)));
 }
 @Test void staleEditorAndCrossWorkspaceCandidateAreRejected(){
  String t=task();var c=candidates.generate(t,new CandidateService.Request(repo.get(t).version,"fixture-stale",inputs(t),1,false));db.update("UPDATE task SET version=version+1 WHERE id=?",t);
  assertEquals("VERSION_CONFLICT",assertThrows(WorkflowStore.Blocked.class,()->candidates.accept(t,c.id(),c.taskVersion(),c.compiled().hash())).code);
  Tenant.run("different",()->assertThrows(WorkflowStore.Blocked.class,()->candidates.get(t,c.id())));
 }
}
