package com.x7x24.workflow;
import com.x7x24.pipeline.Pipeline.*;
import java.util.*;
/** Conservative admission estimate, not a promise of actual provider pricing. Missing prices block typed execution. */
public final class ModelBudgetGuard {
 private ModelBudgetGuard(){}
 public static double worstCallUsd(ModelConfig models,Workflow.RoleSnapshot role){
  String profile=role.modelProfile()==null?models.roles.getOrDefault(role.roleId(),"exec"):role.modelProfile();var route=models.profiles.get(profile);
  if(route==null || route.chain==null || route.chain.isEmpty())throw new WorkflowStore.Blocked("BUDGET_PRICE_UNKNOWN","模型路由或价格尚未核算，不能开始付费执行");
  double worst=0;
  for(String id:route.chain){var model=models.models.stream().filter(m->m.id.equals(id)).findFirst().orElseThrow(()->new WorkflowStore.Blocked("BUDGET_PRICE_UNKNOWN","模型资料缺失，不能核算本次调用"));
   if(model.priceInPer1m==null || model.priceOutPer1m==null || !Double.isFinite(model.priceInPer1m)||!Double.isFinite(model.priceOutPer1m)||model.priceInPer1m<0||model.priceOutPer1m<0)throw new WorkflowStore.Blocked("BUDGET_PRICE_UNKNOWN","模型价格未记录，不能把未知费用当作零");
   var provider=models.providers.stream().filter(p->p.id.equals(model.provider)).findFirst().orElseThrow(()->new WorkflowStore.Blocked("BUDGET_PRICE_UNKNOWN","供应商重试范围未记录，无法核算调用上限"));
   int retries=provider.maxRetries==null?2:provider.maxRetries;if(retries<0||retries>10)throw new WorkflowStore.Blocked("BUDGET_ESTIMATE","供应商重试次数超出支持的费用核算范围");
   int output=model.maxOutputTokens==null?8192:model.maxOutputTokens;if(output<1||output>128000)throw new WorkflowStore.Blocked("BUDGET_ESTIMATE","模型输出上限超出支持的费用核算范围");
   worst+=(16384*model.priceInPer1m+output*model.priceOutPer1m)/1_000_000*3*(retries+1);
  }return worst;
 }
}
