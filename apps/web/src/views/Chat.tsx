import { useRouter } from 'next/navigation';
import { navigationTarget } from '../lib/navigation';
import { AppLink } from '@/components/AppLink';
import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { api, fmtTime, type ProjectDTO } from '../api';
import { ACTION_TITLE, actionAllowed, actionFields, actionView, collapsible, interleave, mergeActions, type ChatAction } from '../lib/chat';
import { inviteLink } from '../lib/workspace';
import { Disclosure } from '@/components/ui/collapsible';
import { GUIDES, chatLink, CAPABILITIES, GROUPS, HELP_TEXT, ROLE_ZH, capabilityState, capabilityText, examples, filterSessions, greeting, matchSlash, type HistoryFilter } from '../lib/command';
import type { LineMeta } from '../api';
import { applyReasoningEvent, showDraft, streamFollowup, objectHref, CLASS_LABEL, RISK_ZH, canDeleteChat, deleteChatCopy, lines, nextAfterDelete, streamChat, deltaBuffer, type ChatBubble as Bubble, type ChatEvent, type ChatSession, type Draft, type Precheck } from '../lib/chat';
import { Badge, Button, Callout, Card, Field, Icon, IconButton, Input, PageHeader, Select, Textarea, cx, toast, useConfirm } from '../ui';
import { ListItemButton } from '@/components/ui/button';
import { PipelineGraph } from '../components/PipelineGraph';
import { NodeIcon } from '../ui/NodeIcon';
import { TaskFlow } from '../components/TaskFlow';

const STATUS: Record<string, [string, any]> = { open: ['进行中', 'primary'], confirmed: ['已建任务', 'success'], abandoned: ['已关闭', 'neutral'], pending_approval: ['待审批', 'warn'] };


/** 发出的第一句话：新会话建好、跳到 /s/<id> 后由 Conversation 自动发送 */
const pending = new Map<string, string>();
export const takePending = (id: string) => { const t = pending.get(id); pending.delete(id); return t; };
export const NEW_PLACEHOLDER = '描述需求，创建项目、任务或邀请成员…';

/** 新对话 / 项目会话的空白态：一个输入框 + 一行灰色提示。发送时才建会话 */
export function BlankComposer({ context, onCreated, hint, initial = '' }: { initial?: string; context: { productLineId?: string | null; projectId?: string | null }; onCreated: (id: string) => void; hint?: React.ReactNode }) {
  const [text, setText] = useState(initial); const [busy, setBusy] = useState(false); const [err, setErr] = useState('');
  const ref = useRef<HTMLTextAreaElement>(null); useEffect(() => { const el = ref.current; if (!el) return; el.focus(); const at = initial.indexOf('  '); const pos = at >= 0 ? at + 1 : initial.length; el.setSelectionRange(pos, pos); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const go = async () => {
    const t = text.trim(); if (!t || busy) return; setBusy(true);
    try { const r = await api<ChatSession>('/api/chats', { method: 'POST', body: { productLineId: context.productLineId || null, projectId: context.projectId || null } }); pending.set(r.id, t); onCreated(r.id); }
    catch (e: any) { setErr(e.message); setBusy(false); }
  };
  return <div className="mx-auto w-full max-w-2xl pt-[12vh]" data-testid="new-session">
    {err && <Callout tone="danger" onClose={() => setErr('')}>{err}</Callout>}
    <Textarea ref={ref} aria-label="输入消息" rows={3} value={text} disabled={busy} placeholder={NEW_PLACEHOLDER} onChange={e => setText(e.target.value)}
      onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); go(); } }} data-testid="new-session-input" />
    <div className="mt-2 flex items-center justify-between gap-2 text-caption text-ink-3"><span>{hint}</span><Button variant="primary" icon="arrow" loading={busy} onClick={go} disabled={!text.trim()} data-testid="new-session-send">发送</Button></div>
  </div>;
}

/** 项目「会话」页签：项目下没绑定任务的会话；没有就显示空白输入 */
export default function Chat({ id, role, lines = [], canAsk: canAskProp, onCatalogChanged, projects, canWrite, canApprove, onOpen, onTask }: { embedded?: boolean; id?: string; me?: string; role?: string; wsName?: string; lines?: LineMeta[]; canAsk?: boolean; onCatalogChanged?: () => void; projects: ProjectDTO[]; canWrite: boolean; canApprove: boolean; onOpen: (id: string) => void; onTask: (id: string) => void }) {
  const canAsk = canAskProp ?? canWrite;
  const [s, setS] = useState<ChatSession | null>(null); const [err, setErr] = useState('');
  const load = (x = id) => x ? api<ChatSession>(`/api/chats/${x}`).then(setS).catch(e => setErr(e.message)) : Promise.resolve(setS(null));
  useEffect(() => { setErr(''); load(); }, [id]);
  return (
    <div className="space-y-gap" data-testid="chat-page">
      {err && <Callout tone="danger" onClose={() => setErr('')}>{err}</Callout>}
      {s ? <SessionBody s={s} role={role} lines={lines} projects={projects} canAsk={canAsk} canWrite={canWrite} canApprove={canApprove} onCatalogChanged={onCatalogChanged} onChanged={setS} reload={() => load()} onTask={onTask} />
        : <BlankComposer context={{ productLineId: projects[0]?.productLineId, projectId: projects.length === 1 ? projects[0].id : null }} onCreated={onOpen} />}
    </div>
  );
}

