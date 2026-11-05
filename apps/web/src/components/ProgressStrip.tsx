import { useId, useLayoutEffect, useRef, useState } from 'react';
import type { TaskDTO } from '../api';
import { progressSteps } from '../lib/commandCenter';
import { progressActivity, progressStripTarget, stripNodes, type PlanData, type StripNode } from '../lib/taskData';
import { nodeIcon } from '../lib/timeline';
import { cx } from '../ui/cx';
import { Tip } from '../ui/InfoTip';
import { NodeIcon, type NodeIconName } from '../ui/NodeIcon';
import { AppLink } from './AppLink';
import { ProgressCat } from './ProgressCat';

const STEP_CLS: Record<string, string> = {
  done: 'border-ink bg-success-soft text-success-ink', current: 'border-ink bg-ink text-ink-inverse',
  blocked: 'border-danger bg-danger-soft text-danger-ink', rejected: 'border-danger bg-danger-soft text-danger-ink',
  pending: 'border-ink bg-raised opacity-45', skipped: 'border-ink bg-raised opacity-30', stale: 'border-ink bg-raised opacity-45',
};
const STATE_LABEL: Record<string, string> = { done: '已完成', current: '进行中', blocked: '阻塞', rejected: '未通过', pending: '未开始', skipped: '已跳过', stale: '待复验' };
const historyLabel = (state?: string, outcome?: string) => outcome === 'PASSED' ? '上次已通过' : outcome === 'FAILED' ? '上次未通过' : state === 'FAILED' ? '上次执行失败' : state === 'SUCCEEDED' ? '上次执行成功' : '保留历史记录';
const DETAILS_PREFERENCE = 'x7_progress_details_folded';

