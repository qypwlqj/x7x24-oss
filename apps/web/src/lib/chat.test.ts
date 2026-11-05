import { describe, expect, it } from 'vitest';
import { actionAllowed, actionFields, applyReasoningEvent, canDeleteChat, deleteChatCopy, lines, mergeActions, nextAfterDelete, parseSse, type ChatAction, type ChatBubble } from './chat';

describe('思考内容流', () => {
  const initial: ChatBubble[] = [{ key: 'u', role: 'user', text: '问题' }, { key: 'a', role: 'assistant', text: '', pending: true }];
  const apply = (b: ChatBubble[], type: Parameters<typeof applyReasoningEvent>[2]['type'], data: any = {}) => applyReasoningEvent(b, 'a', { type, data });
  it('逐段合并，在回答前展开，思考结束立即收起', () => {
    const events = parseSse('event:reasoning\ndata:{"id":"s1","text":"第一段"}\n\nevent:reasoning\ndata:{"id":"s1","text":"第二段"}\n\n').events;
    let b = events.reduce((items, e) => applyReasoningEvent(items, 'a', e), initial);
    expect(b.map(x => x.role)).toEqual(['user', 'reasoning', 'assistant']);
    expect(b[1]).toMatchObject({ text: '第一段第二段', pending: true });
    b = apply(b, 'reasoning_done', { id: 's1' });
    expect(b[1]).toMatchObject({ text: '第一段第二段', pending: false });
    expect(b[2].pending).toBe(true);
  });
  it('多步骤分别保留，重试仅删除当前尝试', () => {
    let b = apply(initial, 'reasoning', { id: 'step1', text: '检查仓库' });
    b = apply(b, 'reasoning_done', { id: 'step1' });
    b.splice(2, 0, { key: 'tool', role: 'tool', text: '仓库内容' });
    b = apply(b, 'reasoning', { id: 'bad', text: '失败尝试' });
    b = apply(b, 'reset', { reasoningId: 'bad' });
    b = apply(b, 'reasoning', { id: 'retry', text: '重新检查' });
    expect(b.map(x => x.text)).toEqual(['问题', '检查仓库', '仓库内容', '重新检查', '']);
    expect(apply(b, 'reset')).toEqual(b);
  });
  it.each(['done', 'stopped', 'error'] as const)('%s 后保留并收起仅有思考的输出', type => {
    const b = apply(initial, 'reasoning', { id: 's', text: '尚未输出回答' });
    expect(apply(b, type)[1]).toMatchObject({ text: '尚未输出回答', pending: false });
  });
});

describe('chat SSE parser', () => {
  it('parses complete events and keeps the remainder', () => {
    const r = parseSse('event:tool\ndata:{"name":"repo_tree"}\n\nevent:delta\ndata:{"text":"你好"}\n\nevent:del');
    expect(r.events).toEqual([{ type: 'tool', data: { name: 'repo_tree' } }, { type: 'delta', data: { text: '你好' } }]);
    expect(r.rest).toBe('event:del');
  });
  it('handles CRLF and multi-line data', () => {
    const r = parseSse('event:done\r\ndata:{"a":\r\ndata:1}\r\n\r\n');
    expect(r.events[0]).toEqual({ type: 'done', data: { a: 1 } });
  });
  it('splits textarea lines', () => { expect(lines(' a \n\n b')).toEqual(['a', 'b']); });
});

describe('delete chat session', () => {
  it('only creator or manager can delete', () => {
    expect(canDeleteChat({ created_by: 'alice' }, 'alice', false)).toBe(true);
    expect(canDeleteChat({ created_by: 'alice' }, 'bob', false)).toBe(false);
    expect(canDeleteChat({ created_by: 'alice' }, 'bob', true)).toBe(true);
    expect(canDeleteChat({ created_by: 'alice' }, undefined, false)).toBe(false);
  });
  it('confirm copy says created tasks are kept', () => {
    expect(deleteChatCopy({ title: null, status: 'open', task_id: null })).toEqual({ title: '删除「新对话」？', description: '这条记录里的对话、需求草稿、操作卡会一起删除，删了不能恢复。' });
    const kept = deleteChatCopy({ title: '导出 CSV', status: 'confirmed', task_id: 't1' });
    expect(kept.description).toContain('创建的任务不受影响'); expect(kept.description).not.toContain('补充信息');
    expect(deleteChatCopy({ title: 'x', status: 'open', task_id: 't1' }).description).toContain('到任务详情里修改需求');
  });
  it('selects the next session, then the previous, then none', () => {
    const l = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
    expect(nextAfterDelete(l, 'a')).toBe('b'); expect(nextAfterDelete(l, 'c')).toBe('b'); expect(nextAfterDelete([{ id: 'a' }], 'a')).toBeNull();
  });
  it('page wires the shadcn confirm and DELETE call', async () => {
    const { readFileSync } = await import('node:fs'); const { resolve } = await import('node:path');
    const src = readFileSync(resolve(__dirname, '../views/Chat.tsx'), 'utf8');
    expect(src).toContain('useConfirm()'); expect(src).toContain("method: 'DELETE'"); expect(src).toContain('destructive: true'); expect(src).toContain('data-testid="chat-delete"');
  });
});

