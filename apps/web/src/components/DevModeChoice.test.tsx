import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { DevModeChoice } from './DevModeChoice';
import { BlockedCard } from './FlowControls';
import { describeEvent } from '../lib/eventText';
import { causeText, paramLines, type ActionState } from '../lib/taskActions';

const act: any = { propose: async () => ({}), confirm: async () => ({}), cancel: async () => {}, say: async () => ({}), reload: () => {}, startSession: async () => '' };

describe('dev mode', () => {
  it('project choice recommends the sandbox and warns about quick-mode limits only when chosen', () => {
    const sandbox = renderToStaticMarkup(<DevModeChoice name="p" value="sandbox" onChange={() => {}} />);
    expect(sandbox).toContain('沙盒'); expect(sandbox).toContain('推荐'); expect(sandbox).toContain('最多参考 10 个文件'); expect(sandbox).not.toContain('data-testid="p-legacy-note"');
    const legacy = renderToStaticMarkup(<DevModeChoice name="p" value="legacy" onChange={() => {}} />);
    expect(legacy).toContain('data-testid="p-legacy-note"'); expect(legacy).toContain('不在本地跑构建和测试');
  });
  it('task choice can follow the project', () => {
    const html = renderToStaticMarkup(<DevModeChoice name="t" value="inherit" inheritLabel="用项目设置（现在是「沙盒」）。" onChange={() => {}} />);
    expect(html).toContain('跟随项目'); expect(html).toContain('现在是「沙盒」');
  });
  it('timeline labels each round and hints when a quick-mode task looks too big', () => {
    expect(describeEvent({ event_type: 'DEV_MODE', payload: { mode: 'sandbox', label: '本轮：沙盒', source: 'default' } }).title).toBe('本轮：沙盒');
    const big = describeEvent({ event_type: 'DEV_MODE', payload: { mode: 'legacy', label: '本轮：快速方式', source: 'project', hint: '这个需求可能超出快速方式能力，建议改用沙盒' } });
    expect(big.title).toBe('本轮：快速方式'); expect(big.tone).toBe('warn'); expect(big.text).toContain('建议改用沙盒');
    expect(describeEvent({ event_type: 'SANDBOX_RETRY', payload: { attempt: 1, of: 2, waitMin: 1, reason: 'x' } }).title).toContain('1 分钟后自动重试（第 1/2 次）');
    expect(describeEvent({ event_type: 'TASK_DEV_MODE', payload: { before: 'inherit', after: 'legacy' } }).text).toContain('跟随项目 → 快速方式');
  });
  it('blocked card offers retry in sandbox, switch to quick mode, or give up', () => {
    const s: ActionState = { blocked: true, status: 'DEV', node: 'DEV', paused: false, cause: 'sandbox_failed', detail: '沙盒里的开发 agent 用完了对话轮数上限还没做完，这次的半成品没有采用。', evidence: [],
      suggestions: [
        { kind: 'task_resume', title: '再试一次（沙盒）', why: '', params: {}, recommended: true, danger: 'low', minRole: 'member' },
        { kind: 'dev_mode_retry', title: '本任务改用快速方式重试', why: '', params: { devMode: 'legacy' }, recommended: false, danger: 'mid', minRole: 'member' },
        { kind: 'task_abandon', title: '放弃', why: '', params: { reason: 'x' }, recommended: false, danger: 'high', minRole: 'admin' }],
      pending: [], history: [], can: { act: true, admin: false }, sessionId: null, lockKey: 'k' };
    const html = renderToStaticMarkup(<BlockedCard s={s} act={act} onProposed={() => {}} />);
    for (const t of ['沙盒开发没成功', '再试一次（沙盒）', '本任务改用快速方式重试', '放弃', '半成品没有采用']) expect(html).toContain(t);
    expect(causeText('sandbox_failed')).toBe('沙盒开发没成功');
    expect(paramLines({ kind: 'dev_mode_retry', params: { devMode: 'legacy' } }).join('\n')).toContain('最多参考 10 个文件');
  });
});
