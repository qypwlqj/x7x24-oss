package com.x7x24.execution;
import com.x7x24.workflow.*;
import com.x7x24.pipeline.Pipeline.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ModelBudgetGuardTest {
 @Test void missingPriceBlocksAndRetryCostIsIncluded(){
  var config=new ModelConfig();var route=new Profile();route.chain=List.of("model");config.profiles.put("exec",route);var model=new Model();model.id="model";model.provider="provider";model.maxOutputTokens=1000;config.models.add(model);var provider=new Provider();provider.id="provider";provider.maxRetries=1;config.providers.add(provider);
  var role=new Workflow.RoleSnapshot("maker","dev.code",null,List.of(),"fixed","digest","route");assertThrows(WorkflowStore.Blocked.class,()->ModelBudgetGuard.worstCallUsd(config,role));model.priceInPer1m=1.0;model.priceOutPer1m=2.0;assertEquals((16384+2000)/1_000_000.0*6,ModelBudgetGuard.worstCallUsd(config,role),0.000001);
 }
}
