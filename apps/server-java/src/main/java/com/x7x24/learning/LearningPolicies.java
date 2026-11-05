package com.x7x24.learning;

import com.x7x24.agents.Models;
import com.x7x24.db.Db;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import com.x7x24.workflow.Canonical;
import org.springframework.stereotype.Component;
import java.util.*;

/** Stable task assignment. This first rollout supports legacy v1/v2 repairs; typed roles remain unchanged. */
@Component
public class LearningPolicies {
  private final LearningStore store;private final LearningModel model;
  public LearningPolicies(LearningStore store,LearningModel model) {this.store=store;this.model=model;}
  public void attach(Models.Ctx context) {
    var task=context.task();
    if(task!=null && task.context!=null)task.context.extra.remove("learningInstructions");
    if(!store.enabled || task==null || context.executionMode()!=Models.ExecutionMode.REPAIR || context.address()!=null)return;
    String ws=store.scope(task.projectId);
    if(!ws.equals(task.workspaceId))throw new IllegalArgumentException("任务不属于当前学习工作区");
    var assignment=store.db.tx(()->{
      store.db.row("SELECT id FROM task WHERE id=? AND workspace_id=? FOR UPDATE",task.id,ws);
      var existing=store.db.row("SELECT * FROM learning_assignment WHERE workspace_id=? AND task_id=?",ws,task.id);if(existing!=null)return existing;
      // Only tasks created after the release can join. A baseline assignment is persisted too.
      var release=store.db.row("SELECT r.* FROM learning_release r WHERE r.workspace_id=? AND r.project_id=? AND r.state='CANARY' AND NOT EXISTS (SELECT 1 FROM learning_release revoked WHERE revoked.workspace_id=r.workspace_id AND revoked.project_id=r.project_id AND revoked.policy_id=r.policy_id AND revoked.state='REVOKED') ORDER BY r.created_at DESC,r.id DESC LIMIT 1",ws,task.projectId);
      String policy=Learning.BASELINE,releaseId=null;
      if(release!=null && "CANARY".equals(release.get("state")) && task.createdAt.compareTo(Db.str(release.get("created_at")))>=0
          && bucket(task.id,Db.str(release.get("id")))<Db.i(release.get("percentage"))) {
        policy=Db.str(release.get("policy_id"));releaseId=Db.str(release.get("id"));
      }
      store.db.update("INSERT INTO learning_assignment(workspace_id,project_id,task_id,policy_id,release_id,created_at) VALUES(?,?,?,?,?,?)",ws,task.projectId,task.id,policy,releaseId,store.clk.iso());
      return store.db.row("SELECT * FROM learning_assignment WHERE workspace_id=? AND task_id=?",ws,task.id);
    });
    String policyId=Db.str(assignment.get("policy_id"));
    if(Learning.BASELINE.equals(policyId)){task.context.extra.remove("learningInstructions");task.context.extra.put("learningPolicy",Learning.BASELINE);return;}
    if(store.db.count("SELECT COUNT(*) FROM learning_release WHERE workspace_id=? AND project_id=? AND policy_id=? AND state='REVOKED'",ws,task.projectId,policyId)>0)
      throw new IllegalArgumentException("本任务使用的返修策略已撤销，请核对后恢复任务");
    var policy=store.get("learning_policy",task.projectId,policyId);
    if(!LearningStore.digest(Db.str(policy.get("body"))).equals(policy.get("digest")))throw new IllegalArgumentException("返修策略内容校验未通过");
    if(!LearningStore.body(policy).path("modelConfigDigest").asText().equals(model.fingerprint(task.projectId)))throw new IllegalArgumentException("返修策略的模型或角色配置已变化，需要重新验证");
    task.context.extra.put("learningPolicy",policyId);task.context.extra.put("learningPolicyDigest",policy.get("digest"));
    task.context.extra.put("learningInstructions",LearningStore.body(policy).path("instructions").asText());
  }
  static int bucket(String task,String release) {return (int)(Long.parseUnsignedLong(U.sha256(task+":"+release).substring(0,8),16)%100);}
  public Models.ExecAgents wrap(Models.ExecAgents base) {
    if(!(base instanceof com.x7x24.agents.LlmAgents))return base;
    return new Models.ExecAgents() {
      public String mode(){return base.mode();} public String model(){return base.model();}
      public Models.Prd draftPrd(Models.Ctx c){return base.draftPrd(c);}public Models.Plan plan(Models.Ctx c){return base.plan(c);}
      public List<Models.Case> specCases(Models.Ctx c){return base.specCases(c);}public Map<String,Object> design(Models.Ctx c){return base.design(c);}
      public Models.CodeChange code(Models.Ctx c){
        attach(c);boolean observe=store.enabled && c.executionMode()==Models.ExecutionMode.REPAIR && c.address()==null;
        if(observe)recordUse(c);
        try {var result=base.code(c);if(observe)recordResult(c,"SUCCEEDED",LearningStore.digest(LearningStore.json(result)));return result;}
        catch(RuntimeException e){if(observe)recordResult(c,"FAILED",e.getClass().getSimpleName());throw e;}
      }
    };
  }
  private void recordUse(Models.Ctx c) {
    var t=c.task();String ws=store.scope(t.projectId),source=t.id+":v"+t.version;
    if(store.db.count("SELECT COUNT(*) FROM learning_episode WHERE workspace_id=? AND source_kind='POLICY_USE' AND source_id=?",ws,source)>0)return;
    String root=t.id;String parent=t.parentTaskId;Set<String> seen=new HashSet<>();
    while(parent!=null && seen.add(parent)) {var p=store.db.row("SELECT id,parent_task_id FROM task WHERE workspace_id=? AND project_id=? AND id=?",ws,t.projectId,parent);if(p==null)break;root=parent;parent=Db.str(p.get("parent_task_id"));}
    var body=new LinkedHashMap<String,Object>();body.put("role","dev.fix");body.put("taskVersion",t.version);body.put("policyId",t.context.extra.get("learningPolicy"));body.put("policyDigest",t.context.extra.get("learningPolicyDigest"));body.put("executionOutcome","DISPATCHED");body.put("testOutcome","UNKNOWN");body.put("replayability","INCOMPLETE");body.put("goal",t.problem);body.put("failure",U.ellipsis(c.ciLog(),4000));body.put("failureTruncated",c.ciLog()!=null&&c.ciLog().length()>4000);body.put("baseSha",t.context.pr==null?"UNKNOWN":Objects.toString(t.context.pr.headSha,"UNKNOWN"));
    String json=LearningStore.json(body);
    store.db.update("INSERT INTO learning_episode(id,workspace_id,project_id,task_id,root_task_id,source_kind,source_id,body,digest,created_at) VALUES(?,?,?,?,?,'POLICY_USE',?,?,?,?)",LearningStore.id(),ws,t.projectId,t.id,root,source,json,LearningStore.digest(json),store.clk.iso());
  }
  private void recordResult(Models.Ctx c,String state,String result) {
    var t=c.task();String body=LearningStore.json(Map.of("source","POLICY_USE","runKey",t.id+":v"+t.version,"executionOutcome",state,"testOutcome","UNKNOWN","resultDigestOrError",result)),digest=LearningStore.digest(body),ws=store.scope(t.projectId);
    if(store.db.count("SELECT COUNT(*) FROM learning_outcome WHERE workspace_id=? AND task_id=? AND digest=?",ws,t.id,digest)==0)store.db.update("INSERT INTO learning_outcome(id,workspace_id,project_id,task_id,body,digest,observed_at) VALUES(?,?,?,?,?,?,?)",LearningStore.id(),ws,t.projectId,t.id,body,digest,store.clk.iso());
  }
  /** Legacy JSON consumes only a freshly checked assignment. Safety rules follow this text. */
  public static String instructions(com.x7x24.engine.Task task) {
    return task!=null && task.context!=null && task.context.extra.get("learningInstructions") instanceof String text?
        "\n已验证的返修策略（低于安全规则）：\n"+text:"";
  }
}
