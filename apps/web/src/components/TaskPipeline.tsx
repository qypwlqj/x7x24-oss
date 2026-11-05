import { useEffect, useState } from 'react';
import { api } from '../api';
import { Badge, Button, Callout, EmptyState, Field, Input, Select, cx } from '../ui';
import { TaskFlow } from './TaskFlow';
import { evidenceMatches, gateMatches, HANDLERS, STATE_LABEL, type NodeRec, type Route } from '../lib/taskFlow';
import { topNodes, type Graph } from '../lib/pipeline';
import { executionStateName, nodeOutcomeName } from '../catalog';
import { reviewerName } from '../lib/timeline';

interface PlanView { schema?: number; graph?: Graph; currentNode?: string | null; status: string; executionState?: string; paused?: boolean; pipelineVersion?: string; stacks?: string[]; records: Record<string, NodeRec>; evidence: Record<string, string[]>; nodeLoops?: Record<string, number>; routes?: Route[]; revisions: number; maxRevisions: number }
interface CatalogNode { id: string; label: string; family: string; module: string; check?: string | null; allowed: boolean; why: string }

/** 修订选项：插入一个流程步骤（设计 / 评审）或给测试加一项检查 */
export function reviseOptions(nodes: CatalogNode[], g: Graph | undefined): { value: string; label: string; adj: Record<string, unknown> }[] {
  const have = new Set((g?.nodes ?? []).map(n => n.id));
  const out: { value: string; label: string; adj: Record<string, unknown> }[] = [];
  for (const n of nodes) {
    if (!n.allowed || have.has(n.id) || n.module === 'sensing' || n.family === 'build') continue;
    if (n.family === 'check' && n.check) out.push({ value: n.id, label: `测试时加一项「${n.label}」`, adj: { op: 'addCheck', stage: 'QA', check: n.check } });
    else if (n.module === 'delivery' && n.id !== 'ACCEPTANCE' && n.id !== 'RELEASE') out.push({ value: n.id, label: `测试后加「${n.label}」`, adj: { op: 'insertStage', stage: n.id, after: 'QA', label: n.label } });
  }
  return out;
}

