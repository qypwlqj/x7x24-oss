import { useEffect, useState } from 'react';
import { Disclosure } from './ui/collapsible';
import { modelDisplayName, modelStateName, reviewerName, type ModelCall } from '../lib/timeline';
import { retryText, reviewActive, reviewEnded, reviewStateName, type ReviewActivity, type ReviewGroup } from '../lib/reviewActivity';
import { NodeIcon } from '../ui/NodeIcon';

function ExecutionSignal() {
  return <span className="execution-signal" aria-hidden="true"><span /><span /><span /></span>;
}

function Indicator({ active, state }: { active: boolean; state?: string }) {
  return active ? <ExecutionSignal /> : <span aria-hidden="true" className={state === 'FAILED' ? 'text-danger-ink' : state === 'COMPLETED' || state === 'SUCCEEDED' ? 'text-success-ink' : 'text-ink-3'}>{state === 'COMPLETED' || state === 'SUCCEEDED' ? '✓' : state === 'FAILED' ? '✗' : state === 'SKIPPED' ? '—' : '○'}</span>;
}

function Calls({ calls, animate }: { calls: ModelCall[]; animate: boolean }) {
  return <ul className="mt-1 space-y-1 text-caption" aria-label="评审模型状态">{calls.map(c => <li key={c.callId} data-testid="tl-model-seat" className="flex items-center gap-2 break-words [overflow-wrap:anywhere]">
    <Indicator active={animate && c.state === 'RUNNING'} state={c.state} />
    <span>{reviewerName(c.role)} · {modelDisplayName(c.model, !animate && ['RUNNING', 'QUEUED'].includes(c.state) ? 'UNCONFIRMED' : c.state, c.source)} · {animate ? modelStateName(c.state) : ['RUNNING', 'QUEUED'].includes(c.state) ? '状态待确认' : modelStateName(c.state)}</span>
  </li>)}</ul>;
}

function GroupRow({ group: g, now, uncertain }: { group: ReviewGroup; now: number; uncertain: boolean }) {
  const unknown = g.state === 'UNCONFIRMED' || uncertain && !reviewEnded(g.state);
  const active = reviewActive(g.state) && !unknown;
  const model = g.model ?? [...g.calls].reverse().find(c => c.model && (g.state !== 'VERIFYING' || c.role === 'review.code.verify'))?.model;
  const retry = unknown ? null : retryText(g); const failed = g.state === 'FAILED';
  const directory = g.files[0]?.slice(0, g.files[0].lastIndexOf('/') + 1) || '根目录';
  const end = reviewEnded(g.state) ? g.updatedAt : now || g.updatedAt;
  const seconds = g.startedAt ? Math.max(0, Math.floor((end - g.startedAt) / 1000)) : 0;
  return <div className={`execution-group border-l-2 pl-3 py-1 ${failed ? 'border-danger-ink' : 'border-line'}`} data-testid="review-group" data-group={g.fileGroupId} data-slot={g.slot} data-state={unknown ? 'UNCONFIRMED' : g.state}>
    <div className="flex items-start gap-2"><span className="mt-0.5 w-3 shrink-0"><Indicator active={active} state={g.state} /></span><div className="min-w-0 flex-1">
      <div className="break-words text-sm [overflow-wrap:anywhere]">第 {g.index} 组 · {directory} · {g.files.length} 个文件</div>
      <p className="mt-0.5 break-words text-caption text-ink-3 [overflow-wrap:anywhere]">{unknown ? '状态待确认' : reviewStateName(g.state)}{model ? ` · ${model}` : ''}{g.startedAt > 0 ? ` · ${reviewEnded(g.state) ? '用时' : '已用时'} ${seconds} 秒` : ''}</p>
      {retry && <p className="text-caption text-warn-ink" data-testid="review-retry">{retry}</p>}
      {g.reason && <p className={`text-caption ${failed ? 'text-danger-ink' : 'text-warn-ink'}`} data-testid={failed ? 'review-failed-reason' : undefined}>{g.reason}</p>}
      <Disclosure summary="文件与调用详情" summaryClassName="mt-1 text-caption text-ink-3"><ul className="mt-1 text-caption text-ink-2">{g.files.map(f => <li className="break-all" key={f}>{f}</li>)}</ul><Calls calls={g.calls} animate={active} /></Disclosure>
    </div></div>
  </div>;
}

