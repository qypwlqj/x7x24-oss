package com.x7x24.it;
import com.x7x24.engine.*;
import com.x7x24.db.Db;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import com.x7x24.workflow.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
import static org.junit.jupiter.api.Assertions.*;
/** Synthetic contract/runtime tests. No GitHub, models, SMTP, deployment or live runner. */
@org.springframework.context.annotation.Import(TypedWorkflowIT.FixtureRegistry.class)
class TypedWorkflowIT extends Harness {
 @org.springframework.boot.test.context.TestConfiguration static class FixtureRegistry {
  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
  DefinitionRegistry syntheticCapabilities(){return new DefinitionRegistry(null){@Override public Map<String,Definition> definitions(){return Definitions.standard();}};}
 }
 @org.junit.jupiter.api.BeforeEach void scope(){Tenant.set(Tenant.LEGACY);}
 @org.junit.jupiter.api.AfterEach void clearScope(){Tenant.clear();}
 @Autowired WorkflowStore store;@Autowired WorkflowScheduler scheduler;@Autowired ExecutionLeases leases;
 String task(){String id=engine.ingest(new com.x7x24.engine.Engine.Ingest("sandbox","typed fixture "+U.uuid(),"synthetic contract test","fixture",U.uuid(),null,false,null,"sandbox-default","",null)).taskId();db.update("UPDATE task SET pipeline_version='v3',next_run_at=NULL WHERE id=?",id);return id;}
 ArtifactVersion artifact(String task,ArtifactType type,String content){var t=repo.get(task);var a=new ArtifactVersion(U.uuid(),t.workspaceId,t.projectId,task,type,1,Canonical.digest(Json.tree(content)),content,"FIXTURE",null,null,"base",List.of());store.publish(a,null,null);store.validate(new Validation(a.id(),"fixture-schema","schema-v1",ValidationResult.PASS,a.contentDigest(),"fixture:"+a.id(),clk.iso()));return a;}
 Binding pin(String port,String version){return new Binding(port,port,0,version,null,null);}
 Binding up(String port,String node,String out){return new Binding(port,port,0,null,node,out);}
 Plan plan(String task,Node... nodes){var t=repo.get(task);return new Plan(3,U.uuid(),t.workspaceId,t.projectId,task,1,null,"base","policy",1,List.of(nodes),List.of(),Map.of("requiresUiChange",true,"requiresApiChange",false));}
 void accept(Plan p){var c=store.save(p,repo.get(p.taskId()).version);assertTrue(c.valid(),c.diagnostics().toString());store.approve(p.taskId(),c.hash(),"fixture-operator",3600000);store.activate(p.taskId(),p.revisionId(),c.hash(),repo.get(p.taskId()).version);}
 @Test void stableSerialHandoffPinsInputsAndSeparatesApproval(){
  String task=task();var ac=artifact(task,ArtifactType.AcceptanceCriteria,"{\"criteria\":[\"returns filtered list\"]}");var repo=artifact(task,ArtifactType.RepoSnapshot,"{\"sha\":\"base\"}");
  var requirements=new Node("requirements","requirements","document.requirements@1",Map.of(),List.of(),Map.of());
  var design=new Node("design","design","document.design@1",Map.of(),List.of(up("requirements","requirements","requirements")),Map.of());
  var dev=new Node("dev","dev","code.implement@1",Map.of(),List.of(up("requirements","requirements","requirements"),up("design","design","design"),pin("acceptance",ac.id()),pin("repository",repo.id()),pin("scopeEvidence",artifact(task,ArtifactType.RepositoryProfile,"{\"features\":{\"requiresUiChange\":true,\"requiresApiChange\":false}}").id())),Map.of());
  var p=plan(task,dev,design,requirements);accept(p);
  assertThrows(WorkflowStore.Blocked.class,()->scheduler.start(task,"dev"));
  var first=scheduler.start(task,"requirements");assertThrows(WorkflowStore.Blocked.class,()->scheduler.start(task,"requirements"));
  assertThrows(WorkflowStore.Blocked.class,()->scheduler.complete(task,first,Map.of(),"FIXTURE"));
  scheduler.complete(task,first,Map.of("requirements","{\"title\":\"List\",\"goal\":\"Filter records\"}"),"FIXTURE");
  var second=scheduler.start(task,"design");scheduler.complete(task,second,Map.of("design","{\"notes\":\"Approved existing design\"}"),"FIXTURE");
  assertEquals("WAITING_APPROVAL",assertThrows(WorkflowStore.Blocked.class,()->scheduler.start(task,"dev")).code);
  var row=db.row("SELECT body FROM artifact_version WHERE producer_node='design' AND task_id=?",task);var designArtifact=Json.parse(Db.str(row.get("body")),ArtifactVersion.class);
  store.approve(task,WorkflowStore.artifactApprovalDigest(designArtifact),"fixture-operator",3600000);
  var third=scheduler.start(task,"dev");assertTrue(third.inputs().stream().anyMatch(i->i.versionId().equals(designArtifact.id())));
  assertTrue(third.inputs().stream().anyMatch(i->i.port().equals("failureEvidence")&&i.versionId().equals("ABSENT")));
  var stale=new Run(third.id(),third.nodeId(),third.attempt(),third.state(),third.fingerprint(),third.epoch()+1,third.owner(),third.inputs());
  assertThrows(WorkflowStore.Blocked.class,()->scheduler.complete(task,stale,Map.of("patch","{}"),"FIXTURE"));
  scheduler.complete(task,third,Map.of("patch","{\"baseSha\":\"base\",\"change\":{\"files\":[{\"path\":\"src/a.ts\",\"content\":\"fixture\"}]}}"),"FIXTURE");
  assertEquals(3,db.count("SELECT COUNT(*) FROM node_run WHERE task_id=? AND state='SUCCEEDED'",task));
  assertEquals(5,db.count("SELECT COUNT(*) FROM artifact_lineage l JOIN artifact_version a ON a.id=l.output_version_id WHERE a.task_id=? AND a.producer_node='dev'",task));
  var source=db.row("SELECT id FROM artifact_version WHERE task_id=? AND producer_node='requirements'",task);
  assertEquals(Set.of("design","dev"),store.invalidate(task,p.revisionId(),Db.str(source.get("id")),"new requirements"));
  assertEquals("SUCCEEDED",db.row("SELECT state FROM node_instance WHERE plan_revision_id=? AND id='requirements'",p.revisionId()).get("state"));
 }
 @Test void frontendBackendAndFullstackPinnedInputsExecuteAsDistinctSerialContracts(){
  for(var facts:List.of(Map.of("requiresUiChange",true,"requiresApiChange",false),Map.of("requiresUiChange",false,"requiresApiChange",true),Map.of("requiresUiChange",true,"requiresApiChange",true))){
   String task=task();var requirement=artifact(task,ArtifactType.RequirementSpec,"{\"title\":\"fixture\",\"goal\":\"multi-source input\"}");var ac=artifact(task,ArtifactType.AcceptanceCriteria,"{\"criteria\":[\"synthetic contract handoff\"]}");var repository=artifact(task,ArtifactType.RepoSnapshot,"{\"sha\":\"base\"}");
   var bindings=new ArrayList<>(List.of(pin("requirements",requirement.id()),pin("acceptance",ac.id()),pin("repository",repository.id()),pin("scopeEvidence",artifact(task,ArtifactType.RepositoryProfile,Json.str(Map.of("features",facts))).id())));
   if(facts.get("requiresUiChange")){var design=artifact(task,ArtifactType.DesignSpec,"{\"notes\":\"existing design\"}");store.approve(task,WorkflowStore.artifactApprovalDigest(design),"fixture",3600000);bindings.add(pin("design",design.id()));}
   if(facts.get("requiresApiChange")){var api=artifact(task,ArtifactType.APIContract,"{\"schema\":{\"response\":\"unchanged\"}}");bindings.add(pin("apiContract",api.id()));}
   var node=new Node("implement","implement","code.implement@1",Map.of(),bindings,Map.of());var base=plan(task,node);var p=new Plan(3,base.revisionId(),base.workspaceId(),base.projectId(),task,1,null,"base","policy",1,List.of(node),List.of(),facts);accept(p);
   var run=scheduler.start(task,"implement");assertEquals(bindings.size(),run.inputs().stream().filter(in->!in.versionId().equals("ABSENT")).count());
   scheduler.complete(task,run,Map.of("patch","{\"baseSha\":\"base\",\"change\":{\"files\":[{\"path\":\"src/fixture.ts\",\"content\":\"synthetic fixture\"}]}}"),"FIXTURE");
   assertEquals(1,db.count("SELECT COUNT(*) FROM node_run WHERE task_id=? AND state='SUCCEEDED'",task));
  }
 }
 @Test void revokedApprovalBlocksStartAndImmutableArtifactsCannotBeOverwritten(){
  String task=task();var p=plan(task,new Node("human","human","document.requirements@1",Map.of(),List.of(),Map.of()));accept(p);
  db.update("UPDATE approval_record SET revoked_at=? WHERE task_id=?",clk.iso(),task);assertEquals("WAITING_APPROVAL",assertThrows(WorkflowStore.Blocked.class,()->scheduler.start(task,"human")).code);
  var a=artifact(task,ArtifactType.RequirementSpec,"{\"title\":\"x\",\"goal\":\"y\"}");assertThrows(Exception.class,()->db.update("UPDATE artifact_version SET content_digest='different' WHERE id=?",a.id()));
 }
 @Test void identicalContentOnDifferentArtifactVersionDoesNotReuseInputApproval(){
  String task=task();var requirement=artifact(task,ArtifactType.RequirementSpec,"{\"title\":\"fixture\",\"goal\":\"goal\"}");var ac=artifact(task,ArtifactType.AcceptanceCriteria,"{\"criteria\":[\"fixture\"]}");var repository=artifact(task,ArtifactType.RepoSnapshot,"{\"sha\":\"base\"}");var scope=artifact(task,ArtifactType.RepositoryProfile,"{\"features\":{\"requiresUiChange\":true,\"requiresApiChange\":false}}");var old=artifact(task,ArtifactType.DesignSpec,"{\"notes\":\"same content\"}");var changed=artifact(task,ArtifactType.DesignSpec,old.content());
  store.approve(task,WorkflowStore.artifactApprovalDigest(old),"fixture",3600000);
  var node=new Node("implement","implement","code.implement@1",Map.of(),List.of(pin("requirements",requirement.id()),pin("acceptance",ac.id()),pin("repository",repository.id()),pin("scopeEvidence",scope.id()),pin("design",changed.id())),Map.of());accept(plan(task,node));
  assertEquals("WAITING_APPROVAL",assertThrows(WorkflowStore.Blocked.class,()->scheduler.start(task,"implement")).code);
 }
 @Test void revokingInputApprovalDuringExecutionRejectsPublication(){
  String task=task();var requirement=artifact(task,ArtifactType.RequirementSpec,"{\"title\":\"fixture\",\"goal\":\"goal\"}");var ac=artifact(task,ArtifactType.AcceptanceCriteria,"{\"criteria\":[\"fixture\"]}");var repository=artifact(task,ArtifactType.RepoSnapshot,"{\"sha\":\"base\"}");var scope=artifact(task,ArtifactType.RepositoryProfile,"{\"features\":{\"requiresUiChange\":true,\"requiresApiChange\":false}}");var design=artifact(task,ArtifactType.DesignSpec,"{\"notes\":\"fixed design\"}");
  String approval=store.approve(task,WorkflowStore.artifactApprovalDigest(design),"fixture",3600000);
  var node=new Node("implement","implement","code.implement@1",Map.of(),List.of(pin("requirements",requirement.id()),pin("acceptance",ac.id()),pin("repository",repository.id()),pin("scopeEvidence",scope.id()),pin("design",design.id())),Map.of());accept(plan(task,node));var run=scheduler.start(task,"implement");
  db.update("UPDATE approval_record SET revoked_at=? WHERE id=?",clk.iso(),approval);
  assertEquals("WAITING_APPROVAL",assertThrows(WorkflowStore.Blocked.class,()->scheduler.complete(task,run,Map.of("patch","{\"baseSha\":\"base\",\"change\":{\"files\":[{\"path\":\"docs/a.md\",\"content\":\"fixture\"}]}}"),"FIXTURE")).code);
  assertEquals(0,db.count("SELECT COUNT(*) FROM artifact_version WHERE producer_run=?",run.id()));scheduler.fail(task,run,new IllegalStateException("fixture stopped"));
 }
 @Test void policySnapshotIsServerDerivedAndChangesBlockAnApprovedPlan(){
  String task=task();var p=plan(task,new Node("human","human","document.requirements@1",Map.of(),List.of(),Map.of()));
  var compiled=store.compile(p);assertNotEquals(p.policyDigest(),compiled.plan().policyDigest());accept(p);
  var changed=Json.M.valueToTree(cfg.get());((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("release")).put("maxFiles",1);cfg.update(changed);
  assertEquals("POLICY_CHANGED",assertThrows(WorkflowStore.Blocked.class,()->scheduler.start(task,"human")).code);
 }
 @Test void revokedApprovalAndExpiredLeaseCannotAdvanceOrRestart(){
  String task=task();var p=plan(task,new Node("human","human","document.requirements@1",Map.of(),List.of(),Map.of()));accept(p);var run=scheduler.start(task,"human");
  clk.advance(61000);assertThrows(WorkflowStore.Blocked.class,()->scheduler.complete(task,run,Map.of("requirements","{}"),"FIXTURE"));
  assertEquals("RECOVERY_REQUIRED",assertThrows(WorkflowStore.Blocked.class,()->leases.claim("task",task,"replacement")).code);
  assertThrows(WorkflowStore.Blocked.class,()->scheduler.start(task,"human"));
  scheduler.watchdog();scheduler.recoverStopped(task,run.id(),"fixture-operator","fixture:old-dispatcher-terminated");
  var replacement=scheduler.start(task,"human");assertTrue(replacement.epoch()>run.epoch());
  assertThrows(WorkflowStore.Blocked.class,()->scheduler.complete(task,run,Map.of("requirements","{\"title\":\"old\",\"goal\":\"late output\"}"),"FIXTURE"));scheduler.fail(task,replacement,new IllegalStateException("fixture stopped"));
 }
 @Test void oldRoutesRemainOldAndStaleEditsDoNotOverwrite(){
  String task=task();db.update("UPDATE task SET pipeline_version='v2' WHERE id=?",task);var p=plan(task,new Node("a","a","document.requirements@1",Map.of(),List.of(),Map.of()));
  assertEquals("LEGACY_ROUTE",assertThrows(WorkflowStore.Blocked.class,()->store.save(p,repo.get(task).version)).code);
  db.update("UPDATE task SET pipeline_version='v3' WHERE id=?",task);
  assertEquals("VERSION_CONFLICT",assertThrows(WorkflowStore.Blocked.class,()->store.save(p,-1)).code);
  assertEquals(0,db.count("SELECT COUNT(*) FROM workflow_plan_revision WHERE task_id=?",task));
 }
 @Test void unknownValidationAndCrossTenantReferencesNeverPass(){
  String task=task();var a=artifact(task,ArtifactType.RequirementSpec,"{\"title\":\"x\",\"goal\":\"y\"}");clk.advance(1);store.validate(new Validation(a.id(),"fixture","schema-v1",ValidationResult.UNKNOWN,a.contentDigest(),null,clk.iso()));assertFalse(store.validated(a));
  Tenant.run("other-workspace",()->assertNull(store.artifact(a.id())));
  assertThrows(WorkflowStore.Blocked.class,()->store.validate(new Validation(a.id(),"fixture","schema-v1",ValidationResult.PASS,"old-digest","fixture",clk.iso())));
 }
}
