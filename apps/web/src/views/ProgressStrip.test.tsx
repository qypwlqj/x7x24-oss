import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { ProgressStrip } from './CommandCenter';
import type { TaskDTO } from '../api';
import { progressActivity, progressStripTarget, stripNodes, type PlanData } from '../lib/taskData';
import { progressSteps } from '../lib/commandCenter';

const task = { id: 'task', status: 'DEV', currentStage: 'DEV', executionState: 'RUNNING' } as TaskDTO;
const record = (state = 'SUCCEEDED', startedAt?: string, endedAt?: string, runs = 1) => ({ state, runs, usd: 0, tokens: 0, startedAt, endedAt });
const planFor = (extra: Partial<PlanData> = {}): PlanData => ({ status: 'DEV', currentNode: 'DEV', records: {}, graph: { schema: 2, edges: [], nodes: [
  { id: 'INTAKE', stage: 'BACKLOG', label: '需求收录', module: 'sensing' },
  { id: 'DEV', stage: 'DEV', label: '编码', module: 'production' },
  { id: 'QA', stage: 'QA', label: '测试', module: 'delivery' },
  { id: 'CHECK', stage: 'QA', parent: 'QA', label: '安全检查' },
  { id: 'RELEASE', stage: 'RELEASE', label: '上线', module: 'delivery' },
] }, ...extra });
const targetFor = (plan: PlanData, active?: string) => progressStripTarget(stripNodes(plan)!, plan, active);

describe('progress strip initial target', () => {
  it('prioritizes the explicitly viewed node over current execution', () => {
    expect(targetFor(planFor(), 'QA')).toBe('QA');
    expect(targetFor(planFor(), 'missing')).toBe('DEV');
  });

  it('locates the parent of an explicitly viewed or currently executing child', () => {
    expect(targetFor(planFor(), 'CHECK')).toBe('QA');
    expect(targetFor(planFor({ currentNode: 'CHECK' }))).toBe('QA');
  });

  it.each(['ANOMALY', 'DEGRADED', 'DEV'])('keeps the authoritative node when status is %s', status => {
    expect(targetFor(planFor({ status, paused: true, records: {
      QA: record('FAILED', '2026-10-27T10:00:00Z', '2026-10-27T10:01:00Z'),
    } }))).toBe('DEV');
  });

  it('uses execution time instead of graph position when a task returns to an earlier node', () => {
    expect(targetFor(planFor({ currentNode: null, records: {
      DEV: record('SUCCEEDED', '2026-10-27T10:00:00Z', '2026-10-27T12:00:00Z', 2),
      QA: record('FAILED', '2026-10-27T11:00:00Z', '2026-10-27T11:01:00Z'),
      RELEASE: record('SKIPPED', '2026-10-27T13:00:00Z', '2026-10-27T13:01:00Z', 0),
    } }))).toBe('DEV');
  });

  it('uses startedAt when the latest execution has no valid endedAt', () => {
    expect(targetFor(planFor({ currentNode: 'missing', records: {
      INTAKE: record('SUCCEEDED', undefined, '2026-10-27T10:00:00Z'),
      DEV: record('RUNNING', '2026-10-27T11:00:00Z', 'invalid'),
    } }))).toBe('DEV');
  });

  it('ignores skipped, unexecuted and removed nodes when selecting history', () => {
    expect(targetFor(planFor({ currentNode: null, records: {
      QA: record('FAILED'),
      RELEASE: record('SKIPPED', undefined, '2026-10-27T12:00:00Z'),
      DEV: record('PENDING', undefined, '2026-10-27T13:00:00Z', 0),
      REMOVED: record('SUCCEEDED', undefined, '2026-10-27T14:00:00Z'),
    } }))).toBe('QA');
  });

  it('locates the parent of a recently executed child', () => {
    expect(targetFor(planFor({ currentNode: null, records: { CHECK: record() } }))).toBe('QA');
  });

  it('does not infer execution merely from the done display state of a completed task', () => {
    const plan = planFor({ status: 'RELEASED', currentNode: null });
    expect(stripNodes(plan)!.every(s => s.state !== 'done')).toBe(true);
    expect(targetFor(plan)).toBe('INTAKE');
    expect(targetFor({ ...plan, records: { QA: record() } })).toBe('QA');
  });

  it('keeps the start when no execution exists and handles an empty graph', () => {
    expect(targetFor(planFor({ currentNode: null }))).toBe('INTAKE');
    expect(progressStripTarget([], planFor())).toBeNull();
  });

  it('uses the current or completed stage for legacy tasks without a graph', () => {
    const steps = (t: TaskDTO) => progressSteps(t).map(s => ({ id: s.stage, state: s.state, children: [] }));
    expect(progressStripTarget(steps(task))).toBe('DEV');
    expect(progressStripTarget(steps({ ...task, executionState: 'FAILED' }))).toBe('DEV');
    expect(progressStripTarget(steps(task), { status: 'DEV', records: {} }, 'QA')).toBe('QA');
    expect(progressStripTarget(steps({ ...task, status: 'RELEASED' }))).toBe('DELIVERED');
  });
});

