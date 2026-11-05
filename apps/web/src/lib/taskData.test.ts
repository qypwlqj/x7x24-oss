import { describe, expect, it } from 'vitest';
import { ciEvidenceCurrent, ciState, type Evidence, type TaskDetailData } from './taskData';

const evidence = (content: Record<string, unknown>, kind = 'test_report') => ({ id: 'test', stage: 'QA', iteration: 1, createdAt: '', content, kind } as Evidence);

describe('test evidence', () => {
  it('requires an explicit passing result and refuses contradictory or empty reports', () => {
    expect(ciState(evidence({}))).toBe('fail');
    expect(ciState(evidence({ passed: true, jobs: 0 }))).toBe('fail');
    expect(ciState(evidence({ state: 'passed', passed: false }))).toBe('fail');
    expect(ciState(evidence({ passed: true, jobs: 2 }))).toBe('pass');
  });

  it('only treats CI results for the active code version and phase as current', () => {
    const e = evidence({ headSha: 'old', state: 'passed' }, 'ci_log');
    const d = { task: { status: 'ACCEPTANCE' }, context: { pr: { headSha: 'old' }, ci: { headSha: 'old', state: 'passed' } } } as TaskDetailData;
    expect(ciEvidenceCurrent(e, d)).toBe(true);
    expect(ciEvidenceCurrent(e, { ...d, task: { status: 'DEV' } })).toBe(false);
    expect(ciEvidenceCurrent(e, { ...d, context: { pr: { headSha: 'new' }, ci: { headSha: 'new', state: 'pending' } } })).toBe(false);
    expect(ciEvidenceCurrent(e, null)).toBe(false);
  });
});
