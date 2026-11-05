import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import { bindable, diffEffective, isOptional, key, setRoleSkills, setStageField, toggleStage, type Effective, type LineBinding, type PipelineState } from '../lib/pipeline';
import { Badge, Button, Callout, Card, Field, Input, Select, Tabs, Textarea, Toggle, Checkbox } from '../ui';
import { Disclosure } from '@/components/ui/collapsible';
import { TableRoot, TableBody, TableRow, TableCell } from '@/components/ui/table';
import { Tip } from '../ui/InfoTip';
import { roleName } from '../copy/terms';
import { statusName } from '../labels';

type Tab = 'flow' | 'roles' | 'models';
const MAKER_ROLES = ['dev.code', 'prd.draft', 'plan.make', 'spec.write', 'design.make'] as const;

/** 业务线详情：流程 / 角色与技能 / 模型 */
export default function LinePipeline({ lineId }: { lineId: string }) {
  const [state, setState] = useState<PipelineState | null>(null); const [current, setCurrent] = useState<Effective | null>(null);
  const [draft, setDraft] = useState<LineBinding | null>(null); const [preview, setPreview] = useState<Effective | null>(null);
  const [profiles, setProfiles] = useState<string[]>([]); const [tab, setTab] = useState<Tab>('flow');
  const [selectedBaseRole, setSelectedBaseRole] = useState<string>('dev.code');
  const [error, setError] = useState(''); const [msg, setMsg] = useState(''); const [busy, setBusy] = useState(false);
  const [v2, setV2] = useState(false);
  useEffect(() => { api<{ pipelineV2: boolean }>('/api/pipeline/features').then(r => setV2(r.pipelineV2)).catch(() => setV2(false)); }, []);
  const load = useCallback(async () => {
    const [s, e] = await Promise.all([api<PipelineState>('/api/pipeline'), api<Effective>(`/api/pipeline/lines/${encodeURIComponent(lineId)}/effective`)]);
    setState(s); setCurrent(e); setDraft(s.lines[lineId] ?? { template: 'default@1' }); setPreview(null);
    api<{ config: { profiles: Record<string, unknown> } }>('/api/pipeline/models').then(m => setProfiles(Object.keys(m.config.profiles ?? {}))).catch(() => setProfiles([]));
  }, [lineId]);
  useEffect(() => { setError(''); setMsg(''); load().catch(e => setError(e.message)); }, [load]);

  const tpl = useMemo(() => state?.templates.find(t => key(t) === draft?.template), [state, draft]);
  // 预览：草稿变化后自动解析 + 校验（不保存）
  useEffect(() => {
    if (!draft) return; let alive = true;
    const t = setTimeout(() => { api<Effective>(`/api/pipeline/lines/${encodeURIComponent(lineId)}/preview`, { method: 'POST', body: draft }).then(p => { if (alive) { setPreview(p); setError(''); } }).catch(e => { if (alive) { setPreview(null); setError(e.message); } }); }, 300);
    return () => { alive = false; clearTimeout(t); };
  }, [draft, lineId]);
  const diff = useMemo(() => diffEffective(current, preview), [current, preview]);
  if (!state || !draft) return <Card title="流程 / 角色 / 模型"><p className="text-sm text-ink-3">{error || '加载中…'}</p></Card>;

  const derivedRoleId = `${lineId}.${selectedBaseRole}`; const curRole = draft.roles?.[derivedRoleId];
  const save = async () => {
    setBusy(true); setMsg('');
    try { const r = await api<{ effective: Effective }>(`/api/pipeline/lines/${encodeURIComponent(lineId)}`, { method: 'PUT', body: draft }); setMsg(`已保存。新任务和重新规划的任务会用新配置，进行中的任务不受影响。（配置版本 ${r.effective.id}）`); await load(); }
    catch (e: any) { setError(e.message); } finally { setBusy(false); }
  };
  const stageOn = (type: string) => { const o = draft.stageOverrides?.[type]?.enabled; const d = tpl?.stages.find(s => s.type === type)?.enabled; return o ?? d ?? true; };

  return <Card title="流程 / 角色 / 模型" subtitle={<>当前流程：{current?.template ?? '-'} <span className="font-mono text-ink-3">· 配置版本 {current?.id ?? '-'}</span></>} data-testid="line-pipeline"
    actions={<div className="flex gap-2"><Button size="sm" onClick={() => setDraft(state.lines[lineId] ?? { template: 'default@1' })}>撤销修改</Button><Button size="sm" variant="primary" disabled={busy || !preview || diff.length === 0} onClick={save}>保存（{diff.length} 处变化）</Button></div>}>
    {error && <Callout tone="danger" onClose={() => setError('')}>{error}</Callout>}
    {msg && <Callout tone="success" onClose={() => setMsg('')}>{msg}</Callout>}
    <Tabs label="业务线配置" value={tab} onChange={setTab} items={[{ id: 'flow', label: '流程' }, { id: 'roles', label: '角色与技能' }, { id: 'models', label: '模型' }]} className="mb-3" />
    {tab === 'flow' && <div className="space-y-3">
      {v2 && <Callout tone="info">已开启可组合流程：新任务的流程起点改为项目「流程设置」里的默认预设（没选时沿用这里的模板），再按规则、治理必选和 AI 提议组装。这里的模板与步骤开关仍对开启前的任务生效。</Callout>}
      <Field label={v2 ? '流程模板（项目未选预设时的起点）' : '流程模板'} hint={tpl?.description}>{id => <Select id={id} value={draft.template} onChange={e => setDraft({ ...draft, template: e.target.value })}>
        {bindable(state.templates, lineId).map(t => <option key={key(t)} value={key(t)}>{t.name ?? t.id}（{key(t)}{t.owner ? '，本业务线私有' : ''}）</option>)}
      </Select>}</Field>
      <div className="divide-y divide-line border border-line">{state.catalog.stageTypes.map(st => {
        const optional = isOptional(state.catalog.stageTypes, st.type); const ov = draft.stageOverrides?.[st.type] ?? {}; const base = tpl?.stages.find(s => s.type === st.type);
        return <div key={st.type} className="grid gap-2 p-3 md:grid-cols-[180px_1fr]">
          <div className="flex items-center gap-2"><b className="text-sm">{st.name}</b>{optional ? <Badge tone="info">可选</Badge> : <Badge tone="neutral">锁定</Badge>}</div>
          <div className="flex flex-wrap items-center gap-4 text-sm">
            {optional ? <Toggle label="启用" hint="关闭后规划器给出的路径会跳过该节点" checked={stageOn(st.type)} onChange={v => setDraft(toggleStage(draft, st.type, v, base?.enabled ?? true))} /> : <span className="text-xs text-ink-3">不可关闭，只能收紧参数（对话录入小需求可通过极速通道或动态节点替换）</span>}
            {['PRD_REVIEW', 'DESIGN', 'ACCEPTANCE'].includes(st.type) && <label className="flex items-center gap-1 text-xs">通过阈值<Input className="w-20" type="number" step="0.05" min={0} max={1} placeholder={base?.gate?.pass?.toString() ?? '治理配置'} value={ov.gate?.pass ?? ''} onChange={e => setDraft(setStageField(draft, st.type, 'gate', e.target.value === '' ? undefined : { ...(ov.gate ?? {}), pass: Number(e.target.value) }))} /></label>}
            {st.type === 'QA' && <label className="flex items-center gap-1 text-xs">返修上限<Input className="w-16" type="number" min={1} max={10} placeholder={base?.maxLoops?.toString() ?? '治理配置'} value={ov.maxLoops ?? ''} onChange={e => setDraft(setStageField(draft, 'QA', 'maxLoops', e.target.value === '' ? undefined : Number(e.target.value)))} /></label>}
            {st.type === 'QA' && Object.entries(state.catalog.checks).filter(([c]) => c !== 'ci').map(([c, desc]) => { const list = ov.checks ?? base?.checks ?? ['ci']; const on = list.includes(c);
              return <Tip key={c} content={desc}><label className="flex items-center gap-1 text-xs"><Checkbox checked={on} onCheckedChange={() => setDraft(setStageField(draft, 'QA', 'checks', on ? list.filter(x => x !== c) : [...new Set(['ci', ...list, c])]))} />{c}</label></Tip>; })}
            {st.type === 'DEV' && <label className="flex items-center gap-1 text-xs">单次改动上限（加权行）<Input className="w-24" type="number" min={1} max={state.catalog.floor.maxChangeLinesCeiling} placeholder={base?.maxChangeLines?.toString() ?? '治理配置'} value={ov.maxChangeLines ?? ''} onChange={e => setDraft(setStageField(draft, 'DEV', 'maxChangeLines', e.target.value === '' ? undefined : Number(e.target.value)))} /></label>}
          </div>
        </div>; })}</div>
      <p className="text-xs text-ink-3">全局上限：单次改动 ≤ {state.catalog.floor.maxChangeLinesCeiling} 行，自治等级 ≤ {state.catalog.floor.maxAutonomy}，评审 ≥ {state.catalog.floor.minJudgeSeats} 席。业务线覆盖只能比模板更严格。</p>
    </div>}
    {tab === 'roles' && <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-ink-2"><b>助手角色与技能配置</b>：在内置执行助手的基础上，为本业务线派生角色、挂载技能或补充要求。输出格式和安全规则不能改。</p>
        <Select className="w-56" value={selectedBaseRole} onChange={e => setSelectedBaseRole(e.target.value)} aria-label="选择要配置的助手角色">
          {MAKER_ROLES.map(r => <option key={r} value={r}>{roleName(r)}（{r}）{draft.roles?.[`${lineId}.${r}`] ? ' · 已定制' : ''}</option>)}
        </Select>
      </div>
      <Disclosure summaryClassName="text-xs text-ink-3" summary={<>详情</>}><p className="mt-1 text-xs text-ink-3">派生角色 id <code>{derivedRoleId}</code>，基于内置角色 <code>{selectedBaseRole}</code>。技能会作为额外要求交给这个助手，优先级低于安全规则。</p></Disclosure>
      <div className="grid gap-2 md:grid-cols-2">{state.catalog.skills.map(s => { const k = `${s.id}@${s.version}`; const on = curRole?.skills?.includes(k) ?? false;
        return <label key={k} className={`border p-3 text-sm ${on ? 'border-primary bg-primary-soft' : 'border-line'}`}><div className="flex items-center gap-2"><Checkbox checked={on} onCheckedChange={() => setDraft(setRoleSkills(draft, lineId, selectedBaseRole, on ? (curRole?.skills ?? []).filter(x => x !== k) : [...(curRole?.skills ?? []), k], curRole?.instructions ?? '', curRole?.profile))} /><b>{s.name ?? s.id}</b><span className="text-xs text-ink-3">{k}</span></div><p className="mt-1 text-xs text-ink-3">{s.instructions.slice(0, 120)}…</p></label>; })}</div>
      <Field label="业务线补充要求（可选）">{id => <Textarea id={id} rows={3} value={curRole?.instructions ?? ''} onChange={e => setDraft(setRoleSkills(draft, lineId, selectedBaseRole, curRole?.skills ?? [], e.target.value, curRole?.profile))} />}</Field>
      {preview?.skillText[derivedRoleId] && <Disclosure summaryClassName="cursor-pointer text-sm text-primary-ink" summary={<>查看助手会收到的要求</>}><pre className="mt-2 whitespace-pre-wrap border border-line p-2 text-xs">{preview.skillText[derivedRoleId]}</pre></Disclosure>}
    </div>}
    {tab === 'models' && <div className="space-y-2">
      <p className="text-sm text-ink-2">为本业务线的各个执行助手选择模型方案（方案在「模型配置」页维护），不选就用默认。评审用的模型不在这里改，这样干活的和打分的互不影响。</p>
      {['prd.draft', 'plan.make', 'spec.write', 'design.make', 'dev.code'].map(r => <div key={r} className="flex items-center gap-3 text-sm"><span className="w-32">{roleName(r)} <code className="text-caption text-ink-3">{r}</code></span>
        <Select className="w-56" value={draft.models?.[r] ?? ''} onChange={e => { const m = { ...(draft.models ?? {}) }; if (e.target.value) m[r] = e.target.value; else delete m[r]; setDraft({ ...draft, models: Object.keys(m).length ? m : undefined }); }}>
          <option value="">全局默认</option>{profiles.filter(p => p !== 'judges').map(p => <option key={p} value={p}>{p}</option>)}
        </Select></div>)}
    </div>}
    <DryRun lineId={lineId} draft={draft} />
    <div className="mt-4 border-t border-line pt-3"><b className="text-sm">保存前预览</b>{preview && <span className="ml-2 font-mono text-xs text-ink-3">新配置版本 {preview.id}</span>}
      {diff.length === 0 ? <p className="text-xs text-ink-3">没有变化</p> : <TableRoot className="mt-2 text-xs"><TableBody>{diff.map(d => <TableRow key={d.path} className="border-b border-line"><TableCell className="py-1 pr-2 font-mono">{d.path}</TableCell><TableCell className="pr-2 text-ink-3 line-through">{d.from.slice(0, 80)}</TableCell><TableCell className="text-ink">{d.to.slice(0, 80)}</TableCell></TableRow>)}</TableBody></TableRoot>}
    </div>
  </Card>;
}

