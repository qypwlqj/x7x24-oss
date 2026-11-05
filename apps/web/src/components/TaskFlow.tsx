import { useId, type CSSProperties, type ReactNode } from 'react';
import { cx } from '../ui';
import { dashPattern } from '../pulse';
import { BASE_DASH, COLOR } from './PipelineFlow';
import { LANE_H, NH, NW } from './flowLayout';
import { LANE_NAMES, STATE_LABEL, fitText, layoutTaskFlow, type FlowInput, type LEdge, type LNode, type NodeState } from '../lib/taskFlow';
import { SOURCE_LABEL, type Graph } from '../lib/pipeline';
import { FIBER_MATRIX, laneRuleYs, marginDash, sheetPath } from '../ui/paperPath';

/** 节点状态 → 颜色（与总览流水线同一套墨色 / 状态色） */
const STATE_TONE: Record<NodeState, string> = { done: 'success', current: 'primary', blocked: 'warn', rejected: 'danger', skipped: 'ink-3', pending: 'ink-3', stale: 'ink-3' };
const NODE = sheetPath(NW, NH);
const EC = (k: LEdge['kind']) => COLOR[k === 'bypass' ? 'flow' : k];
const ED = (k: LEdge['kind']) => k === 'bypass' ? '3 5' : BASE_DASH[k];

/**
 * 任务流程图：渲染任务钉住的组装流程（感知 / 生产 / 交付三条泳道），视觉与总览「交付流水线」一致：铅笔节点、稿纸泳道、正交折线、CSS 脉冲。
 * 节点状态：已完成（绿）/ 进行中（墨，页边方块）/ 卡住（黄）/ 被退回（棕，回路连线标次数）/ 跳过（虚线）/ 未开始（灰）。
 * compact：对话草稿卡里的小图（无状态、不可点）。
 */
