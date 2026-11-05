import { modelActivity, type ModelCall, type TaskEnvelope } from './timeline';

export type ReviewState = 'QUEUED' | 'REVIEWING' | 'VALIDATING' | 'VERIFYING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'UNCONFIRMED';
export interface ReviewGroup {
  fileGroupId: string; index: number; total: number; concurrency: number; slot: number; files: string[];
  state: ReviewState; startedAt: number; updatedAt: number; reason?: string; calls: ModelCall[];
  /** 当前这次模型调用：第几次 / 最多几次、模型、上次失败类型、换模型前的模型、整组截止时间（毫秒） */
  attempt?: number; maxAttempts?: number; model?: string | null; errorKind?: ErrorKind | null; switchedFrom?: string | null; deadlineAt?: number;
}
export type ErrorKind = 'timeout' | 'connect' | 'rate_limit' | 'server';
export const errorKindName = (k?: string | null) => k === 'timeout' ? '接口超时' : k === 'connect' ? '连不上接口' : k === 'rate_limit' ? '接口限流' : k === 'server' ? '接口出错' : '调用失败';
/** 进度上的一句话：「接口超时，正在第 2/2 次重试（kimi）」「已换 deepseek-chat」；没有重试或换模型时为 null */
export function retryText(g: Pick<ReviewGroup, 'attempt' | 'maxAttempts' | 'model' | 'errorKind' | 'switchedFrom' | 'state'>): string | null {
  if (reviewEnded(g.state as ReviewState)) return null;
  const parts: string[] = [];
  if (g.switchedFrom && g.model) parts.push(`${g.switchedFrom} 没成功，已换 ${g.model}`);
  if ((g.attempt ?? 0) > 1) parts.push(`${errorKindName(g.errorKind)}，正在第 ${g.attempt}/${g.maxAttempts ?? g.attempt} 次重试${g.model && !g.switchedFrom ? `（${g.model}）` : ''}`);
  return parts.length ? parts.join(' · ') : null;
}
/** engine 为 ocr 时由 open-code-review 自己分组，后端只报整体状态（state / reason / resumed），groups 为空 */
export interface ReviewActivity { runId: string; total: number; concurrency: number; groups: ReviewGroup[]; finished: boolean; engine?: 'ocr'; state?: string; reason?: string; resumed?: boolean; calls?: ModelCall[] }
export const reviewStateName = (state: ReviewState) => ({ QUEUED: '等待开始', REVIEWING: '正在初审', VALIDATING: '校验意见', VERIFYING: '正在复核', COMPLETED: '已完成', FAILED: '失败', SKIPPED: '已跳过', UNCONFIRMED: '状态待确认' }[state]);
export const reviewActive = (state: ReviewState) => ['REVIEWING', 'VALIDATING', 'VERIFYING'].includes(state);
export const reviewEnded = (state: ReviewState) => ['COMPLETED', 'FAILED', 'SKIPPED'].includes(state);
const resets = new Set(['STATUS_CHANGED', 'TASK_RESTARTED', 'TASK_RESUMED', 'NODE_RETRY_REQUESTED', 'DIRECTION_CHANGED', 'TASK_PAUSED', 'AGENT_FAILED']);
const states = new Set(['QUEUED', 'REVIEWING', 'VALIDATING', 'VERIFYING', 'COMPLETED', 'FAILED', 'SKIPPED']);

/** 从可重放的任务事件恢复这一轮分组，绝不按调用条数推算组数或并发数。 */
export function reviewActivity(events: TaskEnvelope[], scope: { taskId: string; nodeId?: string | null; iteration?: number | null }, calls = modelActivity(events, scope)): ReviewActivity | null {
  let runId: string | undefined, total = 0, concurrency = 1, finished = false;
  let ocr: { state: string; reason?: string; resumed?: boolean } | null = null;
  const groups = new Map<string, ReviewGroup>(), retired = new Set<string>();
  for (const e of [...events].sort((a, b) => a.seq - b.seq)) {
    if (e.taskId !== scope.taskId) continue;
    if (resets.has(e.type)) { if (runId) retired.add(runId); groups.clear(); runId = undefined; finished = false; ocr = null; }
    if (e.nodeId !== scope.nodeId || scope.iteration != null && e.iteration !== scope.iteration) continue;
    const d = e.data;
    if (e.type === 'CODE_REVIEW_FAILED' && (!runId || d?.runId === runId)) { runId ??= d?.runId ?? `legacy-failed-${e.seq}`; finished = true; continue; }
    if (e.type === 'CODE_REVIEW_DONE' && (!runId || d?.runId === runId)) { runId ??= d?.runId ?? `legacy-done-${e.seq}`; finished = true; continue; }
    if (e.type === 'CODE_REVIEW_PROGRESS' && d?.engine === 'ocr' && d.runId && !retired.has(d.runId)) {
      if (d.runId !== runId) { if (runId) retired.add(runId); runId = d.runId; groups.clear(); finished = false; }
      if (d.state === 'COMPLETED' || d.state === 'FAILED') finished = true;
      ocr = { state: d.state, reason: d.reason, resumed: d.resumed === true }; continue;
    }
    if (e.type !== 'CODE_REVIEW_PROGRESS' || !d?.runId || !d.fileGroupId || retired.has(d.runId) || !states.has(d.state)) continue;
    if (d.runId !== runId) {
      if (d.state !== 'QUEUED' && d.state !== 'SKIPPED') continue;
      if (runId) retired.add(runId);
      runId = d.runId; groups.clear(); finished = false;
    }
    if (finished) continue;
    const previous = groups.get(d.fileGroupId);
    if (previous && reviewEnded(previous.state)) continue;
    total = d.total; concurrency = d.concurrency;
    groups.set(d.fileGroupId, { ...d, state: d.stale && !reviewEnded(d.state) ? 'UNCONFIRMED' : d.state, calls: [] });
  }
  if (!runId) return null;
  if (ocr) return { runId, total: 0, concurrency: 1, finished, groups: [], engine: 'ocr', state: ocr.state, reason: ocr.reason, resumed: ocr.resumed, calls: calls.filter(c => c.runId === runId) };
  return { runId, total, concurrency, finished, groups: [...groups.values()].sort((a, b) => a.index - b.index).map(g => ({ ...g, calls: calls.filter(c => c.runId === runId && c.fileGroupId === g.fileGroupId) })) };
}
