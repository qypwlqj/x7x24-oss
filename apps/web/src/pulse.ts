/**
 * 流水线「任务流转」脉冲逻辑（纯函数 + 小型 store，不依赖 React / DOM，便于单测）。
 *  - resolveFlow：把后端 STATUS_CHANGED 事件映射为流程图上的连线 id / 节点 id
 *  - ambientPulse：按连线真实流量计算持续脉冲的密度 / 速度
 *  - PulseStore：收到事件后给对应连线 / 节点打「一次性高亮」标记，TTL 到期自动清除
 *  - connectFlowEvents：订阅 /api/events（SSE），断线交给 EventSource 自动重连
 */
export interface FlowEvt { projectId?: string; productLine?: string; id: number; taskId: string | null; from: string | null; to: string; edge: string | null; at: string }
export type EdgeKind = 'flow' | 'loop' | 'exc';

export const STATUS_NODE: Record<string, string> = {
  BACKLOG: 'pool', PRD_REVIEW: 'prd', PLANNING: 'planning', SPEC: 'spec', DESIGN: 'design', DEV: 'dev', QA: 'qa',
  ACCEPTANCE: 'acceptance', DELIVERED: 'delivered', RELEASED: 'released', ANOMALY: 'anomaly', DISCARDED: 'discarded',
};
/** 主干顺序（相邻节点之间都存在一条 flow 连线） */
export const FORWARD = ['signal', 'pool', 'prd', 'planning', 'spec', 'design', 'dev', 'qa', 'acceptance', 'delivered', 'released'] as const;
/** 特殊边（回路 / 异常 / 废弃）→ 流程图连线 id */
const SPECIAL: Record<string, string> = { qa_loop: 'qa>dev', reject: 'acceptance>planning', rollback: 'released>planning', anomaly: 'qa>anomaly', auto_discard: 'anomaly>discarded' };
export const edgeId = (a: string, b: string) => `${a}>${b}`;
export const edgeKindOf = (id: string): EdgeKind => (id === 'qa>dev' || id === 'acceptance>planning' || id === 'released>planning' ? 'loop' : id === 'qa>anomaly' || id === 'anomaly>discarded' ? 'exc' : 'flow');

export function resolveFlow(ev: Pick<FlowEvt, 'from' | 'to' | 'edge'>): { edges: string[]; nodes: string[] } {
  const toNode = STATUS_NODE[ev.to]; const nodes = toNode ? [toNode] : [];
  if (ev.from === null) return { edges: ev.edge === 'create' || !ev.edge ? [edgeId('signal', 'pool')] : [], nodes };
  if (ev.edge && SPECIAL[ev.edge]) return { edges: [SPECIAL[ev.edge]], nodes };
  const fromNode = STATUS_NODE[ev.from];
  const a = fromNode ? (FORWARD as readonly string[]).indexOf(fromNode) : -1; const b = toNode ? (FORWARD as readonly string[]).indexOf(toNode) : -1;
  if (a >= 0 && b > a) return { edges: FORWARD.slice(a, b).map((n, i) => edgeId(n, FORWARD[a + i + 1])), nodes };
  return { edges: [], nodes }; // 无对应连线（如 resume / requeue / 直接废弃）：只让目标节点发光
}

/** 持续脉冲参数：traffic 为该连线的真实流量，max 为全图最大流量。流量越大 → 脉冲越密、越快；流量为 0 → 不出脉冲。 */
export interface Ambient { count: number; dur: number; period: number }
export function ambientPulse(traffic: number, max: number, speed = 1): Ambient {
  if (!(traffic > 0) || !(max > 0)) return { count: 0, dur: 0, period: 100 };
  const ratio = Math.min(1, Math.sqrt(traffic / max)); // 开方：比 log 更能拉开大 / 小流量的差距
  const count = 1 + Math.round(ratio * 4);               // 1..5 个光点同时在线上
  const dur = Math.max(0.5, (5.2 - 2.8 * ratio) / Math.max(0.1, speed)); // 秒：流量大 → 快；演示加速 → 更快
  return { count, dur, period: 100 / count };
}
/** stroke-dasharray（pathLength=100）：flow/loop = 单个短光点；exc = 三连虚线光点 */
export function dashPattern(kind: EdgeKind, period: number): string {
  if (kind === 'exc') { const d = 1.1; return `${d} ${d} ${d} ${d} ${d} ${Math.max(1, period - 5 * d)}`; }
  const d = kind === 'loop' ? 3.2 : 2.6; return `${d} ${Math.max(1, period - d)}`;
}

export function parseFlowEvent(data: string): FlowEvt | null {
  try { const o = JSON.parse(data); if (o && typeof o.to === 'string' && typeof o.id === 'number') return { id: o.id, taskId: o.taskId ?? null, from: o.from ?? null, to: o.to, edge: o.edge ?? null, at: String(o.at ?? ''), projectId: typeof o.projectId === 'string' ? o.projectId : undefined, productLine: typeof o.productLine === 'string' ? o.productLine : undefined }; } catch { /* ignore */ }
  return null;
}

