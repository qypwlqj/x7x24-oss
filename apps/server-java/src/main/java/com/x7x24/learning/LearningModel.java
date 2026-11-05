package com.x7x24.learning;

import com.x7x24.db.Db;
import com.x7x24.gov.*;
import com.x7x24.llm.*;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import com.x7x24.workflow.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/** Opt-in model boundary; all calls, including failed calls, are separately metered. */
@Component
public class LearningModel implements Learning.Model {
  private final LearningStore store; private final ModelRouter router; private final BudgetService budget;
  private final ConfigStore governance; private final KillSwitch kill; private final com.x7x24.pipeline.PipelineService pipes; private final com.x7x24.engine.LineRepo lines;
  @Value("${x7x24.learning.model-enabled:false}") boolean modelEnabled;
  @Value("${x7x24.learning.max-call-usd:0.25}") double maxCallUsd;
  @Value("${x7x24.learning.daily-usd:2}") double dailyUsd;
  public LearningModel(LearningStore store,ModelRouter router,BudgetService budget,ConfigStore governance,KillSwitch kill,com.x7x24.pipeline.PipelineService pipes,com.x7x24.engine.LineRepo lines) {
    this.store=store;this.router=router;this.budget=budget;this.governance=governance;this.kill=kill;this.pipes=pipes;this.lines=lines;
  }
  private com.x7x24.pipeline.RolePlans.EffectiveRolePlan role(String project) {return com.x7x24.pipeline.RolePlans.resolve(pipes.snapshot(null,store.projects.require(project).productLineId()),"DEV","fixer","dev.fix");}
  public String fingerprint(String project) {return Canonical.digest(List.of(router.config(),role(project)));}
  public void guard(String project) {
    store.requireEnabled();store.scope(project);var p=store.projects.runtime(project);
    if(p.paused()!=0)throw new IllegalArgumentException("项目已暂停，学习实验暂时不能执行");
    kill.assertWritable(p.productLineId(),project,"write");
    if("hard".equals(budget.globalStatus().get("level")) || !"ok".equals(budget.projectStatus(project)) || !"ok".equals(budget.lineStatus(p.productLineId())))
      throw new IllegalArgumentException("项目或上级预算不足，不能开始学习实验");
  }
  @Override public Learning.Completion call(String project,String system,String prompt) {
    guard(project);if(!modelEnabled)throw new IllegalArgumentException("学习模型调用尚未启用，请配置 LEARNING_MODEL_ENABLED");
    var config=router.config();var role=role(project);String configDigest=Canonical.digest(config),behaviorDigest=fingerprint(project);
    String fullSystem=system+"\n固定角色规范：\n"+role.instructions()+"\n"+com.x7x24.agents.Prompts.SAFETY;
    ContextBuilder.guardRequest(fullSystem,prompt,16384);
    double quote=ModelBudgetGuard.worstCallUsd(config,new Workflow.RoleSnapshot("fixer",role.roleId(),role.modelProfile(),List.of(),"","",configDigest));
    if(!Double.isFinite(maxCallUsd)||maxCallUsd<=0||quote>maxCallUsd)throw new IllegalArgumentException("本次模型调用的费用上限超过学习单次预算");
    String profile=role.modelProfile()==null?config.roles.getOrDefault(role.roleId(),"exec"):role.modelProfile();long tokenQuote=0;
    for(String ref:config.profiles.get(profile).chain) {
      var m=config.models.stream().filter(x->ref.equals(x.id)).findFirst().orElseThrow();
      var p=config.providers.stream().filter(x->x.id.equals(m.provider)).findFirst().orElseThrow();
      tokenQuote+=(16384L+(m.maxOutputTokens==null?8192:m.maxOutputTokens))*3*(1+(p.maxRetries==null?2:p.maxRetries));
    }
    String id=LearningStore.id(),ws=Tenant.id();long reserveTokens=tokenQuote;
    store.db.tx(()->{
      store.db.row("SELECT id FROM workspace WHERE id=? FOR UPDATE",ws);
      var p=store.projects.require(project);String line=p.productLineId();
      double outstanding=store.db.dbl("SELECT COALESCE(SUM(reserved_usd),0) FROM learning_call WHERE workspace_id=? AND state IN ('RUNNING','UNKNOWN')",ws);
      long pendingTokens=store.db.count("SELECT COALESCE(SUM(reserved_tokens),0) FROM learning_call WHERE workspace_id=? AND state IN ('RUNNING','UNKNOWN')",ws);
      double spent=store.db.dbl("SELECT COALESCE(SUM(actual_usd),0) FROM learning_call WHERE workspace_id=? AND project_id=? AND created_at>=?",ws,project,store.clk.dayKey());
      var pl=budget.limits(p.cfg().budget);var ll=budget.limits(lines.require(line).cfg().budget);var gl=governance.get().budgets.global;
      // Workspace-wide reservations are deliberately conservative for project/line admission.
      if(spent+outstanding+quote>dailyUsd || budget.projectDaily(project).usd()+outstanding+quote>pl.dailyUsd || budget.lineDaily(line).usd()+outstanding+quote>ll.dailyUsd || budget.globalDaily().usd()+outstanding+quote>gl.dailyUsd || budget.globalMonthly().usd()+outstanding+quote>gl.monthlyUsd
          ||budget.projectDaily(project).tokens()+pendingTokens+reserveTokens>pl.dailyTokens ||budget.lineDaily(line).tokens()+pendingTokens+reserveTokens>ll.dailyTokens ||budget.globalDaily().tokens()+pendingTokens+reserveTokens>gl.dailyTokens)
        throw new IllegalArgumentException("剩余学习预算不足以覆盖模型调用和重试");
      store.db.update("INSERT INTO learning_call(id,workspace_id,project_id,product_line,state,reserved_usd,reserved_tokens,created_at) VALUES(?,?,?,?,'RUNNING',?,?,?)",id,ws,project,line,quote,reserveTokens,store.clk.iso());
    });
    try {
      Map<String,String> overrides=role.modelProfile()==null?Map.of():Map.of(role.roleId(),role.modelProfile());
      var response=router.withExpectedConfig(configDigest,()->router.maker(role.roleId(),null,overrides,false).chat(new LlmProvider.Request("learning.repair",fullSystem,prompt,"learning-repair@1"),n->n.isObject()?null:"需要输出 JSON 对象"));
      settle(id,project,response.model(),response.tokens(),response.usd(),"SUCCEEDED");
      return new Learning.Completion(response.text(),response.model(),behaviorDigest,response.tokens(),response.usd());
    } catch(LlmProvider.OutputException e) {settle(id,project,e.model,e.tokens,e.usd,"FAILED");throw e;}
      catch(LlmProvider.Cancelled e) {settle(id,project,e.model,e.tokens,e.usd,"CANCELLED");throw e;}
      catch(RuntimeException e) {store.db.update("UPDATE learning_call SET state='UNKNOWN' WHERE id=? AND state='RUNNING'",id);throw e;}
  }
  public void evaluationUsage(String project,String trial,long millis) {
    var p=store.projects.require(project);budget.chargeProject("learning-evaluation:"+trial,null,p.productLineId(),project,"learning_evaluation","isolated-evaluator",0,0,Math.max(0,millis)/60000d);
  }
  public void beforeEvaluation(String project,double minutes) {
    guard(project);var p=store.projects.require(project);var pl=budget.limits(p.cfg().budget);var ll=budget.limits(lines.require(p.productLineId()).cfg().budget);
    if(budget.projectDaily(project).ci()+minutes>pl.dailyCiMinutes || budget.lineDaily(p.productLineId()).ci()+minutes>ll.dailyCiMinutes || budget.globalDaily().ci()+minutes>governance.get().budgets.global.dailyCiMinutes)
      throw new IllegalArgumentException("剩余测试分钟不足以覆盖本次隔离评估");
  }
  private void settle(String id,String project,String model,long tokens,double usd,String state) {
    if(!Double.isFinite(usd)||usd<0||tokens<0)throw new IllegalArgumentException("模型用量无效，费用预留继续保留");
    store.db.tx(()->{
      var p=store.projects.require(project);
      budget.chargeProject("learning:"+id,null,p.productLineId(),project,"learning",model,tokens,usd,0);
      store.db.update("UPDATE learning_call SET state=?,actual_usd=? WHERE id=? AND workspace_id=?",state,usd,id,Tenant.id());
    });
  }
}
