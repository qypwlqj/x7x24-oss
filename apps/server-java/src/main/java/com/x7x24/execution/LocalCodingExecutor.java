package com.x7x24.execution;
import com.x7x24.db.Db;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import com.x7x24.workflow.*;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.x7x24.execution.CodingExecutor.*;
import static com.x7x24.workflow.WorkflowStore.Blocked;
/** Durable intents around a deliberately limited in-process executor. No project shell runner is implied. */
@Component
public class LocalCodingExecutor implements CodingExecutor {
 private final Db db;private final Clk clk;private final ExecutionLeases leases;private final WorkflowScheduler scheduler;private final com.x7x24.gov.KillSwitch kill;private final com.x7x24.engine.TaskRepo tasks;
 private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();private final Map<String,AtomicBoolean> active=new ConcurrentHashMap<>();
 public LocalCodingExecutor(Db db,Clk clk,ExecutionLeases leases,WorkflowScheduler scheduler,com.x7x24.gov.KillSwitch kill,com.x7x24.engine.TaskRepo tasks){this.db=db;this.clk=clk;this.leases=leases;this.scheduler=scheduler;this.kill=kill;this.tasks=tasks;}
 public Handle submit(JobSpec spec){
  scope(spec.workspaceId());String digest=Canonical.digest(spec);AtomicBoolean created=new AtomicBoolean();
  String id=db.tx(()->{db.row("SELECT id FROM task WHERE id=? AND workspace_id=? FOR UPDATE",spec.taskId(),spec.workspaceId());
   var old=db.row("SELECT id,request_digest FROM coding_job WHERE workspace_id=? AND task_id=? AND operation_key=?",spec.workspaceId(),spec.taskId(),spec.operationKey());
   if(old!=null){if(!digest.equals(old.get("request_digest")))throw new Blocked("JOB_KEY_CONFLICT","相同执行键不能提交不同合同");return Db.str(old.get("id"));}
   authority(spec);String job=U.uuid();db.update("INSERT INTO coding_job(id,workspace_id,task_id,node_run_id,operation_key,request_digest,state,spec,created_at,updated_at) VALUES(?,?,?,?,?,?,'CREATED',?,?,?)",job,spec.workspaceId(),spec.taskId(),spec.nodeRunId(),spec.operationKey(),digest,Canonical.json(spec),clk.iso(),clk.iso());created.set(true);return job;
  });
  if(created.get()){
   var cancel=new AtomicBoolean();active.put(id,cancel);
   workers.submit(()->Tenant.run(spec.workspaceId(),()->execute(id,spec,cancel)));
  }
  return get(spec.workspaceId(),spec.taskId(),id);
 }
 private void execute(String id,JobSpec spec,AtomicBoolean cancel){
  Path directory=null;
  try{
   if(db.update("UPDATE coding_job SET state='RUNNING',updated_at=? WHERE id=? AND workspace_id=? AND state='CREATED'",clk.iso(),id,spec.workspaceId())!=1)cancel.set(true);
   directory=Files.createTempDirectory("x7-controlled-");
   try(var workspace=new LocalControlledWorkspace(directory,spec,()->authority(spec),cancel::get)){
    Result result=workspace.execute(event->append(id,spec.workspaceId(),event));
    db.update("UPDATE coding_job SET state=?,result=?,result_digest=?,checkpoint_ref=?,updated_at=? WHERE id=? AND workspace_id=? AND state IN ('RUNNING','CANCEL_REQUESTED','CREATED')",result.state().name(),Canonical.json(result),Canonical.digest(result),result.checkpointRef(),clk.iso(),id,spec.workspaceId());
   }
  }catch(Exception failure){db.update("UPDATE coding_job SET state='OUTCOME_UNKNOWN',updated_at=? WHERE id=? AND workspace_id=? AND result IS NULL",clk.iso(),id,spec.workspaceId());}
  finally{active.remove(id);}
 }
 private void authority(JobSpec spec){
  scope(spec.workspaceId());var r=db.row("SELECT * FROM node_run WHERE id=? AND workspace_id=? AND task_id=?",spec.nodeRunId(),spec.workspaceId(),spec.taskId());
  if(r==null || !"RUNNING".equals(r.get("state")) || !spec.ownerId().equals(r.get("owner_id")) || spec.epoch()!=Db.l(r.get("epoch")) || !spec.inputFingerprint().equals(r.get("input_fingerprint")))throw new Blocked("STALE_EXECUTOR","执行权或输入版本已变化，本地执行必须停止");
  var manifest=Json.parse(Db.str(r.get("inputs")),new com.fasterxml.jackson.core.type.TypeReference<List<Workflow.ResolvedInput>>(){});
  scheduler.assertCurrent(spec.taskId(),new Workflow.Run(spec.nodeRunId(),Db.str(r.get("node_id")),Db.i(r.get("attempt_no")),"RUNNING",spec.inputFingerprint(),spec.epoch(),spec.ownerId(),manifest));
  var task=tasks.get(spec.taskId());kill.assertWritable(task.productLine,task.projectId,"write");
  leases.assertOwner("task",spec.taskId(),spec.ownerId(),spec.epoch());String resource=r.get("repository_resource_id")==null?Db.str(r.get("project_id")):Db.str(r.get("repository_resource_id"));leases.assertOwner("repository",resource,spec.ownerId(),Long.parseLong(Db.str(r.get("checkpoint"))));
  var t=db.row("SELECT paused,active_plan_revision_id FROM task WHERE id=? AND workspace_id=?",spec.taskId(),spec.workspaceId());
  if(t==null || Db.i(t.get("paused"))!=0 || !Objects.equals(t.get("active_plan_revision_id"),r.get("plan_revision_id")))throw new Blocked("STALE_PLAN","任务已暂停或计划已切换，本地执行必须停止");
 }
 private void append(String id,String workspace,Event event){db.tx(()->{
  var row=db.row("SELECT event_seq FROM coding_job WHERE id=? AND workspace_id=? FOR UPDATE",id,workspace);long seq=Db.l(row.get("event_seq"))+1;
  db.update("INSERT INTO coding_job_event(job_id,seq,workspace_id,event_type,body,created_at) VALUES(?,?,?,?,?,?)",id,seq,workspace,event.type(),Canonical.json(new Event(seq,event.type(),event.subjectDigest(),event.detail(),event.at())),clk.iso());db.update("UPDATE coding_job SET event_seq=?,checkpoint_ref=?,updated_at=? WHERE id=?",seq,"local-tree:"+event.subjectDigest(),clk.iso(),id);
 });}
 public Optional<Handle> lookupByKey(String workspace,String task,String key){scope(workspace);var r=db.row("SELECT id FROM coding_job WHERE workspace_id=? AND task_id=? AND operation_key=?",workspace,task,key);return r==null?Optional.empty():Optional.of(get(workspace,task,Db.str(r.get("id"))));}
 public Handle get(String workspace,String task,String id){var r=row(workspace,task,id);State state=State.valueOf(Db.str(r.get("state")));if((state==State.CREATED || state==State.RUNNING || state==State.CANCEL_REQUESTED)&&!active.containsKey(id))state=State.OUTCOME_UNKNOWN;return new Handle(id,state,Db.l(r.get("event_seq")));}
 public List<Event> events(String workspace,String task,String id,long after){row(workspace,task,id);return db.rows("SELECT body FROM coding_job_event WHERE workspace_id=? AND job_id=? AND seq>? ORDER BY seq",workspace,id,after).stream().map(r->Json.parse(Db.str(r.get("body")),Event.class)).toList();}
 public Handle cancel(String workspace,String task,String id,String reason){row(workspace,task,id);var flag=active.get(id);if(flag!=null){if(db.update("UPDATE coding_job SET state='CANCEL_REQUESTED',updated_at=? WHERE id=? AND workspace_id=? AND state IN ('CREATED','RUNNING')",clk.iso(),id,workspace)==1)append(id,workspace,new Event(0,"CANCEL_REQUESTED","",Objects.toString(reason,""),clk.iso()));flag.set(true);}return get(workspace,task,id);}
 public Handle resume(String workspace,String task,String id,String checkpoint){row(workspace,task,id);throw new Blocked("UNSUPPORTED_RESUME","本地受控适配器不支持跨进程续跑；请先核对旧执行停止，再创建新执行修订");}
 public Result exportResult(String workspace,String task,String id){var r=row(workspace,task,id);if(r.get("result")==null)throw new Blocked("WAITING_EXTERNAL","执行结果尚未确定，请先查询或核对原执行");var result=Json.parse(Db.str(r.get("result")),Result.class);if(!Canonical.digest(result).equals(r.get("result_digest")))throw new Blocked("RESULT_DIGEST","执行结果摘要不匹配");return result;}
 private Map<String,Object> row(String workspace,String task,String id){scope(workspace);var r=db.row("SELECT * FROM coding_job WHERE id=? AND workspace_id=? AND task_id=?",id,workspace,task);if(r==null)throw new Blocked("NOT_FOUND","本地执行不存在或不可访问");return r;}
 private void scope(String workspace){if(!Tenant.writeId().equals(workspace))throw new Blocked("NOT_FOUND","执行不属于当前工作区");}
 @jakarta.annotation.PreDestroy public void close(){active.values().forEach(flag->flag.set(true));workers.shutdownNow();}
}
