/** 预算编辑的前端校验与文案（与后端 BudgetApi 的规则一致；后端仍是最终裁判）。 */
export type Scope = 'global' | 'line' | 'project' | 'task';
export type Level = 'ok' | 'soft' | 'hard';
export interface Limits { dailyUsd?: number | null; dailyTokens?: number | null; dailyCiMinutes?: number | null; monthlyUsd?: number | null }
export const LIMIT_FIELDS = [
  { key: 'dailyUsd', label: '日金额', unit: '美元', example: '例如 50 = 每天最多花 50 美元' },
  { key: 'dailyTokens', label: '日 Token', unit: 'token', example: '例如 40000000 = 4000 万 Token（约 3000 万字）' },
  { key: 'dailyCiMinutes', label: '日 CI 分钟', unit: '分钟', example: '例如 200 = 每天最多跑 200 分钟测试' },
] as const;
export const TASK_FIELDS = [
  { key: 'usd', label: '单任务金额', unit: '美元', example: '例如 3 = 一个任务最多花 3 美元' },
  { key: 'tokens', label: '单任务 Token', unit: 'token', example: '例如 2000000' },
  { key: 'ciMinutes', label: '单任务 CI 分钟', unit: '分钟', example: '例如 60' },
  { key: 'iterations', label: '单任务迭代轮数', unit: '轮', example: '例如 50 = 最多返修 50 轮' },
] as const;

export const POLICIES = [
  { id: 'degrade', name: '改用便宜的模型', text: '用量到省钱线后改用更便宜的模型继续工作；触顶后停止新的步骤。适合大多数团队。', recommended: true },
  { id: 'pause_new', name: '暂停新任务', text: '到省钱线后不再接收新任务，已有任务继续跑完。适合想优先收尾的场景。', recommended: false },
  { id: 'halt', name: '全部停止', text: '全局触顶时立即停止所有自动执行（比只读更彻底）。适合预算非常紧的场景。', recommended: false },
] as const;

export const levelOf = (ratio: number, soft: number): Level => (ratio >= 1 ? 'hard' : ratio >= soft ? 'soft' : 'ok');
export const levelText = (l: Level | string) => (l === 'hard' ? '已触顶' : l === 'soft' ? '省钱模式' : '正常');
export const pctText = (x: number) => `${Math.round(x * 100)}%`;
export const ratioOf = (used: number, limit: number) => (limit > 0 ? used / limit : 0);

/** 返回错误列表；limits 里 null / undefined 表示沿用默认，不参与上限比较 */
export function validateLimits(scope: 'global' | 'line' | 'project', next: Limits, parent?: Limits, children: { name: string; limits: Limits }[] = []): string[] {
  const errs: string[] = []; const names: Record<string, string> = { dailyUsd: '日金额', dailyTokens: '日 Token', dailyCiMinutes: '日 CI 分钟', monthlyUsd: '月金额' };
  for (const k of Object.keys(names) as (keyof Limits)[]) { const v = next[k]; if (v != null && (!Number.isFinite(v) || v <= 0)) errs.push(`${names[k]}必须是大于 0 的数字`); }
  if (scope === 'global' && next.monthlyUsd != null && next.dailyUsd != null && next.monthlyUsd < next.dailyUsd) errs.push('月金额上限不能低于日金额上限');
  if (parent) for (const k of ['dailyUsd', 'dailyTokens', 'dailyCiMinutes'] as const) { const v = next[k], p = parent[k]; if (v != null && p != null && v > p) errs.push(`${names[k]}不能超过${scope === 'line' ? '全局' : '所属产品线'}上限（${p}）`); }
  for (const c of children) for (const k of ['dailyUsd', 'dailyTokens', 'dailyCiMinutes'] as const) { const v = next[k], o = c.limits[k]; if (v != null && o != null && o > v) errs.push(`「${c.name}」设置的${names[k]}（${o}）高于新额度，请先调低它`); }
  return errs;
}
export function validateTask(next: { usd?: number; tokens?: number; ciMinutes?: number; iterations?: number }, global: Limits): string[] {
  const errs: string[] = [];
  for (const [k, v] of Object.entries(next)) if (v != null && (!Number.isFinite(v) || v <= 0)) errs.push(`${TASK_FIELDS.find(f => f.key === k)?.label ?? k}必须是大于 0 的数字`);
  if (next.usd != null && global.dailyUsd != null && next.usd > global.dailyUsd) errs.push(`单任务金额不能超过全局日金额上限（${global.dailyUsd}）`);
  if (next.tokens != null && global.dailyTokens != null && next.tokens > global.dailyTokens) errs.push('单任务 Token 不能超过全局日 Token 上限');
  if (next.ciMinutes != null && global.dailyCiMinutes != null && next.ciMinutes > global.dailyCiMinutes) errs.push('单任务 CI 分钟不能超过全局日上限');
  return errs;
}
/** 本地即时预览：当前用量在新额度下的状态（后端预览给出权威结果） */
export function previewImpact(used: { usd: number; tokens: number; ci: number }, next: Limits, soft: number, hasSoft = true): { pct: number; level: Level; text: string } {
  const r = Math.max(ratioOf(used.usd, next.dailyUsd ?? Infinity), ratioOf(used.tokens, next.dailyTokens ?? Infinity), ratioOf(used.ci, next.dailyCiMinutes ?? Infinity));
  const level = hasSoft ? levelOf(r, soft) : r >= 1 ? 'hard' : 'ok'; const pct = Math.round(r * 100);
  return { pct, level, text: `当前已用 ${pct}%，保存后${level === 'hard' ? '将触顶：新的步骤会停止执行' : level === 'soft' ? '将进入省钱模式：改用便宜的模型' : '保持正常'}` };
}
/** 数字输入框 → number | null（空 = 沿用默认） */
export const parseNum = (s: string): number | null | undefined => { const t = s.trim(); if (t === '') return null; const n = Number(t); return Number.isFinite(n) ? n : undefined; };