describe('progress strip labels', () => {
  it('provides a native disclosure button linked to every lower detail group', () => {
    const html = renderToStaticMarkup(<ProgressStrip task={task} plan={planFor()} />);
    const button = html.match(/<button[^>]*aria-expanded="true"[^>]*aria-controls="([^"]+)"[^>]*>/);
    expect(button).not.toBeNull();
    expect(button![0]).toContain('type="button"');
    const groups = button![1].split(' ');
    expect(groups).toHaveLength(stripNodes(planFor())!.length);
    for (const id of groups) expect(html).toContain(`id="${id}"`);
    expect(html).toContain('收起详情');
    expect(html).toContain('aria-current="step"');
  });

  it('keeps full long names discoverable and preserves node and child links', () => {
    const labels = ['接口契约设计', '数据库变更设计', '自定义节点名称很长时仍可以查看完整名称'];
    const plan: PlanData = { status: 'DEV', currentNode: 'N1', records: {}, graph: { schema: 2, edges: [], nodes: [
      ...labels.map((label, i) => ({ id: `N${i}`, stage: 'DEV', module: 'production', label })),
      { id: 'CHECK', stage: 'DEV', parent: 'N1', label: '数据库安全检查' },
    ] } };
    const html = renderToStaticMarkup(<ProgressStrip task={{ id: 'task' } as TaskDTO} plan={plan} active="N1" />);
    labels.forEach((label, i) => {
      expect(html).toContain(`aria-label="${label} · `);
      expect(html).toContain(`/t/task/node/N${i}`);
      expect(html).toContain(`>${label}</span>`);
    });
    expect(html).toContain('data-testid="cc-subnodes"');
    expect(html).toContain('aria-label="数据库安全检查 · ');
    expect(html).toContain('/t/task/node/CHECK');
    expect(html.match(/data-state="closed"/g)).toHaveLength(labels.length);
  });
});

describe('progress strip execution', () => {
  it('marks downstream history as awaiting recheck after returning to development', () => {
    const plan = planFor({ records: { QA: { ...record(), outcome: 'PASSED' }, CHECK: { ...record(), outcome: 'PASSED' } } });
    const qa = stripNodes(plan)!.find(n => n.id === 'QA')!;
    expect(qa.state).toBe('pending');
    const html = renderToStaticMarkup(<ProgressStrip task={task} plan={plan} />);
    expect(html).toContain('待复验');
    expect(html).toContain('上次已通过');
    expect(html).toContain('检查 0/1');
  });

  it('keeps the cat at execution when another node is being viewed', () => {
    const html = renderToStaticMarkup(<ProgressStrip task={task} plan={planFor()} active="QA" />);
    expect(html).toContain('data-testid="progress-cat"');
    expect(html).toContain('data-execution-node="DEV"');
    expect(html).toContain('aria-current="step"');
    expect(html).toContain('bg-ink text-ink-inverse');
    expect(html).not.toContain('border-ink bg-raised bg-ink');
  });

  it('keeps invalidated results gray after execution has moved past their position', () => {
    const plan = planFor({ status: 'RELEASED', currentNode: 'RELEASE', records: {
      DEV: { ...record(), outcome: 'PASSED', valid: true }, QA: { ...record(), outcome: 'PASSED', valid: false },
    } });
    const qa = stripNodes(plan)!.find(n => n.id === 'QA')!;
    expect(qa).toMatchObject({ state: 'pending', recheck: true, historyOutcome: 'PASSED' });
    const html = renderToStaticMarkup(<ProgressStrip task={{ ...task, status: 'RELEASED' }} plan={plan} />);
    expect(html).toContain('测试 · 待复验'); expect(html).toContain('上次已通过');
  });

  it('retains valid independent results and distinguishes failed tests from successful execution', () => {
    const plan = planFor({ records: { QA: { ...record(), outcome: 'FAILED', valid: true } } });
    expect(stripNodes(plan)!.find(n => n.id === 'QA')!.state).toBe('rejected');
    plan.records.QA.outcome = 'PASSED';
    expect(stripNodes(plan)!.find(n => n.id === 'QA')!).toMatchObject({ state: 'done', recheck: false });
  });

  it('shows child execution at its parent with the check name', () => {
    const html = renderToStaticMarkup(<ProgressStrip task={{ ...task, status: 'QA', currentStage: 'QA' }} plan={planFor({ status: 'QA', currentNode: 'CHECK' })} />);
    expect(html).toContain('data-execution-node="QA"');
    expect(html).toContain('正在安全检查');
    expect(stripNodes(planFor({ status: 'QA', currentNode: 'CHECK' }))!.find(n => n.id === 'QA')!.state).toBe('current');
  });

  it.each([
    [{ paused: true }, 'paused', '已暂停'],
    [{ executionState: 'WAITING' }, 'waiting', '等待执行'],
    [{ executionState: 'BLOCKED' }, 'blocked', '需要处理'],
    [{ executionState: 'FAILED' }, 'blocked', '执行失败'],
    [{ status: 'RELEASED', executionState: 'COMPLETED' }, 'done', '已完成'],
    [{ status: 'DISCARDED', executionState: 'DISCARDED' }, 'stopped', '已结束'],
  ] as const)('expresses execution state %j independently of node selection', (extra, mode, label) => {
    const html = renderToStaticMarkup(<ProgressStrip task={{ ...task, ...extra } as TaskDTO} plan={planFor()} active="QA" />);
    expect(html).toContain(`data-mode="${mode}"`);
    expect(html).toContain(label);
  });
});

