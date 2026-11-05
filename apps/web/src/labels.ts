import type { Tone } from './ui';

export const EVT: Record<string, string> = {
  REPOSITORY_INITIALIZED: '仓库基线已就绪',
  BUDGET_UPDATED: '预算调整', INTEGRATION_CONNECTED: 'GitHub 已连接', INTEGRATION_DISCONNECTED: 'GitHub 已断开', INTEGRATION_MODE_CHANGED: '写入模式切换', INTEGRATION_DEVICE_STARTED: '发起授权登录', INTEGRATION_CI_BOOTSTRAP: '补充 CI 模板 PR', INTEGRATION_DEVICE_DENIED: '授权被拒绝',
  TASK_BLOCKED: '当前步骤受阻，进度已保存', STATUS_CHANGED: '状态变化', TASK_ACTION_EXECUTED: '执行了流程操作', AGENT_TOOLS: 'AI 运行工具', AGENT_BROWSER_CHECK: 'AI 浏览器自测', AGENT_NOTE: 'AI 说明', AGENT_RESULT: 'AI 本轮结束', TASK_ACTION_FAILED: '流程操作没有执行', DIRECTION_CHANGED: '改方向', NODE_RETRY_REQUESTED: '重试节点', QUESTION_ANSWERED: '回答了问题', SIZE_LARGE: '改动较大', SIZE_ADVISORY: '超过建议大小', MERGE_APPROVAL_REQUESTED: '等你确认合并', MERGE_APPROVED: '已确认合并', MERGE_RETURNED: '退回修改', MERGE_APPROVAL_STALE: '有新提交，需重新确认', MERGE_APPROVAL_REMINDER: '确认合并提醒', TASK_EDITED: '编辑任务', TASK_RESTARTED: '手动重启', TASK_DELETED: '删除任务（归档）', TASK_RESUMED: '手动恢复', TASK_PAUSED: '暂停/恢复', VERDICT: '评审结果', EVIDENCE_ADDED: '过程记录', TRIAGE: '自动分类', AGENT_FAILED: '执行出错', ANOMALY_OPENED: '发现异常', ANOMALY_CLOSED: '异常已关闭', SIZE_SPLIT: '分段提交', SEGMENT_RESPLIT: '继续拆小', SPLIT_PART_COMMITTED: '分段已提交', SELF_HEAL: '自动修复',
  DEGRADED: '预算用完，改用省钱模式', OWNER_NEEDED: '需要你处理', TASK_BUDGET_SWITCH: '任务预算设置', DEV_MODE: '本轮开发方式', SANDBOX_RETRY: '沙盒自动重试', CODE_REVIEW_FAILED: '逐行审查没审完', CODE_REVIEW_SKIP_FAILED: '跳过没审完的组', TASK_DEV_MODE: '任务开发方式', ROLLBACK: '自动撤回上线', BUDGET_HIT: '预算用完', KILL_SWITCH: '紧急停止', SIGNAL_SENT: '巡查提醒', RELEASE_WAIT: '等待发布时间', ITERATE: '重新进入流程',
  STAGE_SKIPPED: '跳过步骤', STAGE_RESUMED: '接着返修', NODE_PASSED: '节点检查通过', NODE_REJECTED: '节点检查退回', CODE_REVIEW_PROGRESS: '逐行审查进度', CODE_REVIEW_DONE: '逐行审查完成', UI_ACCEPTANCE_DONE: '页面验收完成', PATH_FILTERED: '流程步骤调整', PATH_POLICY_BLOCK: '越界改动已拦截', PLAN_REVISED: '流程计划已修改', CONFIG_UPGRADED: '流程配置升级',
  ISSUE_SYNC_FAILED: 'Issue 同步失败', CHAT_CONFIRMED: '对话确认创建', CHAT_DELETED: '来源对话已删除', CHAT_CLARIFICATION_REQUESTED: '退回对话补充信息',
  ACTION_DENIED: '禁止区域已拦截', POLICY_VIOLATION: '超出权限已拦截', AUTONOMY_CHANGED: '自动化程度调整', CONFIG_UPDATED: '配置更新', LINE_UPDATED: '产品线更新', ADAPTER_CALL: 'GitHub 调用',
};
export const EDGE: Record<string, string> = {
  submit: '提交评审', review_pin: '评审置顶', discard: '废弃', pass: '评审通过', dispatch: '分派', advance: '推进', qa_loop: '测试未过，返修', reject: '验收未过，退回修改', accept: '验收通过', release: 'GitHub 仓库版本已创建',
  rollback: '自动撤回上线', merge_return: '负责人退回修改', anomaly: '发现异常，暂停推进', split: '改动太大，拆段重新规划', resume: '自动修复后继续', degrade: '预算用完，改用省钱模式', auto_discard: '自动废弃', requeue: '冷却重新入池', iterate: '重新进入流程', active_discard: '主动废弃', create: '创建',
};
export const STATUS: Record<string, string> = { BACKLOG: '需求池', PRD_REVIEW: 'PRD 评审', PLANNING: '任务规划', SPEC: '需求策划', DESIGN: '体验设计', DEV: '开发执行', QA: '质量测试', ACCEPTANCE: '业务验收', DELIVERED: '待发布', RELEASED: '版本已发布', ANOMALY: '异常巡查', DEGRADED: '省钱模式', DISCARDED: '已废弃' };
export const statusName = (s: string) => STATUS[s] ?? s;
export const statusTone = (s: string): Tone => ({ RELEASED: 'success', DELIVERED: 'info', DISCARDED: 'neutral', ANOMALY: 'warn', DEGRADED: 'warn' } as Record<string, Tone>)[s] ?? 'primary';
export const RISK_TONE: Record<string, Tone> = { low: 'success', mid: 'info', high: 'warn', forbidden: 'danger' };
export const RISK_LABEL: Record<string, string> = { low: '低风险', mid: '中风险', high: '高风险', forbidden: '禁区' };
export const DISCARD_LABEL: Record<string, string> = {};

/** 任务已结束（交付 / 上线 / 废弃）：不再显示「正在…」等运行提示 */
export const ENDED_STATUSES: ReadonlySet<string> = new Set(['DELIVERED', 'RELEASED', 'DISCARDED']);
/** 执行层面已停下（受限 / 失败 / 暂停 / 完成 / 废弃） */
export const STOPPED_EXECUTION: ReadonlySet<string> = new Set(['BLOCKED', 'WAITING_CONFIGURATION', 'FAILED', 'PAUSED', 'COMPLETED', 'DISCARDED']);
export const isTaskRunning = (t: { status?: string; executionState?: string; paused?: boolean }) =>
  !t.paused && !ENDED_STATUSES.has(t.status ?? '') && !STOPPED_EXECUTION.has(t.executionState ?? '');
