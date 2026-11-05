import { AppLink } from '@/components/AppLink';
/** 产品线 / 项目 / 任务 对象页：面包屑 + 标题行 + 页签（docs/command-center-v2-nav.md 第 3 节） */
import { lazy, Suspense, useEffect, useState } from 'react';
import { api, type Meta, type TaskDTO } from '../api';
import type { ChatSession } from '../lib/chat';
import { sessionForTask, taskDot } from '../lib/commandCenter';
import { TABS, fromSession, headerActions, isAdmin, rowMenu, type ObjRoute, type RowAction, type Role, type Tab } from '../lib/nav';
import { scopeBudget, taskBudget, type WorkspaceData } from '../lib/workspaceData';
import { Badge, Button, Callout, cx, toast } from '../ui';
import { NodeIcon } from '../ui/NodeIcon';
import { RowMenu } from '../components/WorkspaceTree';
import { GovernanceSummary, ScopeBudget, TaskBudget } from '../components/ScopeViews';
import { TaskBudgetCard } from '../components/TaskBudgetCard';
import { TaskInfo, TaskStatus } from '../components/TaskInfo';
import { DeliverySummary } from '../components/DeliverySummary';
import { Tip } from '../ui/InfoTip';
import { IconButton } from '../ui/Button';
import { NodeDetail, TaskAcceptance, TaskPrCi, TaskSession } from './CommandCenter';
import Chat, { BlankComposer } from './Chat';
import { BackToChat, ObjectChats } from './Chats';
import Tasks from './Tasks';
import TaskDetail from './TaskDetail';
import type { WorkspaceInfo } from '../lib/workspace';
const Governance = lazy(() => import('./Governance'));
const Project = lazy(() => import('./Project'));
const WorkspacePage = lazy(() => import('./Workspace'));

export interface ObjectViewProps { r: ObjRoute; role: Role; me?: string; meta: Meta | null; data: WorkspaceData; current: WorkspaceInfo | null; go: (to: string) => void; refresh: () => Promise<unknown>; onCreateTask: (projectId: string) => void }

export function ObjectHeader({ crumbs, title, badges, actions, tools, menu, tabs, active, base, tabBadge }: { crumbs: { label: string; href?: string }[]; title: string; badges?: React.ReactNode; actions?: { a: RowAction; run: () => void }[]; tools?: React.ReactNode; menu?: RowAction[]; tabs: Tab[]; active: string; base: string; tabBadge?: Record<string, React.ReactNode> }) {
  return <div className="border-2 border-b-0 border-ink bg-raised" data-testid="object-header">
    <div className="break-words border-b border-line px-4 py-1.5 text-caption text-ink-3">{crumbs.map((c, i) => <span key={i}>{i > 0 && ' / '}{c.href ? <AppLink className="hover:underline" href={c.href}>{c.label}</AppLink> : <b className="text-ink">{c.label}</b>}</span>)}</div>
    <div className="flex flex-wrap items-center gap-2 px-4 pt-3">
      <Tip content={title}><h1 className="min-w-0 basis-full truncate text-lg font-bold sm:flex-1 sm:basis-auto">{title}</h1></Tip>{badges}
      {tools}
      {actions?.map(({ a, run }) => <button key={a.id} type="button" onClick={run} data-edit="1" className="border-2 border-ink bg-raised px-3 py-1 text-sm font-semibold shadow-[2px_2px_0_rgb(var(--c-line-strong))] hover:bg-sunken">{a.label}</button>)}
      {menu && <RowMenu label={title} items={menu} className="border-2 border-ink px-2 py-1" />}
    </div>
    <nav aria-label="页签" className="mt-2 flex gap-0.5 overflow-x-auto px-3" data-testid="object-tabs">
      {tabs.map(t => <AppLink key={t.id} href={`${base}/${t.id}`} aria-current={t.id === active ? 'page' : undefined}
        className={cx('-mb-[2px] flex shrink-0 items-center gap-1.5 whitespace-nowrap border-2 border-b-0 px-3 py-1.5 text-sm', t.id === active ? 'border-ink bg-canvas font-bold' : 'border-transparent text-ink-2 hover:text-ink')}>
        <NodeIcon name={t.icon} size={14} />{t.label}{tabBadge?.[t.id]}</AppLink>)}
    </nav>
  </div>;
}
const Pill = ({ tone, children }: { tone: 'warn' | 'over' | 'info'; children: React.ReactNode }) => <span className={cx('rounded-full px-1.5 text-[10px] font-bold', tone === 'over' ? 'bg-danger text-white' : tone === 'warn' ? 'bg-warn text-white' : 'bg-ink text-ink-inverse')}>{children}</span>;
const budgetPill = (b: { level: string; pct: number }) => b.level === 'over' ? <Pill tone="over">超</Pill> : b.level === 'warn' ? <Pill tone="warn">{b.pct}%</Pill> : null;
const Body = ({ children }: { children: React.ReactNode }) => <div className="border-2 border-ink bg-canvas p-3 sm:p-4" data-testid="object-body"><Suspense fallback={<p className="text-ink-3">加载中…</p>}>{children}</Suspense></div>;

