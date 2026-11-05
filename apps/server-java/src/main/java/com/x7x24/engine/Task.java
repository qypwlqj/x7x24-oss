package com.x7x24.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** task 表的可变投影（事件溯源的当前状态）。 */
public class Task {
  /** 所属工作区（库列 workspace_id） */
  public String workspaceId;
  /** 可组合流程：任务当前所在的流程节点 id（看板按节点聚合用；v1 任务也会写） */
  public String currentNode;
  public String projectId, currentStage = "BACKLOG", executionState = "WAITING", acceptanceCriteria = "", pipelineVersion = "v1";
  public String id, productLine, title, problem, status, riskLevel = "low", cooldownUntil, parentTaskId, discardReason, anomalyFrom, observeUntil, workflowId, nextRunAt, createdAt, statusChangedAt, releasedAt, deletedAt;
  public double priorityScore, budgetUsd, spentUsd, budgetCi, spentCi;
  public long budgetTokens, spentTokens;
  public int scopeLevel, strategyVariant, currentIteration = 1, loopCount, rejectCount, reviewRetry, rollbackCount, requeueCount, degradeCount, healCount, stepAttempts, budgetIterations = 50, spentIterations;
  public int paused, feedbackDone, acceptedOnce, version;
  public List<String> path = new ArrayList<>();
  public JsonNode discardDetail, plan;
  public TaskCtx context = new TaskCtx();
  public Map<String, Object> release = new LinkedHashMap<>();

  public com.x7x24.core.Status st() { return com.x7x24.core.Status.valueOf(status); }
  public Task copy() { return TaskRepo.fromJson(TaskRepo.toJson(this)); }
}
