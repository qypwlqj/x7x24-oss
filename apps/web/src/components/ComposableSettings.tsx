import { useEffect, useState } from 'react';
import { api } from '../api';
import { Badge, Button, Callout, Card, Field, Input, Select, Textarea, Toggle, toast } from '../ui';

interface WsNode { id: string; version: number; status: 'draft' | 'approved' | 'rejected'; createdBy?: string; reviewedBy?: string; def: { label: string; description: string; executor: string; skills?: string[] } }
const STATUS: Record<string, { t: string; tone: 'warn' | 'success' | 'neutral' }> = { draft: { t: '待审批', tone: 'warn' }, approved: { t: '已批准', tone: 'success' }, rejected: { t: '已退回', tone: 'neutral' } };

/** 工作区「可组合流程」设置：功能开关 + 工作区派生节点（管理员提交，所有者批准） */
export function ComposableSettings({ manage, owner }: { manage: boolean; owner: boolean }) {
  const [on, setOn] = useState<boolean | null>(null); const [nodes, setNodes] = useState<WsNode[]>([]); const [err, setErr] = useState('');
  const [form, setForm] = useState({ id: '', label: '', description: '', executor: 'review' });
  const load = () => {
    Promise.all([
      api<{ pipelineV2: boolean }>('/api/pipeline/features'),
      api<{ nodes: WsNode[] }>('/api/pipeline/workspace-nodes'),
    ]).then(([features, catalogue]) => { setOn(features.pipelineV2); setNodes(catalogue.nodes); setErr(''); }).catch(e => setErr(e.message));
  };
  useEffect(load, []);
  const run = async (f: () => Promise<unknown>, ok: string) => { setErr(''); try { await f(); toast.success(ok); load(); } catch (e: any) { setErr(e.message); } };
  return <Card title="可组合流程" subtitle="开启后，新任务按需求和项目技术栈组装流程（每个流程步骤都标明来源和理由），逐步执行并核对过程记录；在途任务继续按原流程跑完。" data-testid="composable-settings">
    {err && <Callout tone="danger" onClose={() => setErr('')}>{err}</Callout>}
    <Toggle checked={!!on} disabled={!manage || on === null} label="对新任务启用可组合流程" hint={manage ? '只影响开启之后确认的需求' : '只有工作区管理员可以切换'}
      onChange={v => run(() => api('/api/pipeline/features', { method: 'PUT', body: { pipelineV2: v } }), v ? '已启用可组合流程' : '已关闭可组合流程')} />
    <div className="mt-3 space-y-1.5">
      <div className="text-sm font-semibold text-ink">工作区自定义流程步骤</div>
      <div className="text-caption text-ink-3">只能基于内置的「评审」或「产出设计」方式派生；提交后需要工作区所有者批准，AI 组装流程时才能使用。</div>
      {nodes.length === 0 && <div className="text-caption text-ink-3">还没有自定义流程步骤</div>}
      {nodes.map(n => <div key={`${n.id}@${n.version}`} className="flex flex-wrap items-center gap-2 border border-line bg-sunken px-2 py-1 text-sm" data-testid={`ws-node-${n.id}`}>
        <span className="font-semibold">{n.def.label}</span><span className="font-mono text-caption text-ink-3">{n.id} · 第 {n.version} 版</span>
        <Badge tone={STATUS[n.status].tone}>{STATUS[n.status].t}</Badge><Badge tone="info">{n.def.executor === 'review' ? '评审' : '产出设计'}</Badge>
        <span className="min-w-0 flex-1 truncate text-caption text-ink-2" title={n.def.description}>{n.def.description}</span>
        {owner && n.status === 'draft' && <span className="flex gap-1">
          <Button size="sm" onClick={() => run(() => api(`/api/pipeline/workspace-nodes/${n.id}/${n.version}/review`, { method: 'POST', body: { approve: true } }), '已批准')}>批准</Button>
          <Button size="sm" variant="ghost" onClick={() => run(() => api(`/api/pipeline/workspace-nodes/${n.id}/${n.version}/review`, { method: 'POST', body: { approve: false } }), '已退回')}>退回</Button>
        </span>}
      </div>)}
      {manage && <div className="grid gap-2 border border-dashed border-line p-2 md:grid-cols-[10rem_12rem_8rem_1fr_auto] md:items-end">
        <Field label="步骤标识">{id => <Input id={id} value={form.id} placeholder="大写字母、数字和下划线" onChange={e => setForm({ ...form, id: e.target.value.toUpperCase() })} />}</Field>
        <Field label="显示名称">{id => <Input id={id} value={form.label} placeholder="如 隐私合规评审" onChange={e => setForm({ ...form, label: e.target.value })} />}</Field>
        <Field label="执行方式">{id => <Select id={id} value={form.executor} onChange={e => setForm({ ...form, executor: e.target.value })}><option value="review">评审</option><option value="agent">产出设计</option></Select>}</Field>
        <Field label="职责说明">{id => <Textarea id={id} rows={1} value={form.description} placeholder="这一步要检查或产出什么" onChange={e => setForm({ ...form, description: e.target.value })} />}</Field>
        <Button disabled={!form.id || !form.label || !form.description} onClick={() => run(async () => { await api('/api/pipeline/workspace-nodes', { method: 'POST', body: form }); setForm({ id: '', label: '', description: '', executor: 'review' }); }, '已提交，等待所有者批准')}>提交</Button>
      </div>}
    </div>
  </Card>;
}
