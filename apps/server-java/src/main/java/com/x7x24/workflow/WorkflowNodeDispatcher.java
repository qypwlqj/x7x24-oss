package com.x7x24.workflow;
import org.springframework.stereotype.Component;
import java.util.Map;
import static com.x7x24.workflow.Workflow.*;
@Component
public class WorkflowNodeDispatcher implements WorkflowScheduler.Dispatcher {
 private final LegacyJsonDispatcher legacy;private final LocalFileDispatcher local;
 public WorkflowNodeDispatcher(LegacyJsonDispatcher legacy,LocalFileDispatcher local){this.legacy=legacy;this.local=local;}
 public Map<String,String> execute(Plan plan,Node node,Run run){return node.definitionRef().equals("local.file-check@1")?local.execute(plan,node,run):legacy.execute(plan,node,run);}
}
