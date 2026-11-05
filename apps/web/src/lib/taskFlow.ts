/** 任务流程图（任务钉住的组装流程）：布局与节点状态，纯函数。视觉常量与总览「交付流水线」一致。 */
import { NW, NH, LANE_H, LEFT } from '../components/flowLayout';
import { bySegment, childChecks, type GNode, type Graph } from './pipeline';

export type NodeState = 'done' | 'current' | 'blocked' | 'rejected' | 'skipped' | 'pending' | 'stale';
export interface NodeHistory { id?: string; state: string; outcome?: string; version?: number; headSha?: string; iteration?: number; endedAt?: string }
export interface NodeRec extends NodeHistory { runs: number; usd: number; tokens: number; reason?: string; valid?: boolean; history?: NodeHistory[] }
/** 失败 / 异常去向（后端按引擎路由规则给出，count = 实际走过次数） */
export interface Route { from: string; to: string; kind: 'loop' | 'exc'; label: string; count: number; max?: number }
export interface FlowInput { currentNode?: string | null; status?: string; executionState?: string; paused?: boolean; records?: Record<string, NodeRec>; nodeLoops?: Record<string, number>; routes?: Route[] }
export interface LNode { n: GNode; lane: number; x: number; y: number; cx: number; cy: number; t: number; b: number; r: number; state: NodeState; loops: number; checks: GNode[] }
export interface LEdge { id: string; from: string; to: string; kind: 'flow' | 'loop' | 'exc' | 'bypass'; path: string; active: boolean; done: boolean; count?: number; title?: string; label?: { x: number; y: number; text: string; w: number } }
export const LANE_NAMES: Record<string, { name: string; sub: string }> = { sensing: { name: '感知', sub: '收录 · 评审 · 规划' }, production: { name: '生产', sub: '设计 → 编码' }, delivery: { name: '交付', sub: '测试 · 评审 · 上线' }, exception: { name: '例外', sub: '卡住 · 预算用完' } };
/** 例外泳道里的处理节点（不在组装的流程里，由引擎在出错 / 次数用完时接管） */
export const HANDLERS: Record<string, string> = { ANOMALY: '异常处理', DEGRADED: '省钱模式' };
const BLOCKED = new Set(['ANOMALY', 'DEGRADED']);
const END = new Set(['DELIVERED', 'RELEASED', 'DISCARDED']);

/** 单个节点的状态：已完成 / 进行中 / 卡住（异常、降级、暂停）/ 被退回过 / 跳过 / 未开始 */
export function nodeState(n: GNode, f: FlowInput, passed: boolean): NodeState {
  const rec = f.records?.[n.id]; const loops = f.nodeLoops?.[n.id] ?? 0; const cur = f.currentNode === n.id;
  if (cur && f.executionState === 'WAITING_CONFIGURATION') return 'blocked';
  if (cur && !END.has(f.status ?? '')) return BLOCKED.has(f.status ?? '') || f.paused || ['FAILED', 'BLOCKED', 'PAUSED'].includes(f.executionState ?? '') ? 'blocked' : 'current';
  if (rec?.valid === false) return 'stale';
  if (rec?.state === 'SKIPPED') return 'skipped';
  if (rec?.state === 'FAILED' || rec?.state === 'BLOCKED') return 'blocked';
  if (rec?.outcome === 'FAILED') return 'rejected';
  if (rec?.outcome === 'BLOCKED' || rec?.outcome === 'ERROR') return 'blocked';
  if (rec?.outcome === 'PASSED') return 'done';
  if (rec?.outcome) return 'pending';
  if (loops > 0 && rec?.state !== 'SUCCEEDED') return 'rejected';
  if (rec?.state === 'SUCCEEDED') return loops > 0 ? 'rejected' : 'done';
  if (rec && rec.runs > 0) return 'pending';
  if (passed) return 'skipped';
  return 'pending';
}

