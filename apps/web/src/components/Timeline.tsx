import { AppLink } from '@/components/AppLink';
/** 结构化时间线：事件卡片 + 对话消息。popover = 短的只读信息；collapse = 工具调用 / 原因；重要卡片自带「详情」（原始数据） */
import * as Popover from '@radix-ui/react-popover';
import type { ReactNode } from 'react';
import { Disclosure } from '@/components/ui/collapsible';
import { fmtTime } from '../api';
import { type Entry, type ModelCall, type TimelineCard } from '../lib/timeline';
import type { ReviewActivity } from '../lib/reviewActivity';
import { ExecutionActivity } from './ExecutionActivity';
import { cx } from '../ui';
import { NodeIcon } from '../ui/NodeIcon';
import { Checks, ShotStrip } from './Shots';

const TONE: Record<string, string> = { danger: 'text-danger-ink', warn: 'text-warn-ink', success: 'text-success-ink', info: 'text-ink', neutral: 'text-ink-3' };
const Raw = ({ c }: { c: TimelineCard }) => <Disclosure className="mt-1" summaryClassName="text-caption text-ink-3" summary="详情" data-testid="tl-raw">
  <pre className="mt-1 max-h-60 overflow-auto whitespace-pre-wrap break-all border border-line bg-surface p-2 font-mono text-[11px] text-ink-2">{JSON.stringify(c.raw.map(({ seq, type, nodeId, at, actor, model, from, to, edge, data, evidence }) => ({ seq, type, nodeId, at, actor, model, from, to, edge, data, evidence })), null, 2)}</pre></Disclosure>;
const Meta = ({ c, taskId }: { c: TimelineCard; taskId: string }) => <div className="text-caption text-ink-3">{fmtTime(c.at)}{c.nodeId && <> · <AppLink className="underline" href={`/t/${encodeURIComponent(taskId)}/node/${encodeURIComponent(c.nodeId)}`}>节点详情</AppLink></>}</div>;
const Models = ({ c }: { c: TimelineCard }) => c.models !== undefined ? <p className="mt-0.5 break-words text-caption text-ink-3 [overflow-wrap:anywhere]" data-testid="tl-models">{c.models.length ? `使用模型：${c.models.join('、')}` : '本次未记录模型'}</p> : null;
const Ico = ({ c, big }: { c: TimelineCard; big?: boolean }) => <span className={cx('grid shrink-0 place-items-center rounded-sm border-[1.5px] border-ink bg-raised', big ? 'h-8 w-8' : 'h-7 w-7', TONE[c.tone])}><NodeIcon name={c.icon} size={big ? 18 : 16} /></span>;

function PopRow({ c, taskId }: { c: TimelineCard; taskId: string }) {
  return <div className="flex items-start gap-2.5" data-testid="tl-row" data-kind={c.kind}>
    <Popover.Root><Popover.Trigger aria-label={`${c.title} 的说明`} className="rounded-sm focus-visible:outline-2" data-testid="tl-pop-trigger"><Ico c={c} /></Popover.Trigger>
      <Popover.Portal><Popover.Content side="right" align="start" sideOffset={6} collisionPadding={12} className="z-50 w-80 max-w-[calc(100vw-24px)] border-2 border-ink bg-raised p-3 text-sm shadow-[4px_4px_0_rgb(var(--c-line-strong))]" data-testid="tl-popover">
        <div className="flex items-center gap-2 font-bold"><NodeIcon name={c.icon} size={15} />{c.title}</div>
        {c.text && <p className="mt-1 text-ink-2">{c.text}</p>}<Meta c={c} taskId={taskId} />
        {c.link && <AppLink className="mt-1 inline-block underline" href={c.link.href} target={c.link.href.startsWith('http') ? '_blank' : undefined} rel="noreferrer">{c.link.label}</AppLink>}
        <Raw c={c} />
      </Popover.Content></Popover.Portal></Popover.Root>
    <div className="min-w-0 flex-1 pt-0.5"><div className={cx('break-words text-sm [overflow-wrap:anywhere]', c.tone === 'danger' && 'font-semibold', TONE[c.tone] === 'text-ink-3' ? 'text-ink-2' : TONE[c.tone])}>{c.title}{c.link && c.link.href.startsWith('http') && <> · <AppLink className="underline" href={c.link.href} target="_blank" rel="noreferrer">{c.link.label}</AppLink></>}</div><Models c={c} />
      {c.expand === 'collapse' ? <Disclosure defaultOpen={c.tone === 'danger'} className="mt-0.5" summaryClassName="text-caption text-accent-ink" summary={c.items ? `展开 ${c.items.length} 项` : '展开原因'} data-testid="tl-collapse">
        <div className="mt-1 border-l-2 border-dashed border-line pl-2.5 font-mono text-[12px] leading-5 text-ink-2">
          {c.items?.map((it, i) => <div key={i} data-testid="tl-tool" className="flex flex-wrap items-baseline gap-x-2">
            <span className={it.ok === false ? 'text-danger-ink' : it.ok == null && it.ms != null ? 'text-ink-3' : 'text-success-ink'}>{it.ok === false ? '✗' : it.ok == null && it.ms != null ? '…' : '✓'}</span>
            <span className="min-w-0 break-all">{it.title}</span>{it.ms != null && <span className="text-ink-3">{it.ms < 1000 ? `${it.ms}ms` : `${(it.ms / 1000).toFixed(1)}s`}</span>}
            {it.text && <span className="w-full pl-4 text-danger-ink">{it.text}</span>}
            {it.out && it.ok !== false && <span className="w-full truncate pl-4 text-ink-3">{it.out.split('\n').filter(Boolean).slice(-1)[0]}</span>}</div>)}
          {c.text && <p className="whitespace-pre-wrap font-sans text-sm">{c.text}</p>}
        </div><Raw c={c} /></Disclosure>
        : c.text && <div className="truncate text-caption text-ink-3">{c.text}</div>}
    </div>
  </div>;
}

