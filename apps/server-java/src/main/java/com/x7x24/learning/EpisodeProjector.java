package com.x7x24.learning;

import com.x7x24.db.Db;
import com.x7x24.engine.*;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

/** Bounded, idempotent projection. Delivery records are never rewritten or upgraded to stronger evidence. */
@Component
public class EpisodeProjector {
  private final LearningStore store; private final TaskRepo tasks;
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(EpisodeProjector.class);
  public EpisodeProjector(LearningStore store,TaskRepo tasks) { this.store=store; this.tasks=tasks; }
  @Scheduled(initialDelayString="${x7x24.learning.project-delay-ms:60000}",fixedDelayString="${x7x24.learning.project-delay-ms:60000}")
  public void tick() {
    if(!store.enabled)return;
    for(var p:store.db.rows("SELECT id,workspace_id FROM project WHERE state='ACTIVE' ORDER BY id LIMIT 100")) {
      try { Tenant.run(Db.str(p.get("workspace_id")),()->project(Db.str(p.get("id")))); }
      catch(RuntimeException e) { log.warn("学习样本整理失败：{}",e.getClass().getSimpleName()); }
    }
  }
  public synchronized Map<String,Object> project(String project) {
    store.requireEnabled(); String ws=store.scope(project); int added=0;
    var rows=store.db.rows("SELECT s.* FROM stage_execution s JOIN task t ON t.id=s.task_id WHERE t.workspace_id=? AND t.project_id=? AND s.ended_at IS NOT NULL AND s.state IN ('SUCCEEDED','FAILED','SKIPPED') AND NOT EXISTS (SELECT 1 FROM learning_episode e WHERE e.workspace_id=? AND e.source_kind='STAGE' AND e.source_id=s.id) ORDER BY s.started_at,s.id LIMIT 200",ws,project,ws);
    for(var row:rows) added+=episode(project,ws,row,"STAGE");
    rows=store.db.rows("SELECT r.* FROM node_run r WHERE workspace_id=? AND project_id=? AND ended_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM learning_episode e WHERE e.workspace_id=? AND e.source_kind='NODE' AND e.source_id=r.id) ORDER BY started_at,id LIMIT 200",ws,project,ws);
    for(var row:rows) added+=episode(project,ws,row,"NODE");
    for(var row:store.db.rows("SELECT * FROM task WHERE workspace_id=? AND project_id=? AND deleted_at IS NULL ORDER BY status_changed_at DESC LIMIT 200",ws,project)) {
      var t=TaskRepo.map(row); var labels=new TreeMap<String,Object>();
      labels.put("deliveryOutcome",t.releasedAt!=null?"RELEASE_CREATED":"UNKNOWN");
      labels.put("acceptanceOutcome","UNKNOWN");
      labels.put("delayedOutcome",t.rollbackCount>0?"ROLLBACK_RECORDED":"UNKNOWN");
      labels.put("productionDeployment","UNKNOWN"); labels.put("releasedAt",Objects.toString(t.releasedAt,""));
      labels.put("observationMature",false); labels.put("source","TASK_PROJECTION");
      String body=LearningStore.json(labels),digest=LearningStore.digest(body);
      if(store.db.count("SELECT COUNT(*) FROM learning_outcome WHERE workspace_id=? AND task_id=? AND digest=?",ws,t.id,digest)==0)
        store.db.update("INSERT INTO learning_outcome(id,workspace_id,project_id,task_id,body,digest,observed_at) VALUES(?,?,?,?,?,?,?)",LearningStore.id(),ws,project,t.id,body,digest,store.clk.iso());
    }
    return Map.of("added",added,"total",store.db.count("SELECT COUNT(*) FROM learning_episode WHERE workspace_id=? AND project_id=?",ws,project));
  }
  private int episode(String project,String ws,Map<String,Object> row,String kind) {
    Task t=tasks.get(Db.str(row.get("task_id"))); if(t==null)return 0;
    String root=t.id;Set<String> seen=new HashSet<>(); Task cursor=t;
    while(cursor.parentTaskId!=null && seen.add(cursor.id)) {
      Task parent=tasks.get(cursor.parentTaskId); if(parent==null || !project.equals(parent.projectId))break;
      root=parent.id;cursor=parent;
    }
    boolean legacy="STAGE".equals(kind);
    var in=Json.lenient(Db.str(row.get(legacy?"input":"inputs")));var out=Json.lenient(Db.str(row.get(legacy?"output":"result_manifest")));
    String state=Db.str(row.get("state")),error=Db.str(row.get("error"));
    String outcome=legacy && out!=null?out.path("outcome").asText("UNKNOWN"):"UNKNOWN";
    String failure=error==null?"":error;
    String edge=out==null?"":out.path("edge").asText("");
    var info=CiFailure.classify(failure);
    Map<String,Object> body=new LinkedHashMap<>();
    body.put("pipelineVersion",legacy?in==null?"UNKNOWN":in.path("pipelineVersion").asText("UNKNOWN"):"v3");
    body.put("node",Objects.toString(row.get(legacy?"stage":"node_id"),"UNKNOWN"));
    body.put("attempt",Db.i(row.get(legacy?"attempt":"attempt_no")));
    body.put("inputs",in);body.put("outputs",out); body.put("executionOutcome",state);
    body.put("testOutcome",legacy && "QA".equals(row.get("stage")) && "FAILED".equals(outcome)?"FAIL":"UNKNOWN");
    body.put("acceptanceOutcome","UNKNOWN");body.put("businessOutcome",outcome);
    body.put("failureClass",!failure.isBlank()?info.setup()?"INFRASTRUCTURE":"EXECUTION_ERROR":Set.of("qa_loop","node_fix","reject").contains(edge)?"VALIDATION_REJECTED":"UNKNOWN");
    body.put("error",failure);body.put("failureFingerprint",info.signature());
    body.put("baseSha",legacy?"UNKNOWN":planBase(ws,Db.str(row.get("plan_revision_id"))));
    body.put("headSha",out==null?"UNKNOWN":out.path("headSha").asText("UNKNOWN"));
    body.put("roleSnapshot",legacy?null:Json.lenient(Db.str(row.get("role_snapshot"))));
    body.put("usd",legacy?Db.d(row.get("usd")):null);body.put("tokens",legacy?Db.l(row.get("tokens")):null);
    body.put("startedAt",row.get("started_at"));body.put("endedAt",row.get("ended_at"));
    body.put("replayability","INCOMPLETE");body.put("missing",List.of("FIXED_REPOSITORY_FILES","EXECUTOR_ENVIRONMENT","INDEPENDENT_ACCEPTANCE"));
    body.put("evidenceAssociation",legacy?"UNLINKED_LEGACY":"EXPLICIT_ARTIFACT_LINEAGE");
    if(!legacy)body.put("artifacts",store.db.rows("SELECT id,content_digest,artifact_type FROM artifact_version WHERE workspace_id=? AND project_id=? AND producer_run=?",ws,project,row.get("id")));
    String json=LearningStore.json(body);
    store.db.update("INSERT INTO learning_episode(id,workspace_id,project_id,task_id,root_task_id,source_kind,source_id,body,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",LearningStore.id(),ws,project,t.id,root,kind,row.get("id"),json,LearningStore.digest(json),store.clk.iso());return 1;
  }
  private String planBase(String ws,String revision) {
    var p=store.db.row("SELECT body FROM workflow_plan_revision WHERE workspace_id=? AND id=?",ws,revision);
    return p==null?"UNKNOWN":LearningStore.body(p).path("baseSha").asText("UNKNOWN");
  }
}
