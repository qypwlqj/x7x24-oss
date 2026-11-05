package com.x7x24.it;
import com.x7x24.util.*;
import com.x7x24.workflow.Workflow.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** UI command contract exercised over HTTP; no live providers. */
class WorkflowEditorApiIT extends Harness {
 @Autowired TestRestTemplate http;
 @DynamicPropertySource static void settings(DynamicPropertyRegistry r){r.add("x7x24.workflow-v3-admission",()->"true");}
 com.fasterxml.jackson.databind.JsonNode post(String path,Object body){var r=http.postForEntity(path,body,String.class);assertEquals(200,r.getStatusCode().value(),r.getBody());return Json.tree(r.getBody());}
 @Test void humanPlanEditorUsesExactVersionAndApprovalBeforeExecution(){
  String task=post("/api/projects/sandbox-default/workflow-tasks",Map.of("title","editor-http-"+U.uuid(),"goal","synthetic human handoff")).path("taskId").asText();
  String prefix="/api/tasks/"+task;var t=repo.get(task);var node=new Node("human","human","document.requirements@1",Map.of(),List.of(),Map.of());var p=new Plan(3,U.uuid(),t.workspaceId,t.projectId,task,1,null,"base","server-derived",1,List.of(node),List.of(),Map.of());
  var compiled=post(prefix+"/plan-revisions",Map.of("plan",p,"expectedVersion",t.version));String hash=compiled.path("hash").asText();int version=repo.get(task).version;
  assertEquals(409,http.postForEntity(prefix+"/plan-revisions/"+p.revisionId()+"/activate",Map.of("hash",hash,"expectedVersion",version),String.class).getStatusCode().value());
  assertEquals(409,http.postForEntity(prefix+"/workflow-approvals",Map.of("revisionId",p.revisionId(),"subjectDigest",hash,"expectedVersion",version-1),String.class).getStatusCode().value());
  post(prefix+"/workflow-approvals",Map.of("revisionId",p.revisionId(),"subjectDigest",hash,"expectedVersion",version));post(prefix+"/plan-revisions/"+p.revisionId()+"/activate",Map.of("hash",hash,"expectedVersion",version));
  int activeVersion=repo.get(task).version;var output=Map.of("outputs",Map.of("requirements",Map.of("title","fixture","goal","approved material")),"expectedVersion",activeVersion);
  post(prefix+"/nodes/human/human-output",output);
  var execution=Json.tree(http.getForObject(prefix+"/execution",String.class));assertEquals("SUCCEEDED",execution.path("nodes").get(0).path("state").asText());assertEquals(1,execution.path("artifacts").size());
  assertEquals(409,http.postForEntity(prefix+"/nodes/human/human-output",output,String.class).getStatusCode().value());
 }
}