describe('stage-specific cat activity', () => {
  it.each([
    ['BACKLOG', 'think'], ['PLANNING', 'think'], ['SPEC', 'think'], ['PRD_REVIEW', 'think'],
    ['API_DESIGN', 'draw'], ['DB_SCHEMA', 'draw'], ['UX_DESIGN', 'draw'], ['DESIGN', 'draw'],
    ['DEV', 'type'], ['QA', 'test'], ['CODE_REVIEW', 'review'], ['SEC_AUDIT', 'review'],
    ['ACCEPTANCE', 'accept'], ['RELEASE', 'deliver'], ['UNRECOGNIZED', 'think'],
  ])('uses %s to choose the %s activity', (stage, behavior) => {
    const plan: PlanData = { status: stage, currentNode: stage, records: {}, graph: { schema: 2, edges: [], nodes: [{ id: stage, stage, label: '当前节点' }] } };
    const activity = progressActivity(task, plan, stripNodes(plan)!);
    expect(activity.behavior).toBe(behavior);
    const html = renderToStaticMarkup(<ProgressStrip task={task} plan={plan} active="QA" />);
    expect(html).toContain(`data-behavior="${behavior}"`);
  });

  it('uses custom node metadata and keeps child checks at their parent', () => {
    const plan: PlanData = { status: 'QA', currentNode: 'CUSTOM_CHECK', records: {}, graph: { schema: 2, edges: [], nodes: [
      { id: 'DEV', stage: 'DEV', label: '编码' }, { id: 'CUSTOM_CHECK', stage: 'DEV', parent: 'DEV', family: 'check', executor: 'check', label: '专项检查' },
    ] } };
    expect(progressActivity(task, plan, stripNodes(plan)!)).toMatchObject({ nodeId: 'DEV', childLabel: '专项检查', behavior: 'test' });
    const review = { ...plan, currentNode: 'CUSTOM', graph: { schema: 2, edges: [] as [string, string][], nodes: [{ id: 'CUSTOM', stage: 'CUSTOM', executor: 'review', label: '自定义评审' }] } };
    expect(progressActivity(task, review, stripNodes(review)!).behavior).toBe('review');
  });

  it('keeps the stage while execution state overrides its working animation', () => {
    for (const executionState of ['PAUSED', 'WAITING', 'FAILED'] as const) {
      const html = renderToStaticMarkup(<ProgressStrip task={{ ...task, executionState }} plan={planFor()} />);
      expect(html).toContain('data-behavior="type"');
      expect(html).not.toContain('data-mode="running"');
    }
  });

  it('uses the task stage for legacy flows', () => {
    const steps = progressSteps(task).map(s => ({ id: s.stage, label: s.label, state: s.state, children: [] }));
    expect(progressActivity(task, null, steps).behavior).toBe('type');
    expect(progressActivity(task, null, steps).successKey).toBe('');
  });

  it('only publishes success feedback keys for actual successful records', () => {
    const plan = planFor({ records: { DEV: record('RUNNING'), QA: { ...record('SUCCEEDED'), outcome: 'FAILED' }, CHECK: { ...record('SUCCEEDED', undefined, '2026-10-27T06:00:00Z'), outcome: 'PASSED' } } });
    const activity = progressActivity(task, plan, stripNodes(plan)!);
    expect(JSON.parse(activity.successKey)).toEqual(['CHECK:1:2026-10-27T06:00:00Z']);
    expect(progressActivity(task, { ...plan, records: { ...plan.records } }, stripNodes(plan)!).successKey).toBe(activity.successKey);
    expect(JSON.parse(progressActivity(task, { ...plan, records: { QA: record(), CHECK: { ...plan.records.CHECK, valid: false } } }, stripNodes(plan)!).successKey)).toEqual([]);
  });
});
