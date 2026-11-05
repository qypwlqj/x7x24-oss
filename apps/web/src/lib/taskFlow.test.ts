import { describe, expect, it } from 'vitest';
import { gateMatches, layoutTaskFlow, nodeState } from './taskFlow';
import type { Graph } from './pipeline';

const g: Graph = { schema: 2, edges: [], nodes: [
  { id: 'INTAKE', stage: 'BACKLOG', label: '需求收录', module: 'sensing' }, { id: 'PLANNING', stage: 'PLANNING', label: '任务规划', module: 'sensing' },
  { id: 'SPEC', stage: 'SPEC', label: '需求策划', module: 'production', optional: true }, { id: 'DB_SCHEMA', stage: 'DB_SCHEMA', label: '数据库变更设计', module: 'production' },
  { id: 'DEV', stage: 'DEV', label: '编码', module: 'production' }, { id: 'QA', stage: 'QA', label: '测试', module: 'delivery' },
  { id: 'CHECK_SQL_SAFETY', stage: 'QA', label: 'SQL 安全检查', parent: 'QA' }, { id: 'SEC_AUDIT', stage: 'SEC_AUDIT', label: '安全审计', module: 'delivery' },
  { id: 'ACCEPTANCE', stage: 'ACCEPTANCE', label: '验收', module: 'delivery' },
] };
const rec = (state: string, runs = 1) => ({ state, runs, usd: 0, tokens: 0 });

