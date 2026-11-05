import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { retryText, reviewActivity } from './reviewActivity';
import { describeEvent } from './eventText';
import { BlockedCard } from '../components/FlowControls';
import { causeText, paramLines, type ActionState } from './taskActions';
import { buildCards, type TaskEnvelope } from './timeline';
import { Timeline } from '../components/Timeline';
import { runningText } from '../views/CommandCenter';
import type { TaskDTO } from '../api';
import type { PlanData } from './taskData';

const scope = { taskId: 't', nodeId: 'CODE_REVIEW', iteration: 1 };
const task = { id: 't', status: 'ACCEPTANCE', currentStage: 'ACCEPTANCE', currentIteration: 1, executionState: 'RUNNING', paused: false } as TaskDTO;
const plan: PlanData = { status: 'ACCEPTANCE', currentNode: 'CODE_REVIEW', records: {}, graph: { schema: 2, nodes: [{ id: 'CODE_REVIEW', stage: 'CODE_REVIEW', label: '逐行审查' }], edges: [] } };
function progress(seq: number, index: number, state: string, extra: Record<string, unknown> = {}): TaskEnvelope {
  return { seq, ...scope, type: 'CODE_REVIEW_PROGRESS', at: '2026-11-02T09:00:00Z', data: { runId: 'r', fileGroupId: `group-${index}`, index, total: 8, concurrency: 3, slot: state === 'QUEUED' ? 0 : index, files: [`src/g${index}/a.ts`], startedAt: 1000, updatedAt: 2000 + seq * 1000, state, ...extra } };
}
const queued = () => Array.from({ length: 8 }, (_, i) => progress(i + 1, i + 1, 'QUEUED'));
function render(events: TaskEnvelope[], uncertain = false) {
  const review = reviewActivity(events, scope);
  return renderToStaticMarkup(<Timeline entries={[]} taskId="t" calls={[]} review={review} uncertain={uncertain} lastSyncAt={Date.UTC(2026, 10, 2, 9)} running={runningText(task, [], plan, review)} />);
}

