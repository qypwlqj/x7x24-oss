package com.x7x24.web;

import java.util.*;

/** 角色注册表：执行组 11 + 裁决组 4（见 docs/dev-plan.md §2.2） */
public final class Agents {
  public record Meta(String id, String name, String group, String permissions, String inputs, String outputs) {}
  public static final List<Meta> ALL = List.of(
      new Meta("signal", "信号 Signal", "exec", "GitHub Issues 只读", "目标仓库 Issues（label 7x24:signal / 全部新 open issue）", "Signal（按 Issue 号 / 指纹去重）"),
      new Meta("triage", "分诊 Triage", "exec", "只读", "Signal", "Task(BACKLOG)、优先级分、风险初判"),
      new Meta("drafter", "PRD 起草 Drafter", "exec", "写文档区", "需求池任务、历史评审意见", "PRD 一页纸"),
      new Meta("planner", "规划 Planner", "exec", "写 Task 计划", "PRD、失败上下文（含 CI 日志）", "路径、策略、风险等级"),
      new Meta("spec", "策划 Spec", "exec", "写文档区", "计划 + PRD", "验收用例（Gherkin）、护栏指标"),
      new Meta("design", "设计 Design", "exec", "设计规范只读", "PRD", "设计说明"),
      new Meta("dev", "开发 Dev", "exec", "只能推 agent/* 分支；绝不直推受保护分支", "PRD + 计划 + 仓库 + CI 日志", "分支、多文件提交、PR"),
      new Meta("qa", "测试 QA", "exec", "触发 / 读取 CI", "PR head SHA", "Actions run、jobs、日志摘要、CI 分钟"),
      new Meta("release", "发布 Release", "exec", "合并 PR / 创建 tag + Release（经治理闸门）", "已验收的 PR", "合并提交、Release、上线校验"),
      new Meta("observe", "观测 Observe", "exec", "只读", "主干 CI / workflow 健康度", "金丝雀判定输入、上线后校验"),
      new Meta("patrol", "巡查 Patrol", "exec", "只发信号，不改状态", "全部任务", "超时 / 异常信号、自动 kill switch、自治等级调整"),
      new Meta("prd_committee", "PRD 评审委员会", "judge", "只读", "PRD（剥离自评）", "多模型打分、分歧仲裁"),
      new Meta("design_review", "设计评审", "judge", "只读", "设计说明", "设计评分"),
      new Meta("acceptance_committee", "验收委员会", "judge", "只读", "验收用例结果 + PR + CI 报告", "通过 / 驳回"),
      new Meta("canary_judge", "金丝雀裁决", "judge", "规则型（Argo 语义）", "指标样本", "pass / fail / inconclusive"));
  private Agents() {}
}
