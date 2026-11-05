import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { Conversation, ReasoningBubble } from './Chat';
import type { ChatSession } from '../lib/chat';

// 对话页布局回归：消息区必须是独立滚动容器，输入框固定在底部，不能随长消息被挤出视口
const src = readFileSync(resolve(__dirname, 'Chat.tsx'), 'utf8');
const cls = (testid: string) => (src.match(new RegExp(`className="([^"]*)"[^>]*data-testid="${testid}"`)) ?? [])[1] ?? '';

describe('chat layout', () => {
  it('panel has a bounded height and clips overflow', () => {
    expect(cls('chat-conversation')).toMatch(/\bh-\[calc\(100dvh/);
    expect(cls('chat-conversation')).toContain('overflow-hidden');
    expect(src).toContain('bodyClassName="flex min-h-0 flex-1 flex-col"');
  });
  it('message list is its own scroll container', () => {
    for (const c of ['min-h-0', 'flex-1', 'overflow-y-auto']) expect(cls('chat-messages').split(' ')).toContain(c);
  });
  it('composer never shrinks', () => { expect(cls('chat-composer').split(' ')).toContain('shrink-0'); });
  it('auto-scrolls the list itself, not the window', () => {
    expect(src).toContain('l.scrollTop = l.scrollHeight');
    expect(src).not.toContain('scrollIntoView');
  });
});

describe('思考折叠展示', () => {
  it('思考中展开并显示内容，完成后默认收起且保留展开按钮', () => {
    const bubble = { key: 'r', role: 'reasoning' as const, text: '检查需求\n整理回复' };
    const active = renderToStaticMarkup(createElement(ReasoningBubble, { bubble: { ...bubble, pending: true } }));
    expect(active).toContain('aria-expanded="true"'); expect(active).toContain(bubble.text); expect(active).toContain('思考中…');
    const finished = renderToStaticMarkup(createElement(ReasoningBubble, { bubble }));
    expect(finished).toContain('aria-expanded="false"'); expect(finished).toContain('思考过程'); expect(finished).not.toContain(bubble.text);
  });
  it('刷新加载的历史思考作为折叠块展示，回答仍正常显示', () => {
    const s = { id: 's', status: 'open', turns: 1, usd_spent: 0, messages: [
      { id: 1, role: 'reasoning', content: '已保存的思考', created_at: '' },
      { id: 2, role: 'assistant', content: '最终回答', created_at: '' },
    ] } as ChatSession;
    const html = renderToStaticMarkup(createElement(Conversation, { s, lines: [], projects: [], canAsk: false, canWrite: false, prefill: null, onPrefillUsed() {}, onChanged() {}, reload() {} }));
    expect(html).toContain('data-testid="chat-reasoning"'); expect(html).toContain('aria-expanded="false"');
    expect(html).not.toContain('已保存的思考'); expect(html).toContain('最终回答');
  });
});
