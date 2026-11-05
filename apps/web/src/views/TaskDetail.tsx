import { AppLink } from '@/components/AppLink';
import { WorkflowEditor } from '../components/WorkflowEditor';
import { WorkflowExecution } from '../components/WorkflowExecution';
import { lazy, Suspense, useEffect, useState, type ReactNode } from 'react';
import { api, fmtTime, type TaskDTO } from '../api';
import { executionStateName, nodeOutcomeName } from '../catalog';
import { EDGE, EVT, RISK_LABEL, RISK_TONE, statusName, statusTone } from '../labels';
import { Badge, Button, Card, EmptyState, Field, Input, KV, Modal, PageSkeleton, Progress, Tabs, Textarea , useConfirm } from '../ui';
import { LinkChips } from './Tasks';
import { describeEvent } from '../lib/eventText';
import { canPauseTask } from '../lib/nav';
import { resumeDescription } from '../lib/taskActions';
import { Disclosure } from '@/components/ui/collapsible';
import { failureText } from '../lib/eventText';
import { TaskPipelineTab } from '../components/TaskPipeline';
import { CodeReviewCard } from '../components/CodeReviewCard';
import { MergeGateCard } from '../components/MergeGateCard';
import { TaskBudgetCard } from '../components/TaskBudgetCard';
import { CodeBlock } from '../components/CodeBlock';
import { DeliverySummary } from '../components/DeliverySummary';

const MarkdownContent = lazy(() => import('../components/MarkdownContent').then(module => ({ default: module.MarkdownContent })));

