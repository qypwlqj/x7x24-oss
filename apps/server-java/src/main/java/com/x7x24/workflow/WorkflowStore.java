package com.x7x24.workflow;

import com.x7x24.db.Db;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;

/** Scoped durable facts. Published versions, validations and lineage are append-only through this service. */
@Component
public class WorkflowStore {
  final Db db; final Clk clk; final RoleSnapshotResolver roles; final DefinitionRegistry registry;
  public WorkflowStore(Db db, Clk clk,RoleSnapshotResolver roles,DefinitionRegistry registry) { this.db=db; this.clk=clk; this.roles=roles; this.registry=registry; }
  public static class Blocked extends IllegalStateException { public final String code; public Blocked(String code,String message) { super(message);this.code=code; } }
  public Map<String,Object> task(String taskId) {
    var r=db.row("SELECT * FROM task WHERE id=? AND workspace_id=? AND deleted_at IS NULL",taskId,Tenant.writeId());
    if(r==null) throw new Blocked("NOT_FOUND","当前工作区不存在该任务"); return r;
  }
  public Map<String,Object> lockTask(String taskId) {
    var r=db.row("SELECT * FROM task WHERE id=? AND workspace_id=? AND deleted_at IS NULL FOR UPDATE",taskId,Tenant.writeId());
    if(r==null)throw new Blocked("NOT_FOUND","任务不可访问或不存在");return r;
  }
  public String repositoryResource(Plan plan) {
    var t=task(plan.taskId());var context=Json.tree(Db.str(t.get("context")));String repository=context.path("projectSnapshot").path("repo").asText("");
    if(repository.isBlank())throw new Blocked("REPOSITORY_IDENTITY","任务缺少固定仓库身份，不能领取执行权");
    return "repository:"+Canonical.digest(repository.toLowerCase(Locale.ROOT));
  }
  public static String runRepository(Map<String,Object> run){return run.get("repository_resource_id")==null?Db.str(run.get("project_id")):Db.str(run.get("repository_resource_id"));}
  public ArtifactVersion artifact(String id) {
    var r=db.row("SELECT body FROM artifact_version WHERE id=? AND workspace_id=?",id,Tenant.writeId());
    return r==null ? null : Json.parse(Db.str(r.get("body")),ArtifactVersion.class);
  }
  public ArtifactVersion publish(ArtifactVersion a,String revision,String outputPort) {
    var task=task(a.taskId());
    if(!Objects.equals(a.workspaceId(),Tenant.writeId()) || !Objects.equals(task.get("project_id"),a.projectId())) throw new Blocked("ARTIFACT_SCOPE","产物不属于当前任务或项目");
    if(a.schema()!=1 || a.type()==null || a.content()==null || a.content().length()>1_000_000 || !Objects.equals(a.contentDigest(),Canonical.digest(Json.tree(a.content())))) throw new Blocked("ARTIFACT_SCHEMA","产物格式、内容或摘要校验失败");
    ArtifactSchemas.check(a);
    if(!Set.of("USER","IMPORTED","LEGACY_JSON","LOCAL_CONTROLLED","FIXTURE").contains(a.sourceKind())) throw new Blocked("ARTIFACT_SOURCE","当前产物来源类型不受支持");
    return db.tx(()-> {
      if(a.inputVersionIds()!=null) for(String input:a.inputVersionIds()) { var parent=artifact(input); if(parent==null || !parent.projectId().equals(a.projectId())) throw new Blocked("ARTIFACT_SCOPE","产物来源版本不可访问"); }
      db.update("INSERT INTO artifact_version(id,workspace_id,project_id,task_id,artifact_type,schema_version,content_digest,producer_node,producer_run,output_port,plan_revision_id,body,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",a.id(),a.workspaceId(),a.projectId(),a.taskId(),a.type().name(),a.schema(),a.contentDigest(),a.producerNode(),a.producerRun(),outputPort,revision,Canonical.json(a),clk.iso());
      if(a.inputVersionIds()!=null) for(String input:a.inputVersionIds()) db.update("INSERT INTO artifact_lineage(workspace_id,project_id,output_version_id,input_version_id) VALUES(?,?,?,?)",a.workspaceId(),a.projectId(),a.id(),input);
      return a;
    });
  }
  public void validate(Validation v) {
    var a=artifact(v.versionId()); if(a==null || !a.contentDigest().equals(v.checkedDigest())) throw new Blocked("VALIDATION_SUBJECT","校验记录与产物版本不一致");
    if(v.result()==ValidationResult.PASS && (v.evidenceRef()==null || v.evidenceRef().isBlank())) throw new Blocked("MISSING_EVIDENCE","通过状态必须提供可核验的证据引用");
    db.update("INSERT INTO artifact_validation(id,workspace_id,version_id,profile,result,checked_digest,body,created_at) VALUES(?,?,?,?,?,?,?,?)",U.uuid(),Tenant.writeId(),a.id(),v.profile(),v.result().name(),v.checkedDigest(),Canonical.json(v),clk.iso());
  }
  public boolean validated(ArtifactVersion a) {
    var r=db.row("SELECT result,checked_digest FROM artifact_validation WHERE workspace_id=? AND version_id=? AND profile='schema-v1' ORDER BY seq DESC LIMIT 1",Tenant.writeId(),a.id());
    return r!=null && "PASS".equals(r.get("result")) && a.contentDigest().equals(r.get("checked_digest"));
  }
  public Compiled compile(Plan p) {
    var task=task(p.taskId());
    if(!Tenant.writeId().equals(p.workspaceId()) || !Objects.equals(task.get("project_id"),p.projectId())) throw new Blocked("PLAN_SCOPE","计划不属于当前工作区或项目");
    if(p.budgetUsd()>Db.d(task.get("budget_usd"))-Db.d(task.get("spent_usd"))) throw new Blocked("BUDGET","计划费用超过父任务剩余额度");
    if(p.nodes()!=null && p.nodes().stream().anyMatch(n->n==null || n.bindings()!=null&&n.bindings().stream().anyMatch(Objects::isNull)))throw new Blocked("PLAN_SCHEMA","节点与输入绑定不能包含空条目");
    var structural=new PlanCompiler(registry.definitions(),this::artifact).compile(p);
    return structural.valid()?new PlanCompiler(registry.definitions(),this::artifact).compile(roles.lock(p)):structural;
  }
  public Compiled save(Plan submitted,int expectedVersion) {
    var compiled=compile(submitted); var p=compiled.plan(); if(!compiled.valid()) return compiled;
    db.tx(()-> {
      var task=lockTask(p.taskId());
      if(!"v3".equals(task.get("pipeline_version"))) throw new Blocked("LEGACY_ROUTE","旧版任务不能直接切换到新执行路径");
      if(Db.i(task.get("version"))!=expectedVersion) throw new Blocked("VERSION_CONFLICT","任务已变化，请刷新当前修订后重试");
      if(!Objects.equals(task.get("active_plan_revision_id"),p.parentRevisionId())) throw new Blocked("VERSION_CONFLICT","引用的上级计划已过期");
      if(db.count("SELECT COUNT(*) FROM node_run WHERE task_id=? AND workspace_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN')",p.taskId(),p.workspaceId())>0) throw new Blocked("RECOVERY_REQUIRED","存在未结束或结果未知的执行，请先核对后再修订计划");
      db.update("INSERT INTO workflow_plan_revision(id,workspace_id,project_id,task_id,revision_no,parent_revision_id,plan_hash,body,status,created_at) VALUES(?,?,?,?,?,?,?,?,'VALIDATED',?)",p.revisionId(),p.workspaceId(),p.projectId(),p.taskId(),p.revisionNo(),p.parentRevisionId(),compiled.hash(),Canonical.json(compiled),clk.iso());
      for(var n:p.nodes()) {
        db.update("INSERT INTO node_instance(id,plan_revision_id,workspace_id,project_id,task_id,definition_ref,body) VALUES(?,?,?,?,?,?,?)",n.id(),p.revisionId(),p.workspaceId(),p.projectId(),p.taskId(),n.definitionRef(),Canonical.json(n));
        if(n.bindings()!=null) for(var b:n.bindings()) db.update("INSERT INTO input_binding(id,plan_revision_id,node_id,workspace_id,project_id,task_id,port_name,ordinal,version_id,producer_node,output_port) VALUES(?,?,?,?,?,?,?,?,?,?,?)",b.id(),p.revisionId(),n.id(),p.workspaceId(),p.projectId(),p.taskId(),b.port(),b.ordinal(),b.versionId(),b.producerNode(),b.outputPort());
      }
      if(p.controls()!=null) for(var e:p.controls()) db.update("INSERT INTO control_dependency(plan_revision_id,from_node,to_node,workspace_id) VALUES(?,?,?,?)",p.revisionId(),e.from(),e.to(),p.workspaceId());
      if(db.update("UPDATE task SET version=version+1 WHERE id=? AND workspace_id=? AND version=?",p.taskId(),p.workspaceId(),expectedVersion)!=1) throw new Blocked("VERSION_CONFLICT","任务已被其他操作修改，请刷新后重试");
    }); return compiled;
  }
  public Compiled revision(String id) {
    var r=db.row("SELECT body FROM workflow_plan_revision WHERE id=? AND workspace_id=?",id,Tenant.writeId());
    if(r==null) throw new Blocked("NOT_FOUND","计划修订不可访问或不存在");return Json.parse(Db.str(r.get("body")),Compiled.class);
  }
  /** Approval binds identity, provenance and repository SHA, even when two versions share content. */
  public static String artifactApprovalDigest(ArtifactVersion artifact) { return Canonical.digest(Map.of("subjectType","ARTIFACT_VERSION@1","version",artifact)); }
  public String approve(String taskId,String digest,String actor,long validMs) {
    task(taskId); if(validMs<=0 || validMs>86400000) throw new IllegalArgumentException("批准有效期必须大于零且不超过一天");
    String id=U.uuid();db.update("INSERT INTO approval_record(id,workspace_id,task_id,subject_digest,decision,approver,expires_at,created_at) VALUES(?,?,?,?,'APPROVED',?,?,?)",id,Tenant.writeId(),taskId,digest,actor,Clk.iso(clk.now()+validMs),clk.iso());return id;
  }
  public boolean approved(String taskId,String digest) { return db.count("SELECT COUNT(*) FROM approval_record WHERE workspace_id=? AND task_id=? AND subject_digest=? AND decision='APPROVED' AND revoked_at IS NULL AND expires_at>?",Tenant.writeId(),taskId,digest,clk.iso())>0; }
  public void activate(String taskId,String revision,String hash,int expectedVersion) {
    db.tx(()-> {
      var c=revision(revision);var p=c.plan();var t=lockTask(taskId);
      if(db.count("SELECT COUNT(*) FROM workflow_plan_revision WHERE id=? AND workspace_id=? AND status='VALIDATED'",revision,Tenant.writeId())!=1) throw new Blocked("STALE_PLAN","只能激活已经校验且尚未执行的计划修订");
      if(!p.taskId().equals(taskId) || !c.hash().equals(hash) || !approved(taskId,hash)) throw new Blocked("WAITING_APPROVAL","当前计划摘要没有有效批准");
      var current=compile(p);if(!current.valid() || !current.hash().equals(hash))throw new Blocked("STALE_PLAN","当前输入、能力或策略与批准版本不一致，请重新校验候选");
      if(!"v3".equals(t.get("pipeline_version"))) throw new Blocked("LEGACY_ROUTE","旧任务的执行版本不能静默变更");
      if(db.count("SELECT COUNT(*) FROM node_run WHERE task_id=? AND workspace_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN')",taskId,Tenant.writeId())>0) throw new Blocked("RECOVERY_REQUIRED","存在活动执行，请先核对执行结果");
      if(db.update("UPDATE task SET active_plan_revision_id=?,version=version+1,next_run_at=?,execution_state='WAITING' WHERE id=? AND workspace_id=? AND version=?",revision,clk.iso(),taskId,Tenant.writeId(),expectedVersion)!=1) throw new Blocked("VERSION_CONFLICT","任务或计划版本已变化，请刷新后重试");
      db.update("UPDATE workflow_plan_revision SET status='SUPERSEDED' WHERE task_id=? AND workspace_id=? AND status='ACCEPTED'",taskId,Tenant.writeId());
      db.update("UPDATE workflow_plan_revision SET status='ACCEPTED',state_version=state_version+1 WHERE id=? AND workspace_id=? AND status='VALIDATED'",revision,Tenant.writeId());
    });
  }
  /** Invalidation is scoped to one plan and consumer closure; historical PASS records are retained. */
  public Set<String> invalidate(String taskId,String revision,String version,String reason) {
    var p=revision(revision).plan();if(!p.taskId().equals(taskId))throw new Blocked("PLAN_SCOPE","该计划不属于当前任务");
    Set<String> affected=new TreeSet<>();
    for(var r:db.rows("SELECT node_id FROM input_binding WHERE workspace_id=? AND plan_revision_id=? AND (version_id=? OR resolved_version_id=?)",Tenant.writeId(),revision,version,version)) affected.add(Db.str(r.get("node_id")));
    boolean changed=true;while(changed){changed=false;for(var n:p.nodes()) if(n.bindings()!=null && n.bindings().stream().anyMatch(b->b.producerNode()!=null && affected.contains(b.producerNode()))) changed|=affected.add(n.id());if(p.controls()!=null)for(var e:p.controls())if(affected.contains(e.from()))changed|=affected.add(e.to());}
    db.tx(()-> {for(String node:affected) if(db.count("SELECT COUNT(*) FROM artifact_invalidation WHERE plan_revision_id=? AND consumer_node=?",revision,node)==0) {
      db.update("INSERT INTO artifact_invalidation(id,workspace_id,task_id,plan_revision_id,consumer_node,reason,created_at) VALUES(?,?,?,?,?,?,?)",U.uuid(),Tenant.writeId(),taskId,revision,node,reason,clk.iso());
      db.update("UPDATE node_instance SET state='SUPERSEDED' WHERE plan_revision_id=? AND id=?",revision,node);
    }});return affected;
  }
}
