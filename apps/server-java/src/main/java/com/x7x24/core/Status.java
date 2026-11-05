package com.x7x24.core;

import java.util.*;

/** 状态机（与 packages/shared/src/states.ts 保持一致，前端共用同一份转移表语义）。 */
public enum Status {
  BACKLOG, PRD_REVIEW, PLANNING, SPEC, DESIGN, DEV, QA, ACCEPTANCE, DELIVERED, RELEASED, ANOMALY, DEGRADED, DISCARDED;

  public record Rule(Status to, Set<String> edges) {}
  private static Rule r(Status to, String... edges) { return new Rule(to, Set.of(edges)); }

  public static final Map<Status, List<Rule>> TRANSITIONS = Map.ofEntries(
      Map.entry(BACKLOG, List.of(r(DISCARDED, "abandon"), r(PRD_REVIEW, "submit"), r(PLANNING, "submit"), r(DEV, "submit"))),
      Map.entry(PRD_REVIEW, List.of(r(DISCARDED, "abandon"), r(BACKLOG, "review_pin"), r(PLANNING, "pass"), r(ANOMALY, "anomaly"))),
      Map.entry(PLANNING, List.of(r(DISCARDED, "abandon"), r(SPEC, "dispatch"), r(DESIGN, "dispatch"), r(DEV, "dispatch"), r(DEGRADED, "degrade"), r(ANOMALY, "anomaly"))),
      Map.entry(SPEC, List.of(r(DISCARDED, "abandon"), r(DESIGN, "advance"), r(DEV, "advance"), r(ANOMALY, "anomaly"))),
      Map.entry(DESIGN, List.of(r(DISCARDED, "abandon"), r(DESIGN, "advance"), r(DEV, "advance"), r(ANOMALY, "anomaly"))),
      Map.entry(DEV, List.of(r(DISCARDED, "abandon"), r(QA, "advance"), r(PLANNING, "split"), r(DEGRADED, "degrade"), r(ANOMALY, "anomaly"))),
      Map.entry(QA, List.of(r(DISCARDED, "abandon"), r(DEV, "qa_loop"), r(PLANNING, "qa_loop"), r(ACCEPTANCE, "advance"), r(DEGRADED, "qa_loop", "degrade"), r(ANOMALY, "anomaly"))),
      Map.entry(ACCEPTANCE, List.of(r(DISCARDED, "abandon"), r(PLANNING, "reject"), r(DEV, "node_fix"), r(DELIVERED, "accept"), r(DEGRADED, "reject", "degrade"), r(ANOMALY, "anomaly"))),
      Map.entry(DELIVERED, List.of(r(DISCARDED, "abandon"), r(RELEASED, "release"), r(DEV, "merge_return"), r(PLANNING, "rollback"), r(DEGRADED, "rollback"), r(ANOMALY, "anomaly"))),
      Map.entry(RELEASED, List.of(r(PLANNING, "rollback"), r(DEGRADED, "rollback"), r(ANOMALY, "anomaly"))),
      Map.entry(ANOMALY, List.of(r(DISCARDED, "abandon"), r(PRD_REVIEW, "resume"), r(PLANNING, "resume"), r(SPEC, "resume"), r(DESIGN, "resume"), r(DEV, "resume"), r(QA, "resume"), r(ACCEPTANCE, "resume"))),
      Map.entry(DEGRADED, List.of(r(DISCARDED, "abandon"), r(DEV, "degrade"), r(PLANNING, "degrade"))),
      Map.entry(DISCARDED, List.of(r(BACKLOG,"requeue","restore_progress"),r(PRD_REVIEW,"restore_progress"),r(PLANNING,"restore_progress"),r(SPEC,"restore_progress"),r(DESIGN,"restore_progress"),r(DEV,"restore_progress"),r(QA,"restore_progress"),r(ACCEPTANCE,"restore_progress"),r(DELIVERED,"restore_progress"),r(RELEASED,"restore_progress"))));

  public static boolean can(Status from, Status to, String edge) {
    return TRANSITIONS.getOrDefault(from, List.of()).stream().anyMatch(x -> x.to == to && (edge == null || x.edges.contains(edge)));
  }

  public static final List<Status> ANOMALY_FROM = List.of(PRD_REVIEW, PLANNING, SPEC, DESIGN, DEV, QA, ACCEPTANCE);
  public static final List<Status> WATCH = List.of(BACKLOG, PRD_REVIEW);
  public static final List<Status> PRISM = List.of(PLANNING, SPEC, DESIGN, DEV, QA, ACCEPTANCE, ANOMALY, DEGRADED);
  public static final List<Status> IN_FLIGHT = List.of(PRD_REVIEW, PLANNING, SPEC, DESIGN, DEV, QA, ACCEPTANCE, DELIVERED, ANOMALY, DEGRADED);

  public record Node(String id, String label, List<Status> statuses, String lane) {}
  public static final List<Node> BOARD_NODES = List.of(
      new Node("signal", "抓取信号", List.of(), "watch"), new Node("pool", "需求池", List.of(BACKLOG), "watch"),
      new Node("prd", "PRD 评审", List.of(PRD_REVIEW), "gate"), new Node("planning", "任务规划", List.of(PLANNING), "prism"),
      new Node("spec", "需求策划", List.of(SPEC), "prism"), new Node("design", "体验设计", List.of(DESIGN), "prism"),
      new Node("dev", "开发执行", List.of(DEV), "prism"), new Node("qa", "质量测试", List.of(QA), "prism"),
      new Node("acceptance", "业务验收", List.of(ACCEPTANCE), "prism"), new Node("anomaly", "异常巡查", List.of(ANOMALY, DEGRADED), "prism"),
      new Node("delivered", "待发布", List.of(DELIVERED), "outer"), new Node("released", "版本已发布", List.of(RELEASED), "outer"),
      new Node("discarded", "已废弃", List.of(DISCARDED), "watch"));

  public static final Map<String, Boolean> DISCARD_REASONS = new LinkedHashMap<>() {{
    put("duplicate", false); put("low_value", false); put("needs_clarification", false); put("exceeds_budget", false); put("forbidden_scope", false); put("infeasible", false); put("loop_exhausted", false);
    put("canary_regression", false); put("budget_exhausted", false); put("superseded", false); put("policy_withdrawn", false); put("manual_abandon", false);
  }};
}
