import { describe, expect, it, vi } from 'vitest';
import { makeBinder } from './session';
import { contextLine, isNewSessionKey, navRedirect, intentText, newSessionHref, otherSessions, recentSessions, recentConversationEntries, anchorOf, sessionHref, anchorHref, sessionCount, fromSession, filterSessions, rowMenu, PRIMARY } from './nav';

describe('新对话自动绑定任务', () => {
  it('空项目从会话创建任务时保留产品线和项目上下文', () => {
    expect(newSessionHref('line 1', 'task', 'p/1')).toBe('/new?line=line+1&do=task&project=p%2F1');
    expect(intentText('task')).toBe('新建任务 ');
    expect(newSessionHref(undefined, 'project')).toBe('/new?do=project');
  });
  it('最近入口按任务合并，保留独立对话并汇总待确认卡片', () => {
    const sessions = [
      { id: 'old', task_id: 'T1', updated_at: '2026-10-29', pending_actions: 2 },
      { id: 'new', task_id: 'T1', updated_at: '2026-10-31', pending_actions: 1 },
      { id: 'standalone', task_id: null, updated_at: '2026-10-30' },
      { id: 'other', task_id: null, updated_at: '2026-10-28' },
    ];
    const entries = recentConversationEntries(sessions);
    expect(entries.map(s => s.id)).toEqual(['new', 'standalone', 'other']);
    expect(entries[0].pending_actions).toBe(3);
    expect(sessions[1].pending_actions).toBe(1);
  });
  it('未绑定：留在会话视图；建了任务后再读一次就跳到任务会话', async () => {
    let row: { id: string; task_id: string | null } = { id: 'c1', task_id: null };
    const replace = vi.fn(); const show = vi.fn(); const onBound = vi.fn();
    const bind = makeBinder({ fetch: async () => row, replace, show, onBound });
    expect(await bind('c1')).toBe(false); expect(show).toHaveBeenCalledWith(row); expect(replace).not.toHaveBeenCalled();
    row = { id: 'c1', task_id: 'T 9' }; // 这一轮助手建了任务
    expect(await bind('c1')).toBe(true); expect(replace).toHaveBeenCalledWith('t/T%209/chat'); expect(onBound).toHaveBeenCalledTimes(1); expect(show).toHaveBeenCalledTimes(1);
  });
  it('旧链接打开已绑定的会话：直接去任务', async () => {
    const replace = vi.fn(); await makeBinder({ fetch: async () => ({ id: 'c2', task_id: 'T-2' }), replace, show: vi.fn() })('c2');
    expect(replace).toHaveBeenCalledWith('t/T-2/chat');
  });
});

describe('取消指挥台菜单', () => {
  it('主菜单只保留 总览 / 待我处理', () => expect(PRIMARY.map(p => p.label)).toEqual(['总览', '待我处理']));
  it('旧路由：未绑定会话进会话视图（再由它判断是否跳任务），首页进新对话', () => {
    expect(navRedirect('/command')).toBe('/new'); expect(navRedirect('/command/chat_1')).toBe('/s/chat_1'); expect(navRedirect('/chat/chat_1')).toBe('/s/chat_1');
    expect(navRedirect('/command/t/T-1')).toBe('/t/T-1/chat');
  });
  it('新对话带上当前产品线', () => {
    expect(newSessionHref(null)).toBe('/new'); expect(newSessionHref('教 育')).toBe('/new?line=%E6%95%99+%E8%82%B2');
    const projects = [{ id: 'p1', productLineId: 'l1' }]; const tasks = [{ id: 'T-1', projectId: 'p1' }];
    expect(contextLine({ kind: 'line', id: 'l2' }, projects, tasks)).toBe('l2');
    expect(contextLine({ kind: 'project', id: 'p1' }, projects, tasks)).toBe('l1');
    expect(contextLine({ kind: 'task', id: 'T-1' }, projects, tasks)).toBe('l1');
    expect(contextLine(null, projects, tasks)).toBeNull();
    expect(rowMenu('line', 'l1', 'owner').find(a => a.id === 'new-project')?.href).toBe('/new?line=l1&do=project');
    expect(rowMenu('line', 'l1', 'owner').find(a => a.id === 'invite')?.href).toBe('/new?line=l1&do=invite');
    expect(intentText('project', '教育')).toBe('新建项目  到产品线 教育'); expect(intentText(null)).toBe('');
  });
  it('快捷键 Ctrl/Cmd+K', () => {
    const k = (o: Partial<KeyboardEvent>) => isNewSessionKey({ key: 'k', metaKey: false, ctrlKey: false, shiftKey: false, altKey: false, ...o } as any);
    expect(k({ ctrlKey: true })).toBe(true); expect(k({ metaKey: true, key: 'K' })).toBe(true); expect(k({})).toBe(false); expect(k({ ctrlKey: true, shiftKey: true })).toBe(false);
  });
  it('其他对话：只列没绑定任务的，最近的在前', () => {
    const l = [{ id: 'a', task_id: 'T', updated_at: '3' }, { id: 'b', task_id: null, updated_at: '1' }, { id: 'c', task_id: null, updated_at: '2' }];
    expect(otherSessions(l).map(x => x.id)).toEqual(['c', 'b']);
  });
  it('最近对话：全部会话按最近更新取前 10 条，含已归属的', () => {
    const l = Array.from({ length: 12 }, (_, i) => ({ id: 'c' + i, task_id: i === 3 ? 'T' : null, updated_at: String(i).padStart(2, '0') }));
    const r = recentSessions(l); expect(r).toHaveLength(10); expect(r[0].id).toBe('c11'); expect(r.map(x => x.id)).toContain('c3');
  });
  it('归属：链接、计数、返回对话、筛选', () => {
    const p = { id: 'chat_1', task_id: null, updated_at: '1', anchor_kind: 'project', anchor_id: 'p1', anchor_name: '官网', title: '建官网' };
    const t = { id: 'chat_2', task_id: 'T1', updated_at: '2' };
    expect(anchorOf(t)).toEqual({ kind: 'task', id: 'T1', name: null });
    expect(sessionHref(p)).toBe('/s/chat_1'); expect(sessionHref(t)).toBe('/t/T1/chat');
    expect(anchorHref(p)).toBe('/p/p1/chat'); expect(anchorHref({ id: 'x', task_id: null, updated_at: '0' })).toBeNull();
    expect(sessionCount([p, t, { ...p, id: 'chat_3' }], 'project', 'p1')).toBe(2);
    expect(fromSession('/p/p1/detail?from=s%2Fchat_1')).toBe('chat_1'); expect(fromSession('/p/p1/detail?from=evil')).toBeNull();
    expect(filterSessions([p, t], '官网', 'all').map(x => x.id)).toEqual(['chat_1']); expect(filterSessions([p, t], '', 'task').map(x => x.id)).toEqual(['chat_2']);
  });
});
