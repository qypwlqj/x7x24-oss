
/** 流水线图布局（纯数据，无 React）：三条横向泳道的节点坐标 + 正交折线连线。连线 id = `from>to`，与 pulse.ts 的映射一致。 */
export const NW = 132, NH = 60, LANE_H = 108, GAP = 40, LEFT = 112, COL = 156;
export const lane = (i: number) => i * (LANE_H + GAP);
export interface N { id: string; lane: number; x: number; tone: 'info' | 'primary' | 'success' | 'warn' | 'neutral' }
export const NODES: N[] = [
  { id: 'signal', lane: 0, x: LEFT, tone: 'info' }, { id: 'pool', lane: 0, x: LEFT + COL, tone: 'info' }, { id: 'prd', lane: 0, x: LEFT + 2 * COL, tone: 'info' },
  ...['planning', 'spec', 'design', 'dev', 'qa', 'acceptance'].map((id, i): N => ({ id, lane: 1, x: LEFT + i * COL, tone: 'primary' })),
  { id: 'delivered', lane: 2, x: LEFT, tone: 'success' }, { id: 'released', lane: 2, x: LEFT + COL, tone: 'success' },
  { id: 'anomaly', lane: 2, x: LEFT + 4 * COL - 20, tone: 'warn' }, { id: 'discarded', lane: 2, x: LEFT + 5 * COL + 12, tone: 'neutral' },
];
export const LANES = [{ name: '感知', sub: '信号 · 评审' }, { name: '生产', sub: '规划 → 验收' }, { name: '交付 · 例外', sub: '上线 · 巡查' }];
export const pos = (id: string) => { const n = NODES.find((x) => x.id === id)!; return { x: n.x, y: lane(n.lane) + (LANE_H - NH) / 2, cx: n.x + NW / 2, cy: lane(n.lane) + LANE_H / 2, r: n.x + NW, b: lane(n.lane) + (LANE_H + NH) / 2, t: lane(n.lane) + (LANE_H - NH) / 2 }; };
export const TONE_VAR = { info: 'info', primary: 'primary', success: 'success', warn: 'warn', neutral: 'ink-3' } as const;

export type Kind = 'flow' | 'loop' | 'exc';
export interface E { id: string; from: string; to: string; kind: Kind; path: string; label?: { key: string; x: number; y: number; text: string } }
export function edges(): E[] {
  const h = (a: string, b: string): Omit<E, 'id'> => { const A = pos(a), B = pos(b); return { from: a, to: b, kind: 'flow', path: `M${A.r},${A.cy} H${B.x}` }; };
  const g1 = lane(0) + LANE_H, g2 = lane(1) + LANE_H;               // 泳道间隙起点
  const P = pos, pr = P('prd'), pl = P('planning'), ac = P('acceptance'), dv = P('delivered'), rl = P('released'), qa = P('qa'), dev = P('dev'), an = P('anomaly'), di = P('discarded');
  const list: Omit<E, 'id'>[] = [
    h('signal', 'pool'), h('pool', 'prd'),
    { from: 'prd', to: 'planning', kind: 'flow', path: `M${pr.cx},${pr.b} V${g1 + 14} H${pl.cx} V${pl.t}` },
    h('planning', 'spec'), h('spec', 'design'), h('design', 'dev'), h('dev', 'qa'), h('qa', 'acceptance'),
    { from: 'acceptance', to: 'delivered', kind: 'flow', path: `M${ac.cx},${ac.b} V${g2 + 12} H${dv.cx} V${dv.t}` },
    h('delivered', 'released'),
    // 回路
    { from: 'qa', to: 'dev', kind: 'loop', path: `M${qa.cx},${qa.t} V${qa.t - 14} H${dev.cx} V${dev.t}`, label: { key: 'qa_loop', x: (qa.cx + dev.cx) / 2, y: qa.t - 14, text: 'Loop 返修' } },
    { from: 'acceptance', to: 'planning', kind: 'loop', path: `M${ac.cx + 24},${ac.t} V${ac.t - 28} H${pl.cx - 24} V${pl.t}`, label: { key: 'reject', x: (ac.cx + pl.cx) / 2, y: ac.t - 28, text: '验收驳回' } },
    { from: 'released', to: 'planning', kind: 'loop', path: `M${rl.cx},${rl.t} V${g2 + 28} H${pl.cx - 30} V${pl.b}`, label: { key: 'rollback', x: rl.cx + 8, y: g2 + 28, text: '自动回滚' } },
    // 异常 / 废弃
    { from: 'qa', to: 'anomaly', kind: 'exc', path: `M${qa.cx + 30},${qa.b} V${g2 + 20} H${an.cx} V${an.t}`, label: { key: 'anomaly', x: an.cx + 4, y: g2 + 20, text: '异常阻塞' } },
    { from: 'anomaly', to: 'discarded', kind: 'exc', path: `M${an.r},${an.cy} H${di.x}`, label: { key: 'auto_discard', x: (an.r + di.x) / 2, y: an.cy - 10, text: '' } },
  ];
  return list.map((e) => ({ ...e, id: `${e.from}>${e.to}` }));
}
export const W = LEFT + 6 * COL + 8, H = lane(2) + LANE_H;
/** 连线的真实流量：带计数标签的回路 / 异常连线取 board.edges[key]；其余取目标节点的「曾进入」流量。 */
export function edgeTraffic(e: E, board: { edges: Record<string, number>; nodes: { id: string; flow: number }[] }): number {
  if (e.label) return board.edges[e.label.key] ?? 0;
  return board.nodes.find((n) => n.id === e.to)?.flow ?? 0;
}
