/** 流程模板 / 角色 / 模型配置（后端 /api/pipeline）的类型与纯函数 */
export interface Gate { pass?: number; revise?: number }
export interface StageCfg {
  type: string; enabled?: boolean; maker?: string; checker?: string; fixer?: string;
  gate?: Gate; maxLoops?: number; judgeSeats?: number; checks?: string[]; maxChangeLines?: number;
  label?: string; anchorPhase?: string; executor?: string; promptTemplate?: string; artifactKey?: string;
  replacedFrom?: string; afterStage?: string; requiredFields?: string[]; skills?: string[]; instructions?: string;
}
export interface Template { id: string; version: number; name?: string; description?: string; owner?: string | null; status: 'approved' | 'pending' | 'rejected'; builtin?: boolean; stages: StageCfg[]; heal?: Record<string, string[]>; maxHeals?: number; submittedBy?: string; reviewedBy?: string; reviewNote?: string; at?: string }
export interface Role { id?: string; kind?: string; schema?: string; profile?: string; extendsRole?: string; skills?: string[]; instructions?: string; builtin?: boolean }
export interface Skill { id: string; version: number; name?: string; instructions: string; filePatterns?: string[]; builtin?: boolean }
export interface LineBinding { template: string; roles?: Record<string, Role>; stageOverrides?: Record<string, Partial<StageCfg>>; models?: Record<string, string>; version?: number; updatedBy?: string; at?: string }
export interface StageType { type: string; name: string; optional: boolean; slots: string[]; output: string; requiredEvidence: string[] }
export interface Effective { id: string; lineId: string; template: string; stages: Record<string, StageCfg>; heal: Record<string, string[]>; maxHeals?: number; roles: Record<string, Role>; skillText: Record<string, string>; models: Record<string, string>; sources: Record<string, string> }
export interface PipelineState {
  version: number;
  catalog: { stageTypes: StageType[]; stagePresets?: StageCfg[]; roles: Role[]; skills: Skill[]; healActions: string[]; checks: Record<string, string>; floor: { maxChangeLinesCeiling: number; maxAutonomy: string; minJudgeSeats: number; optionalStages: string[] } };
  templates: Template[]; lines: Record<string, LineBinding>; me: { login: string; admin: boolean; adminsConfigured: boolean };
}

export const key = (t: Pick<Template, 'id' | 'version'>) => `${t.id}@${t.version}`;
export const skillKey = (s: Pick<Skill, 'id' | 'version'>) => `${s.id}@${s.version}`;
/** 业务线可绑定的模板：已审批，且是平台模板或本业务线私有模板 */
export const bindable = (ts: Template[], lineId: string) => ts.filter(t => t.status === 'approved' && (!t.owner || t.owner === lineId));
export const isOptional = (types: StageType[], type: string) => !!types.find(s => s.type === type)?.optional;

/** 节点开关：只允许可选节点；回到模板默认时删除覆盖项 */
export function toggleStage(b: LineBinding, type: string, on: boolean, templateDefaultOn: boolean): LineBinding {
  const ov = { ...(b.stageOverrides ?? {}) }; const cur = { ...(ov[type] ?? {}) };
  if (on === templateDefaultOn) delete cur.enabled; else cur.enabled = on;
  if (Object.keys(cur).length === 0) delete ov[type]; else ov[type] = cur;
  return { ...b, stageOverrides: Object.keys(ov).length ? ov : undefined };
}
export function setStageField<K extends keyof StageCfg>(b: LineBinding, type: string, field: K, value: StageCfg[K] | undefined): LineBinding {
  const ov = { ...(b.stageOverrides ?? {}) }; const cur: Partial<StageCfg> = { ...(ov[type] ?? {}) };
  if (value === undefined || value === null || (Array.isArray(value) && value.length === 0)) delete cur[field]; else cur[field] = value;
  if (Object.keys(cur).length === 0) delete ov[type]; else ov[type] = cur;
  return { ...b, stageOverrides: Object.keys(ov).length ? ov : undefined };
}

const ROLE_STAGE_MAP: Record<string, string> = {
  'prd.draft': 'PRD_REVIEW', 'plan.make': 'PLANNING', 'spec.write': 'SPEC', 'design.make': 'DESIGN', 'dev.code': 'DEV',
};
/** 派生任意内置执行角色：<line>.<baseRole>，继承 baseRole，并绑定到对应节点的 maker 槽位 */
export function setRoleSkills(b: LineBinding, lineId: string, baseRole: string, skills: string[], instructions: string, profile?: string): LineBinding {
  const id = `${lineId}.${baseRole}`; const stage = ROLE_STAGE_MAP[baseRole] ?? 'DEV';
  const roles = { ...(b.roles ?? {}) };
  const empty = skills.length === 0 && !instructions.trim() && !profile;
  if (empty) { delete roles[id]; const nb = setStageField({ ...b, roles }, stage, 'maker', undefined); return { ...nb, roles: Object.keys(roles).length ? roles : undefined }; }
  roles[id] = { extendsRole: baseRole, skills, ...(instructions.trim() ? { instructions: instructions.trim() } : {}), ...(profile ? { profile } : {}) };
  return setStageField({ ...b, roles }, stage, 'maker', id);
}
/** 派生 DEV 执行角色：<line>.dev.code，继承 dev.code，并绑定到 DEV 节点（保持向后兼容） */
export function setDevSkills(b: LineBinding, lineId: string, skills: string[], instructions: string, profile?: string): LineBinding {
  return setRoleSkills(b, lineId, 'dev.code', skills, instructions, profile);
}

