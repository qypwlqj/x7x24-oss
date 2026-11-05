import { useEffect, useState } from 'react';
import { api } from '../api';
import { Badge, Button, Card, Callout } from '../ui';
import { refreshResource } from '../lib/resource';
import { Disclosure } from '@/components/ui/collapsible';
type Execution = { activeRevisionId: string | null; blockCode?: string; blockReason?: string; nodes: { plan_revision_id: string; id: string; definition_ref: string; state: string; body: string }[]; runs: { plan_revision_id: string; id: string; node_id: string; state: string; input_fingerprint: string; inputs: string; role_snapshot: string }[]; bindings: { plan_revision_id: string; id: string; ordinal: number; node_id: string; port_name: string; version_id: string | null; resolved_version_id: string | null; producer_node: string | null; output_port: string | null }[]; invalidations: { plan_revision_id: string; consumer_node: string; reason: string }[] };
export function WorkflowExecution({ taskId }: { taskId: string }) {
  const [data, setData] = useState<Execution>(); const [error, setError] = useState('');
  useEffect(() => { let active = true; const load = () => refreshResource(() => api<Execution>(`/api/tasks/${taskId}/execution`), d => { if (active) setData(d); }, e => { if (active) setError(e); }); load(); const timer = setInterval(load, 5000); return () => { active = false; clearInterval(timer); }; }, [taskId]);
  return <Card title="合同执行 · schema 3" subtitle="按稳定依赖顺序串行执行；产物生成、校验和人工批准分别记录。">
    {error && <Callout tone="danger">{error}。已有信息可能过期。</Callout>}
    {!data ? <p>正在加载合同…</p> : <div className="space-y-3 text-sm">
      {data.blockReason && <Callout tone="warn">{data.blockReason}（{data.blockCode}）</Callout>}
      <p>计划修订：<code>{data.activeRevisionId ?? '等待批准计划'}</code></p>
      <p className="text-ink-3">JSON 编码器只生成补丁。本地受控检查只证明声明的文件断言；项目测试与部署需独立证据。</p>
      {data.nodes.filter(node => node.plan_revision_id === data.activeRevisionId).map(node => <section key={node.id} className="border border-line p-3">
        <div className="flex justify-between"><b>{node.id} · {node.definition_ref}</b><Badge tone={node.state === 'SUCCEEDED' ? 'success' : node.state === 'FAILED' ? 'danger' : 'neutral'}>{node.state === 'SUCCEEDED' ? '产物已生成' : node.state}</Badge></div>
        <ul className="mt-2 text-xs">{data.bindings.filter(b => b.node_id === node.id && b.plan_revision_id === data.activeRevisionId).map(b => <li key={b.id}>{b.port_name}[{b.ordinal}] ← {b.resolved_version_id ?? b.version_id ?? `${b.producer_node}.${b.output_port}`} {!b.resolved_version_id && !b.version_id && '（等待生产者）'}</li>)}</ul>
        {data.invalidations.filter(i => i.consumer_node === node.id && i.plan_revision_id === data.activeRevisionId).map(i => <p key={i.reason} className="text-danger-ink">已失效：{i.reason}</p>)}
        {data.runs.filter(r => r.node_id === node.id && r.plan_revision_id === data.activeRevisionId).map(r => <Disclosure key={r.id} summary={`${r.state} · 执行 ${r.id}`}><pre className="max-h-64 overflow-auto text-xs">{JSON.stringify({ fingerprint: r.input_fingerprint, inputs: stored(r.inputs), roles: stored(r.role_snapshot) }, null, 2)}</pre></Disclosure>)}
      </section>)}
      <ExecutionEvidence key={taskId} taskId={taskId} />
    </div>}
  </Card>;
}

function stored(body: string) { try { return JSON.parse(body); } catch { return { error: '记录格式不可读取，请核对原始证据' }; } }
type Detail = { jobs: { id: string; state: string; event_seq: number; checkpoint_ref: string | null; result_digest: string | null }[]; contexts: { id: string; node_run_id: string; digest: string; body: string }[]; evaluations: { id: string; node_run_id: string; set_digest: string; body: string }[] };
function ExecutionEvidence({ taskId }: { taskId: string }) {
 const [tick, setTick] = useState(0); const [data, setData] = useState<Detail>(); const [error, setError] = useState(''); const [loading, setLoading] = useState(true);
 useEffect(() => { let active = true; setLoading(true); Promise.all([
  api<Detail['jobs']>(`/api/tasks/${taskId}/executor-jobs`), api<Detail['contexts']>(`/api/tasks/${taskId}/context-manifests`), api<Detail['evaluations']>(`/api/tasks/${taskId}/skill-evaluations`),
 ]).then(([jobs, contexts, evaluations]) => { if (active) { setData({ jobs, contexts, evaluations }); setError(''); } }).catch(e => { if (active) setError(e instanceof Error ? e.message : String(e)); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, [taskId, tick]);
 return <Disclosure summary="实际执行、技能适用性与上下文来源">
  <Button size="sm" loading={loading} onClick={() => setTick(n => n + 1)}>刷新执行证据</Button>
  {error && <Callout tone="danger">{error}。已有记录可能过期。</Callout>}
  {data && <div className="space-y-2 text-xs">
   <p>本地执行 {data.jobs.length} · 上下文清单 {data.contexts.length} · 技能评价 {data.evaluations.length}</p>
   {data.jobs.map(job => <p key={job.id}>{job.id} · {job.state} · 事件 {job.event_seq} · 检查点 {job.checkpoint_ref ?? '未记录'} · 结果摘要 {job.result_digest ?? '未确认'}</p>)}
   {data.contexts.map(row => <Disclosure key={row.id} summary={`上下文 ${row.node_run_id} · ${row.digest.slice(0, 12)}`}><pre className="max-h-64 overflow-auto">{JSON.stringify(stored(row.body), null, 2)}</pre></Disclosure>)}
   {data.evaluations.map(row => <Disclosure key={row.id} summary={`技能评价 ${row.node_run_id} · ${row.set_digest.slice(0, 12)}`}><pre className="max-h-64 overflow-auto">{JSON.stringify(stored(row.body), null, 2)}</pre></Disclosure>)}
  </div>}
 </Disclosure>;
}
