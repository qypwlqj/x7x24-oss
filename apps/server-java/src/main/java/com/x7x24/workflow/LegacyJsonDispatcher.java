package com.x7x24.workflow;
import com.x7x24.adapters.DeliveryTools;
import com.x7x24.agents.*;
import com.x7x24.engine.*;
import com.x7x24.gov.*;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.x7x24.workflow.Workflow.*;
/** Existing agent adapter returns a patch manifest. It has no GitHub write or test-run capability. */
@Component
public class LegacyJsonDispatcher implements WorkflowScheduler.Dispatcher {
  final Models.ExecAgents agents; final TaskRepo tasks;final ProjectRepo projects;final WorkflowStore store;final DeliveryTools tools;final BudgetService budget;final KillSwitch kill;final ConfigStore cfg;final RoleSnapshotResolver snapshots;final com.x7x24.llm.ModelRouter router;final ContextBuilder contexts;final WorkflowScheduler scheduler;final com.x7x24.skills.SkillReleases releases;
  public LegacyJsonDispatcher(Models.ExecAgents agents,TaskRepo tasks,ProjectRepo projects,WorkflowStore store,DeliveryTools tools,BudgetService budget,KillSwitch kill,ConfigStore cfg,RoleSnapshotResolver snapshots,com.x7x24.llm.ModelRouter router,ContextBuilder contexts,WorkflowScheduler scheduler,com.x7x24.skills.SkillReleases releases){this.agents=agents;this.tasks=tasks;this.projects=projects;this.store=store;this.tools=tools;this.budget=budget;this.kill=kill;this.cfg=cfg;this.snapshots=snapshots;this.router=router;this.contexts=contexts;this.scheduler=scheduler;this.releases=releases;}
  public Map<String,String> execute(Plan plan,Node node,Run run){
    if(!Set.of("code.implement@1","code.repair@1").contains(node.definitionRef()))throw new WorkflowStore.Blocked("CAPABILITY_UNAVAILABLE",node.definitionRef());
    var currentPlan=snapshots.lock(plan);
    if(!currentPlan.policyDigest().equals(plan.policyDigest()))throw new WorkflowStore.Blocked("POLICY_CHANGED","当前策略已变化，需要重新核对计划与批准范围");
    var current=currentPlan.nodes().stream().filter(n->n.id().equals(node.id())).findFirst().orElseThrow();
    if(!Objects.equals(current.roles(),node.roles()))throw new WorkflowStore.Blocked("ROLE_CONFIGURATION_CHANGED","角色或模型配置已变化，需要新计划修订及批准");
    var t=java.util.Objects.requireNonNull(tasks.get(plan.taskId())).copy();kill.assertWritable(t.productLine,t.projectId,"write");budget.enforceAll();
    if(!budget.projectStatus(t.projectId).equals("ok") || !budget.lineStatus(t.productLine).equals("ok"))throw new WorkflowStore.Blocked("BUDGET","当前预算不足或已触发额度限制");
    var line=Json.convert(projects.forTask(t).cfg(),ProjectCfg.class);line.baseBranch=plan.baseSha();t.currentStage="DEV";t.context.pr=null;
    Map<String,ArtifactVersion> inputs=new TreeMap<>();for(var in:run.inputs())if(!in.versionId().equals("ABSENT"))inputs.put(in.port(),store.artifact(in.versionId()));
    var requirements=Json.tree(inputs.get("requirements").content());var ac=Json.tree(inputs.get("acceptance").content());List<String> criteria=new ArrayList<>();for(var item:ac.path("criteria"))criteria.add(item.asText());
    if(criteria.isEmpty())throw new WorkflowStore.Blocked("MISSING_ACCEPTANCE","验收标准 criteria 必须包含明确的非空条目");
    t.title=requirements.path("title").asText(t.title);t.problem=requirements.path("goal").asText(t.problem);t.acceptanceCriteria=String.join("\n",criteria);
    // Every direct binding is visible with fixed version/digest, not forwarded via another node.
    boolean repair="code.repair@1".equals(node.definitionRef());String slot=repair?"fixer":"maker";
    var boundVersions=new TreeMap<String,ArtifactVersion>();for(var in:run.inputs())if(!in.versionId().equals("ABSENT"))boundVersions.put(in.versionId(),store.artifact(in.versionId()));
    String effectiveInstructions=node.roles().get(slot).instructions();
    if(node.config()!=null && node.config().get("_effectiveSkillLocks") instanceof Map<?,?> locks && locks.get(slot)!=null){
      var lock=Json.convert(locks.get(slot),com.x7x24.skills.SkillResolver.Lock.class);String scopeDigest=run.inputs().stream().filter(in->in.port().equals("scopeEvidence")).findFirst().map(ResolvedInput::contentDigest).orElse(null);
      var evaluations=new com.x7x24.skills.SkillResolver().evaluate(lock,new com.x7x24.skills.SkillResolver.Facts(plan.facts(),scopeDigest!=null,scopeDigest),Set.of("instruction-load"),releases.revoked());
      if(evaluations.stream().anyMatch(e->e.state().startsWith("BLOCKED")))throw new WorkflowStore.Blocked("SKILL_BLOCKED","必需技能的条件、能力或有效性未满足");
      var taskConfig=store.roles.pipes.snapshot(t.context.configSnapshot,t.productLine);String baseText=com.x7x24.pipeline.RolePlans.resolve(taskConfig,"DEV",slot,slot.equals("maker")?"dev.code":"dev.fix").instructions();
      var active=evaluations.stream().filter(e->e.state().equals("ACTIVE")).map(com.x7x24.skills.SkillResolver.Evaluation::skillRef).collect(java.util.stream.Collectors.toSet());
      boolean expanded=true;while(expanded){expanded=false;for(var skill:lock.skills())if(active.contains(skill.id()+"@"+skill.version()))expanded|=active.addAll(skill.dependencies());}
      effectiveInstructions=baseText+"\n"+lock.skills().stream().filter(skill->active.contains(skill.id()+"@"+skill.version())&&node.roles().get(slot).skills().stream().noneMatch(old->old.id().equals(skill.id()))).map(com.x7x24.skills.SkillResolver.Resolved::instructions).collect(java.util.stream.Collectors.joining("\n"));
      store.db.update("INSERT INTO skill_evaluation(id,workspace_id,task_id,node_run_id,set_digest,body,created_at) VALUES(?,?,?,?,?,?,?)",U.uuid(),plan.workspaceId(),plan.taskId(),run.id(),lock.digest(),Canonical.json(Map.of("evaluations",evaluations,"result","INSTRUCTIONS_LOADED_ONLY")),store.clk.iso());
    }
    var envelope=contexts.build(plan,node,run,boundVersions,effectiveInstructions,new ContextBuilder.Budget(16384,4096,1024));contexts.record(plan,run,envelope);
    t.context.artifacts.clear();t.context.artifacts.putAll(envelope.artifacts());t.context.extra.put("typedRoleInstructions",effectiveInstructions);
    var prd=new Models.Prd(1,t.title,t.problem,"",criteria,List.of(),0,List.of(),"other",t.riskLevel,null,List.of(),false);
    var implementation=new Models.Plan(List.of("DEV"),"other","typed-legacy-json",0,t.riskLevel,List.of(),List.of());
    DeliveryTools readOnly=(DeliveryTools)java.lang.reflect.Proxy.newProxyInstance(DeliveryTools.class.getClassLoader(),new Class<?>[]{DeliveryTools.class},(proxy,method,args)->{
      com.x7x24.execution.ExecutionGuard.check();
      if(!Set.of("live","readFile","listDir","sourceFiles").contains(method.getName()))throw new WorkflowStore.Blocked("EXECUTOR_CAPABILITY","当前 JSON 执行器只能读取仓库，不能直接调用 GitHub 写操作");
      try{return method.invoke(tools,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
    });
    AtomicInteger charge=new AtomicInteger();Models.Meter meter=(agent,response)->budget.charge(run.id()+":usage:"+charge.incrementAndGet(),t.id,t.productLine,agent,response.model(),response.tokens(),response.usd(),0);
    var address=new Models.ExecutionAddress(t.workspaceId,t.projectId,t.id,t.workflowId,plan.revisionId(),node.id(),slot,node.roles().get(slot).roleId(),run.id());
    String failureContext=repair?Canonical.json(run.inputs().stream().filter(in->in.port().equals("failureEvidence")).map(in->store.artifact(in.versionId())).toList()):null;
    var ctx=new Models.Ctx(t,line,t.context.issue,meter,readOnly,prd,implementation,failureContext,false,cfg.get().release,repair?Models.ExecutionMode.REPAIR:Models.ExecutionMode.IMPLEMENT,address);
    double maximumCall=ModelBudgetGuard.worstCallUsd(router.config(),node.roles().get(slot));
    var patch=com.x7x24.execution.ExecutionGuard.with(()->{scheduler.assertCurrent(plan.taskId(),run);kill.assertWritable(t.productLine,t.projectId,"write");
      double used=store.db.dbl("SELECT COALESCE(SUM(l.usd),0) FROM budget_ledger l JOIN node_run r ON l.ledger_key LIKE r.id || ':usage:%' WHERE r.plan_revision_id=? AND r.workspace_id=? AND l.task_id=?",plan.revisionId(),plan.workspaceId(),plan.taskId());
      if(used+maximumCall>plan.budgetUsd() || Set.of("hard","safety").contains(budget.taskStatus(tasks.get(plan.taskId()))))throw new WorkflowStore.Blocked("BUDGET","剩余预算不足以覆盖本次模型调用和受控重试");
    },()->router.withExpectedConfig(node.roles().get(slot).modelRouteDigest(),()->agents.code(ctx)));
    boolean ui=patch.files().stream().anyMatch(f->f.path().matches("(?i).*(\\.(tsx|jsx|vue|css|scss)|/components/.*|/pages/.*)"));
    boolean api=patch.files().stream().anyMatch(f->f.path().matches("(?i).*(/api/.*|/controllers?/.*|controller\\.java|openapi.*|swagger.*)"));
    if(ui && !Boolean.TRUE.equals(plan.facts().get("requiresUiChange")) || api && !Boolean.TRUE.equals(plan.facts().get("requiresApiChange")))throw new WorkflowStore.Blocked("PLAN_SCOPE_CHANGED","实际改动扩大了 UI 或 API 范围，需要新计划修订及批准");
    var errors=PathPolicy.check(line,patch.files().stream().map(DeliveryTools.FileChange::path).toList(),patch.files().stream().filter(f->f.content()!=null).collect(java.util.stream.Collectors.toMap(DeliveryTools.FileChange::path,DeliveryTools.FileChange::content,(a,b)->a)));
    if(!errors.isEmpty())throw new WorkflowStore.Blocked("PATH_POLICY",errors.toString());
    return Map.of("patch",Canonical.json(Map.of("baseSha",plan.baseSha(),"change",patch,"executor","LegacyJsonCodingExecutor@1","testEvidence","UNKNOWN")));
  }
}
