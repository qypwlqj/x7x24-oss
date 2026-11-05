import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import { addManual, applyPreset, maskKey, picksFrom, PROFILE_LABEL, saveBody, type Draft, type ModelPick, type Preset, type ProviderRow } from '../lib/providers';
import { Badge, Button, Callout, Card, Checkbox, Field, Input, Select, toast, useConfirm } from '../ui';

interface Resp { providers: ProviderRow[]; presets: Preset[]; profiles: string[]; admin: boolean; adminsConfigured: boolean }
interface TestResp { ok: boolean; modelsSupported?: boolean; models?: { id: string; contextTokens?: number; maxOutputTokens?: number }[]; error?: string; latencyMs?: number; ping?: { ok: boolean; latencyMs?: number; error?: string } }
const empty: Draft = { name: '', preset: '', baseUrl: '', apiKey: '' };

/** 接入模型 / 供应商：表单 → 测试连接 → 勾选模型与档位 → 保存。密钥只写不读。 */
export default function ProviderOnboarding({ onChanged }: { onChanged: () => void }) {
  const confirm = useConfirm();
  const [d, setD] = useState<Resp | null>(null); const [draft, setDraft] = useState<Draft | null>(null);
  const [picks, setPicks] = useState<ModelPick[]>([]); const [profiles, setProfiles] = useState<string[]>([]); const [manual, setManual] = useState(''); const [pingModel, setPingModel] = useState('');
  const [test, setTest] = useState<TestResp | null>(null); const [busy, setBusy] = useState(''); const [err, setErr] = useState(''); const [msg, setMsg] = useState('');
  const load = useCallback(async () => setD(await api<Resp>('/api/pipeline/providers')), []);
  useEffect(() => { load().catch(e => setErr(e.message)); }, [load]);
  const preset = d?.presets.find(p => p.id === draft?.preset);
  const existing = d?.providers.find(p => p.id === draft?.id);
  const open = (r?: ProviderRow) => { setErr(''); setMsg(''); setTest(null); setManual(''); setPingModel(''); setProfiles(r?.profiles ?? []);
    setDraft(r ? { id: r.id, name: r.name, preset: r.preset ?? 'custom', baseUrl: r.baseUrl, apiKey: '' } : empty);
    setPicks(r ? r.models.map(id => ({ id, checked: true })) : []); };
  const doTest = async () => { if (!draft) return; setBusy('test'); setErr(''); setTest(null);
    try { const t = await api<TestResp>('/api/pipeline/providers/test', { method: 'POST', body: { providerId: draft.id, baseUrl: draft.baseUrl, apiKey: draft.apiKey || undefined, pingModel: pingModel || undefined } });
      setTest(t); if (t.ok && t.models?.length) { const fresh = picksFrom(t.models, preset, existing?.models ?? []); setPicks(prev => [...fresh, ...prev.filter(p => !fresh.some(f => f.id === p.id))]); } }
    catch (e: any) { setErr(e.message); } finally { setBusy(''); } };
  const doSave = async () => { if (!draft) return; setBusy('save'); setErr('');
    try { await api('/api/pipeline/providers', { method: 'POST', body: saveBody(draft, picks, profiles) }); toast.success(`${draft.name || draft.id} 已保存并立即生效`); setDraft(null); await load(); onChanged(); }
    catch (e: any) { setErr(e.message); } finally { setBusy(''); } };
  const doDelete = async (r: ProviderRow) => { if (!await confirm({ title: `删除供应商 ${r.name}？`, description: '其模型会从所有档位中移除，密钥一并删除。', confirmText: '删除', destructive: true })) return; setErr('');
    try { await api(`/api/pipeline/providers/${encodeURIComponent(r.id)}`, { method: 'DELETE' }); toast.success(`${r.name} 已删除`); await load(); onChanged(); } catch (e: any) { setErr(e.message); } };
  const setPick = (i: number, patch: Partial<ModelPick>) => setPicks(ps => ps.map((p, j) => j === i ? { ...p, ...patch } : p));
  const canSave = !!draft && draft.baseUrl.trim() && draft.name.trim() && (draft.apiKey.trim() || existing) && picks.some(p => p.checked) && (test?.ok || (existing && !draft.apiKey));

  if (!d) return null;
  return <Card title="接入模型 / 供应商" subtitle="填写地址和密钥 → 测试连接 → 勾选要用的模型 → 保存。密钥加密保存，之后只显示末 4 位。" data-testid="provider-onboarding"
    actions={d.admin && !draft ? <Button variant="primary" size="sm" onClick={() => open()}>接入新供应商</Button> : undefined}>
    {!d.adminsConfigured && <Callout tone="warn">还没有指定平台管理员，当前无人拥有管理平台模型供应商的权限。<span className="ml-1 text-xs text-ink-3">（由管理员在服务器配置 <code>X7_PLATFORM_ADMINS</code>）</span></Callout>}
    {!d.admin && <Callout tone="info">只有平台管理员可以接入或修改供应商。</Callout>}
    {err && <Callout tone="danger" onClose={() => setErr('')}>{err}</Callout>}
    {msg && <Callout tone="success" onClose={() => setMsg('')}>{msg}</Callout>}
    <div className="divide-y divide-line">{d.providers.map(r => <div key={r.id} className="flex flex-wrap items-center gap-3 py-2 text-sm">
      <b>{r.name}</b><span className="text-ink-3">{r.baseUrl}</span><code className="text-xs">{maskKey(r)}</code>
      {r.keyUpdatedAt && <span className="text-xs text-ink-3">更新于 {new Date(r.keyUpdatedAt).toLocaleString()}</span>}
      <Badge tone={r.available ? 'success' : 'warn'}>{r.available ? '可用' : '缺少密钥'}</Badge>
      <span className="text-xs text-ink-3">{r.models.join('、')}</span>
      {d.admin && <span className="ml-auto flex gap-2"><Button size="sm" onClick={() => open(r)}>编辑 / 替换密钥</Button><Button size="sm" onClick={() => doDelete(r)}>删除</Button></span>}
    </div>)}</div>
    {draft && <div className="mt-3 space-y-3 rounded border border-line p-3">
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="预设">{id => <Select id={id} value={draft.preset} onChange={e => setDraft(applyPreset(draft, d.presets, e.target.value))}><option value="">选择供应商…</option>{d.presets.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</Select>}</Field>
        <Field label="名称">{id => <Input id={id} value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} placeholder="例如 DeepSeek" />}</Field>
        <Field label="Base URL" hint="必须是 https；不允许内网地址">{id => <Input id={id} value={draft.baseUrl} onChange={e => setDraft({ ...draft, baseUrl: e.target.value })} placeholder="https://api.example.com/v1" />}</Field>
        <Field label="API Key" hint={existing ? `已保存 ${maskKey(existing)}；留空表示不更换` : '保存后只显示末 4 位'}>{id => <Input id={id} type="password" autoComplete="new-password" value={draft.apiKey} onChange={e => setDraft({ ...draft, apiKey: e.target.value })} placeholder={existing ? '留空则保留原密钥' : 'sk-…'} />}</Field>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Input className="w-56" value={pingModel} onChange={e => setPingModel(e.target.value)} placeholder="（可选）发一条测试消息的模型 id" />
        <Button variant="primary" loading={busy === 'test'} disabled={!draft.baseUrl || (!draft.apiKey && !existing)} onClick={doTest}>测试连接</Button>
        {test && <Badge tone={test.ok ? 'success' : 'danger'}>{test.ok ? `连接成功${test.latencyMs ? ` · ${test.latencyMs}ms` : ''}` : '连接失败'}</Badge>}
        {test?.ping && <Badge tone={test.ping.ok ? 'success' : 'danger'}>{test.ping.ok ? `对话测试通过 · ${test.ping.latencyMs}ms` : `对话测试失败：${test.ping.error ?? ''}`}</Badge>}
      </div>
      {test?.error && <Callout tone={test.ok ? 'warn' : 'danger'}>{test.error}</Callout>}
      {(test?.ok || existing) && <>
        <div><b className="text-sm">选择模型</b>{test?.ok && !test.modelsSupported && <span className="ml-2 text-xs text-ink-3">该地址不提供模型列表，请手动填写模型 id</span>}
          <div className="mt-1 max-h-72 space-y-1 overflow-auto">{picks.map((p, i) => <div key={p.id} className="flex flex-wrap items-center gap-2 text-sm">
            <label className="flex w-72 items-center gap-2"><Checkbox checked={p.checked} onCheckedChange={v => setPick(i, { checked: v === true })} aria-label={p.id} /><code>{p.id}</code></label>
            {p.checked && <><span className="text-xs text-ink-3">上下文</span><Input className="w-28" type="number" value={p.contextTokens ?? ''} placeholder="32000" onChange={e => setPick(i, { contextTokens: e.target.value ? Number(e.target.value) : undefined })} />
              <span className="text-xs text-ink-3">最大输出</span><Input className="w-24" type="number" value={p.maxOutputTokens ?? ''} placeholder="4096" onChange={e => setPick(i, { maxOutputTokens: e.target.value ? Number(e.target.value) : undefined })} /></>}
          </div>)}</div>
          <div className="mt-2 flex gap-2"><Input className="w-64" value={manual} onChange={e => setManual(e.target.value)} placeholder="手动添加模型 id" /><Button size="sm" onClick={() => { setPicks(addManual(picks, manual, preset)); setManual(''); }}>添加</Button></div>
        </div>
        <div><b className="text-sm">模型用途（可选）</b><div className="mt-1 flex flex-wrap gap-4 text-sm">{d.profiles.map(p => <label key={p} className="flex items-center gap-2"><Checkbox checked={profiles.includes(p)} onCheckedChange={v => setProfiles(v === true ? [...profiles, p] : profiles.filter(x => x !== p))} />{PROFILE_LABEL[p] ?? p}{p === 'judges' && <span className="text-xs text-ink-3">（加入委员会，未就绪时自动分配评委）</span>}</label>)}</div>
          <p className="mt-1 text-xs text-ink-3">评审需要至少两个不同模型，可以分次接入；已有合法的评委分配会保留。其他用途按顺序添加备用模型。不勾选则只登记。</p></div>
      </>}
      <div className="flex gap-2"><Button variant="primary" loading={busy === 'save'} disabled={!canSave} onClick={doSave}>保存</Button><Button onClick={() => setDraft(null)}>取消</Button>
        {!canSave && <span className="self-center text-xs text-ink-3">{existing && !draft.apiKey ? '勾选至少一个模型后可保存' : '先测试连接成功并勾选至少一个模型'}</span>}</div>
    </div>}
  </Card>;
}