interface DryRunResult { template: string; effective: string; path: string[]; skipped: string[]; steps: { stage: string; role?: string; profile?: string; chain?: string[]; skills?: string[]; checker?: string; checks?: string[] }[]; baselineUsd: number; note: string; task?: { id: string; title: string; status: string } }
/** 试运行：用草稿对一个历史任务做解析 + 校验 + 路由，不调用模型 */
function DryRun({ lineId, draft }: { lineId: string; draft: LineBinding }) {
  const [taskId, setTaskId] = useState(''); const [r, setR] = useState<DryRunResult | null>(null); const [err, setErr] = useState('');
  const go = async () => { setErr(''); try { setR(await api<DryRunResult>(`/api/pipeline/lines/${encodeURIComponent(lineId)}/dry-run`, { method: 'POST', body: { binding: draft, taskId: taskId.trim() || undefined } })); } catch (e: any) { setR(null); setErr(e.message); } };
  return <div className="mt-4 border-t border-line pt-3 space-y-2" data-testid="dry-run">
    <div className="flex flex-wrap items-end gap-2"><b className="text-sm">配置预览</b><Input className="w-80" placeholder="历史任务 ID（可选，从任务详情页地址复制）" value={taskId} onChange={e => setTaskId(e.target.value)} /><Button size="sm" onClick={go}>预览当前配置</Button></div>
    {err && <Callout tone="danger" onClose={() => setErr('')}>{err}</Callout>}
    {r && <div className="text-xs">
      <p className="text-ink-2">{r.task ? `任务「${r.task.title}」` : '通用流程'}：{r.path.map(statusName).join(' → ')}{r.skipped.length ? `（跳过 ${r.skipped.map(statusName).join('、')}）` : ''}{r.task ? ` · 历史花费 $${r.baselineUsd}` : ''} <span className="font-mono text-ink-3">· 流程 {r.template} · 配置版本 {r.effective}</span></p>
      <TableRoot className="mt-1 text-[length:inherit]"><TableBody>{r.steps.map(s => <TableRow key={s.stage} className="border-b border-line"><TableCell className="py-1 pr-2">{statusName(s.stage)}</TableCell><TableCell className="pr-2">{s.role ? <>{roleName(s.role)} <code className="text-ink-3">{s.role}</code></> : '—'}</TableCell><TableCell className="pr-2 text-ink-3">{s.profile ? `${s.profile}：${(s.chain ?? []).join(' → ')}` : ''}</TableCell><TableCell className="text-ink-3">{[s.skills?.length ? `技能 ${s.skills.join(', ')}` : '', s.checker ? `验收 ${s.checker}` : '', s.checks?.length ? `检查 ${s.checks.join(', ')}` : ''].filter(Boolean).join(' · ')}</TableCell></TableRow>)}</TableBody></TableRoot>
      <p className="mt-1 text-ink-3">{r.note}</p>
    </div>}
  </div>;
}