describe('逐行审查分组进度', () => {
  it('shows three active groups and folds completed calls instead of rendering six workers', () => {
    const events = [...queued(), progress(9, 1, 'COMPLETED'), progress(10, 2, 'COMPLETED'), progress(11, 3, 'VERIFYING', { slot: 1 }), progress(12, 4, 'REVIEWING', { slot: 2 }), progress(13, 5, 'REVIEWING', { slot: 3 })];
    const html = render(events);
    expect(html).toContain('已完成 2 / 8 组'); expect(html).toContain('3 组处理中 · 3 组等待开始');
    expect(html.match(/data-testid="review-group"/g)).toHaveLength(3);
    expect(html).toContain('正在复核'); expect(html).toContain('已完成 2 组');
    expect(html).not.toContain('0/1'); expect(html).not.toContain('group-1"');
    expect(buildCards(events)).toEqual([]);
  });

  it('updates the same group and slot across phases and joins its real model calls', () => {
    const call: TaskEnvelope = { ...progress(12, 1, 'REVIEWING'), type: 'MODEL_CALL', model: 'actual-model', data: { callId: 'c', groupId: 'c', runId: 'r', fileGroupId: 'group-1', slot: 1, kind: 'review', role: 'review.code.verify', source: 'llm', state: 'RUNNING', total: 1, model: 'actual-model' } };
    const events = [...queued(), progress(9, 1, 'REVIEWING'), progress(10, 1, 'VALIDATING'), progress(11, 1, 'VERIFYING'), call];
    const review = reviewActivity(events, scope)!;
    expect(review.groups).toHaveLength(8); expect(review.groups[0].state).toBe('VERIFYING');
    expect(review.groups[0].slot).toBe(1); expect(review.groups[0].calls[0].model).toBe('actual-model');
    expect(render(events)).toContain('actual-model');
    events.push(progress(13, 1, 'COMPLETED'), progress(14, 4, 'REVIEWING', { slot: 1 }));
    const html = render(events);
    expect(html.match(/data-testid="review-group"/g)).toHaveLength(1);
    expect(html).toContain('data-group="group-4" data-slot="1"');
    expect(html).toContain('已完成 1 / 8 组');
  });

  it('replays sorted events, keeps failures and skips separate, and never revives a completed group', () => {
    const events = [...queued(), progress(9, 1, 'COMPLETED'), progress(10, 2, 'FAILED'), progress(11, 3, 'SKIPPED'), progress(12, 1, 'REVIEWING')];
    const review = reviewActivity([...events].reverse(), scope)!;
    expect(review.groups[0].state).toBe('COMPLETED');
    const html = render(events);
    expect(html).toContain('已完成 1 / 8 组'); expect(html).toContain('1 组失败'); expect(html).toContain('1 组没审（超出上限）');
    expect(html).toContain('完成 1 · 没审 1'); expect(html).toContain('data-testid="review-failed"');
  });

  it.each(['TASK_RESUMED', 'NODE_RETRY_REQUESTED', 'TASK_PAUSED', 'STATUS_CHANGED'])('ignores late progress after %s and starts a new execution independently', type => {
    const reset: TaskEnvelope = { ...progress(10, 1, 'QUEUED'), type, data: {} };
    const events = [...queued(), progress(9, 1, 'REVIEWING'), reset, progress(11, 1, 'VERIFYING')];
    expect(reviewActivity(events, scope)).toBeNull();
    events.push(progress(12, 1, 'QUEUED', { runId: 'new' }), progress(13, 1, 'REVIEWING', { runId: 'new' }), progress(14, 2, 'QUEUED'));
    expect(reviewActivity(events, scope)!.runId).toBe('new');
    expect(reviewActivity(events, scope)!.groups).toHaveLength(1);
  });

  it('removes running animation on completion, pause, failed sync and previous runtime events', () => {
    const events = [...queued(), progress(9, 1, 'REVIEWING')];
    expect(render(events).match(/class="execution-signal"/g)).toHaveLength(2);
    const disconnected = render(events, true);
    expect(disconnected).toContain('状态待确认'); expect(disconnected).toContain('最后更新于');
    expect(disconnected).not.toContain('execution-signal');
    expect(render([...queued(), progress(9, 1, 'REVIEWING', { stale: true })])).not.toContain('execution-signal');
    events.push({ ...progress(10, 1, 'COMPLETED'), type: 'CODE_REVIEW_DONE', data: { runId: 'r' } });
    expect(render(events)).not.toContain('tl-running');
    expect(runningText({ ...task, paused: true })).toBeNull();
  });

  it('uses the configured concurrency and preserves committee seat rendering', () => {
    const html = render([progress(1, 1, 'QUEUED', { concurrency: 2 }), progress(2, 1, 'REVIEWING', { concurrency: 2 })]);
    expect(html).toContain('最多 2 组并行');
    expect(reviewActivity([{ ...progress(1, 1, 'QUEUED'), taskId: 'other' }], scope)).toBeNull();
    expect(reviewActivity([{ ...progress(1, 1, 'QUEUED'), iteration: 2 }], scope)).toBeNull();
    expect(runningText(task, [], plan)).toBe('正在逐行审查…');
  });

  it('hides a legacy completed run without inventing groups and ignores old completion during a new run', () => {
    const done: TaskEnvelope = { ...progress(1, 1, 'COMPLETED'), type: 'CODE_REVIEW_DONE', data: {} };
    expect(render([done])).not.toContain('tl-running');
    const events = [done, progress(2, 1, 'QUEUED', { runId: 'new' }), progress(3, 1, 'REVIEWING', { runId: 'new' }), { ...done, seq: 4 }];
    expect(reviewActivity(events, scope)!.finished).toBe(false);
    expect(render(events)).toContain('tl-running');
  });
});

describe('逐行审查：重试、换模型、失败与部分审查', () => {
  it('shows the retry attempt, model and error kind on the active group', () => {
    const events = [...queued(), progress(9, 1, 'REVIEWING', { slot: 1, attempt: 2, maxAttempts: 2, model: 'kimi-k2.7-code', errorKind: 'timeout', deadlineAt: 999999 })];
    const html = render(events);
    expect(html).toContain('接口超时，正在第 2/2 次重试（kimi-k2.7-code）');
    expect(reviewActivity(events, scope)!.groups[0].deadlineAt).toBe(999999);
  });

  it('says which model it switched to', () => {
    expect(retryText({ state: 'REVIEWING', attempt: 1, maxAttempts: 2, model: 'deepseek-chat', switchedFrom: 'kimi-k2.7-code', errorKind: null })).toBe('kimi-k2.7-code 没成功，已换 deepseek-chat');
    expect(retryText({ state: 'REVIEWING', attempt: 1, maxAttempts: 2, model: 'kimi-k2.7-code', errorKind: null })).toBeNull();
    expect(retryText({ state: 'COMPLETED', attempt: 2, maxAttempts: 2, model: 'kimi-k2.7-code', errorKind: 'timeout' })).toBeNull();
  });

  it('marks failed groups red with the reason and capped groups as not reviewed', () => {
    const events = [...queued(), progress(9, 1, 'FAILED', { slot: 1, reason: '审查出错：接口超时' }), progress(10, 2, 'REVIEWING', { slot: 2 }), progress(11, 8, 'SKIPPED', { reason: '超出本次审查的分组上限' })];
    const html = render(events);
    expect(html).toContain('1 组失败'); expect(html).toContain('1 组没审（超出上限）');
    expect(html).toContain('border-danger-ink'); expect(html).toContain('data-testid="review-failed-reason"'); expect(html).toContain('审查出错：接口超时');
  });

  it('ends the live panel when the review stops for failed groups', () => {
    const events = [...queued(), progress(9, 1, 'FAILED', { slot: 1 }), { ...progress(10, 1, 'FAILED'), type: 'CODE_REVIEW_FAILED', data: { runId: 'r', failed: [{ files: ['src/g1/a.ts'], reason: '接口超时' }], completedGroups: 7, totalGroups: 8 } } as TaskEnvelope];
    expect(reviewActivity(events, scope)!.finished).toBe(true);
    const t = describeEvent({ event_type: 'CODE_REVIEW_FAILED', payload: { label: '逐行审查', failed: [{ files: ['src/g1/a.ts'], reason: '接口超时' }], completedGroups: 7, totalGroups: 8 } });
    expect(t.title).toBe('逐行审查有 1 组没审完，已停下'); expect(t.text).toContain('已审完 7/8 组'); expect(t.text).toContain('src/g1/a.ts：接口超时');
  });

  it('writes partial coverage on the done event', () => {
    const t = describeEvent({ event_type: 'CODE_REVIEW_DONE', payload: { files: 20, total: 0, partial: true, coverageText: '只审了 12/39 组，其余 27 组（27 个文件）没审（超出本次审查的分组上限 27 组）：src/a/、src/b/\n按规则排除 3 个文件（锁文件、生成文件、文档、图片等），不算没审' } });
    expect(t.title).toBe('逐行审查完成（部分审查）'); expect(t.tone).toBe('warn');
    expect(t.text).toContain('只审了 12/39 组'); expect(t.text).toContain('按规则排除 3 个文件');
  });
});

