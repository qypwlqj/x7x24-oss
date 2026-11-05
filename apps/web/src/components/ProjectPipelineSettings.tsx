import { useEffect, useState } from 'react';
import { api } from '../api';
import { Badge, Button, Callout, Card, Field, Input, Select, Toggle, cx } from '../ui';
import { STACK_LABEL, presetOptions, type Preset } from '../lib/pipeline';

interface ModuleStack { path: string; stack: string }
interface Profile { source: 'auto' | 'manual'; stacks: string[]; modules: ModuleStack[] }
interface Policy { disabled: string[]; mandatory: string[]; defaultPreset?: string | null }
interface CatalogNode { id: string; label: string; module: string; family: string; optional: boolean; available: boolean; allowed: boolean; why: string; mandatory?: string | null; projectMandatory: boolean; description?: string }
interface NodesResp { nodes: CatalogNode[]; stacks: string[]; profile: Profile; policy?: Policy | null }

/** 增删一个节点 id（纯函数，便于测试） */
export const toggleIn = (list: string[], id: string, on: boolean) => on ? [...new Set([...list, id])] : list.filter(x => x !== id);

/** 项目的「流程设置」：技术栈画像（自动识别 / 手动指定）与节点策略（禁用可选步骤、项目级必选步骤） */
export function ProjectPipelineSettings({ projectId, lineId, canEdit }: { projectId: string; lineId?: string; canEdit: boolean }) {
  const [presets, setPresets] = useState<Preset[]>([]);
  useEffect(() => { api<{ presets: Preset[] }>(`/api/pipeline/presets${lineId ? `?line=${encodeURIComponent(lineId)}` : ''}`).then(r => setPresets(r.presets)).catch(() => setPresets([])); }, [lineId]);
  const [data, setData] = useState<NodesResp | null>(null); const [prof, setProf] = useState<Profile | null>(null); const [pol, setPol] = useState<Policy>({ disabled: [], mandatory: [] });
  const [busy, setBusy] = useState(false); const [msg, setMsg] = useState<{ tone: 'info' | 'danger'; text: string } | null>(null);
  const load = () => api<NodesResp>(`/api/pipeline/nodes?project=${encodeURIComponent(projectId)}`).then(r => { setData(r); setProf({ ...r.profile, modules: r.profile.modules.length ? r.profile.modules : [{ path: '', stack: r.profile.stacks[0] ?? 'node' }] }); setPol({ disabled: r.policy?.disabled ?? [], mandatory: r.policy?.mandatory ?? [], defaultPreset: r.policy?.defaultPreset }); });
  useEffect(() => { load().catch(e => setMsg({ tone: 'danger', text: e.message })); }, [projectId]);
  if (!data || !prof) return <Card title="流程设置"><p className="text-sm text-ink-3">加载中…</p></Card>;
  const manual = prof.source === 'manual';
  const setMod = (i: number, m: Partial<ModuleStack>) => setProf({ ...prof, modules: prof.modules.map((x, j) => j === i ? { ...x, ...m } : x) });
  const optional = data.nodes.filter(n => n.available && n.optional);
  const save = async () => {
    setBusy(true); setMsg(null);
    try {
      await api(`/api/projects/${projectId}`, { method: 'PUT', body: { config: { stackProfile: manual ? { source: 'manual', stacks: [], modules: prof.modules.filter(m => m.stack) } : { source: 'auto', stacks: [], modules: [] }, nodePolicy: { disabled: pol.disabled, mandatory: pol.mandatory, defaultPreset: pol.defaultPreset || null } } } });
      await load(); setMsg({ tone: 'info', text: '已保存，新规划的任务按新设置组装流程；进行中的任务不受影响' });
    } catch (e: any) { setMsg({ tone: 'danger', text: e.message }); } finally { setBusy(false); }
  };
  return <Card title="流程设置" subtitle="决定这个项目的任务可以用哪些流程步骤；进行中的任务不受影响" data-testid="project-pipeline-settings"
    actions={canEdit ? <Button size="sm" variant="primary" loading={busy} onClick={save} data-testid="save-pipeline-settings">保存</Button> : <Badge tone="neutral">只读</Badge>}>
    <div className="grid gap-4 lg:grid-cols-2">
      <div className="space-y-2">
        <Field label="默认预设" hint="新任务组装流程的起点；不选则沿用业务线的流程模板">{fid => <Select id={fid} value={pol.defaultPreset ?? ''} disabled={!canEdit} onChange={e => setPol({ ...pol, defaultPreset: e.target.value || null })} data-testid="default-preset"><option value="">沿用业务线配置</option>{presetOptions(presets).map(o => <option key={o.value} value={o.value}>{o.label}</option>)}</Select>}</Field>
        <div className="label-caps text-caption text-ink-3">技术栈</div>
        <Toggle checked={manual} disabled={!canEdit} onChange={v => setProf({ ...prof, source: v ? 'manual' : 'auto' })} label="手动指定技术栈" hint={manual ? '按目录指定，每个目录一种技术栈' : `自动识别：${prof.stacks.map(s => STACK_LABEL[s] ?? s).join('、') || '尚未识别'}`} />
        {manual && <div className="space-y-1" data-testid="stack-modules">{prof.modules.map((m, i) => <div key={i} className="flex items-center gap-2">
          <Input value={m.path} disabled={!canEdit} onChange={e => setMod(i, { path: e.target.value })} placeholder="目录路径，留空表示整个仓库" aria-label="目录" />
          <Select value={m.stack} disabled={!canEdit} onChange={e => setMod(i, { stack: e.target.value })} aria-label="技术栈" className="w-36">{data.stacks.map(s => <option key={s} value={s}>{STACK_LABEL[s] ?? s}</option>)}</Select>
          {canEdit && <Button size="sm" variant="ghost" onClick={() => setProf({ ...prof, modules: prof.modules.filter((_, j) => j !== i) })}>移除</Button>}
        </div>)}{canEdit && <Button size="sm" icon="plus" onClick={() => setProf({ ...prof, modules: [...prof.modules, { path: '', stack: data.stacks[0] }] })}>添加目录</Button>}</div>}
      </div>
      <div className="space-y-2">
        <div className="label-caps text-caption text-ink-3">可选流程步骤</div>
        <p className="text-xs text-ink-3">关闭 = 本项目不使用（放松，只有所有者或管理员能改，并记入审计）；必选 = 每个任务都要经过（收紧）。</p>
        <ul className="space-y-1" data-testid="node-policy">{optional.map(n => { const off = pol.disabled.includes(n.id); const must = pol.mandatory.includes(n.id); return <li key={n.id} className={cx('flex flex-wrap items-center justify-between gap-2 border px-2 py-1 text-sm', off ? 'border-line opacity-60' : 'border-line-strong/50')}>
          <span title={n.description}>{n.label}{!n.allowed && !off && <span className="ml-1 text-caption text-ink-3">（{n.why}）</span>}</span>
          <span className="flex items-center gap-3"><Toggle checked={!off} disabled={!canEdit || must} onChange={v => setPol({ ...pol, disabled: toggleIn(pol.disabled, n.id, !v) })} label="启用" /><Toggle checked={must} disabled={!canEdit || off} onChange={v => setPol({ ...pol, mandatory: toggleIn(pol.mandatory, n.id, v) })} label="必选" /></span>
        </li>; })}</ul>
      </div>
    </div>
    {msg && <div className="mt-3"><Callout tone={msg.tone} onClose={() => setMsg(null)}>{msg.text}</Callout></div>}
  </Card>;
}
