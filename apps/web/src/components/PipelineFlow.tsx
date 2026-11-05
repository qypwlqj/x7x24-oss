import { useId, useSyncExternalStore, type CSSProperties } from 'react';
import type { BoardResponse } from '../api';
import { cx } from '../ui';
import { ambientPulse, dashPattern, edgeKindOf, type PulseStore } from '../pulse';
import { edges, edgeTraffic, H, LANES, lane, LANE_H, NH, NODES, NW, pos, TONE_VAR, W, type Kind } from './flowLayout';
import { FIBER_MATRIX, laneRuleYs, sheetPath } from '../ui/paperPath';

/**
 * 交付流水线：三条横向泳道，正交折线。
 * 脉冲动画（性能：每条连线最多 2 个 <path>，全部 CSS 动画，无逐帧 JS）：
 *  1) 持续脉冲 .pulse-ambient：stroke-dashoffset 动画，密度 / 速度 ∝ 该连线真实流量（ambientPulse）
 *  2) 事件脉冲 .pulse-burst：收到 SSE 流转事件后，在对应连线上跑一次墨色线段；目标节点 .node-hit 描一次边
 * 线型：正常流转=黑色实线，回路 / 返修=深棕虚线，异常 / 废弃=暗红点线。reduced-motion 下降级为静态高亮（见 index.css）。
 */
export const COLOR: Record<Kind, string> = { flow: 'var(--c-neon-cyan)', loop: 'var(--c-neon-magenta)', exc: 'var(--c-neon-red)' };
export const BASE_DASH: Record<Kind, string | undefined> = { flow: undefined, loop: '6 4', exc: '2 4' };
const NODE = sheetPath(NW, NH);
const ES = edges();

