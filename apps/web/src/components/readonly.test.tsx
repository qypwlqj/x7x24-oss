import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import * as Popover from '@radix-ui/react-popover';
import { GovernanceSummary, ScopeBudget, TaskBudget } from './ScopeViews';
import { UserMenuItems } from './UserMenu';
import ObjectView, { ObjectHeader, type ObjectViewProps } from '../views/ObjectView';
import { TABS, headerActions, rowMenu } from '../lib/nav';

vi.mock('../views/TaskDetail', () => ({ default: ({ canManageBudget }: { canManageBudget: boolean }) => <div data-testid="detail-budget-permission" data-can-manage={String(canManageBudget)} /> }));
afterEach(() => vi.unstubAllGlobals());

const budget = { lines: [{ id: 'l1', name: '教育', daily: { usd: 9, tokens: 0, ci: 0 }, limits: { dailyUsd: 10 } }], projects: [{ id: 'p1', name: '官网', productLineId: 'l1', daily: { usd: 12, tokens: 0, ci: 0 }, limits: { dailyUsd: 10 } }] };
const task: any = { id: 'T-1', title: 'x', projectId: 'p1', spentUsd: 6, budgetUsd: 5, spentTokens: 0, budgetTokens: 0 };
const line: any = { id: 'l1', name: '教育', autonomy: 'L2', paused: false };
const EDIT = /data-edit="1"|调整额度|调整治理|调整 →|新任务|新建项目|暂停|继续/;

describe('只读成员在新页面里看不到编辑按钮', () => {
  it('预算 / 治理 页签', () => {
    for (const html of [
      renderToStaticMarkup(<ScopeBudget kind="line" id="l1" budget={budget as any} role="viewer" />),
      renderToStaticMarkup(<ScopeBudget kind="project" id="p1" budget={budget as any} role="viewer" tasks={[task]} />),
      renderToStaticMarkup(<TaskBudget task={task} role="viewer" admin={<button>ADMIN</button>} />),
      renderToStaticMarkup(<GovernanceSummary line={line} role="viewer" meta={null} editor={<button>EDITOR</button>} />),
    ]) { expect(html).not.toMatch(EDIT); expect(html).not.toContain('ADMIN'); expect(html).not.toContain('EDITOR'); expect(html).toContain('只读成员只能查看'); }
  });
  it('管理员能看到调整入口', () => {
    expect(renderToStaticMarkup(<ScopeBudget kind="project" id="p1" budget={budget as any} role="admin" />)).toContain('data-edit="1"');
    expect(renderToStaticMarkup(<GovernanceSummary line={line} role="admin" meta={null} editor={<button>EDITOR</button>} />)).toContain('EDITOR');
  });
  it.each(['owner', 'admin', 'member', 'viewer', undefined])('两个任务预算入口使用已加载的工作区角色：%s', role => {
    vi.stubGlobal('location', { pathname: '/t/T-1/budget', search: '' });
    const t = { ...task, status: 'DEV', budgetSwitches: { budgetOff: false, degradeOff: false, budgetFrom: 'governance', degradeFrom: 'governance', safetyMaxIterations: 150 } };
    const props = {
      // App temporarily falls back to owner while the workspace request is pending.
      role: 'owner', current: role ? { id: 'ws1', role } : null, meta: null,
      data: { tasks: [t], taskLookups: {}, reload: async () => {}, loadTask: async () => {} },
      go: () => {}, refresh: async () => {}, onCreateTask: () => {},
    } as unknown as Omit<ObjectViewProps, 'r'>;
    const allowed = role === 'owner' || role === 'admin';
    const budget = renderToStaticMarkup(<ObjectView {...props} r={{ kind: 'task', id: 'T-1', tab: 'budget', rest: [] }} />);
    const switches = budget.match(/<button\b[^>]*role="switch"[^>]*>/g) ?? [];
    expect(switches).toHaveLength(2);
    expect(switches.filter(button => !/\sdisabled(?:\s|=|>)/.test(button))).toHaveLength(allowed ? 2 : 0);
    const detail = renderToStaticMarkup(<ObjectView {...props} r={{ kind: 'task', id: 'T-1', tab: 'detail', rest: [] }} />);
    expect(detail).toContain(`data-can-manage="${allowed}"`);
  });
  it('对象页头部与用户菜单', () => {
    for (const k of ['line', 'project', 'task'] as const) {
      const html = renderToStaticMarkup(<ObjectHeader crumbs={[{ label: 'a' }]} title="t" actions={headerActions(k, 'viewer').map(a => ({ a, run: () => {} }))} menu={rowMenu(k, 'x', 'viewer')} tabs={TABS[k]} active={TABS[k][0].id} base="/x" />);
      expect(html).not.toContain('data-edit="1"');
    }
    const menu = renderToStaticMarkup(<Popover.Root open><UserMenuItems role="viewer" onLogout={() => {}} /></Popover.Root>);
    expect(menu).not.toContain('data-edit="1"'); expect(menu).not.toContain('集成'); expect(menu).toContain('成员列表（只读）');
    expect(renderToStaticMarkup(<Popover.Root open><UserMenuItems role="admin" onLogout={() => {}} /></Popover.Root>)).toContain('集成');
  });
});