function BigCard({ c, taskId }: { c: TimelineCard; taskId: string }) {
  const border = c.tone === 'danger' ? 'border-danger bg-danger-soft/50' : c.tone === 'warn' ? 'border-warn bg-warn-soft/50' : c.tone === 'success' ? 'border-success bg-success-soft/40' : 'border-ink bg-raised';
  return <div className={cx('border-2 p-3 shadow-[3px_3px_0_rgb(var(--c-line-strong))]', border)} data-testid="tl-card" data-kind={c.kind}>
    <div className="flex items-center gap-2"><Ico c={c} big /><b className={cx('flex-1', TONE[c.tone])}>{c.title}</b></div>
    {c.text && <p className="mt-1.5 text-sm text-ink-2">{c.text}</p>}
    <Models c={c} />
    {!!c.suggestions?.length && <div className="mt-2 text-sm"><span className="text-ink-3">建议：</span>{c.suggestions.map((s, i) => <span key={i} className="mr-1.5 inline-block border border-dashed border-ink px-1.5 text-caption">{i + 1}. {s}</span>)}<p className="mt-1 text-caption text-ink-3">在会话里直接恢复 / 重试会在 M3 开放；现在可去任务详情处理。</p></div>}
    {!!c.checks?.length && <div className="mt-2"><Checks checks={c.checks} /></div>}
    {!!c.shots?.length && <div className="mt-2"><ShotStrip shots={c.shots} /></div>}
    <div className="mt-1 flex flex-wrap items-center gap-3"><Meta c={c} taskId={taskId} />{c.link && <AppLink className="text-caption underline" href={c.link.href} target={c.link.href.startsWith('http') ? '_blank' : undefined} rel="noreferrer">{c.link.label}</AppLink>}</div>
    <Raw c={c} />
  </div>;
}

function TimelineEntry({ entry: x, taskId }: { entry: Entry; taskId: string }) {
  if (x.t === 'divider') return <div className="flex items-center gap-2 pt-1 text-caption text-ink-3" data-testid="tl-divider"><span className="h-px flex-1 border-t border-dashed border-line-strong" />{x.label}<span className="h-px flex-1 border-t border-dashed border-line-strong" /></div>;
  if (x.t === 'card') return x.card.expand === 'card' ? <BigCard c={x.card} taskId={taskId} /> : <PopRow c={x.card} taskId={taskId} />;
  if (x.msg.role === 'reasoning') return <Disclosure summary="思考过程" className="max-w-[90%] border-l-2 border-line-strong py-1 pl-3" summaryClassName="text-caption text-ink-3" data-testid="tl-reasoning"><div className="mt-2 whitespace-pre-wrap break-words text-sm text-ink-2">{x.msg.text}</div></Disclosure>;
  return <div className={cx('flex', x.msg.role === 'user' ? 'justify-end' : 'justify-start')} data-testid="tl-chat"><div className={cx('max-w-[90%] whitespace-pre-wrap break-words border-[1.5px] px-3 py-2 text-sm [overflow-wrap:anywhere]', x.msg.role === 'user' ? 'border-ink bg-ink text-ink-inverse' : x.msg.role === 'tool' ? 'border-dashed border-line font-mono text-caption text-accent-ink' : 'border-ink bg-raised')}>{x.msg.text}</div></div>;
}
const ROUTINE_EVENTS = new Set(['STAGE_RESUMED', 'TASK_RESUMED', 'TASK_ACTION_EXECUTED']);
const routine = (x: Entry) => x.t === 'divider' || x.t === 'card' && !['danger', 'warn'].includes(x.card.tone) && (x.card.kind === 'node.entered' || x.card.kind === 'direction.changed' && x.card.raw.length > 0 && x.card.raw.every(e => ROUTINE_EVENTS.has(e.type)));
const entryKey = (x: Entry, i: number) => x.t === 'card' ? x.card.key : x.t === 'chat' ? `m${x.msg.id}` : `d${i}`;

export function Timeline({ entries, taskId, running, calls = [], compact = false, review, uncertain, lastSyncAt }: { entries: Entry[]; taskId: string; running?: string | null; calls?: ModelCall[]; compact?: boolean; review?: ReviewActivity | null; uncertain?: boolean; lastSyncAt?: number | null }) {
  const rows: ReactNode[] = [];
  let records: Entry[] = [];
  const flush = () => {
    const count = records.filter(x => x.t !== 'divider').length;
    if (count) { const items = records; rows.push(<Disclosure key={`records-${entryKey(items[0], rows.length)}`} summary={`执行记录（${count}）`} summaryClassName="text-caption text-ink-3" data-testid="tl-execution-records"><div className="mt-2 space-y-2.5 border-l border-dashed border-line pl-3">{items.map((x, i) => <TimelineEntry key={entryKey(x, i)} entry={x} taskId={taskId} />)}</div></Disclosure>); }
    records = [];
  };
  entries.forEach((x, i) => { if (compact && routine(x)) records.push(x); else { flush(); rows.push(<TimelineEntry key={entryKey(x, i)} entry={x} taskId={taskId} />); } });
  flush();
  return <div className="space-y-2.5" data-testid="timeline">
    {entries.length === 0 && !running && <p className="text-sm text-ink-3">还没有工作记录。</p>}
    {rows}
    {running && <ExecutionActivity running={running} calls={calls} review={review} uncertain={uncertain} lastSyncAt={lastSyncAt} />}
  </div>;
}
