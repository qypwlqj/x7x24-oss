import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { createElement } from 'react';
import { buildCards, dropCurrentBlocked, mergeEvents, mergeTimeline, toCard, type TaskEnvelope } from './timeline';
import { Timeline } from '../components/Timeline';
import { stripNodes } from './taskData';
import { canTransition } from '@x7x24/shared';
import { causeText } from './taskActions';

const ev = (seq: number, type: string, x: Partial<TaskEnvelope> = {}): TaskEnvelope => ({ seq, taskId: 't1', type, at: `2026-10-24T01:00:${String(seq).padStart(2, '0')}Z`, ...x });
describe('timeline', () => {
  it('shows one configuration error per blocking episode and retains its raw events', () => {
    const data = { category: 'model_config_error', error: '需要两个模型', retryable: false, willRetry: false };
    const events = [ev(1, 'AGENT_FAILED', { nodeId: 'PRD_REVIEW', data }),
      ev(2, 'ANOMALY_OPENED', { nodeId: 'patrol', data: { type: 'model_config_error', detail: data.error } }),
      ev(3, 'STATUS_CHANGED', { nodeId: 'ANOMALY', from: 'PRD_REVIEW', to: 'ANOMALY' })];
    const cards = buildCards(events);
    expect(cards).toHaveLength(1); expect(cards[0].title).toBe('模型配置有误，已暂停');
    expect(cards[0].raw.map(e => e.seq)).toEqual([1, 2, 3]);
    const withConfirmation = buildCards([...events, ev(4, 'TASK_ACTION_EXECUTED', { data: { kind: 'task_pause' } })]);
    expect(dropCurrentBlocked(withConfirmation, true).filter(c => c.kind === 'task.blocked')).toHaveLength(0);
    const retried = buildCards([...events, ev(5, 'STATUS_CHANGED', { from: 'ANOMALY', to: 'PRD_REVIEW' }), ev(6, 'AGENT_FAILED', { data })]);
    expect(retried.filter(c => c.kind === 'task.blocked')).toHaveLength(2);
  });
  it('renders model configuration errors as blocked cards with a working settings link', () => {
    expect(causeText('model_config_error')).toBe('模型配置有误');
    const cards = buildCards([ev(1, 'AGENT_FAILED', { data: { type: 'IsolationError', retryable: false, willRetry: false, category: 'model_config_error', error: '评审委员会至少需要 2 个不同模型' } })]);
    expect(cards[0].kind).toBe('task.blocked'); expect(cards[0].tone).toBe('danger'); expect(cards[0].icon).toBe('blocked');
    expect(cards[0].expand).toBe('card'); expect(cards[0].link?.href).toBe('/admin/models');
    const html = renderToStaticMarkup(createElement(Timeline, { entries: mergeTimeline(cards, []), taskId: 't1' }));
    expect(html).toContain('模型配置有误，已暂停'); expect(html).toContain('href="/admin/models"');
    expect(html).toContain('评审委员会至少需要 2 个不同模型'); expect(html).not.toContain('正在自动重试');
    expect(toCard(ev(2, 'AGENT_FAILED', { data: { retryable: true, willRetry: true, error: 'timeout' } }))?.kind).toBe('agent.thinking');
    expect(toCard(ev(3, 'AGENT_FAILED', { data: { retryable: true, willRetry: false, error: 'timeout' } }))?.kind).toBe('task.blocked');
  });
  it('allows the shared state machine to pause and resume the failed review', () => {
    for (const stage of ['PRD_REVIEW', 'SPEC', 'DESIGN', 'ACCEPTANCE'] as const) {
      expect(canTransition(stage, 'ANOMALY', 'anomaly')).toBe(true);
      expect(canTransition('ANOMALY', stage, 'resume')).toBe(true);
    }
  });
  it('maps event types to card kinds', () => {
    expect(toCard(ev(1, 'STATUS_CHANGED', { from: 'PLAN', to: 'IMPLEMENT', nodeId: 'IMPLEMENT' }))?.kind).toBe('node.entered');
    expect(toCard(ev(2, 'STATUS_CHANGED', { to: 'RELEASED' }))?.kind).toBe('task.done');
    expect(toCard(ev(3, 'EVIDENCE_ADDED', { evidence: { id: 'e', kind: 'pr', stage: 'IMPLEMENT', content: { number: 7, url: 'https://github.com/a/b/pull/7' } } }))?.kind).toBe('pr.opened');
    const ci = toCard(ev(4, 'EVIDENCE_ADDED', { evidence: { id: 'c', kind: 'ci_log', stage: 'TEST', content: { state: 'failed', log: 'a\nboom' } } }))!;
    expect(ci.kind).toBe('ci.failed'); expect(ci.expand).toBe('collapse');
    expect(toCard(ev(5, 'MERGE_APPROVAL_REQUESTED'))?.kind).toBe('approval.waiting');
    expect(toCard(ev(6, 'BUDGET_HIT'))?.kind).toBe('budget');
  });
  it('shows one observation result and distinguishes a return from a paused review', () => {
    const cards = buildCards([
      ev(1, 'VERDICT', { data: { gate: 'code_review', decision: 'pass' } }),
      ev(2, 'CODE_REVIEW_DONE', { data: { wouldBlock: true, blockingCount: 2 } }),
      ev(3, 'NODE_REJECTED', { data: { label: '安全审计', returnTo: 'DEV', loops: 1, maxLoops: 2, reason: 'src/auth.ts:27 缺少资源归属校验' } }),
      ev(4, 'NODE_REJECTED', { data: { label: '安全审计', paused: true, pauseCode: 'review_plan_adjustment' } }),
    ]);
    expect(cards.map(c => c.title)).toEqual([
      '发现 2 项阻断级问题 · 观察模式继续',
      '安全审计未通过 → 已退回开发 · 第 1/2 轮返修',
      '安全审计未通过 → 待确认方案调整',
    ]);
    expect(cards[1].expand).toBe('collapse');
    expect(cards[1].text).toContain('src/auth.ts:27');
  });
  it('merges consecutive tool calls within a node and dedupes by seq', () => {
    const es = [ev(1, 'ADAPTER_CALL', { nodeId: 'IMPLEMENT', data: { tool: 'git' } }), ev(2, 'ADAPTER_CALL', { nodeId: 'IMPLEMENT', data: { tool: 'npm' } }), ev(3, 'ADAPTER_CALL', { nodeId: 'TEST', data: { tool: 'x' } })];
    const cards = buildCards(es); expect(cards).toHaveLength(2); expect(cards[0].items).toHaveLength(2);
    expect(mergeEvents(es, [es[2], ev(4, 'X')]).list.map(e => e.seq)).toEqual([1, 2, 3, 4]);
  });
  it('interleaves chat and inserts node dividers; renders plain text with raw under 详情', () => {
    const cards = buildCards([ev(1, 'STATUS_CHANGED', { from: 'PLAN', to: 'IMPLEMENT', nodeId: 'IMPLEMENT' }), ev(9, 'STATUS_CHANGED', { to: 'ANOMALY', from: 'TEST' })]);
    const entries = mergeTimeline(cards, [{ id: 1, role: 'user', text: '做个登录页', at: '2026-10-24T01:00:05Z' }]);
    expect(entries.map(e => e.t)).toEqual(['divider', 'card', 'chat', 'card']);
    const html = renderToStaticMarkup(createElement(Timeline, { entries, taskId: 't1' }));
    expect(html).toContain('做个登录页'); expect(html).toContain('详情'); expect(html).toContain('data-kind="task.blocked"');
  });
  it('strip falls back to null without a graph', () => { expect(stripNodes({ status: 'PLAN', records: {} })).toBeNull(); });
});
