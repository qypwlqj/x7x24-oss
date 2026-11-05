package com.x7x24.execution.agent;

import com.x7x24.agents.Models;
import java.util.*;

/**
 * ExecAgents 装饰器：开发（code）按项目 / 任务选择的方式执行——沙盒或快速方式（原实现的 JSON 补丁），
 * 其余（PRD、规划、设计等）全部委托原实现。沙盒失败不会自动改用快速方式，也不会采用沙盒里的半成品；
 * 由 Engine 停在可恢复状态并写明原因。
 */
public class AgentRuntimeAgents implements Models.ExecAgents {
  private final Models.ExecAgents base; private final AgentJobService jobs;
  public AgentRuntimeAgents(Models.ExecAgents base, AgentJobService jobs) { this.base = base; this.jobs = jobs; }
  public static Models.ExecAgents wrap(Models.ExecAgents base, AgentRuntimeSettings s, java.util.function.Supplier<AgentJobService> jobs) {
    return new AgentRuntimeAgents(base, s.remote() ? jobs.get() : null);
  }
  @Override public String mode() { return base.mode(); }
  @Override public String model() { return base.model(); }
  @Override public Models.Prd draftPrd(Models.Ctx c) { return base.draftPrd(c); }
  @Override public Models.Plan plan(Models.Ctx c) { return base.plan(c); }
  @Override public List<Models.Case> specCases(Models.Ctx c) { return base.specCases(c); }
  @Override public Map<String, Object> design(Models.Ctx c) { return base.design(c); }
  @Override public String devMode(Models.Ctx c) { return DevMode.resolve(c.task(), c.line()).mode(); }
  @Override public Models.CodeChange code(Models.Ctx c) {
    if (DevMode.resolve(c.task(), c.line()).legacy()) return base.code(c);
    if (jobs == null) throw new AgentJobService.JobFailed("UNCONFIGURED", "沙盒未配置：AGENT_RUNTIME 需要设为 remote-docker");
    return jobs.run(c);
  }
}
