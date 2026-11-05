import { describe, expect, it } from 'vitest';
import { buildCards, dropCurrentBlocked, toCard, toolName, type TaskEnvelope } from './timeline';

const ev = (seq: number, type: string, x: Partial<TaskEnvelope> = {}): TaskEnvelope => ({ seq, taskId: 't1', type, at: `2026-10-24T01:00:${String(seq).padStart(2, '0')}Z`, nodeId: 'DEV', ...x });
describe('M4 timeline', () => {
  it('maps tool batches with duration / status and merges consecutive batches in a node', () => {
    const a = ev(1, 'AGENT_TOOLS', { data: { items: [{ name: 'Bash', args: 'npm test', ms: 1200, ok: false, out: 'FAIL x' }], failed: 1 } });
    const b = ev(2, 'AGENT_TOOLS', { data: { items: [{ name: 'Read', args: 'a.ts', ms: 3, ok: true, count: 4 }] } });
    const c = buildCards([a, b]); expect(c).toHaveLength(1); expect(c[0].items).toHaveLength(2);
    expect(c[0].items![0]).toMatchObject({ ok: false, ms: 1200 }); expect(c[0].items![0].text).toContain('FAIL x'); expect(c[0].items![1].title).toContain('×4');
    expect(toolName('mcp__playwright__browser_navigate')).toBe('浏览器·navigate');
  });
  it('browser self-test gets its own card', () => {
    expect(toCard(ev(3, 'AGENT_BROWSER_CHECK', { data: { items: [{ name: 'mcp__playwright__browser_take_screenshot', ok: true, browser: true }] } }))?.kind).toBe('agent.browser');
  });
  it('acceptance report card carries screenshots, verdict and per-check results', () => {
    const c = toCard(ev(4, 'EVIDENCE_ADDED', { evidence: { id: 'e', kind: 'ui_report', stage: 'UI_ACCEPTANCE', content: { jobId: 'j1', verdict: 'FAIL', blocked: false, observeOnly: true, summary: '缺学号列', screenshots: ['grades.png'], criteria: [{ criterion: '导出含学号', verdict: 'FAIL', reason: '表头没有学号' }], tests: [{ title: 'export works', status: 'passed' }], passed: 1, failed: 0 } } }))!;
    expect(c.kind).toBe('acceptance.result'); expect(c.shots).toEqual(['/api/agent-jobs/j1/screenshots/grades.png']);
    expect(c.checks).toEqual([{ name: '导出含学号', ok: false, reason: '表头没有学号' }, { name: '脚本：export works', ok: true, reason: undefined }]);
    expect(c.title).toContain('观察期');
  });
  it('drops the old blocked card of the current episode only when the new blocked card is shown', () => {
    const cards = buildCards([ev(1, 'STATUS_CHANGED', { from: 'DEV', to: 'QA', nodeId: 'QA' }), ev(2, 'STATUS_CHANGED', { from: 'QA', to: 'ANOMALY', nodeId: 'QA' }), ev(3, 'STATUS_CHANGED', { from: 'ANOMALY', to: 'QA', nodeId: 'QA' }), ev(4, 'STATUS_CHANGED', { from: 'QA', to: 'ANOMALY', nodeId: 'QA' })]);
    expect(cards.filter(c => c.kind === 'task.blocked')).toHaveLength(2);
    const kept = dropCurrentBlocked(cards, true).filter(c => c.kind === 'task.blocked'); expect(kept.map(c => c.seq)).toEqual([2]);
    expect(dropCurrentBlocked(cards, false)).toHaveLength(cards.length);
  });
});