/** 估算文字宽度（中文按 1 个字号，其它按 0.6 个字号） */
export function textWidth(text: string, size: number) { let w = 0; for (const ch of text) w += /[\u2e80-\uffff]/.test(ch) ? size : size * 0.6; return w; }
/** 按宽度截断，超出加「…」；完整文字放在 title 提示里 */
export function fitText(text: string, maxW: number, size: number) {
  if (textWidth(text, size) <= maxW) return text;
  let out = ''; for (const ch of text) { if (textWidth(out + ch + '…', size) > maxW) break; out += ch; }
  return out + '…';
}

/** 任务流程图专用的列宽：节点之间留出 38px 的竖向通道，绕行的连线从这里穿过泳道，不会压到节点 */
export const TF_COL = 170; const GUT = TF_COL - NW;
const TRACK = 20, CH_PAD = 13, CH_MIN = 26, CH_EDGE = 10, LABEL_H = 18, LABEL_MAX = 116, LABEL_FS = 10.5;
export interface LayoutOpts { /** 额外显示这个步骤所有可能的去向（包括没走过的）；默认只画实际走过的 */ reveal?: string | null }
type Side = 'top' | 'bottom';
interface Seg { ch: number; x1: number; x2: number; track?: number }
interface Plan { id: string; a: LNode; b: LNode; aSide: Side; bSide: Side; ax: number; bx: number; segs: Seg[]; gx?: number; flow: boolean }

/**
 * 三条泳道（感知 / 生产 / 交付）+ 出错时的「例外」泳道。
 * 连线按正交折线走：横向只在泳道之间的通道里走（每条占一条轨道，通道高度随轨道数变化），跨过多条泳道时从节点之间的竖向通道穿过，不压节点。
 * 同一对步骤之间的往返连线合成一条线（两端箭头），标签各自放、互不重叠；没走过的去向默认不画，点击步骤时才显示。
 */
