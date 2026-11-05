package com.x7x24.it;
import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class ChatScenarioIT extends Harness {
 @Autowired TestRestTemplate http;
 @Test void scenarioSelectionIsPersistedWithoutInventingRiskOrSkippingGates(){
  for(String preset:java.util.List.of("frontend","backend","migration","fast")){
   var response=http.postForEntity("/api/chats",Map.of("projectId","sandbox-default","presetId",preset),String.class);assertEquals(200,response.getStatusCode().value(),response.getBody());
   String id=Json.tree(response.getBody()).path("id").asText();var draft=Json.tree(http.getForObject("/api/chats/"+id,String.class)).path("draft");assertEquals(preset,draft.path("presetId").asText());
   if(preset.equals("fast")){assertTrue(draft.path("risk").isNull()||draft.path("risk").isMissingNode());assertTrue(draft.path("size").isNull()||draft.path("size").isMissingNode());}
   else assertEquals(preset,draft.path("classification").asText());
  }
 }
}