/** 只在这个局部面板计时，计时刷新不会重绘整条时间线。 */
export function ExecutionActivity({ running, calls, review, uncertain = false, lastSyncAt }: { running: string; calls: ModelCall[]; review?: ReviewActivity | null; uncertain?: boolean; lastSyncAt?: number | null }) {
  const [now, setNow] = useState(0);
  const unconfirmed = uncertain || !!review?.groups.some(g => g.state === 'UNCONFIRMED') || !review && calls.some(c => c.state === 'UNCONFIRMED');
  const animate = !unconfirmed && !review?.finished;
  const ticking = animate && !!review?.groups.some(g => reviewActive(g.state));
  useEffect(() => {
    if (!ticking) return;
    setNow(Date.now()); const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [ticking]);
  if (review?.finished) return null;
  if (review?.engine === 'ocr') {
    const ocrCalls = (review.calls?.length ? review.calls : calls).filter(c => c.kind !== 'execution');
    return <div className="flex items-start gap-2.5 text-sm text-ink-2" data-testid="tl-running" aria-busy={animate}>
      <span className="flex h-7 w-7 shrink-0 flex-col items-center justify-center gap-0.5 rounded-sm border border-line bg-sunken"><NodeIcon name="agent-think" size={15} />{animate && <ExecutionSignal />}</span>
      <div className="min-w-0 flex-1 break-words [overflow-wrap:anywhere]" data-testid="review-ocr">
        <p role="status">{review.state === 'PAUSED' ? '逐行审查已暂停，进度已保存' : review.resumed ? '正在逐行审查（接着上次审）' : '正在逐行审查'}</p>
        <p className="mt-1 text-caption text-ink-3">open-code-review 自己挑文件、分组并复核；审完会一次性给出结果。{review.reason ? ` ${review.reason}` : ''}</p>
        {ocrCalls.length > 0 && <div className="mt-2"><Calls calls={ocrCalls} animate={animate} /></div>}
      </div>
    </div>;
  }
  const done = review?.groups.filter(g => g.state === 'COMPLETED').length ?? 0;
  const failed = review?.groups.filter(g => g.state === 'FAILED').length ?? 0;
  const skipped = review?.groups.filter(g => g.state === 'SKIPPED').length ?? 0;
  const waiting = review?.groups.filter(g => g.state === 'QUEUED').length ?? 0;
  const current = review?.groups.filter(g => reviewActive(g.state) || g.state === 'UNCONFIRMED') ?? [];
  const history = review?.groups.filter(g => reviewEnded(g.state)) ?? [];
  // slot 由后端分配，初审/复核沿用同一位置；旧服务缺少分组信息时按调用展示并标明口径。
  const slots = new Map<number, ReviewGroup>();
  for (const g of current) if (g.slot > 0 && (!slots.has(g.slot) || slots.get(g.slot)!.updatedAt < g.updatedAt)) slots.set(g.slot, g);
  const legacy = !review && calls.some(c => c.role.startsWith('review.code'));
  const visibleCalls = calls.filter(c => c.kind !== 'execution' && (!legacy || ['RUNNING', 'QUEUED', 'UNCONFIRMED'].includes(c.state)));
  const oldCalls = legacy ? calls.filter(c => !['RUNNING', 'QUEUED', 'UNCONFIRMED'].includes(c.state)) : [];
  return <div className="flex items-start gap-2.5 text-sm text-ink-2" data-testid="tl-running" aria-busy={animate}>
    <span className="flex h-7 w-7 shrink-0 flex-col items-center justify-center gap-0.5 rounded-sm border border-line bg-sunken">
      <NodeIcon name="agent-think" size={15} />
      {animate && <ExecutionSignal />}
    </span>
    <div className="min-w-0 flex-1 break-words [overflow-wrap:anywhere]">
      <p role="status" className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1"><span>{unconfirmed ? '状态待确认' : review ? '正在逐行审查' : running}</span>{review && <span className="tabular text-caption">已完成 {done} / {review.total} 组</span>}</p>
      {unconfirmed && <p className="mt-1 text-caption text-warn-ink">{uncertain ? '状态更新中断' : '运行状态尚未确认'}{lastSyncAt ? ` · 最后更新于 ${new Date(lastSyncAt).toLocaleTimeString('zh-CN', { hour12: false })}` : ''}</p>}
      {review ? <>
        <div role="progressbar" aria-label="逐行审查已完成分组" aria-valuemin={0} aria-valuemax={review.total} aria-valuenow={done} className="my-2 h-1 overflow-hidden bg-sunken"><div className="execution-progress h-full bg-ink" style={{ width: `${review.total ? done / review.total * 100 : 0}%` }} /></div>
        <p className="mb-2 text-caption text-ink-3">{current.length} 组{unconfirmed ? '状态待确认' : '处理中'} · {waiting} 组等待开始 · 最多 {review.concurrency} 组并行{failed ? ` · ${failed} 组失败` : ''}{skipped ? ` · ${skipped} 组没审（超出上限）` : ''}</p>
        <div className="space-y-2" data-testid="review-active">{[...slots].sort(([a], [b]) => a - b).map(([slot, group]) => <GroupRow key={`${review.runId}:${slot}`} group={group} now={now} uncertain={unconfirmed} />)}</div>
        {!current.length && !waiting && <p className="text-caption text-ink-3">分组处理已结束，正在汇总结果…</p>}
        {failed > 0 && <div className="mt-2 space-y-2" data-testid="review-failed">{history.filter(g => g.state === 'FAILED').map(group => <GroupRow key={group.fileGroupId} group={group} now={now} uncertain={unconfirmed} />)}</div>}
        {history.some(g => g.state !== 'FAILED') && <Disclosure className="mt-2" summary={skipped ? `已结束 ${history.length - failed} 组（完成 ${done} · 没审 ${skipped}）` : `已完成 ${done} 组`} summaryClassName="text-caption text-ink-3"><div className="mt-2 space-y-2">{history.filter(g => g.state !== 'FAILED').map(group => <GroupRow key={group.fileGroupId} group={group} now={now} uncertain={unconfirmed} />)}</div></Disclosure>}
      </> : <><Calls calls={visibleCalls} animate={animate} />{legacy && <p className="mt-1 text-caption text-ink-3">{visibleCalls.length} 次调用处理中 · 此记录未提供文件分组信息</p>}{oldCalls.length > 0 && <Disclosure className="mt-1" summary={`已结束 ${oldCalls.length} 次调用`} summaryClassName="text-caption text-ink-3"><Calls calls={oldCalls} animate={false} /></Disclosure>}</>}
    </div>
  </div>;
}
