import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import { skillKey, type PipelineState, type Skill } from '../lib/pipeline';
import ProviderOnboarding from './ProviderOnboarding';
import { Badge, Button, Callout, Card, Field, Input, PageHeader, Select, Textarea } from '../ui';
import { changeJudgePrimary, judgeDraftErrors, type JudgeModel, type JudgeSeat } from '../lib/providers';
import { roleName } from '../copy/terms';
import { Disclosure } from '@/components/ui/collapsible';
import { PresetsCard } from '../components/PresetsCard';

interface ModelsResp { mode: string; config: any; own: any | null; platformFallback: boolean; committee: { ready: boolean; models: string[]; errors: string[] }; providerAvailable: Record<string, boolean>; breakers: Record<string, { open: boolean; failures: number; recent: number; lastError?: string; lastErrorAt?: number; lastStatus?: number }>; errors: string[]; roles: Record<string, { profile: string; chain: string[] }> }

const EMPTY_SEATS: JudgeSeat[] = [{ chain: [] }, { chain: [] }, { chain: [] }];
export function ModelHealth({ breakers, models, available }: { breakers: ModelsResp['breakers']; models: JudgeModel[]; available: Record<string, boolean> }) {
  const entries = models.flatMap(model => breakers[model.id] ? [{ model, breaker: breakers[model.id] }] : []);
  return <div><b>模型健康状态</b>{entries.length === 0 ? <p className="text-xs text-ink-3">暂无记录</p> : entries.map(({ model, breaker: b }) => {
    const usable = !!available[model.provider];
    const label = !usable ? '不可用（缺少供应商密钥）' : b.open ? '暂停使用（连续出错，稍后自动恢复）' : b.failures > 0 ? '近期调用有失败' : b.recent > 0 ? '正常' : '等待验证';
    return <div key={model.id} className="mt-1 flex flex-wrap gap-2"><code>{model.id}</code><Badge tone={!usable || b.open ? 'danger' : b.failures > 0 || b.recent === 0 ? 'warn' : 'success'}>{label}</Badge><span className="text-xs text-ink-3">最近 {b.recent} 次，失败 {b.failures}</span>{b.lastError ? <span className="basis-full text-xs text-ink-3">最近错误（{b.lastErrorAt ? new Date(b.lastErrorAt).toLocaleString('zh-CN', { hour12: false }) : '时间未知'}）：{b.lastError}</span> : null}</div>;
  })}</div>;
}
export function CommitteeEditor({ initialSeats, models, available, status, inherited, canEdit, onSaved }: {
  initialSeats: JudgeSeat[]; models: JudgeModel[]; available: Record<string, boolean>; status: ModelsResp['committee']; inherited: boolean; canEdit: boolean; onSaved: () => Promise<void>;
}) {
  const [seats, setSeats] = useState(() => initialSeats.map(s => ({ ...s, chain: [...s.chain] })));
  const [busy, setBusy] = useState(false); const [feedback, setFeedback] = useState(''); const [error, setError] = useState('');
  const draftErrors = judgeDraftErrors(seats);
  const dirty = JSON.stringify(seats) !== JSON.stringify(initialSeats);
  const updateChain = (index: number, chain: string[]) => setSeats(prev => prev.map((s, i) => i === index ? { ...s, chain } : s));
  const autoAssign = async () => {
    setBusy(true); setError(''); setFeedback('');
    try {
      const preview = await api<{ profile: { seats: JudgeSeat[] }; errors: string[] }>('/api/pipeline/models/committee/preview', { method: 'POST', body: { seats, autoAssign: true } });
      setSeats(preview.profile.seats); setFeedback(preview.errors.length ? preview.errors.join('；') : '已生成分配预览，保存后生效');
    } catch (e: unknown) { setError(e instanceof Error ? e.message : '自动分配失败'); } finally { setBusy(false); }
  };
  const save = async () => {
    setBusy(true); setError(''); setFeedback('');
    try { await api('/api/pipeline/models/committee', { method: 'PUT', body: { seats, autoAssign: false } }); await onSaved(); setFeedback('评审委员会已保存；已暂停的任务可回到原评审节点恢复'); }
    catch (e: unknown) { setError(e instanceof Error ? e.message : '保存失败'); } finally { setBusy(false); }
  };
  return <Card title="评审委员会" subtitle="勾选评审用途的模型会自动参与分配；至少两种模型承担三个评审职责，最后一席兼任分歧仲裁。" data-testid="committee-editor"
    actions={<Badge tone={status.ready ? 'success' : 'warn'}>{status.ready ? '已就绪' : '未就绪'}</Badge>}>
    <p className="text-sm text-ink-2">当前生效模型：{status.models.filter(Boolean).join('、') || '尚未分配'}{inherited ? '（使用平台配置；下方编辑本工作区配置）' : ''}</p>
    {!status.ready && <Callout tone="warn">{status.errors.join('；')}。可分次接入模型，补齐后再开始评审。</Callout>}
    {error && <Callout tone="danger">{error}</Callout>}
    {feedback && <Callout tone="info">{feedback}</Callout>}
    <div className="mt-3 grid gap-3 lg:grid-cols-3">{seats.map((seat, i) => <div key={i} className="space-y-2 border border-line p-3" data-testid={`judge-seat-${i}`}>
      <b className="text-sm">评委 {i + 1} · {['价值 / 用例覆盖', '可行性 / 可用性', '风险'][Math.min(i, 2)]}{i === seats.length - 1 ? '（兼任仲裁）' : ''}</b>
      <Field label="主模型">{id => <Select id={id} value={seat.chain[0] ?? ''} disabled={!canEdit || busy} onChange={e => setSeats(prev => changeJudgePrimary(prev, i, e.target.value))} aria-label={`评委 ${i + 1} 主模型`}>
        <option value="">选择主模型</option>{models.map(m => <option key={m.id} value={m.id}>{m.id}{available[m.provider] ? '' : '（缺少密钥）'}</option>)}
      </Select>}</Field>
      <div className="text-xs text-ink-3">备用顺序</div>
      {seat.chain.slice(1).map((model, k) => <div key={model} className="flex items-center gap-1 text-xs"><span className="min-w-0 flex-1 break-all">{k + 1}. {model}</span>
        {canEdit && <><Button size="sm" variant="ghost" disabled={busy || k === 0} aria-label={`评委 ${i + 1} 备用 ${model} 上移`} onClick={() => { const chain = [...seat.chain]; [chain[k], chain[k + 1]] = [chain[k + 1], chain[k]]; updateChain(i, chain); }}>上移</Button>
          <Button size="sm" variant="ghost" disabled={busy} aria-label={`评委 ${i + 1} 移除备用 ${model}`} onClick={() => updateChain(i, seat.chain.filter(m => m !== model))}>移除</Button></>}
      </div>)}
      <Select value="" disabled={!canEdit || busy} aria-label={`评委 ${i + 1} 添加备用模型`} onChange={e => { if (e.target.value) updateChain(i, [...seat.chain, e.target.value]); }}>
        <option value="">添加备用模型</option>{models.filter(m => !seat.chain.includes(m.id)).map(m => <option key={m.id} value={m.id}>{m.id}</option>)}
      </Select>
    </div>)}</div>
    {dirty && <p className="mt-2 text-xs text-ink-3">有未保存的调整，当前生效配置尚未改变。</p>}
    {draftErrors.length > 0 && <p className="mt-2 text-xs text-warn">草稿：{draftErrors.join('；')}</p>}
    {canEdit && <div className="mt-3 flex gap-2"><Button disabled={busy || models.length < 2} onClick={autoAssign}>一键自动分配</Button><Button variant="primary" loading={busy} disabled={busy || !dirty || draftErrors.length > 0} onClick={save}>保存评审委员会</Button></div>}
  </Card>;
}

