package com.x7x24.workflow;
import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.auth.*;
import com.x7x24.db.Db;
import com.x7x24.engine.*;
import com.x7x24.tenant.*;
import com.x7x24.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
@RestController
@RequestMapping("/api")
public class WorkflowApi {
  final WorkflowStore store;final WorkflowScheduler scheduler;final Db db;final Clk clk;final Engine engine;final ProjectRepo projects;final WorkflowRuntime runtime;final CandidateService candidates;final com.x7x24.execution.LocalCodingExecutor executor;final com.x7x24.skills.SkillReleases releases;
  @org.springframework.beans.factory.annotation.Autowired RecoveryService recovery;
  @Value("${x7x24.workflow-v3-admission:false}") boolean admission;
  public WorkflowApi(WorkflowStore store,WorkflowScheduler scheduler,Db db,Clk clk,Engine engine,ProjectRepo projects,WorkflowRuntime runtime,CandidateService candidates,com.x7x24.execution.LocalCodingExecutor executor,com.x7x24.skills.SkillReleases releases){this.store=store;this.scheduler=scheduler;this.db=db;this.clk=clk;this.engine=engine;this.projects=projects;this.runtime=runtime;this.candidates=candidates;this.executor=executor;this.releases=releases;}
  @GetMapping("/node-definitions") public Object definitions(){return Map.of("schema",3,"parallel",false,"newTaskAdmission",admission,"definitions",store.registry.definitions().values());}
  @PostMapping("/projects/{projectId}/workflow-tasks") public Object create(@PathVariable String projectId,@RequestBody JsonNode body,HttpServletRequest req){
    WorkspaceFilter.requireMember(req);if(!admission)throw new WorkflowStore.Blocked("ADMISSION_DISABLED","当前未开放 schema=3 新任务准入");
    var project=projects.require(projectId);String title=body.path("title").asText("").trim();if(title.isEmpty())throw new IllegalArgumentException("请填写任务标题");
    return db.tx(()->{var r=engine.ingest(new Engine.Ingest(project.productLineId(),title,body.path("goal").asText(title),"typed-workflow",U.uuid(),null,false,null,projectId,null,null));db.update("UPDATE task SET pipeline_version='v3',current_stage='BACKLOG',execution_state='WAITING',next_run_at=NULL WHERE id=? AND workspace_id=?",r.taskId(),Tenant.writeId());return Map.of("taskId",r.taskId(),"pipelineVersion","v3");});
  }
  @PostMapping("/tasks/{taskId}/plan-candidates") public Object generate(@PathVariable String taskId,@RequestBody CandidateService.Request request){return candidates.generate(taskId,request);}
  @PutMapping("/tasks/{taskId}/plan-candidates/{candidateId}") public Object editCandidate(@PathVariable String taskId,@PathVariable String candidateId,@RequestBody JsonNode b){return candidates.edit(taskId,candidateId,b.path("expectedVersion").asInt(-1),b.path("requestKey").asText(null),Json.convert(b.path("plan"),Plan.class));}
  @PostMapping("/tasks/{taskId}/plan-candidates/{candidateId}/accept") public Object acceptCandidate(@PathVariable String taskId,@PathVariable String candidateId,@RequestBody JsonNode b){return candidates.accept(taskId,candidateId,b.path("expectedVersion").asInt(-1),b.path("hash").asText());}
  @PostMapping("/tasks/{taskId}/plans/validate") public Object validate(@PathVariable String taskId,@RequestBody Plan p){sameTask(taskId,p);return store.compile(p);}
  @PostMapping("/tasks/{taskId}/plan-revisions") public Object save(@PathVariable String taskId,@RequestBody JsonNode b){var p=Json.convert(b.path("plan"),Plan.class);sameTask(taskId,p);return store.save(p,b.path("expectedVersion").asInt(-1));}
  @PostMapping("/tasks/{taskId}/plan-revisions/{revision}/activate") public Object activate(@PathVariable String taskId,@PathVariable String revision,@RequestBody JsonNode b){store.activate(taskId,revision,b.path("hash").asText(),b.path("expectedVersion").asInt(-1));return Map.of("activated",true);}
  @PostMapping("/tasks/{taskId}/workflow-approvals") public Object approve(@PathVariable String taskId,@RequestBody JsonNode b,HttpServletRequest req){
    WorkspaceFilter.requireManager(req,"批准工作流合同");expected(taskId,b);String digest;
    if(b.hasNonNull("revisionId")){var c=store.revision(b.path("revisionId").asText());sameTask(taskId,c.plan());digest=c.hash();if(!digest.equals(b.path("subjectDigest").asText()))throw new WorkflowStore.Blocked("STALE_APPROVAL","计划内容已变化，请重新核对后批准");}
    else {var a=store.artifact(b.path("versionId").asText());if(a==null || !a.taskId().equals(taskId))throw new WorkflowStore.Blocked("ARTIFACT_SCOPE","产物不可访问或不存在");digest=WorkflowStore.artifactApprovalDigest(a);if(!digest.equals(b.path("subjectDigest").asText()))throw new WorkflowStore.Blocked("STALE_APPROVAL","产物内容摘要已变化，原审批不再适用");}
    String actor=req.getAttribute(AuthFilter.ATTR) instanceof AuthService.User u?u.login():"operator";return Map.of("approvalId",store.approve(taskId,digest,actor,3600000));
  }
  @PostMapping("/tasks/{taskId}/workflow-approvals/{approval}/revoke") public Object revoke(@PathVariable String taskId,@PathVariable String approval,HttpServletRequest req){WorkspaceFilter.requireManager(req,"撤销批准");store.task(taskId);db.update("UPDATE approval_record SET revoked_at=? WHERE id=? AND task_id=? AND workspace_id=?",clk.iso(),approval,taskId,Tenant.writeId());return Map.of("revoked",true);}
  @PostMapping("/tasks/{taskId}/artifacts") public Object importArtifact(@PathVariable String taskId,@RequestBody JsonNode b){
    var t=store.task(taskId);var type=ArtifactType.valueOf(b.path("type").asText());String content=Canonical.json(b.path("content"));
    var a=new ArtifactVersion(U.uuid(),Tenant.writeId(),Db.str(t.get("project_id")),taskId,type,1,Canonical.digest(b.path("content")),content,"USER",null,null,b.path("baseSha").asText(null),List.of());store.publish(a,null,null);
    store.validate(new Validation(a.id(),"json-schema-envelope@1","schema-v1",ValidationResult.PASS,a.contentDigest(),"artifact:"+a.id(),clk.iso()));return a;
  }
  @PostMapping("/tasks/{taskId}/nodes/{nodeId}/human-output") public Object human(@PathVariable String taskId,@PathVariable String nodeId,@RequestBody JsonNode b){
    expected(taskId,b);var t=store.task(taskId);var c=store.revision(Db.str(t.get("active_plan_revision_id")));var node=c.plan().nodes().stream().filter(n->n.id().equals(nodeId)).findFirst().orElseThrow();if(Definitions.standard().get(node.definitionRef()).executor()!=ExecutorKind.HUMAN)throw new WorkflowStore.Blocked("EXECUTOR_KIND","当前节点不接受人工提交");
    var run=scheduler.start(taskId,nodeId);try{Map<String,String> outputs=new TreeMap<>();b.path("outputs").fields().forEachRemaining(e->outputs.put(e.getKey(),Canonical.json(e.getValue())));scheduler.complete(taskId,run,outputs,"USER");recovery.markResolved(com.x7x24.engine.TaskRepo.map(store.task(taskId)));db.update("UPDATE task SET execution_state='WAITING',next_run_at=?,version=version+1 WHERE id=? AND workspace_id=?",clk.iso(),taskId,Tenant.writeId());return Map.of("nodeRunId",run.id());}catch(RuntimeException e){scheduler.fail(taskId,run,e);throw e;}
  }
  @GetMapping("/tasks/{taskId}/execution") public Object execution(@PathVariable String taskId){var t=store.task(taskId);Map<String,Object> result=new LinkedHashMap<>();result.put("runtime",t.get("pipeline_version"));result.put("artifacts",db.rows("SELECT id,artifact_type,content_digest,body,created_at FROM artifact_version WHERE workspace_id=? AND task_id=? ORDER BY created_at DESC",Tenant.writeId(),taskId).stream().map(row->{var copy=new LinkedHashMap<>(row);copy.put("approval_subject",WorkflowStore.artifactApprovalDigest(Json.parse(Db.str(row.get("body")),ArtifactVersion.class)));return copy;}).toList());result.put("plans",db.rows("SELECT id,plan_hash,status,body,created_at FROM workflow_plan_revision WHERE workspace_id=? AND task_id=? ORDER BY revision_no DESC",Tenant.writeId(),taskId));result.put("candidates",db.rows("SELECT id,body,created_at FROM workflow_candidate WHERE workspace_id=? AND task_id=? ORDER BY created_at DESC LIMIT 50",Tenant.writeId(),taskId));result.put("approvals",db.rows("SELECT id,subject_digest,decision,expires_at,revoked_at FROM approval_record WHERE workspace_id=? AND task_id=? ORDER BY created_at DESC",Tenant.writeId(),taskId));result.put("activeRevisionId",t.get("active_plan_revision_id"));result.put("taskVersion",t.get("version"));result.put("blockCode",t.get("workflow_block_code"));result.put("blockReason",t.get("workflow_block_reason"));result.put("nodes",db.rows("SELECT id,plan_revision_id,definition_ref,state,body FROM node_instance WHERE workspace_id=? AND task_id=? ORDER BY plan_revision_id,id",Tenant.writeId(),taskId));result.put("runs",db.rows("SELECT * FROM node_run WHERE workspace_id=? AND task_id=? ORDER BY started_at,id",Tenant.writeId(),taskId));result.put("bindings",db.rows("SELECT * FROM input_binding WHERE workspace_id=? AND task_id=?",Tenant.writeId(),taskId));result.put("invalidations",db.rows("SELECT * FROM artifact_invalidation WHERE workspace_id=? AND task_id=?",Tenant.writeId(),taskId));return result;}
  @PostMapping("/tasks/{taskId}/runs/{runId}/confirm-stopped") public Object recover(@PathVariable String taskId,@PathVariable String runId,@RequestBody JsonNode b,HttpServletRequest req){
    WorkspaceFilter.requireManager(req,"确认旧执行者停止并恢复");store.task(taskId);
    if(!b.path("oldExecutorStopped").asBoolean(false) || runtime.locallyRunning(runId))throw new WorkflowStore.Blocked("RECOVERY_REQUIRED","旧执行者仍可能运行，不能启动第二个执行者");
    String actor=req.getAttribute(AuthFilter.ATTR) instanceof AuthService.User u?u.login():"operator";
    scheduler.recoverStopped(taskId,runId,actor,b.path("stopEvidenceRef").asText(null));return Map.of("recovered",true);
  }
  @GetMapping("/tasks/{taskId}/executor-jobs") public Object jobs(@PathVariable String taskId){store.task(taskId);return db.rows("SELECT id,node_run_id,state,event_seq,checkpoint_ref,result_digest,created_at,updated_at FROM coding_job WHERE workspace_id=? AND task_id=? ORDER BY created_at",Tenant.writeId(),taskId);}
  @GetMapping("/tasks/{taskId}/executor-jobs/{jobId}") public Object job(@PathVariable String taskId,@PathVariable String jobId,@RequestParam(defaultValue="0") long after){store.task(taskId);return Map.of("handle",executor.get(Tenant.writeId(),taskId,jobId),"events",executor.events(Tenant.writeId(),taskId,jobId,after));}
  @GetMapping("/tasks/{taskId}/executor-jobs/{jobId}/result") public Object jobResult(@PathVariable String taskId,@PathVariable String jobId){store.task(taskId);return executor.exportResult(Tenant.writeId(),taskId,jobId);}
  @PostMapping("/tasks/{taskId}/executor-jobs/{jobId}/cancel") public Object cancelJob(@PathVariable String taskId,@PathVariable String jobId,@RequestBody JsonNode b){expected(taskId,b);return executor.cancel(Tenant.writeId(),taskId,jobId,b.path("reason").asText("用户请求停止"));}
  @GetMapping("/tasks/{taskId}/context-manifests") public Object contexts(@PathVariable String taskId){store.task(taskId);return db.rows("SELECT id,node_run_id,digest,body,created_at FROM context_manifest WHERE workspace_id=? AND task_id=? ORDER BY created_at",Tenant.writeId(),taskId);}
  @GetMapping("/tasks/{taskId}/skill-evaluations") public Object evaluations(@PathVariable String taskId){store.task(taskId);return db.rows("SELECT id,node_run_id,set_digest,body,created_at FROM skill_evaluation WHERE workspace_id=? AND task_id=? ORDER BY created_at",Tenant.writeId(),taskId);}
  @PostMapping("/skill-releases/revoke") public Object revokeSkill(@RequestBody JsonNode b,HttpServletRequest req){WorkspaceFilter.requireManager(req,"撤销技能版本");String actor=req.getAttribute(AuthFilter.ATTR) instanceof AuthService.User u?u.login():"operator";releases.revoke(b.path("ref").asText(null),b.path("reason").asText(null),actor);return Map.of("revoked",true);}
  @GetMapping("/tasks/{taskId}/tool-operations") public Object operations(@PathVariable String taskId){
    store.task(taskId);String prefix=taskId+":";
    return db.rows("SELECT op_key,state,request_digest,created_at,updated_at FROM tool_ops WHERE LEFT(op_key,?)=? ORDER BY created_at",prefix.length(),prefix);
  }
  @GetMapping("/artifacts/{versionId}/manifest") public Object artifact(@PathVariable String versionId){var a=store.artifact(versionId);if(a==null)throw new WorkflowStore.Blocked("NOT_FOUND","产物不可访问或不存在");return a;}
  private void expected(String taskId,JsonNode body){if(Db.i(store.task(taskId).get("version"))!=body.path("expectedVersion").asInt(-1))throw new WorkflowStore.Blocked("VERSION_CONFLICT","任务版本已变化，请刷新后核对本次操作");}
  private void sameTask(String taskId,Plan p){store.task(taskId);if(!taskId.equals(p.taskId()))throw new IllegalArgumentException("请求中的任务与计划不一致");}
  @ExceptionHandler(WorkflowStore.Blocked.class) public ResponseEntity<?> blocked(WorkflowStore.Blocked e){return ResponseEntity.status(e.code.equals("NOT_FOUND")?404:409).body(Map.of("error",e.code,"message",e.getMessage()));}
}
