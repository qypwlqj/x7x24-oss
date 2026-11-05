import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ambientPulse, connectFlowEvents, dashPattern, edgeKindOf, FORWARD, parseFlowEvent, PulseStore, resolveFlow, type FlowEvt } from './pulse';
import { edges, edgeTraffic } from './components/flowLayout';

const ev = (o: Partial<FlowEvt> & { to: string }): FlowEvt => ({ id: 1, taskId: 't', from: null, edge: null, at: '', ...o });

describe('resolveFlow', () => {
  it('新任务 create → signal>pool + pool 节点', () => expect(resolveFlow({ from: null, to: 'BACKLOG', edge: 'create' })).toEqual({ edges: ['signal>pool'], nodes: ['pool'] }));
  it('相邻前进 → 单条 flow 连线', () => expect(resolveFlow({ from: 'DEV', to: 'QA', edge: 'advance' })).toEqual({ edges: ['dev>qa'], nodes: ['qa'] }));
  it('跨级前进（PLANNING→DEV）沿主干点亮整段', () => expect(resolveFlow({ from: 'PLANNING', to: 'DEV', edge: 'dispatch' }).edges).toEqual(['planning>spec', 'spec>design', 'design>dev']));
  it('回路 / 异常 / 废弃走特殊连线', () => {
    expect(resolveFlow({ from: 'QA', to: 'PLANNING', edge: 'qa_loop' }).edges).toEqual(['qa>dev']);
    expect(resolveFlow({ from: 'ACCEPTANCE', to: 'PLANNING', edge: 'reject' }).edges).toEqual(['acceptance>planning']);
    expect(resolveFlow({ from: 'DELIVERED', to: 'PLANNING', edge: 'rollback' }).edges).toEqual(['released>planning']);
    expect(resolveFlow({ from: 'DEV', to: 'ANOMALY', edge: 'anomaly' }).edges).toEqual(['qa>anomaly']);
    expect(resolveFlow({ from: 'ANOMALY', to: 'DISCARDED', edge: 'auto_discard' })).toEqual({ edges: ['anomaly>discarded'], nodes: ['discarded'] });
  });
  it('没有对应连线时只点亮目标节点；DEGRADED 无节点', () => {
    expect(resolveFlow({ from: 'PRD_REVIEW', to: 'DISCARDED', edge: 'discard' })).toEqual({ edges: [], nodes: ['discarded'] });
    expect(resolveFlow({ from: 'PLANNING', to: 'DEGRADED', edge: 'degrade' })).toEqual({ edges: [], nodes: [] });
  });
  it('映射出的连线 id 全部存在于流程图布局中，且主干相邻节点都有连线', () => {
    const ids = new Set(edges().map((e) => e.id));
    for (let i = 0; i < FORWARD.length - 1; i++) expect(ids.has(`${FORWARD[i]}>${FORWARD[i + 1]}`)).toBe(true);
    for (const id of ['qa>dev', 'acceptance>planning', 'released>planning', 'qa>anomaly', 'anomaly>discarded']) expect(ids.has(id)).toBe(true);
    for (const e of edges()) expect(e.kind).toBe(edgeKindOf(e.id));
  });
});

describe('ambientPulse / dashPattern', () => {
  it('流量 0 不出脉冲；流量越大越密越快；演示加速更快', () => {
    expect(ambientPulse(0, 100).count).toBe(0);
    const a = ambientPulse(2, 100), b = ambientPulse(100, 100);
    expect(b.count).toBeGreaterThan(a.count); expect(b.dur).toBeLessThan(a.dur);
    expect(b.count).toBeLessThanOrEqual(5); expect(a.count).toBeGreaterThanOrEqual(1);
    expect(ambientPulse(50, 100, 3).dur).toBeLessThan(ambientPulse(50, 100, 1).dur);
    expect(ambientPulse(5, 0).count).toBe(0);
  });
  it('dash 图案周期 = 100/count（循环无缝）；异常为三连虚线', () => {
    const sum = (s: string) => s.split(' ').map(Number).reduce((x, y) => x + y, 0);
    for (const k of ['flow', 'loop', 'exc'] as const) for (const c of [1, 3, 5]) expect(sum(dashPattern(k, 100 / c))).toBeCloseTo(100 / c, 5);
    expect(dashPattern('exc', 50).split(' ')).toHaveLength(6); expect(dashPattern('flow', 50).split(' ')).toHaveLength(2);
  });
  it('edgeTraffic：带标签取 board.edges，其余取目标节点流量', () => {
    const board = { edges: { qa_loop: 7 }, nodes: [{ id: 'qa', flow: 40 }, { id: 'dev', flow: 55 }] };
    const es = edges();
    expect(edgeTraffic(es.find((e) => e.id === 'qa>dev')!, board)).toBe(7);
    expect(edgeTraffic(es.find((e) => e.id === 'dev>qa')!, board)).toBe(40);
  });
});

describe('parseFlowEvent', () => {
  it('解析合法事件，拒绝坏数据', () => {
    expect(parseFlowEvent('{"id":3,"taskId":"a","from":"DEV","to":"QA","edge":"advance","at":"x"}')).toMatchObject({ id: 3, to: 'QA', edge: 'advance' });
    expect(parseFlowEvent('not json')).toBeNull(); expect(parseFlowEvent('{"id":"x"}')).toBeNull(); expect(parseFlowEvent('{"id":1}')).toBeNull();
  });
});