export function TaskFlow({ graph, flow, selected, onSelect, compact, detail }: { graph: Graph; flow?: FlowInput; selected?: string | null; onSelect?: (id: string | null) => void; compact?: boolean; detail?: (n: LNode) => ReactNode }) {
  const uid = useId().replace(/:/g, '');
  const L = layoutTaskFlow(graph, flow ?? {}, { reveal: compact ? null : selected });
  const laneY = (i: number) => L.laneTop[i];
  const sel = L.nodes.find(n => n.n.id === selected) ?? null;
  return <div data-testid={compact ? 'task-flow-compact' : 'task-flow'}>
    <div className="overflow-x-auto">
      <svg width={compact ? '100%' : L.W} height={compact ? undefined : L.H} viewBox={`0 0 ${L.W} ${L.H}`} role="group" aria-label="任务流程图" className={compact ? 'max-h-64' : 'min-w-[960px]'}>
        <defs>
          <filter id={`${uid}-fiber`} x="-20%" y="-20%" width="140%" height="140%">
            <feTurbulence type="fractalNoise" baseFrequency="0.8" numOctaves="2" stitchTiles="stitch" />
            <feColorMatrix values={FIBER_MATRIX} />
          </filter>
          <pattern id={`${uid}-fiber-pat`} width="120" height="120" patternUnits="userSpaceOnUse">
            <rect width="120" height="120" filter={`url(#${uid}-fiber)`} />
          </pattern>
          {(['flow', 'loop', 'exc'] as const).map(k => <marker key={k} id={`${uid}-ah-${k}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M1.5,1.5 L8,5 L1.2,8.5" fill="none" stroke={`rgb(${COLOR[k]})`} strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" /></marker>)}
        </defs>
        {L.lanes.map((m, i) => {
          const y = laneY(i);
          return <g key={m}>
          <rect x={0} y={y} width={L.W} height={LANE_H} fill="rgb(var(--c-sunken))" opacity={0.55} />
          {laneRuleYs(y, LANE_H).map(yy => <line key={yy} x1={0} x2={L.W} y1={yy} y2={yy} stroke="rgb(var(--c-line))" strokeOpacity={0.45} strokeWidth={1} />)}
          <line x1={1.5} x2={1.5} y1={y} y2={y + LANE_H} stroke="rgb(var(--c-ink))" strokeWidth={1.25} strokeLinecap="round" />
          <line x1={5} x2={5} y1={y} y2={y + LANE_H} stroke="rgb(var(--c-ink))" strokeWidth={0.8} strokeLinecap="round" />
          <text x={16} y={y + LANE_H / 2 - 4} fontSize={13} fontWeight={700} fill="rgb(var(--c-primary-ink))" style={{ fontFamily: 'var(--font-display)', letterSpacing: '.08em' }}>{LANE_NAMES[m]?.name ?? m}</text>
          <text x={16} y={y + LANE_H / 2 + 14} fontSize={11} fill="rgb(var(--c-ink-3))">{LANE_NAMES[m]?.sub}</text>
        </g>; })}
        {L.edges.map((e, i) => { const col = `rgb(${EC(e.kind)})`; const live = !compact && e.active; const taken = (e.count ?? 0) > 0 || e.kind === 'loop' && !e.count && e.label?.text.startsWith('退回编码'); const failRoute = e.kind === 'loop' || e.kind === 'exc';
          const lw = e.label?.w ?? 0;
          return <g key={e.id} data-edge={e.id} data-kind={e.kind} data-active={e.active || undefined} data-count={e.count ?? undefined}>
          {e.title && <title>{e.title}</title>}
          <path d={e.path} fill="none" stroke={col} strokeOpacity={failRoute ? (taken ? 0.85 : 0.35) : e.kind === 'bypass' ? 0.5 : e.done || e.active ? 0.6 : 0.2} strokeWidth={failRoute && taken ? 1.8 : 1.4} strokeLinecap="round" strokeLinejoin="round" strokeDasharray={ED(e.kind)} markerEnd={`url(#${uid}-ah-${e.kind === 'bypass' ? 'flow' : e.kind})`} />
          {(live || (!compact && failRoute && taken)) && <path d={e.path} pathLength={100} fill="none" stroke={col} strokeWidth={e.kind === 'flow' ? 3 : 2.6} strokeLinecap="round" color={col} className="pulse-ambient" data-pulse="ambient"
            strokeDasharray={dashPattern(e.kind === 'bypass' ? 'flow' : e.kind, live ? 34 : 50)} style={{ '--off': `-${live ? 34 : 50}`, '--dur': `${live ? 1.6 : 3}s`, animationDelay: `-${(i * 0.37) % 1}s` } as CSSProperties} />}
          {e.label && !compact && <g transform={`translate(${e.label.x},${e.label.y})`} opacity={failRoute && !taken ? 0.7 : 1}><path d={sheetPath(Math.max(lw, 24), 18)} transform={`translate(${-Math.max(lw, 24) / 2},-9)`} fill="rgb(var(--c-surface))" stroke={col} strokeWidth={1} strokeLinecap="round" strokeLinejoin="round" strokeDasharray={taken ? undefined : '2 2'} /><text textAnchor="middle" y={4} fontSize={10.5} fill={taken ? 'rgb(var(--c-ink))' : 'rgb(var(--c-ink-3))'} className="tabular">{e.label.text}</text></g>}
        </g>; })}
        {L.nodes.map(ln => { const tv = `rgb(var(--c-${STATE_TONE[ln.state]}))`; const dim = ['pending', 'skipped', 'stale'].includes(ln.state);
          const rule = marginDash(ln.state === 'blocked' ? 'dotted' : ln.state === 'rejected' || ln.state === 'skipped' || (ln.n.optional && ln.state === 'pending') ? 'dashed' : 'solid');
          const click = () => onSelect?.(selected === ln.n.id ? null : ln.n.id);
          return <g key={ln.n.id} transform={`translate(${ln.x},${ln.y})`} data-testid={`tf-${ln.n.id}`} data-state={ln.state} role={compact ? undefined : 'button'} tabIndex={compact ? undefined : 0} aria-pressed={compact ? undefined : selected === ln.n.id}
            aria-label={`${ln.n.label}：${STATE_LABEL[ln.state]}`} onClick={compact ? undefined : click} onKeyDown={e => { if (!compact && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); click(); } }}
            className={cx('flow-node outline-none', !compact && 'cursor-pointer')}>
            <title>{[ln.n.label, STATE_LABEL[ln.state], ln.n.module === 'exception' ? '出错或次数用完时由系统接管' : SOURCE_LABEL[ln.n.source ?? 'preset'], ln.n.mandatory && `必选：${ln.n.mandatory}`, ln.state === 'skipped' && (flow?.records?.[ln.n.id]?.reason ?? '任务规划判断这次不需要'), ln.n.reason].filter(Boolean).join(' · ')}</title>
            <path d={NODE} fill="rgb(var(--c-raised))" fillOpacity={dim ? 0.55 : 0.96} />
            <path d={NODE} fill={`url(#${uid}-fiber-pat)`} opacity={dim ? 0.28 : 0.5} pointerEvents="none" />
            <path d={NODE} fill="none" stroke={tv} strokeWidth={1.25} strokeLinecap="round" strokeLinejoin="round" strokeDasharray={ln.state === 'skipped' || ln.n.optional && ln.state === 'pending' ? '4 3' : undefined} className="node-body" />
            <line x1={8} y1={11} x2={8} y2={NH - 11} stroke={tv} strokeWidth={1} strokeLinecap="round" strokeDasharray={rule} />
            <line x1={11.5} y1={11} x2={11.5} y2={NH - 11} stroke={tv} strokeWidth={1} strokeLinecap="round" strokeDasharray={rule} />
            <text x={18} y={22} fontSize={13} fontWeight={700} fill={dim ? 'rgb(var(--c-ink-3))' : 'rgb(var(--c-ink))'}>{fitText(`${ln.n.mandatory ? '◆ ' : ''}${ln.n.label}`, NW - 26, 13)}</text>
            {!compact && <text x={18} y={44} fontSize={11} fill={tv}>{fitText(`${STATE_LABEL[ln.state]}${ln.state !== 'skipped' && flow?.records?.[ln.n.id]?.runs ? ` · ${flow.records[ln.n.id].runs} 次` : ''}`, NW - 26, 11)}</text>}
            {compact && ln.n.source && ln.n.source !== 'preset' && <text x={18} y={44} fontSize={11} fill="rgb(var(--c-ink-3))">{SOURCE_LABEL[ln.n.source]}</text>}
            {ln.checks.length > 0 && <text x={18} y={NH - 4} fontSize={9.5} fill="rgb(var(--c-ink-3))">{fitText(`✓ ${ln.checks.map(c => c.label).join('、')}`, NW - 26, 9.5)}</text>}
            {(ln.state === 'current' || ln.state === 'blocked') && !compact && <rect x={3} y={14} width={3} height={3} fill={tv} className="node-live" />}
            {selected === ln.n.id && <path d={NODE} fill="none" stroke="rgb(var(--c-ink))" strokeWidth={1.05} strokeLinejoin="round" strokeLinecap="round" transform="translate(1.6,1.8)" pointerEvents="none" />}
            <path d={NODE} fill="none" className="node-focus" pointerEvents="none" />
          </g>; })}
      </svg>
    </div>
    {!compact && <ul className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-ink-2" aria-label="图例">
      {(['done', 'current', 'blocked', 'rejected', 'skipped', 'pending', 'stale'] as NodeState[]).map(s => <li key={s} className="flex items-center gap-1.5"><i className="h-2.5 w-2.5" style={{ background: `rgb(var(--c-${STATE_TONE[s]}))`, opacity: ['pending', 'skipped', 'stale'].includes(s) ? 0.5 : 1 }} />{STATE_LABEL[s]}</li>)}
      <li className="flex items-center gap-1.5"><svg width="28" height="8" aria-hidden><line x1="0" x2="28" y1="4" y2="4" stroke="rgb(var(--c-neon-cyan))" strokeWidth="2" strokeLinecap="round" /></svg>实线 = 正常推进</li>
      <li className="flex items-center gap-1.5"><svg width="28" height="8" aria-hidden><line x1="0" x2="28" y1="4" y2="4" stroke="rgb(var(--c-neon-magenta))" strokeWidth="2" strokeLinecap="round" strokeDasharray="6 4" /></svg>虚线 = 失败退回（测试未过、评审不通过）</li>
      <li className="flex items-center gap-1.5"><svg width="28" height="8" aria-hidden><line x1="0" x2="28" y1="4" y2="4" stroke="rgb(var(--c-neon-red))" strokeWidth="2" strokeLinecap="round" strokeDasharray="2 4" /></svg>点线 = 异常去向（出错、次数用完）</li>
      <li className="flex items-center gap-1.5"><svg width="28" height="8" aria-hidden><line x1="0" x2="28" y1="4" y2="4" stroke="rgb(var(--c-neon-cyan))" strokeWidth="2" strokeLinecap="round" strokeDasharray="3 5" /></svg>绕过 = 跳过的步骤</li>
      <li>×N = 这个任务实际走过的次数；没走过的去向默认不画，点击步骤时显示；悬停连线看完整说明</li>
      <li>◆ = 必选步骤 · 点击步骤查看过程记录与评审结果</li>
    </ul>}
    {sel && detail && <div className="mt-3 border border-line-strong/50 border-l-2 border-l-primary bg-raised/70 p-3" data-testid="task-flow-detail">{detail(sel)}</div>}
  </div>;
}