describe('逐行审查没审完的停下卡片', () => {
  it('offers resume retry and a manager-only skip', () => {
    const act: any = { propose: async () => ({}), confirm: async () => ({}), cancel: async () => {}, say: async () => ({}), reload: () => {}, startSession: async () => '' };
    const s: ActionState = { blocked: true, status: 'ACCEPTANCE', node: 'CODE_REVIEW', paused: false, cause: 'code_review_failed', detail: '逐行审查没审完：已审完 11/12 个文件，结果已保存。', evidence: [],
      suggestions: [
        { kind: 'task_resume', title: '重试', why: '接着上次的审查往下审，已审完的文件不重审；不占返修次数', params: {}, recommended: true, danger: 'low', minRole: 'member' },
        { kind: 'code_review_skip_failed', title: '跳过并继续', why: '', params: {}, recommended: false, danger: 'mid', minRole: 'admin' }],
      pending: [], history: [], can: { act: true, admin: true }, sessionId: null, lockKey: 'k' };
    const html = renderToStaticMarkup(<BlockedCard s={s} act={act} onProposed={() => {}} />);
    for (const t of ['逐行审查没审完', '重试', '跳过并继续', '已审完 11/12 个文件']) expect(html).toContain(t);
    expect(causeText('code_review_failed')).toBe('逐行审查没审完');
    expect(paramLines({ kind: 'code_review_skip_failed', params: {} }).join('\n')).toContain('部分审查');
  });
});

describe('open-code-review 进度', () => {
  const ev = (seq: number, data: any, type = 'CODE_REVIEW_PROGRESS'): any => ({ seq, taskId: 't', nodeId: 'CODE_REVIEW', iteration: 1, type, data, at: '2026-11-04T00:00:00Z' });
  it('tracks one whole-review state instead of groups', () => {
    const a = reviewActivity([ev(1, { runId: 'r1', engine: 'ocr', state: 'REVIEWING' })], { taskId: 't', nodeId: 'CODE_REVIEW', iteration: 1 })!;
    expect(a.engine).toBe('ocr'); expect(a.groups).toEqual([]); expect(a.finished).toBe(false);
    const b = reviewActivity([ev(1, { runId: 'r1', engine: 'ocr', state: 'REVIEWING' }), ev(2, { runId: 'r1', engine: 'ocr', state: 'COMPLETED' })], { taskId: 't', nodeId: 'CODE_REVIEW', iteration: 1 })!;
    expect(b.finished).toBe(true);
  });
  it('describes a failed ocr run with files and models', () => {
    const t = describeEvent({ event_type: 'CODE_REVIEW_FAILED', payload: { label: '逐行审查', reviewedFiles: 9, totalFiles: 12, failedFiles: [{ path: 'src/a.ts', reason: '接口超时' }], attempts: [{ model: 'kimi' }, { model: 'deepseek-chat' }] } });
    expect(t.title).toBe('逐行审查没审完，已停下'); expect(t.text).toContain('已审完 9/12 个文件'); expect(t.text).toContain('kimi → deepseek-chat'); expect(t.text).toContain('src/a.ts：接口超时');
  });
});
