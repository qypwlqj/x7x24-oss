import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { BlockedCard, ConfirmCard, Composer } from './FlowControls';
import { paramLines, type ActionState, type TaskAction } from '../lib/taskActions';

const act: any = { propose: async () => ({}), confirm: async () => ({}), cancel: async () => {}, say: async () => ({}), reload: () => {}, startSession: async () => '' };
const st = (can: { act: boolean; admin: boolean }): ActionState => ({ blocked: true, status: 'ANOMALY', node: 'QA', paused: false, cause: 'ci_failed', detail: 'CI 连续失败', evidence: ['FAIL export.test.ts'], suggestions: [{ kind: 'node_retry', title: '带提示重试', why: 'x', params: {}, recommended: true, danger: 'low', minRole: 'member' }, { kind: 'task_abandon', title: '放弃', why: '可以恢复', params: {}, recommended: false, danger: 'high', minRole: 'admin' }], pending: [], history: [], can, sessionId: null, lockKey: 'k' });
const A = (o: Partial<TaskAction>): TaskAction => ({ id: 'a', taskId: 't', kind: 'task_abandon', label: '放弃任务', params: { reason: '不做了' }, danger: 'high', minRole: 'admin', confirmWord: '放弃', status: 'proposed', proposedBy: 'mia', source: 'button', createdAt: '', ...o });