export interface PulseState { edges: Record<string, number>; nodes: Record<string, number>; recent: FlowEvt[]; total: number }
type Timer = ReturnType<typeof setTimeout>;
export class PulseStore {
  private state: PulseState = { edges: {}, nodes: {}, recent: [], total: 0 };
  private subs = new Set<() => void>(); private seq = 0; private flushT: Timer | null = null; private timers = new Map<string, Timer>();
  constructor(private ttlMs = 1400, private batchMs = 40, private recentMax = 6) {}
  subscribe = (fn: () => void) => { this.subs.add(fn); return () => { this.subs.delete(fn); }; };
  getSnapshot = (): PulseState => this.state;
  setTtl(ms: number) { this.ttlMs = ms; }
  /** 事件到达：立即更新（同一批内合并通知，避免高频 re-render） */
  push(ev: FlowEvt) {
    const r = resolveFlow(ev); const s = this.state; const edges = { ...s.edges }; const nodes = { ...s.nodes };
    for (const e of r.edges) { edges[e] = ++this.seq; this.expire('e:' + e); }
    for (const n of r.nodes) { nodes[n] = ++this.seq; this.expire('n:' + n); }
    this.state = { edges, nodes, recent: [ev, ...s.recent].slice(0, this.recentMax), total: s.total + 1 };
    this.schedule();
  }
  private expire(key: string) {
    const old = this.timers.get(key); if (old) clearTimeout(old);
    this.timers.set(key, setTimeout(() => {
      this.timers.delete(key); const [k, id] = [key[0], key.slice(2)]; const s = this.state;
      if (k === 'e') { const edges = { ...s.edges }; delete edges[id]; this.state = { ...s, edges }; } else { const nodes = { ...s.nodes }; delete nodes[id]; this.state = { ...s, nodes }; }
      this.schedule();
    }, this.ttlMs));
  }
  private schedule() { if (this.flushT) return; this.flushT = setTimeout(() => { this.flushT = null; this.subs.forEach((f) => f()); }, this.batchMs); }
  dispose() { this.timers.forEach(clearTimeout); this.timers.clear(); if (this.flushT) clearTimeout(this.flushT); this.flushT = null; this.subs.clear(); }
}

export type LiveStatus = 'connecting' | 'live' | 'retrying' | 'unsupported';
interface ESLike { addEventListener(t: string, fn: (e: any) => void): void; close(): void; onerror: any; onopen: any }
/** 订阅 SSE；返回关闭函数。url 可以是函数（每次连接现取一次性票据）：
 *  EventSource 自带的重连会复用旧 URL，票据已用过会被拒并关闭连接，这时我们重新取票据并退避重连。 */
export function connectFlowEvents(url: string | (() => Promise<string>), onEvent: (e: FlowEvt) => void, onStatus: (s: LiveStatus) => void, ES: (new (u: string) => ESLike) | undefined = (globalThis as any).EventSource, onBoard?: (e: { taskId?: string; type?: string; projectId?: string; productLine?: string }) => void, retryMs = (n: number) => Math.min(30_000, 1000 * 2 ** n)): () => void {
  if (!ES) { onStatus('unsupported'); return () => {}; }
  let es: ESLike | null = null, stopped = false, fails = 0, timer: ReturnType<typeof setTimeout> | null = null;
  const retry = () => { if (stopped || timer) return; onStatus('retrying'); timer = setTimeout(() => { timer = null; open(); }, retryMs(fails++)); };
  const open = () => {
    if (typeof url === 'string') { connect(url); return; }
    url().then((u) => { if (!stopped) connect(u); }, () => retry());
  };
  const connect = (u: string) => {
    const cur = new ES(u); es = cur;
    cur.addEventListener('hello', () => { fails = 0; onStatus('live'); });
    cur.addEventListener('flow', (m: { data: string }) => { const e = parseFlowEvent(m.data); if (e) onEvent(e); });
    // 删除 / 编辑 / 暂停 / 重启 / 恢复等不产生 flow 的操作：只通知刷新看板数字
    if (onBoard) cur.addEventListener('board', (m: { data: string }) => { try { onBoard(JSON.parse(m.data)); } catch { onBoard({}); } });
    cur.onopen = () => onStatus('live');
    cur.onerror = () => {
      if (typeof url === 'string') { onStatus('retrying'); return; } // 固定地址：交给 EventSource 自己重连
      cur.close(); if (es === cur) es = null; retry();            // 票据模式：换新票据再连
    };
  };
  onStatus('connecting'); open();
  return () => { stopped = true; if (timer) clearTimeout(timer); es?.close(); };
}
