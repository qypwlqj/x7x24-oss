import { AppLink } from '@/components/AppLink';
/** 对象页里的范围视图：预算（产品线/项目/任务）、治理（只读摘要 / 管理员编辑）。只读成员看不到任何会改数据的按钮 */
import { useEffect, useState } from 'react';
import { api, type LineMeta, type Meta, type TaskDTO } from '../api';
import { budgetLevel, budgetPct, isAdmin, isViewer, type Role } from '../lib/nav';
import type { BudgetData } from '../lib/workspaceData';
import { Card, cx } from '../ui';

const fmt = (v: number | null | undefined, pre = '') => v == null ? '不限' : `${pre}${Number(v).toFixed(pre ? 2 : 0)}`;
export function Bar({ used, limit, label, pre = '' }: { used: number; limit: number | null | undefined; label: string; pre?: string }) {
  const lv = budgetLevel(used, limit); const pct = Math.min(100, budgetPct(used, limit));
  return <div className="text-sm" data-level={lv}>
    <div className="mb-1 flex justify-between"><span>{label}</span><span className={cx('tabular', lv === 'over' && 'font-bold text-danger-ink', lv === 'warn' && 'text-warn-ink')}>{pre}{Number(used).toFixed(pre ? 2 : 0)} / {fmt(limit, pre)}{limit ? ` · ${budgetPct(used, limit)}%` : ''}</span></div>
    <div className="h-2.5 border-2 border-ink bg-surface"><div className={cx('h-full', lv === 'over' ? 'bg-danger' : lv === 'warn' ? 'bg-warn' : 'bg-ink')} style={{ width: `${limit ? pct : 0}%` }} /></div>
  </div>;
}
export function EditHint({ role, href, label }: { role: Role; href: string; label: string }) {
  if (isViewer(role)) return <p className="text-caption text-ink-3">只读成员只能查看。</p>;
  if (!isAdmin(role)) return <p className="text-caption text-ink-3">调整需要工作区管理员。</p>;
  return <AppLink href={href} data-edit="1" className="inline-block border-2 border-ink bg-raised px-3 py-1 text-sm font-semibold shadow-[2px_2px_0_rgb(var(--c-line-strong))] hover:bg-sunken">{label}</AppLink>;
}

export function ScopeBudget({ kind, id, budget, role, tasks }: { kind: 'line' | 'project'; id: string; budget: BudgetData | null; role: Role; tasks?: TaskDTO[] | null }) {
  const s = (kind === 'line' ? budget?.lines : budget?.projects)?.find(x => x.id === id);
  const [top, setTop] = useState<any[] | null>(null);
  useEffect(() => { if (kind === 'project') api<any[]>(`/api/budget/projects/${encodeURIComponent(id)}/tasks`).then(setTop).catch(() => setTop(null)); }, [kind, id]);
  const children = kind === 'line' ? (budget?.projects ?? []).filter(p => p.productLineId === id) : null;
  const rows = kind === 'project' ? (top ?? (tasks ?? []).filter(t => t.projectId === id).map(t => ({ id: t.id, title: t.title, status: t.status, spent_usd: t.spentUsd, budget_usd: t.budgetUsd }))) : null;
  return <div className="grid gap-gap lg:grid-cols-2" data-testid="scope-budget">
    <Card title={`今日${kind === 'line' ? '产品线' : '项目'}预算`}>
      {!s ? <p className="text-sm text-ink-3">没有预算数据。</p> : <div className="space-y-3">
        <Bar label="金额" used={s.daily.usd} limit={s.limits.dailyUsd} pre="$" /><Bar label="Token" used={s.daily.tokens} limit={s.limits.dailyTokens} /><Bar label="CI 分钟" used={s.daily.ci} limit={s.limits.dailyCiMinutes} />
        {s.status?.reason && <p className="text-caption text-danger-ink">{s.status.reason}</p>}
      </div>}
      <div className="mt-3"><EditHint role={role} href="/admin/budget" label="调整额度 →" /></div>
    </Card>
    <Card title={kind === 'line' ? '按项目' : '按任务花费'}>
      <ul className="divide-y divide-dashed divide-line text-sm">
        {(children ?? []).map(p => <li key={p.id} className="flex justify-between py-1.5"><AppLink className="underline" href={`/p/${encodeURIComponent(p.id)}/budget`}>{p.name}</AppLink><span className={cx('tabular', budgetLevel(p.daily.usd, p.limits.dailyUsd) === 'over' && 'font-bold text-danger-ink')}>${Number(p.daily.usd).toFixed(2)} / {fmt(p.limits.dailyUsd, '$')}</span></li>)}
        {(rows ?? []).map(t => { const lv = budgetLevel(t.spent_usd, t.budget_usd); return <li key={t.id} className="flex justify-between gap-2 py-1.5"><AppLink className="min-w-0 truncate underline" href={`/t/${encodeURIComponent(t.id)}/budget`}>{t.title}</AppLink><span className={cx('shrink-0 tabular', lv === 'over' && 'font-bold text-danger-ink', lv === 'warn' && 'text-warn-ink')}>${Number(t.spent_usd ?? 0).toFixed(2)} / {fmt(t.budget_usd, '$')}{lv === 'over' ? ' · 超额' : ''}</span></li>; })}
        {children?.length === 0 && <li className="py-1.5 text-ink-3">还没有项目</li>}{rows?.length === 0 && <li className="py-1.5 text-ink-3">还没有花费</li>}
      </ul>
    </Card>
  </div>;
}

export function TaskBudget({ task, role, admin }: { task: TaskDTO; role: Role; admin?: React.ReactNode }) {
  return <div className="grid gap-gap lg:grid-cols-2" data-testid="task-budget">
    <Card title="本任务预算"><div className="space-y-3"><Bar label="金额" used={task.spentUsd} limit={task.budgetUsd} pre="$" /><Bar label="Token" used={task.spentTokens} limit={task.budgetTokens} /></div>
      <div className="mt-3"><AppLink className="text-sm underline" href={`/p/${encodeURIComponent(task.projectId)}/budget`}>看项目预算 →</AppLink></div></Card>
    {isAdmin(role) && admin ? admin : <Card title="预算开关"><EditHint role={role} href={`/t/${encodeURIComponent(task.id)}/detail?tab=info`} label="调整 →" /></Card>}
  </div>;
}

export function GovernanceSummary({ line, role, meta, editor }: { line: LineMeta | undefined; role: Role; meta: Meta | null; editor?: React.ReactNode }) {
  if (!line) return <p className="text-sm text-ink-3">找不到产品线。</p>;
  if (isAdmin(role) && editor) return <>{editor}</>;
  return <Card title={`治理 · ${line.name}`} data-testid="governance-summary"><div className="space-y-1 text-sm">
    <div className="flex justify-between border-b border-dashed border-line py-1"><span className="text-ink-3">自治上限</span><b>{line.autonomy}</b></div>
    <div className="flex justify-between border-b border-dashed border-line py-1"><span className="text-ink-3">产品线状态</span><span>{line.paused ? '已暂停' : '运行中'}</span></div>
    <div className="flex justify-between border-b border-dashed border-line py-1"><span className="text-ink-3">项目数</span><span>{meta?.projects.filter(p => p.productLineId === line.id).length ?? 0}</span></div>
    <p className="pt-2 text-caption text-ink-3">评审阈值、返修上限等按产品线生效；项目继承所属产品线。</p>
    <div className="pt-1"><EditHint role={role} href="/admin/governance" label="调整治理 →" /></div>
  </div></Card>;
}
