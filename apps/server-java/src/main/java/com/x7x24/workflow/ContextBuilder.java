package com.x7x24.workflow;
import com.x7x24.db.Db;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
/** Deterministic bounded projection. Required material is never silently summarized or omitted. */
@Component
public class ContextBuilder {
 public record Budget(int contextWindow,int outputReserve,int safetyMargin) {public int usable(){return contextWindow-outputReserve-safetyMargin;}}
 public record Entry(String port,String versionId,String digest,int estimatedTokens,boolean included,String reason) {}
 public record Envelope(Map<String,Map<String,Object>> artifacts,List<Entry> manifest,int estimatedTokens,int usableTokens,String digest) {}
 private final Db db;private final Clk clk;
 public ContextBuilder(Db db,Clk clk){this.db=db;this.clk=clk;}
 public Envelope build(Plan plan,Node node,Run run,Map<String,ArtifactVersion> versions,String instructions,Budget budget){
  if(budget.contextWindow()<1 || budget.contextWindow()>128000 || budget.outputReserve()<0 || budget.safetyMargin()<0 || budget.usable()<=0)throw new WorkflowStore.Blocked("CONTEXT_BUDGET_EXCEEDED","上下文预算不足以容纳必留材料");
  Map<String,Map<String,Object>> artifacts=new LinkedHashMap<>();var manifest=new ArrayList<Entry>();
  int used=estimate(Canonical.json(Map.of("goal",node.logicalKey(),"baseSha",plan.baseSha(),"policy",plan.policyDigest(),"role",instructions,"facts",plan.facts())));
  var definition=Definitions.standard().get(node.definitionRef());
  for(var input:run.inputs()){
   if(input.versionId().equals("ABSENT")){manifest.add(new Entry(input.port(),"ABSENT","ABSENT",0,false,"EXPLICIT_ABSENT"));continue;}
   var a=versions.get(input.versionId());if(a==null || !a.workspaceId().equals(plan.workspaceId()) || !a.projectId().equals(plan.projectId()) || !a.contentDigest().equals(input.contentDigest()) || !Canonical.digest(Json.tree(a.content())).equals(a.contentDigest()))throw new WorkflowStore.Blocked("CONTEXT_INPUT","上下文产物来源、权限或摘要不匹配");
   var entry=Map.<String,Object>of("versionId",a.id(),"digest",a.contentDigest(),"source",a.sourceKind(),"baseSha",Objects.toString(a.baseSha(),""),"data",Json.tree(a.content()));int tokens=estimate(Canonical.json(entry));
   var port=definition.inputs().stream().filter(p->p.name().equals(input.port())).findFirst().orElseThrow();boolean required=port.requirement()!=Requirement.OPTIONAL;
   if(used+tokens>budget.usable()){
    if(required)throw new WorkflowStore.Blocked("CONTEXT_BUDGET_EXCEEDED","必需输入超出上下文预算，请拆分任务或调整已批准合同："+input.port());
    manifest.add(new Entry(input.port(),a.id(),a.contentDigest(),tokens,false,"OPTIONAL_BUDGET_OMISSION"));continue;
   }
   used+=tokens;artifacts.put(input.port()+"["+input.ordinal()+"]",entry);manifest.add(new Entry(input.port(),a.id(),a.contentDigest(),tokens,true,"BOUND_VERSION"));
  }
  return new Envelope(Collections.unmodifiableMap(artifacts),List.copyOf(manifest),used,budget.usable(),Canonical.digest(Map.of("manifest",manifest,"roleDigest",Canonical.digest(instructions),"inputFingerprint",run.fingerprint(),"budget",budget)));
 }
 public void record(Plan p,Run run,Envelope envelope){db.update("INSERT INTO context_manifest(id,workspace_id,task_id,node_run_id,digest,body,created_at) VALUES(?,?,?,?,?,?,?)",U.uuid(),p.workspaceId(),p.taskId(),run.id(),envelope.digest(),Canonical.json(Map.of("entries",envelope.manifest(),"estimatedTokens",envelope.estimatedTokens(),"usableTokens",envelope.usableTokens())),clk.iso());}
 public static int estimate(String text){return Math.max(1,(text.getBytes(StandardCharsets.UTF_8).length+2)/3);}
 public static void guardRequest(String system,String prompt,int contextWindow){if(estimate(system)+estimate(prompt)>contextWindow-4096-1024)throw new WorkflowStore.Blocked("CONTEXT_BUDGET_EXCEEDED","模型请求连同必留规则已超出上下文预算，请缩小或拆分任务");}
}
