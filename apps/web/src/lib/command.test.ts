import { describe, expect, it } from 'vitest';
import { GUIDES, chatLink, CAPABILITIES, capabilityState, capabilityText, examples, filterSessions, greeting, matchSlash } from './command';

const cap = (id: string) => CAPABILITIES.find(c => c.id === id)!;
describe('指挥台首屏', () => {
  it('按角色显示能力按钮：只读看不到会改数据的按钮，成员看到锁', () => {
    expect(capabilityState(cap('line'), 'viewer').hidden).toBe(true);
    expect(capabilityState(cap('q-stuck'), 'viewer')).toEqual({ hidden: false, locked: false });
    expect(capabilityState(cap('line'), 'member')).toMatchObject({ hidden: false, locked: true });
    expect(capabilityState(cap('invite-admin'), 'admin').reason).toBe('只有所有者可以邀请管理员');
    expect(capabilityState(cap('invite-admin'), 'owner').locked).toBe(false);
  });
  it('问候语按角色变化', () => {
    expect(greeting('alice', '教育', 'admin')).toContain('是管理员');
    expect(greeting('bob', '教育', 'member')).toContain('需要管理员确认');
    expect(greeting('eve', '教育', 'viewer')).toContain('只读成员');
  });
  it('示例和快捷指令只给角色用得上的', () => {
    expect(examples('viewer')).toHaveLength(1); expect(examples('owner')).toHaveLength(5);
    expect(matchSlash('/产品', 'member').map(s => s.label)).toEqual(['新建产品线']);
    expect(matchSlash('/', 'viewer').map(s => s.label)).toEqual(['查询进度', '能做什么']);
    expect(matchSlash('新建', 'owner')).toEqual([]);
  });
  it('新建项目带上当前产品线', () => { expect(capabilityText(cap('project'), '教育')).toContain('到产品线 教育'); });
  it('历史筛选', () => {
    const l = [{ title: '导出', task_id: 't', action_count: 0 }, { title: null, task_id: null, action_count: 2 }];
    expect(filterSessions(l, 'req')).toHaveLength(1); expect(filterSessions(l, 'ops')).toHaveLength(1); expect(filterSessions(l, 'all')).toHaveLength(2);
  });
});

import { actionView, objectHref } from './chat';
describe('操作卡状态与引导卡', () => {
  const card = (x: any) => ({ kind: 'create_project', params: { name: '官网' }, status: 'proposed', result: null, error: null, blockedRole: null, ...x });
  it('六种状态的文案', () => {
    expect(actionView(card({}), 'owner').label).toBe('待确认');
    expect(actionView(card({}), 'owner', true).label).toBe('执行中…');
    expect(actionView(card({}), 'member')).toMatchObject({ label: '等待有权限的人', tone: 'warn' });
    expect(actionView(card({ blockedRole: 'admin' }), 'admin').body).toContain('留给你处理');
    expect(actionView(card({ status: 'executed', result: { projectId: 'p1', name: '官网' } }), 'owner').object).toEqual({ kind: 'project', id: 'p1', name: '官网' });
    expect(objectHref({ kind: 'project', id: 'p1' }, 'chat_1')).toBe('/p/p1/detail?from=s%2Fchat_1');
    expect(actionView(card({ status: 'cancelled' }), 'owner').body).toBe('已取消，没有做任何改动。');
    expect(actionView(card({ status: 'failed', error: '产品线已归档' }), 'owner').body).toContain('没有完成：产品线已归档');
  });
  it('引导卡链接是固定表，对话链接指向新路由', () => {
    expect(GUIDES.budget.href).toBe('/budget'); expect(Object.values(GUIDES).every(g => g.href.startsWith('/'))).toBe(true);
    expect(chatLink({ origin: 'https://x', pathname: '/' }, 'chat_1')).toBe('https://x/s/chat_1');
  });
});