export function TaskPipelineTab({ id, focus, canRevise, evidence = [], verdicts = [], paused }: { id: string; focus?: string | null; canRevise: boolean; evidence?: any[]; verdicts?: any[]; paused?: boolean }) {
  const [sel, setSel] = useState<string | null>(focus ?? null);
  const [p, setP] = useState<PlanView | null>(null); const [err, setErr] = useState(''); const [nodes, setNodes] = useState<CatalogNode[]>([]);
  const [pick, setPick] = useState(''); const [reason, setReason] = useState(''); const [busy, setBusy] = useState(false); const [msg, setMsg] = useState<{ tone: 'info' | 'warn' | 'danger'; text: string } | null>(null);
  const load = () => api<PlanView>(`/api/tasks/${id}/plan`).then(x => { setP(x); setErr(''); }).catch(e => setErr(e.message));
  useEffect(() => { load(); api<{ nodes: CatalogNode[] }>('/api/pipeline/nodes').then(r => setNodes(r.nodes)).catch(e => setErr(e.message)); const t = setInterval(load, 10000); return () => clearInterval(t); }, [id]);
  if (err) return <EmptyState title="流程加载失败" hint={err} />;
  if (!p) return <p className="text-sm text-ink-3">加载中…</p>;
  if (!p.graph) return <EmptyState title="这个任务没有流程图" hint="可组合流程开启前创建的任务按原来的阶段执行，可在「节点执行」查看记录。" />;
  const active = focus || p.currentNode; const opts = reviseOptions(nodes, p.graph); const done = ['DELIVERED', 'RELEASED', 'DISCARDED'].includes(p.status) && p.executionState !== 'WAITING_CONFIGURATION';
  const revise = async () => {
    const o = opts.find(x => x.value === pick); if (!o) return; setBusy(true); setMsg(null);
    try { const r: any = await api(`/api/tasks/${id}/plan/revise`, { method: 'POST', body: { adjustments: [{ ...o.adj, reason: reason || '人工修订' }] } });
      if (!r.ok) setMsg({ tone: 'warn', text: r.message }); else { setMsg({ tone: (r.revisionWarnings ?? []).length ? 'warn' : 'info', text: ['已修订流程，从下一个步骤开始生效', ...(r.revisionWarnings ?? [])].join('；') }); setPick(''); setReason(''); }
      await load();
    } catch (e: any) { setMsg({ tone: 'danger', text: e.message }); } finally { setBusy(false); }
  };
  return <div className="space-y-4" data-testid="task-pipeline">
    <div className="flex flex-wrap items-center gap-2 text-xs text-ink-3"><span>当前步骤</span><Badge tone="primary" dot>{topNodes(p.graph).find(n => n.id === p.currentNode)?.label ?? p.currentNode ?? '—'}</Badge>{(p.stacks ?? []).length > 0 && <span>· 技术栈 {p.stacks!.join('、')}</span>}<span>· 已修订 {p.revisions}/{p.maxRevisions} 次</span></div>
    <TaskFlow graph={p.graph} flow={{ currentNode: p.currentNode, status: p.status, executionState: p.executionState, paused: paused || p.paused, records: p.records, nodeLoops: p.nodeLoops, routes: p.routes }} selected={sel ?? active} onSelect={setSel} detail={ln => {
      const r = p.records[ln.n.id]; const ev = evidence.filter(e => evidenceMatches(ln.n, e.stage)); const vs = verdicts.filter(v => gateMatches(ln.n.id, v.gate));
      return <div className="space-y-2 text-sm">
        <div className="flex flex-wrap items-center gap-2"><b className="text-ink">{ln.n.label}</b><Badge tone={ln.state === 'done' ? 'success' : ln.state === 'rejected' ? 'accent' : ln.state === 'blocked' ? 'warn' : ln.state === 'current' ? 'primary' : 'neutral'}>{STATE_LABEL[ln.state]}</Badge>{ln.loops > 0 && <Badge tone="accent">退回编码 {ln.loops} 次</Badge>}{ln.n.mandatory && <span className="text-xs text-ink-3">必选：{ln.n.mandatory}</span>}</div>
        {ln.n.reason && <p className="text-xs text-ink-3">{ln.n.reason}</p>}
        {r?.state === 'SKIPPED' && <p className="text-xs text-ink-2" data-testid="skip-reason">跳过原因：{r.reason ?? '任务规划判断这次不需要'}</p>}
        {(p.routes ?? []).filter(x => x.from === ln.n.id).length > 0 && <p className="text-xs text-ink-3" data-testid="node-routes">失败去向：{(p.routes ?? []).filter(x => x.from === ln.n.id).map(x => `${x.label} → ${p.graph?.nodes.find(n => n.id === x.to)?.label ?? HANDLERS[x.to] ?? x.to}${x.max ? `（最多 ${x.max} 轮）` : ''}${x.count ? `，已走过 ${x.count} 次` : ''}`).join('；')}</p>}
        <p className="text-xs text-ink-3">{r ? `${executionStateName(r.state)} · 结论：${nodeOutcomeName(r.outcome)}${r.valid === false ? '（历史结果，待复验）' : ''} · 执行 ${r.runs} 次 · ${r.tokens} Token · $${r.usd.toFixed(4)}` : '尚未执行'}{ln.checks.length ? ` · 附加检查：${ln.checks.map(c => c.label).join('、')}` : ''}</p>
        {!!r?.history?.length && <details className="text-xs text-ink-3"><summary>历史结果（{r.history.length}）</summary><ul className="mt-1 space-y-1">{r.history.map((h, i) => <li key={h.id ?? i}>第 {h.iteration ?? '—'} 轮 · {executionStateName(h.state)} · {nodeOutcomeName(h.outcome)}{h.headSha ? ` · ${h.headSha.slice(0, 7)}` : ''}</li>)}</ul></details>}
        {vs.length > 0 && <ul className="space-y-1" data-testid="task-flow-verdicts">{vs.map((v: any) => <li key={v.id} className="border border-line p-2"><div className="flex items-center justify-between gap-2"><span className="text-xs text-ink-2">评审 · 迭代 {v.iteration}</span><Badge tone={v.decision === 'pass' ? 'success' : v.decision === 'revise' ? 'warn' : 'danger'}>{v.decision === 'pass' ? '通过' : v.decision === 'revise' ? '需修改' : '不通过'}{v.aggregate != null ? ` · ${Number(v.aggregate).toFixed(2)}` : ''}</Badge></div>
          {Array.isArray(v.votes) && v.votes.map((x: any, i: number) => <div key={i} className="mt-1 text-xs text-ink-2"><p className="break-words text-ink-3 [overflow-wrap:anywhere]">{reviewerName(x.reviewer ?? '')} · {x.model ?? '模型未记录'}{x.score != null ? ` · 评分 ${Number(x.score).toFixed(2)}` : ''}{x.block && ' · 风险阻断'}</p><p className="line-clamp-4 whitespace-pre-wrap">{x.rationale}</p></div>)}</li>)}</ul>}
        {ev.length > 0 && <p className="text-xs text-ink-3">过程记录：{[...new Set(ev.map((e: any) => e.kind))].join("、")} · 共 {ev.length} 份（详见「过程记录」页签）</p>}
        {vs.length === 0 && ev.length === 0 && <p className="text-xs text-ink-3">这个步骤还没有过程记录或评审结果</p>}
      </div>;
    }} />
    {canRevise && !done && <div className="space-y-2 border border-line-strong/50 border-l-2 border-l-accent bg-raised/60 p-3" data-testid="task-revise">
      <div className="text-sm font-medium text-ink">中途修订流程</div>
      <p className="text-xs text-ink-3">只有工作区管理员可以修订，每个任务最多 {p.maxRevisions} 次；已经执行过的步骤不会重跑，治理必选的步骤不能去掉。</p>
      {opts.length === 0 ? <p className="text-xs text-ink-3">没有可以追加的步骤</p> : <div className="grid gap-2 md:grid-cols-[2fr_2fr_auto] md:items-end">
        <Field label="追加">{fid => <Select id={fid} value={pick} onChange={e => setPick(e.target.value)}><option value="">请选择</option>{opts.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}</Select>}</Field>
        <Field label="原因">{fid => <Input id={fid} value={reason} onChange={e => setReason(e.target.value)} placeholder="例如：涉及登录，补一次安全审计" />}</Field>
        <Button variant="primary" loading={busy} disabled={!pick || p.revisions >= p.maxRevisions} onClick={revise} data-testid="task-revise-submit">修订</Button>
      </div>}
      {msg && <Callout tone={msg.tone} onClose={() => setMsg(null)}>{msg.text}</Callout>}
    </div>}
  </div>;
}
