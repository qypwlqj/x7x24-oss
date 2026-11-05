# 编排层：零依赖引擎与 Temporal 替换路径

方案（`dev-plan.md` §2.5）选择 Temporal。为了让仓库**零外部依赖即可运行、可测试**，本仓库实现了一个持久化工作流引擎 `apps/server-java/.../engine/Engine.java`，并在 `WorkflowOrchestrator.java` 里留出 `WorkflowOrchestrator` 接口作为 Temporal 适配层的替换点（**接口已留，Temporal 适配本身未实现**）。

## 引擎已具备的 Temporal 语义

| Temporal 能力 | 本引擎的实现 |
|---|---|
| 事件溯源 / 历史重放 | `task_event` 只追加（H2/PostgreSQL 触发器禁止 UPDATE/DELETE）+ 哈希链；`task` 表是投影；`replayStatus()` 可由事件重建状态，测试里与投影对账 |
| Timer / 定时调度 | `task.next_run_at` + `tick()`；`run(hours, stepMinutes)` 配合 `SimClock` 加速 |
| Activity 重试 + 退避 | 步骤异常 → `step_attempts++`，有限退避重试；超限后原节点 BLOCKED，保存恢复点并停止调度 |
| 幂等 | 事件/证据/账本/工具操作都带幂等键（`${task}:it${n}:${stage}:...`）；乐观锁 `task.version`，并发/重放时第二次 `apply` 放弃 |
| Signal | `sendSignal()`：Patrol 只发信号，状态迁移由工作流自己执行 |
| 暂停 / kill switch | 每步执行前检查（`KillSwitch.isBlocked`），按 全局 / 产品线 / agent 范围 |
| 崩溃恢复 | 重启后同一 DB 继续（`restart` 测试）；模拟时钟持久化 |

## 换成 Temporal 的映射

| 本仓库 | Temporal |
|---|---|
| `Engine.startTask` / `ingest` | `client.workflow.start(TaskLifecycleWorkflow, { workflowId: 'task-<id>' })` |
| `Engine.handle(task)` 的每个状态分支（`onPrdReview`、`onDev`…） | 一个 Activity（超时/重试策略取自 `config.loops`） |
| `Engine.apply(decision)` | Workflow 内的状态迁移（唯一允许改状态的地方） |
| `Engine.sendSignal` | `handle.signal(...)` |
| `Patrol.run()` | `PatrolWorkflow`（Schedule 每 5 分钟） |
| 原节点阻塞与恢复 | 持久化检查点 + Signal 明确恢复；错误不终止任务 |
| 评审委员会（`agents/Committee.java`） | 子工作流 `ReviewCommitteeWorkflow`，评审员并行 Activity |
| `onDelivered`（分档灰度） | 子工作流 `CanaryReleaseWorkflow` |
| `metrics.board()` | `MetricsRollupWorkflow` 写物化视图（Postgres）替代实时聚合 |

实现 `WorkflowOrchestrator` 的 Temporal 版本后，Spring 装配（`Wiring`） 只依赖该接口的 `startTask / signal / describe`，agent、治理、看板代码无需修改。

当前恢复与历史兼容的权威说明见 [可恢复交付流程](design/recoverable-workflow.md)。
