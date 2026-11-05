package com.x7x24.engine;

import com.x7x24.core.Status;

/**
 * 编排层抽象。当前实现：{@link Engine}（数据库事件溯源 + 定时 tick 调度，零外部依赖）。
 * 生产可替换为 Temporal：实现同一接口即可，状态机 / agent / 治理代码无需改动。
 *
 * <pre>
 * startTask -> client.newWorkflowStub(TaskLifecycleWorkflow).start(taskId)      workflowId = task-{id}
 * signal    -> workflow.signal("ANOMALY" | "KILL_SWITCH")                        Patrol / kill switch 只发 Signal
 * describe  -> WorkflowStub.describe() + 事件历史重放
 * tick      -> 不需要：Temporal Timer / Schedule 自带（CI 轮询 = Activity 重试 + Timer；Patrol = 每 5 分钟 Schedule）
 * Engine.handle(task) 的每个状态分支 -> 一个 Activity（超时 / 重试策略对应 maxStepAttempts）
 * 幂等键 {task}:it{n}:{stage}:... -> Activity 内部副作用幂等键（原样复用）
 * </pre>
 */
public interface WorkflowOrchestrator {
  record Described(Status status, int version, String nextRunAt, Status replayStatus) {}
  record IngestResult(boolean duplicate, String signalId, String taskId) {}
  IngestResult startTask(Engine.Ingest in);
  boolean signal(String taskId, String anomalyType, String detail);
  Described describe(String taskId);
  /** 仅本地调度器需要；Temporal 适配层可实现为空操作 */
  int tick();
}