/** 一条会话：对话 + 需求草稿卡 */
export function SessionBody({ s, me, role, lines, projects, canAsk, canWrite, canApprove, onCatalogChanged, onChanged, reload, onTask, onDeleted }: { s: ChatSession; me?: string; onDeleted?: () => void; role?: string; lines: LineMeta[]; projects: ProjectDTO[]; canAsk: boolean; canWrite: boolean; canApprove: boolean; onCatalogChanged?: () => void; onChanged: (s: ChatSession) => void; reload: () => void; onTask: (id: string) => void }) {
  const [prefill, setPrefill] = useState<{ text: string; send?: boolean } | null>(() => { const t = takePending(s.id); return t ? { text: t, send: true } : null; });
  const confirm = useConfirm();
  const remove = async () => {
    if (!await confirm({ ...deleteChatCopy(s), confirmText: '删除', destructive: true })) return;
    try { const r = await api<{ keptTaskId: string | null }>(`/api/chats/${s.id}`, { method: 'DELETE' }); toast.success(r.keptTaskId ? '已删除这条对话，由它创建的任务不受影响' : '已删除这条对话'); onDeleted?.(); }
    catch (e: any) { toast.error(e.message); }
  };
  const drafting = showDraft(s);
  return <div className={cx('grid gap-gap', drafting && 'xl:grid-cols-[minmax(0,1fr)_340px]')} data-phase={drafting ? 'drafting' : 'setup'}>
    {onDeleted && canWrite && canDeleteChat(s, me, canApprove) && <div className="flex justify-end xl:col-span-2"><Button size="sm" variant="ghost" icon="trash" onClick={remove} data-testid="chat-delete">删除对话</Button></div>}
    <Conversation key={s.id} s={s} role={role} lines={lines} projects={projects} canAsk={canAsk} prefill={prefill} onPrefillUsed={() => setPrefill(null)} onCatalogChanged={onCatalogChanged} canWrite={canWrite} onChanged={onChanged} reload={reload} />
    {drafting && <DraftCard key={s.id + ':' + s.draft_version} s={s} projects={projects} canWrite={canWrite} canApprove={canApprove} reload={reload} onTask={onTask} />}
  </div>;
}

function toBubbles(s: ChatSession): Bubble[] {
  return (s.messages ?? []).map(m => m.role === 'tool' && m.tool_name === 'guide' ? { key: String(m.id), role: 'guide' as const, text: m.tool_summary ?? '', at: m.created_at } : ({ key: String(m.id), role: m.role, at: m.created_at, text: m.role === 'tool' ? `${m.tool_name}：${m.tool_summary ?? ''}` : m.content ?? '' }));
}

