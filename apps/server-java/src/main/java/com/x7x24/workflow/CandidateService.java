package com.x7x24.workflow;
import com.x7x24.db.Db;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
import static com.x7x24.workflow.WorkflowStore.Blocked;
@Component
public class CandidateService {
 public record Request(int expectedVersion,String requestKey,Map<String,String> inputs,double budgetUsd,boolean repair) {}
 public record Candidate(String id,String parentId,int taskVersion,Compiled compiled,String provenance) {}
 private final WorkflowStore store;private final Db db;private final Clk clk;private final CandidatePlanner planner;
 @org.springframework.beans.factory.annotation.Autowired
 public CandidateService(WorkflowStore store,Db db,Clk clk){this(store,db,clk,new CandidatePlanner.Registered());}
 public CandidateService(WorkflowStore store,Db db,Clk clk,CandidatePlanner planner){this.store=store;this.db=db;this.clk=clk;this.planner=planner;}
 public Candidate generate(String taskId,Request request){
  var t=store.task(taskId);check(t,request.expectedVersion());key(request.requestKey());String digest=Canonical.digest(request);
  var cached=cached(taskId,request.requestKey(),digest);if(cached!=null)return cached;
  Map<String,String> inputs=request.inputs()==null?Map.of():new TreeMap<>(request.inputs());
  String sha="";Map<String,Boolean> facts=new TreeMap<>();
  for(var input:inputs.entrySet()){
   var a=store.artifact(input.getValue());if(a==null || !a.projectId().equals(t.get("project_id")))throw new Blocked("ARTIFACT_SCOPE","候选输入不属于当前项目");
   if(input.getKey().equals("repository"))sha=a.baseSha();
   if(input.getKey().equals("scopeEvidence")){var n=Json.tree(a.content()).path("features");for(String name:List.of("requiresUiChange","requiresApiChange"))if(n.path(name).isBoolean())facts.put(name,n.path(name).asBoolean());}
  }
  var context=new CandidatePlanner.Context(Db.str(t.get("problem")),sha,facts,inputs,List.copyOf(store.registry.definitions().values()),request.repair());
  var proposal=planner.propose(context);
  var p=new Plan(3,U.uuid(),Tenant.writeId(),Db.str(t.get("project_id")),taskId,nextRevision(taskId),Db.str(t.get("active_plan_revision_id")),sha,"server-derived",request.budgetUsd(),proposal.nodes(),proposal.controls(),facts);
  return persist(taskId,request.expectedVersion(),request.requestKey(),digest,null,store.compile(p),proposal.provenance());
 }
 public Candidate edit(String taskId,String parentId,int expectedVersion,String requestKey,Plan draft){
  var t=store.task(taskId);check(t,expectedVersion);key(requestKey);var parent=get(taskId,parentId);
  if(!taskId.equals(draft.taskId()) || !parent.compiled().plan().workspaceId().equals(draft.workspaceId()) || !parent.compiled().plan().projectId().equals(draft.projectId()))throw new Blocked("PLAN_SCOPE","草稿不属于当前候选任务");
  String digest=Canonical.digest(draft);var cached=cached(taskId,requestKey,digest);if(cached!=null)return cached;
  var p=new Plan(3,U.uuid(),Tenant.writeId(),Db.str(t.get("project_id")),taskId,nextRevision(taskId),Db.str(t.get("active_plan_revision_id")),draft.baseSha(),"server-derived",draft.budgetUsd(),draft.nodes(),draft.controls(),draft.facts());
  return persist(taskId,expectedVersion,requestKey,digest,parent.id(),store.compile(p),"user-edit@1");
 }
 public Compiled accept(String taskId,String id,int expectedVersion,String expectedHash){
  var c=get(taskId,id);check(store.task(taskId),expectedVersion);
  if(!c.compiled().valid() || !c.compiled().hash().equals(expectedHash))throw new Blocked("STALE_CANDIDATE","请先校验并保存准确版本的候选");
  var current=store.compile(c.compiled().plan());if(!current.valid() || !current.hash().equals(expectedHash))throw new Blocked("STALE_CANDIDATE","角色、策略或输入已变化，请重新生成候选");
  return store.save(c.compiled().plan(),expectedVersion);
 }
 public Candidate get(String taskId,String id){store.task(taskId);var r=db.row("SELECT body FROM workflow_candidate WHERE workspace_id=? AND task_id=? AND id=?",Tenant.writeId(),taskId,id);if(r==null)throw new Blocked("NOT_FOUND","候选不存在或不可访问");return Json.parse(Db.str(r.get("body")),Candidate.class);}
 private Candidate cached(String taskId,String key,String digest){var r=db.row("SELECT request_digest,body FROM workflow_candidate WHERE workspace_id=? AND task_id=? AND request_key=?",Tenant.writeId(),taskId,key);if(r==null)return null;if(!digest.equals(r.get("request_digest")))throw new Blocked("COMMAND_CONFLICT","相同请求标识不能用于不同内容");return Json.parse(Db.str(r.get("body")),Candidate.class);}
 private Candidate persist(String taskId,int version,String key,String digest,String parent,Compiled compiled,String provenance){return db.tx(()->{
  var t=store.lockTask(taskId);check(t,version);var c=new Candidate(U.uuid(),parent,version,compiled,provenance);
  db.update("INSERT INTO workflow_candidate(id,workspace_id,project_id,task_id,parent_candidate_id,request_key,request_digest,expected_task_version,body,provenance,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",c.id(),Tenant.writeId(),t.get("project_id"),taskId,parent,key,digest,version,Canonical.json(c),provenance,clk.iso());return c;
 });}
 private int nextRevision(String taskId){return Db.i(db.row("SELECT COALESCE(MAX(revision_no),0)+1 n FROM workflow_plan_revision WHERE task_id=? AND workspace_id=?",taskId,Tenant.writeId()).get("n"));}
 private void check(Map<String,Object> t,int expected){if(!"v3".equals(t.get("pipeline_version")))throw new Blocked("LEGACY_ROUTE","旧任务不能通过新候选接口改变执行语义");if(Db.i(t.get("version"))!=expected)throw new Blocked("VERSION_CONFLICT","任务已变化，请刷新后重新编辑候选");}
 private void key(String key){if(key==null || !key.matches("[A-Za-z0-9_-]{8,100}"))throw new IllegalArgumentException("请提供稳定的请求标识");}
}
