import { useEffect, useState } from 'react';
import { api } from '../api';
import { Badge, Button, Callout, Card, Field, Input, Select, Toggle } from '../ui';
import { PRESET_SOURCE, presetKey, presetOptions, type Preset } from '../lib/pipeline';
import { statusName } from '../labels';

const OPTIONAL = ['SPEC', 'DESIGN'];

/** 流程预设：组装的起点。内置与业务线模板自动转换成预设；管理员可以基于某个预设关掉可选步骤另存 */
export function PresetsCard({ canEdit }: { canEdit: boolean }) {
  const [ps, setPs] = useState<Preset[] | null>(null); const [v2, setV2] = useState(false); const [err, setErr] = useState(''); const [ok, setOk] = useState('');
  const [f, setF] = useState<{ id: string; name: string; description: string; base: string; disable: string[] } | null>(null); const [busy, setBusy] = useState(false);
  const load = () => api<{ presets: Preset[]; pipelineV2: boolean }>('/api/pipeline/presets').then(r => { setPs(r.presets); setV2(r.pipelineV2); }).catch(e => setErr(e.message));
  useEffect(() => { load(); }, []);
  const save = async () => { if (!f) return; setBusy(true); setErr(''); try { const p = await api<Preset>('/api/pipeline/presets', { method: 'POST', body: f }); setOk(`已保存预设「${p.name}」`); setF(null); await load(); } catch (e: any) { setErr(e.message); } finally { setBusy(false); } };
  return <Card title="流程预设" subtitle="预设只是组装流程的起点：规则、治理必选和 AI 提议会在它的基础上增减步骤。项目在「流程设置」里选默认预设，手动建任务时也可以临时选。" data-testid="presets-card"
    actions={canEdit ? <Button size="sm" variant="primary" icon="plus" onClick={() => setF({ id: '', name: '', description: '', base: 'default@1', disable: [] })} data-testid="preset-new">另存预设</Button> : undefined}>
    {!v2 && <div className="mb-3"><Callout tone="info">本工作区还没开启可组合流程，任务仍按业务线绑定的流程模板执行；预设在开启后生效（「工作区成员」页的「可组合流程」）。</Callout></div>}
    {err && <div className="mb-3"><Callout tone="danger" onClose={() => setErr('')}>{err}</Callout></div>}
    {ok && <div className="mb-3"><Callout tone="success" onClose={() => setOk('')}>{ok}</Callout></div>}
    {!ps ? <p className="text-sm text-ink-3">加载中…</p> : <div className="divide-y divide-line">{ps.map(p => <div key={presetKey(p)} className="py-2 text-sm" data-testid={`preset-${p.id}`}>
      <div className="flex flex-wrap items-center gap-2"><b className="text-ink">{p.name}</b><Badge tone={p.source === 'builtin' ? 'neutral' : p.source === 'workspace' ? 'accent' : 'info'}>{PRESET_SOURCE[p.source]}</Badge>{p.owner && <Badge tone="info">业务线私有</Badge>}</div>
      {p.description && <p className="mt-0.5 text-xs text-ink-3">{p.description}</p>}
      <p className="mt-1 flex flex-wrap gap-1">{p.nodes.map(n => <span key={n} className="border border-line px-1 text-caption text-ink-2">{statusName(n)}</span>)}</p>
    </div>)}</div>}
    {f && ps && <div className="mt-3 space-y-2 border-t border-line pt-3" data-testid="preset-editor">
      <div className="grid gap-2 md:grid-cols-3">
        <Field label="预设标识（小写字母、数字、连字符）">{id => <Input id={id} value={f.id} onChange={e => setF({ ...f, id: e.target.value })} />}</Field>
        <Field label="名称">{id => <Input id={id} value={f.name} onChange={e => setF({ ...f, name: e.target.value })} placeholder="例如：轻量修复" />}</Field>
        <Field label="起点">{id => <Select id={id} value={f.base} onChange={e => setF({ ...f, base: e.target.value })}>{presetOptions(ps).map(o => <option key={o.value} value={o.value}>{o.label}</option>)}</Select>}</Field>
      </div>
      <Field label="说明">{id => <Input id={id} value={f.description} onChange={e => setF({ ...f, description: e.target.value })} />}</Field>
      <div className="flex flex-wrap gap-4">{OPTIONAL.map(s => <Toggle key={s} checked={!f.disable.includes(s)} onChange={v => setF({ ...f, disable: v ? f.disable.filter(x => x !== s) : [...f.disable, s] })} label={statusName(s)} hint="可选步骤，关闭后从起点里去掉" />)}</div>
      <div className="flex gap-2"><Button variant="primary" loading={busy} disabled={!f.id || !f.name} onClick={save}>保存预设</Button><Button onClick={() => setF(null)}>取消</Button></div>
    </div>}
  </Card>;
}