export function Conversation({ s, role, lines, projects, canAsk, prefill, onPrefillUsed, onCatalogChanged, canWrite, onChanged, reload }: { s: ChatSession; role?: string; lines: LineMeta[]; projects: ProjectDTO[]; canAsk: boolean; prefill: { text: string; send?: boolean } | null; onPrefillUsed: () => void; onCatalogChanged?: () => void; canWrite: boolean; onChanged: (s: ChatSession) => void; reload: () => void }) {
  const [bubbles, setBubbles] = useState<Bubble[]>(() => toBubbles(s)); const [text, setText] = useState(''); const [busy, setBusy] = useState(false); const [status, setStatus] = useState('');
  const abort = useRef<AbortController | null>(null);
  const list = useRef<HTMLDivElement>(null);
  const [acts, setActs] = useState<ChatAction[]>(() => s.actions ?? []);
  useEffect(() => { if (s.actions) setActs(s.actions); }, [s.actions]);
  // 重新加载后以服务端消息为准（操作完成、阶段切换时服务端会补一句提示）；本地的错误提示留在末尾
  useEffect(() => { if (!s.messages || busy) return; setBubbles(b => [...toBubbles(s), ...b.filter(x => x.role === 'error')]); }, [s.messages]); // eslint-disable-line react-hooks/exhaustive-deps
  const anchors = useRef<Record<string, string>>({}); const lastKey = useRef('');
  useEffect(() => { lastKey.current = bubbles[bubbles.length - 1]?.key ?? ''; }, [bubbles]);
  const items = useMemo(() => interleave(bubbles, acts, anchors.current), [bubbles, acts]);
  // 待确认卡片滚出视野时，底部给一个跳转提示
  const [hidden, setHidden] = useState<string[]>([]);
  useEffect(() => {
    const root = list.current; if (!root || typeof IntersectionObserver === 'undefined') return;
    const vis = new Map<string, boolean>();
    const io = new IntersectionObserver(es => { es.forEach(e => vis.set((e.target as HTMLElement).dataset.aid!, e.isIntersecting)); setHidden([...vis].filter(([, v]) => !v).map(([k]) => k)); }, { root, threshold: 0.3 });
    root.querySelectorAll<HTMLElement>('[data-testid=chat-action-card][data-status=proposed]').forEach(el => io.observe(el));
    return () => io.disconnect();
  }, [items]);
  const jump = () => { const el = list.current?.querySelector<HTMLElement>(`[data-aid="${hidden[hidden.length - 1]}"]`); const l = list.current; if (el && l) l.scrollTo({ top: el.offsetTop - l.clientHeight / 2, behavior: 'smooth' }); };
  useEffect(() => { const l = list.current; if (l) l.scrollTop = l.scrollHeight; }, [bubbles, status]);
  const open = s.status === 'open';
  const ex = useMemo(() => examples(role), [role]); const [exI, setExI] = useState(0); const [focused, setFocused] = useState(false); const [help, setHelp] = useState(false);
  useEffect(() => { if (focused || ex.length < 2) return; const t = setInterval(() => setExI(i => (i + 1) % ex.length), 4000); return () => clearInterval(t); }, [focused, ex.length]);
  const slash = matchSlash(text, role);
  useEffect(() => { if (!prefill) return; onPrefillUsed(); if (prefill.send) send(prefill.text); else setText(prefill.text); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const [line, setLine] = useState(s.product_line_id ?? ''); const [proj, setProj] = useState(s.project_id ?? '');
  const setCtx = async (l: string, p: string) => { try { await api(`/api/chats/${s.id}/context`, { method: 'PUT', body: { productLineId: l || null, projectId: p || null } }); setLine(l); setProj(p); } catch (e: any) { toast.error(e.message); } };
  const send = async (override?: string) => {
    const t = (override ?? text).trim(); if (!t || busy) return;
    setText(''); setBubbles(b => [...b, { key: 'u' + Date.now(), role: 'user', text: t, at: new Date().toISOString() }]);
    await stream((on, sig) => streamChat(s.id, t, on, sig));
  };
  /** 操作完成后续一轮「下一步」提示 */
  const followup = (actionId: string) => { if (!busy && canAsk) void stream((on, sig) => streamFollowup(s.id, actionId, on, sig)); };
  const stream = async (start: (on: (e: ChatEvent) => void, sig: AbortSignal) => Promise<void>) => {
    setBusy(true); setStatus('思考中…'); const aKey = 'a' + Date.now();
    setBubbles(b => [...b, { key: aKey, role: 'assistant', text: '', pending: true, at: new Date().toISOString() }]);
    abort.current = new AbortController();
    const deltas = deltaBuffer(t => setBubbles(b => b.map(x => x.key === aKey ? { ...x, text: x.text + t } : x)));
    let reasoningId = '';
    const thoughts = deltaBuffer(t => { const id = reasoningId; setBubbles(b => applyReasoningEvent(b, aKey, { type: 'reasoning', data: { id, text: t } })); });
    const on = (e: ChatEvent) => {
      if (e.type !== 'delta') deltas.flush();
      if (e.type !== 'reasoning') thoughts.flush();
      if (e.type === 'reasoning_done' || e.type === 'reset' || e.type === 'done' || e.type === 'error' || e.type === 'stopped') setBubbles(b => applyReasoningEvent(b, aKey, e));
      if (e.type === 'tool') setBubbles(b => { const i = b.findIndex(x => x.key === aKey); const tool: Bubble = { key: 't' + Math.random(), role: 'tool', text: `${e.data.name}：${e.data.summary}` }; return i < 0 ? [...b, tool] : [...b.slice(0, i), tool, ...b.slice(i)]; });
      else if (e.type === 'action') { const x = e.data as ChatAction; if (!anchors.current[x.id]) anchors.current[x.id] = lastKey.current; setActs(a => mergeActions(a, x)); }
      else if (e.type === 'guide') setBubbles(b => { const i = b.findIndex(x => x.key === aKey); const g: Bubble = { key: 'g' + Math.random(), role: 'guide', text: e.data.topic }; return i < 0 ? [...b, g] : [...b.slice(0, i), g, ...b.slice(i)]; });
      else if (e.type === 'status') setStatus(e.data.text);
      else if (e.type === 'reasoning') {
        setStatus('');
        if (reasoningId !== e.data.id) { thoughts.flush(); reasoningId = e.data.id; }
        thoughts.push(e.data.text);
      }
      else if (e.type === 'reasoning_done') setStatus('');
      else if (e.type === 'delta') { setStatus(''); deltas.push(e.data.text); }
      else if (e.type === 'reset') { deltas.drop(); setBubbles(b => b.map(x => x.key === aKey ? { ...x, text: '' } : x)); }
      else if (e.type === 'draft' || e.type === 'done') onChanged({ ...s, draft: e.data.draft, precheck: e.data.precheck, draft_version: e.data.version ?? s.draft_version, ...(e.data.phase ? { phase: e.data.phase } : {}), ...(e.data.plan !== undefined ? { plan: e.data.plan } : {}) });
      else if (e.type === 'error') setBubbles(b => [...b, { key: 'e' + Math.random(), role: 'error', text: e.data.message }]);
      else if (e.type === 'stopped') setBubbles(b => [...b, { key: 'e' + Math.random(), role: 'error', text: '已停止' }]);
    };
    try { await start(on, abort.current.signal); }
    catch (e: any) { if (e.name !== 'AbortError') setBubbles(b => [...b, { key: 'e' + Math.random(), role: 'error', text: `连接中断（${e.message}），已重新加载保存的内容` }]); }
    finally { deltas.flush(); thoughts.flush(); setBusy(false); setStatus(''); setBubbles(b => applyReasoningEvent(b, aKey, { type: 'done', data: {} }).filter(x => !(x.key === aKey && !x.text)).map(x => x.key === aKey ? { ...x, pending: false } : x)); reload(); }
  };
  // 先请求服务端停止（中止模型、保存已输出的部分），等它发回 stopped；3 秒没结束再断开连接
  const stop = async () => { const a = abort.current; try { await api(`/api/chats/${s.id}/stop`, { method: 'POST' }); } catch { a?.abort(); return; } setTimeout(() => a?.abort(), 3000); };
  return (
    <Card flush title={s.title || '新指令'} subtitle={<span className="tabular">第 {s.turns} 轮 · 模型费用 ${Number(s.usd_spent ?? 0).toFixed(4)}{s.limits ? ` / 上限 $${s.limits.sessionUsd}` : ''}</span>} bodyClassName="flex min-h-0 flex-1 flex-col" className="flex h-[calc(100dvh-120px)] min-h-[420px] flex-col overflow-hidden xl:h-[calc(100dvh-180px)]" data-testid="chat-conversation">
      <div ref={list} className="relative min-h-0 flex-1 space-y-3 overflow-y-auto overscroll-contain p-4" data-testid="chat-messages">
        {bubbles.length === 0 && <div className="text-sm text-ink-3">说说你想要什么。比如：「订单列表接口加个分页」「成绩页想加个导出」。也可以直接说「新增产品线 教育」「新建项目 官网 到产品线 教育」「邀请 @octocat 为成员」，助手会先给你一张确认卡。</div>}
        {items.map(it => { if (it.t === 'a') { const a = it.a; return <ActionCard key={s.id + ':' + a.id} chatId={s.id} a={a} role={role} canWrite={canWrite} onDone={(x, executed) => { if (x.id !== a.id) anchors.current[x.id] = lastKey.current; setActs(l => mergeActions(l, x)); if (executed && x.kind !== 'invite_member') onCatalogChanged?.(); if (executed) followup(x.id); else reload(); }} onRefresh={x => { if (x) setActs(l => mergeActions(l, x)); reload(); }} />; } const b = it.b; return b.role === 'reasoning' ? <ReasoningBubble key={b.key} bubble={b} /> : b.role === 'guide' ? <GuideCard key={b.key} topic={b.text} /> : b.role === 'tool'
          ? <div key={b.key} className="flex items-center gap-1.5 pl-1 font-mono text-caption text-accent-ink"><Icon name="check" size={12} />{b.text}</div>
          : <div key={b.key} className={cx('flex', b.role === 'user' ? 'justify-end' : 'justify-start')}>
            <div className={cx('cut cut-sm max-w-[85%] whitespace-pre-wrap border px-3 py-2 text-sm leading-relaxed',
              b.role === 'user' ? 'border-primary/60 bg-primary-soft text-ink' : b.role === 'error' ? 'border-danger bg-danger-soft text-danger-ink' : 'border-line-strong bg-sunken text-ink')}>
              {b.text ? <Rich text={b.text} /> : (b.pending ? <span className="animate-pulse text-ink-3">▍</span> : '')}{b.pending && b.text && <span className="animate-pulse text-primary-ink">▍</span>}
            </div></div>; })}
        {status && <div className="pl-1 text-caption text-ink-3" data-testid="chat-status">{status}</div>}
      </div>
      {hidden.length > 0 && <div className="relative shrink-0"><Button size="sm" variant="primary" className="absolute bottom-2 left-1/2 -translate-x-1/2 shadow" onClick={jump} data-testid="chat-pending-jump">{hidden.length} 张待确认卡片 ↑ 点此跳转</Button></div>}
      {open && canWrite && <div className="flex shrink-0 flex-wrap items-center gap-2 border-b border-line bg-sunken/60 px-3 py-1.5 text-caption" data-testid="chat-context">
        <span className="font-mono text-ink-3">CTX //</span>
        <Select aria-label="当前产品线" value={line} onChange={e => setCtx(e.target.value, '')} className="h-7 w-auto text-caption"><option value="">全部产品线</option>{lines.filter(l => l.state !== 'ARCHIVED').map(l => <option key={l.id} value={l.id}>{l.name}</option>)}</Select>
        <Select aria-label="当前项目" value={proj} onChange={e => { const p = projects.find(x => x.id === e.target.value); setCtx(p ? p.productLineId : line, e.target.value); }} className="h-7 w-auto text-caption"><option value="">全部项目</option>{projects.filter(p => !line || p.productLineId === line).map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</Select>
      </div>}
      {open && canAsk ? <div className="relative shrink-0 border-t border-line p-3" data-testid="chat-composer">
        {slash.length > 0 && <ul className="absolute bottom-full left-3 z-10 mb-1 min-w-[220px] border border-primary/60 bg-surface shadow-lg" role="listbox" data-testid="chat-slash">{slash.map(c => <li key={c.cmd[0]}><button type="button" className="flex w-full items-center justify-between gap-3 px-3 py-1.5 text-left text-sm hover:bg-raised" onClick={() => { if (c.help) { setHelp(true); setText(''); } else setText(c.text); }}><span>{c.label}</span><span className="font-mono text-caption text-ink-3">{c.cmd.join(' ')}</span></button></li>)}</ul>}
        {help && <Callout tone="info" onClose={() => setHelp(false)}>{HELP_TEXT}</Callout>}
        <Textarea aria-label="输入消息" rows={2} value={text} disabled={busy} placeholder={ex[exI % Math.max(ex.length, 1)] ?? '提需求、查进度…'} onFocus={() => setFocused(true)} onBlur={() => setFocused(false)} onChange={e => setText(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); send(); } }} data-testid="chat-input" />
        <div className="mt-2 flex justify-end gap-2">{busy ? <Button variant="danger" icon="pause" onClick={stop}>停止</Button> : <Button variant="primary" icon="arrow" onClick={() => send()} disabled={!text.trim()} data-testid="chat-send">发送</Button>}</div>
        <div className="mt-1 text-caption text-ink-3">Enter 发送 · Shift+Enter 换行 · 输入 / 查看快捷指令</div>
      </div> : <div className="shrink-0 border-t border-line p-3 text-sm text-ink-3">{s.status === 'confirmed' ? '这条对话已建任务。有新想法请点「＋ 新对话」。' : !canAsk ? '你是只读成员，只能查看。需要权限请找工作区管理员。' : '这条记录已关闭。'}</div>}
    </Card>
  );
}

/** 建项目卡片里可改的参数：仓库可以「暂不绑定」；只填仓库名时 owner 用下拉（默认工作区绑定的 GitHub 账号） */
function ProjectParamsEditor({ a, err, onChange }: { a: ChatAction; err: { field?: string; message: string } | null; onChange: (patch: Record<string, any>) => void }) {
  const p = a.params ?? {}; const full = String(p.repo ?? ''); const [o0, r0] = full.includes('/') ? full.split('/', 2) : ['', full];
  const bindOnly = a.kind === 'bind_repo'; // 绑定仓库卡：只改仓库和分支，不能「暂不绑定」
  const [bind, setBind] = useState(bindOnly || !!full); const [owners, setOwners] = useState<string[]>([]); const [owner, setOwner] = useState(o0);
  const [repo, setRepo] = useState(r0); const [branch, setBranch] = useState(String(p.baseBranch ?? 'main')); const [name, setName] = useState(String(p.name ?? ''));
  const [sugs, setSugs] = useState<{ fullName: string; defaultBranch?: string }[]>([]);
  useEffect(() => { api<{ owners: string[]; default?: string }>('/api/chats/repo-owners').then(d => { setOwners(d.owners ?? []); if (!o0 && d.default) setOwner(d.default); }).catch(() => {}); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (!bind || !repo) { setSugs([]); return; } const t = setTimeout(() => api(`/api/integrations/github/repos?q=${encodeURIComponent(repo)}&page=1&size=8`).then(d => setSugs(d?.items ?? [])).catch(() => setSugs([])), 300); return () => clearTimeout(t); }, [repo, bind]);
  useEffect(() => { const r = bind ? (repo.includes('/') ? repo : owner ? `${owner}/${repo}` : repo) : null; if (bindOnly) { onChange({ repo: r, baseBranch: branch || 'main' }); return; } onChange({ name, bindRepo: bind, repo: bind ? (repo.includes('/') ? repo : owner ? `${owner}/${repo}` : repo) : null, baseBranch: branch || 'main' }); }, [name, bind, owner, repo, branch]); // eslint-disable-line react-hooks/exhaustive-deps
  const fe = (f: string) => err?.field === f ? <div className="text-caption text-danger-ink" data-testid={`chat-action-err-${f}`}>{err.message}</div> : null;
  const ownerOpts = owner && !owners.includes(owner) ? [owner, ...owners] : owners;
  return <div className="mt-1 space-y-1.5" data-testid="chat-action-editor">
    {!bindOnly && <><div><Input aria-label="项目名称" value={name} onChange={e => setName(e.target.value)} className="h-7 text-sm" data-testid="chat-action-name" />{fe('name')}</div>
    <div className="flex items-center gap-2"><Button size="sm" variant={bind ? 'ghost' : 'primary'} role="switch" aria-checked={!bind} onClick={() => setBind(b => !b)} data-testid="chat-action-nobind">{bind ? '暂不绑定仓库' : '✓ 暂不绑定（之后在项目里绑）'}</Button></div></>}
    {bind && <div className="flex flex-wrap items-center gap-1.5">
      <Select aria-label="仓库 owner" value={owner} onChange={e => setOwner(e.target.value)} className="h-7 w-auto text-caption" data-testid="chat-action-owner">{ownerOpts.length === 0 && <option value="">（未绑定 GitHub）</option>}{ownerOpts.map(o => <option key={o} value={o}>{o}</option>)}</Select>
      <span className="text-ink-3">/</span>
      <Input aria-label="仓库名" list={`repos-${a.id}`} value={repo} placeholder="仓库名" onChange={e => { const v = e.target.value; const hit = sugs.find(x => x.fullName === v || x.fullName.endsWith('/' + v)); if (hit && v.includes('/')) { const [o, r] = hit.fullName.split('/'); setOwner(o); setRepo(r); if (hit.defaultBranch) setBranch(hit.defaultBranch); } else setRepo(v); }} className="h-7 w-40 text-sm" data-testid="chat-action-repo" />
      <datalist id={`repos-${a.id}`}>{sugs.map(x => <option key={x.fullName} value={x.fullName} />)}</datalist>
      <span className="text-ink-3">分支</span><Input aria-label="基础分支" value={branch} onChange={e => setBranch(e.target.value)} className="h-7 w-24 text-sm" data-testid="chat-action-branch" />
    </div>}
    {fe('repo')}{fe('baseBranch')}
  </div>;
}

/** 阶段切换时重建折叠块，让思考完成立即收起；之后的手动展开不受内容刷新影响。 */
export function ReasoningBubble({ bubble }: { bubble: Bubble }) {
  return <Disclosure key={bubble.pending ? 'thinking' : 'finished'} defaultOpen={!!bubble.pending}
    summary={bubble.pending ? '思考中…' : '思考过程'} summaryClassName="text-caption text-ink-3"
    className="max-w-[85%] border-l-2 border-line-strong py-1 pl-3" data-testid="chat-reasoning">
    <div className="mt-2 whitespace-pre-wrap break-words text-sm leading-relaxed text-ink-2">{bubble.text}</div>
  </Disclosure>;
}

/** A 类操作确认卡：显示参数，确认后以当前用户权限执行；没权限时提示找管理员。
 * 校验失败卡片保持待确认、在字段下提示；任何失败都会刷新卡片，409 = 状态已变化；失败 / 取消的卡可「重新发起」 */
function ActionCard({ chatId, a, role, canWrite, onDone, onRefresh }: { chatId: string; a: ChatAction; role?: string; canWrite: boolean; onDone: (a: ChatAction, executed: boolean) => void; onRefresh: (a?: ChatAction) => void }) {
  const [busy, setBusy] = useState(false); const [link, setLink] = useState(''); const [err, setErr] = useState<{ field?: string; message: string } | null>(a.error ? { message: a.error } : null);
  const patch = useRef<Record<string, any> | null>(null);
  const allowed = actionAllowed(a, role); const v = actionView(a, role, busy);
  const editable = (a.kind === 'create_project' || a.kind === 'bind_repo') && a.status === 'proposed' && canWrite && allowed;
  const fail = (e: any) => {
    if (e?.status === 409) { toast.error('状态已变化，已刷新'); onRefresh(); return; }
    const d = e?.data; if (d?.action) onRefresh(d.action as ChatAction); else onRefresh();
    setErr({ field: d?.field ?? undefined, message: e?.message ?? '操作失败' }); if (!d?.field) toast.error(e?.message ?? '操作失败');
  };
  const act = async (what: 'confirm' | 'cancel' | 'repropose') => {
    setBusy(true); setErr(null);
    try {
      if (what === 'cancel') { onDone(await api<ChatAction>(`/api/chats/${chatId}/actions/${a.id}/cancel`, { method: 'POST' }), false); return; }
      if (what === 'repropose') { onDone(await api<ChatAction>(`/api/chats/${chatId}/actions/${a.id}/repropose`, { method: 'POST', body: {} }), false); toast.success('已重新发起，请确认'); return; }
      if (editable && patch.current) await api<ChatAction>(`/api/chats/${chatId}/actions/${a.id}/params`, { method: 'PUT', body: patch.current });
      const r = await api<{ action: ChatAction; executed: boolean; inviteToken?: string }>(`/api/chats/${chatId}/actions/${a.id}/confirm`, { method: 'POST' });
      if (r.inviteToken) { const l = inviteLink(location, r.inviteToken); setLink(l); try { await navigator.clipboard.writeText(l); toast.success('邀请链接已复制'); } catch { /* ignore */ } }
      else toast.success(r.executed ? '已完成' : '这个操作之前已经完成了');
      onDone(r.action, r.executed);
    } catch (e: any) { fail(e); } finally { setBusy(false); }
  };
  const [open, setOpen] = useState(false);
  if (collapsible(a) && !open && !link) return (
    <div className="cut cut-sm flex max-w-[85%] items-center gap-2 border border-line bg-sunken px-3 py-1 text-caption text-ink-2" data-testid="chat-action-card" data-status={a.status} data-aid={a.id} data-collapsed="true">
      <span className="hidden shrink-0 font-mono text-ink-3 sm:inline">ACTION //</span><span className="shrink-0 whitespace-nowrap font-semibold">{ACTION_TITLE[a.kind] ?? a.kind}</span><span className="min-w-0 flex-1 truncate">{String(a.params?.name ?? (a.kind === 'bind_repo' ? `${a.params?.projectName ?? ''} ← ${a.params?.repo ?? ''}` : a.params?.login) ?? '')}</span>
      <span className="shrink-0 whitespace-nowrap"><Badge tone={v.tone}>{v.label}</Badge></span>
      {v.object && <AppLink href={objectHref(v.object, chatId)} className="shrink-0 whitespace-nowrap text-primary-ink underline" data-testid="chat-object-card">{v.object.kind === 'project' ? '打开项目 →' : '打开产品线 →'}</AppLink>}
      <Button size="sm" variant="ghost" onClick={() => setOpen(true)} data-testid="chat-action-expand">详情</Button>
    </div>);
  return (
    <div className="cut cut-sm max-w-[85%] border border-accent/60 bg-sunken px-3 py-2 text-sm" data-testid="chat-action-card" data-status={a.status} data-aid={a.id}>
      <div className="mb-1.5 flex items-center justify-between gap-2">
        <span className="font-semibold text-accent-ink"><span className="mr-1.5 font-mono text-caption text-ink-3">ACTION //</span>{ACTION_TITLE[a.kind] ?? a.kind}</span>
        <Badge tone={v.tone}>{v.label}</Badge>
      </div>
      <p className="mb-1.5 text-ink-2" data-testid="chat-action-body">{v.body}{v.link && <> <AppLink className="text-primary-ink underline" href={v.link.href}>{v.link.label}</AppLink></>}</p>
      {v.object && <AppLink href={objectHref(v.object, chatId)} className="mb-1.5 flex items-center gap-2 border-2 border-ink bg-raised px-2.5 py-1.5 shadow-[2px_2px_0_rgb(var(--c-line-strong))] hover:bg-surface" data-testid="chat-object-card">
        <NodeIcon name={v.object.kind === 'project' ? 'node-plan' : 'node-audit'} size={16} /><span className="min-w-0 flex-1"><span className="block text-caption text-ink-3">{v.object.kind === 'project' ? '项目' : '产品线'}</span><span className="block truncate font-bold">{v.object.name}</span></span>
        <span className="shrink-0 text-caption text-primary-ink underline">{v.object.kind === 'project' ? '打开项目 →' : '打开产品线 →'}</span></AppLink>}
      {editable ? <><dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-ink-2">{actionFields(a).filter(([k]) => !['名称', '仓库', '基础分支', '分支'].includes(k)).map(([k, v]) => <Fragment key={k}><dt className="text-ink-3">{k}</dt><dd className="break-all">{v}</dd></Fragment>)}</dl>
        <ProjectParamsEditor a={a} err={err} onChange={p => { patch.current = p; }} /></>
        : <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-ink-2">{actionFields(a).map(([k, v]) => <Fragment key={k}><dt className="text-ink-3">{k}</dt><dd className="break-all">{v}</dd></Fragment>)}</dl>}
      {err && !(editable && err.field && ['name', 'repo', 'baseBranch'].includes(err.field)) && a.status === 'proposed' && <div className="mt-1 text-caption text-danger-ink" data-testid="chat-action-error">{err.message}</div>}
      {link && <div className="mt-1.5 break-all border border-line bg-surface px-2 py-1 font-mono text-caption text-ink" data-testid="chat-action-invite-link">{link}<div className="mt-0.5 font-sans text-ink-3">链接只显示这一次，请复制发给对方。</div></div>}
      {a.status === 'proposed' && canWrite && <div className="mt-2 flex items-center justify-end gap-2">
        {!allowed && <Button size="sm" variant="ghost" className="mr-auto" onClick={async () => { try { await navigator.clipboard.writeText(chatLink(location, chatId)); toast.success('对话链接已复制'); } catch { toast.error('复制失败，请手动复制地址栏'); } }} data-testid="chat-action-copy-link">复制对话链接</Button>}
        <Button size="sm" disabled={busy} onClick={() => act('cancel')} data-testid="chat-action-cancel">取消</Button>
        <Button size="sm" variant="primary" loading={busy} disabled={!allowed} onClick={() => act('confirm')} data-testid="chat-action-confirm">确认</Button>
      </div>}
      {a.status === 'failed' && canWrite && <div className="mt-2 flex justify-end"><Button size="sm" variant="primary" loading={busy} onClick={() => act('repropose')} data-testid="chat-action-repropose">重新发起</Button></div>}
    </div>
  );
}

/** B 类引导卡：不能代办的操作，给去对应页面的按钮 */
function GuideCard({ topic }: { topic: string }) {
  const router = useRouter();
  const g = GUIDES[topic]; if (!g) return null;
  return <div className="cut cut-sm max-w-[85%] border border-warn/60 bg-sunken px-3 py-2 text-sm" data-testid="chat-guide-card">
    <div className="mb-1 font-semibold text-warn-ink"><span className="mr-1.5 font-mono text-caption text-ink-3">GUIDE //</span>{g.title}</div>
    <p className="text-ink-2">这个操作需要你在页面上亲自完成，我不能代你执行（{g.reason}）。</p>
    <div className="mt-2 flex justify-end"><Button size="sm" variant="primary" icon="arrow" onClick={() => { router.push(navigationTarget(g.href)); }}>去「{g.page}」</Button></div>
  </div>;
}

function DraftCard({ s, projects, canWrite, canApprove, reload, onTask }: { s: ChatSession; projects: ProjectDTO[]; canWrite: boolean; canApprove: boolean; reload: () => void; onTask: (id: string) => void }) {
  const d0 = s.draft ?? ({ scope: [], outOfScope: [], acceptance: [], targetPaths: [], classifiedBy: [], openQuestions: [] } as Draft); const pc = s.precheck;
  const [f, setF] = useState(() => ({ title: d0.title ?? '', goal: d0.goal ?? '', projectId: d0.projectId ?? '', risk: d0.risk ?? '', scope: d0.scope.join('\n'), outOfScope: d0.outOfScope.join('\n'), acceptance: d0.acceptance.join('\n') }));
  const [busy, setBusy] = useState(false); const [err, setErr] = useState(''); const [check, setCheck] = useState<Precheck | undefined>(pc);
  const dirty = useMemo(() => f.title !== (d0.title ?? '') || f.goal !== (d0.goal ?? '') || f.projectId !== (d0.projectId ?? '') || f.risk !== (d0.risk ?? '') || f.scope !== d0.scope.join('\n') || f.outOfScope !== d0.outOfScope.join('\n') || f.acceptance !== d0.acceptance.join('\n'), [f, d0]);
  const editable = canWrite && (s.status === 'open' || s.status === 'pending_approval'); const pending = s.status === 'pending_approval';
  const [plan, setPlan] = useState<any>(s.plan);
  useEffect(() => { setPlan(s.plan); }, [s.plan]); useEffect(() => { setCheck(s.precheck); }, [s.precheck]);
  const save = async () => {
    const r = await api<{ version: number; precheck: Precheck; plan?: any }>(`/api/chats/${s.id}/draft`, { method: 'PUT', body: { baseVersion: s.draft_version, patch: { title: f.title, goal: f.goal, projectId: f.projectId || null, risk: f.risk || null, scope: lines(f.scope), outOfScope: lines(f.outOfScope), acceptance: lines(f.acceptance) } } });
    setCheck(r.precheck); setPlan(r.plan); return r.version;
  };
  const run = async (fn: () => Promise<void>) => { setBusy(true); setErr(''); try { await fn(); } catch (e: any) { setErr(e.message); } finally { setBusy(false); } };
  const submit = (approve = false) => run(async () => {
    const v = dirty ? await save() : s.draft_version;
    const r = await api<{ taskId: string | null; created: boolean; pendingApproval: boolean }>(`/api/chats/${s.id}/draft/${approve ? 'approve' : 'confirm'}`, { method: 'POST', body: { baseVersion: v } });
    if (r.pendingApproval) { toast.success('流程调整含放松项，已提交管理员审批'); reload(); return; }
    toast.success(r.created ? '任务已创建，进入流水线' : '任务此前已创建'); reload(); if (r.taskId) onTask(r.taskId);
  });
  const vague = new Set(check?.vagueAcceptance ?? []); const acc = lines(f.acceptance);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF(x => ({ ...x, [k]: e.target.value }));
  return (
    <Card title="需求草稿" subtitle={<span>版本 v{s.draft_version}{d0.classification ? ` · ${CLASS_LABEL[d0.classification] ?? d0.classification}` : ''}{check?.risk ? ` · 风险 ${RISK_ZH[check.risk] ?? check.risk}` : ''}{d0.size ? ` · 规模 ${d0.size}` : ''}</span>}
      className="xl:max-h-[calc(100vh-180px)] xl:overflow-auto" data-testid="draft-card"
      actions={s.task_id ? <Button size="sm" variant="primary" onClick={() => onTask(s.task_id!)}>查看任务</Button> : undefined}>
      <div className="space-y-3">
        {err && <Callout tone="danger" onClose={() => setErr('')}>{err}</Callout>}
      {d0.presetId && <p className="text-xs text-ink-3">用户选择场景：{d0.presetId} · 实际计划按输入与项目策略校验。</p>}
        <Field label="标题">{id => <Input id={id} value={f.title} disabled={!editable} onChange={set('title')} />}</Field>
        <Field label="目标（谁 · 要什么 · 为什么）">{id => <Textarea id={id} rows={2} value={f.goal} disabled={!editable} onChange={set('goal')} />}</Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="目标项目">{id => <Select id={id} value={f.projectId} disabled={!editable} onChange={set('projectId')}><option value="">选择项目</option>{projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</Select>}</Field>
          <Field label="风险（只能调高）">{id => <Select id={id} value={f.risk} disabled={!editable} onChange={set('risk')}><option value="">按规则</option>{['low', 'mid', 'high'].map(r => <option key={r} value={r}>{RISK_ZH[r]}</option>)}</Select>}</Field>
        </div>
        <Field label="范围（每行一条）">{id => <Textarea id={id} rows={2} value={f.scope} disabled={!editable} onChange={set('scope')} />}</Field>
        <Field label="不包含（每行一条）">{id => <Textarea id={id} rows={2} value={f.outOfScope} disabled={!editable} onChange={set('outOfScope')} />}</Field>
        <Field label="验收标准（每行一条，必须可验证）">{id => <Textarea id={id} rows={4} value={f.acceptance} disabled={!editable} onChange={set('acceptance')} className={vague.size ? 'border-danger' : undefined} />}</Field>
        {acc.length > 0 && <ol className="space-y-1 text-sm" data-testid="acceptance-list">{acc.map((a, i) => <li key={i} className={cx('flex gap-1.5', vague.has(i) ? 'text-danger-ink' : 'text-ink-2')}><Icon name={vague.has(i) ? 'alert' : 'check'} size={14} className="mt-0.5 shrink-0" />{a}{vague.has(i) && <span className="text-caption">（不可验证）</span>}</li>)}</ol>}
        {d0.targetPaths.length > 0 && <div className="text-caption text-ink-3">预计改动：<span className="font-mono">{d0.targetPaths.join('  ')}</span></div>}
        {plan && !plan.error && <PlanPreview plan={plan} />}
        {pending && <Callout tone="warn">流程计划含放松项，等待工作区所有者 / 管理员审批；修改草稿会撤回申请。</Callout>}
        <div className={cx('border px-3 py-2 text-sm', check?.ok ? 'border-success/60 bg-success-soft text-success-ink' : 'border-warn/60 bg-warn-soft text-warn-ink')} data-testid="precheck">
          <div className="mb-1 font-semibold">{check?.ok ? '✓ 预检通过，可以确认' : '预检未通过'}{dirty && '（有未保存的修改）'}</div>
          {check?.problems.map((p, i) => <div key={i}>· {p}</div>)}
          {check?.warnings.map((p, i) => <div key={'w' + i} className="text-ink-2">⚠ {p}</div>)}
        </div>
        {editable && <div className="flex justify-end gap-2">
          <Button disabled={!dirty || busy} onClick={() => run(async () => { await save(); reload(); toast.success('草稿已保存'); })}>保存草稿</Button>
          {pending && canApprove ? <Button variant="primary" loading={busy} onClick={() => submit(true)} data-testid="chat-approve">批准并创建任务</Button>
            : <Button variant="primary" loading={busy} disabled={(!dirty && !check?.ok) || (pending && !dirty)} onClick={() => submit()} data-testid="chat-confirm">{plan?.needsApproval && !canApprove ? '提交审批' : '确认创建任务'}</Button>}
        </div>}
      </div>
    </Card>
  );
}

/** C2：流程计划预览（含自定义工作流节点、替换节点、极速通道与动态技能） */
export function PlanPreview({ plan }: { plan: any }) {
  if (!plan) return null;
  return <div className="border border-accent/50 bg-sunken px-3 py-2 text-sm" data-testid="plan-preview">
    <div className="mb-1 flex flex-wrap items-center justify-between gap-1 font-semibold text-accent-ink">
      <span>流程计划 · {plan.template}</span>
      <span className="flex flex-wrap gap-1">{(plan.labels?.length ? plan.labels : plan.classification ? [plan.classification] : []).map((l: string) => <Badge key={l} tone="info">{CLASS_LABEL[l] ?? l}</Badge>)}</span>
    </div>
    {plan.graph?.nodes?.length ? <><TaskFlow graph={plan.graph} compact /><PipelineGraph graph={plan.graph} compact /></> : <div className="flex flex-wrap gap-1">{(plan.stages ?? []).map((st: any) => <Badge key={st.type} tone={st.on ? (st.custom || st.changed ? 'accent' : 'primary') : 'neutral'}>{st.on ? (st.custom ? '✦ ' : st.replacedFrom ? '⇄ ' : '') : '⊘ '}{st.label ?? st.type}{st.checks?.length ? ` +${st.checks.length}` : ''}{st.skills?.length ? ` [${st.skills.length}技能]` : ''}</Badge>)}</div>}
    {(plan.adjustments ?? []).map((a: any, i: number) => <div key={i} className={cx('mt-1 text-caption', a.rejected ? 'text-danger-ink line-through' : a.relax ? 'text-warn-ink' : 'text-ink-2')}>{a.rejected ? '✕' : a.relax ? '⚠' : '＋'} {a.summary}{a.reason ? ` —— ${a.reason}` : ''}{a.rejected ? `（${a.rejected}）` : ''}{!a.rejected && a.warning && <span className="text-warn-ink">（{a.warning}）</span>}</div>)}
    {plan.needsApproval && <div className="mt-1 text-caption text-warn-ink">含放松项：确认后需要所有者 / 管理员审批</div>}
  </div>;
}

function Rich({ text }: { text: string }) {
  const parts = text.split(/(\*\*[^*\n]+\*\*|`[^`\n]+`)/g);
  return <>{parts.map((x, i) => x.startsWith('**') && x.endsWith('**') && x.length > 4 ? <strong key={i} className="text-primary-ink">{x.slice(2, -2)}</strong>
    : x.startsWith('`') && x.endsWith('`') && x.length > 2 ? <code key={i} className="bg-surface px-1 font-mono text-[0.92em] text-accent-ink">{x.slice(1, -1)}</code> : x)}</>;
}
