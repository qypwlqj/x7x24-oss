package com.x7x24.workflow;
import com.x7x24.execution.*;
import com.x7x24.engine.*;
import com.x7x24.gov.PathPolicy;
import com.x7x24.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
import static com.x7x24.execution.CodingExecutor.*;
/** Bridges typed inputs to the limited local file checker; never claims to run a project test suite. */
@Component
public class LocalFileDispatcher implements WorkflowScheduler.Dispatcher {
 private final WorkflowStore store;private final LocalCodingExecutor executor;private final TaskRepo tasks;private final ProjectRepo projects;
 @Value("${x7x24.local-file-executor-enabled:false}") boolean enabled;
 public LocalFileDispatcher(WorkflowStore store,LocalCodingExecutor executor,TaskRepo tasks,ProjectRepo projects){this.store=store;this.executor=executor;this.tasks=tasks;this.projects=projects;}
 public Map<String,String> execute(Plan plan,Node node,Run run){
  if(!enabled || !node.definitionRef().equals("local.file-check@1"))throw new WorkflowStore.Blocked("CAPABILITY_UNAVAILABLE","当前未启用本地文件断言执行器");
  Map<String,ArtifactVersion> inputs=new TreeMap<>();for(var input:run.inputs())if(!input.versionId().equals("ABSENT"))inputs.put(input.port(),store.artifact(input.versionId()));
  var repository=Json.tree(inputs.get("repository").content());var patch=Json.tree(inputs.get("patch").content());var ac=Json.tree(inputs.get("acceptance").content());
  Map<String,String> baseFiles=new TreeMap<>();repository.path("files").fields().forEachRemaining(e->{if(!e.getValue().isTextual())throw new WorkflowStore.Blocked("LOCAL_INPUT","本地仓库材料必须包含明确的文本文件");baseFiles.put(e.getKey(),e.getValue().asText());});
  if(baseFiles.isEmpty())throw new WorkflowStore.Blocked("LOCAL_INPUT","本地检查需要固定仓库版本中的文件内容");
  var task=tasks.get(plan.taskId());var policy=projects.forTask(task).cfg();var steps=new ArrayList<Step>();for(String path:baseFiles.keySet())steps.add(new Step(Action.READ,path,null,null,null));
  var changed=new TreeMap<String,String>();for(var f:patch.path("change").path("files")){
   if(f.path("delete").asBoolean(false)||!f.path("content").isTextual())throw new WorkflowStore.Blocked("LOCAL_PATCH","本地文件适配器仅接受明确文本补丁，不执行脚本或删除");
   String path=f.path("path").asText();changed.put(path,f.path("content").asText());steps.add(new Step(Action.PUT,path,null,f.path("content").asText(),null));
  }
  var errors=PathPolicy.check(policy,new ArrayList<>(changed.keySet()),changed);if(!errors.isEmpty())throw new WorkflowStore.Blocked("PATH_POLICY",errors.toString());
  var criteria=new TreeSet<String>();for(var criterion:ac.path("criteria"))criteria.add(criterion.asText());
  var checks=Json.M.valueToTree(node.config().get("checks"));if(!checks.isArray())throw new WorkflowStore.Blocked("MISSING_CHECKS","本地检查节点必须声明有界文件断言");
  for(var check:checks)steps.add(new Step(check.path("json").asBoolean(false)?Action.ASSERT_JSON:Action.ASSERT_CONTAINS,check.path("path").asText(),check.path("contains").asText(null),null,check.path("acceptanceId").asText(null)));
  var scopes=new ArrayList<>(policy.allowedPaths);scopes.addAll(List.of("README.md","CHANGELOG.md"));
  var spec=new JobSpec(1,plan.workspaceId(),plan.projectId(),plan.taskId(),run.id(),run.owner(),run.epoch(),"node-run:"+run.id(),plan.baseSha(),run.fingerprint(),baseFiles,steps,scopes,criteria,new Limits(100,1_000_000,200,System.currentTimeMillis()+30000),"DENY");
  var handle=executor.submit(spec);store.db.update("UPDATE node_run SET executor_job_id=? WHERE id=? AND workspace_id=?",handle.jobId(),run.id(),plan.workspaceId());
  while(Set.of(State.CREATED,State.RUNNING,State.CANCEL_REQUESTED).contains(handle.state())){
   if(System.currentTimeMillis()>spec.limits().deadlineAt()+1000){executor.cancel(plan.workspaceId(),plan.taskId(),handle.jobId(),"deadline");throw new WorkflowStore.Blocked("OUTCOME_UNKNOWN","本地执行超过截止时间，必须先查询原执行的终态");}
   try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();executor.cancel(plan.workspaceId(),plan.taskId(),handle.jobId(),"interrupted");throw new WorkflowStore.Blocked("OUTCOME_UNKNOWN","取消请求已发送，需要确认原执行终止");}
   handle=executor.get(plan.workspaceId(),plan.taskId(),handle.jobId());
  }
  if(handle.state()==State.OUTCOME_UNKNOWN)throw new WorkflowStore.Blocked("OUTCOME_UNKNOWN","本地作业结果未知，不能启动替代执行");
  var result=executor.exportResult(plan.workspaceId(),plan.taskId(),handle.jobId());store.db.update("UPDATE node_run SET result_manifest=?,executor_checkpoint_ref=? WHERE id=? AND workspace_id=?",Canonical.json(result),result.checkpointRef(),run.id(),plan.workspaceId());
  if(result.state()==State.CANCELLED)throw new WorkflowStore.Blocked("CANCELLED","本地执行已确认停止，已保留部分执行证据");
  if(result.state()!=State.SUCCEEDED)throw new WorkflowStore.Blocked("LOCAL_CHECK_FAILED","本地文件断言未通过："+result.missingEvidence());
  return Map.of("report",Canonical.json(Map.of("executor","LocalControlledWorkspace@1","scope","DECLARED_FILE_ASSERTIONS_ONLY","baseSha",result.baseSha(),"treeHash",result.treeHash(),"checks",result.checks(),"jobId",handle.jobId(),"projectTestExecution",false,"businessValidation","UNKNOWN")));
 }
}
