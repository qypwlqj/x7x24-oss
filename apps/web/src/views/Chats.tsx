import { AppLink } from '@/components/AppLink';
/** 对话列表：/chats 全部对话（搜索 + 按归属筛选），以及项目 / 产品线「对话」页签里的列表（docs/command-center-v2-nav.md 2.5） */
import { useEffect, useMemo, useState } from 'react';
import { api, fmtTime } from '../api';
import type { ChatSession } from '../lib/chat';
import { ANCHOR_ZH, anchorHref, anchorOf, filterSessions, sessionHref, type AnchorKind } from '../lib/nav';
import { Callout, Input, PageHeader, cx } from '../ui';

export function AnchorTag({ s, className }: { s: ChatSession; className?: string }) {
  const a = anchorOf(s); if (a.kind === 'none') return null;
  return <span className={cx('inline-flex max-w-[9rem] shrink-0 items-center gap-0.5 border border-line-strong px-1 text-[10px] leading-4 text-ink-3', className)} data-testid="anchor-tag">
    <span className="font-bold">{ANCHOR_ZH[a.kind]}</span>{a.name && <span className="truncate">·{a.name}</span>}</span>;
}

/** 会话行列表；hideAnchor 用在对象自己的页签里（归属就是自己，不用重复显示） */
export function SessionList({ list, empty, hideAnchor }: { list: ChatSession[]; empty: React.ReactNode; hideAnchor?: (s: ChatSession) => boolean }) {
  if (list.length === 0) return <p className="text-sm text-ink-3">{empty}</p>;
  return <ul className="divide-y-2 divide-dashed divide-line border-2 border-ink bg-raised" data-testid="session-list">
    {list.map(s => <li key={s.id} className="flex items-center gap-2 px-3 py-2 text-sm hover:bg-sunken" data-testid="session-row">
      <AppLink href={sessionHref(s)} className="min-w-0 flex-1 truncate font-semibold underline-offset-2 hover:underline">{s.title || '新对话'}</AppLink>
      {!hideAnchor?.(s) && <AnchorTag s={s} />}
      <span className="shrink-0 text-caption tabular text-ink-3">{s.turns} 轮 · {fmtTime(s.updated_at)}</span>
    </li>)}
  </ul>;
}

const KINDS: (AnchorKind | 'all')[] = ['all', 'task', 'project', 'line', 'none'];
export default function Chats() {
  const [list, setList] = useState<ChatSession[] | null>(null); const [err, setErr] = useState('');
  const [q, setQ] = useState(''); const [kind, setKind] = useState<AnchorKind | 'all'>('all');
  useEffect(() => { api<ChatSession[]>('/api/chats').then(setList).catch(e => setErr(e.message)); }, []);
  const shown = useMemo(() => filterSessions(list ?? [], q, kind), [list, q, kind]);
  return <div className="space-y-gap" data-testid="chats-page">
    <PageHeader title="全部对话" desc="按最近更新排列。建了任务的对话在任务的「对话」页签里打开。" />
    {err && <Callout tone="danger">{err}</Callout>}
    <div className="flex flex-wrap items-center gap-2">
      <Input aria-label="搜索对话" placeholder="搜索标题 / 归属…" value={q} onChange={e => setQ(e.target.value)} className="w-64" />
      {KINDS.map(k => <button key={k} type="button" aria-pressed={kind === k} onClick={() => setKind(k)} className={cx('border-2 border-ink px-2 py-0.5 text-caption', kind === k ? 'bg-ink text-ink-inverse' : 'bg-raised hover:bg-sunken')}>{k === 'all' ? '全部' : ANCHOR_ZH[k]}</button>)}
    </div>
    {list === null ? <p className="text-sm text-ink-3">加载中…</p> : <SessionList list={shown} empty="没有符合条件的对话。" />}
  </div>;
}

/** 项目 / 产品线的「对话」页签：这个对象下的对话 + 在此新对话 */
export function ObjectChats({ kind, id, composer }: { kind: 'project' | 'line'; id: string; composer: React.ReactNode }) {
  const [list, setList] = useState<ChatSession[] | null>(null);
  useEffect(() => { setList(null); api<ChatSession[]>(`/api/chats?anchor=${kind}:${encodeURIComponent(id)}`).then(setList).catch(() => setList([])); }, [kind, id]);
  return <div className="space-y-gap" data-testid="object-chats">
    <section>
      <h3 className="label-caps mb-1.5 text-caption text-ink-3">{kind === 'project' ? '这个项目的对话' : '这条产品线及其项目的对话'}{list ? `（${list.length}）` : ''}</h3>
      {list === null ? <p className="text-sm text-ink-3">加载中…</p> : <SessionList list={list} empty="还没有对话。在下面说一句就会新开一个。" hideAnchor={s => anchorOf(s).kind === kind && anchorOf(s).id === id} />}
    </section>
    <section><h3 className="label-caps mb-1.5 text-caption text-ink-3">在此新对话</h3>{composer}</section>
  </div>;
}

/** 对象页顶部「← 返回对话」：从对话里的对象卡片点进来时显示 */
export function BackToChat({ sessionId }: { sessionId: string }) {
  const [s, setS] = useState<ChatSession | null>(null);
  useEffect(() => { api<ChatSession>(`/api/chats/${encodeURIComponent(sessionId)}`).then(setS).catch(() => setS(null)); }, [sessionId]);
  return <AppLink href={s ? sessionHref(s) : `/s/${encodeURIComponent(sessionId)}`} className="mb-2 inline-flex max-w-full items-center gap-1 border-2 border-ink bg-raised px-2 py-0.5 text-sm hover:bg-sunken" data-testid="back-to-chat">
    ← 返回对话{s?.title ? <span className="truncate">：{s.title}</span> : null}</AppLink>;
}
export { anchorHref };