describe('任务流程图', () => {
  it('三条泳道，检查挂在测试下，跨泳道折线', () => {
    const L = layoutTaskFlow(g);
    expect(L.lanes).toEqual(['sensing', 'production', 'delivery']);
    expect(L.nodes.map(n => n.n.id)).not.toContain('CHECK_SQL_SAFETY');
    expect(L.nodes.find(n => n.n.id === 'QA')!.checks.map(c => c.id)).toEqual(['CHECK_SQL_SAFETY']);
    expect(L.edges.find(e => e.id === 'PLANNING>SPEC')!.path.split(' L').length).toBeGreaterThan(2);
    expect(L.edges.find(e => e.id === 'INTAKE>PLANNING')!.path).toMatch(/^M\d+,\d+ H\d+$/);
  });
  it('真实 lms 运行：安全审计退回 3 次后降级 → 卡住 + 回到编码的回路', () => {
    const f = { currentNode: 'SEC_AUDIT', status: 'DEGRADED', records: { INTAKE: rec('SUCCEEDED'), PLANNING: rec('SUCCEEDED'), SPEC: rec('SUCCEEDED'), DB_SCHEMA: rec('SUCCEEDED'), DEV: rec('SUCCEEDED', 4), QA: rec('SUCCEEDED', 3), SEC_AUDIT: rec('SUCCEEDED', 3) }, nodeLoops: { SEC_AUDIT: 3 } };
    const L = layoutTaskFlow(g, f); const st = Object.fromEntries(L.nodes.map(n => [n.n.id, n.state]));
    expect(st).toMatchObject({ INTAKE: 'done', DEV: 'done', SEC_AUDIT: 'blocked', ACCEPTANCE: 'pending' });
    const loop = L.edges.find(e => e.kind === 'loop')!;
    expect(loop).toMatchObject({ from: 'SEC_AUDIT', to: 'DEV' }); expect(loop.label!.text).toBe('退回编码 3');
    expect(L.edges.find(e => e.id === 'QA>SEC_AUDIT')!.active).toBe(true);
  });
  it('节点状态：进行中 / 跳过 / 未开始 / 被退回', () => {
    const n = g.nodes[2];
    expect(nodeState(n, { currentNode: 'SPEC', status: 'SPEC' }, false)).toBe('current');
    expect(nodeState(n, { currentNode: 'SPEC', status: 'SPEC', paused: true }, false)).toBe('blocked');
    expect(nodeState(n, { records: { SPEC: rec('SKIPPED', 0) } }, true)).toBe('skipped');
    expect(nodeState(n, {}, true)).toBe('skipped'); expect(nodeState(n, {}, false)).toBe('pending');
    expect(nodeState(g.nodes[7], { currentNode: 'DEV', status: 'DEV', records: { SEC_AUDIT: rec('SUCCEEDED') }, nodeLoops: { SEC_AUDIT: 1 } }, true)).toBe('rejected');
  });
  it('评审门对应', () => { expect(gateMatches('SEC_AUDIT', 'sec_audit')).toBe(true); expect(gateMatches('ACCEPTANCE', 'acceptance_l2')).toBe(true); expect(gateMatches('DB_SCHEMA', 'design_review')).toBe(true); expect(gateMatches('DEV', 'sec_audit')).toBe(false); });
  it('执行成功不能覆盖测试未通过，历史返修次数不能覆盖本次通过', () => {
    const qa = g.nodes.find(n => n.id === 'QA')!;
    const records = { QA: { ...rec('SUCCEEDED'), outcome: 'FAILED', valid: true } };
    expect(nodeState(qa, { records }, false)).toBe('rejected');
    records.QA.outcome = 'PASSED';
    expect(nodeState(qa, { records, nodeLoops: { QA: 2 } }, false)).toBe('done');
    records.QA.valid = false;
    expect(nodeState(qa, { records }, false)).toBe('stale');
  });
  it('失败和等待记录不能因为执行过就标为完成', () => {
    const qa = g.nodes.find(n => n.id === 'QA')!;
    expect(nodeState(qa, { records: { QA: rec('FAILED') } }, false)).toBe('blocked');
    expect(nodeState(qa, { records: { QA: rec('WAITING') } }, false)).toBe('pending');
    expect(nodeState(qa, { records: { QA: rec('RUNNING') } }, false)).toBe('pending');
    expect(nodeState(qa, { records: { QA: rec('SUCCEEDED') } }, false)).toBe('done');
  });
  it('失败 / 异常去向：虚线回编码、点线去例外泳道，走过的标次数；跳过的步骤画绕过线', () => {
    const routes = [{ from: 'QA', to: 'DEV', kind: 'loop' as const, label: '测试未过', count: 2, max: 3 }, { from: 'SEC_AUDIT', to: 'DEV', kind: 'loop' as const, label: '审计不通过', count: 0 },
      { from: 'DEV', to: 'ANOMALY', kind: 'exc' as const, label: '出错 / 超限', count: 0 }, { from: 'QA', to: 'DEGRADED', kind: 'exc' as const, label: '返修用完', count: 1 }, { from: 'DEGRADED', to: 'DEV', kind: 'exc' as const, label: '换方案继续', count: 1 }];
    const L = layoutTaskFlow(g, { currentNode: 'DEV', status: 'DEV', routes, records: { SPEC: rec('SUCCEEDED'), DB_SCHEMA: rec('SKIPPED', 0) } });
    expect(L.lanes).toEqual(['sensing', 'production', 'delivery', 'exception']);
    expect(L.nodes.find(n => n.n.id === 'DEGRADED')!.state).toBe('done');
    const qa = L.edges.find(e => e.id === 'QA>DEV')!; expect(qa.kind).toBe('loop'); expect(qa.label!.text).toBe('测试未过 ×2');
    expect(L.edges.find(e => e.id === 'SEC_AUDIT>DEV'), '没走过的去向默认不画').toBeUndefined();
    const R = layoutTaskFlow(g, { currentNode: 'DEV', status: 'DEV', routes }, { reveal: 'SEC_AUDIT' });
    expect(R.edges.find(e => e.id === 'SEC_AUDIT>DEV')!.label!.text).toBe('审计不通过');
    expect(L.edges.find(e => e.id === 'QA>DEGRADED')!.kind).toBe('exc');
    expect(L.edges.find(e => e.id === 'DEGRADED>DEV')!.active).toBe(true);
    expect(L.edges.some(e => e.kind === 'flow' && (e.from === 'ANOMALY' || e.to === 'DEGRADED'))).toBe(false);
    expect(L.edges.find(e => e.kind === 'bypass')).toMatchObject({ from: 'SPEC', to: 'DEV' });
  });
  it('真实 lms 任务的去向：线不穿过节点，标签不压节点、不互相重叠，往返合成一条线', () => {
    const big: Graph = { schema: 2, edges: [], nodes: [
      { id: 'INTAKE', stage: 'BACKLOG', label: '需求收录', module: 'sensing' }, { id: 'PRD_REVIEW', stage: 'PRD_REVIEW', label: 'PRD 评审', module: 'sensing' }, { id: 'PLANNING', stage: 'PLANNING', label: '任务规划', module: 'sensing' },
      { id: 'SPEC', stage: 'SPEC', label: '需求策划', module: 'production' }, { id: 'UX_DESIGN', stage: 'DESIGN', label: '体验设计与交互原型说明', module: 'production' }, { id: 'DEV', stage: 'DEV', label: '编码', module: 'production' },
      { id: 'QA', stage: 'QA', label: '测试', module: 'delivery' }, { id: 'CODE_REVIEW', stage: 'CODE_REVIEW', label: '逐行审查', module: 'delivery' }, { id: 'SEC_AUDIT', stage: 'SEC_AUDIT', label: '安全审计', module: 'delivery' }, { id: 'ACCEPTANCE', stage: 'ACCEPTANCE', label: '验收', module: 'delivery' },
    ] };
    const routes = [
      { from: 'QA', to: 'DEV', kind: 'loop' as const, label: '测试未过', count: 9 }, { from: 'QA', to: 'PLANNING', kind: 'loop' as const, label: '测试未过', count: 4 },
      { from: 'QA', to: 'DEGRADED', kind: 'exc' as const, label: '返修用完', count: 5 }, { from: 'DEGRADED', to: 'DEV', kind: 'exc' as const, label: '换方案继续', count: 3 },
      { from: 'DEGRADED', to: 'PLANNING', kind: 'exc' as const, label: '缩小范围重新规划', count: 1 }, { from: 'DEV', to: 'ANOMALY', kind: 'exc' as const, label: '出错 / 超限', count: 2 },
      { from: 'ANOMALY', to: 'PLANNING', kind: 'exc' as const, label: '修复后继续', count: 2 }, { from: 'QA', to: 'ANOMALY', kind: 'exc' as const, label: '测试卡住', count: 1 }, { from: 'ANOMALY', to: 'QA', kind: 'exc' as const, label: 'CI 修好后重跑', count: 1 },
      { from: 'SEC_AUDIT', to: 'DEV', kind: 'loop' as const, label: '审计不通过', count: 0 }, { from: 'ACCEPTANCE', to: 'PLANNING', kind: 'loop' as const, label: '验收退回', count: 0 },
    ];
    const L = layoutTaskFlow(big, { currentNode: 'QA', status: 'DISCARDED', routes });
    const box = (n: { x: number; y: number }) => ({ x1: n.x + 1, y1: n.y + 1, x2: n.x + 131, y2: n.y + 59 });
    const segs = (path: string) => { const p = path.replace(/^M/, '').split(/ L| H| V/); const pts: number[][] = []; let cur = p[0].split(',').map(Number); pts.push(cur);
      for (const m of path.matchAll(/ (L|H|V)(-?[\d.]+)(?:,(-?[\d.]+))?/g)) { cur = m[1] === 'L' ? [+m[2], +m[3]] : m[1] === 'H' ? [+m[2], cur[1]] : [cur[0], +m[2]]; pts.push(cur); } return pts; };
    for (const e of L.edges.filter(e => e.kind !== 'flow' && e.kind !== 'bypass')) {
      const ps = segs(e.path);
      for (let i = 1; i < ps.length; i++) { const [x1, y1] = ps[i - 1], [x2, y2] = ps[i];
        for (const n of L.nodes) { if (n.n.id === e.from || n.n.id === e.to) continue; const b = box(n);
          const crosses = Math.max(x1, x2) > b.x1 && Math.min(x1, x2) < b.x2 && Math.max(y1, y2) > b.y1 && Math.min(y1, y2) < b.y2;
          expect(crosses, `${e.id} 穿过了 ${n.n.id}`).toBe(false); } }
    }
    const labels = L.edges.filter(e => e.label && e.kind !== 'flow').map(e => ({ id: e.id, x1: e.label!.x - e.label!.w / 2, x2: e.label!.x + e.label!.w / 2, y1: e.label!.y - 9, y2: e.label!.y + 9 }));
    for (const a of labels) {
      for (const n of L.nodes) { const b = box(n); expect(a.x1 < b.x2 && b.x1 < a.x2 && a.y1 < b.y2 && b.y1 < a.y2, `${a.id} 的标签压住了 ${n.n.id}`).toBe(false); }
      for (const b of labels) if (a !== b) expect(a.x1 < b.x2 && b.x1 < a.x2 && a.y1 < b.y2 && b.y1 < a.y2, `${a.id} 与 ${b.id} 标签重叠`).toBe(false);
    }
    const there = L.edges.find(e => e.id === 'QA>ANOMALY')!, back = L.edges.find(e => e.id === 'ANOMALY>QA')!;
    expect(segs(back.path)).toEqual(segs(there.path).reverse());
    expect(L.edges.find(e => e.id === 'SEC_AUDIT>DEV')).toBeUndefined();
    expect(L.edges.find(e => e.id === 'QA>DEV')!.label!.text).toBe('测试未过 ×9');
    expect(L.edges.find(e => e.id === 'DEGRADED>PLANNING')!.title).toContain('缩小范围重新规划');
    const ux = L.nodes.find(n => n.n.id === 'UX_DESIGN')!; expect(ux.x + 132).toBeLessThanOrEqual(L.nodes.find(n => n.n.id === 'DEV')!.x - 38);
  });
});
