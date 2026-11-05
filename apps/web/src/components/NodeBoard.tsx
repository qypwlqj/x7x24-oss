import { AppLink } from '@/components/AppLink';
import { useEffect, useState } from 'react';
import { api } from '../api';
import { Badge, Button, Card, Drawer, EmptyState, cx } from '../ui';
import { STACK_LABEL, boardStacks, dwell, visibleCells, type BoardCell, type NodeBoardData, type NodeTask } from '../lib/pipeline';
import { statusName } from '../labels';

const qs = (o: Record<string, string | undefined>) => new URLSearchParams(Object.entries(o).filter(([, v]) => v) as [string, string][]).toString();

/** 方案 A：工作区汇总的流程节点看板，按「采集 / 生产（设计、构建、检查） / 交付」三段；罕见步骤默认折叠 */
export function NodeBoardCard({ productLine, projectId }: { productLine?: string; projectId?: string }) {
  const [b, setB] = useState<NodeBoardData | null>(null); const [err, setErr] = useState(''); const [stack, setStack] = useState('');
  const [open, setOpen] = useState<Record<string, boolean>>({}); const [sel, setSel] = useState<BoardCell | null>(null);
  const q = qs({ productLine, projectId, stack, days: '30' });
  useEffect(() => { let on = true; const load = () => api<NodeBoardData>(`/api/board/pipeline?${q}`).then(r => { if (on) { setB(r); setErr(''); } }).catch(e => on && setErr(e.message)); load(); const t = setInterval(load, 15000); return () => { on = false; clearInterval(t); }; }, [q]);
  const stacks = boardStacks(b);
  return <Card title="流程步骤看板" subtitle={b ? `近 ${b.window.replace('d', '')} 天 ${b.windowTasks} 个任务 · 数字为当前停在该步骤的任务 · 点击查看` : '按流程步骤汇总所有项目的任务'} data-testid="node-board"
    actions={<div className="flex flex-wrap gap-1" data-testid="node-board-stacks">{['', ...stacks.filter(s => s !== 'other')].map(s => <Button key={s || 'all'} size="sm" variant={stack === s ? 'primary' : 'ghost'} onClick={() => setStack(s)}>{s ? STACK_LABEL[s] ?? s : '全部技术栈'}</Button>)}</div>}>
    {err ? <EmptyState title="看板加载失败" hint={err} /> : !b ? <p className="text-sm text-ink-3">加载中…</p> : <div className="space-y-3">
      {(b.attention.anomaly > 0 || b.attention.degraded > 0) && <div className="flex gap-2 text-xs"><Badge tone="danger">异常 {b.attention.anomaly}</Badge><Badge tone="warn">降级 {b.attention.degraded}</Badge></div>}
      <div className="grid gap-2 lg:grid-cols-[1fr_3fr_1.4fr]">
        <Segment title="采集" tone="border-info/60">{b.modules.sensing.map(c => <Cell key={c.node} c={c} onClick={() => setSel(c)} />)}</Segment>
        <Segment title="生产" tone="border-accent/60">
          <div className="grid gap-2 md:grid-cols-3">{b.modules.production.map(f => { const show = !!open[f.family]; const cells = visibleCells(f.nodes, show); return <div key={f.family} className="space-y-1" data-testid={`family-${f.family}`}>
            <div className="label-caps text-caption text-ink-3">{f.label}</div>
            {cells.map(c => <Cell key={c.node} c={c} onClick={() => setSel(c)} />)}
            {f.rareCount > 0 && <button type="button" className="w-full border border-dashed border-line px-2 py-1 text-left text-caption text-ink-3 hover:border-primary hover:text-primary-ink" onClick={() => setOpen(o => ({ ...o, [f.family]: !show }))}>{show ? '收起不常用的步骤' : `+${f.rareCount} 更多`}</button>}
          </div>; })}</div>
        </Segment>
        <Segment title="交付" tone="border-success/60">{b.modules.delivery.map(c => <Cell key={c.node} c={c} onClick={() => setSel(c)} />)}</Segment>
      </div>
      <p className="text-caption text-ink-3">经过率 = 近 {b.window.replace('d', '')} 天经过该步骤的任务占比；低于 {b.rarePct}% 且当前没有任务的步骤默认折叠。</p>
    </div>}
    {sel && <NodeTasks cell={sel} q={qs({ productLine, projectId, stack })} onClose={() => setSel(null)} />}
  </Card>;
}

