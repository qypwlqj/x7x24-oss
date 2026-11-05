import * as Popover from '@radix-ui/react-popover';
import { CircleCheck, CirclePause, CirclePlay, Clock, Copy, Info, TriangleAlert } from 'lucide-react';
import type { TaskDTO } from '../api';
import { api, fmtTime } from '../api';
import { useState } from 'react';
import { DevModeChoice, DEV_MODE_ZH, type DevModeValue } from './DevModeChoice';
import { taskDot } from '../lib/commandCenter';
import { executionStateName } from '../catalog';
import { statusName } from '../labels';
import { Tip } from '../ui/InfoTip';
import { cx, toast } from '../ui';
import { AppLink } from './AppLink';
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from './ui/dialog';
import { Icon } from '../ui/Icon';

export function taskStatusLabel(task: Pick<TaskDTO, 'status' | 'executionState' | 'paused'>) {
  if (task.status === 'DISCARDED') return '已废弃';
  if (task.executionState === 'WAITING_CONFIGURATION') return '待配置发布';
  if (task.status === 'RELEASED') return '版本已发布';
  if (task.status === 'DELIVERED') return '待发布';
  if (task.paused || task.executionState === 'PAUSED') return '已暂停';
  if (task.executionState === 'FAILED') return '执行失败';
  if (task.executionState === 'BLOCKED') return '已阻塞';
  if (task.executionState === 'RECOVERY_REQUIRED') return '需要恢复';
  if (task.executionState === 'RUNNING') return '运行中';
  return executionStateName(task.executionState);
}

/** 正常状态只占一个图标；需要关注的状态始终带文字，点击可查看详情。 */
export function TaskStatus({ task }: { task: TaskDTO }) {
  const dot = taskDot(task);
  const label = taskStatusLabel(task);
  const stopped = task.status === 'DISCARDED' || task.executionState === 'CANCELLED';
  const attention = dot === 'blocked' || dot === 'waiting' || task.executionState === 'CANCELLED';
  const StatusIcon = dot === 'blocked' ? TriangleAlert : stopped || task.paused || task.executionState === 'PAUSED' ? CirclePause : dot === 'done' ? CircleCheck : dot === 'running' && task.executionState !== 'WAITING' ? CirclePlay : Clock;
  return <Popover.Root>
    <Tip content={`${label} · ${statusName(task.currentStage ?? task.status)}`}><Popover.Trigger asChild>
      <button type="button" aria-label={`${label}，查看状态详情`} className={cx('inline-flex min-h-8 min-w-8 items-center justify-center gap-1.5 px-1.5 text-caption hover:bg-sunken [@media(pointer:coarse)]:min-h-11 [@media(pointer:coarse)]:min-w-11', dot === 'blocked' ? 'text-danger-ink' : dot === 'waiting' ? 'text-warn-ink' : stopped ? 'text-ink-3' : dot === 'running' || dot === 'done' ? 'text-success-ink' : 'text-ink-3')} data-testid="task-status">
        <StatusIcon size={16} aria-hidden="true" />{attention && <span role="status">{label}</span>}
      </button>
    </Popover.Trigger></Tip>
    <Popover.Portal><Popover.Content align="end" sideOffset={6} collisionPadding={12} className="z-50 w-72 max-w-[calc(100vw-24px)] border-2 border-ink bg-raised p-3 text-sm shadow-[4px_4px_0_rgb(var(--c-line-strong))]" aria-label="任务状态详情" data-testid="task-status-popover">
      <p className="font-bold">{label} · {statusName(task.currentStage ?? task.status)}</p>
      <dl className="mt-2 space-y-1 text-caption text-ink-2"><div className="flex justify-between gap-3"><dt>当前轮次</dt><dd>{task.currentIteration}</dd></div><div className="flex justify-between gap-3"><dt>更新于</dt><dd>{fmtTime(task.statusChangedAt)}</dd></div></dl>
      <AppLink className="mt-3 inline-block text-caption underline" href={`/t/${encodeURIComponent(task.id)}/detail`}>查看完整任务详情 →</AppLink>
    </Popover.Content></Popover.Portal>
  </Popover.Root>;
}

