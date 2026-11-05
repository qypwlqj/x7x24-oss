package com.x7x24.it;
import com.x7x24.workflow.*;
import com.x7x24.execution.*;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
import static org.junit.jupiter.api.Assertions.*;
@Import(TypedWorkflowIT.FixtureRegistry.class)
class LocalExecutorRuntimeIT extends Harness {
 @Autowired WorkflowStore store;@Autowired LocalCodingExecutor executor;
 @DynamicPropertySource static void local(DynamicPropertyRegistry r){r.add("x7x24.local-file-executor-enabled",()->"true");}
 @BeforeEach void scope(){Tenant.set(Tenant.LEGACY);}@AfterEach void clear(){Tenant.clear();}
 String artifact(String task,ArtifactType type,String content){var t=repo.get(task);var a=new ArtifactVersion(U.uuid(),t.workspaceId,t.projectId,task,type,1,Canonical.digest(Json.tree(content)),content,"FIXTURE",null,null,"base",List.of());store.publish(a,null,null);store.validate(new Validation(a.id(),"fixture","schema-v1",ValidationResult.PASS,a.contentDigest(),"fixture",clk.iso()));return a.id();}
 Binding pin(String port,String version){return new Binding(port,port,0,version,null,null);}
 @Test void typedRuntimeExecutesDurableLocalJobAndPublishesTruthfulReport(){
  String task=engine.ingest(new com.x7x24.engine.Engine.Ingest("sandbox","local runtime "+U.uuid(),"fixture","fixture",U.uuid(),null,false,null,"sandbox-default","",null)).taskId();db.update("UPDATE task SET pipeline_version='v3',next_run_at=NULL WHERE id=?",task);var t=repo.get(task);
  String repository=artifact(task,ArtifactType.RepoSnapshot,"{\"sha\":\"base\",\"files\":{\"src/value.txt\":\"old\"}}");String patch=artifact(task,ArtifactType.PatchSet,"{\"baseSha\":\"base\",\"change\":{\"files\":[{\"path\":\"src/value.txt\",\"content\":\"new\"}]}}");String ac=artifact(task,ArtifactType.AcceptanceCriteria,"{\"criteria\":[\"AC-1\"]}");
  var node=new Node("local-check","local-check","local.file-check@1",Map.of("checks",List.of(Map.of("path","src/value.txt","contains","new","acceptanceId","AC-1"))),List.of(pin("repository",repository),pin("patch",patch),pin("acceptance",ac)),Map.of());var p=new Plan(3,U.uuid(),t.workspaceId,t.projectId,task,1,null,"base","server-derived",0,List.of(node),List.of(),Map.of());var compiled=store.save(p,t.version);assertTrue(compiled.valid(),compiled.diagnostics().toString());store.approve(task,compiled.hash(),"fixture",3600000);store.activate(task,p.revisionId(),compiled.hash(),repo.get(task).version);
  engine.tick();
  var run=db.row("SELECT * FROM node_run WHERE task_id=?",task);assertEquals("SUCCEEDED",run.get("state"),Objects.toString(repo.get(task).executionState));String job=Objects.toString(run.get("executor_job_id"));var result=executor.exportResult(t.workspaceId,task,job);assertEquals(CodingExecutor.State.SUCCEEDED,result.state());assertEquals("new",result.files().get("src/value.txt"));assertEquals(3,executor.events(t.workspaceId,task,job,0).size());assertEquals(2,executor.events(t.workspaceId,task,job,1).size());
  var spec=Json.parse(Objects.toString(db.row("SELECT spec FROM coding_job WHERE id=?",job).get("spec")),CodingExecutor.JobSpec.class);assertEquals(job,executor.submit(spec).jobId());assertEquals(1,db.count("SELECT COUNT(*) FROM coding_job WHERE task_id=?",task));
  var changed=Json.M.valueToTree(spec);((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("networkPolicy","ALLOW");assertEquals("JOB_KEY_CONFLICT",assertThrows(WorkflowStore.Blocked.class,()->executor.submit(Json.convert(changed,CodingExecutor.JobSpec.class))).code);
  assertThrows(WorkflowStore.Blocked.class,()->executor.resume(t.workspaceId,task,job,result.checkpointRef()));
  var report=store.artifact(Objects.toString(db.row("SELECT id FROM artifact_version WHERE producer_run=?",run.get("id")).get("id")));var body=Json.tree(report.content());assertFalse(body.path("projectTestExecution").asBoolean(true));assertEquals("UNKNOWN",body.path("businessValidation").asText());assertEquals("LOCAL_CONTROLLED",report.sourceKind());
  Tenant.run("other",()->assertThrows(WorkflowStore.Blocked.class,()->executor.get(t.workspaceId,task,job)));
 }
}