function Segment({ title, tone, children }: { title: string; tone: string; children: React.ReactNode }) {
  return <div className={cx('space-y-1 border-l-2 pl-2', tone)}><div className="hud-title text-xs text-ink-2">{title}</div>{children}</div>;
}

function Cell({ c, onClick }: { c: BoardCell; onClick: () => void }) {
  const stacks = Object.entries(c.byStack);
  return <button type="button" onClick={onClick} data-testid={`cell-${c.node}`} className={cx('block w-full border bg-raised/70 px-2 py-1.5 text-left transition hover:border-primary', c.current > 0 ? 'border-primary/60' : 'border-line', c.blocked > 0 && 'border-danger/70')}>
    <div className="flex items-baseline justify-between gap-2"><span className="truncate text-sm text-ink">{c.label}</span><span className={cx('num text-lg font-semibold', c.current > 0 ? 'text-ink' : 'text-ink-3')}>{c.current}</span></div>
    <div className="flex flex-wrap items-center gap-1 text-caption text-ink-3">
      {c.candidates ? <span>待确认的需求候选</span> : <><span>经过 {c.throughPct}%</span>{c.current > 0 && <span>· 平均停留 {dwell(c.avgDwellH)}</span>}</>}
      {c.blocked > 0 && <Badge tone="danger">卡住 {c.blocked}</Badge>}
      {stacks.map(([s, n]) => <span key={s} className="border border-line px-1">{STACK_LABEL[s] ?? s} {n}</span>)}
    </div>
  </button>;
}

function NodeTasks({ cell, q, onClose }: { cell: BoardCell; q: string; onClose: () => void }) {
  const [rows, setRows] = useState<NodeTask[] | null>(null); const [err, setErr] = useState('');
  useEffect(() => { api<{ tasks: NodeTask[] }>(`/api/board/pipeline/node/${encodeURIComponent(cell.node)}?${q}`).then(r => setRows(r.tasks)).catch(e => setErr(e.message)); }, [cell.node, q]);
  return <Drawer title={`${cell.label} · 当前 ${cell.current} 个任务`} onClose={onClose}>
    {err ? <EmptyState title="加载失败" hint={err} /> : !rows ? <p className="text-sm text-ink-3">加载中…</p> : cell.candidates ? <EmptyState title="需求候选在项目页确认" hint="打开对应项目，在「需求候选」里确认或忽略。" /> : rows.length === 0 ? <EmptyState title="没有停在这个步骤的任务" hint="任务进入这个步骤后会出现在这里。" /> :
      <ul className="space-y-2" data-testid="node-tasks">{rows.map(t => <li key={t.id}><AppLink href={`/task/${t.id}/pipeline/${encodeURIComponent(cell.node)}`} className="block border border-line bg-raised/70 p-2 text-sm hover:border-primary">
        <div className="flex justify-between gap-2"><b className="truncate">{t.title}</b>{t.blocked ? <Badge tone="danger">卡住</Badge> : <Badge tone="neutral">{statusName(t.status)}</Badge>}</div>
        <p className="mt-1 text-xs text-ink-3">{t.project} · 停留 {dwell(t.dwellH)} · 执行 {t.attempts} 次{t.stacks.length ? ` · ${t.stacks.map(s => STACK_LABEL[s] ?? s).join('、')}` : ''}</p>
        {t.reason && <p className="mt-1 text-xs text-danger-ink">{t.reason}</p>}
      </AppLink></li>)}</ul>}
  </Drawer>;
}
