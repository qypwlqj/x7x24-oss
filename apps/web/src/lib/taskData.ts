/** 任务详情数据（/api/tasks/{id} 与 /plan）：PR/CI 页签、节点详情页、真实节点进度条共用。seq 变化时刷新（防抖 1.5s） */
import { useEffect, useRef, useState } from 'react';
import { api, type TaskDTO } from '../api';
import { bySegment, childChecks, type GNode, type Graph } from './pipeline';
import { nodeState, type NodeRec, type NodeState } from './taskFlow';

export interface PlanData { graph?: Graph; currentNode?: string | null; currentIteration?: number; status: string; executionState?: string; paused?: boolean; records: Record<string, NodeRec & { startedAt?: string; endedAt?: string }>; nodeLoops?: Record<string, number> }
export interface Evidence { id: string; kind: string; stage: string; iteration: number; createdAt: string; content: any; producedBy?: string; model?: string }
export interface TaskDetailData { task: any; evidence: Evidence[]; stageExecutions: any[]; timeline: any[]; repo?: string; context?: { pr?: { headSha?: string }; ci?: { headSha?: string; state?: string } } }

export function useRefreshing<T>(url: string | null, seq: number, every = 15000): T | null {
  const [d, setD] = useState<T | null>(null); const t = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => { if (!url) return; let on = true; const load = () => api<T>(url).then(x => on && setD(x)).catch(() => {}); load(); const i = setInterval(load, every); return () => { on = false; clearInterval(i); }; }, [url, every]);
  useEffect(() => { if (!url || !seq) return; if (t.current) clearTimeout(t.current); t.current = setTimeout(() => { api<T>(url).then(setD).catch(() => {}); }, 1500); return () => { if (t.current) clearTimeout(t.current); }; }, [seq, url]);
  return d;
}

export interface StripCheck { id: string; label: string; state: NodeState; recheck?: boolean; historyState?: string; historyOutcome?: string }
export interface StripNode extends StripCheck { children: StripCheck[] }
/** 真实流程节点（含挂在测试里的检查子节点）；没有流程图的老任务返回 null，由调用方退回按阶段显示 */
export function stripNodes(p: PlanData | null, task?: Pick<TaskDTO, 'executionState' | 'paused'>): StripNode[] | null {
  if (!p?.graph) return null;
  const flat: GNode[] = bySegment(p.graph).flatMap(s => s.nodes);
  const currentParent = p.graph.nodes.find(n => n.id === p.currentNode)?.parent ?? p.currentNode;
  const cur = flat.findIndex(n => n.id === currentParent);
  const end = ['DELIVERED', 'RELEASED'].includes(p.status) && (task?.executionState ?? p.executionState) !== 'WAITING_CONFIGURATION';
  const f = { currentNode: p.currentNode, status: p.status, paused: p.paused || task?.paused, executionState: task?.executionState ?? p.executionState, records: p.records, nodeLoops: p.nodeLoops };
  const display = (n: GNode, i: number, parent = false): StripCheck => {
    const rec = p.records?.[n.id];
    // 回到上游后，后续节点保留历史结果，但要针对新的产出重新检查。
    const recheck = rec?.valid === false || rec?.valid == null && !end && cur >= 0 && i > cur && (rec?.runs ?? 0) > 0 && rec?.state !== 'SKIPPED';
    const state = recheck ? 'pending' : nodeState(n, parent ? { ...f, currentNode: currentParent } : f, cur >= 0 && i < cur);
    return { id: n.id, label: n.label, state, recheck, historyState: recheck ? rec?.state : undefined, historyOutcome: recheck ? rec?.outcome : undefined };
  };
  return flat.map((n, i) => ({ ...display(n, i, true), children: childChecks(p.graph!, n.id).map(c => display(c, i)) }));
}

