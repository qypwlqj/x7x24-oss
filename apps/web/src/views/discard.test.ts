import { describe, it, expect } from 'vitest';
import { discardLines, DISCARD_REASON } from './TaskDetail';

describe('discardLines', () => {
  it('shows failed hard rules, notes and clarification questions', () => {
    const l = discardLines({ failedRules: ['verifiable_criteria'], notes: '第 2 条无判定点', questions: ['第 2 条怎样算通过？'], after: '已修订 2 次仍未通过' });
    expect(l.join('\n')).toContain('verifiable_criteria');
    expect(l.join('\n')).toContain('第 2 条无判定点');
    expect(l.join('\n')).toContain('1. 第 2 条怎样算通过？');
    expect(DISCARD_REASON.needs_clarification).toContain('澄清');
  });
});
