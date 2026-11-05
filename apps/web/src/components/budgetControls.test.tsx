import { Children, isValidElement, type ReactElement, type ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { TaskDTO } from '../api';
import { canManage } from '../lib/workspace';
import { TaskBudgetCard } from './TaskBudgetCard';
import { TaskSwitchDefaults } from './TaskSwitchDefaults';

// Exercise the real save handlers and API response parsing without adding a DOM dependency.
const hooks = vi.hoisted(() => ({ active: false, cursor: 0, values: [] as unknown[] }));
vi.mock('react', async importOriginal => {
  const actual = await importOriginal<typeof import('react')>();
  return { ...actual, useState: (initial: unknown) => {
    if (!hooks.active) return actual.useState(initial);
    const index = hooks.cursor++;
    if (!(index in hooks.values)) hooks.values[index] = typeof initial === 'function' ? initial() : initial;
    return [hooks.values[index], (next: unknown) => { hooks.values[index] = typeof next === 'function' ? next(hooks.values[index]) : next; }];
  } };
});

const task = {
  id: 'T-budget', status: 'DEV', spentUsd: 0, budgetUsd: 3, spentTokens: 0, budgetTokens: 1000,
  budgetSwitches: { budgetOff: false, degradeOff: false, budgetFrom: 'task', degradeFrom: 'task', safetyMaxIterations: 150 },
} as TaskDTO;
const refreshed = vi.fn(async () => {});
type Control = { label?: string; children?: ReactNode; disabled?: boolean; onChange?: (value: boolean) => Promise<void>; onClick?: () => Promise<void> };
function findControl(tree: ReactNode, label: string): ReactElement<Control> {
  let found: ReactElement<Control> | undefined;
  const visit = (node: ReactNode) => Children.forEach(node, child => {
    if (!isValidElement<Control>(child)) return;
    if (child.props.label === label || child.props.children === label) found = child;
    visit(child.props.children);
  });
  visit(tree);
  if (!found) throw new Error(`Missing control: ${label}`);
  return found;
}
beforeEach(() => { hooks.active = false; hooks.cursor = 0; hooks.values = []; refreshed.mockClear(); });
afterEach(() => { hooks.active = false; vi.unstubAllGlobals(); });

describe('task budget permissions', () => {
  it.each(['owner', 'admin', 'member', 'viewer', undefined])('only enables editing for known managers: %s', role => {
    const html = renderToStaticMarkup(<TaskBudgetCard t={task} canManageBudget={canManage(role)} onChanged={refreshed} />);
    const switches = html.match(/<button\b[^>]*role="switch"[^>]*>/g) ?? [];
    expect(switches).toHaveLength(2);
    const enabled = switches.filter(button => !/\sdisabled(?:\s|=|>)/.test(button));
    expect(enabled).toHaveLength(canManage(role) ? 2 : 0);
    const restore = html.match(/<button\b[^>]*>恢复为项目默认<\/button>/)?.[0];
    expect(restore).toBeDefined();
    expect(/\sdisabled(?:\s|=|>)/.test(restore!)).toBe(!canManage(role));
  });
  it.each([false, undefined])('fails closed even when the permission prop is missing: %s', permission => {
    hooks.active = true;
    const tree = TaskBudgetCard({ t: task, canManageBudget: permission as boolean, onChanged: refreshed });
    expect(findControl(tree, '不限预算').props.disabled).toBe(true);
  });
  it.each(['RELEASED', 'DISCARDED'])('keeps ended tasks disabled: %s', status => {
    const html = renderToStaticMarkup(<TaskBudgetCard t={{ ...task, status } as TaskDTO} canManageBudget onChanged={refreshed} />);
    const switches = html.match(/<button\b[^>]*role="switch"[^>]*>/g) ?? [];
    expect(switches).toHaveLength(2);
    expect(switches.every(button => /\sdisabled(?:\s|=|>)/.test(button))).toBe(true);
  });
  it('does not submit either a toggle or restore when editing is disallowed', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); hooks.active = true;
    const tree = TaskBudgetCard({ t: task, canManageBudget: false, onChanged: refreshed });
    await findControl(tree, '不限预算').props.onChange!(true);
    await findControl(tree, '恢复为项目默认').props.onClick!();
    expect(fetch).not.toHaveBeenCalled(); expect(refreshed).not.toHaveBeenCalled();
  });
});

describe.each(['task', 'defaults'] as const)('%s budget save feedback', kind => {
  const render = () => {
    hooks.active = true; hooks.cursor = 0;
    return kind === 'task'
      ? TaskBudgetCard({ t: task, canManageBudget: true, onChanged: refreshed })
      : TaskSwitchDefaults({ cfg: {}, meta: null, onSaved: refreshed });
  };
  const change = (tree: ReactNode) => findControl(tree, kind === 'task' ? '不限预算' : '默认不限预算').props.onChange!(true);
  it.each(['forbidden', 'read_only'])('explains a structured %s denial', async code => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: code, message: '禁止操作' }), { status: 403 })));
    await change(render());
    expect(hooks.values[1]).toBe('只有工作区所有者或管理员可以修改预算设置。');
    expect(refreshed).not.toHaveBeenCalled();
  });
  it('does not turn a non-JSON CORS or gateway 403 into a permission error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('Invalid CORS request', { status: 403 })));
    await change(render());
    expect(hooks.values[1]).toBe('请求失败（403），请稍后重试');
  });
  it('preserves other structured 403 explanations', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: 'policy_denied', message: '安全策略拦截，请联系管理员查看详情' }), { status: 403 })));
    await change(render());
    expect(hooks.values[1]).toBe('安全策略拦截，请联系管理员查看详情');
  });
  it('preserves network errors', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    await change(render()); expect(hooks.values[1]).toBe('Failed to fetch');
  });
  it('submits the intended scope and refreshes after a successful save', async () => {
    const fetch = vi.fn(async () => new Response('{}', { status: 200 })); vi.stubGlobal('fetch', fetch);
    await change(render());
    expect(fetch).toHaveBeenCalledWith(kind === 'task' ? '/api/tasks/T-budget/budget-switches' : '/api/budget/task-switches', expect.objectContaining({
      method: kind === 'task' ? 'PATCH' : 'PUT',
      body: JSON.stringify(kind === 'task' ? { budgetOff: true } : { scope: 'governance', unlimited: true }),
    }));
    expect(refreshed).toHaveBeenCalledOnce(); expect(hooks.values[1]).toBe('');
  });
});