export default function ObjectView({ r, role, me, meta, data, current, go, refresh, onCreateTask }: ObjectViewProps) {
  const taskId = r.kind === 'task' ? r.id : null;
  const task = taskId ? data.tasks?.find(t => t.id === taskId) : undefined;
  const lookup = taskId ? data.taskLookups[taskId] : undefined;
  const hasTask = !!task;
  useEffect(() => { if (taskId && !hasTask && !lookup) void data.loadTask(taskId); }, [taskId, hasTask, lookup, data.loadTask]);
  const [chats, setChats] = useState<ChatSession[]>([]);
  const loadChats = () => api<ChatSession[]>('/api/chats').then(setChats).catch(() => {});
  useEffect(() => { if (r.kind === 'task') loadChats(); }, [r.kind, r.id]);
  const fromSid = fromSession(location.pathname + location.search); const back = fromSid ? <BackToChat sessionId={fromSid} /> : null;
  const canWrite = role !== 'viewer'; const lines = meta?.lines ?? []; const projects = meta?.projects ?? [];
  const toggleTask = async (t: TaskDTO, resume: boolean) => { try {
    if (resume) await api(`/api/tasks/${t.id}/resume`, { method: 'POST', body: {} });
    else await api(`/api/tasks/${t.id}/pause`, { method: 'PUT', body: { paused: true } });
    toast.success(resume ? '已继续' : '已暂停'); data.reload();
  } catch (e: any) { toast.error(e.message); } };

  if (r.kind === 'line') {
    const l = lines.find(x => x.id === r.id); if (!meta) return <p className="text-ink-3">加载中…</p>; if (!l) return <Callout tone="warn">找不到这条产品线。</Callout>;
    const lb = scopeBudget(data.budget, 'line', l.id); const ps = projects.filter(p => p.productLineId === l.id);
    return <div data-testid="object-line">{back}<ObjectHeader crumbs={[{ label: '产品线' }, { label: l.name }]} title={l.name} badges={<Badge tone="neutral">自治 {l.autonomy}</Badge>}
      actions={headerActions('line', role).map(a => ({ a, run: () => go(`new?line=${encodeURIComponent(l.id)}`) }))} menu={rowMenu('line', l.id, role)} tabs={TABS.line} active={r.tab} base={`/l/${encodeURIComponent(l.id)}`} tabBadge={{ budget: budgetPill(lb), overview: <Pill tone="info">{ps.length}</Pill> }} />
      <Body>
        {r.tab === 'overview' && <ul className="grid gap-gap sm:grid-cols-2 xl:grid-cols-3">{ps.map(p => { const all = (data.tasks ?? []).filter(t => t.projectId === p.id); const pb = scopeBudget(data.budget, 'project', p.id);
          return <li key={p.id} className="border-2 border-ink bg-raised p-3 shadow-[3px_3px_0_rgb(var(--c-line-strong))]"><AppLink className="font-bold underline" href={`/p/${encodeURIComponent(p.id)}/chat`}>{p.name}</AppLink>
            <div className="mt-1 text-caption text-ink-3">{p.config?.repo ?? '未绑定仓库'}</div>
            <div className="mt-2 flex flex-wrap gap-2 text-caption"><span>任务 {all.length}</span><span className="text-danger-ink">阻塞 {all.filter(t => taskDot(t) === 'blocked').length}</span><span>预算 {pb.limit ? pb.pct + '%' : '未设'}</span></div></li>; })}
          {ps.length === 0 && <li className="text-ink-3">这条产品线还没有项目。</li>}</ul>}
        {r.tab === 'chat' && <ObjectChats kind="line" id={l.id} composer={<BlankComposer context={{ productLineId: l.id }} onCreated={id => go(`s/${id}`)} hint={<>产品线：{l.name} · 会改动数据的操作都会先给你一张确认卡</>} />} />}
        {r.tab === 'budget' && <ScopeBudget kind="line" id={l.id} budget={data.budget} role={role} />}
        {r.tab === 'governance' && <GovernanceSummary line={l} role={role} meta={meta} editor={<Governance meta={{ ...meta, lines: [l] }} onChanged={refresh} />} />}
        {r.tab === 'members' && <WorkspacePage current={current} me={me} />}
      </Body></div>;
  }

  if (r.kind === 'project') {
    const p = projects.find(x => x.id === r.id); if (!meta) return <p className="text-ink-3">加载中…</p>; if (!p) return <Callout tone="warn">找不到这个项目。</Callout>;
    const l = lines.find(x => x.id === p.productLineId); const pb = scopeBudget(data.budget, 'project', p.id); const all = (data.tasks ?? []).filter(t => t.projectId === p.id);
    return <div data-testid="object-project">{back}<ObjectHeader crumbs={[{ label: l?.name ?? '产品线', href: l ? `/l/${encodeURIComponent(l.id)}/overview` : undefined }, { label: p.name }]} title={p.name}
      badges={<><Badge tone="neutral">{p.config?.repo ?? '未绑定仓库'}{p.config?.baseBranch ? ` · ${p.config.baseBranch}` : ''}</Badge>{p.paused && <Badge tone="warn">已暂停</Badge>}</>}
      actions={headerActions('project', role).map(a => ({ a, run: () => onCreateTask(p.id) }))} menu={rowMenu('project', p.id, role)} tabs={TABS.project} active={r.tab} base={`/p/${encodeURIComponent(p.id)}`}
      tabBadge={{ budget: budgetPill(pb), tasks: <Pill tone={all.some(t => taskDot(t) === 'blocked') ? 'over' : 'info'}>{all.length}</Pill> }} />
      <Body>
        {r.tab === 'chat' && <ObjectChats kind="project" id={p.id} composer={<Chat embedded canAsk me={me} role={role} wsName={current?.name} lines={lines} projects={[p]} canWrite={canWrite} canApprove={isAdmin(role)} onCatalogChanged={refresh} onOpen={id => go(id ? `s/${id}` : `p/${p.id}/chat`)} onTask={id => go(`t/${id}/chat`)} />} />}
        {r.tab === 'tasks' && <Tasks params={`projectId=${encodeURIComponent(p.id)}`} onOpen={id => go(`t/${id}/chat`)} onCreate={canWrite ? () => onCreateTask(p.id) : undefined} />}
        {r.tab === 'budget' && <ScopeBudget kind="project" id={p.id} budget={data.budget} role={role} tasks={data.tasks} />}
        {r.tab === 'governance' && <GovernanceSummary line={l} role={role} meta={meta} editor={l && <Governance meta={{ ...meta, lines: [l] }} onChanged={refresh} />} />}
        {r.tab === 'detail' && <Project id={p.id} canEdit={isAdmin(role)} today={meta.now.slice(0, 10)} onBack={id => go(`l/${id}/overview`)} onTask={id => go(`t/${id}/chat`)} onCreate={() => onCreateTask(p.id)} onChanged={refresh} />}
        {r.tab === 'members' && <WorkspacePage current={current} me={me} />}
      </Body></div>;
  }

  const t = task;
  if (!t) {
    if (lookup?.status === 'not_found') return <Callout tone="warn">找不到这个任务，可能已被删除或不在当前工作区。</Callout>;
    if (lookup?.status === 'error') return <Callout tone="danger">任务加载失败：{lookup.message}<Button className="ml-2" size="sm" onClick={() => { void data.loadTask(r.id); }}>重试</Button></Callout>;
    return <p className="text-ink-3" role="status" aria-busy="true">加载中…</p>;
  }
  const tb = taskBudget(t); const tab = r.tab === 'node' ? 'chat' : r.tab;
  return <div data-testid="object-task">{back}<ObjectHeader crumbs={[{ label: t.productLineName }, { label: t.projectName, href: `/p/${encodeURIComponent(t.projectId)}/tasks` }]} title={t.title}
    tools={<div className="flex max-w-full shrink-0 flex-wrap items-center gap-1"><TaskStatus task={t} />{headerActions('task', role, t).map(a => a.id === 'pause' ? <IconButton key={a.id} label={a.label} icon="pause" onClick={() => toggleTask(t, false)} data-edit="1" /> : <Button key={a.id} size="sm" onClick={() => toggleTask(t, true)} data-edit="1">{a.label}</Button>)}<TaskInfo task={t} /></div>}
    menu={rowMenu('task', t.id, role, t).filter(a => a.href)} tabs={TABS.task} active={tab} base={`/t/${encodeURIComponent(t.id)}`} tabBadge={{ budget: budgetPill(tb) }} />
    <Body>
      {r.tab === 'chat' && <TaskSession key={t.id} task={t} session={sessionForTask(chats, t.id)} role={role} lines={lines} projects={projects} canWrite={canWrite} onCatalogChanged={refresh} reload={loadChats} />}
      {r.tab === 'node' && <NodeDetail key={r.rest[0]} task={t} nodeId={decodeURIComponent(r.rest[0] ?? '')} />}
      {r.tab === 'pr' && <TaskPrCi task={t} />}
      {r.tab === 'accept' && <TaskAcceptance task={t} />}
      {r.tab === 'delivery' && <DeliverySummary task={t} showEmpty />}
      {r.tab === 'detail' && <TaskDetail key={t.id} id={t.id} initialTab={r.rest[0]} focus={r.rest[1] ?? null} canRevise={isAdmin(role)} canManageBudget={isAdmin(current?.role ?? 'viewer')} onBack={() => go(`t/${t.id}/chat`)} />}
      {r.tab === 'budget' && <TaskBudget task={t} role={role} admin={<TaskBudgetCard t={t} canManageBudget={isAdmin(current?.role ?? 'viewer')} onChanged={async () => data.reload()} />} />}
    </Body></div>;
}
