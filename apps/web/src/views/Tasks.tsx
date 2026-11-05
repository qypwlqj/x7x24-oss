import { AppLink } from '@/components/AppLink';
import { useEffect, useState } from 'react';
import { api, fmtTime, type TaskDTO } from '../api';
import { executionStateName } from '../catalog';
import { RISK_LABEL, RISK_TONE, statusName, statusTone } from '../labels';
import { DiscardedActions } from './TaskDetail';
import { Badge, Button, Card, Drawer, EmptyState, PageHeader, PageSkeleton, Table, type Column } from '../ui';
import { Select } from '../ui/Field';

/** 真实链接：Issue / PR / CI Run / Release（来自 GitHub，新标签页打开） */
export function LinkChips({ links }: { links?: Record<string, any> }) {
  const L = links ?? {}; const items: [string, string, string][] = [];
  if (L.issue?.url) items.push([`Issue #${L.issue.number}`, L.issue.url, 'issue']);
  if (L.pr?.url) items.push([`PR #${L.pr.number}`, L.pr.url, 'pr']);
  if (L.ci?.url) items.push(['CI Run', L.ci.url, 'ci']);
  if (L.release?.url) items.push([`Release ${L.release.tag}`, L.release.url, 'release']);
  if (!items.length) return <span className="text-ink-3">-</span>;
  return <span className="flex flex-wrap gap-1">{items.map(([l, u, k]) => <AppLink key={k} data-testid={`link-${k}`} href={u} target="_blank" rel="noreferrer noopener" onClick={(e) => e.stopPropagation()} className="border border-primary/40 px-1.5 py-0.5 font-mono text-caption text-primary-ink hover:bg-primary-soft">{l} ↗</AppLink>)}</span>;
}

export const taskColumns = (onChanged?: () => void): Column<TaskDTO>[] => [
  { key: 'title', header: '任务', render: (t) => <span className="font-medium text-ink">{t.title}</span>, className: 'max-w-[320px] truncate' },
  { key: 'project', header: '所属项目', render: t => <span className="text-ink-2">{t.projectName}</span> },
  { key: 'status', header: '当前阶段', render: (t) => <Badge tone={statusTone(t.currentStage)} dot>{statusName(t.currentStage)}</Badge> },
  { key: 'execution', header: '执行状态', render: t => <Badge tone={['FAILED', 'BLOCKED'].includes(t.executionState) ? 'danger' : t.executionState === 'RUNNING' ? 'primary' : 'neutral'}>{executionStateName(t.executionState)}</Badge> },
  { key: 'links', header: '真实链接', render: (t) => <LinkChips links={(t as any).links} /> },
  { key: 'risk', header: '风险', render: (t) => <Badge tone={RISK_TONE[t.riskLevel]}>{RISK_LABEL[t.riskLevel]}</Badge> },
  { key: 'it', header: '上线次数', align: 'right', render: (t) => <span className="tabular">{t.currentIteration}</span> },
  { key: 'loop', header: '返修/撤回/省钱', align: 'right', render: (t) => <span className="tabular text-ink-2">{t.loopCount}/{t.rollbackCount}/{t.degradeCount}</span> },
  { key: 'cost', header: '成本', align: 'right', render: (t) => <span className="tabular">${t.spentUsd}</span> },
  { key: 'at', header: '更新', render: (t) => <span className="tabular text-ink-3">{fmtTime(t.statusChangedAt)}</span> },
  ...(onChanged ? [{ key: 'ops', header: '操作', render: (t: TaskDTO) => t.status === 'DISCARDED' ? <DiscardedActions task={t} onChanged={onChanged} compact /> : <span className="text-ink-3">-</span> }] : []),
];

/** 总览页节点点击 → 抽屉里的任务列表 */
export function NodeDrawer({ node, label, params, onClose, onOpen }: { node: string; label: string; params: string; onClose: () => void; onOpen: (id: string) => void }) {
  const [tasks, setTasks] = useState<TaskDTO[] | null>(null);
  useEffect(() => { setTasks(null); api<TaskDTO[]>(`/api/tasks?node=${node}&limit=200&${params}`).then(setTasks).catch(() => setTasks([])); }, [node, params]);
  return (
    <Drawer title={`${label} · 任务列表`} onClose={onClose} width={720}>
      {tasks === null ? <PageSkeleton /> : <Table caption={`${label}节点任务`} columns={taskColumns().slice(0, 5)} rows={tasks} rowKey={(t) => t.id} onRowClick={(t) => onOpen(t.id)} empty="该节点暂无任务" dense />}
    </Drawer>
  );
}

export default function Tasks({ params, onOpen, onCreate, filters }: { params: string; onOpen: (id: string) => void; onCreate?: () => void; filters?: import('react').ReactNode }) {
  const [tasks, setTasks] = useState<TaskDTO[] | null>(null); const [status, setStatus] = useState('');
  const [rev, setRev] = useState(0);
  useEffect(() => { let active = true; const load = () => api<TaskDTO[]>(`/api/tasks?limit=300&${params}${status ? `&status=${status}` : ''}`).then(rows => { if (active) setTasks(rows); }).catch(() => { if (active) setTasks([]); }); load(); const timer = setInterval(load, 12000); return () => { active = false; clearInterval(timer); }; }, [params, status, rev]);
  return (
    <div>
      <PageHeader title="任务" desc="区间内创建的全部任务；点击一行查看时间线、过程记录和评审结果" actions={onCreate && <Button variant="primary" icon="plus" onClick={onCreate}>新增任务</Button>} />
      <div className="mb-gap flex flex-wrap items-center gap-2">{filters}
        <Select aria-label="按状态筛选" value={status} onChange={(e) => setStatus(e.target.value)} className="w-auto min-w-32 num">
          <option value="">全部状态</option>{['BACKLOG', 'PRD_REVIEW', 'PLANNING', 'SPEC', 'DESIGN', 'DEV', 'QA', 'ACCEPTANCE', 'DELIVERED', 'RELEASED', 'ANOMALY', 'DEGRADED', 'DISCARDED'].map((s) => <option key={s} value={s}>{statusName(s)}</option>)}
        </Select></div>
      <Card flush>{tasks === null ? <PageSkeleton /> : <Table caption="任务列表" columns={taskColumns(() => setRev(r => r + 1))} rows={tasks} rowKey={(t) => t.id} onRowClick={(t) => onOpen(t.id)} empty="暂无任务" />}</Card>
    </div>
  );
}