/** 平台级：模型配置（供应商 / 模型 / 档位 / 熔断）+ 流程预设 + 技能库维护 */
export default function Models() {
  const [m, setM] = useState<ModelsResp | null>(null); const [p, setP] = useState<PipelineState | null>(null);
  const [json, setJson] = useState(''); const [error, setError] = useState(''); const [msg, setMsg] = useState('');
  const [skillDraft, setSkillDraft] = useState<{ id: string; name: string; instructions: string; filePatterns: string } | null>(null);
  const load = useCallback(async () => { const [a, b] = await Promise.all([api<ModelsResp>('/api/pipeline/models'), api<PipelineState>('/api/pipeline')]); setM(a); setP(b); setJson(JSON.stringify(a.config, null, 2)); }, []);
  useEffect(() => { load().catch(e => setError(e.message)); }, [load]);
  const run = async (f: () => Promise<unknown>, ok: string) => { setError(''); setMsg(''); try { await f(); setMsg(ok); await load(); } catch (e: any) { setError(e.message); } };
  const admin = p?.me.admin ?? false;
  const editSkill = (s?: Skill) => setSkillDraft(s ? { id: s.id, name: s.name ?? '', instructions: s.instructions, filePatterns: (s.filePatterns ?? []).join(', ') } : { id: '', name: '', instructions: '', filePatterns: '' });

  return <div className="space-y-gap" data-testid="models-page">
    <PageHeader title="模型与流程" desc="平台级配置：模型供应商、档位与兜底链；流程预设；技能库的维护与版本迭代。业务线自己的角色 / 模型在「产品线与项目」里配置，项目的流程设置在项目页。" />
    {error && <Callout tone="danger" onClose={() => setError('')}>{error}</Callout>}
    {msg && <Callout tone="success" onClose={() => setMsg('')}>{msg}</Callout>}
    {p && !p.me.adminsConfigured && <Callout tone="warn">还没有指定平台管理员，当前无人拥有平台管理员权限；请由部署者显式配置管理员名单。<span className="ml-1 text-xs text-ink-3">（由管理员在服务器配置 <code>X7_PLATFORM_ADMINS</code>）</span></Callout>}
    <ProviderOnboarding onChanged={() => { load().catch(e => setError(e.message)); }} />
    {m && <CommitteeEditor key={JSON.stringify(m.own?.profiles?.judges?.seats ?? EMPTY_SEATS)} initialSeats={m.own?.profiles?.judges?.seats ?? EMPTY_SEATS}
      models={m.own?.models ?? []} available={m.providerAvailable} status={m.committee} inherited={!m.own && m.platformFallback} canEdit={admin} onSaved={async () => { await load(); setMsg('评审委员会已保存；已暂停的任务可从原评审节点恢复'); }} />}
    {m && <Card title="模型配置（高级）" subtitle={m.mode === 'rule' ? '还没有接入模型，目前按固定规则打分' : m.config.migratedFromEnv ? <>当前配置来自服务器设置，在本页保存后以本页为准 <code className="text-caption">LLM_MODEL / LLM_JUDGE_MODELS</code></> : '当前配置来自本页'}>
      <div className="grid gap-gap lg:grid-cols-2">
        <div className="space-y-3 text-sm">
          <div><b>供应商</b>{m.config.providers.map((x: any) => <div key={x.id} className="mt-1 flex flex-wrap items-center gap-2"><code>{x.id}</code><span className="text-ink-3">{x.baseUrl}</span><code className="text-xs">{x.secretRef}</code><Badge tone={m.providerAvailable[x.id] ? 'success' : 'warn'}>{m.providerAvailable[x.id] ? '可用' : '缺少密钥，已跳过'}</Badge></div>)}</div>
          <div><b>各执行助手使用的模型（按顺序备用）</b>{Object.entries(m.roles).map(([r, v]) => <div key={r} className="mt-1 flex gap-2"><span className="w-28">{roleName(r)} <code className="text-caption text-ink-3">{r}</code></span><span className="text-ink-3">{v.profile}</span><span className="text-ink-3">{v.chain.join(' → ')}</span></div>)}</div>
          <div><b>评委</b>{(m.config.profiles.judges?.seats ?? []).map((s: any, i: number, all: any[]) => <div key={i} className="mt-1 flex gap-2"><span className="w-16">评委 {i + 1}{i === all.length - 1 ? '（最终判断）' : ''}</span><span>{s.chain.join(' → ')}</span><span className="text-ink-3">温度 {s.temperature ?? '-'}</span></div>)}</div>
          <ModelHealth breakers={m.breakers} models={m.config.models} available={m.providerAvailable} />
          {m.errors.length > 0 && <Callout tone="warn">{m.errors.join('；')}</Callout>}
        </div>
        <div className="space-y-2">
          <b className="text-sm">直接编辑配置（高级）</b>
          <div className="text-xs text-ink-3">一般用上方「接入模型」就够了。模型按列表顺序使用，前一个出错时自动换下一个；没有密钥的供应商会被跳过。<Disclosure className="mt-1" summaryClassName="text-xs text-ink-3" summary={<>高级说明</>}><span className="block text-xs text-ink-3">密钥引用写 <code>db:供应商id</code>（页面保存的加密密钥）或 <code>env:XXX_API_KEY</code>（服务器环境变量）。每个方案里 <code>chain</code> 的顺序就是备用顺序；自动修复时 <code>switch_model</code> 会换到下一个模型。</span></Disclosure></div>
          <Textarea rows={18} className="font-mono text-xs" value={json} onChange={e => setJson(e.target.value)} disabled={!admin} />
          <Button variant="primary" disabled={!admin} onClick={() => run(async () => { await api('/api/pipeline/models', { method: 'PUT', body: JSON.parse(json) }); }, '模型配置已保存并立即生效')}>保存模型配置</Button>
        </div>
      </div>
    </Card>}
    {p && <Card title="技能库" subtitle="可复用的编码规范与架构指令片段，版本化保存（修改已有 ID 会自动生成 @v+1 新版本，不影响在途任务）。"
      actions={admin ? <Button size="sm" variant="primary" onClick={() => editSkill()} data-testid="skill-new">新增技能</Button> : undefined}>
      <div className="divide-y divide-line">{p.catalog.skills.map(s => <div key={skillKey(s)} className="flex flex-wrap items-start gap-3 py-2 text-sm">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2"><b>{s.name ?? s.id}</b><code className="text-xs">{skillKey(s)}</code>{s.builtin && <Badge tone="neutral">内置</Badge>}{s.filePatterns?.length ? <span className="font-mono text-xs text-ink-3">{s.filePatterns.join(', ')}</span> : null}</div>
          <p className="mt-1 text-xs text-ink-2">{s.instructions}</p>
        </div>
        {admin && <Button size="sm" onClick={() => editSkill(s)}>基于此版本修改</Button>}
      </div>)}</div>
      {skillDraft && <div className="mt-3 space-y-2 border-t border-line pt-3" data-testid="skill-editor">
        <b className="text-sm">保存技能（同 ID 自动生成新版本）</b>
        <div className="grid gap-2 md:grid-cols-3">
          <Field label="技能 ID（小写字母数字-）">{id => <Input id={id} placeholder="如 java-spring 或 custom-api" /* copy-ok 输入示例 */ value={skillDraft.id} onChange={e => setSkillDraft({ ...skillDraft, id: e.target.value })} />}</Field>
          <Field label="显示名称">{id => <Input id={id} placeholder="如 Java + Spring Boot" /* copy-ok 输入示例 */ value={skillDraft.name} onChange={e => setSkillDraft({ ...skillDraft, name: e.target.value })} />}</Field>
          <Field label="适用文件通配符（逗号分隔，可选）">{id => <Input id={id} placeholder="**/*.java, **/pom.xml" value={skillDraft.filePatterns} onChange={e => setSkillDraft({ ...skillDraft, filePatterns: e.target.value })} />}</Field>
        </div>
        <Field label="技能指令与规范（≤ 4000 字）">{id => <Textarea id={id} rows={4} value={skillDraft.instructions} onChange={e => setSkillDraft({ ...skillDraft, instructions: e.target.value })} />}</Field>
        <div className="flex gap-2">
          <Button variant="primary" onClick={() => run(async () => {
            const pats = skillDraft.filePatterns.split(',').map(x => x.trim()).filter(Boolean);
            const saved = await api<Skill>('/api/pipeline/skills', { method: 'POST', body: { id: skillDraft.id.trim(), name: skillDraft.name.trim() || undefined, instructions: skillDraft.instructions.trim(), filePatterns: pats.length ? pats : undefined } });
            setSkillDraft(null); return saved;
          }, '技能已保存为新版本')}>保存技能</Button>
          <Button onClick={() => setSkillDraft(null)}>取消</Button>
        </div>
      </div>}
    </Card>}
    {p && <PresetsCard canEdit={admin} />}
  </div>;
}