/** 节点图标表示结果，猫咪表示执行位置，选中边框表示正在查看的节点。 */
export function ProgressStrip({ task, plan, active, compact = false }: { task: TaskDTO; plan?: PlanData | null; active?: string; compact?: boolean }) {
  const [detailsOpen, setDetailsOpen] = useState(!compact);
  const detailsId = useId();
  // 服务端与首次客户端渲染一致，随后在绘制前恢复本浏览器的选择。
  useLayoutEffect(() => {
    try { setDetailsOpen(compact ? localStorage.getItem('x7_progress_compact_details') === '1' : localStorage.getItem(DETAILS_PREFERENCE) !== '1'); } catch { /* 存储不可用时仍可临时折叠。 */ }
  }, [compact]);
  const toggleDetails = () => {
    const next = !detailsOpen;
    setDetailsOpen(next);
    try { localStorage.setItem(compact ? 'x7_progress_compact_details' : DETAILS_PREFERENCE, compact ? next ? '1' : '0' : next ? '0' : '1'); } catch { /* 本次页面内的选择仍然有效。 */ }
  };
  const ended = ['DELIVERED', 'RELEASED', 'DISCARDED'].includes(task.status) && task.executionState !== 'WAITING_CONFIGURATION';
  const displayPlan = plan && ended ? { ...plan, status: task.status } : plan;
  const real = stripNodes(displayPlan ?? null, task);
  const steps: (StripNode & { icon?: NodeIconName })[] = real ?? progressSteps(task).map(s => ({ id: s.stage, label: s.label, state: s.state, icon: s.icon, children: [] }));
  const activity = progressActivity(task, displayPlan, steps);
  const repairing = activity.mode === 'running' && steps.some(s => s.recheck);
  const target = progressStripTarget(steps, plan, active);
  const stripRef = useRef<HTMLDivElement>(null), targetRef = useRef<HTMLLIElement>(null);
  const catAnchorRef = useRef<HTMLSpanElement>(null);
  const positioned = useRef<string | null>(null);
  const locationKey = JSON.stringify([task.id, active ?? null, real !== null]);
  // 首次及显式查看节点时定位；轮询与推进不覆盖用户的手动滚动。
  useLayoutEffect(() => {
    const strip = stripRef.current, node = targetRef.current;
    if (!strip || !node || !strip.clientWidth || positioned.current === locationKey) return;
    const bounds = strip.getBoundingClientRect(), item = node.getBoundingClientRect();
    const left = strip.scrollLeft + item.left - bounds.left - strip.clientLeft + item.width / 2 - strip.clientWidth / 2;
    strip.scrollLeft = Math.max(0, Math.min(left, strip.scrollWidth - strip.clientWidth));
    positioned.current = locationKey;
  }, [locationKey, target]);

  if (compact) return <div className="shrink-0 border-b border-dashed border-line-strong bg-sunken" data-testid="cc-progress" data-real={real ? '1' : '0'} data-details-open={detailsOpen}>
    <div className="flex items-start gap-2 px-3 py-1.5">
      <ol className="flex min-w-0 flex-1 flex-wrap items-center gap-x-1.5 gap-y-1 text-caption" aria-label="当前进度" data-testid="cc-progress-compact">
        {steps.map((s, i) => { const current = s.id === activity.nodeId; const stateLabel = current ? activity.childLabel && activity.mode === 'running' ? `正在${activity.childLabel}` : activity.text : s.recheck ? '待复验' : STATE_LABEL[s.state];
          return <li key={s.id} className={cx('min-w-0 items-center gap-1.5', current ? 'flex' : 'hidden sm:flex')} data-node={s.id} data-state={s.state}>
            {i > 0 && <span aria-hidden="true" className="hidden text-ink-3 sm:inline">›</span>}
            <Tip content={`${s.label} · ${stateLabel}`}><AppLink href={`/t/${encodeURIComponent(task.id)}/node/${encodeURIComponent(s.id)}`} aria-label={`${s.label} · ${stateLabel}`} aria-current={current && !ended ? 'step' : undefined}
              className={cx('inline-flex min-h-7 min-w-0 items-center gap-1 py-0.5 [@media(pointer:coarse)]:min-h-11', current ? activity.mode === 'blocked' ? 'font-bold text-danger-ink' : ['waiting', 'paused'].includes(activity.mode) ? 'font-bold text-warn-ink' : 'font-bold text-ink' : s.state === 'done' ? 'text-success-ink' : s.state === 'rejected' || s.state === 'blocked' ? 'text-danger-ink' : 'text-ink-3')}>
              <NodeIcon name={s.state === 'done' ? 'ci-pass' : s.state === 'blocked' || s.state === 'rejected' ? 'blocked' : s.icon ?? nodeIcon(s.id)} size={14} className="shrink-0" />
              <span className="max-w-36 truncate">{s.label}</span>{s.recheck && <span className="text-ink-3">待复验</span>}
            </AppLink></Tip>
          </li>;
        })}
      </ol>
      <button type="button" className="min-h-7 shrink-0 px-1 text-caption text-ink-3 hover:text-ink [@media(pointer:coarse)]:min-h-11" onClick={toggleDetails} aria-expanded={detailsOpen} aria-controls={`${detailsId}-compact`} data-testid="cc-progress-toggle">{detailsOpen ? '收起' : '详情'}</button>
    </div>
    <div id={`${detailsId}-compact`} hidden={!detailsOpen} className="border-t border-dashed border-line px-3 py-2" data-testid="cc-progress-details">
      {detailsOpen && <ul className="space-y-2 text-caption">{steps.map(s => <li key={s.id}>
        <AppLink className="break-words underline" href={`/t/${encodeURIComponent(task.id)}/node/${encodeURIComponent(s.id)}`}>{s.label}</AppLink><span className="ml-2 text-ink-3">{s.id === activity.nodeId ? activity.text : s.recheck ? `待复验 · ${historyLabel(s.historyState, s.historyOutcome)}` : STATE_LABEL[s.state]}</span>
        {!!s.children.length && <ul className="mt-1 space-y-1 border-l border-line pl-3">{s.children.map(c => <li key={c.id}><AppLink className="break-words underline" href={`/t/${encodeURIComponent(task.id)}/node/${encodeURIComponent(c.id)}`}>{c.label} · {c.recheck ? '待复验' : STATE_LABEL[c.state]}</AppLink></li>)}</ul>}
      </li>)}</ul>}
    </div>
  </div>;

  return <div className="flex shrink-0 items-start border-b-2 border-dashed border-line-strong bg-sunken" data-testid="cc-progress" data-real={real ? '1' : '0'} data-details-open={detailsOpen}>
    <div ref={stripRef} className="min-w-0 flex-1 overflow-x-auto px-3 py-1.5" data-testid="cc-progress-scroll">
    <div className="relative min-w-full" style={{ width: steps.length * 112 }}>
      <ol className="grid gap-y-0.5" style={{ gridTemplateColumns: `repeat(${steps.length || 1}, minmax(0, 1fr))`, gridTemplateRows: detailsOpen ? '32px auto auto' : '32px auto' }} aria-label="当前进度">
        {steps.map((s, i) => {
          const current = s.id === activity.nodeId;
          const stateLabel = current
            ? activity.childLabel && activity.mode === 'running' ? `正在${activity.childLabel}` : repairing ? '返修中' : activity.mode === 'running' ? `正在${s.label}` : activity.text
            : s.recheck ? '待复验' : STATE_LABEL[s.state];
          const waiting = current && ['waiting', 'paused'].includes(activity.mode);
          const tone = waiting ? 'border-warn bg-warn-soft text-warn-ink' : STEP_CLS[s.state];
          return <li ref={s.id === target ? targetRef : undefined} key={s.id} className="relative grid min-w-28 text-caption" style={{ gridColumn: i + 1, gridRow: '1 / -1', gridTemplateRows: 'subgrid' }} data-state={s.state} data-node={s.id}>
            {i < steps.length - 1 && <span aria-hidden className="absolute left-[calc(50%+16px)] right-[calc(-50%+16px)] top-[15px] border-t-2 border-line-strong" />}
            <Tip content={s.label}><AppLink href={`/t/${encodeURIComponent(task.id)}/node/${encodeURIComponent(s.id)}`} aria-label={`${s.label} · ${stateLabel}`} aria-current={current && !ended ? 'step' : undefined} className="grid w-full justify-items-center gap-y-0.5" style={{ gridRow: '1 / span 2', gridTemplateRows: 'subgrid' }}>
              <span className={cx('grid h-8 w-8 place-items-center rounded-full border-2', tone, active === s.id && 'ring-2 ring-accent ring-offset-2')}><NodeIcon name={s.state === 'blocked' && !waiting ? 'blocked' : ('icon' in s ? s.icon : undefined) ?? nodeIcon(s.id)} size={16} /></span>
              <span className={cx('line-clamp-2 w-full self-end px-2 text-center leading-4 [overflow-wrap:anywhere]', current && 'font-bold')}>{s.label}</span>
            </AppLink></Tip>
            <div id={`${detailsId}-${i}`} hidden={!detailsOpen} className="flex flex-col items-center gap-0.5" style={{ gridRow: 3 }} data-testid="cc-progress-details">
            {detailsOpen && <>
            <span ref={current ? catAnchorRef : undefined} className="h-10 w-full" aria-hidden="true" />
            <span className={cx('w-full px-2 text-center [overflow-wrap:anywhere]', current ? activity.mode === 'blocked' ? 'text-danger-ink font-bold' : waiting ? 'text-warn-ink' : 'text-ink font-bold' : 'text-ink-3')} role={current ? 'status' : undefined} aria-live={current ? 'polite' : undefined}>{stateLabel}</span>
            {s.recheck && <span className="text-ink-3">{historyLabel(s.historyState, s.historyOutcome)}</span>}
            {s.children.length > 0 && <details className="w-full px-2 text-ink-2" data-testid="cc-subnodes">
              <summary className="text-center">检查 {s.children.filter(c => c.state === 'done').length}/{s.children.length}</summary>
              <ul className="mt-1 space-y-1">{s.children.map(c => <li key={c.id}><AppLink href={`/t/${encodeURIComponent(task.id)}/node/${encodeURIComponent(c.id)}`} aria-label={`${c.label} · ${c.recheck ? '待复验' : STATE_LABEL[c.state]}`} className="block break-words underline">
                {c.label} · {c.recheck ? '待复验' : STATE_LABEL[c.state]}
              </AppLink></li>)}</ul>
            </details>}
            </>}
            </div>
          </li>;
        })}
      </ol>
      {detailsOpen && activity.nodeId && <ProgressCat taskId={task.id} nodeId={activity.nodeId} mode={activity.mode} behavior={activity.behavior} successKey={activity.successKey} anchorRef={catAnchorRef} />}
    </div>
    </div>
    <button type="button" onClick={toggleDetails} aria-expanded={detailsOpen} aria-controls={steps.length ? steps.map((_, i) => `${detailsId}-${i}`).join(' ') : undefined} className="m-1 inline-flex min-h-8 shrink-0 items-center gap-1 rounded-sm px-1.5 text-caption text-ink-2 hover:text-ink [@media(pointer:coarse)]:min-h-11" data-testid="cc-progress-toggle">
      <span aria-hidden="true">{detailsOpen ? '▴' : '▾'}</span>{detailsOpen ? '收起详情' : '展开详情'}
    </button>
  </div>;
}