describe('对话操作确认卡', () => {
  const a = (kind: string, params: Record<string, any>, id = 'act_1'): ChatAction => ({ id, kind, params, status: 'proposed', proposedBy: 'rules', createdAt: '' });
  it('参数用中文标签显示，空值不显示', () => {
    expect(actionFields(a('create_project', { name: '官网', productLineName: '教育', repo: null, baseBranch: 'main', description: '' }))).toEqual([['名称', '官网'], ['所属产品线', '教育'], ['仓库', '暂不绑定'], ['基础分支', 'main']]);
    expect(actionFields(a('invite_member', { login: null, role: 'viewer' }))).toEqual([['GitHub 账号', '任何拿到链接的人'], ['角色', '只读']]);
  });
  it('按角色判断能否确认：邀请管理员只有所有者可以', () => {
    expect(actionAllowed(a('create_product_line', { name: 'x' }), 'member')).toBe(false);
    expect(actionAllowed(a('create_product_line', { name: 'x' }), 'admin')).toBe(true);
    expect(actionAllowed(a('invite_member', { role: 'admin' }), 'admin')).toBe(false);
    expect(actionAllowed(a('invite_member', { role: 'admin' }), 'owner')).toBe(true);
  });
  it('SSE 推来的卡片按 id 去重', () => {
    const l = mergeActions([a('create_product_line', { name: 'x' })], { ...a('create_product_line', { name: 'x' }), status: 'executed' });
    expect(l).toHaveLength(1); expect(l[0].status).toBe('executed');
    expect(mergeActions(l, a('invite_member', { role: 'member' }, 'act_2'))).toHaveLength(2);
  });
});

describe('流式片段节流', () => {
  it('合并片段、flush 立即输出、drop 丢弃未输出的', async () => {
    const { deltaBuffer } = await import('./chat');
    const out: string[] = []; const d = deltaBuffer(t => out.push(t), 20);
    d.push('你'); d.push('好'); expect(out).toEqual([]);
    await new Promise(r => setTimeout(r, 40)); expect(out).toEqual(['你好']);
    d.push('世'); d.flush(); expect(out).toEqual(['你好', '世']);
    d.push('界'); d.drop(); await new Promise(r => setTimeout(r, 40)); expect(out).toEqual(['你好', '世']);
  });
});

import { interleave, collapsible } from './chat';
describe('interleave cards inline', () => {
  const act = (id: string, createdAt: string, status: ChatAction['status'] = 'proposed'): ChatAction => ({ id, kind: 'create_project', params: {}, status, proposedBy: 'agent', createdAt });
  const bs = [{ key: '1', at: '2026-01-01T00:00:01Z' }, { key: '2', at: '2026-01-01T00:00:02Z' }, { key: '3', at: '2026-01-01T00:00:05Z' }, { key: '4', at: '2026-01-01T00:00:06Z' }];
  it('places each card after the last earlier message, not at the bottom', () => {
    const r = interleave(bs, [act('b', '2026-01-01T00:00:05.5Z'), act('a', '2026-01-01T00:00:02.5Z')]);
    expect(r.map(x => x.t === 'b' ? x.b.key : 'A:' + x.a.id)).toEqual(['1', '2', 'A:a', '3', 'A:b', '4']);
  });
  it('live anchors win over clock skew; cards before any message go first', () => {
    const r = interleave(bs, [act('c', '2026-01-01T00:00:00Z'), act('d', '2026-01-01T00:00:01Z')], { d: '4' });
    expect(r.map(x => x.t === 'b' ? x.b.key : 'A:' + x.a.id)).toEqual(['A:c', '1', '2', '3', '4', 'A:d']);
  });
  it('collapses only finished cards', () => { expect(collapsible(act('x', '', 'executed'))).toBe(true); expect(collapsible(act('x', '', 'cancelled'))).toBe(true); expect(collapsible(act('x', '', 'failed'))).toBe(false); expect(collapsible(act('x', '', 'proposed'))).toBe(false); });
});

import { pendingActionHref } from './chat';
describe('pending action link', () => {
  it('goes back to the original session', () => {
    expect(pendingActionHref({ id: 'a', session_id: 'chat_1', kind: 'create_project', params: {}, created_at: '' })).toBe('/s/chat_1');
    expect(pendingActionHref({ id: 'a', session_id: 'chat_1', anchor_kind: 'task', anchor_id: 't1', kind: 'create_project', params: {}, created_at: '' })).toBe('/t/t1/chat');
  });
});

import { showDraft } from './chat';
describe('draft panel phase', () => {
  it('hides the draft only in setup', () => { expect(showDraft({ phase: 'setup' })).toBe(false); expect(showDraft({ phase: 'drafting' })).toBe(true); expect(showDraft({})).toBe(true); });
});

import { actionView as viewBR, actionFields as fieldsBR } from './chat';
describe('bind_repo card', () => {
  const a: ChatAction = { id: 'b', kind: 'bind_repo', params: { projectId: 'p1', projectName: '官网', repo: 'acme/site', baseBranch: 'main' }, status: 'proposed', proposedBy: 'rules', createdAt: '' };
  it('shows project, repo and branch', () => { expect(fieldsBR(a)).toEqual([['项目', '官网'], ['仓库', 'acme/site'], ['基础分支', 'main']]); });
  it('links to the project when done', () => { const v = viewBR({ ...a, status: 'executed', result: { projectId: 'p1', name: '官网', repo: 'acme/site' } }, 'owner', false); expect(v.object).toEqual({ kind: 'project', id: 'p1', name: '官网' }); expect(v.body).toContain('acme/site'); });
});
