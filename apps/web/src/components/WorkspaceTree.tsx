import { useRouter } from 'next/navigation';
import { AppLink } from '@/components/AppLink';
/** 侧边栏里的工作区树：产品线 → 项目 → 任务；行右侧最多 2 个小图标 + ⋯ 菜单（docs/command-center-v2-nav.md 2.2–2.4） */
import { useMemo, useState } from 'react';
import * as Popover from '@radix-ui/react-popover';
import { type LineMeta, type ProjectDTO, type TaskDTO } from '../api';
import { canDeleteChat, deleteChatCopy, type ChatSession } from '../lib/chat';
import { api } from '../api';
import { RecentConversation } from './RecentConversation';
import { toast, useConfirm, usePrompt } from '../ui';
import { buildTree, taskDot, DOT_ZH, type Dot } from '../lib/commandCenter';
import { anchorHref, anchorOf, recentConversationEntries, newSessionHref, sessionCount, rowBadges, rowMenu, type ObjKind, type RowAction, type RowBadge, type Role } from '../lib/nav';
import { scopeBudget, taskBudget, type BudgetData } from '../lib/workspaceData';
import { cx } from '../ui';
import { NodeIcon } from '../ui/NodeIcon';

const DOT_CLS: Record<Dot, string> = { blocked: 'bg-danger', waiting: 'bg-warn', running: 'bg-success', idle: 'border border-line-strong', done: '' };
export function BadgeIcon({ b }: { b: RowBadge }) {
  if (b.kind === 'dot') return b.dot === 'done' ? <NodeIcon name="done" size={12} className="text-success" aria-label={DOT_ZH.done} /> : <span role="img" aria-label={DOT_ZH[b.dot]} className={cx('inline-block h-2 w-2 rounded-full', DOT_CLS[b.dot])} />;
  if (b.kind === 'blocked') return <span className="inline-flex items-center gap-0.5 text-[10px] font-bold text-danger-ink" aria-label={`阻塞${b.count && b.count > 1 ? ' ' + b.count : ''}`} data-badge="blocked"><NodeIcon name="blocked" size={12} />{b.count && b.count > 1 ? b.count : ''}</span>;
  if (b.kind === 'waiting') return <span className="inline-flex items-center gap-0.5 text-[10px] font-bold text-warn-ink" aria-label="等你处理" data-badge="waiting"><NodeIcon name="await-approval" size={12} />{b.count && b.count > 1 ? b.count : ''}</span>;
  return <span className={cx('inline-flex items-center gap-0.5 text-[10px] tabular', b.level === 'over' ? 'font-bold text-danger-ink' : 'text-warn-ink')} aria-label={`预算 ${b.level === 'over' ? '超额' : b.pct + '%'}`} data-badge="budget"><NodeIcon name="budget" size={12} />{b.level === 'over' ? '超' : `${b.pct}%`}</span>;
}

export function RowMenu({ items, label, onAction, className }: { items: RowAction[]; label: string; onAction?: (a: RowAction) => void; className?: string }) {
  if (!items.length) return null;
  return <Popover.Root><Popover.Trigger aria-label={`${label} 更多操作`} className={cx('px-1 font-bold leading-none text-ink-3 hover:text-ink focus-visible:opacity-100', className)} data-testid="row-menu">⋯</Popover.Trigger>
    <Popover.Portal><Popover.Content side="right" align="start" sideOffset={4} className="z-50 min-w-40 border-2 border-ink bg-raised py-1 text-sm shadow-[4px_4px_0_rgb(var(--c-line-strong))]">
      {items.map(a => a.href ? <AppLink key={a.id} href={a.href} className="block px-3 py-1.5 hover:bg-sunken" data-edit={a.edit ? '1' : undefined}>{a.label}</AppLink>
        : <Popover.Close key={a.id} asChild><button type="button" className="block w-full px-3 py-1.5 text-left hover:bg-sunken" data-edit={a.edit ? '1' : undefined} onClick={() => onAction?.(a)}>{a.label}</button></Popover.Close>)}
    </Popover.Content></Popover.Portal></Popover.Root>;
}

