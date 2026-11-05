package com.x7x24.workflow;

import com.x7x24.db.Db;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
import static com.x7x24.workflow.WorkflowStore.Blocked;

/** Stable topological serial runtime. Result publication, lineage and success share one transaction. */
@Component
public class WorkflowScheduler {
  private final WorkflowStore store; private final ExecutionLeases leases;private final Db db;private final Clk clk;
  public interface Dispatcher { Map<String,String> execute(Plan plan,Node node,Run run); }
  public WorkflowScheduler(WorkflowStore store,ExecutionLeases leases,Db db,Clk clk){this.store=store;this.leases=leases;this.db=db;this.clk=clk;}
  public List<ResolvedInput> resolve(Plan plan,Node node){
    var def=store.registry.definitions().get(node.definitionRef());List<ResolvedInput> inputs=new ArrayList<>();
    for(var port:def.inputs()){
      Boolean condition=port.requirement()==Requirement.CONDITIONAL ? plan.facts().get(port.condition()) : Boolean.TRUE;
      if(condition==null)throw new Blocked("APPLICABILITY_UNKNOWN",node.id()+":"+port.name());
      var bs=(node.bindings()==null?List.<Binding>of():node.bindings()).stream().filter(b->port.name().equals(b.port())).sorted(Comparator.comparingInt(Binding::ordinal)).toList();
      if(bs.isEmpty()){
        if(port.requirement()!=Requirement.OPTIONAL && condition)throw new Blocked("WAITING_INPUT",port.name());
        inputs.add(new ResolvedInput(port.name(),0,"ABSENT","ABSENT",condition.toString()));continue;
      }
      for(var b:bs){
        String id=b.versionId();
        if(id==null){var pinned=db.row("SELECT resolved_version_id FROM input_binding WHERE workspace_id=? AND plan_revision_id=? AND node_id=? AND id=?",Tenant.writeId(),plan.revisionId(),node.id(),b.id());if(pinned!=null)id=Db.str(pinned.get("resolved_version_id"));}
        if(id==null){var row=db.row("SELECT a.id FROM artifact_version a JOIN node_run r ON r.id=a.producer_run JOIN node_instance n ON n.plan_revision_id=r.plan_revision_id AND n.id=r.node_id WHERE a.workspace_id=? AND a.plan_revision_id=? AND a.producer_node=? AND a.output_port=? AND r.state='SUCCEEDED' AND n.state='SUCCEEDED' ORDER BY a.created_at DESC LIMIT 1",Tenant.writeId(),plan.revisionId(),b.producerNode(),b.outputPort());if(row!=null)id=Db.str(row.get("id"));}
        var a=id==null?null:store.artifact(id);
        if(a==null || !a.projectId().equals(plan.projectId()) || a.type()!=port.type() || a.schema()!=port.schema())throw new Blocked("WAITING_INPUT",node.id()+":"+port.name()+"缺少类型或版本兼容的固定输入");
        if(!store.validated(a))throw new Blocked("WAITING_VALIDATION",a.id());
        if(port.approvalRequired() && !store.approved(plan.taskId(),WorkflowStore.artifactApprovalDigest(a)))throw new Blocked("WAITING_APPROVAL",a.id());
        if(db.update("UPDATE input_binding SET resolved_version_id=? WHERE plan_revision_id=? AND node_id=? AND id=? AND (resolved_version_id IS NULL OR resolved_version_id=?)",a.id(),plan.revisionId(),node.id(),b.id(),a.id())!=1)throw new Blocked("BINDING_PIN_CHANGED",b.id());
        inputs.add(new ResolvedInput(port.name(),b.ordinal(),a.id(),a.contentDigest(),condition.toString()));
      }
    }return List.copyOf(inputs);
  }
  public String fingerprint(Plan p,Node n,List<ResolvedInput> inputs){return Canonical.digest(Map.of("algorithm","execution-v1","definition",n.definitionRef(),"config",n.config()==null?Map.of():n.config(),"roles",n.roles()==null?Map.of():n.roles(),"inputs",inputs,"baseSha",p.baseSha(),"policy",p.policyDigest(),"executor","legacy-json-v1","facts",p.facts()));}
  public Run start(String taskId,String nodeId){return db.tx(()->{
    var t=store.lockTask(taskId);if(!"v3".equals(t.get("pipeline_version")))throw new Blocked("LEGACY_ROUTE","schema=3 执行器不能接管旧任务");
    String revision=Db.str(t.get("active_plan_revision_id"));if(revision==null)throw new Blocked("WAITING_PLAN","任务尚无已接受的计划修订");
    var c=store.revision(revision);var p=c.plan();assertPolicy(p);if(!store.approved(taskId,c.hash()))throw new Blocked("WAITING_APPROVAL","计划批准已过期或被撤销");
    if(db.count("SELECT COUNT(*) FROM workflow_plan_revision WHERE id=? AND status='ACCEPTED'",revision)!=1)throw new Blocked("STALE_PLAN",revision);
    if(Db.i(t.get("paused"))!=0)throw new Blocked("PAUSED",taskId);
    if(db.count("SELECT COUNT(*) FROM node_run WHERE task_id=? AND workspace_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN')",taskId,Tenant.writeId())>0)throw new Blocked("RECOVERY_REQUIRED","原执行可能仍在运行，禁止重复启动");
    String next=c.order().stream().filter(id->db.count("SELECT COUNT(*) FROM node_instance WHERE plan_revision_id=? AND id=? AND state='SUCCEEDED'",revision,id)==0).findFirst().orElseThrow(()->new Blocked("COMPLETE","所有节点均已生成产物，业务验收需单独核验"));
    if(!next.equals(nodeId))throw new Blocked("SERIAL_ORDER","当前串行流程应先执行节点："+next);
    if(db.count("SELECT COUNT(*) FROM artifact_invalidation WHERE plan_revision_id=? AND consumer_node=?",revision,nodeId)>0)throw new Blocked("STALE_INPUT","上游输入已变化，请先建立新计划修订");
    var n=p.nodes().stream().filter(x->x.id().equals(nodeId)).findFirst().orElseThrow();if(!store.registry.definitions().get(n.definitionRef()).available())throw new Blocked("CAPABILITY_UNAVAILABLE","当前环境缺少执行器，规则模板不能替代通用编码能力");var inputs=resolve(p,n);
    String owner=U.uuid();long epoch=leases.claim("task",taskId,owner);String resource=store.repositoryResource(p);long repoEpoch=leases.claim("repository",resource,owner);
    String runId=U.uuid();int attempt=(int)db.count("SELECT COUNT(*) FROM node_run WHERE plan_revision_id=? AND node_id=?",revision,nodeId)+1;
    String fp=fingerprint(p,n,inputs);
    db.update("INSERT INTO node_run(id,workspace_id,project_id,task_id,plan_revision_id,node_id,attempt_no,input_fingerprint,inputs,role_snapshot,state,owner_id,epoch,checkpoint,started_at,heartbeat_at,repository_resource_id) VALUES(?,?,?,?,?,?,?,?,?,?,'RUNNING',?,?,?,?,?,?)",runId,p.workspaceId(),p.projectId(),taskId,revision,nodeId,attempt,fp,Canonical.json(inputs),Canonical.json(n.roles()),owner,epoch,Long.toString(repoEpoch),clk.iso(),clk.iso(),resource);
    db.update("UPDATE node_instance SET state='RUNNING' WHERE plan_revision_id=? AND id=?",revision,nodeId);
    return new Run(runId,nodeId,attempt,"RUNNING",fp,epoch,owner,inputs);
  });}
  public void complete(String taskId,Run run,Map<String,String> outputs,String source){db.tx(()->{
    var t=store.lockTask(taskId);var row=db.row("SELECT * FROM node_run WHERE id=? AND task_id=? AND workspace_id=? FOR UPDATE",run.id(),taskId,Tenant.writeId());
    if(row==null || !"RUNNING".equals(row.get("state")) || !run.owner().equals(row.get("owner_id")) || run.epoch()!=Db.l(row.get("epoch")))throw new Blocked("STALE_EXECUTOR","当前执行已失去执行权，拒绝写入结果");
    leases.assertOwner("task",taskId,run.owner(),run.epoch());long repoEpoch=Long.parseLong(Db.str(row.get("checkpoint")));leases.assertOwner("repository",WorkflowStore.runRepository(row),run.owner(),repoEpoch);
    var c=store.revision(Db.str(row.get("plan_revision_id")));var p=c.plan();assertPolicy(p);var n=p.nodes().stream().filter(x->x.id().equals(run.nodeId())).findFirst().orElseThrow();
    if(!p.revisionId().equals(t.get("active_plan_revision_id")) || !store.approved(taskId,c.hash()) || Db.i(t.get("paused"))!=0 || db.count("SELECT COUNT(*) FROM artifact_invalidation WHERE plan_revision_id=? AND consumer_node=?",p.revisionId(),n.id())>0)throw new Blocked("STALE_PLAN","结果所属计划已失效，不能推进当前计划");
    if(!resolve(p,n).equals(run.inputs()))throw new Blocked("INPUT_FINGERPRINT","已绑定输入的批准、校验或有效性已变化，拒绝发布输出");
    if(!fingerprint(p,n,run.inputs()).equals(row.get("input_fingerprint")))throw new Blocked("INPUT_FINGERPRINT","执行输入清单与原指纹不一致");
    var def=store.registry.definitions().get(n.definitionRef());
    if(outputs==null || outputs.size()!=def.outputs().size())throw new Blocked("MISSING_OUTPUT","缺少必需输出，或返回了合同未声明的输出");
    for(var port:def.outputs()){
      String content=outputs.get(port.name());if(content==null || Json.lenient(content)==null)throw new Blocked("MISSING_OUTPUT",port.name());
      var a=new ArtifactVersion(U.uuid(),p.workspaceId(),p.projectId(),taskId,port.type(),port.schema(),Canonical.digest(Json.tree(content)),content,source,n.id(),run.id(),p.baseSha(),run.inputs().stream().map(ResolvedInput::versionId).filter(id->!id.equals("ABSENT")).distinct().toList());
      store.publish(a,p.revisionId(),port.name());
      store.validate(new Validation(a.id(),"json-schema-envelope@1","schema-v1",ValidationResult.PASS,a.contentDigest(),"artifact:"+a.id(),clk.iso()));
    }
    if(db.update("UPDATE node_run SET state='SUCCEEDED',ended_at=?,state_version=state_version+1 WHERE id=? AND state='RUNNING' AND epoch=?",clk.iso(),run.id(),run.epoch())!=1)throw new Blocked("STALE_EXECUTOR",run.id());
    db.update("UPDATE node_instance SET state='SUCCEEDED' WHERE plan_revision_id=? AND id=?",p.revisionId(),n.id());
    leases.release("task",taskId,run.owner(),run.epoch());leases.release("repository",WorkflowStore.runRepository(row),run.owner(),repoEpoch);
  });}
  public void assertCurrent(String taskId,Run run){
    var t=store.task(taskId);var r=db.row("SELECT * FROM node_run WHERE id=? AND task_id=? AND workspace_id=?",run.id(),taskId,Tenant.writeId());
    if(r==null || !"RUNNING".equals(r.get("state")) || !run.owner().equals(r.get("owner_id")) || run.epoch()!=Db.l(r.get("epoch")))throw new Blocked("STALE_EXECUTOR","原执行已失去执行权，不能继续读取或调用模型");
    leases.assertOwner("task",taskId,run.owner(),run.epoch());leases.assertOwner("repository",WorkflowStore.runRepository(r),run.owner(),Long.parseLong(Db.str(r.get("checkpoint"))));
    var c=store.revision(Db.str(r.get("plan_revision_id")));if(!Objects.equals(t.get("active_plan_revision_id"),c.plan().revisionId()) || Db.i(t.get("paused"))!=0 || !store.approved(taskId,c.hash()))throw new Blocked("WAITING_APPROVAL","计划已暂停、失效或批准被撤销");
    assertPolicy(c.plan());var n=c.plan().nodes().stream().filter(node->node.id().equals(run.nodeId())).findFirst().orElseThrow();if(!resolve(c.plan(),n).equals(run.inputs()))throw new Blocked("INPUT_FINGERPRINT","运行输入已失效，请重新核对合同");
  }
  private void assertPolicy(Plan plan) {
    if(!store.roles.lock(plan).policyDigest().equals(plan.policyDigest()))throw new Blocked("POLICY_CHANGED","当前策略与原批准内容不一致，请建立新计划修订");
  }
  /** Validate a recovery without starting a second executor or changing accepted plan inputs. */
  public void assertResumable(String taskId) {
    var t=store.task(taskId);String revision=Db.str(t.get("active_plan_revision_id"));
    if(revision==null)throw new Blocked("WAITING_PLAN","任务尚无有效计划，请先接受计划修订");
    var c=store.revision(revision);assertPolicy(c.plan());
    if(!store.approved(taskId,c.hash()))throw new Blocked("WAITING_APPROVAL","当前计划的批准已过期，请先核对计划");
    String next=c.order().stream().filter(id->db.count("SELECT COUNT(*) FROM node_instance WHERE plan_revision_id=? AND id=? AND state='SUCCEEDED'",revision,id)==0).findFirst().orElseThrow(()->new Blocked("COMPLETE","当前计划节点已完成"));
    if(db.count("SELECT COUNT(*) FROM artifact_invalidation WHERE plan_revision_id=? AND consumer_node=?",revision,next)>0)throw new Blocked("STALE_INPUT","输入已失效，请建立新的计划修订");
    var node=c.plan().nodes().stream().filter(n->n.id().equals(next)).findFirst().orElseThrow();
    if(!store.registry.definitions().get(node.definitionRef()).available())throw new Blocked("CAPABILITY_UNAVAILABLE","当前节点的执行器仍不可用");
    resolve(c.plan(),node);
  }
  public void cancelled(String taskId,Run run){db.tx(()->{
    var r=db.row("SELECT * FROM node_run WHERE id=? AND workspace_id=? AND task_id=? FOR UPDATE",run.id(),com.x7x24.tenant.Tenant.writeId(),taskId);
    if(r==null || !run.owner().equals(r.get("owner_id")) || run.epoch()!=Db.l(r.get("epoch")) || !Set.of("RUNNING","OUTCOME_UNKNOWN").contains(r.get("state")))throw new Blocked("STALE_EXECUTOR","取消结果不属于当前执行");
    db.update("UPDATE node_run SET state='CANCELLED',ended_at=?,state_version=state_version+1 WHERE id=?",clk.iso(),run.id());db.update("UPDATE node_instance SET state='CANCELLED' WHERE plan_revision_id=? AND id=?",r.get("plan_revision_id"),r.get("node_id"));
    leases.release("task",taskId,run.owner(),run.epoch());leases.release("repository",WorkflowStore.runRepository(r),run.owner(),Long.parseLong(Db.str(r.get("checkpoint"))));
  });}
  public void fail(String taskId,Run run,Throwable failure){
    int updated=db.update("UPDATE node_run SET state='FAILED',ended_at=?,state_version=state_version+1 WHERE id=? AND workspace_id=? AND owner_id=? AND epoch=? AND state='RUNNING'",clk.iso(),run.id(),Tenant.writeId(),run.owner(),run.epoch());
    if(updated!=1)throw new Blocked("STALE_EXECUTOR",run.id());
    // A dispatcher has no GitHub write capability; once it returns/throws it has stopped.
    var row=db.row("SELECT project_id,repository_resource_id,checkpoint,plan_revision_id FROM node_run WHERE id=? AND workspace_id=?",run.id(),Tenant.writeId());
    if(row!=null){db.update("UPDATE node_instance SET state='FAILED' WHERE plan_revision_id=? AND id=?",row.get("plan_revision_id"),run.nodeId());leases.release("task",taskId,run.owner(),run.epoch());leases.release("repository",WorkflowStore.runRepository(row),run.owner(),Long.parseLong(Db.str(row.get("checkpoint"))));}
  }
  /** Explicit authenticated stop confirmation, never an automatic expiry takeover. */
  public void recoverStopped(String taskId,String runId,String actor,String stopEvidence) { db.tx(()->{
    var t=store.lockTask(taskId);
    if(!"v3".equals(t.get("pipeline_version")) || stopEvidence==null || stopEvidence.isBlank())throw new Blocked("RECOVERY_EVIDENCE","请提供旧执行者已经停止的核验证据");
    var r=db.row("SELECT * FROM node_run WHERE id=? AND task_id=? AND workspace_id=? FOR UPDATE",runId,taskId,Tenant.writeId());
    if(r==null || !"OUTCOME_UNKNOWN".equals(r.get("state")))throw new Blocked("RECOVERY_STATE","仅结果未知的执行可以进入停止确认恢复");
    String prefix=taskId+":";
    if(db.count("SELECT COUNT(*) FROM tool_ops WHERE LEFT(op_key,?)=? AND state IN ('DISPATCHING','OUTCOME_UNKNOWN')",prefix.length(),prefix)>0)throw new Blocked("RECOVERY_REQUIRED","请先核对尚未确定结果的外部写操作");
    String owner=Db.str(r.get("owner_id"));long epoch=Db.l(r.get("epoch"));
    store.approve(taskId,Canonical.digest(Map.of("runId",runId,"owner",owner,"epoch",epoch,"stopEvidence",stopEvidence)),actor,300000);
    leases.release("task",taskId,owner,epoch);leases.release("repository",WorkflowStore.runRepository(r),owner,Long.parseLong(Db.str(r.get("checkpoint"))));
    db.update("UPDATE node_run SET state='CANCELLED',ended_at=?,state_version=state_version+1 WHERE id=? AND state='OUTCOME_UNKNOWN'",clk.iso(),runId);
    db.update("UPDATE node_instance SET state='PENDING' WHERE plan_revision_id=? AND id=?",r.get("plan_revision_id"),r.get("node_id"));
    db.update("UPDATE task SET next_run_at=?,version=version+1 WHERE id=? AND workspace_id=?",clk.iso(),taskId,Tenant.writeId());
  }); }
  public void watchdog(){
    db.update("UPDATE node_run SET state='OUTCOME_UNKNOWN',state_version=state_version+1 WHERE state='RUNNING' AND heartbeat_at<?",Clk.iso(clk.now()-60000));
  }
}
