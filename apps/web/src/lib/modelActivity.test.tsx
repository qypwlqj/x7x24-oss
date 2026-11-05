import { afterEach, describe, it, expect, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { buildCards, mergeEvents, mergeTimeline, modelActivity, type TaskEnvelope } from './timeline';
import { Timeline } from '../components/Timeline';
import { NodeDetail, runningText } from '../views/CommandCenter';
import * as taskData from './taskData';
import * as taskStream from './taskStream';
import type { TaskDTO } from '../api';

const task = { id: 't', currentStage: 'DEV', status: 'DEV', currentIteration: 1, executionState: 'RUNNING', paused: false } as TaskDTO;
const scope = { taskId: 't', nodeId: 'DEV', iteration: 1 };
const call = (seq: number, state = 'RUNNING', data: Record<string, unknown> = {}, extra: Partial<TaskEnvelope> = {}): TaskEnvelope => ({
  seq, taskId: 't', nodeId: 'DEV', iteration: 1, at: `2026-10-27T10:00:${String(seq).padStart(2, '0')}Z`, type: 'MODEL_CALL', model: 'actual-model',
  data: { callId: 'c', groupId: 'g', runId: 'r', role: 'dev.code', kind: 'execution', source: 'llm', total: 1, model: 'actual-model', state, ...data }, ...extra,
});
const event = (seq: number, type: string, extra: Partial<TaskEnvelope> = {}): TaskEnvelope => ({ ...call(seq), type, data: {}, ...extra });

describe('model activity visibility', () => {
  it('shows the actual model during execution and uses past tense after the call returns', () => {
    expect(runningText(task, modelActivity([call(1)], scope))).toContain('actual-model');
    expect(runningText(task, modelActivity([call(1), call(2, 'SUCCEEDED')], scope))).toContain('本轮已用 actual-model');
    expect(runningText(task)).toBe('正在编码…');
    expect(runningText(task, [], null, null, true)).toBe('同步执行状态…');
  });

  it('renders CI testing after DEV without carrying its model into QA', () => {
    const qa = { ...task, status: 'QA', currentStage: 'QA' } as TaskDTO;
    const events = [call(1), call(2, 'SUCCEEDED'), event(3, 'STATUS_CHANGED', { nodeId: 'QA', from: 'DEV', to: 'QA' })];
    const calls = modelActivity(events, { ...scope, nodeId: 'QA' });
    const html = renderToStaticMarkup(<Timeline entries={mergeTimeline(buildCards(events), [])} taskId="t" calls={calls} running={runningText(qa, calls)} />);
    expect(calls).toEqual([]);
    expect(html).toContain('等待 CI 测试结果 · GitHub Actions');
    expect(html).not.toContain('模型未记录');
    expect(html).not.toContain('actual-model');
  });

  it('still shows actual QA model calls and keeps unreported calls distinct from CI', () => {
    const qa = { ...task, status: 'QA', currentStage: 'QA' } as TaskDTO;
    const qaScope = { ...scope, nodeId: 'QA' };
    const queued = call(1, 'QUEUED', { model: null }, { nodeId: 'QA', model: null });
    expect(runningText(qa, modelActivity([queued], qaScope))).toContain('等待模型上报');
    const started = call(2, 'RUNNING', {}, { nodeId: 'QA' });
    expect(runningText(qa, modelActivity([queued, started], qaScope))).toBe('正在测试… · actual-model');
    const finished = call(3, 'SUCCEEDED', {}, { nodeId: 'QA' });
    expect(runningText(qa, modelActivity([queued, started, finished], qaScope))).toBe('正在测试… · 本轮已用 actual-model');
  });

  it('keeps parallel reviewers separate and counts completed seats rather than models', () => {
    const events = [call(1, 'SUCCEEDED', { callId: 'a', kind: 'review', role: 'accept.risk', total: 3 }), call(2, 'RUNNING', { callId: 'b', kind: 'review', role: 'accept.cases', total: 3 }), call(3, 'RUNNING', { callId: 'c', kind: 'review', role: 'accept.ux', total: 3 })];
    // A replay includes the start of completed calls.
    events.unshift(call(0, 'RUNNING', { callId: 'a', kind: 'review', role: 'accept.risk', total: 3 }));
    const calls = modelActivity(events, scope);
    expect(runningText(task, calls)).toBe('正在评审… · 1/3 已完成');
    const html = renderToStaticMarkup(<Timeline entries={[]} taskId="t" calls={calls} running={runningText(task, calls)} />);
    expect(html.match(/data-testid="tl-model-seat"/g)).toHaveLength(3);
    expect(html).toContain('风险评审'); expect(html).toContain('用例评审'); expect(html).toContain('已完成');
    expect(html).toContain('[overflow-wrap:anywhere]');
  });

  it('reports arbitration independently', () => {
    const calls = modelActivity([call(1, 'RUNNING', { kind: 'arbitration', role: 'accept.arbiter', model: 'arbiter-model' })], scope);
    expect(runningText(task, calls)).toBe('正在仲裁… · arbiter-model');
  });

  it('shows a model switch when a remote job falls back to a new execution call', () => {
    const cards = buildCards([call(1, 'RUNNING', { source: 'agent' }), call(2, 'FAILED', { source: 'agent' }), call(3, 'QUEUED', { callId: 'legacy', model: null }, { model: null }), call(4, 'RUNNING', { callId: 'legacy', model: 'fallback' }, { model: 'fallback' })]);
    expect(cards).toHaveLength(1); expect(cards[0].title).toContain('actual-model → fallback');
    expect(cards[0].raw).toHaveLength(2);
  });

  it('updates a fallback within the same call and keeps one model switch record', () => {
    const events = [call(1), call(2, 'FAILED'), call(3, 'RUNNING', { model: 'fallback-model', previousModel: 'actual-model' }, { model: 'fallback-model' })];
    expect(modelActivity(events, scope)).toHaveLength(1);
    expect(runningText(task, modelActivity(events, scope))).toContain('fallback-model');
    const cards = buildCards(events); expect(cards).toHaveLength(1);
    expect(cards[0].title).toContain('actual-model → fallback-model');
    expect(buildCards(mergeEvents(events, [events[2]]).list)).toHaveLength(1);
  });

  it.each(['TASK_RESTARTED', 'TASK_RESUMED', 'NODE_RETRY_REQUESTED', 'TASK_PAUSED', 'AGENT_FAILED', 'STATUS_CHANGED'])('does not revive an old call after %s', type => {
    expect(modelActivity([call(1), event(2, type), call(3, 'SUCCEEDED'), call(4)], scope)).toEqual([]);
    expect(modelActivity([call(1), event(2, type), call(3, 'RUNNING', { callId: 'new', runId: 'new-run', model: 'new-model' })], scope)[0].model).toBe('new-model');
  });

  it('does not combine tasks, nodes, iterations or execution attempts', () => {
    const events = [call(1), call(2, 'RUNNING', { callId: 'new', runId: 'new-run' }), call(3, 'SUCCEEDED'), call(4, 'RUNNING', {}, { taskId: 'other' }), call(5, 'RUNNING', {}, { iteration: 2 }), call(6, 'RUNNING', {}, { nodeId: 'QA' }), call(7, 'RUNNING', { callId: 'late-old-seat' })];
    expect(modelActivity(events, scope).map(c => c.callId)).toEqual(['new']);
  });

  it('reconstructs a refreshed page without claiming an old server call is still active', () => {
    expect(modelActivity([call(2, 'SUCCEEDED'), call(1)], scope)[0].state).toBe('SUCCEEDED');
    const old = modelActivity([call(1, 'RUNNING', { stale: true })], scope);
    expect(old[0].state).toBe('UNCONFIRMED');
    expect(runningText(task, old)).toContain('本轮已用');
    expect(runningText({ ...task, paused: true }, old)).toBeNull();
    expect(runningText({ ...task, executionState: 'FAILED' }, old)).toBeNull();
  });

  it('attaches recorded actual models to result cards and preserves unknown legacy records', () => {
    const pr = event(3, 'EVIDENCE_ADDED', { evidence: { id: 'pr', kind: 'pr', stage: 'DEV', content: { number: 1 } } });
    const cards = buildCards([call(1), call(2, 'SUCCEEDED'), pr]);
    expect(cards[0].models).toEqual(['actual-model']);
    const html = renderToStaticMarkup(<Timeline taskId="t" entries={mergeTimeline(cards, [])} />);
    expect(html).toContain('使用模型：actual-model');
    const legacy = buildCards([pr]);
    expect(legacy[0].models).toEqual([]);
    expect(renderToStaticMarkup(<Timeline taskId="t" entries={mergeTimeline(legacy, [])} />)).toContain('本次未记录模型');
  });

  it('uses runtime-reported aliases and never invents a model for a queued remote job', () => {
    const queued = modelActivity([call(1, 'QUEUED', { source: 'agent', model: null }, { model: null })], scope);
    expect(runningText(task, queued)).toContain('等待开发助手启动');
    expect(runningText(task, [{ ...queued[0], state: 'RUNNING' }])).toContain('等待模型上报');
    expect(runningText(task, [{ ...queued[0], state: 'SUCCEEDED' }])).toContain('本次未记录模型');
    expect(modelActivity([call(1, 'RUNNING', { source: 'agent', model: 'gateway/alias' })], scope)[0].model).toBe('gateway/alias');
  });
});

describe('node model records', () => {
  afterEach(() => vi.restoreAllMocks());
  function renderNode(nodeId: string, events: TaskEnvelope[] = [], executor?: string) {
    const plan: taskData.PlanData = { status: 'QA', currentNode: 'QA', records: {}, graph: { schema: 2, edges: [], nodes: [
      { id: 'QA', stage: 'QA', label: '测试', executor: executor ?? 'system' },
      { id: 'CHECK', stage: 'QA', parent: 'QA', label: '安全检查', executor: 'check' },
    ] } };
    vi.spyOn(taskData, 'useRefreshing').mockImplementation(url => url?.endsWith('/plan') ? plan as any : null);
    vi.spyOn(taskStream, 'useTaskStream').mockReturnValue({ events, seq: events.at(-1)?.seq ?? 0, mode: 'live' });
    return renderToStaticMarkup(<NodeDetail task={task} nodeId={nodeId} />);
  }
  it.each([['QA', '本节点由 CI 执行'], ['CHECK', '本节点执行自动检查']])('explains the non-model executor for %s', (nodeId, text) => {
    const html = renderNode(nodeId);
    expect(html).toContain(text);
    expect(html).not.toContain('模型未记录');
  });
  it('preserves real model history on CI nodes', () => {
    const html = renderNode('QA', [call(1, 'RUNNING', {}, { nodeId: 'QA' })]);
    expect(html).toContain('actual-model');
    expect(html).toContain('查看 1 次调用');
    expect(html).not.toContain('本节点由 CI 执行');
  });
  it('does not hide missing model identifiers or model-backed executors', () => {
    expect(renderNode('QA', [call(1, 'QUEUED', { model: null }, { nodeId: 'QA', model: null })])).toContain('等待模型上报');
    expect(renderNode('QA', [], 'review')).toContain('暂无模型调用记录');
    expect(renderNode('DEV')).toContain('暂无模型调用记录');
  });

  describe('code review model display', () => {
    const rv = (seq: number, group: string, state: string, model: string, previousModel?: string) => call(seq, state, { callId: `c-${group}`, runId: 'review-run', fileGroupId: group, role: 'review.code', kind: 'review', model, ...(previousModel ? { previousModel } : {}) }, { nodeId: 'CODE_REVIEW', model });
    const done = (seq: number, data: Record<string, unknown>) => ({ ...call(seq), type: 'CODE_REVIEW_DONE', model: undefined, nodeId: 'CODE_REVIEW', data: { node: 'CODE_REVIEW', total: 0, files: 2, ...data } }) as TaskEnvelope;

    it('merges several groups switching to the same model in one run into one card', () => {
      const cards = buildCards([rv(1, 'group-1', 'RUNNING', 'kimi-k3.1-pro', 'kimi-k2.7-code'), rv(2, 'group-2', 'RUNNING', 'kimi-k3.1-pro', 'kimi-k2.7-code'), rv(3, 'group-3', 'RUNNING', 'kimi-k3.1-pro', 'kimi-k2.7-code')]);
      const sw = cards.filter(c => c.kind === 'direction.changed');
      expect(sw).toHaveLength(1); expect(sw[0].title).toBe('已切换模型：3 组换到了 kimi-k3.1-pro'); expect(sw[0].raw).toHaveLength(3);
    });
    it('keeps a single-group switch as before and does not merge different target models', () => {
      const cards = buildCards([rv(1, 'group-1', 'RUNNING', 'kimi-k3.1-pro', 'kimi-k2.7-code'), rv(2, 'group-2', 'RUNNING', 'deepseek-chat', 'kimi-k2.7-code')]);
      expect(cards.map(c => c.title)).toEqual(['已切换模型：kimi-k2.7-code → kimi-k3.1-pro', '已切换模型：kimi-k2.7-code → deepseek-chat']);
    });
    it('shows the models recorded on the review-done event even after a pause retired the calls', () => {
      const cards = buildCards([rv(1, 'group-1', 'SUCCEEDED', 'kimi-k3.1-pro'), event(2, 'TASK_PAUSED', { data: { paused: true } }), done(3, { models: ['kimi-k3.1-pro', 'deepseek-chat'] })]);
      const card = cards.find(c => c.raw.some(e => e.type === 'CODE_REVIEW_DONE'))!;
      expect(card.models).toEqual(['kimi-k3.1-pro', 'deepseek-chat']);
      expect(renderToStaticMarkup(<Timeline taskId="t" entries={mergeTimeline(cards, [])} />)).toContain('使用模型：kimi-k3.1-pro、deepseek-chat');
    });
  });
});
