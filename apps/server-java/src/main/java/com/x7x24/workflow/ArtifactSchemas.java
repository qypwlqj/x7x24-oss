package com.x7x24.workflow;
import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.util.Json;
import static com.x7x24.workflow.Workflow.*;
/** M1 payload schema validation, distinct from independent business/test validation. */
public final class ArtifactSchemas {
 private ArtifactSchemas(){}
 public static void check(ArtifactVersion a){
  JsonNode n=Json.lenient(a.content());boolean valid=n!=null && n.isObject() && !n.isEmpty();
  if(valid)valid=switch(a.type()){
   case RequirementSpec -> text(n,"title") && text(n,"goal");
   case AcceptanceCriteria -> n.path("criteria").isArray() && !n.path("criteria").isEmpty() && java.util.stream.StreamSupport.stream(n.path("criteria").spliterator(),false).allMatch(x->x.isTextual()&&!x.asText().isBlank());
   case RepoSnapshot -> text(n,"sha") && n.path("sha").asText().equals(a.baseSha());
   case DesignSpec -> text(n,"notes") || n.path("frames").isArray()&&!n.path("frames").isEmpty();
   case APIContract -> n.path("schema").isObject()&&!n.path("schema").isEmpty();
   case PatchSet -> n.path("baseSha").asText().equals(a.baseSha()) && n.path("change").path("files").isArray()&&!n.path("change").path("files").isEmpty();
   default -> true;
  };
  if(!valid)throw new WorkflowStore.Blocked("ARTIFACT_SCHEMA","产物内容不满足类型合同："+a.type()+"@"+a.schema());
 }
 private static boolean text(JsonNode n,String k){return n.path(k).isTextual()&&!n.path(k).asText().isBlank();}
}
