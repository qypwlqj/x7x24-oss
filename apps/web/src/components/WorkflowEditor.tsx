import { WorkflowPlanForm } from './WorkflowPlanForm';
import { Disclosure } from '@/components/ui/collapsible';
import { useEffect, useState } from 'react';
import { api } from '../api';
import { Badge, Button, Callout, Card, Field, Input, Select, Textarea } from '../ui';
import { approved, canSaveCandidate, inputTypes, parseDraft, type Candidate, type Compiled, type EditorState, type Definition } from '../lib/workflow';
const pretty = (x: unknown) => JSON.stringify(x, null, 2);
/** Every mutation uses the current server version; approval always names the exact saved digest. */
export function WorkflowEditor({ taskId, canManage }: { taskId: string; canManage: boolean }) {
 const [definitions, setDefinitions] = useState<Definition[]>([]);
 const [data, setData] = useState<EditorState>(); const [error, setError] = useState(''); const [busy, setBusy] = useState('');
 const [inputs, setInputs] = useState<Record<string, string>>({}); const [budget, setBudget] = useState('1'); const [repair, setRepair] = useState(false);
 const [kind, setKind] = useState('RequirementSpec'); const [content, setContent] = useState(''); const [baseSha, setBaseSha] = useState('');
 const [candidate, setCandidate] = useState<Candidate>(); const [draft, setDraft] = useState(''); const [original, setOriginal] = useState(''); const [planId, setPlanId] = useState('');
 const [humanNode, setHumanNode] = useState(''); const [humanOutput, setHumanOutput] = useState('');
 const reload = async () => { const d = await api<EditorState>(`/api/tasks/${taskId}/execution`); setData(d); return d; };
 useEffect(() => { let active = true; setData(undefined); setCandidate(undefined); setPlanId(''); setInputs({}); Promise.all([api<EditorState>(`/api/tasks/${taskId}/execution`), api<{ definitions: Definition[] }>('/api/node-definitions')]).then(([d, registry]) => { if (active) { setDefinitions(registry.definitions); setData(d); setError(''); } }).catch(e => { if (active) setError(e.message); }); return () => { active = false; }; }, [taskId]);
 const run = async (name: string, action: () => Promise<unknown>) => { setBusy(name); setError(''); try { await action(); await reload(); } catch (e) { setError(e instanceof Error ? e.message : String(e)); try { await reload(); } catch { /* preserve original failure and stale state */ } } finally { setBusy(''); } };
 const selectCandidate = (c: Candidate) => { setCandidate(c); const text = pretty(c.compiled.plan); setDraft(text); setOriginal(text); };
 const plan = data?.plans.find(p => p.id === planId); const compiled: Compiled | undefined = plan ? JSON.parse(plan.body) : undefined;
 const active = data?.plans.find(p => p.id === data.activeRevisionId); const activePlan: Compiled | undefined = active ? JSON.parse(active.body) : undefined;
 return <Card title="候选、批准与人工交接" subtitle="保存候选、编译、批准和开始是独立动作。所示预算是合同上限，不是精确报价。">
  {error && <Callout tone="danger">{error}。请核对刷新后的版本再操作。</Callout>}
  {!data ? <p>正在加载可编辑合同…</p> : <div className="space-y-5">
   <div className="flex items-center gap-3 text-xs"><span>任务版本 {data.taskVersion}</span><Button size="sm" onClick={() => run('refresh', reload)} loading={busy === 'refresh'}>刷新</Button></div>
   <section className="space-y-2"><h3 className="font-semibold">1. 导入已有材料</h3><p className="text-xs text-ink-3">来源记为用户提交；格式校验通过不代表业务验收或人工批准。</p>
    <Field label="产物类型">{id => <Select id={id} value={kind} onChange={e => setKind(e.target.value)}>{[...new Set(Object.values(inputTypes))].map(t => <option key={t}>{t}</option>)}</Select>}</Field>
    <Field label="材料 JSON">{id => <Textarea id={id} rows={4} value={content} onChange={e => setContent(e.target.value)} placeholder='例如：{"title":"需求标题","goal":"明确目标"}' />}</Field>
    <Field label="仓库固定 SHA（仓库快照必填）">{id => <Input id={id} value={baseSha} onChange={e => setBaseSha(e.target.value)} />}</Field>
    <Button disabled={!!busy || !content.trim()} onClick={() => run('import', () => api(`/api/tasks/${taskId}/artifacts`, { method: 'POST', body: { type: kind, content: JSON.parse(content), baseSha: baseSha || null } }))}>导入并校验格式</Button>
   </section>
   <section className="space-y-2"><h3 className="font-semibold">2. 选择明确输入并生成候选</h3><p className="text-xs text-ink-3">同类材料有多个版本时请明确选择。范围条件来自 scopeEvidence 的 features，不靠候选自报。</p>
    <div className="grid gap-2 sm:grid-cols-2">{Object.entries(inputTypes).map(([port, type]) => <Field key={port} label={`${port} · ${type}`}>{id => <Select id={id} value={inputs[port] ?? ''} onChange={e => setInputs(x => ({ ...x, [port]: e.target.value }))}><option value="">未绑定</option>{data.artifacts.filter(a => a.artifact_type === type).map(a => <option key={a.id} value={a.id}>{a.id} · {a.content_digest.slice(0, 10)}</option>)}</Select>}</Field>)}</div>
    <Field label="计划费用上限 USD">{id => <Input id={id} type="number" min="0" step="0.01" value={budget} onChange={e => setBudget(e.target.value)} />}</Field>
    <Field label="执行模式">{id => <Select id={id} value={repair ? 'repair' : 'implement'} onChange={e => setRepair(e.target.value === 'repair')}><option value="implement">首次实现</option><option value="repair">返修（必须绑定失败报告）</option></Select>}</Field>
    <Button disabled={!!busy} onClick={() => run('generate', async () => selectCandidate(await api<Candidate>(`/api/tasks/${taskId}/plan-candidates`, { method: 'POST', body: { expectedVersion: data.taskVersion, requestKey: crypto.randomUUID(), budgetUsd: Number(budget), repair, inputs: Object.fromEntries(Object.entries(inputs).filter(([, v]) => v)) } })))}>生成并编译候选</Button>
    <Field label="已有候选">{id => <Select id={id} value={candidate?.id ?? ''} onChange={e => { const row = data.candidates.find(c => c.id === e.target.value); if (row) selectCandidate(JSON.parse(row.body)); }}><option value="">选择候选</option>{data.candidates.map(c => <option key={c.id}>{c.id}</option>)}</Select>}</Field>
    {candidate && <div className="space-y-2"><Badge tone={candidate.compiled.diagnostics.length ? 'danger' : 'info'}>{candidate.compiled.diagnostics.length ? '编译阻断' : '合同可保存，尚未批准'}</Badge><p className="text-xs">来源 {candidate.provenance} · 摘要 {candidate.compiled.hash}</p>
     <ul className="text-xs">{candidate.compiled.diagnostics.map((d, i) => <li key={i} role="alert">{d.node}/{d.port} · {d.code}：{d.message}</li>)}</ul>
     {(() => { try { return <WorkflowPlanForm plan={parseDraft(draft)} definitions={definitions} artifacts={data.artifacts} onChange={p => setDraft(pretty(p))} />; } catch { return <p className="text-danger-ink">高级 JSON 尚未形成合法草稿，请修复后继续结构编辑。</p>; } })()}
     <Disclosure summary="高级：完整计划 JSON"><Field label="完整合同草稿">{id => <Textarea id={id} rows={12} value={draft} onChange={e => setDraft(e.target.value)} />}</Field></Disclosure>
     <Button disabled={!!busy} onClick={() => run('edit', async () => selectCandidate(await api<Candidate>(`/api/tasks/${taskId}/plan-candidates/${candidate.id}`, { method: 'PUT', body: { expectedVersion: data.taskVersion, requestKey: crypto.randomUUID(), plan: parseDraft(draft) } })))}>保存新候选版本并重新编译</Button>
     <Button disabled={!!busy || !canSaveCandidate(candidate, data.taskVersion, draft !== original)} onClick={() => run('save', async () => { const c = await api<Compiled>(`/api/tasks/${taskId}/plan-candidates/${candidate.id}/accept`, { method: 'POST', body: { expectedVersion: data.taskVersion, hash: candidate.compiled.hash } }); setPlanId(c.plan.revisionId); })}>保存为计划修订</Button>
     {candidate.taskVersion !== data.taskVersion && <p className="text-xs text-warn-ink">任务版本已变化；请刷新并保存新候选，旧候选不能覆盖当前计划。</p>}
    </div>}
   </section>
   <section className="space-y-2"><h3 className="font-semibold">3. 批准准确版本并开始</h3>
    <Field label="已保存的计划">{id => <Select id={id} value={planId} onChange={e => setPlanId(e.target.value)}><option value="">选择计划修订</option>{data.plans.map(p => <option key={p.id} value={p.id}>{p.id} · {p.status}</option>)}</Select>}</Field>
    {plan && compiled && <><p className="break-all text-xs">仓库 {compiled.plan.baseSha} · 预算 ${compiled.plan.budgetUsd} · 顺序 {compiled.order.join(' → ')} · 摘要 {plan.plan_hash}</p>
     <Button disabled={!!busy || !canManage || plan.status !== 'VALIDATED'} onClick={() => run('approve', () => api(`/api/tasks/${taskId}/workflow-approvals`, { method: 'POST', body: { revisionId: plan.id, subjectDigest: plan.plan_hash, expectedVersion: data.taskVersion } }))}>批准此计划</Button>
     <Button variant="primary" disabled={!!busy || plan.status !== 'VALIDATED' || !approved(data.approvals, plan.plan_hash)} onClick={() => run('activate', () => api(`/api/tasks/${taskId}/plan-revisions/${plan.id}/activate`, { method: 'POST', body: { hash: plan.plan_hash, expectedVersion: data.taskVersion } }))}>开始已批准的计划</Button>
     {!canManage && <p className="text-xs text-ink-3">批准需工作区管理员处理。</p>}
    </>}
    {canManage && data.approvals.filter(a => !a.revoked_at).map(a => <div key={a.id} className="flex gap-2 text-xs"><span>批准 {a.subject_digest.slice(0, 14)} · 截止 {a.expires_at}</span><Button size="sm" disabled={!!busy} onClick={() => run('revoke', () => api(`/api/tasks/${taskId}/workflow-approvals/${a.id}/revoke`, { method: 'POST', body: {} }))}>撤销</Button></div>)}
   </section>
   <section className="space-y-2"><h3 className="font-semibold">4. 人工产物与输入批准</h3>
    {canManage && data.artifacts.filter(a => a.artifact_type === 'DesignSpec').map(a => <div key={a.id} className="text-xs">设计 {a.id} <Button size="sm" disabled={!!busy || approved(data.approvals, a.approval_subject)} onClick={() => run('approve-input', () => api(`/api/tasks/${taskId}/workflow-approvals`, { method: 'POST', body: { versionId: a.id, subjectDigest: a.approval_subject, expectedVersion: data.taskVersion } }))}>批准此设计版本</Button></div>)}
    <Field label="当前计划中的人工节点">{id => <Select id={id} value={humanNode} onChange={e => setHumanNode(e.target.value)}><option value="">选择人工节点</option>{activePlan?.plan.nodes.filter(n => n.definitionRef.startsWith('document.')).map(n => <option key={n.id}>{n.id}</option>)}</Select>}</Field>
    <Field label="输出 JSON（端口名到产物内容）">{id => <Textarea id={id} rows={5} value={humanOutput} onChange={e => setHumanOutput(e.target.value)} placeholder='{"requirements":{"title":"目标","goal":"范围"}}' />}</Field>
    <Button disabled={!!busy || !humanNode || !humanOutput.trim()} onClick={() => run('human', () => api(`/api/tasks/${taskId}/nodes/${encodeURIComponent(humanNode)}/human-output`, { method: 'POST', body: { outputs: JSON.parse(humanOutput), expectedVersion: data.taskVersion } }))}>提交到当前固定计划</Button>
   </section>
  </div>}
 </Card>;
}
