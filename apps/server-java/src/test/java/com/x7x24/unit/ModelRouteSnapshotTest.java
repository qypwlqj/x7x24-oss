package com.x7x24.unit;
import com.x7x24.engine.X7Props;
import com.x7x24.llm.ModelRouter;
import com.x7x24.pipeline.Pipeline;
import com.x7x24.util.Json;
import com.x7x24.workflow.Canonical;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ModelRouteSnapshotTest {
 @Test void aPinnedExecutionRejectsChangedModelConfigAndClearsThreadScope(){
  var router=new ModelRouter(new X7Props(),null,(provider,key,model,temperature,persona)->{throw new AssertionError("No provider invocation allowed");});
  String digest=Canonical.digest(router.config());var changed=Json.convert(router.config(),Pipeline.ModelConfig.class);changed.roles.put("dev.code","changed-profile");
  assertThrows(IllegalStateException.class,()->router.withExpectedConfig(digest,()->{router.resetForTest(changed);return router.config();}));
  assertEquals("changed-profile",router.config().roles.get("dev.code"));
 }
}
