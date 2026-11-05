/** 指挥台 v2 · M1：三栏布局用到的纯函数（工作区树、状态圆点、进度条、路由、旧会话重定向）。docs/command-center-v2.md 第 1 节 */
import type { LineMeta } from '../api';
import type { ProjectDTO, TaskDTO } from '@x7x24/shared';
import type { NodeIconName } from '../ui/NodeIcon';

export type Dot = 'blocked' | 'waiting' | 'running' | 'idle' | 'done';
export const DOT_ZH: Record<Dot, string> = { blocked: '阻塞', waiting: '等你处理', running: '运行中', idle: '未开始', done: '已完成' };

/** 任务行右侧圆点：红=阻塞/失败，黄=暂停或待审批（等你），绿=运行中，灰=未开始，旗子=完成 */
export function taskDot(t: Pick<TaskDTO, 'status' | 'executionState' | 'paused'>): Dot {
  if (t.executionState === 'WAITING_CONFIGURATION') return 'waiting';
  if (['RELEASED', 'DELIVERED', 'DISCARDED'].includes(t.status) || t.executionState === 'COMPLETED' || t.executionState === 'DISCARDED') return 'done';
  if (['BLOCKED','FAILED','RECOVERY_REQUIRED'].includes(t.executionState)) return 'blocked';
  if (t.paused || ['PAUSED','WAITING_INPUT','WAITING_PLAN','WAITING_APPROVAL','WAITING_VALIDATION','WAITING_EXTERNAL'].includes(t.executionState)) return 'waiting';
  if (t.executionState === 'RUNNING' || t.executionState === 'RETRYING') return 'running';
  return t.status === 'BACKLOG' ? 'idle' : 'running';
}

export interface TreeProject { project: ProjectDTO; tasks: TaskDTO[]; hidden: number }
export interface TreeLine { id: string; name: string; projects: TreeProject[] }
const ORDER: Record<Dot, number> = { blocked: 0, waiting: 1, running: 2, idle: 3, done: 4 };
/** 产品线 → 项目 → 任务。每个项目显示活跃/阻塞任务 + 最近 limit 个，其余计入 hidden */
export function buildTree(lines: Pick<LineMeta, 'id' | 'name'>[], projects: ProjectDTO[], tasks: TaskDTO[], limit = 5, expanded: Set<string> = new Set()): TreeLine[] {
  const byProject = new Map<string, TaskDTO[]>();
  for (const t of tasks) { const a = byProject.get(t.projectId) ?? []; a.push(t); byProject.set(t.projectId, a); }
  const mk = (p: ProjectDTO): TreeProject => {
    const all = (byProject.get(p.id) ?? []).slice().sort((a, b) => ORDER[taskDot(a)] - ORDER[taskDot(b)] || (b.statusChangedAt ?? '').localeCompare(a.statusChangedAt ?? ''));
    if (expanded.has(p.id)) return { project: p, tasks: all, hidden: 0 };
    const keep = all.filter((t, i) => i < limit || ORDER[taskDot(t)] <= 1);
    return { project: p, tasks: keep, hidden: all.length - keep.length };
  };
  const out: TreeLine[] = lines.map(l => ({ id: l.id, name: l.name, projects: projects.filter(p => p.productLineId === l.id).map(mk) }));
  const orphan = projects.filter(p => !lines.some(l => l.id === p.productLineId));
  if (orphan.length) out.push({ id: '', name: '未分组', projects: orphan.map(mk) });
  return out.filter(l => l.projects.length > 0 || l.id);
}

export type StepState = 'done' | 'current' | 'blocked' | 'pending';
export interface Step { stage: string; label: string; icon: NodeIconName; state: StepState }
const STAGES: [string, string, NodeIconName][] = [
  ['BACKLOG', '需求收录', 'node-intake'], ['SPEC', '需求策划', 'node-spec'], ['PLANNING', '任务规划', 'node-plan'], ['DESIGN', '设计', 'node-design'],
  ['DEV', '编码', 'node-code'], ['QA', '测试', 'node-test'], ['ACCEPTANCE', '验收', 'node-accept'], ['DELIVERED', '完成', 'done'],
];
const ALIAS: Record<string, string> = { PRD_REVIEW: 'SPEC', API_DESIGN: 'DESIGN', DB_SCHEMA: 'DESIGN', UX_DESIGN: 'DESIGN', SEC_AUDIT: 'QA', RELEASED: 'DELIVERED' };
/** 顶部进度条：按任务 path（没有就用默认顺序）排节点，当前节点按执行状态标阻塞 */
export function progressSteps(t: Pick<TaskDTO, 'status' | 'currentStage' | 'executionState' | 'path'>): Step[] {
  const norm = (s: string) => ALIAS[s] ?? s;
  const want = (t.path ?? []).map(norm).filter((s, i, a) => a.indexOf(s) === i && STAGES.some(x => x[0] === s));
  const list = want.length >= 2 ? STAGES.filter(s => want.includes(s[0]) || s[0] === 'BACKLOG' || s[0] === 'DELIVERED') : STAGES;
  const cur = norm(t.currentStage ?? t.status); const done = ['DELIVERED', 'RELEASED'].includes(t.status) && t.executionState !== 'WAITING_CONFIGURATION';
  let idx = list.findIndex(s => s[0] === cur); if (idx < 0) idx = done ? list.length - 1 : 0;
  const blocked = ['BLOCKED', 'FAILED', 'WAITING_CONFIGURATION'].includes(t.executionState);
  return list.map(([stage, label, icon], i) => ({ stage, label, icon, state: done || i < idx ? 'done' : i > idx ? 'pending' : blocked ? 'blocked' : 'current' }));
}

/** 指挥台子路由：['command'] / ['command','t',taskId] / ['command','t',taskId,'node',nodeId] / ['command',sessionId] */
export type CommandView = { kind: 'home' } | { kind: 'task'; taskId: string } | { kind: 'node'; taskId: string; nodeId: string } | { kind: 'session'; sessionId: string };
export function commandView(route: string[]): CommandView {
  if (route[1] === 't' && route[2]) return route[3] === 'node' && route[4] ? { kind: 'node', taskId: route[2], nodeId: route[4] } : { kind: 'task', taskId: route[2] };
  return route[1] ? { kind: 'session', sessionId: route[1] } : { kind: 'home' };
}
/** 旧会话链接：会话已经绑定任务就跳到任务会话 */
export function sessionRedirect(s: { task_id: string | null } | null | undefined): string | null { return s?.task_id ? `t/${encodeURIComponent(s.task_id)}/chat` : null; }
/** 任务的主会话：绑定该任务、最近更新的一条 */
export function sessionForTask<T extends { task_id: string | null; updated_at: string }>(list: T[], taskId: string): T | undefined {
  return list.filter(s => s.task_id === taskId).sort((a, b) => b.updated_at.localeCompare(a.updated_at))[0];
}