describe('PulseStore', () => {
  beforeEach(() => { vi.useFakeTimers(); }); afterEach(() => { vi.useRealTimers(); });
  it('push 标记连线 / 节点，TTL 后清除，通知被批量合并', () => {
    const s = new PulseStore(1000, 40); const n = vi.fn(); s.subscribe(n);
    s.push(ev({ id: 1, from: 'DEV', to: 'QA', edge: 'advance' })); s.push(ev({ id: 2, from: 'QA', to: 'ACCEPTANCE', edge: 'advance' }));
    expect(Object.keys(s.getSnapshot().edges).sort()).toEqual(['dev>qa', 'qa>acceptance']); expect(s.getSnapshot().nodes).toHaveProperty('qa'); expect(s.getSnapshot().total).toBe(2);
    vi.advanceTimersByTime(41); expect(n).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(1000); expect(s.getSnapshot().edges).toEqual({}); expect(s.getSnapshot().nodes).toEqual({}); expect(s.getSnapshot().recent).toHaveLength(2);
    s.dispose();
  });
  it('同一连线重复事件 → seq 递增（组件 key 变化，动画重播）并续期 TTL', () => {
    const s = new PulseStore(1000, 10); s.push(ev({ id: 1, from: 'DEV', to: 'QA', edge: 'advance' })); const a = s.getSnapshot().edges['dev>qa'];
    vi.advanceTimersByTime(600); s.push(ev({ id: 2, from: 'DEV', to: 'QA', edge: 'advance' })); const b = s.getSnapshot().edges['dev>qa'];
    expect(b).toBeGreaterThan(a); vi.advanceTimersByTime(600); expect(s.getSnapshot().edges['dev>qa']).toBe(b); vi.advanceTimersByTime(500); expect(s.getSnapshot().edges['dev>qa']).toBeUndefined(); s.dispose();
  });
  it('recent 只保留最近 N 条，状态对象不可变', () => {
    const s = new PulseStore(1000, 10, 3); const s0 = s.getSnapshot();
    for (let i = 1; i <= 5; i++) s.push(ev({ id: i, from: 'DEV', to: 'QA', edge: 'advance' }));
    expect(s.getSnapshot().recent.map((r) => r.id)).toEqual([5, 4, 3]); expect(s.getSnapshot()).not.toBe(s0); expect(s0.total).toBe(0); s.dispose();
  });
});

describe('connectFlowEvents (SSE 客户端)', () => {
  class FakeES { static last: FakeES; h: Record<string, (e: any) => void> = {}; onerror: any; onopen: any; closed = false; constructor(public url: string) { FakeES.last = this; } addEventListener(t: string, f: (e: any) => void) { this.h[t] = f; } close() { this.closed = true; } }
  it('hello → live；flow 事件回调；坏帧忽略；error → retrying；close 关闭连接', () => {
    const got: FlowEvt[] = []; const st: string[] = [];
    const stop = connectFlowEvents('/api/events', (e) => got.push(e), (s) => st.push(s), FakeES as any);
    const es = FakeES.last; expect(es.url).toBe('/api/events'); expect(st).toEqual(['connecting']);
    es.h.hello({ data: '{}' }); es.h.flow({ data: '{"id":9,"taskId":null,"from":"DEV","to":"QA","edge":"advance","at":"t"}' }); es.h.flow({ data: 'garbage' });
    es.onerror(); expect(st).toEqual(['connecting', 'live', 'retrying']); expect(got).toHaveLength(1); expect(got[0].id).toBe(9);
    stop(); expect(es.closed).toBe(true);
  });
  it('board 事件（删除 / 重启 / 恢复）只触发看板刷新回调', () => {
    const boards: any[] = [];
    connectFlowEvents('/api/events', () => {}, () => {}, FakeES as any, (b) => boards.push(b));
    FakeES.last.h.board({ data: '{"taskId":"t1","type":"TASK_RESUMED","projectId":"p"}' }); FakeES.last.h.board({ data: 'bad' });
    expect(boards).toHaveLength(2); expect(boards[0].type).toBe('TASK_RESUMED');
  });
  it('票据模式：每次连接现取票据；出错关闭旧连接并换新票据重连', async () => {
    let n = 0; const st: string[] = []; const urls: string[] = [];
    const stop = connectFlowEvents(async () => `/api/events?ticket=t${++n}`, () => {}, (s) => st.push(s), class extends FakeES { constructor(u: string) { super(u); urls.push(u); } } as any, undefined, () => 0);
    await new Promise((r) => setTimeout(r, 0)); expect(urls).toEqual(['/api/events?ticket=t1']);
    const first = FakeES.last; first.onerror(); expect(first.closed).toBe(true); expect(st).toContain('retrying');
    await new Promise((r) => setTimeout(r, 5)); expect(urls).toEqual(['/api/events?ticket=t1', '/api/events?ticket=t2']);
    stop(); expect(FakeES.last.closed).toBe(true);
  });
  it('没有 EventSource 时报告 unsupported', () => { const st: string[] = []; connectFlowEvents('/x', () => {}, (s) => st.push(s), undefined as any); expect(st).toContain('unsupported'); });
});

it('流转事件保留项目和产品线归属，供筛选后的看板隔离脉冲', () => {
  const event = parseFlowEvent(JSON.stringify({ id: 41, taskId: 'task-a', projectId: 'project-a', productLine: 'line-a', from: 'DEV', to: 'QA', edge: 'advance', at: '2026-10-11T00:00:00Z' }));
  expect(event?.projectId).toBe('project-a'); expect(event?.productLine).toBe('line-a');
});