type Tab = 'stages' | 'timeline' | 'evidence' | 'verdicts' | 'info';
export default function TaskDetail({ id, onBack, initialTab, focus, canRevise = true, canManageBudget = false }: { id: string; onBack: () => void; initialTab?: string; focus?: string | null; canRevise?: boolean; canManageBudget: boolean }) {
  const confirm = useConfirm();
  useEffect(() => { if (['stages', 'timeline', 'evidence', 'verdicts', 'info'].includes(initialTab ?? '')) setTab(initialTab as Tab); }, [id, initialTab]);
  const [d, setD] = useState<any>(null); const [tab, setTab] = useState<Tab>(['stages', 'timeline', 'evidence', 'verdicts', 'info'].includes(initialTab ?? '') ? initialTab as Tab : 'timeline'); const [err, setErr] = useState('');
  useEffect(() => { let active = true; setD(null); setErr(''); const load = () => api(`/api/tasks/${id}`).then(detail => { if (active) { setD(detail); setErr(''); } }).catch(e => { if (active) setErr(e.message); }); load(); const timer = setInterval(load, 5000); return () => { active = false; clearInterval(timer); }; }, [id]);
  if (err) return <EmptyState title="加载失败" hint={err} />;
  if (!d) return <PageSkeleton />;
  const t: TaskDTO = d.task;
  const pause = async () => { try { await api(`/api/tasks/${id}/pause`, { method: 'PUT', body: { paused: !t.paused } }); setD(await api(`/api/tasks/${id}`)); } catch (e: any) { setErr(e.message); } };
  const resume = async () => { if (!await confirm({ title: '恢复执行', description: resumeDescription(t.recovery), confirmText: '恢复执行' })) return; try { await api(`/api/tasks/${id}/resume`, { method: 'POST', body: { expectedVersion: t.version } }); setD(await api(`/api/tasks/${id}`)); } catch (e: any) { setErr(e.message); } };
  const verdictTone = (x: string) => (x === 'pass' ? 'success' : x === 'revise' ? 'warn' : 'danger');
  return (
    <div className="space-y-gap" data-testid="task-detail">
      <Button variant="ghost" size="sm" icon="back" onClick={onBack}>返回任务列表</Button>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0"><h1 className="hud-title glow-text text-xl text-ink">{t.title}</h1>
          <p className="mt-1 text-sm text-ink-3"><AppLink href={`/catalog/${t.productLine}`} className="hover:text-primary-ink">{d.line}</AppLink> / <AppLink href={`/project/${t.projectId}`} className="hover:text-primary-ink">{t.projectName}</AppLink> · 迭代 {t.currentIteration} · 优先级 {t.priorityScore} · <span className="font-mono text-xs">{t.id}</span></p></div>
        <div className="flex gap-2"><Badge tone={statusTone(t.currentStage)} dot>{statusName(t.currentStage)}</Badge><Badge tone={t.executionState === 'FAILED' ? 'danger' : 'neutral'}>{executionStateName(t.executionState)}</Badge>{(t.paused || canPauseTask(t)) && <Button size="sm" onClick={pause}>{t.paused ? '恢复任务' : '暂停任务'}</Button>}{t.recovery?.canResume && t.status !== 'DISCARDED' && <Button size="sm" variant="primary" data-testid="task-resume" onClick={resume}>恢复执行</Button>}<Badge tone={RISK_TONE[t.riskLevel]}>{RISK_LABEL[t.riskLevel]}</Badge>{t.discardReason && <Badge tone="danger">废弃：{DISCARD_REASON[t.discardReason] ?? t.discardReason}</Badge>}</div>
      </div>
      {t.recovery?.canResume && t.status !== 'DISCARDED' && <Card><p className="text-sm" data-testid="recovery-progress">任务停在「{statusName(t.currentStage)}」{t.recovery.checkpoint.phase === 'DEV' && t.recovery.checkpoint.segmentIndex != null ? `（第 ${t.recovery.checkpoint.segmentIndex + 1} 段）` : ''}。{resumeDescription(t.recovery)}</p></Card>}
      {d.config && <div className="flex flex-wrap items-center gap-2 text-sm" data-testid="task-config"><span className="label-caps text-caption text-ink-3">流程配置</span><code className="text-xs">{d.config.template}</code><span className="text-xs text-ink-3">快照 {d.config.pinned}</span>
        {Object.keys(d.config.modelPins ?? {}).length > 0 && <Badge tone="info">已切换模型 {Object.values(d.config.modelPins).join(', ')}</Badge>}
        {d.config.upgradable && <><Badge tone="warn">业务线配置已更新</Badge><Button size="sm" onClick={async () => { if (!await confirm({ title: '升级到最新配置', description: '把这个任务升级到业务线最新的流程 / 角色 / 模型配置？从下一个节点开始生效。', confirmText: '升级' })) return; try { await api(`/api/tasks/${id}/config-upgrade`, { method: 'POST', body: {} }); setD(await api(`/api/tasks/${id}`)); } catch (e: any) { setErr(e.message); } }}>升级到最新配置</Button></>}
      </div>}
      <div className="flex flex-wrap items-center gap-2 text-sm" data-testid="task-links"><span className="label-caps text-caption text-ink-3">GitHub</span><LinkChips links={(t as any).links} />{d.repo && <span className="font-mono text-xs text-ink-3">{d.repo}</span>}</div>
      {['DELIVERED', 'RELEASED', 'DEV', 'QA', 'ACCEPTANCE'].includes(t.status) && <MergeGateCard taskId={id} status={t.status} onChanged={async () => setD(await api(`/api/tasks/${id}`))} />}
      <DeliverySummary task={t} />
      {t.status === 'DISCARDED' && <DiscardCard task={t} detail={d.discardDetail} timeline={d.timeline} verdicts={d.verdicts} actions={<DiscardedActions task={t} onChanged={async () => setD(await api(`/api/tasks/${id}`))} onDeleted={onBack} />} />}
      {(d.config?.schema === 2 || t.pipelineVersion === 'v2') && <Card title="任务流程" subtitle="这个任务钉住的组装流程 · 点击步骤查看过程记录与评审结果" data-testid="task-flow-card"><TaskPipelineTab id={id} focus={focus} canRevise={canRevise} evidence={d.evidence} verdicts={d.verdicts} paused={!!t.paused} /></Card>}
      {t.pipelineVersion === 'v3' && <><WorkflowEditor taskId={id} canManage={canRevise} /><WorkflowExecution taskId={id} /></>}
      <CodeReviewCard evidence={d.evidence} />
      <div className="grid gap-gap lg:grid-cols-3">
        <Card className="min-w-0 lg:col-span-2" flush>
          <div className="overflow-x-auto px-card pt-2"><Tabs className="min-w-max whitespace-nowrap" label="任务信息" value={tab} onChange={setTab} items={[{ id: 'stages', label: '节点执行', count: d.stageExecutions?.length ?? 0 }, { id: 'timeline', label: '时间线', count: d.timeline.length }, { id: 'evidence', label: '过程记录', count: d.evidence.length }, { id: 'verdicts', label: '评审结果', count: d.verdicts.length }, { id: 'info', label: '概览' }]} /></div>
          <div className="p-card" role="tabpanel" aria-labelledby={`tab-${tab}`}>
            {tab === 'stages' && (d.stageExecutions?.length ? <ol className="space-y-3">{d.stageExecutions.map((run: any) => <li key={run.id} className="min-w-0 border border-line p-3 text-sm"><div className="flex flex-wrap justify-between gap-2"><b>{statusName(run.stage)} · 迭代 {run.iteration_seq} · 执行 {run.attempt}</b><Badge tone={run.state === 'FAILED' ? 'danger' : run.state === 'SUCCEEDED' ? 'success' : 'neutral'}>{executionStateName(run.state)}</Badge><Badge tone={run.outcome === 'FAILED' || run.outcome === 'ERROR' ? 'danger' : run.outcome === 'PASSED' ? 'success' : 'neutral'}>结论：{nodeOutcomeName(run.outcome ?? run.output?.outcome)}</Badge></div><p className="mt-1 text-xs text-ink-3">{fmtTime(run.started_at)} → {fmtTime(run.ended_at)} · {run.tokens} Token · ${Number(run.usd).toFixed(4)}</p>{run.error && <p className="mt-2 whitespace-pre-wrap text-danger-ink">{run.error}</p>}<Disclosure className="mt-3" summaryClassName="cursor-pointer text-xs text-ink-2" summary={<>输入、输出与关联记录</>}><div className="mt-2"><CodeBlock title="输入、输出与关联记录" language="json" code={JSON.stringify({ input: run.input, output: run.output, evidenceIds: run.evidenceIds }, null, 2)} /></div></Disclosure></li>)}</ol> : <EmptyState title="暂无节点执行记录" hint="升级前的历史仍可在时间线和证据中查看。" />)}
            {tab === 'timeline'  && <ol className="space-y-2 border-l border-line pl-4">
              {d.timeline.filter((e: any) => e.event_type !== 'EVIDENCE_ADDED').map((e: any) => (
                <TimelineItem key={e.id} e={e} onResume={resume} />))}
            </ol>}
            {tab === 'evidence' && (d.evidence.length ? <ul className="space-y-2">{d.evidence.map((e: any) => (
              <li key={e.id} className="min-w-0 border border-line-strong/50 border-l-2 border-l-primary bg-raised/70 p-3 text-sm"><div className="flex flex-wrap items-center justify-between gap-2"><span className="min-w-0 break-words font-medium">{e.kind} <span className="text-xs font-normal text-ink-3">· {statusName(e.stage)} · 迭代 {e.iteration}</span></span><Badge tone={e.verified ? 'success' : 'danger'}>{e.verified ? '哈希校验通过' : '被篡改'}</Badge></div>
                <div className="mt-1 font-mono text-caption text-ink-3">sha256:{e.sha256.slice(0, 24)}… · {e.producedBy ?? '-'}</div>
                <Disclosure className="mt-3" summaryClassName="cursor-pointer text-xs text-ink-2" summary={<>查看内容</>}><div className="mt-2"><CodeBlock title="过程记录" language={typeof e.content === 'string' ? 'text' : 'json'} code={typeof e.content === 'string' ? e.content : JSON.stringify(e.content, null, 2) ?? ''} /></div></Disclosure></li>))}</ul> : <EmptyState title="暂无过程记录" hint="任务运行到相应阶段后会自动沉淀。" />)}
            {tab === 'verdicts' && (d.verdicts.length ? <ul className="space-y-2">{d.verdicts.map((v: any) => (
              <li key={v.id} className="border border-line-strong/50 border-l-2 border-l-primary bg-raised/70 p-3 text-sm">
                <div className="flex items-center justify-between"><span className="font-medium">{v.gate} <span className="text-xs font-normal text-ink-3">· 迭代 {v.iteration}</span></span><Badge tone={verdictTone(v.decision)}>{v.decision}{v.aggregate != null ? ` · ${Number(v.aggregate).toFixed(2)}` : ''}</Badge></div>
                {Array.isArray(v.ruleResults) && <div className="mt-2 flex flex-wrap gap-1">{v.ruleResults.map((r: any) => <Badge key={r.name} tone={r.ok ? 'success' : 'danger'}>{r.name}</Badge>)}</div>}
                {Array.isArray(v.votes) && v.votes.map((x: any, i: number) => <p key={i} className="mt-1 text-xs text-ink-2"><b className="text-ink">{x.reviewer}</b> <span className="text-ink-3">({x.model})</span> {Number(x.score).toFixed(2)}{x.block ? ' · 阻断' : ''} — {x.rationale}</p>)}
                {v.meta?.reason && <p className="mt-1 text-xs text-ink-3">{v.meta.reason}{v.meta.arbitrated ? ' · 已仲裁' : ''}</p>}
              </li>))}</ul> : <EmptyState title="暂无评审结果" hint="评审或验收完成后会出现在这里。" />)}
            {tab === 'info' && <div className="min-w-0 space-y-4"><Suspense fallback={<p className="text-sm text-ink-3">正在排版…</p>}><div>
              <section className="task-overview-section" aria-label="需求说明"><h2 className="task-overview-heading">需求说明</h2><MarkdownContent text={t.problem || '暂无需求说明'} /></section>
              {t.acceptanceCriteria && <section className="task-overview-section" aria-label="验收标准"><h2 className="task-overview-heading">验收标准</h2><MarkdownContent text={t.acceptanceCriteria} /></section>}
              </div></Suspense><p className="text-xs text-ink-3">项目配置版本 {t.projectConfigVersion} · 流水线 {t.pipelineVersion}</p>
              {d.discardDetail && <CodeBlock title="废弃详情" language="json" code={JSON.stringify(d.discardDetail, null, 2)} />}
              {d.anomalies.map((a: any) => <div key={a.id} className="border border-line-strong/50 border-l-2 border-l-primary bg-raised/70 p-2 text-sm">异常 {a.type} · {a.status} · {a.resolution ?? '-'} · 步骤 {a.heal_steps.map((s: any) => s.action + (s.success ? '✓' : '✗')).join(' → ')}</div>)}
              <p className="text-xs text-ink-3">事件重放状态：{d.replayStatus}（应等于当前状态 {t.status}）</p>
            </div>}
          </div>
        </Card>
        <div className="space-y-gap">
          <TaskBudgetCard t={t} canManageBudget={canManageBudget} onChanged={async () => setD(await api(`/api/tasks/${id}`))} />
          <Card title="返修与自动处理"><dl>
            {([['测试返修', t.loopCount], ['验收退回', t.rejectCount], ['撤回上线', t.rollbackCount], ['省钱模式（预算触发）', t.degradeCount], ['自动修复', t.healCount], ['重新入池', t.requeueCount], ['换过的方案', t.strategyVariant], ['缩小范围', t.scopeLevel]] as const).map(([k, v]) => <KV key={k} k={k} v={v} />)}
          </dl></Card>
        </div>
      </div>
    </div>
  );
}

