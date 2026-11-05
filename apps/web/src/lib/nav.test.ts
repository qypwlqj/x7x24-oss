import { describe, expect, it } from 'vitest';
import { budgetLevel, headerActions, navRedirect, objRoute, rowBadges, rowMenu, userMenu } from './nav';
import { parseRoute } from '../router';

describe('旧路由重定向', () => {
  const cases: [string, string | null][] = [
    ['/chat', '/new'], ['/chat/c1', '/s/c1'], ['/command', '/new'], ['/command?line=l1', '/new?line=l1'], ['/command/c1', '/s/c1'],
    ['/command/t/T-1', '/t/T-1/chat'], ['/command/t/T-1/node/QA', '/t/T-1/node/QA'], ['/new', null], ['/s/c1', null],
    ['/task/T-1', '/t/T-1/detail'], ['/task/T-1?tab=evidence&focus=DEV', '/t/T-1/detail?tab=evidence&focus=DEV'], ['/task/T-1/verdicts', '/t/T-1/detail?tab=verdicts'],
    ['/project/p1', '/p/p1/tasks'], ['/catalog', '/admin/catalog'], ['/catalog/l1', '/l/l1/overview'],
    ['/integrations', '/admin/integrations'], ['/integrations/status', '/admin/integrations/status'], ['/adapters', '/admin/integrations'],
    ['/governance', '/admin/governance'], ['/budget', '/admin/budget'], ['/models', '/admin/models'], ['/audit', '/admin/audit'],
    ['/workspace', '/admin/members'], ['/settings', '/me/settings'], ['/settings/notifications', '/me/settings'], ['/notifications', '/me/notifications'],
    ['/overview', null], ['/tasks', null], ['/platform', null], ['/t/T-1/chat', null], ['/inbox', null],
  ];
  it.each(cases)('%s → %s', (from, to) => expect(navRedirect(from)).toBe(to));
  it('新路由解析', () => {
    expect(objRoute(parseRoute('/t/T-1'))).toEqual({ kind: 'task', id: 'T-1', tab: 'chat', rest: [] });
    expect(objRoute(parseRoute('/p/p1/budget'))).toMatchObject({ kind: 'project', tab: 'budget' });
    expect(objRoute(parseRoute('/t/T-1/node/QA'))).toMatchObject({ tab: 'node', rest: ['QA'] });
    expect(parseRoute('/t/T-1/detail?tab=evidence&focus=DEV')).toEqual(['t', 'T-1', 'detail', 'evidence', 'DEV']);
  });
});

describe('只读成员看不到任何编辑入口', () => {
  it('offers recovery instead of pause for blocked tasks and no actions for completed tasks', () => {
    const blocked = { paused: false, status: 'ANOMALY', executionState: 'BLOCKED' };
    expect(headerActions('task', 'owner', blocked).map(a => a.id)).toEqual(['resume']);
    expect(rowMenu('task', 't', 'member', blocked).some(a => a.id === 'pause')).toBe(false);
    expect(headerActions('task', 'owner', { status: 'RELEASED' })).toEqual([]);
  });
  it('用户菜单没有管理项', () => {
    const m = userMenu('viewer'); expect(m.some(x => x.edit)).toBe(false); expect(m.map(x => x.id)).toEqual(['settings', 'members']);
    expect(userMenu('member').some(x => x.edit)).toBe(false);
    expect(userMenu('admin').filter(x => x.edit).length).toBeGreaterThan(4); expect(userMenu('admin').some(x => x.id === 'platform')).toBe(false);
    expect(userMenu('owner', true).some(x => x.id === 'platform')).toBe(true);
  });
  it('行 ⋯ 菜单和头部按钮', () => {
    for (const k of ['line', 'project', 'task'] as const) { expect(rowMenu(k, 'x', 'viewer').some(a => a.edit)).toBe(false); expect(headerActions(k, 'viewer')).toEqual([]); }
    expect(rowMenu('line', 'l', 'member').find(a => a.id === 'new-project')!.label).toContain('需管理员确认');
    expect(rowMenu('task', 't', 'member', { paused: true }).some(a => a.id === 'resume')).toBe(true);
    expect(headerActions('line', 'member')).toEqual([]); expect(headerActions('line', 'admin').length).toBe(1);
  });
});

describe('行图标', () => {
  it('预算等级', () => { expect(budgetLevel(1, 0)).toBe('none'); expect(budgetLevel(7, 10)).toBe('ok'); expect(budgetLevel(8, 10)).toBe('warn'); expect(budgetLevel(10, 10)).toBe('over'); });
  it('最多 2 个，阻塞优先，正常时显示状态点', () => {
    expect(rowBadges({ blocked: 1, waiting: 2, budget: { level: 'over', pct: 120 } }).map(b => b.kind)).toEqual(['blocked', 'waiting']);
    expect(rowBadges({ budget: { level: 'warn', pct: 88 }, dot: 'running' }).map(b => b.kind)).toEqual(['budget']);
    expect(rowBadges({ budget: { level: 'ok', pct: 10 }, dot: 'running' })).toEqual([{ kind: 'dot', dot: 'running' }]);
  });
});