export interface DiffRow { path: string; from: string; to: string }
/** 两个生效快照的差异（用于保存前预览） */
export function diffEffective(a: Effective | null, b: Effective | null): DiffRow[] {
  const flat = (o: unknown, p = '', out: Record<string, string> = {}) => {
    if (o && typeof o === 'object' && !Array.isArray(o)) { for (const [k, v] of Object.entries(o as Record<string, unknown>)) flat(v, p ? `${p}.${k}` : k, out); }
    else if (o !== undefined && o !== null) out[p] = JSON.stringify(o);
    return out;
  };
  const strip = (e: Effective | null) => e ? { template: e.template, stages: e.stages, heal: e.heal, maxHeals: e.maxHeals, skillText: e.skillText, models: e.models } : {};
  const fa = flat(strip(a)), fb = flat(strip(b)); const keys = [...new Set([...Object.keys(fa), ...Object.keys(fb)])].sort();
  return keys.filter(k => fa[k] !== fb[k]).map(k => ({ path: k, from: fa[k] ?? '—', to: fb[k] ?? '—' }));
}

// ── 可组合流程（P2）：组装出的流程图
export interface GNode { id: string; def?: string; stage: string; label: string; module?: string; family?: string; executor?: string; source?: 'preset' | 'rule' | 'governance' | 'agent'; reason?: string; mandatory?: string; skills?: string[]; checks?: string[]; parent?: string; optional?: boolean }
export interface Graph { schema: number; nodes: GNode[]; edges: [string, string][]; estimate?: { llmCalls: number; usd: number; minutes: number }; warnings?: string[] }
export const SOURCE_LABEL: Record<string, string> = { preset: '预设', rule: '规则', governance: '治理必选', agent: 'AI 提议' };
export const MODULE_LABEL: Record<string, string> = { sensing: '感知', production: '生产', delivery: '交付' };
/** 顶层节点（不含挂在 QA 内执行的检查类节点），按流程顺序 */
export const topNodes = (g?: Graph | null) => (g?.nodes ?? []).filter(n => !n.parent);
/** 某个节点内执行的检查类节点 */
export const childChecks = (g: Graph | null | undefined, id: string) => (g?.nodes ?? []).filter(n => n.parent === id);
/** 按模块分段（感知 / 生产 / 交付），保持原顺序 */
export function bySegment(g?: Graph | null): { module: string; nodes: GNode[] }[] {
  const out: { module: string; nodes: GNode[] }[] = [];
  for (const n of topNodes(g)) { const m = n.module ?? 'production'; const last = out[out.length - 1]; if (last && last.module === m) last.nodes.push(n); else out.push({ module: m, nodes: [n] }); }
  return out;
}

/** 工作区汇总的流程节点看板（方案 A）：采集 / 生产（设计 / 构建 / 检查） / 交付 */
export interface BoardCell { node: string; label: string; current: number; blocked: number; avgDwellH: number; through: number; throughPct: number; byStack: Record<string, number>; rare: boolean; candidates?: boolean }
export interface BoardFamily { family: string; label: string; nodes: BoardCell[]; rareCount: number }
export interface NodeBoardData { modules: { sensing: BoardCell[]; production: BoardFamily[]; delivery: BoardCell[] }; attention: { anomaly: number; degraded: number }; window: string; windowTasks: number; rarePct: number }
export interface NodeTask { id: string; title: string; status: string; projectId: string; project: string; stacks: string[]; dwellH: number; attempts: number; blocked: boolean; reason?: string | null }
export const STACK_LABEL: Record<string, string> = { go: 'Go', java: 'Java', python: 'Python', node: 'Node', react: 'React', vue: 'Vue', web: '网页', sql: '数据库迁移', docs: '文档', other: '其他' };
/** 罕见节点（窗口内经过率低于阈值且当前没有任务）默认折叠 */
export const visibleCells = (cells: BoardCell[], showRare: boolean) => showRare ? cells : cells.filter(c => !c.rare);
/** 看板上出现的技术栈（用于筛选） */
export function boardStacks(b: NodeBoardData | null): string[] {
  if (!b) return [];
  const all = [...b.modules.sensing, ...b.modules.production.flatMap(f => f.nodes), ...b.modules.delivery];
  return [...new Set(all.flatMap(c => Object.keys(c.byStack)))].sort();
}
/** 停留时长的人话 */
export const dwell = (h: number) => h <= 0 ? '—' : h < 1 ? `${Math.max(1, Math.round(h * 60))} 分钟` : h < 48 ? `${h.toFixed(1)} 小时` : `${(h / 24).toFixed(1)} 天`;

/** 流程预设（P5）：组装的起点，不再绑定业务线 */
export interface Preset { id: string; version: number; name: string; description?: string; template: string; disable: string[]; source: 'builtin' | 'template' | 'workspace'; owner?: string | null; nodes: string[]; createdBy?: string }
export const PRESET_SOURCE: Record<Preset['source'], string> = { builtin: '内置', template: '由业务线模板转换', workspace: '工作区预设' };
export const presetKey = (p: Pick<Preset, 'id' | 'version'>) => `${p.id}@${p.version}`;
/** 选项：同名预设只显示一次，内置在前 */
export const presetOptions = (ps: Preset[]) => [...ps].sort((a, b) => (a.source === 'builtin' ? 0 : 1) - (b.source === 'builtin' ? 0 : 1)).map(p => ({ value: presetKey(p), label: `${p.name}${p.source === 'builtin' ? '' : `（${PRESET_SOURCE[p.source]}）`}` }));
