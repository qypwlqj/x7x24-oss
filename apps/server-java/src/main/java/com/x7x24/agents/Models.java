package com.x7x24.agents;

import com.x7x24.adapters.DeliveryTools;
import com.x7x24.engine.*;
import com.x7x24.llm.LlmProvider;

import java.util.*;

public final class Models {
  private Models() {}
  /** 一个可执行示例：函数名 + 参数（JSON 字面量）+ 期望值（JSON 字面量） */
  public record Example(String fn, List<String> argsJs, String expectedJs, String text) {}
  public record Prd(int version, String title, String problem, String userStory, List<String> acceptanceCriteria, List<String> metrics, double costEstimateUsd,
                    List<String> reviewNotesApplied, String template, String risk, String functionName, List<Example> examples, boolean naiveFirst, List<String> targetPaths) {
    public Prd(int version, String title, String problem, String userStory, List<String> acceptanceCriteria, List<String> metrics, double costEstimateUsd, List<String> reviewNotesApplied, String template, String risk, String functionName, List<Example> examples, boolean naiveFirst) { this(version, title, problem, userStory, acceptanceCriteria, metrics, costEstimateUsd, reviewNotesApplied, template, risk, functionName, examples, naiveFirst, List.of()); }
  }
  /** estimate：规划时的加权规模预估（ChangeSize.toMap），可为 null */
  public record Plan(List<String> path, String template, String strategy, int scopeLevel, String risk, List<String> files, List<Map<String, Object>> subtasks, Map<String, Object> estimate) {
    public Plan(List<String> path, String template, String strategy, int scopeLevel, String risk, List<String> files, List<Map<String, Object>> subtasks) { this(path, template, strategy, scopeLevel, risk, files, subtasks, null); }
  }
  public record Case(String id, String gherkin) {}
  public record CodeChange(List<DeliveryTools.FileChange> files, String message, String summary, String variant, String baseSha) {
    public CodeChange(List<DeliveryTools.FileChange> files, String message, String summary, String variant) { this(files, message, summary, variant, null); }
  }

  public interface Meter { void accept(String agent, LlmProvider.Response r); }
  /** 单次 agent 调用的上下文 */
  /** rel：本任务生效的变更预算（治理配置 + 模板 / 任务计划的 DEV.maxChangeLines 收紧）；null = 用全局治理配置 */
  public enum ExecutionMode { IMPLEMENT, REPAIR }
  public record ExecutionAddress(String workspaceId,String projectId,String taskId,String workflowRunId,String planRevisionId,String nodeInstanceId,String roleSlot,String roleId,String attemptId) {}
  public record Ctx(Task task, ProjectCfg line, TaskCtx.Issue issue, Meter meter, DeliveryTools tools, Prd prd, Plan plan, String ciLog, boolean cheap, com.x7x24.gov.Governance.Release rel, ExecutionMode executionMode, ExecutionAddress address) {
    public Ctx(Task task, ProjectCfg line, TaskCtx.Issue issue, Meter meter, DeliveryTools tools, Prd prd, Plan plan, String ciLog, boolean cheap, com.x7x24.gov.Governance.Release rel) {
      this(task,line,issue,meter,tools,prd,plan,ciLog,cheap,rel,ciLog!=null&&!ciLog.isBlank()&&task.context.pr!=null?ExecutionMode.REPAIR:ExecutionMode.IMPLEMENT,null);
    }
    public Ctx(Task task, ProjectCfg line, TaskCtx.Issue issue, Meter meter, DeliveryTools tools, Prd prd, Plan plan, String ciLog, boolean cheap) { this(task, line, issue, meter, tools, prd, plan, ciLog, cheap, null); }
    public Ctx with(Prd p, Plan pl) { return new Ctx(task, line, issue, meter, tools, p, pl, ciLog, cheap, rel, executionMode, address); }
  }

  /** 执行组 agent（Drafter / Planner / Spec / Design / Dev）：规则型本地 agent 或 LLM 驱动，两者都必须真实产出可审查的 diff。 */
  public interface ExecAgents {
    String mode();
    String model();
    Prd draftPrd(Ctx c);
    Plan plan(Ctx c);
    List<Case> specCases(Ctx c);
    Map<String, Object> design(Ctx c);
    CodeChange code(Ctx c);
    /** 本轮开发方式（sandbox / legacy）；规则 agent 等不区分方式的实现返回 null。 */
    default String devMode(Ctx c) { return null; }
  }
}