export type ProgressMode = 'running' | 'waiting' | 'paused' | 'blocked' | 'done' | 'stopped';
export type ProgressBehavior = 'think' | 'draw' | 'type' | 'test' | 'review' | 'accept' | 'deliver';
const STAGE_BEHAVIOR: Record<string, ProgressBehavior> = {
  BACKLOG: 'think', INTAKE: 'think', PRD_REVIEW: 'think', PLANNING: 'think', SPEC: 'think',
  API_DESIGN: 'draw', DB_SCHEMA: 'draw', UX_DESIGN: 'draw', DESIGN: 'draw', DEV: 'type', QA: 'test',
  CODE_REVIEW: 'review', SEC_AUDIT: 'review', ACCEPTANCE: 'accept', RELEASE: 'deliver', DELIVERED: 'deliver', RELEASED: 'deliver',
};
function behaviorOf(node: GNode | undefined, fallback: string): ProgressBehavior {
  if (node?.family === 'check' || node?.executor === 'check') return 'test';
  const known = STAGE_BEHAVIOR[node?.id ?? ''] ?? STAGE_BEHAVIOR[node?.stage ?? fallback];
  if (known) return known;
  if (node?.executor === 'review' || node?.executor === 'llm_checker' || node?.family === 'review') return 'review';
  return ({ design: 'draw', build: 'type', check: 'test', delivery: 'review' } as Record<string, ProgressBehavior>)[node?.family ?? ''] ?? 'think';
}
/** 位置来自执行节点，动作来自执行状态；页面选中项不参与计算。 */
export function progressActivity(task: TaskDTO, plan: PlanData | null | undefined, steps: StripNode[]) {
  const nodeId = plan?.currentNode ?? steps.find(n => n.state === 'current' || n.state === 'blocked')?.id;
  const parent = steps.find(n => n.id === nodeId || n.children.some(c => c.id === nodeId));
  const node = parent?.children.find(c => c.id === nodeId) ?? parent;
  let mode: ProgressMode = 'running'; let text = '进行中';
  if (task.status === 'DISCARDED' || task.executionState === 'DISCARDED') { mode = 'stopped'; text = '已结束'; }
  else if (task.executionState === 'WAITING_CONFIGURATION') { mode = 'blocked'; text = '待配置发布'; }
  else if (['DELIVERED', 'RELEASED'].includes(task.status) || task.executionState === 'COMPLETED') { mode = 'done'; text = '已完成'; }
  else if (task.paused || plan?.paused || task.executionState === 'PAUSED') { mode = 'paused'; text = '已暂停'; }
  else if (task.executionState === 'FAILED') { mode = 'blocked'; text = '执行失败'; }
  else if (task.executionState === 'BLOCKED' || ['ANOMALY', 'DEGRADED'].includes(task.status)) { mode = 'blocked'; text = '需要处理'; }
  else if (task.executionState === 'WAITING' || !parent) { mode = 'waiting'; text = '等待执行'; }
  const located = parent ?? (mode === 'done' ? steps.at(-1) : undefined);
  const executionNode = plan?.graph?.nodes.find(n => n.id === nodeId);
  const behavior = behaviorOf(executionNode, nodeId ?? task.currentStage ?? task.status);
  // 稳定的成功凭据：轮询不重播，只有实际成功记录新增或更新才产生反馈。
  const successKey = plan ? JSON.stringify(Object.entries(plan.records).filter(([id, r]) => {
    if (r.state !== 'SUCCEEDED' || r.valid === false) return false;
    if (r.outcome != null) return r.outcome === 'PASSED';
    const stage = behaviorOf(plan?.graph?.nodes.find(n => n.id === id), id);
    // 评审和测试的进程完成不等于检查通过；老数据没有明确结论时保持安静。
    return !['test', 'review', 'accept'].includes(stage);
  }).map(([id, r]) => `${id}:${r.runs}:${r.endedAt ?? ''}`).sort()) : '';
  return { nodeId: located?.id ?? null, label: node?.label ?? located?.label, mode, text, behavior, successKey, childLabel: parent && node !== parent ? node?.label : undefined };
}

type ProgressStep = { id: string; state: string; children: { id: string }[] };
/** 定位显式查看的节点，其次当前节点，最后按时间找实际执行过的节点；子检查定位到所属主节点。 */
export function progressStripTarget(steps: ProgressStep[], plan?: PlanData | null, active?: string): string | null {
  const parentOf = (id?: string | null) => id ? steps.find(s => s.id === id || s.children.some(c => c.id === id))?.id : undefined;
  const explicit = parentOf(active); if (explicit) return explicit;
  const current = parentOf(plan?.currentNode); if (current) return current;
  if (plan?.graph) {
    let latest: string | undefined; let latestAt = -Infinity;
    for (const [id, rec] of Object.entries(plan.records)) {
      const parent = parentOf(id);
      if (!parent || rec.state === 'SKIPPED' || rec.runs <= 0) continue;
      const at = Math.max(Date.parse(rec.endedAt ?? '') || 0, Date.parse(rec.startedAt ?? '') || 0);
      if (at >= latestAt) { latest = parent; latestAt = at; }
    }
    return latest ?? steps[0]?.id ?? null;
  }
  // 老任务使用已有阶段推断；完成任务的阶段全部标为 done，定位最后一项。
  return steps.find(s => ['current', 'blocked', 'running', 'failed'].includes(s.state))?.id
    ?? steps.filter(s => s.state === 'done').at(-1)?.id ?? steps[0]?.id ?? null;
}

export const prsOf = (d: TaskDetailData | null) => (d?.evidence ?? []).filter(e => e.kind === 'pr');
export const cisOf = (d: TaskDetailData | null) => (d?.evidence ?? []).filter(e => e.kind === 'ci_log' || e.kind === 'test_report');
export const ciState = (e: Evidence) => {
  const s = String(e.content?.state ?? e.content?.conclusion ?? '');
  if (e.content?.passed === false || e.kind === 'test_report' && e.content?.jobs === 0) return 'fail';
  return s === 'passed' || s === 'success' ? 'pass' : ['running', 'pending', 'queued'].includes(s) ? 'running' : e.kind === 'test_report' && e.content?.passed === true ? 'pass' : 'fail';
};
export function ciEvidenceCurrent(e: Evidence, d: TaskDetailData | null) {
  const head = d?.context?.pr?.headSha; const ci = d?.context?.ci;
  return !!head && e.content?.headSha === head && ci?.headSha === head && ci.state === e.content?.state
    && ['QA', 'ACCEPTANCE', 'DELIVERED', 'RELEASED'].includes(d?.task?.status);
}
/** 节点相关的事件 / 证据 / 执行记录 / 用时 */
export function nodeFacts(d: TaskDetailData | null, nodeId: string, events: { nodeId?: string | null; at: string }[]) {
  const ev = (d?.evidence ?? []).filter(e => e.stage === nodeId);
  const runs = (d?.stageExecutions ?? []).filter((s: any) => s.stage === nodeId || s.node === nodeId);
  const mine = events.filter(e => e.nodeId === nodeId); const times = [...mine.map(e => e.at), ...runs.flatMap((r: any) => [r.started_at, r.finished_at, r.startedAt, r.endedAt].filter(Boolean))].sort();
  const first = times[0], last = times[times.length - 1];
  return { evidence: ev, runs, events: mine, first, last, ms: first && last ? Date.parse(last) - Date.parse(first) : 0 };
}
export const fmtDur = (ms: number) => !ms || ms < 0 ? '—' : ms < 60000 ? `${Math.round(ms / 1000)} 秒` : ms < 3600000 ? `${Math.round(ms / 60000)} 分钟` : `${(ms / 3600000).toFixed(1)} 小时`;