export function PipelineFlow({ board, selected, onSelect, store, speed = 1, live }: { board: BoardResponse; selected: string | null; onSelect: (id: string) => void; store?: PulseStore; speed?: number; live?: string }) {
  const nodes = Object.fromEntries(board.nodes.map((n) => [n.id, n]));
  const uid = useId().replace(/:/g, '');
  const pulse = useSyncExternalStore(store ? store.subscribe : noopSub, store ? store.getSnapshot : emptySnap);
  const traffic = ES.map((e) => edgeTraffic(e, board)); const max = Math.max(1, ...traffic);
  const burstMs = Math.round(900 / Math.max(0.5, speed));
  return (
    <div className="overflow-x-auto" data-testid="flow" data-speed={speed}>
      <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} role="group" aria-label="交付流水线图" className="min-w-[1040px]">
        <defs>
          <filter id={`${uid}-fiber`} x="-20%" y="-20%" width="140%" height="140%">
            <feTurbulence type="fractalNoise" baseFrequency="0.8" numOctaves="2" stitchTiles="stitch" />
            <feColorMatrix values={FIBER_MATRIX} />
          </filter>
          <pattern id={`${uid}-fiber-pat`} width="120" height="120" patternUnits="userSpaceOnUse">
            <rect width="120" height="120" filter={`url(#${uid}-fiber)`} />
          </pattern>
          {(['flow', 'loop', 'exc'] as Kind[]).map((k) => <marker key={k} id={`${uid}-ah-${k}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M1.5,1.5 L8,5 L1.2,8.5" fill="none" stroke={`rgb(${COLOR[k]})`} strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" /></marker>)}
        </defs>
        {LANES.map((l, i) => {
          const y = lane(i);
          return (
          <g key={l.name}>
            <rect x={0} y={y} width={W} height={LANE_H} fill="rgb(var(--c-sunken))" opacity={0.55} />
            {laneRuleYs(y, LANE_H).map((yy) => <line key={yy} x1={0} x2={W} y1={yy} y2={yy} stroke="rgb(var(--c-line))" strokeOpacity={0.45} strokeWidth={1} />)}
            <line x1={1.5} x2={1.5} y1={y} y2={y + LANE_H} stroke="rgb(var(--c-ink))" strokeWidth={1.25} strokeLinecap="round" />
            <line x1={5} x2={5} y1={y} y2={y + LANE_H} stroke="rgb(var(--c-ink))" strokeWidth={0.8} strokeLinecap="round" />
            <text x={16} y={y + LANE_H / 2 - 4} fontSize={13} fontWeight={700} fill="rgb(var(--c-primary-ink))" style={{ fontFamily: 'var(--font-display)', letterSpacing: '.08em' }}>{l.name}</text>
            <text x={16} y={y + LANE_H / 2 + 14} fontSize={11} fill="rgb(var(--c-ink-3))">{l.sub}</text>
          </g>
          );
        })}
        {ES.map((e, i) => {
          const cnt = e.label ? (board.edges[e.label.key] ?? 0) : 0; const col = `rgb(${COLOR[e.kind]})`; const amb = ambientPulse(traffic[i], max, speed);
          const burst = pulse.edges[e.id];
          const labelText = e.label ? (e.label.text ? `${e.label.text} ${cnt}` : (cnt > 0 ? String(cnt) : '')) : '';
          const lw = e.label?.text ? Math.max(76, labelText.length * 6.6) : 28;
          const lh = 18;
          return (
            <g key={e.id} data-edge={e.id} data-kind={e.kind} data-traffic={traffic[i]}>
              <path d={e.path} fill="none" stroke={col} strokeOpacity={traffic[i] > 0 ? 0.5 : 0.22} strokeWidth={1.4} strokeLinecap="round" strokeLinejoin="round" strokeDasharray={BASE_DASH[e.kind]} markerEnd={`url(#${uid}-ah-${e.kind})`} />
              {amb.count > 0 && <path d={e.path} pathLength={100} fill="none" stroke={col} strokeWidth={e.kind === 'flow' ? 3 : 2.6} strokeLinecap="round" color={col} className="pulse-ambient" data-pulse="ambient" data-count={amb.count}
                strokeDasharray={dashPattern(e.kind, amb.period)} style={{ '--off': `-${amb.period}`, '--dur': `${amb.dur}s`, animationDelay: `-${((i * 0.37) % 1) * amb.dur}s` } as CSSProperties} />}
              {burst !== undefined && <path key={burst} d={e.path} pathLength={100} fill="none" stroke="rgb(var(--c-ink))" strokeWidth={4} strokeLinecap="round" className="pulse-burst" data-pulse="burst" data-edge-burst={e.id}
                strokeDasharray="16 200" style={{ '--glow': col, '--burst-ms': `${burstMs}ms` } as CSSProperties} />}
              {e.label && labelText && <g transform={`translate(${e.label.x},${e.label.y})`}>
                <path d={sheetPath(lw, lh)} transform={`translate(${-lw / 2},${-lh / 2})`} fill="rgb(var(--c-surface))" stroke={col} strokeWidth={1} strokeOpacity={0.85} strokeLinecap="round" strokeLinejoin="round" />
                <text textAnchor="middle" y={4} fontSize={10.5} fill="rgb(var(--c-ink))" className="tabular">{labelText}</text>
              </g>}
            </g>
          );
        })}
        {NODES.map((n) => {
          const d = nodes[n.id]; if (!d) return null; const p = pos(n.id); const on = selected === n.id; const tv = `rgb(var(--c-${TONE_VAR[n.tone]}))`; const hit = pulse.nodes[n.id];
          return (
            <g key={n.id} transform={`translate(${p.x},${p.y})`} role="button" tabIndex={0} aria-pressed={on} aria-label={`${d.label}：驻留 ${d.resident}，流量 ${d.flow}`} data-testid={`node-${n.id}`}
              onClick={() => onSelect(n.id)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(n.id); } }} className={cx('flow-node cursor-pointer outline-none')}>
              <title>{`${d.label} · 当前 ${d.resident} / 累计进入 ${d.flow}`}</title>
              <path d={NODE} fill="rgb(var(--c-raised))" fillOpacity={0.96} />
              <path d={NODE} fill={`url(#${uid}-fiber-pat)`} opacity={0.5} pointerEvents="none" />
              <path d={NODE} fill="none" stroke={tv} strokeWidth={1.25} strokeOpacity={0.9} strokeLinecap="round" strokeLinejoin="round" className="node-body" />
              <line x1={8} y1={11} x2={8} y2={NH - 11} stroke={tv} strokeWidth={1} strokeLinecap="round" />
              <line x1={11.5} y1={11} x2={11.5} y2={NH - 11} stroke={tv} strokeWidth={1} strokeLinecap="round" />
              <text x={18} y={21} fontSize={13} fontWeight={500} fill="rgb(var(--c-ink))">{d.label}</text>
              <text x={18} y={47} fontSize={21} fontWeight={500} fill={d.resident ? tv : 'rgb(var(--c-ink-3))'} className="tabular">{d.resident}</text>
              <g transform={`translate(${NW - 8},${NH - 20})`}>
                <rect x={-(String(d.flow).length * 7.2 + 14)} y={0} width={String(d.flow).length * 7.2 + 14} height={16} fill="rgb(var(--c-sunken))" stroke="rgb(var(--c-line-strong))" strokeOpacity={0.6} />
                <text x={-(String(d.flow).length * 3.6 + 7)} y={12} textAnchor="middle" fontSize={10.5} fill="rgb(var(--c-ink-2))" className="tabular">{d.flow}</text>
              </g>
              {on && <path d={NODE} fill="none" stroke="rgb(var(--c-ink))" strokeWidth={1.05} strokeLinejoin="round" strokeLinecap="round" transform="translate(1.6,1.8)" pointerEvents="none" />}
              {hit !== undefined && <path key={hit} d={NODE} fill="none" stroke={tv} strokeWidth={3} strokeLinejoin="round" className="node-hit" data-node-hit={n.id} style={{ '--glow': tv, '--hit-ms': `${burstMs + 300}ms` } as CSSProperties} />}
              <path d={NODE} fill="none" className="node-focus" pointerEvents="none" />
            </g>
          );
        })}
      </svg>
      <ul className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-1 text-xs text-ink-2" aria-label="图例">
        <li className="flex items-center gap-1.5"><svg width="28" height="8" aria-hidden><line x1="0" x2="28" y1="4" y2="4" stroke="rgb(var(--c-neon-cyan))" strokeWidth="2" strokeLinecap="round" /></svg>正常流转（实线）</li>
        <li className="flex items-center gap-1.5"><svg width="28" height="8" aria-hidden><line x1="0" x2="28" y1="4" y2="4" stroke="rgb(var(--c-neon-magenta))" strokeWidth="2" strokeLinecap="round" strokeDasharray="6 4" /></svg>回路 / 返修 / 回滚（虚线）</li>
        <li className="flex items-center gap-1.5"><svg width="28" height="8" aria-hidden><line x1="0" x2="28" y1="4" y2="4" stroke="rgb(var(--c-neon-red))" strokeWidth="2" strokeLinecap="round" strokeDasharray="2 4" /></svg>异常 / 废弃（点线）</li>
        <li data-testid="flow-legend">节点大数字 = 当前停在该节点的任务数（随重启 / 恢复 / 删除实时变化）；小框 = 区间内累计进入过该节点的任务数；连线数字 = 区间内累计流转次数（历史累计，不会因恢复而减少）</li>
        {live && <li className="ml-auto flex items-center gap-1.5 font-mono" data-testid="live-status"><i className={cx('inline-block h-[3px] w-[3px]', live === 'live' ? 'bg-ink' : 'bg-warn')} />实时流转 {live === 'live' ? 'LIVE' : live === 'unsupported' ? 'N/A' : 'RETRY'} · {pulse.total} 条</li>}
      </ul>
      {store && pulse.recent.length > 0 && (
        <ol className="mt-2 grid gap-0.5 font-mono text-caption text-ink-3" aria-label="最近流转事件" data-testid="recent-events">
          {pulse.recent.slice(0, 4).map((r) => <li key={r.id}><span className="text-primary-ink">#{r.id}</span> {r.from ?? '∅'} → {r.to} <span className={edgeKindOf(r.edge === 'qa_loop' || r.edge === 'reject' || r.edge === 'rollback' ? 'qa>dev' : r.edge === 'anomaly' || r.edge === 'auto_discard' ? 'qa>anomaly' : 'x') === 'flow' ? 'text-ink-3' : 'text-accent-ink'}>[{r.edge}]</span></li>)}
        </ol>
      )}
    </div>
  );
}
const noopSub = () => () => {};
const EMPTY = { edges: {}, nodes: {}, recent: [], total: 0 };
const emptySnap = () => EMPTY;