export function TaskInfo({ task }: { task: TaskDTO }) {
  const copy = async () => { try { await navigator.clipboard.writeText(task.id); toast.success('已复制任务 ID'); } catch { toast.error('复制失败，请选择任务 ID 手动复制'); } };
  return <Dialog>
    <Tip content="任务信息"><DialogTrigger asChild><button type="button" aria-label="任务信息" className="inline-flex h-8 w-8 items-center justify-center text-ink-2 hover:bg-sunken [@media(pointer:coarse)]:h-11 [@media(pointer:coarse)]:w-11" data-testid="task-info-trigger"><Info size={16} aria-hidden="true" /></button></DialogTrigger></Tip>
    <DialogContent side="right" className="w-[360px] border-l-2 border-ink" data-testid="task-info-drawer">
      <DialogHeader><DialogTitle>任务信息</DialogTitle><DialogClose asChild><button type="button" aria-label="关闭任务信息" className="grid h-9 w-9 shrink-0 place-items-center hover:bg-sunken"><Icon name="x" /></button></DialogClose></DialogHeader>
      <div className="min-h-0 flex-1 space-y-5 overflow-y-auto p-4">
        <DialogDescription className="break-words text-sm text-ink">{task.title}</DialogDescription>
        <section><h3 className="mb-2 text-caption text-ink-3">所属项目</h3><AppLink className="break-words text-sm underline" href={`/p/${encodeURIComponent(task.projectId)}/tasks`}>{task.productLineName} / {task.projectName}</AppLink></section>
        <section><h3 className="mb-2 text-caption text-ink-3">运行详情</h3><dl className="divide-y divide-dashed divide-line text-sm">
          {([['当前节点', statusName(task.currentStage ?? task.status)], ['状态', taskStatusLabel(task)], ['轮次', task.currentIteration], ['退回 / 重试', `${task.rejectCount} / ${task.reviewRetry}`], ['更新于', fmtTime(task.statusChangedAt)]] as const).map(([label, value]) => <div key={label} className="flex justify-between gap-3 py-2"><dt className="text-ink-3">{label}</dt><dd className="text-right tabular">{value}</dd></div>)}
        </dl></section>
        <TaskDevModeSection task={task} />
        <section><h3 className="mb-2 text-caption text-ink-3">花费 / 预算上限</h3><AppLink className="text-sm tabular underline" href={`/t/${encodeURIComponent(task.id)}/budget`}>${Number(task.spentUsd ?? 0).toFixed(2)} / ${task.budgetUsd}</AppLink></section>
        <section><h3 className="mb-2 text-caption text-ink-3">任务 ID</h3><div className="flex items-start gap-2"><code className="min-w-0 flex-1 select-all break-all text-caption">{task.id}</code><Tip content="复制完整任务 ID"><button type="button" aria-label="复制完整任务 ID" className="grid h-8 w-8 shrink-0 place-items-center hover:bg-sunken" onClick={copy}><Copy size={15} aria-hidden="true" /></button></Tip></div></section>
        <DialogClose asChild><AppLink className="inline-block text-sm underline" href={`/t/${encodeURIComponent(task.id)}/detail`}>完整任务详情 →</AppLink></DialogClose>
      </div>
    </DialogContent>
  </Dialog>;
}

/** 任务级开发方式：成员可改（只读成员由服务端拒绝），下一轮开发生效。 */
export function TaskDevModeSection({ task }: { task: TaskDTO }) {
  const [value, setValue] = useState<DevModeValue>(task.devMode?.override ?? 'inherit'); const [busy, setBusy] = useState(false);
  const ended = task.status === 'DISCARDED' || task.status === 'RELEASED';
  const projectMode = task.devMode && task.devMode.source !== 'task' ? DEV_MODE_ZH[task.devMode.mode] : null;
  const save = async (v: DevModeValue) => {
    const before = value; setValue(v); setBusy(true);
    try { await api(`/api/tasks/${encodeURIComponent(task.id)}/dev-mode`, { method: 'PATCH', body: { devMode: v === 'inherit' ? null : v } }); toast.success('已保存，下一轮开发生效'); }
    catch (e: any) { setValue(before); toast.error(e?.message ?? '保存失败'); } finally { setBusy(false); }
  };
  return <section data-testid="task-dev-mode-section"><h3 className="mb-2 text-caption text-ink-3">开发方式</h3>
    <DevModeChoice compact name="task-dev-mode" value={value} onChange={save} disabled={busy || ended} inheritLabel={projectMode ? `用项目设置（现在是「${projectMode}」）。` : '用项目设置。'} />
    <p className="mt-1 text-caption text-ink-3">改了以后下一轮开发生效，正在跑的这一轮不受影响。</p>
  </section>;
}
