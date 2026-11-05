package com.x7x24.workflow;

import java.util.*;

/** Schema 3 wire contracts. Identifiers and versions are explicit; no floating latest references. */
public final class Workflow {
  private Workflow() {}
  public enum ArtifactType { RequirementSpec, AcceptanceCriteria, RepoSnapshot, RepositoryProfile, ImplementationPlan, WorkOrder, APIContract, DesignSpec, PatchSet, ChangeSet, TestReport, ReviewReport, GateDecision, DeploymentRecord }
  public enum Requirement { REQUIRED, CONDITIONAL, OPTIONAL }
  public enum ValidationResult { PASS, FAIL, UNKNOWN, SKIPPED, ERROR }
  public enum ExecutorKind { AGENT, SYSTEM, HUMAN }
  public record Port(String name, ArtifactType type, int schema, Requirement requirement, String condition, boolean approvalRequired, int maxItems) {}
  public record Definition(String ref, ExecutorKind executor, boolean available, List<Port> inputs, List<Port> outputs, Set<String> capabilities) {}
  public record Binding(String id, String port, int ordinal, String versionId, String producerNode, String outputPort) {}
  public record Edge(String from, String to) {}
  public record RoleSnapshot(String slot, String roleId, String modelProfile, List<SkillSnapshot> skills, String instructions, String digest, String modelRouteDigest) {}
  public record SkillSnapshot(String id, int version, String contentDigest, String instructions) {}
  public record Node(String id, String logicalKey, String definitionRef, Map<String,Object> config, List<Binding> bindings, Map<String,RoleSnapshot> roles) {}
  public record Plan(int schema, String revisionId, String workspaceId, String projectId, String taskId, int revisionNo,
                     String parentRevisionId, String baseSha, String policyDigest, double budgetUsd,
                     List<Node> nodes, List<Edge> controls, Map<String,Boolean> facts) {}
  public record ArtifactVersion(String id, String workspaceId, String projectId, String taskId, ArtifactType type, int schema,
                                String contentDigest, String content, String sourceKind, String producerNode, String producerRun,
                                String baseSha, List<String> inputVersionIds) {}
  public record Validation(String versionId, String validator, String profile, ValidationResult result, String checkedDigest, String evidenceRef, String checkedAt) {}
  public record Approval(String id, String subjectDigest, String approver, boolean approved, String expiresAt, String revokedAt) {}
  public record Diagnostic(String node, String port, String code, String message) {}
  public record Compiled(Plan plan, List<String> order, String hash, List<Diagnostic> diagnostics) {
    public boolean valid() { return diagnostics.isEmpty(); }
  }
  public record ResolvedInput(String port, int ordinal, String versionId, String contentDigest, String condition) {}
  public record Run(String id, String nodeId, int attempt, String state, String fingerprint, long epoch, String owner, List<ResolvedInput> inputs) {}
}
