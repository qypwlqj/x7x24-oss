package com.x7x24.workflow;
import com.x7x24.db.Db;
import com.x7x24.engine.Task;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.x7x24.workflow.Workflow.*;
/** New runtime routing is persisted per task. Admission flag never reroutes in-flight tasks. */
@Component
public class WorkflowRuntime {
  @org.springframework.beans.factory.annotation.Autowired com.x7x24.engine.RecoveryService recovery;
  @org.springframework.beans.factory.annotation.Autowired com.x7x24.engine.TaskRepo tasks;
  final WorkflowStore store;final WorkflowScheduler scheduler;final WorkflowNodeDispatcher dispatcher;final Db db;final Clk clk;final ExecutionLeases leases;
  final Map<String,Run> local=new ConcurrentHashMap<>();
  public WorkflowRuntime(WorkflowStore store,WorkflowScheduler scheduler,WorkflowNodeDispatcher dispatcher,Db db,Clk clk,ExecutionLeases leases){this.store=store;this.scheduler=scheduler;this.dispatcher=dispatcher;this.db=db;this.clk=clk;this.leases=leases;}
  public void step(Task task){
    Run run=null;
    try{
      var t=store.task(task.id);String rid=Db.str(t.get("active_plan_revision_id"));if(rid==null)throw new WorkflowStore.Blocked("WAITING_PLAN","任务尚无已批准的计划");
      var c=store.revision(rid);String next=c.order().stream().filter(id->db.count("SELECT COUNT(*) FROM node_instance WHERE plan_revision_id=? AND id=? AND state='SUCCEEDED'",rid,id)==0).findFirst().orElse(null);
      if(next==null){waitState(task.id,"SUCCEEDED","Contract outputs generated; business validation and delivery remain separate");return;}
      var n=c.plan().nodes().stream().filter(x->x.id().equals(next)).findFirst().orElseThrow();
      db.update("UPDATE task SET current_node=? WHERE id=? AND workspace_id=?",next,task.id,task.workspaceId);
      if(Definitions.standard().get(n.definitionRef()).executor()==ExecutorKind.HUMAN)throw new WorkflowStore.Blocked("WAITING_INPUT","等待人工向节点提交产物："+n.id());
      run=scheduler.start(task.id,next);local.put(task.id,run);
      var output=dispatcher.execute(c.plan(),n,run);scheduler.complete(task.id,run,output,n.definitionRef().equals("local.file-check@1")?"LOCAL_CONTROLLED":"LEGACY_JSON");
      recovery.markResolved(task);
      waitState(task.id,"WAITING","Next serial node");
    }catch(WorkflowStore.Blocked b){if(run!=null){if("CANCELLED".equals(b.code)){scheduler.cancelled(task.id,run);waitState(task.id,"CANCELLED",b.getMessage());return;}if("OUTCOME_UNKNOWN".equals(b.code)){db.update("UPDATE node_run SET state='OUTCOME_UNKNOWN' WHERE id=? AND workspace_id=? AND state='RUNNING'",run.id(),task.workspaceId);waitState(task.id,"RECOVERY_REQUIRED",b.getMessage());return;}try{scheduler.fail(task.id,run,b);}catch(WorkflowStore.Blocked lost){waitState(task.id,"RECOVERY_REQUIRED",lost.getMessage());return;}waitState(task.id,"FAILED",b.code+"："+b.getMessage());}else waitState(task.id,b.code,b.getMessage());}
    catch(RuntimeException e){if(run!=null)scheduler.fail(task.id,run,e);waitState(task.id,"FAILED",U.ellipsis(com.x7x24.adapters.Redactor.redact(e),1000));}
    finally{local.remove(task.id);}
  }
  private void waitState(String id,String state,String reason){
    if("WAITING".equals(state) || "SUCCEEDED".equals(state)) {
      db.update("UPDATE task SET execution_state=?,workflow_block_code=NULL,workflow_block_reason=NULL,next_run_at=?,version=version+1 WHERE id=? AND workspace_id=? AND pipeline_version='v3'",state,"WAITING".equals(state)?Clk.iso(clk.now()+10000):null,id,Tenant.writeId());return;
    }
    db.tx(()->{
      var before=tasks.get(id);var after=before.copy();if(reason.startsWith("PATH_POLICY"))after.context.policyFeedback=Map.of("violations",List.of(com.x7x24.adapters.Redactor.redact(reason)));recovery.block(before,after,state,reason);
      if(Set.of("WAITING_INPUT","WAITING_PLAN","WAITING_APPROVAL","WAITING_EXTERNAL","WAITING_VALIDATION","RECOVERY_REQUIRED").contains(state))after.executionState=state;
      if(!tasks.save(after))throw new WorkflowStore.Blocked("VERSION_CONFLICT","任务已变化");
      recovery.publishBlock(before,after,state,reason);
      db.update("UPDATE task SET workflow_block_code=?,workflow_block_reason=? WHERE id=? AND workspace_id=?",state,com.x7x24.adapters.Redactor.redact(reason),id,Tenant.writeId());
    });
  }
  public Task resume(String id,int expectedVersion,String actor) {
    return recovery.resume(id,expectedVersion,actor,t->scheduler.assertResumable(id));
  }
  public boolean locallyRunning(String runId){return local.values().stream().anyMatch(run->run.id().equals(runId));}
  public void watchdog(){
    for(var entry:local.entrySet()){
      var row=db.row("SELECT workspace_id,project_id,repository_resource_id,checkpoint FROM node_run WHERE id=?",entry.getValue().id());if(row==null)continue;
      Tenant.run(Db.str(row.get("workspace_id")),()->{var run=entry.getValue();try{leases.heartbeat("task",entry.getKey(),run.owner(),run.epoch());leases.heartbeat("repository",WorkflowStore.runRepository(row),run.owner(),Long.parseLong(Db.str(row.get("checkpoint"))));db.update("UPDATE node_run SET heartbeat_at=? WHERE id=? AND state='RUNNING' AND epoch=?",clk.iso(),run.id(),run.epoch());}catch(WorkflowStore.Blocked lost){ /* Keep local execution visible until the dispatcher actually returns. */ }});
    }
    scheduler.watchdog();
  }
}