describe('flow controls', () => {
  it('explains renewed repair quotas consistently on the blocked and confirmation cards', () => {
    const rework = { node: 'CODE_REVIEW', limit: 2, qaLimit: 3, used: 3, total: 6, round: 1 };
    const state = { ...st({ act: true, admin: false }), cause: 'same_failure', recovery: { canResume: true, state: 'OPEN', checkpoint: { phase: 'ACCEPTANCE' }, rework } };
    const html = renderToStaticMarkup(<BlockedCard s={state} act={act} onProposed={() => {}} />);
    expect(html).toContain('相同错误连续出现'); expect(html).not.toContain('same_failure');
    expect(html).toContain('重新获得最多 2 次返修机会'); expect(html).toContain('测试返修额度同时恢复为 3 次');
    expect(html).toContain('本轮额度已用：2 / 2'); expect(html).toContain('历史累计计数：6');
    expect(html).toContain('历史记录和花费保留');
    for (const kind of ['task_resume', 'node_retry'] as const) {
      const a = A({ kind, params: { rework, hint: '先读失败用例' } });
      expect(paramLines(a).join(' ')).toContain('重新获得最多 2 次返修机会');
      const confirmation = renderToStaticMarkup(<ConfirmCard a={a} act={act} can={{ act: true, admin: false }} onDone={() => {}} />);
      expect(confirmation).toContain('基于已有代码继续修复，再运行检查');
    }
    const restricted = renderToStaticMarkup(<BlockedCard s={{ ...state, resumeBlocker: { code: 'budget_paused', detail: '预算不足' }, suggestions: [] }} act={act} onProposed={() => {}} />);
    expect(restricted).not.toContain('data-testid="rework-resume"');
  });
  it('prioritizes the live budget restriction and hides retry and resume shortcuts', () => {
    const state = {
      ...st({ act: true, admin: true }), cause: 'timeout', status: 'DEV', node: 'DEV', suggestions: [],
      resumeBlocker: { code: 'budget_paused', detail: '单任务 Token 已用尽：2,138,927 / 2,084,668。' },
      guide: { label: '前往预算设置', href: '/t/task-1/budget', recommended: true },
    };
    const html = renderToStaticMarkup(<BlockedCard s={state} act={act} onProposed={() => {}} />);
    expect(html).toContain('预算用完了 · 停在「DEV」'); expect(html).toContain('2,138,927 / 2,084,668');
    expect(html).toContain('本次停止原因：长时间没有进展');
    expect(html).toContain('data-testid="budget-guide"'); expect(html).toContain('href="/t/task-1/budget"');
    expect(html).toContain('推荐'); expect(html).not.toContain('suggestion-btn');
    const shortcuts = renderToStaticMarkup(<Composer s={state} act={act} onSaid={() => {}} />);
    expect(shortcuts).not.toContain('>重试当前节点</button>'); expect(shortcuts).not.toContain('>恢复执行</button>');
  });
  it('keeps past failures collapsed and does not treat a historical budget failure as a live restriction', () => {
    const state = {
      ...st({ act: true, admin: true }), cause: 'timeout', detail: 'DEV 超过 8h 未完成', resumeBlocker: null,
      failureHistory: [{ code: 'budget', stage: 'DEV', detail: '历史预算不足详情', at: '2026-10-28T00:00:00Z' }],
    };
    const html = renderToStaticMarkup(<BlockedCard s={state} act={act} onProposed={() => {}} />);
    expect(html).toContain('长时间没有进展 · 停在'); expect(html).toContain('历史失败记录');
    expect(html).toContain('aria-expanded="false"'); expect(html).not.toContain('历史预算不足详情');
    expect(html).not.toContain('budget-guide'); expect(html).toContain('suggestion-btn');
  });
  it('shows that a recorded budget pause has cleared and offers resume again', () => {
    const state = { ...st({ act: true, admin: true }), cause: 'budget_paused', resumeBlocker: null, suggestions: [{ kind: 'task_resume' as const, title: '恢复原节点', why: '当前预算限制已解除', params: {}, recommended: true, danger: 'low', minRole: 'member' }] };
    const html = renderToStaticMarkup(<BlockedCard s={state} act={act} onProposed={() => {}} />);
    expect(html).toContain('预算限制已解除 · 停在'); expect(html).not.toContain('budget-guide');
    expect(html).toContain('恢复原节点');
    expect(renderToStaticMarkup(<Composer s={state} act={act} onSaid={() => {}} />)).toContain('>恢复执行</button>');
  });
  it('offers a budget viewing link to read-only members without offering execution', () => {
    const state = { ...st({ act: false, admin: false }), suggestions: [], resumeBlocker: { code: 'safety_cap', detail: '开发轮次达到安全上限。' }, guide: { label: '查看预算与安全上限', href: '/t/task-1/budget', recommended: true } };
    const html = renderToStaticMarkup(<BlockedCard s={state} act={act} onProposed={() => {}} />);
    expect(html).toContain('达到安全上限 · 停在'); expect(html).toContain('href="/t/task-1/budget"');
    expect(html).toContain('调整需要工作区管理员'); expect(html).not.toContain('suggestion-btn');
  });
  it('does not offer pause while blocked', () => {
    const h = renderToStaticMarkup(<Composer s={st({ act: true, admin: false })} act={act} onSaid={() => {}} />);
    expect(h).not.toContain('>暂停</button>'); expect(h).toContain('>恢复执行</button>');
  });
  it('offers restore only for discarded tasks explicitly marked restorable by the server', () => {
    const discarded = { ...st({ act: true, admin: true }), blocked: false, status: 'DISCARDED' };
    const render = (s: ActionState) => renderToStaticMarkup(<Composer s={s} act={act} onSaid={() => {}} />);
    expect(render({ ...discarded, canRestore: true })).toContain('>恢复原进度</button>');
    for (const canRestore of [false, undefined]) {
      expect(render({ ...discarded, canRestore })).not.toContain('>恢复原进度</button>');
    }
    for (const status of ['DEV', 'RELEASED', 'DELIVERED', 'ANOMALY']) {
      expect(render({ ...discarded, status, canRestore: true })).not.toContain('>恢复原进度</button>');
    }
    expect(render({ ...discarded, canRestore: true, can: { act: false, admin: false } })).not.toContain('>恢复原进度</button>');
  });
  it('shows the saved segment while a task stays at its real node', () => {
    const state = { ...st({ act: true, admin: true }), status: 'DEV', node: 'DEV', recovery: { canResume: true, state: 'OPEN', checkpoint: { phase: 'DEV', stage: 'DEV', segmentIndex: 10 } } };
    const html = renderToStaticMarkup(<BlockedCard s={state} act={act} onProposed={() => {}} />);
    expect(html).toContain('进度已保存'); expect(html).toContain('当前第 11 段'); expect(html).toContain('保留已有计划和提交');
    const actions = renderToStaticMarkup(<Composer s={state} act={act} onSaid={() => {}} />);
    expect(actions).toContain('恢复执行'); expect(actions).not.toContain('>暂停</button>');
  });
  it('blocked card shows cause, evidence and 1–3 suggestion buttons; read-only sees none', () => {
    const h = renderToStaticMarkup(<BlockedCard s={st({ act: true, admin: false })} act={act} onProposed={() => {}} />);
    expect(h).toContain('测试没通过'); expect(h).toContain('FAIL export.test.ts'); expect(h.match(/data-testid="suggestion-btn"/g)).toHaveLength(2); expect(h).toContain('提交给管理员');
    const ro = renderToStaticMarkup(<BlockedCard s={st({ act: false, admin: false })} act={act} onProposed={() => {}} />);
    expect(ro).not.toContain('suggestion-btn'); expect(ro).toContain('只读成员');
  });
  it('high-risk confirm needs the typed word; members see admin hand-off', () => {
    const h = renderToStaticMarkup(<ConfirmCard a={A({})} act={act} can={{ act: true, admin: true }} onDone={() => {}} />);
    expect(h).toContain('confirm-word'); expect(h).toMatch(/data-testid="confirm-btn"[^>]*disabled|disabled[^>]*data-testid="confirm-btn"/);
    const m = renderToStaticMarkup(<ConfirmCard a={A({})} act={act} can={{ act: true, admin: false }} onDone={() => {}} />);
    expect(m).toContain('needs-admin'); expect(m).not.toContain('confirm-btn');
  });
  it('composer hides quick actions for read-only', () => {
    expect(renderToStaticMarkup(<Composer s={st({ act: false, admin: false })} act={act} onSaid={() => {}} />)).not.toContain('quick-actions');
    expect(paramLines(A({}))).toContain('放弃后任务保留在「已放弃」，可以恢复');
  });
  it('previews the repository, branch and exact README content before administrator confirmation', () => {
    const a = A({ kind: 'repository_initialize_and_resume', label: '初始化仓库并继续', danger: 'mid', confirmWord: null, params: { repo: 'owner/repo', branch: 'main', path: 'README.md', message: 'chore: initialize repository', content: '# repo\n\nInitialized by x7x24.\n' } });
    const h = renderToStaticMarkup(<ConfirmCard a={a} act={act} can={{ act: true, admin: true }} onDone={() => {}} />);
    for (const text of ['owner/repo', 'main', 'README.md', 'chore: initialize repository', '# repo', 'Initialized by x7x24.', '保留已有规划']) expect(h).toContain(text);
    expect(h).toContain('confirm-btn');
    const member = renderToStaticMarkup(<ConfirmCard a={a} act={act} can={{ act: true, admin: false }} onDone={() => {}} />);
    expect(member).toContain('needs-admin'); expect(member).not.toContain('confirm-btn');
  });
});