export interface TreeProps { lines: LineMeta[]; projects: ProjectDTO[]; tasks: TaskDTO[] | null; budget: BudgetData | null; role: Role; me?: string; active?: { kind: ObjKind; id: string } | null; onTaskAction?: (t: TaskDTO, a: RowAction) => void; onNavigate?: () => void; sessions?: ChatSession[] | null; activeSession?: string; onSessionsChanged?: () => void }
export function WorkspaceTree({ lines, projects, tasks, budget, role, me, active, onTaskAction, onNavigate, sessions, activeSession, onSessionsChanged }: TreeProps) {
  const router = useRouter();
  const recent = useMemo(() => recentConversationEntries(sessions ?? [], 10), [sessions]);
  const [recentOpen, setRecentOpen] = useState(() => { try { return localStorage.getItem('x7_recent_folded') !== '1'; } catch { return true; } });
  const toggleRecent = () => { const n = !recentOpen; setRecentOpen(n); try { localStorage.setItem('x7_recent_folded', n ? '0' : '1'); } catch { /* ignore */ } };
  const confirm = useConfirm(); const ask = usePrompt(); const manager = role === 'owner' || role === 'admin';
  const sessionAction = async (c: ChatSession, a: RowAction) => {
    try {
      if (a.id === 'rename') { const t = await ask({ title: '重命名对话', label: '对话标题', defaultValue: c.title ?? '', confirmText: '保存' }); if (!t || !t.trim()) return; await api(`/api/chats/${c.id}/title`, { method: 'PUT', body: { title: t.trim() } }); toast.success('已改名'); }
      else if (a.id === 'delete') { const d = deleteChatCopy(c); if (!await confirm({ ...d, confirmText: '删除', destructive: true })) return; await api(`/api/chats/${c.id}`, { method: 'DELETE' }); toast.success('已删除'); if (c.id === activeSession) router.push('/new'); }
      onSessionsChanged?.();
    } catch (e: any) { toast.error(e.message); }
  };
  const sessionMenu = (c: ChatSession): RowAction[] => { const h = anchorHref(c); return [...(h ? [{ id: 'open-anchor', label: '打开所属对象', href: h }] : []), ...(role !== 'viewer' ? [{ id: 'rename', label: '重命名', edit: true }] : []), ...(canDeleteChat(c, me, manager) ? [{ id: 'delete', label: '删除', edit: true }] : [])]; };
  const [q, setQ] = useState(''); const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [folded, setFolded] = useState<Set<string>>(() => { try { return new Set(JSON.parse(localStorage.getItem('x7_tree_folded') ?? '[]')); } catch { return new Set(); } });
  const toggleFold = (k: string) => { const n = new Set(folded); n.has(k) ? n.delete(k) : n.add(k); setFolded(n); try { localStorage.setItem('x7_tree_folded', JSON.stringify([...n])); } catch { /* ignore */ } };
  const shown = useMemo(() => { const s = q.trim().toLowerCase(); return s ? (tasks ?? []).filter(t => t.title.toLowerCase().includes(s) || t.id.toLowerCase().includes(s)) : tasks ?? []; }, [tasks, q]);
  const tree = useMemo(() => buildTree(lines, q ? projects.filter(p => p.name.toLowerCase().includes(q.toLowerCase()) || shown.some(t => t.projectId === p.id)) : projects, shown, 5, expanded), [lines, projects, shown, expanded, q]);
  const isOn = (k: ObjKind, id: string) => active?.kind === k && active.id === id;
  const rowCls = (on: boolean) => cx('group flex items-center gap-1.5 rounded-sm px-1.5 py-1', on ? 'bg-ink text-ink-inverse' : 'hover:bg-sunken');
  return <div className="flex min-h-0 flex-1 flex-col border-t-2 border-dashed border-line-strong" data-testid="workspace-tree">
    <div className="flex items-center justify-between px-3 pt-2 text-caption text-ink-3"><span className="label-caps">工作区</span></div>
    <div className="px-3 py-1.5"><input aria-label="搜索项目或任务" placeholder="搜索项目 / 任务…" value={q} onChange={e => setQ(e.target.value)} className="w-full border-2 border-ink bg-surface px-2 py-1 text-xs outline-none focus:border-primary" /></div>
    <nav aria-label="工作区" className="min-h-0 flex-1 overflow-y-auto px-2 pb-3 text-sm" onClick={e => { if ((e.target as HTMLElement).closest('a')) onNavigate?.(); }}>
      {tasks === null && <p className="px-2 text-caption text-ink-3">加载中…</p>}
      {tasks !== null && projects.length === 0 && !q && <div className="px-2 py-3 text-caption text-ink-3" data-testid="workspace-empty"><p>还没有项目，从一句话开始。</p><AppLink href={newSessionHref(lines[0]?.id, role === 'viewer' ? undefined : 'project')} className="mt-2 inline-block text-sm text-ink underline">{role === 'viewer' ? '新对话，了解工作区' : '新对话，创建项目'} →</AppLink></div>}
      {tree.map(l => { const lb = l.id ? scopeBudget(budget, 'line', l.id) : null; const lBlocked = l.projects.reduce((n, p) => n + p.tasks.filter(t => taskDot(t) === 'blocked').length, 0);
        return <div key={l.id || 'none'} className="mt-2">
          <div className={rowCls(isOn('line', l.id))}>
            <button type="button" aria-expanded={!folded.has('l:' + l.id)} aria-label={`${folded.has('l:' + l.id) ? '展开' : '收起'}产品线 ${l.name}`} onClick={() => toggleFold('l:' + l.id)} className={cx('w-3 text-[10px] text-ink-3 transition-transform', !folded.has('l:' + l.id) && 'rotate-90')}>▸</button>
            {l.id ? <AppLink href={`/l/${encodeURIComponent(l.id)}/overview`} className="min-w-0 flex-1 truncate text-caption text-ink-3 group-[.bg-ink]:text-ink-inverse" data-testid="tree-line">产品线 · {l.name}</AppLink> : <span className="flex-1 text-caption text-ink-3">{l.name}</span>}
            {rowBadges({ blocked: lBlocked, budget: lb ?? undefined }).map((b, i) => <BadgeIcon key={i} b={b} />)}
            {l.id && <RowMenu label={l.name} items={rowMenu('line', l.id, role)} className="opacity-0 group-hover:opacity-100 [@media(hover:none)]:opacity-100" />}
          </div>
          {!folded.has('l:' + l.id) && l.projects.map(({ project: p, tasks: ts, hidden }) => { const pb = scopeBudget(budget, 'project', p.id); const all = (tasks ?? []).filter(t => t.projectId === p.id);
            return <div key={p.id}>
              <div className={cx(rowCls(isOn('project', p.id)), 'ml-2 font-semibold')} data-testid="tree-project-row">
                <button type="button" aria-expanded={!folded.has(p.id)} aria-label={`${folded.has(p.id) ? '展开' : '收起'}项目 ${p.name}`} onClick={() => toggleFold(p.id)} className={cx('w-3 text-[10px] text-ink-3 transition-transform', !folded.has(p.id) && 'rotate-90')}>▸</button>
                <AppLink href={`/p/${encodeURIComponent(p.id)}/chat`} className="min-w-0 flex-1 truncate" data-testid="tree-project">{p.name}</AppLink>
                {(() => { const n = sessionCount(sessions ?? [], 'project', p.id); return n > 0 ? <AppLink href={`/p/${encodeURIComponent(p.id)}/chat`} aria-label={`${n} 个对话`} title={`${n} 个对话`} className="shrink-0 text-[10px] tabular text-ink-3 group-[.bg-ink]:text-ink-inverse" data-testid="tree-project-chats">💬{n}</AppLink> : null; })()}
                {rowBadges({ blocked: all.filter(t => taskDot(t) === 'blocked').length, waiting: all.filter(t => taskDot(t) === 'waiting').length, budget: pb }).map((b, i) => <BadgeIcon key={i} b={b} />)}
                <RowMenu label={p.name} items={rowMenu('project', p.id, role)} className={isOn('project', p.id) ? 'text-ink-inverse' : 'opacity-0 group-hover:opacity-100 [@media(hover:none)]:opacity-100'} />
              </div>
              {!folded.has(p.id) && <ul className="ml-5 border-l-2 border-dashed border-line pl-1.5">
                {ts.length === 0 && <li className="px-1.5 py-0.5 text-caption text-ink-3">还没有任务{role !== 'viewer' && <AppLink className="mt-1 block text-ink underline" href={newSessionHref(p.productLineId, 'task', p.id)}>新对话，描述任务 →</AppLink>}</li>}
                {ts.map(t => { const on = isOn('task', t.id); const d = taskDot(t);
                  return <li key={t.id} className={rowCls(on)} data-testid="tree-task">
                    <AppLink href={`/t/${encodeURIComponent(t.id)}/chat`} aria-current={on ? 'page' : undefined} className="min-w-0 flex-1 truncate">{t.title}</AppLink>
                    {rowBadges({ blocked: d === 'blocked' ? 1 : 0, waiting: d === 'waiting' ? 1 : 0, budget: taskBudget(t), dot: d }).map((b, i) => <BadgeIcon key={i} b={b} />)}
                    <RowMenu label={t.title} items={rowMenu('task', t.id, role, { paused: t.paused })} onAction={a => onTaskAction?.(t, a)} className={on ? 'text-ink-inverse' : 'opacity-0 group-hover:opacity-100 [@media(hover:none)]:opacity-100'} />
                  </li>; })}
                {hidden > 0 && <li><button type="button" className="px-1.5 py-0.5 text-caption text-accent-ink underline" onClick={() => setExpanded(new Set([...expanded, p.id]))}>显示更多（{hidden}）</button></li>}
              </ul>}
            </div>; })}
        </div>; })}
    </nav>
    <div className="border-t-2 border-dashed border-line-strong px-2 py-1.5 text-sm" data-testid="recent-sessions">
      <button type="button" aria-expanded={recentOpen} onClick={toggleRecent} className="flex w-full items-center gap-1.5 px-1.5 py-1 text-caption text-ink-3 hover:text-ink">
        <span className={cx('w-3 text-[10px] transition-transform', recentOpen && 'rotate-90')}>▸</span><span className="label-caps">最近对话</span><span className="ml-auto tabular">{recent.length}</span></button>
      {recentOpen && <ul className="max-h-64 overflow-y-auto" onClick={e => { if ((e.target as HTMLElement).closest('a')) onNavigate?.(); }}>
        {recent.length === 0 && <li className="px-1.5 py-0.5 text-caption text-ink-3">还没有对话</li>}
        {recent.map(c => { const a = anchorOf(c); const task = a.kind === 'task' ? tasks?.find(t => t.id === a.id) : undefined;
          const project = a.kind === 'project' ? projects.find(p => p.id === a.id) : task ? projects.find(p => p.id === task.projectId) : undefined;
          const line = a.kind === 'line' ? lines.find(l => l.id === a.id) : project ? lines.find(l => l.id === project.productLineId) : undefined;
          const context = task ? `${task.productLineName} / ${task.projectName}` : project ? `${line?.name ?? '产品线'} / ${project.name}` : line?.name ?? a.name ?? '未归属项目';
          const on = c.id === activeSession || a.kind === 'task' && active?.kind === 'task' && active.id === a.id;
          return <RecentConversation key={c.id} session={c} task={task} context={context} active={on} menu={<RowMenu label={c.title || '新对话'} items={sessionMenu(c)} onAction={action => sessionAction(c, action)} className={on ? 'text-ink-inverse' : 'opacity-0 group-hover:opacity-100 group-focus-within:opacity-100 [@media(hover:none)]:opacity-100'} />} />;
        })}
        {(sessions ?? []).length > 0 && <li><AppLink href="/chats" className="block px-1.5 py-1 text-caption text-accent-ink underline" data-testid="all-chats">查看全部 →</AppLink></li>}
      </ul>}
    </div>
  </div>;
}
