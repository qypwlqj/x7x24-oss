/** 指挥台 M3：会话里的流程操作（提议 → 确认）。服务端是权限和乐观锁的唯一依据，这里只做展示 */
import type { RecoveryInfo } from '@x7x24/shared';
import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '../api';
import { statusName } from '../labels';

export type ActionKind = 'task_resume' | 'task_pause' | 'node_retry' | 'task_redirect' | 'node_skip' | 'answer_question' | 'approval_decide' | 'task_abandon' | 'task_restore' | 'repository_initialize_and_resume' | 'dev_mode_retry' | 'code_review_skip_failed';
export interface TaskAction { id: string; taskId: string; kind: ActionKind; label: string; params: Record<string, any>; danger: 'low' | 'mid' | 'high'; minRole: 'member' | 'admin'; confirmWord?: string | null; status: string; proposedBy: string; blockedRole?: string | null; source: 'button' | 'text'; error?: string | null; createdAt: string; decidedBy?: string | null; taskTitle?: string }
export interface Suggestion { kind: ActionKind; title: string; why: string; params: Record<string, any>; recommended: boolean; danger: string; minRole: string }
export interface ResumeBlocker { code: string; detail: string }
export interface FailureRecord { code: string | null; stage: string | null; detail: string; at: string | null }
export interface ActionState { executionState?: string; recovery?: RecoveryInfo; blocked: boolean; status: string; node: string; paused: boolean; canRestore?: boolean; cause?: string; detail?: string | null; resumeBlocker?: ResumeBlocker | null; failureHistory?: FailureRecord[]; evidence?: string[]; suggestions: Suggestion[]; guide?: { label: string; href: string; recommended?: boolean }; pending: TaskAction[]; history: TaskAction[]; can: { act: boolean; admin: boolean }; sessionId: string | null; lockKey: string }
export interface Said { reply: string; action?: TaskAction; via: string }

export const DANGER_ZH = { low: '低风险', mid: '中风险', high: '高风险' } as const;
export function resumeDescription(recovery?: Pick<RecoveryInfo, 'rework'>): string {
  const rework = recovery?.rework;
  if (!rework) return '从保存的位置继续当前步骤，已有计划、分段、提交和历史记录会保留。';
  return `恢复后将重新获得最多 ${rework.limit} 次返修机会，基于已有代码继续修复，再运行检查。${rework.node !== 'QA' ? `测试返修额度同时恢复为 ${rework.qaLimit} 次。` : ''}历史记录和花费保留。`;
}
export const CAUSE_ZH: Record<string, string> = { sandbox_failed: '沙盒开发没成功', code_review_failed: '逐行审查没审完', release_configuration_required: '待配置发布', path_policy: '当前补丁未通过内容或路径检查', forbidden_scope: '当前改动受策略限制', same_failure: '相同错误连续出现', review_same_failure: '评审反复发现相同问题', review_plan_adjustment: '待确认方案调整', rework_exhausted: '本轮自动返修次数已用完', legacy_failure: '原执行已停止', low_value: '需求等待调整', infeasible: '方案等待调整', exceeds_budget: '当前规模超过限制', paused: '任务已暂停', needs_clarification: 'AI 有问题需要你回答', budget_paused: '预算用完了', safety_cap: '达到安全上限', ci_flaky: 'CI 不稳定或超时', evidence_missing: 'CI 没有给出可核验的结果', agent_error: 'agent 连续出错', model_config_error: '模型配置有误', repository_not_initialized: '仓库尚未初始化', timeout: '长时间没有进展', ci_failed: '测试没通过', qa_failed: '测试没通过', merge_conflict: '和主干有冲突' };
export const causeText = (c?: string) => (c && CAUSE_ZH[c]) || (c ? `流程卡住（${c}）` : '流程卡住');
export const failureCauseText = (c: string | null) => c === 'budget' ? '预算用完了' : c === 'manual_abandon' ? '任务曾被放弃' : c && CAUSE_ZH[c] ? CAUSE_ZH[c] : '执行未完成';
/** 确认卡上的参数用人话列出来 */
export function paramLines(a: Pick<TaskAction, 'kind' | 'params'>): string[] {
  const p = a.params ?? {};
  switch (a.kind) {
    case 'repository_initialize_and_resume': return [`仓库：${p.repo}`, `目标分支：${p.branch}`, `初始化文件：${p.path}`, `提交说明：${p.message}`, '完成后从当前节点继续，保留已有规划'];
    case 'code_review_skip_failed': return ['没审完的文件按「没审」记下，审查结论标成「部分审查」', '接着往下走（安全审计、验收照常）', '不占返修次数；这一步会记进审计'];
    case 'dev_mode_retry': return [`本任务改用：${p.devMode === 'sandbox' ? '沙盒' : '快速方式'}`, '快速方式最多参考 10 个文件，不在本地跑构建和测试，只靠 CI 检查', '改完后从开发这一步重新开始，之前的半成品不保留'];
    case 'node_retry': return [p.rework ? resumeDescription({ rework: p.rework }) : `重跑节点：${p.nodeId ?? '当前节点'}`, p.hint ? `提示：${p.hint}` : ''].filter(Boolean);
    case 'task_redirect': return [`补充要求：${p.instruction}`, `退回到：${p.backTo === 'DEV' ? '开发' : statusName(p.backTo)}`];
    case 'node_skip': return [`跳过：${p.label ?? p.nodeId}`, `原因：${p.reason}`];
    case 'answer_question': return [`回答：${p.answer}`];
    case 'approval_decide': return [p.decision === 'approve' ? '批准合并' : '退回修改', p.comment ? `备注：${p.comment}` : ''].filter(Boolean);
    case 'task_restore': case 'task_resume': return [p.rework ? resumeDescription({ rework: p.rework }) : '从保存的节点和步骤继续，保留已有计划、提交和分段进度'];
    case 'task_abandon': return [`原因：${p.reason}`, '放弃后任务保留在「已放弃」，可以恢复'];
    default: return [];
  }
}
export const errText = (e: unknown) => e instanceof ApiError ? (e.status === 409 ? `操作冲突：${e.message}` : e.status === 403 ? `没有权限：${e.message}` : e.message) : String((e as Error)?.message ?? e);

export function useTaskActions(taskId: string, seq: number) {
  const [st, setSt] = useState<ActionState | null>(null);
  const load = useCallback(() => api<ActionState>(`/api/tasks/${encodeURIComponent(taskId)}/actions`).then(setSt).catch(() => {}), [taskId]);
  useEffect(() => { load(); }, [load, seq]);
  const base = `/api/tasks/${encodeURIComponent(taskId)}`;
  return {
    state: st, reload: load,
    propose: async (kind: ActionKind, params: Record<string, any> = {}) => { const a = await api<TaskAction>(`${base}/actions`, { method: 'POST', body: { kind, params, source: 'button', sessionId: st?.sessionId } }); await load(); return a; },
    confirm: async (id: string, confirmText?: string) => { try { return await api<TaskAction>(`${base}/actions/${id}/confirm`, { method: 'POST', body: { confirmText } }); } finally { await load(); } },
    cancel: async (id: string) => { await api(`${base}/actions/${id}/cancel`, { method: 'POST' }); await load(); },
    say: async (text: string) => { const r = await api<Said>(`${base}/say`, { method: 'POST', body: { text } }); await load(); return r; },
    startSession: async () => { const r = await api<{ sessionId: string }>(`${base}/session`, { method: 'POST' }); await load(); return r.sessionId; },
  };
}
