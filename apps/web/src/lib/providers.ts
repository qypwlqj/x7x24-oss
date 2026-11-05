export interface KnownModel { id: string; contextTokens: number; maxOutputTokens: number }
export interface Preset { id: string; name: string; baseUrl: string; models: KnownModel[] }
export interface ProviderRow { id: string; name: string; preset?: string; baseUrl: string; keySource: 'db' | 'env'; last4?: string | null; keyUpdatedAt?: string | null; envVar?: string | null; available: boolean; models: string[]; profiles?: string[] }
export interface ModelPick { id: string; checked: boolean; contextTokens?: number; maxOutputTokens?: number }
export interface Draft { id?: string; name: string; preset: string; baseUrl: string; apiKey: string }

/** 选择预设：填入名称与 Base URL（用户改过名称时不覆盖） */
export function applyPreset(d: Draft, presets: Preset[], presetId: string): Draft {
  const p = presets.find(x => x.id === presetId); if (!p) return { ...d, preset: presetId };
  const prev = presets.find(x => x.id === d.preset);
  const name = !d.name || d.name === prev?.name ? (p.id === 'custom' ? d.name : p.name) : d.name;
  return { ...d, preset: p.id, name, baseUrl: p.baseUrl || (prev && d.baseUrl === prev.baseUrl ? '' : d.baseUrl) };
}
/** /models 返回的列表 → 勾选项；已登记的默认勾选，预设已知的补上限 */
export function picksFrom(models: { id: string; contextTokens?: number; maxOutputTokens?: number }[], preset: Preset | undefined, registered: string[]): ModelPick[] {
  return models.map(m => { const k = preset?.models.find(x => x.id === m.id); return { id: m.id, checked: registered.includes(m.id) || (registered.length === 0 && !!k), contextTokens: m.contextTokens ?? k?.contextTokens, maxOutputTokens: m.maxOutputTokens ?? k?.maxOutputTokens }; });
}
export function addManual(picks: ModelPick[], id: string, preset?: Preset): ModelPick[] {
  const t = id.trim(); if (!t || picks.some(p => p.id === t)) return picks;
  const k = preset?.models.find(x => x.id === t); return [...picks, { id: t, checked: true, contextTokens: k?.contextTokens ?? 32000, maxOutputTokens: k?.maxOutputTokens ?? 4096 }];
}
/** 保存请求体：密钥为空表示保留原密钥 */
export function saveBody(d: Draft, picks: ModelPick[], profiles: string[]) {
  return { id: d.id, name: d.name.trim(), preset: d.preset, baseUrl: d.baseUrl.trim(), apiKey: d.apiKey.trim() || undefined,
    models: picks.filter(p => p.checked).map(p => ({ id: p.id, contextTokens: p.contextTokens, maxOutputTokens: p.maxOutputTokens })), addToProfiles: profiles };
}
export function maskKey(r: ProviderRow) { return r.keySource === 'db' ? (r.last4 ? `••••${r.last4}` : '未保存') : `环境变量 ${r.envVar ?? ''}`; }
export const PROFILE_LABEL: Record<string, string> = { exec: '执行档位', judges: '评审档位', cheap: '降级档位' };

export interface JudgeSeat { chain: string[]; temperature?: number; persona?: string }
export interface JudgeModel { id: string; provider: string }
/** 表单仅编辑草稿；应用时后端仍会校验整份配置。 */
export function changeJudgePrimary(seats: JudgeSeat[], index: number, model: string): JudgeSeat[] {
  return seats.map((s, i) => i !== index ? s : { ...s, chain: model ? [model, ...s.chain.filter(m => m !== model)] : [] });
}
export function judgeDraftErrors(seats: JudgeSeat[]): string[] {
  const errors: string[] = [];
  if (seats.length < 2 || seats.some(s => !s.chain[0])) errors.push('请为每个评委选择主模型');
  if (new Set(seats.map(s => s.chain[0]).filter(Boolean)).size < 2) errors.push('至少需要两个不同的评审模型');
  return errors;
}