export function layoutTaskFlow(g: Graph, f: FlowInput = {}, o: LayoutOpts = {}) {
  const segs = bySegment(g); const order = ['sensing', 'production', 'delivery'];
  const lanes = order.filter(m => segs.some(s => s.module === m));
  const laneOf = (m: string) => Math.max(0, lanes.indexOf(m));
  const flat: GNode[] = []; const count: Record<number, number> = {};
  const nodes: LNode[] = [];
  for (const s of segs) for (const n of s.nodes) flat.push(n);
  const curIdx = flat.findIndex(n => n.id === f.currentNode);
  const mk = (n: GNode, lane: number, x: number, state: NodeState, loops: number, checks: GNode[]): LNode => ({ n, lane, x, y: 0, cx: x + NW / 2, cy: 0, t: 0, b: 0, r: x + NW, state, loops, checks });
  flat.forEach((n, i) => {
    const li = laneOf(n.module ?? 'production'); const k = count[li] ?? 0; count[li] = k + 1;
    const passed = END.has(f.status ?? '') ? true : curIdx >= 0 && i < curIdx;
    nodes.push(mk(n, li, LEFT + k * TF_COL, nodeState(n, f, passed), f.nodeLoops?.[n.id] ?? 0, childChecks(g, n.id)));
  });
  // 去向：默认只画走过的（count > 0），选中的步骤额外显示它所有可能的去向
  const all = f.routes ?? [];
  const reveal = o.reveal ?? null;
  let routes = all.filter(r => r.count > 0 || (reveal != null && (r.from === reveal || r.to === reveal)));
  const synthetic = !all.length; // 旧数据没有去向：被退回的评审节点画回到编码的回路
  if (synthetic && nodes.some(n => n.n.id === 'DEV')) routes = nodes.filter(n => n.loops > 0 && n.n.id !== 'DEV').map(n => ({ from: n.n.id, to: 'DEV', kind: 'loop' as const, label: `退回编码 ${n.loops}`, count: n.loops }));
  const handlers = Object.keys(HANDLERS).filter(h => routes.some(r => r.to === h || r.from === h));
  if (handlers.length) {
    lanes.push('exception'); const li = lanes.length - 1; const devN = nodes.find(n => n.n.id === 'DEV'); const qaN = nodes.find(n => n.n.id === 'QA');
    const used = new Set<number>();
    handlers.forEach((h, k) => {
      let x = (h === 'ANOMALY' ? devN?.x : qaN?.x) ?? LEFT + k * TF_COL; while (used.has(x)) x += TF_COL; used.add(x);
      const cur = f.status === h;
      nodes.push(mk({ id: h, stage: h, label: HANDLERS[h], module: 'exception' }, li, x, cur ? 'blocked' : routes.some(r => r.to === h && r.count > 0) ? 'done' : 'pending', 0, []));
    });
  }
  const NL = lanes.length;
  const by = Object.fromEntries(nodes.map(n => [n.n.id, n]));
  const main = nodes.filter(n => n.n.module !== 'exception');

  // ── 1. 连线规划（只定横向位置；纵向位置等通道高度算出来之后再定）
  const plans: Plan[] = []; const gutterUse: Record<string, number> = {};
  const plan = (id: string, a: LNode, b: LNode, flow: boolean): Plan => {
    let aSide: Side, bSide: Side; const sg: Seg[] = []; let gx: number | undefined;
    const via = (from: number, to: number) => { // 竖向通道：选目标旁边、朝出发方向的那一条
      const left = a.x < b.x && b.x - GUT / 2 > LEFT; const base = left ? b.x - GUT / 2 : b.r + GUT / 2; const key = `${base}`;
      const k = (gutterUse[key] = (gutterUse[key] ?? 0) + 1) - 1; gx = Math.round(base + (k % 2 ? 1 : -1) * Math.ceil(k / 2) * 8);
      sg.push({ ch: from, x1: 0, x2: gx }, { ch: to, x1: gx, x2: 0 });
    };
    if (a.lane === b.lane) { aSide = bSide = 'top'; sg.push({ ch: a.lane, x1: 0, x2: 0 }); }
    else if (a.lane > b.lane) { aSide = 'top'; bSide = 'bottom'; if (a.lane === b.lane + 1) sg.push({ ch: a.lane, x1: 0, x2: 0 }); else via(a.lane, b.lane + 1); }
    else { aSide = 'bottom'; bSide = 'top'; if (b.lane === a.lane + 1) sg.push({ ch: a.lane + 1, x1: 0, x2: 0 }); else via(a.lane + 1, b.lane); }
    const p: Plan = { id, a, b, aSide, bSide, ax: a.cx, bx: b.cx, segs: sg, gx, flow }; plans.push(p); return p;
  };
  const edges: LEdge[] = [];
  const flowPlans: [Plan, LEdge][] = [];
  for (let i = 0; i + 1 < main.length; i++) {
    const a = main[i], b = main[i + 1];
    const e: LEdge = { id: `${a.n.id}>${b.n.id}`, from: a.n.id, to: b.n.id, kind: 'flow', path: '', active: b.state === 'current' || b.state === 'blocked', done: a.state === 'done' && !['pending', 'stale'].includes(b.state) };
    edges.push(e); if (a.lane !== b.lane) flowPlans.push([plan(e.id, a, b, true), e]);
  }
  // 同一对步骤（含往返）合成一条线
  const pairPlan: Record<string, Plan> = {}; const routeEdges: [Route, Plan, boolean][] = [];
  for (const r of [...routes].sort((x, y) => y.count - x.count)) {
    const a = by[r.from], b = by[r.to]; if (!a || !b || a === b) continue;
    const key = [r.from, r.to].sort().join('|'); const ex = pairPlan[key];
    if (ex) routeEdges.push([r, ex, ex.a !== a]); else routeEdges.push([r, (pairPlan[key] = plan(`${r.from}>${r.to}`, a, b, false)), false]);
  }
  // ── 2. 端口：同一节点同一侧的多条线均匀错开（按另一端的位置排序，减少交叉）；正常推进的线占中间
  const ends: Record<string, { p: Plan; end: 'a' | 'b'; other: number }[]> = {};
  for (const p of plans) {
    (ends[`${p.a.n.id}:${p.aSide}`] ??= []).push({ p, end: 'a', other: p.gx ?? p.b.cx });
    (ends[`${p.b.n.id}:${p.bSide}`] ??= []).push({ p, end: 'b', other: p.gx ?? p.a.cx });
  }
  for (const [k, list] of Object.entries(ends)) {
    const n = by[k.split(':')[0]]; const rest = list.filter(e => !e.p.flow).sort((x, y) => x.other - y.other); const hasFlow = rest.length < list.length;
    rest.forEach((e, i) => {
      let x = rest.length === 1 ? n.cx + (hasFlow ? 26 : 0) : n.x + 18 + ((NW - 36) * i) / (rest.length - 1);
      if (hasFlow && Math.abs(x - n.cx) < 9) x += 14;
      if (e.end === 'a') e.p.ax = Math.round(x); else e.p.bx = Math.round(x);
    });
  }
  for (const p of plans) { const s0 = p.segs[0], s1 = p.segs[p.segs.length - 1]; s0.x1 = p.ax; if (p.segs.length === 1) s0.x2 = p.bx; else s1.x2 = p.bx; }
  // ── 3. 轨道：每个通道里横向线段互不重叠地分配轨道（正常推进的线先占）
  const chTracks: { x1: number; x2: number }[][][] = Array.from({ length: NL + 1 }, () => []);
  const place = (s: Seg) => {
    const lo = Math.min(s.x1, s.x2) - 8, hi = Math.max(s.x1, s.x2) + 8; const tr = chTracks[s.ch];
    let t = 0; while (tr[t] && tr[t].some(iv => iv.x1 < hi && lo < iv.x2)) t++;
    (tr[t] ??= []).push({ x1: lo, x2: hi }); s.track = t;
  };
  for (const p of [...plans].sort((x, y) => Number(y.flow) - Number(x.flow))) for (const s of p.segs) place(s);
  const chH = chTracks.map((tr, c) => tr.length ? CH_PAD * 2 + (tr.length - 1) * TRACK : c === 0 || c === NL ? CH_EDGE : CH_MIN);
  const laneTop: number[] = []; let y = chH[0];
  for (let i = 0; i < NL; i++) { laneTop.push(y); y += LANE_H + chH[i + 1]; }
  const H = y; const chTop = (c: number) => (c === 0 ? 0 : laneTop[c - 1] + LANE_H);
  const trackY = (s: Seg) => Math.round(chTop(s.ch) + CH_PAD + (s.track ?? 0) * TRACK);
  for (const n of nodes) { n.y = laneTop[n.lane] + (LANE_H - NH) / 2; n.t = n.y; n.b = n.y + NH; n.cy = n.y + NH / 2; }
  // ── 4. 路径
  const pts = (p: Plan): [number, number][] => {
    const ay = p.aSide === 'top' ? p.a.t : p.a.b, by_ = p.bSide === 'top' ? p.b.t : p.b.b;
    const out: [number, number][] = [[p.ax, ay]];
    p.segs.forEach((s, i) => { const ty = trackY(s); out.push([i === 0 ? p.ax : s.x1, ty]); out.push([i === p.segs.length - 1 ? p.bx : s.x2, ty]); });
    out.push([p.bx, by_]); return out;
  };
  const d = (ps: [number, number][]) => 'M' + ps.map(([x, yy]) => `${Math.round(x)},${Math.round(yy)}`).join(' L');
  for (const [p, e] of flowPlans) e.path = d(pts(p));
  for (const e of edges) if (!e.path) { const a = by[e.from], b = by[e.to]; e.path = `M${a.r},${Math.round(a.cy)} H${b.x}`; }
  // 跳过的步骤：画一条绕过它的虚线（同泳道前后两个节点之间，走节点下方）
  const rects: { x: number; y: number; w: number; h: number }[] = nodes.map(n => ({ x: n.x - 4, y: n.y - 4, w: NW + 8, h: NH + 8 }));
  for (let i = 1; i + 1 < main.length; i++) {
    const s0 = main[i]; if (s0.state !== 'skipped') continue;
    const a = main[i - 1], b = main[i + 1]; if (a.lane !== s0.lane || b.lane !== s0.lane) continue;
    const yy = s0.b + 12; edges.push({ id: `${a.n.id}~${b.n.id}`, from: a.n.id, to: b.n.id, kind: 'bypass', path: `M${a.cx},${a.b} V${yy} H${b.cx} V${b.b}`, active: false, done: true, label: { x: s0.cx, y: yy, text: '跳过', w: 40 } });
    rects.push({ x: s0.cx - 22, y: yy - 10, w: 44, h: 20 });
  }
  // ── 5. 标签：放在线段上，与节点和已放的标签都不重叠；放不下时只写次数
  const maxK = Math.max(1, ...Object.values(count), ...nodes.map(n => Math.round((n.x - LEFT) / TF_COL) + 1)); const W = LEFT + maxK * TF_COL;
  const hit = (r: { x: number; y: number; w: number; h: number }) => r.x < LEFT - 6 || r.x + r.w > W - 2 || rects.some(q => r.x < q.x + q.w && q.x < r.x + r.w && r.y < q.y + q.h && q.y < r.y + r.h);
  const putLabel = (p: Plan, full: string, short: string, rev: boolean) => {
    const order = rev ? [...p.segs] : [...p.segs].reverse(); // 标签靠近箭头所指的一端
    for (const text of [fitText(full, LABEL_MAX, LABEL_FS), short]) {
      const w = Math.max(36, Math.ceil(textWidth(text, LABEL_FS)) + 14);
      for (const s of order) {
        const ty = trackY(s); const lo = Math.min(s.x1, s.x2), hi = Math.max(s.x1, s.x2); const mid = (lo + hi) / 2;
        const cands = [mid]; for (let k = 1; k <= 12; k++) cands.push(mid + k * 22, mid - k * 22);
        for (const cxx of cands) {
          if (hi - lo >= w && (cxx - w / 2 < lo - 4 || cxx + w / 2 > hi + 4)) continue;
          const r = { x: cxx - w / 2 - 2, y: ty - LABEL_H / 2 - 1, w: w + 4, h: LABEL_H + 2 };
          if (!hit(r)) { rects.push(r); return { x: Math.round(cxx), y: ty, text, w }; }
        }
      }
    }
    const s = order[0]; const w = Math.max(36, Math.ceil(textWidth(short, LABEL_FS)) + 14); return { x: Math.round(Math.min(W - w / 2 - 2, Math.max(LEFT + w / 2, (s.x1 + s.x2) / 2))), y: trackY(s), text: short, w };
  };
  for (const [r, p, rev] of routeEdges) {
    const ps = pts(p); const path = d(rev ? [...ps].reverse() : ps);
    const taken = r.count > 0; const live = taken && (f.currentNode === r.to || f.status === r.to);
    const full = synthetic ? r.label : `${r.label}${taken ? ` ×${r.count}` : ''}`; const short = taken ? `×${r.count}` : fitText(r.label, 40, LABEL_FS);
    const lb = putLabel(p, full, short, rev);
    edges.push({ id: `${r.from}>${r.to}`, from: r.from, to: r.to, kind: r.kind, path, active: live, done: taken, count: r.count, title: `${by[r.from].n.label} → ${by[r.to].n.label}：${r.label}${taken ? `，走过 ${r.count} 次` : '（这次没走过）'}${r.max ? `，上限 ${r.max} 次` : ''}`, label: lb });
  }
  return { nodes, edges, lanes, laneTop, W, H };
}

/** 节点对应的评审门（verdict.gate） */
export function gateMatches(nodeId: string, gate: string): boolean {
  const g = gate.toLowerCase(), id = nodeId.toLowerCase();
  if (g === id) return true;
  if (nodeId === 'ACCEPTANCE') return g.startsWith('acceptance');
  if (['API_DESIGN', 'DB_SCHEMA', 'UX_DESIGN'].includes(nodeId)) return g === 'design_review';
  return false;
}
/** 节点对应的过程记录：阶段名等于节点 id 或节点所在阶段 */
export const evidenceMatches = (n: GNode, stage: string) => stage === n.id || (stage === n.stage && n.stage !== 'DESIGN') || (n.stage === 'DESIGN' && stage === 'DESIGN' && n.id === 'UX_DESIGN');
export const STATE_LABEL: Record<NodeState, string> = { done: '已完成', current: '进行中', blocked: '卡住', rejected: '未通过', skipped: '跳过', pending: '未开始', stale: '待复验' };
