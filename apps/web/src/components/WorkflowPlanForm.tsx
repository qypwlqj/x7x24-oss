import { useState } from 'react';
import { Button, Field, Input, Select } from '../ui';
import { bindPort, renameNode, type Artifact, type Definition, type Plan } from '../lib/workflow';
const sourceOf = (versionId: string | null, producerNode: string | null, outputPort: string | null) => JSON.stringify(versionId ? { versionId } : { producerNode, outputPort });
/** Form edits a draft only. The server compiler, approval digest and permissions remain authoritative. */
export function WorkflowPlanForm({ plan, definitions, artifacts, onChange }: { plan: Plan; definitions: Definition[]; artifacts: Artifact[]; onChange: (p: Plan) => void }) {
 const [definition, setDefinition] = useState('document.requirements@1'); const [from, setFrom] = useState(''); const [to, setTo] = useState('');
 const mutate = (id: string, fn: (n: Plan['nodes'][number]) => Plan['nodes'][number]) => onChange({ ...plan, nodes: plan.nodes.map(n => n.id === id ? fn(n) : n) });
 return <div className="space-y-3" data-testid="workflow-plan-form">
  <Field label="本修订预算上限 USD">{id => <Input id={id} type="number" min="0" step="0.01" value={plan.budgetUsd} onChange={e => onChange({ ...plan, budgetUsd: Number(e.target.value) })} />}</Field>
  <p className="text-xs text-ink-3">仓库基线 {plan.baseSha} · 条件 {Object.entries(plan.facts).map(([k, v]) => `${k}=${v}`).join('，')}。端口变化将由服务端重新编译。</p>
  {plan.nodes.map((node, nodeIndex) => { const contract = definitions.find(d => d.ref === node.definitionRef); return <div key={nodeIndex} className="space-y-2 border border-line p-3">
   <div className="grid gap-2 sm:grid-cols-2"><Field label="节点实例 ID">{id => <Input id={id} value={node.id} onChange={e => onChange(renameNode(plan, node.id, e.target.value))} />}</Field>
   <Field label="版本化节点合同">{id => <Select id={id} value={node.definitionRef} onChange={e => mutate(node.id, n => ({ ...n, definitionRef: e.target.value }))}>{definitions.map(d => <option key={d.ref} disabled={!d.available} value={d.ref}>{d.ref} · {d.executor}{!d.available && ' · 能力未就绪'}</option>)}</Select>}</Field></div>
   {contract?.inputs.map(port => { const existing = node.bindings.filter(b => b.port === port.name); const slots = existing.length ? existing.map(b => b.ordinal) : [0]; return <div key={port.name} className="space-y-1 border-l border-line pl-2">
    <p className="text-xs">{port.name} · {port.type} · {port.requirement}{port.condition && `（${port.condition}）`}{port.approvalRequired && ' · 需要当前版本批准'}</p>
    {slots.map(ordinal => { const binding = existing.find(b => b.ordinal === ordinal); const value = binding ? sourceOf(binding.versionId, binding.producerNode, binding.outputPort) : ''; return <Field key={ordinal} label={`${port.name}[${ordinal}] 来源`}>{id => <Select id={id} value={value} onChange={e => {
      let changed = { ...plan, nodes: plan.nodes.map(n => n.id === node.id ? bindPort(n, port.name, ordinal, e.target.value) : n) };
      if (e.target.value) { const source = JSON.parse(e.target.value); const artifact = artifacts.find(a => a.id === source.versionId); if (artifact) { const a = JSON.parse(artifact.body); if (port.name === 'repository') changed = { ...changed, baseSha: a.baseSha }; if (port.name === 'scopeEvidence') changed = { ...changed, facts: JSON.parse(a.content).features }; } }
      onChange(changed);
     }}><option value="">未绑定</option><optgroup label="固定产物版本">{artifacts.filter(a => a.artifact_type === port.type).map(a => <option key={a.id} value={JSON.stringify({ versionId: a.id })}>{a.id} · {a.content_digest.slice(0, 10)}</option>)}</optgroup><optgroup label="本修订生产者输出">{plan.nodes.filter(n => n.id !== node.id).flatMap(n => definitions.find(d => d.ref === n.definitionRef)?.outputs.filter(output => output.type === port.type).map(output => <option key={`${n.id}/${output.name}`} value={JSON.stringify({ producerNode: n.id, outputPort: output.name })}>{n.id}.{output.name}</option>) ?? [])}</optgroup></Select>}</Field>; })}
    {port.maxItems > 1 && <Button size="sm" disabled={existing.length >= port.maxItems} onClick={() => mutate(node.id, n => ({ ...n, bindings: [...n.bindings, { id: `${port.name}-${Math.max(-1, ...slots) + 1}`, port: port.name, ordinal: Math.max(-1, ...slots) + 1, versionId: null, producerNode: null, outputPort: null }] }))}>增加一个明确来源</Button>}
   </div>; })}
   <p className="text-xs text-ink-3">角色与技能由服务端固定。删除节点后，编译会指出受影响的必需输入和依赖。</p>
   <Button size="sm" variant="danger" onClick={() => onChange({ ...plan, nodes: plan.nodes.filter(n => n.id !== node.id) })}>删除此节点</Button>
  </div>; })}
  <div className="flex flex-wrap gap-2"><Select aria-label="新增节点合同" value={definition} onChange={e => setDefinition(e.target.value)}>{definitions.map(d => <option key={d.ref} disabled={!d.available}>{d.ref}</option>)}</Select><Button onClick={() => { const id = `node-${crypto.randomUUID().slice(0, 8)}`; onChange({ ...plan, nodes: [...plan.nodes, { id, logicalKey: id, definitionRef: definition, config: {}, bindings: [], roles: {} }] }); }}>增加节点</Button></div>
  <div className="flex flex-wrap items-center gap-2"><Select aria-label="控制依赖来源" value={from} onChange={e => setFrom(e.target.value)}><option value="">先完成</option>{plan.nodes.map(n => <option key={n.id}>{n.id}</option>)}</Select><Select aria-label="控制依赖消费者" value={to} onChange={e => setTo(e.target.value)}><option value="">后执行</option>{plan.nodes.map(n => <option key={n.id}>{n.id}</option>)}</Select><Button disabled={!from || !to || from === to} onClick={() => onChange({ ...plan, controls: [...plan.controls, { from, to }] })}>增加控制依赖</Button></div>
  {plan.controls.map((edge, index) => <p key={index} className="text-xs">{edge.from} → {edge.to} <Button size="sm" onClick={() => onChange({ ...plan, controls: plan.controls.filter((_, i) => i !== index) })}>移除此依赖</Button></p>)}
 </div>;
}