const TONE_DOT: Record<string, string> = { warn: 'bg-warn', danger: 'bg-danger', success: 'bg-success', info: 'bg-primary', neutral: 'bg-ink-3' };
function TimelineItem({ e, onResume }: { e: any; onResume: () => void }) {
  const x = describeEvent(e); const label = EVT[e.event_type] ?? e.event_type;
  const discard = e.event_type === 'STATUS_CHANGED' && e.to_status === 'DISCARDED' && e.payload;
  return <li className="relative text-sm" data-testid="timeline-item"><i className={`absolute -left-[21px] top-2 h-2 w-2 shadow-glow-cyan ${TONE_DOT[x.tone] ?? 'bg-primary'}`} />
    <div className="flex flex-wrap items-baseline gap-x-2"><span className="tabular text-xs text-ink-3">{fmtTime(e.occurred_at)}</span>
      <Badge tone={x.tone === 'neutral' ? 'neutral' : x.tone === 'info' ? 'primary' : x.tone}>{label}</Badge>
      {x.title && <b className="text-ink">{x.title}</b>}</div>
    {x.text && <p className="mt-0.5 whitespace-pre-wrap text-ink-2" data-testid="timeline-text">{x.text}</p>}
    {discard && <div className="mt-1 border border-danger/50 bg-danger-soft/40 p-2 text-xs text-ink-2" data-testid="discard-event"><b className="text-danger-ink">{DISCARD_REASON[e.payload.reason] ?? e.payload.reason}</b>{discardLines(e.payload.detail).map((l, i) => <p key={i} className="mt-0.5 whitespace-pre-wrap">{l}</p>)}</div>}
    {x.actions && x.actions.length > 0 && <div className="mt-1 flex flex-wrap gap-2">{x.actions.map(a => a.kind === 'link' ? <AppLink key={a.label} href={a.href} className="text-xs text-primary-ink underline-offset-2 hover:underline">{a.label} →</AppLink> : <Button key={a.label} size="sm" variant="ghost" onClick={onResume}>{a.label}</Button>)}</div>}
    {e.payload && <Disclosure className="mt-1" summaryClassName="cursor-pointer text-caption text-ink-3" summary={<>详情 <span className="font-mono">#{e.hash.slice(0, 6)}</span></>}><pre className="mt-1 max-h-48 overflow-auto border border-line bg-sunken p-2 font-mono text-caption text-ink-2">{JSON.stringify(e.payload, null, 1)}</pre></Disclosure>}
  </li>;
}

export const DISCARD_REASON: Record<string, string> = {
  exceeds_budget: '在改动上限内无法完成',
  low_value: '价值不足（评审未通过）', needs_clarification: '需求待澄清（已退回对话）', infeasible: '不可行', forbidden_scope: '命中禁区 / 超出自治等级', duplicate: '与已有任务重复', policy_withdrawn: '长期低优先级，规则撤销',
  budget_exhausted: '预算耗尽', loop_exhausted: '返修次数用尽', rollback_exhausted: '回滚次数用尽',
};
const GATE: Record<string, string> = { prd_review: 'PRD 评审', acceptance: '验收委员会', qa: 'QA' };
/** 把废弃详情转成可读文字（兼容旧数据：只有 rationales 字符串数组） */
export function discardLines(detail: any): string[] {
  if (!detail || typeof detail !== 'object') return detail ? [String(detail)] : [];
  const out: string[] = [];
  if (detail.gate) out.push(`关卡：${GATE[detail.gate] ?? detail.gate}`);
  if (detail.aggregate != null) out.push(`委员会加权分 ${Number(detail.aggregate).toFixed(3)}${detail.threshold != null ? `，低于「打回修改」分数线 ${detail.threshold}` : ''}${detail.passScore != null ? `（通过分 ${detail.passScore}）` : ''}`);
  if (typeof detail.reason === 'string' && detail.reason) out.push(`说明：${detail.reason}`);
  if (Array.isArray(detail.votes)) detail.votes.forEach((v: any) => out.push(`${v.reviewer}（${v.model}）${Number(v.score).toFixed(2)}：${v.rationale}`));
  else if (Array.isArray(detail.rationales)) detail.rationales.forEach((r: string) => out.push(r));
  if (Array.isArray(detail.failures) && detail.failures.length) out.push(`经过：${detail.failures.map(failureText).join('；')}`);
  for (const k of ['violations', 'files', 'categories', 'reasons'] as const) if (Array.isArray(detail[k])) out.push(`${k}：${detail[k].map((x: any) => typeof x === 'string' ? x : JSON.stringify(x)).join('；')}`);
  if (detail.detail && typeof detail.detail === 'string') out.push(detail.detail);
  if (Array.isArray(detail.failedRules) && detail.failedRules.length) out.push(`未通过的硬规则：${detail.failedRules.join('、')}（委员会未开评）`);
  if (typeof detail.notes === 'string' && detail.notes) out.push(`最后一次评审意见：${detail.notes}`);
  if (Array.isArray(detail.questions) && detail.questions.length) out.push(`需要补充：${detail.questions.map((q: string, i: number) => `${i + 1}. ${q}`).join('  ')}`);
  if (typeof detail.guidance === 'string') out.push(detail.guidance);
  if (detail.after) out.push(detail.after);
  if (detail.similarity != null) out.push(`相似度 ${detail.similarity}${detail.threshold != null ? `（判为重复的标准 ${detail.threshold}）` : ''}`);
  if (detail.duplicateOf?.id) out.push(`重复于：${detail.duplicateOf.title ?? ''}（${detail.duplicateOf.id}）`);
  out.push(...riskLines(detail.risk));
  return out;
}

const RISK_BASIS: Record<string, string> = { paths: '按目标路径（项目的路径规则）', keywords: '按关键词' };
/** 风险分级依据：命中的路径 / 关键词 / 被否定忽略的关键词 */
export function riskLines(r: any): string[] {
  if (!r || typeof r !== 'object') return [];
  const out = [`风险 ${r.level}（${RISK_BASIS[r.basis] ?? r.basis ?? '-'}）`];
  if (Array.isArray(r.matchedPaths) && r.matchedPaths.length) out.push(`命中路径：${r.matchedPaths.join('；')}`);
  if (Array.isArray(r.matchedKeywords) && r.matchedKeywords.length) out.push(`命中关键词：${r.matchedKeywords.join('、')}`);
  if (Array.isArray(r.negatedKeywords) && r.negatedKeywords.length) out.push(`已识别否定、未计入：${r.negatedKeywords.join('、')}`);
  return out;
}

/** 已废弃任务的操作：编辑 / 编辑后重启（新一轮迭代回到需求池）/ 删除（软删除，审计留痕） */
export function DiscardedActions({ task, onChanged, onDeleted, compact }: { task: TaskDTO; onChanged: () => void; onDeleted?: () => void; compact?: boolean }) {
  const [open, setOpen] = useState(false); const [busy, setBusy] = useState(''); const [err, setErr] = useState('');
  const [f, setF] = useState({ title: task.title, problem: (task as any).problem ?? '', acceptanceCriteria: (task as any).acceptanceCriteria ?? '' });
  const call = async (kind: string, fn: () => Promise<any>, after?: () => void) => { setBusy(kind); setErr(''); try { await fn(); setOpen(false); (after ?? onChanged)(); } catch (e: any) { setErr(e.message); } finally { setBusy(''); } };
  const save = (restart: boolean) => call(restart ? 'restart' : 'save', () => api(restart ? `/api/tasks/${task.id}/restart` : `/api/tasks/${task.id}`, { method: restart ? 'POST' : 'PATCH', body: f }));
  const restore = () => call('restore', () => api(`/api/tasks/${task.id}/restore`, { method: 'POST', body: { expectedVersion: task.version } }));
  const restart = async () => { if (await confirm({ title: '从头重新开始？', description: '重新进入需求池并执行完整流程。已有执行历史保留。要接着原进度继续，请选择「恢复原进度」。', confirmText: '从头重新开始' })) call('restart', () => api(`/api/tasks/${task.id}/restart`, { method: 'POST', body: {} })); };
  const confirm = useConfirm();
  const del = async () => { if (await confirm({ title: `删除任务「${task.title}」？`, description: '软删除：从列表 / 看板 / 查重中隐藏，审计与证据保留。', confirmText: '删除', destructive: true })) call('delete', () => api(`/api/tasks/${task.id}`, { method: 'DELETE' }), onDeleted ?? onChanged); };
  return <span className="inline-flex flex-wrap items-center gap-2" data-testid="discarded-actions" onClick={(e) => e.stopPropagation()}>
    <Button size="sm" variant="primary" loading={busy === 'restore'} disabled={!!busy} onClick={restore} data-testid="task-restore">恢复原进度</Button>
    {!compact && <Button size="sm" onClick={() => setOpen(true)} data-testid="task-edit">编辑需求</Button>}
    <Button size="sm" variant={compact ? 'ghost' : 'primary'} loading={busy === 'restart' && !open} onClick={restart} data-testid="task-restart">从头重新开始</Button>
    <Button size="sm" variant="ghost" loading={busy === 'delete'} onClick={del} data-testid="task-delete">删除</Button>
    {err && !open && <span className="text-xs text-danger-ink">{err}</span>}
    {open && <Modal title="编辑已废弃任务" onClose={() => setOpen(false)} width={640} footer={<div className="flex justify-end gap-2"><Button variant="ghost" onClick={() => setOpen(false)}>取消</Button><Button loading={busy === 'save'} onClick={() => save(false)}>仅保存</Button><Button variant="primary" loading={busy === 'restart'} onClick={() => save(true)} data-testid="task-save-restart">保存并从头开始</Button></div>}>
      <div className="space-y-3">
        <Field label="标题">{(fid) => <Input id={fid} value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} />}</Field>
        <Field label="问题描述">{(fid) => <Textarea id={fid} rows={6} value={f.problem} onChange={(e) => setF({ ...f, problem: e.target.value })} />}</Field>
        <Field label="验收标准" hint="每行一条；越具体越容易通过 PRD 评审">{(fid) => <Textarea id={fid} rows={5} value={f.acceptanceCriteria} onChange={(e) => setF({ ...f, acceptanceCriteria: e.target.value })} />}</Field>
        <p className="text-xs text-ink-3">重启会开启新一轮迭代、回到需求池重新走 PRD 评审（评审重试次数清零），并写入审计事件。</p>
        {err && <p className="text-sm text-danger-ink">{err}</p>}
      </div>
    </Modal>}
  </span>;
}

function DiscardCard({ task, detail, timeline, verdicts, actions }: { task: TaskDTO; detail: any; timeline: any[]; verdicts: any[]; actions?: ReactNode }) {
  const ev = [...timeline].reverse().find((e: any) => e.event_type === 'STATUS_CHANGED' && e.to_status === 'DISCARDED');
  const dd = detail ?? ev?.payload?.detail;
  const lastVerdict = [...verdicts].reverse().find((v: any) => v.decision === 'reject' || v.decision === 'discard');
  const votes: any[] = Array.isArray(dd?.votes) ? dd.votes : Array.isArray(lastVerdict?.votes) ? lastVerdict.votes : [];
  const reason = task.discardReason ?? ev?.payload?.reason;
  return <section className="cut border border-danger bg-danger-soft/40 p-card shadow-[0_0_18px_rgb(var(--c-danger)/.25)]" data-testid="discard-card" aria-label="废弃原因">
    <div className="flex flex-wrap items-center justify-between gap-2"><h2 className="hud-title text-base text-danger-ink">已废弃 · {DISCARD_REASON[reason ?? ''] ?? reason ?? '原因未记录'}</h2><span className="flex flex-wrap items-center gap-2">{ev && <span className="tabular text-xs text-ink-3">{fmtTime(ev.occurred_at)}</span>}{actions}</span></div>
    {dd?.duplicateOf?.id && <p className="mt-2 text-sm text-ink-2" data-testid="duplicate-of">重复于：<AppLink className="text-primary-ink underline" href={`/task/${dd.duplicateOf.id}`}>{dd.duplicateOf.title || dd.duplicateOf.id}</AppLink>{dd.similarity != null && <span className="text-ink-3">（相似度 {dd.similarity}）</span>}</p>}
    {dd?.risk && <ul className="mt-2 space-y-0.5 text-xs text-ink-2" data-testid="risk-basis">{riskLines(dd.risk).map((x, i) => <li key={i}>{x}</li>)}</ul>}
    <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-3">
      <div><dt className="label-caps text-caption text-ink-3">关卡</dt><dd className="text-ink">{GATE[dd?.gate ?? lastVerdict?.gate] ?? dd?.gate ?? lastVerdict?.gate ?? '-'}</dd></div>
      <div><dt className="label-caps text-caption text-ink-3">委员会得分</dt><dd className="tabular text-ink">{dd?.aggregate != null ? Number(dd.aggregate).toFixed(3) : lastVerdict?.aggregate != null ? Number(lastVerdict.aggregate).toFixed(3) : '-'}</dd></div>
      <div><dt className="label-caps text-caption text-ink-3">分数线（打回修改 / 通过）</dt><dd className="tabular text-ink">{dd?.threshold ?? '未记录'} / {dd?.passScore ?? '-'}</dd></div>
    </dl>
    {dd?.reason && typeof dd.reason === 'string' && <p className="mt-2 text-sm text-ink-2">{dd.reason}</p>}
    {votes.length > 0 ? <ul className="mt-3 space-y-2">{votes.map((v: any, i: number) => <li key={i} className="border-l-2 border-danger pl-3 text-sm"><b className="text-ink">{v.reviewer}</b> <span className="text-xs text-ink-3">({v.model}) · {Number(v.score).toFixed(2)}</span><p className="whitespace-pre-wrap text-ink-2">{v.rationale}</p></li>)}</ul>
      : discardLines(dd).length > 0 && <ul className="mt-3 space-y-1 text-sm text-ink-2">{discardLines(dd).map((x, i) => <li key={i}>{x}</li>)}</ul>}
    {dd && <Disclosure className="mt-2" summaryClassName="text-caption text-ink-3" summary={<>详情（原始数据）</>}><pre className="mt-1 max-h-48 overflow-auto border border-line bg-sunken p-2 font-mono text-caption text-ink-2">{JSON.stringify(dd, null, 1)}</pre></Disclosure>}
  </section>;
}
