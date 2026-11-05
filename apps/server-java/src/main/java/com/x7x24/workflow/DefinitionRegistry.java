package com.x7x24.workflow;
import com.x7x24.llm.Providers;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
/** Actual deployment capability: the limited rule generator is not a general coding fallback. */
@Component
public class DefinitionRegistry {
 @org.springframework.beans.factory.annotation.Value("${x7x24.local-file-executor-enabled:false}") boolean localEnabled;
 private final Providers providers;
 public DefinitionRegistry(Providers providers){this.providers=providers;}
 public Map<String,Definition> definitions(){
  var result=new LinkedHashMap<>(Definitions.standard());
  if(providers==null || !providers.llm())result.replaceAll((key,d)->d.executor()==ExecutorKind.AGENT?new Definition(d.ref(),d.executor(),false,d.inputs(),d.outputs(),d.capabilities()):d);
  if(!localEnabled){var d=result.get("local.file-check@1");result.put(d.ref(),new Definition(d.ref(),d.executor(),false,d.inputs(),d.outputs(),d.capabilities()));}
  return Collections.unmodifiableMap(result);
 }
}
