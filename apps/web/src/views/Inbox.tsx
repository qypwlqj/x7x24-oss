import { AppLink } from '@/components/AppLink';
/** 待我处理：阻塞、等你处理（暂停/待审批）、超预算、未读通知（docs/command-center-v2-nav.md 2.1）。先用现有接口在前端聚合 */
import { useEffect, useState } from 'react';
import { api, fmtTime, type ProjectDTO, type TaskDTO } from '../api';
import type { NotificationInbox } from '../lib/notifications';
import { taskDot } from '../lib/commandCenter';
import { isViewer, type Role } from '../lib/nav';
import { scopeBudget, taskBudget, type WorkspaceData } from '../lib/workspaceData';
import { statusName } from '../labels';
import { Card, PageHeader } from '../ui';
import { DANGER_ZH, type TaskAction } from '../lib/taskActions';
import { isAdmin } from '../lib/nav';
import { NodeIcon, type NodeIconName } from '../ui/NodeIcon';
import { ACTION_TITLE, pendingActionHref, type PendingChatAction } from '../lib/chat';

export function inboxGroups(tasks: TaskDTO[], projects: ProjectDTO[], data: Pick<WorkspaceData, 'budget'>) {
  return {
    blocked: tasks.filter(t => taskDot(t) === 'blocked'),
    waiting: tasks.filter(t => taskDot(t) === 'waiting'),
    overTasks: tasks.filter(t => taskDot(t) !== 'done' && taskBudget(t).level === 'over'),
    overProjects: projects.filter(p => ['warn', 'over'].includes(scopeBudget(data.budget, 'project', p.id).level)),
  };
}
function Section({ icon, title, count, children }: { icon: NodeIconName; title: string; count: number; children: React.ReactNode }) {
  return <Card title={<span className="inline-flex items-center gap-2"><NodeIcon name={icon} size={16} />{title} <span className="tabular text-ink-3">{count}</span></span>}>{count === 0 ? <p className="text-sm text-ink-3">没有。</p> : <ul className="divide-y divide-dashed divide-line text-sm">{children}</ul>}</Card>;
}
const TaskRow = ({ t, note }: { t: TaskDTO; note: string }) => <li className="flex items-center gap-2 py-1.5"><AppLink className="min-w-0 flex-1 truncate underline" href={`/t/${encodeURIComponent(t.id)}/chat`}>{t.title}</AppLink><span className="shrink-0 text-caption text-ink-3">{t.projectName} · {statusName(t.currentStage ?? t.status)} · {note}</span></li>;

export default function Inbox({ data, projects, role }: { data: WorkspaceData; projects: ProjectDTO[]; role: Role }) {
  const [n, setN] = useState<NotificationInbox | null>(null);
  const [acts, setActs] = useState<TaskAction[]>([]);
  useEffect(() => { if (isAdmin(role)) api<TaskAction[]>('/api/task-actions/pending').then(setActs).catch(() => setActs([])); }, [role]);
  const [cards, setCards] = useState<PendingChatAction[]>([]);
  useEffect(() => { api<PendingChatAction[]>('/api/chats/pending-actions').then(setCards).catch(() => setCards([])); }, []);
  useEffect(() => { api<NotificationInbox>('/api/me/notifications').then(setN).catch(() => setN({ items: [], unread: 0 })); }, []);
  const g = inboxGroups(data.tasks ?? [], projects, data); const unread = (n?.items ?? []).filter(i => !i.read_at).slice(0, 10);
  return <div className="space-y-gap" data-testid="inbox">
    <PageHeader title="待我处理" desc={isViewer(role) ? '你是只读成员：这里列出需要关注的事，处理需要有权限的成员。' : '阻塞、等你确认、超预算的任务和项目，以及未读通知。点进去在会话里处理。'} />
    <div className="grid gap-gap lg:grid-cols-2">
      {isAdmin(role) && <Section icon="await-approval" title="等你确认的流程操作" count={acts.length}>{acts.map(a => <li key={a.id} className="flex items-center gap-2 py-1.5" data-testid="inbox-action"><AppLink className="min-w-0 flex-1 truncate underline" href={`/t/${encodeURIComponent(a.taskId)}/chat`}>{a.label} · {a.taskTitle ?? a.taskId}</AppLink><span className="shrink-0 text-caption text-ink-3">{DANGER_ZH[a.danger]} · @{a.proposedBy}</span></li>)}</Section>}
      <Section icon="await-approval" title="对话里待确认的操作" count={cards.length}>{cards.map(a => <li key={a.id} className="flex items-center gap-2 py-1.5" data-testid="inbox-chat-action"><AppLink className="min-w-0 flex-1 truncate underline" href={pendingActionHref(a)}>{ACTION_TITLE[a.kind] ?? a.kind} · {String(a.params?.name ?? a.params?.login ?? '')}</AppLink><span className="shrink-0 truncate text-caption text-ink-3">{a.session_title || '对话'} · {fmtTime(a.created_at)}</span></li>)}</Section>
      <Section icon="blocked" title="阻塞" count={g.blocked.length}>{g.blocked.map(t => <TaskRow key={t.id} t={t} note={fmtTime(t.statusChangedAt)} />)}</Section>
      <Section icon="await-approval" title="等你处理" count={g.waiting.length}>{g.waiting.map(t => <TaskRow key={t.id} t={t} note={t.paused ? '已暂停' : '待确认'} />)}</Section>
      <Section icon="budget" title="超预算" count={g.overTasks.length + g.overProjects.length}>
        {g.overProjects.map(p => { const b = scopeBudget(data.budget, 'project', p.id); return <li key={p.id} className="flex justify-between py-1.5"><AppLink className="underline" href={`/p/${encodeURIComponent(p.id)}/budget`}>项目 · {p.name}</AppLink><span className={b.level === 'over' ? 'font-bold text-danger-ink' : 'text-warn-ink'}>{b.level === 'over' ? '超额' : b.pct + '%'}</span></li>; })}
        {g.overTasks.map(t => <TaskRow key={t.id} t={t} note={`$${t.spentUsd.toFixed(2)} / $${t.budgetUsd}`} />)}
      </Section>
      <Section icon="node-enter" title="未读通知" count={n?.unread ?? 0}>{unread.map(i => <li key={i.id} className="flex justify-between gap-2 py-1.5"><AppLink className="min-w-0 truncate underline" href={i.task_id ? `/t/${encodeURIComponent(i.task_id)}/chat` : '/me/notifications'}>{i.subject}</AppLink><span className="shrink-0 text-caption text-ink-3">{fmtTime(i.created_at)}</span></li>)}</Section>
    </div>
  </div>;
}
