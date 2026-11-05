import { describe, expect, it } from 'vitest';
import { levelOf, parseNum, previewImpact, validateLimits, validateTask } from './budget';

describe('budget validation', () => {
  it('rejects non-positive numbers', () => { expect(validateLimits('global', { dailyUsd: 0 })[0]).toContain('日金额'); expect(validateLimits('global', { dailyTokens: -1 })).toHaveLength(1); });
  it('monthly must be >= daily (global)', () => { expect(validateLimits('global', { dailyUsd: 100, monthlyUsd: 50 })[0]).toContain('月金额'); expect(validateLimits('global', { dailyUsd: 10, monthlyUsd: 50 })).toEqual([]); });
  it('child cannot exceed parent', () => {
    expect(validateLimits('line', { dailyUsd: 80 }, { dailyUsd: 50 })[0]).toContain('不能超过全局上限');
    expect(validateLimits('project', { dailyCiMinutes: 300 }, { dailyCiMinutes: 200 })[0]).toContain('所属产品线');
  });
  it('parent cannot drop below children overrides; null (inherit) is ignored', () => {
    expect(validateLimits('line', { dailyUsd: 10 }, undefined, [{ name: 'P1', limits: { dailyUsd: 20 } }])[0]).toContain('P1');
    expect(validateLimits('line', { dailyUsd: 10 }, undefined, [{ name: 'P1', limits: { dailyUsd: null } }])).toEqual([]);
  });
  it('task limits cannot exceed global daily', () => { expect(validateTask({ usd: 99 }, { dailyUsd: 50 })[0]).toContain('单任务金额'); expect(validateTask({ usd: 3 }, { dailyUsd: 50 })).toEqual([]); });
  it('impact preview text', () => {
    expect(previewImpact({ usd: 40, tokens: 0, ci: 0 }, { dailyUsd: 50 }, 0.8).level).toBe('soft');
    expect(previewImpact({ usd: 40, tokens: 0, ci: 0 }, { dailyUsd: 40 }, 0.8)).toMatchObject({ level: 'hard', pct: 100 });
    expect(previewImpact({ usd: 10, tokens: 0, ci: 0 }, { dailyUsd: 50 }, 0.8).text).toContain('保持正常');
    expect(previewImpact({ usd: 40, tokens: 0, ci: 0 }, { dailyUsd: 50 }, 0.8).text).toContain('80%');
  });
  it('levelOf and parseNum', () => { expect(levelOf(0.5, 0.8)).toBe('ok'); expect(levelOf(0.8, 0.8)).toBe('soft'); expect(levelOf(1, 0.8)).toBe('hard'); expect(parseNum('')).toBeNull(); expect(parseNum(' 12.5 ')).toBe(12.5); expect(parseNum('abc')).toBeUndefined(); });
});
