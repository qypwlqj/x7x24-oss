/** 状态机定义（与 docs/dev-plan.md §1.4 一致）。服务端与前端共用。 */
export const STATUSES = [
  'BACKLOG', 'PRD_REVIEW', 'PLANNING', 'SPEC', 'DESIGN', 'DEV', 'QA',
  'ACCEPTANCE', 'DELIVERED', 'RELEASED', 'ANOMALY', 'DEGRADED', 'DISCARDED',
] as const;
export type Status = (typeof STATUSES)[number];

export const EDGES = [
  'submit', 'review_pin', 'discard', 'pass', 'dispatch', 'advance', 'qa_loop', 'reject',
  'accept', 'release', 'rollback', 'anomaly', 'resume', 'degrade', 'auto_discard',
  'requeue', 'iterate', 'active_discard', 'create', 'abandon', 'restore_progress', 'node_fix', 'merge_return', 'split',
] as const;
export type Edge = (typeof EDGES)[number];

export type TransitionRule = { to: Status; edges: Edge[] };

/** from -> 允许的 (to, edge) */
export const TRANSITIONS: Record<Status, TransitionRule[]> = {
  BACKLOG: [
    { to: 'PRD_REVIEW', edges: ['submit'] },
    { to: 'PLANNING', edges: ['submit'] },
    { to: 'DEV', edges: ['submit'] },
  ],
  PRD_REVIEW: [
    { to: 'BACKLOG', edges: ['review_pin'] },
    { to: 'PLANNING', edges: ['pass'] },
    { to: 'ANOMALY', edges: ['anomaly'] },
  ],
  PLANNING: [
    { to: 'SPEC', edges: ['dispatch'] },
    { to: 'DESIGN', edges: ['dispatch'] },
    { to: 'DEV', edges: ['dispatch'] },
    { to: 'DEGRADED', edges: ['degrade'] },
    { to: 'ANOMALY', edges: ['anomaly'] },
  ],
  SPEC: [
    { to: 'DESIGN', edges: ['advance'] },
    { to: 'DEV', edges: ['advance'] },
    { to: 'ANOMALY', edges: ['anomaly'] },
  ],
  DESIGN: [
    { to: 'DESIGN', edges: ['advance'] },
    { to: 'DEV', edges: ['advance'] },
    { to: 'ANOMALY', edges: ['anomaly'] },
  ],
  DEV: [
    { to: 'QA', edges: ['advance'] },
    { to: 'ANOMALY', edges: ['anomaly'] },
  ],
  QA: [
    { to: 'PLANNING', edges: ['qa_loop'] },
    { to: 'ACCEPTANCE', edges: ['advance'] },
    { to: 'DEGRADED', edges: ['qa_loop', 'degrade'] },
    { to: 'ANOMALY', edges: ['anomaly'] },
  ],
  ACCEPTANCE: [
    { to: 'PLANNING', edges: ['reject'] },
    { to: 'DELIVERED', edges: ['accept'] },
    { to: 'DEGRADED', edges: ['reject', 'degrade'] },
    { to: 'ANOMALY', edges: ['anomaly'] },
  ],
  DELIVERED: [
    { to: 'RELEASED', edges: ['release'] },
    { to: 'PLANNING', edges: ['rollback'] },
    { to: 'DEGRADED', edges: ['rollback'] },
  ],
  RELEASED: [
    { to: 'PLANNING', edges: ['rollback'] },
    { to: 'DEGRADED', edges: ['rollback'] },
  ],
  ANOMALY: [
    { to: 'PRD_REVIEW', edges: ['resume'] },
    { to: 'PLANNING', edges: ['resume'] },
    { to: 'SPEC', edges: ['resume'] },
    { to: 'DESIGN', edges: ['resume'] },
    { to: 'ACCEPTANCE', edges: ['resume'] },
  ],
  DEGRADED: [
    { to: 'PLANNING', edges: ['degrade'] },
  ],
  DISCARDED: [{ to: 'BACKLOG', edges: ['requeue', 'restore_progress'] }, ...(['PRD_REVIEW','PLANNING','SPEC','DESIGN','DEV','QA','ACCEPTANCE','DELIVERED','RELEASED'] as Status[]).map(to => ({ to, edges: ['restore_progress'] as Edge[] }))],
};

export function canTransition(from: Status, to: Status, edge?: Edge): boolean {
  const rules = TRANSITIONS[from].filter((r) => r.to === to);
  if (rules.length === 0) return false;
  return edge ? rules.some((r) => r.edges.includes(edge)) : true;
}

for (const phase of STATUSES) if (!['RELEASED','DISCARDED'].includes(phase)) TRANSITIONS[phase].push({ to: 'DISCARDED', edges: ['abandon'] });

export const DISCARD_REASONS = {
  manual_abandon: { label: '人工放弃', requeueable: false },
  needs_clarification: { label: '等待补充说明', requeueable: false },
  exceeds_budget: { label: '超过当前规模限制', requeueable: false },
  duplicate: { label: '与已有任务重复', requeueable: false },
  low_value: { label: '评审判定价值不足', requeueable: false },
  forbidden_scope: { label: '触及禁区 / 超出自治等级', requeueable: false },
  infeasible: { label: '技术不可行', requeueable: false },
  loop_exhausted: { label: 'Loop 与降级全部耗尽', requeueable: false },
  canary_regression: { label: '多次灰度劣化', requeueable: false },
  budget_exhausted: { label: '预算耗尽', requeueable: false },
  superseded: { label: '被新任务替代', requeueable: false },
  policy_withdrawn: { label: '规则主动撤销', requeueable: false },
} as const;
export type DiscardReason = keyof typeof DISCARD_REASONS;

/** 看板节点（与原图一致） */
export const BOARD_NODES = [
  { id: 'signal', label: '抓取信号', statuses: [] as Status[], lane: 'watch' },
  { id: 'pool', label: '需求池', statuses: ['BACKLOG'] as Status[], lane: 'watch' },
  { id: 'prd', label: 'PRD 评审', statuses: ['PRD_REVIEW'] as Status[], lane: 'gate' },
  { id: 'planning', label: '任务规划', statuses: ['PLANNING'] as Status[], lane: 'prism' },
  { id: 'spec', label: '需求策划', statuses: ['SPEC'] as Status[], lane: 'prism' },
  { id: 'design', label: '体验设计', statuses: ['DESIGN'] as Status[], lane: 'prism' },
  { id: 'dev', label: '开发执行', statuses: ['DEV'] as Status[], lane: 'prism' },
  { id: 'qa', label: '质量测试', statuses: ['QA'] as Status[], lane: 'prism' },
  { id: 'acceptance', label: '业务验收', statuses: ['ACCEPTANCE'] as Status[], lane: 'prism' },
  { id: 'anomaly', label: '异常巡查', statuses: ['ANOMALY', 'DEGRADED'] as Status[], lane: 'prism' },
  { id: 'delivered', label: '待发布', statuses: ['DELIVERED'] as Status[], lane: 'outer' },
  { id: 'released', label: '版本已发布', statuses: ['RELEASED'] as Status[], lane: 'outer' },
  { id: 'discarded', label: '已废弃', statuses: ['DISCARDED'] as Status[], lane: 'watch' },
] as const;
export type BoardNodeId = (typeof BOARD_NODES)[number]['id'];

export const WATCH_STATUSES: Status[] = ['BACKLOG', 'PRD_REVIEW'];
export const PRISM_STATUSES: Status[] = ['PLANNING', 'SPEC', 'DESIGN', 'DEV', 'QA', 'ACCEPTANCE', 'ANOMALY', 'DEGRADED'];
