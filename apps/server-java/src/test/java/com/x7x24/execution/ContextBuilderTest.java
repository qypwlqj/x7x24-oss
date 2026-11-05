package com.x7x24.execution;
import com.x7x24.workflow.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
import static org.junit.jupiter.api.Assertions.*;
class ContextBuilderTest {
 @Test void oversizedRequiredContentFailsBeforeRequestAndCrossScopeContentIsDenied(){
  var builder=new ContextBuilder(null,null);String content="{\"title\":\"x\",\"goal\":\""+"large".repeat(20000)+"\"}";var a=new ArtifactVersion("v","ws","p","t",ArtifactType.RequirementSpec,1,Canonical.digest(com.x7x24.util.Json.tree(content)),content,"FIXTURE",null,null,"base",List.of());
  var n=new Node("n","n","code.implement@1",Map.of(),List.of(),Map.of());var p=new Plan(3,"revision","ws","p","t",1,null,"base","policy",1,List.of(n),List.of(),Map.of());var run=new Run("run","n",1,"RUNNING","fingerprint",1,"owner",List.of(new ResolvedInput("requirements",0,"v",a.contentDigest(),"true")));
  assertThrows(IllegalStateException.class,()->builder.build(p,n,run,Map.of("v",a),"mandatory constraints",new ContextBuilder.Budget(2000,1000,500)));
  var cross=new ArtifactVersion("v","different","p","t",a.type(),1,a.contentDigest(),content,"FIXTURE",null,null,"base",List.of());assertThrows(IllegalStateException.class,()->builder.build(p,n,run,Map.of("v",cross),"mandatory",new ContextBuilder.Budget(100000,1000,500)));
 }
 @Test void finalModelRequestIsBoundedToo(){assertThrows(IllegalStateException.class,()->ContextBuilder.guardRequest("safety".repeat(10000),"goal",16000));}
}
