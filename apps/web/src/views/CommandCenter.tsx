import { isTaskRunning } from '../labels';
import { AppLink } from '@/components/AppLink';
/** 指挥台 v2（docs/command-center-v2-nav.md）：工作区树已移到侧边栏；指挥台首页已取消（新对话见 pages/Session.tsx）；这里是任务“会话”页签等用到的组件 */
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { api, fmtTime, type LineMeta, type ProjectDTO, type TaskDTO } from '../api';
import type { ChatSession } from '../lib/chat';
import { progressSteps } from '../lib/commandCenter';
import { statusName } from '../labels';
import { Card, cx } from '../ui';
import { NodeIcon } from '../ui/NodeIcon';
import { Disclosure } from '@/components/ui/collapsible';
import { ProgressStrip } from '../components/ProgressStrip';
export { ProgressStrip };
import { Timeline } from '../components/Timeline';
import { Checks, ShotStrip } from '../components/Shots';
import { BlockedCard, Composer, ConfirmCard } from '../components/FlowControls';
import { useTaskActions } from '../lib/taskActions';
import { buildCards, dropCurrentBlocked, shotUrl, mergeTimeline, modelActivity, modelDisplayName, modelStateName, reviewerName, type ChatLine, type ModelCall, type TaskEnvelope } from '../lib/timeline';
import { useTaskStream } from '../lib/taskStream';
import { TaskStreamStatus } from '../components/TaskStreamStatus';
import { reviewActivity, type ReviewActivity } from '../lib/reviewActivity';
import { ciState, cisOf, fmtDur, nodeFacts, prsOf, useRefreshing, type PlanData, type TaskDetailData } from '../lib/taskData';
import { executionStateName, nodeOutcomeName } from '../catalog';
import type { GNode } from '../lib/pipeline';

import { nodeIcon as nodeIconFor } from '../lib/timeline';

const toLines = (s: ChatSession | null): ChatLine[] => (s?.messages ?? []).filter(m => m.role !== 'tool' || m.tool_summary).map(m => ({ id: m.id, role: m.role, text: m.role === 'tool' ? `🔧 ${m.tool_summary ?? m.tool_name}` : m.content ?? '', at: m.created_at }));
/** 无调用记录时才按执行器说明；旧任务没有节点元数据时兼容标准 QA。 */
function nonModelExecution(nodeId: string, node?: GNode) {
  if (node?.executor && node.executor !== 'system' && node.executor !== 'check') return null;
  if (node?.executor === 'check' || node?.family === 'check' || node?.parent && node.stage === 'QA') return 'check';
  if ((node?.stage ?? nodeId) === 'QA' && !node?.parent) return 'ci';
  return null;
}
export function runningText(t: TaskDTO, calls: ModelCall[] = [], plan?: PlanData | null, review?: ReviewActivity | null, syncing = false) {
  if (!isTaskRunning(t) || plan?.paused || review?.finished) return null;
  if (syncing) return '同步执行状态…';
  const nodeId = plan?.currentNode ?? t.currentStage ?? t.status;
  if (nodeId === 'CODE_REVIEW') return '正在逐行审查…';
  const node = plan?.graph?.nodes.find(n => n.id === nodeId);
  const label = node?.label ?? progressSteps(t).find(s => s.state === 'current')?.label ?? statusName(t.currentStage ?? t.status);
  const base = `正在${label}…`;
  const execution = calls.length ? null : nonModelExecution(nodeId, node);
  if (execution === 'ci') return '等待 CI 测试结果 · GitHub Actions';
  if (execution === 'check') return base;
  const active = calls.filter(c => c.state === 'RUNNING' || c.state === 'QUEUED');
  if (active.some(c => c.kind === 'arbitration')) return `正在仲裁… · ${active.filter(c => c.kind === 'arbitration').map(c => modelDisplayName(c.model, c.state, c.source)).join('、')}`;
  const currentReview = active.find(c => c.kind === 'review');
  const reviews = calls.filter(c => c.kind === 'review' && c.groupId === currentReview?.groupId);
  if (reviews.length) return `正在评审… · ${reviews.filter(c => c.state === 'SUCCEEDED').length}/${Math.max(...reviews.map(c => c.total), reviews.length)} 已完成`;
  const models = [...new Set((active.length ? active : calls).flatMap(c => c.model ? [c.model] : []))];
  if (models.length) return `${base} · ${active.length ? '' : '本轮已用 '}${models.join('、')}`;
  const call = active[0] ?? calls.at(-1);
  return call ? `${base} · ${modelDisplayName(call.model, call.state, call.source)}` : base;
}

/** 自动滚到最新；用户往上翻时不打扰，改为显示「新消息」按钮 */
export function useStickToBottom(dep: unknown) {
  const ref = useRef<HTMLDivElement>(null); const atBottom = useRef(true); const [fresh, setFresh] = useState(false);
  const toBottom = () => { const el = ref.current; if (el) el.scrollTop = el.scrollHeight; setFresh(false); };
  const onScroll = () => { const el = ref.current; if (!el) return; atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48; if (atBottom.current) setFresh(false); };
  useLayoutEffect(() => { if (atBottom.current) toBottom(); else setFresh(true); }, [dep]);
  return { ref, onScroll, fresh, toBottom };
}

export function TaskSession({ task, session, reload }: { task: TaskDTO; session?: ChatSession; role?: string; lines: LineMeta[]; projects: ProjectDTO[]; canWrite: boolean; onCatalogChanged?: () => void; reload: () => void }) {
  const st = useTaskStream(task.id);
  const act = useTaskActions(task.id, st.seq);
  const sid = session?.id ?? act.state?.sessionId ?? null;
  const [s, setS] = useState<ChatSession | null>(null);
  const loadS = () => sid ? api<ChatSession>(`/api/chats/${sid}`).then(setS).catch(() => setS(null)) : Promise.resolve(setS(null));
  useEffect(() => { loadS(); }, [sid]);
  const [local, setLocal] = useState<ChatLine[]>([]); const [note, setNote] = useState<{ msg: string; tone: 'ok' | 'err' } | null>(null);
  const plan = useRefreshing<PlanData>(`/api/tasks/${encodeURIComponent(task.id)}/plan`, st.seq);
  const scope = { taskId: task.id, nodeId: plan?.currentNode ?? task.currentStage ?? task.status, iteration: plan?.currentIteration ?? task.currentIteration };
  const calls = modelActivity(st.events, scope);
  const review = reviewActivity(st.events, scope, calls);
  const entries = mergeTimeline(dropCurrentBlocked(buildCards(st.events), !!act.state?.blocked), [...toLines(s), ...(sid ? [] : local)]);
  const pending = act.state?.pending ?? [];
  const stick = useStickToBottom(`${entries.length}:${pending.length}:${act.state?.blocked}:${calls.map(c => `${c.callId}:${c.state}:${c.model}`).join('|')}:${review?.groups.map(g => `${g.fileGroupId}:${g.state}`).join('|')}`);
  useEffect(() => { if (note?.tone !== 'ok') return; const timer = setTimeout(() => setNote(null), 4000); return () => clearTimeout(timer); }, [note]);
  const said = (r: { text: string; reply: string; err?: boolean }) => {
    const at = new Date().toISOString();
    if (sid && !r.err) loadS(); else setLocal(l => [...l, ...(r.text ? [{ id: `u${at}`, role: 'user' as const, text: r.text, at }] : []), { id: `a${at}`, role: 'assistant' as const, text: r.reply, at }]);
    if (r.err) setNote({ msg: r.reply, tone: 'err' });
  };
  return <div className="min-w-0">
    <section className="relative flex h-[calc(100dvh-320px)] min-h-[320px] min-w-0 flex-col overflow-hidden border-2 border-ink bg-raised sm:h-[calc(100dvh-230px)] sm:min-h-[420px]" data-testid="cc-session">
      <ProgressStrip task={task} plan={plan} compact />
      {!sid && act.state && <div className="flex shrink-0 flex-wrap items-center gap-2 border-b border-dashed border-line px-4 py-2 text-sm text-ink-2" data-testid="cc-no-session">
        这个任务还没有会话记录，聊天内容不会保存。{act.state.can.act && <button type="button" className="underline" onClick={() => act.startSession().then(() => reload())} data-testid="start-session">开始会话</button>}</div>}
      <div ref={stick.ref} onScroll={stick.onScroll} className="flex min-h-0 flex-1 flex-col overflow-y-auto overscroll-contain p-4" data-testid="cc-scroll">
        <Timeline compact entries={entries} taskId={task.id} calls={calls} review={review} uncertain={['error', 'offline', 'blocked'].includes(st.mode)} lastSyncAt={st.lastSyncAt} running={act.state?.blocked ? null : runningText(task, calls, plan, review, st.mode === 'connecting' && !st.lastSyncAt)} />
        <div className="mt-3 space-y-2.5">
          {act.state && <BlockedCard s={act.state} act={act} onProposed={e => e && setNote({ msg: e, tone: 'err' })} />}
          {act.state && pending.map(a => <ConfirmCard key={a.id} a={a} act={act} can={act.state!.can} onDone={(msg, tone) => { setNote({ msg, tone }); if (sid) loadS(); }} />)}
          {note && <p role="status" className={cx('px-2 py-1 text-sm', note.tone === 'err' ? 'border border-danger bg-danger-soft text-danger-ink' : 'text-success-ink')} data-testid="cc-note">{note.msg}
            {note.tone === 'err' && note.msg.startsWith('操作冲突') && <button type="button" className="ml-2 underline" onClick={() => { act.reload(); setNote(null); }}>刷新</button>}</p>}
        </div>
      </div>
      {stick.fresh && <button type="button" onClick={stick.toBottom} className="absolute bottom-[170px] left-1/2 -translate-x-1/2 rounded-full border-2 border-ink bg-ink px-3 py-1 text-caption text-ink-inverse shadow-[2px_2px_0_rgb(var(--c-line-strong))]" data-testid="new-msg-pill">新消息 ↓</button>}
      {task.executionState === 'WAITING_CONFIGURATION' && <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-t border-warn bg-warn-soft px-3 py-2 text-sm text-warn-ink" role="status" data-testid="delivery-alert"><span>交付已就绪，需要配置发布目标。</span><AppLink className="underline" href={`/t/${encodeURIComponent(task.id)}/delivery`}>查看并处理 →</AppLink></div>}
      {act.state ? <Composer s={act.state} act={act} onSaid={said} status={<TaskStreamStatus mode={st.mode} state={st} />} /> : <div className="px-3 py-1.5 text-right"><TaskStreamStatus mode={st.mode} state={st} /></div>}
    </section>
  </div>;
}

export { TaskStreamStatus } from '../components/TaskStreamStatus';

function Row({ k, v }: { k: string; v: React.ReactNode }) { return <div className="flex justify-between gap-2 border-b border-dashed border-line py-1"><span className="text-ink-3">{k}</span><span className="text-right tabular">{v}</span></div>; }
const CI_ZH = { pass: '通过', fail: '失败', running: '运行中' } as const;
/** PR / CI 页签：所有轮次的 PR、CI 运行、测试报告 */
export function TaskPrCi({ task }: { task: TaskDTO }) {
  const d = useRefreshing<TaskDetailData>(`/api/tasks/${encodeURIComponent(task.id)}`, 0, 15000);
  if (!d) return <p className="text-sm text-ink-3">加载中…</p>;
  const prs = prsOf(d), cis = cisOf(d);
  return <div className="grid gap-gap lg:grid-cols-2" data-testid="task-prci">
    <Card title={`PR（${prs.length}）`}>{prs.length === 0 ? <p className="text-sm text-ink-3">还没有开 PR。</p> : <ul className="divide-y divide-dashed divide-line text-sm">{[...prs].reverse().map(e => <li key={e.id} className="py-2">
      <div className="flex items-center gap-2"><NodeIcon name="pr-open" size={15} /><AppLink className="font-bold underline" href={e.content?.url} target="_blank" rel="noreferrer">#{e.content?.number ?? '?'}</AppLink><span className="text-ink-3">第 {e.iteration} 轮 · {fmtTime(e.createdAt)}</span></div>
      {e.content?.summary && <p className="mt-1 text-ink-2">{String(e.content.summary).slice(0, 200)}</p>}
      <p className="text-caption text-ink-3">{Array.isArray(e.content?.files) ? `${e.content.files.length} 个文件` : ''}{e.content?.lines != null ? ` · ${e.content.lines} 行` : ''}{e.content?.headSha ? ` · ${String(e.content.headSha).slice(0, 7)}` : ''}
        {e.content?.url && <> · <AppLink className="underline" href={`${e.content.url}/files`} target="_blank" rel="noreferrer">看改动</AppLink></>}</p></li>)}</ul>}</Card>
    <Card title={`CI / 测试（${cis.length}）`}>{cis.length === 0 ? <p className="text-sm text-ink-3">还没有 CI 记录。</p> : <ul className="divide-y divide-dashed divide-line text-sm">{[...cis].reverse().map(e => { const s = ciState(e); return <li key={e.id} className="py-2">
      <div className="flex items-center gap-2"><NodeIcon name={s === 'pass' ? 'ci-pass' : s === 'fail' ? 'ci-fail' : 'ci-running'} size={15} /><b className={s === 'fail' ? 'text-danger-ink' : s === 'pass' ? 'text-success-ink' : ''}>{e.kind === 'test_report' ? '测试报告' : 'CI'} {CI_ZH[s]}</b><span className="text-ink-3">第 {e.iteration} 轮 · {fmtTime(e.createdAt)}</span>
        {e.content?.runUrl && <AppLink className="ml-auto underline" href={e.content.runUrl} target="_blank" rel="noreferrer">运行记录</AppLink>}</div>
      {e.content?.log && <Disclosure className="mt-1" summaryClassName="text-caption text-accent-ink" summary="看日志"><pre className="mt-1 max-h-72 overflow-auto whitespace-pre-wrap break-all bg-surface p-2 font-mono text-[11px]">{String(e.content.log).slice(-4000)}</pre></Disclosure>}
    </li>; })}</ul>}</Card>
  </div>;
}

/** 节点详情（二级页）：用时、执行记录、证据（日志 / diff 链接）、本节点的事件 */
export function NodeDetail({ task, nodeId }: { task: TaskDTO; nodeId: string }) {
  const st = useTaskStream(task.id);
  const plan = useRefreshing<PlanData>(`/api/tasks/${encodeURIComponent(task.id)}/plan`, st.seq);
  const d = useRefreshing<TaskDetailData>(`/api/tasks/${encodeURIComponent(task.id)}`, st.seq, 30000);
  const node = plan?.graph?.nodes.find(n => n.id === nodeId); const rec = plan?.records?.[nodeId];
  const f = nodeFacts(d, nodeId, st.events as TaskEnvelope[]);
  const history = new Map<string, TaskEnvelope>();
  f.events.forEach(e => { const event = e as TaskEnvelope; if (event.type === 'MODEL_CALL' && event.data?.callId) history.set(event.data.callId, event); });
  const recordedModels = [...new Set([...history.values()].flatMap(e => e.model ? [e.model] : []))];
  const execution = history.size ? null : nonModelExecution(nodeId, node);
  const latest = [...history.values()].at(-1);
  const callState = (e: TaskEnvelope) => e.data.stale && ['RUNNING', 'QUEUED'].includes(e.data.state) ? 'UNCONFIRMED' : e.data.state;
  const emptyModels = execution === 'ci' ? '本节点由 CI 执行' : execution === 'check' ? '本节点执行自动检查'
    : latest ? modelDisplayName(latest.model, callState(latest), latest.data.source)
    : st.mode === 'connecting' && !st.lastSyncAt ? '同步执行状态…' : '暂无模型调用记录';
  const label = node?.label ?? statusName(nodeId);
  return <section className="border-2 border-ink bg-raised" data-testid="cc-node" data-node={nodeId}>
    <header className="flex flex-wrap items-center gap-2 border-b-2 border-ink px-4 py-3"><AppLink href={`/t/${encodeURIComponent(task.id)}/chat`} className="underline">← 回到会话</AppLink><h2 className="flex-1 text-base font-bold"><NodeIcon name={nodeIconFor(nodeId)} size={16} /> {label}</h2>
      {rec && <span className="border border-ink px-2 text-caption">{rec.valid === false ? '待复验' : nodeOutcomeName(rec.outcome)}</span>}</header>
    <ProgressStrip task={task} plan={plan} active={nodeId} />
    <div className="grid gap-gap p-4 lg:grid-cols-[minmax(0,1fr)_280px]">
      <div className="min-w-0 space-y-4">
        <div><h3 className="mb-2 font-bold">产出</h3>{f.evidence.length === 0 ? <p className="text-sm text-ink-3">这个节点还没有产出。</p> : <ul className="space-y-2" data-testid="node-evidence">{f.evidence.map(e => <li key={e.id} className="border border-line p-2 text-sm">
          <div className="flex flex-wrap items-center gap-2"><b>{({ pr: 'PR', ci_log: 'CI 日志', test_report: '测试报告', verdict: '验收结论', merge_record: '合并记录' } as Record<string, string>)[e.kind] ?? e.kind}</b><span className="text-ink-3">第 {e.iteration} 轮 · {fmtTime(e.createdAt)}</span>
            {e.kind === 'pr' && e.content?.url && <><AppLink className="underline" href={e.content.url} target="_blank" rel="noreferrer">#{e.content.number}</AppLink><AppLink className="underline" href={`${e.content.url}/files`} target="_blank" rel="noreferrer">看 diff</AppLink></>}
            {e.content?.runUrl && <AppLink className="underline" href={e.content.runUrl} target="_blank" rel="noreferrer">运行记录</AppLink>}</div>
          {(e.content?.log || e.content?.output) && <pre className="mt-1 max-h-80 overflow-auto whitespace-pre-wrap break-all bg-surface p-2 font-mono text-[11px]" data-testid="node-log">{String(e.content.log ?? e.content.output).slice(-6000)}</pre>}
          {e.content?.summary && <p className="mt-1 text-ink-2">{String(e.content.summary).slice(0, 400)}</p>}
          <Disclosure className="mt-1" summaryClassName="text-caption text-ink-3" summary="详情"><pre className="mt-1 max-h-60 overflow-auto whitespace-pre-wrap break-all font-mono text-[11px]">{JSON.stringify(e.content, null, 2).slice(0, 8000)}</pre></Disclosure></li>)}</ul>}</div>
        <div><h3 className="mb-2 font-bold">过程</h3><Timeline entries={mergeTimeline(buildCards(f.events as TaskEnvelope[]), [])} taskId={task.id} /></div>
      </div>
      <aside className="space-y-3 text-sm">
        <Card title="模型记录">
          <p className="break-words text-xs text-ink-3 [overflow-wrap:anywhere]">{recordedModels.length ? recordedModels.join("、") : emptyModels}</p>
          {history.size > 0 && <Disclosure summary={`查看 ${history.size} 次调用`}><ul className="mt-2 max-h-64 space-y-2 overflow-y-auto text-xs" data-testid="node-model-history">{[...history.values()].map(e => <li key={e.data.callId} className="break-words [overflow-wrap:anywhere]">第 {e.iteration ?? '—'} 轮 · {reviewerName(e.data.role ?? '')}<br />{modelDisplayName(e.model, callState(e), e.data.source)} · {modelStateName(callState(e))}</li>)}</ul></Disclosure>}
        </Card>
        <Card title="用时"><Row k="开始" v={f.first ? fmtTime(f.first) : '—'} /><Row k="最近" v={f.last ? fmtTime(f.last) : '—'} /><Row k="用时" v={fmtDur(f.ms)} />
          {rec && <><Row k="运行次数" v={rec.runs ?? 0} /><Row k="花费" v={`$${Number(rec.usd ?? 0).toFixed(3)}`} /></>}</Card>
        <Card title={`历史执行记录（${f.runs.length}）`}>{f.runs.length === 0 ? <p className="text-ink-3">无</p> : f.runs.map((r: any) => <Row key={r.id} k={`第 ${r.iteration_seq ?? r.iteration ?? '?'} 轮 · 第 ${r.attempt} 次`} v={<span className={r.state === 'FAILED' || r.outcome === 'FAILED' ? 'text-danger-ink' : ''}>{executionStateName(r.state)} · {nodeOutcomeName(r.outcome ?? r.output?.outcome)}{r.error ? ' ⚠' : ''}</span>} />)}</Card>
        <AppLink className="inline-block underline" href={`/t/${encodeURIComponent(task.id)}/detail?tab=timeline&focus=${encodeURIComponent(nodeId)}`}>在任务详情里看 →</AppLink>
      </aside>
    </div>
  </section>;
}

/** 页面验收记录（/api/agent-jobs/ui-acceptance）：开关关闭时为空列表 */
export interface UiRun { id: string; verdict: string; blocked: number | boolean; created_at: string; report: { summary?: string; criteria?: { criterion: string; verdict: string; reason?: string }[]; tests?: { title: string; status: string; error?: string | null }[]; screenshots?: string[]; passed?: number; failed?: number; source?: string; usedImages?: boolean; observeOnly?: boolean; phase?: string; appLogTail?: string } }
export const runShots = (r: UiRun) => (r.report.screenshots ?? []).map(n => shotUrl(r.id, n));
export const runChecks = (r: UiRun) => [...(r.report.criteria ?? []).map(x => ({ name: x.criterion, ok: x.verdict === 'PASS' ? true : x.verdict === 'FAIL' ? false : null, reason: x.reason })),
  ...(r.report.tests ?? []).map(x => ({ name: `脚本：${x.title}`, ok: x.status === 'passed' ? true : x.status === 'skipped' ? null : false, reason: x.error ?? undefined }))];
const VERDICT_ZH: Record<string, string> = { PASS: '通过', FAIL: '没通过', UNCERTAIN: '需要人工确认' };
const verdictCls = (r: UiRun) => r.blocked ? 'text-danger-ink' : r.verdict === 'PASS' ? 'text-success-ink' : 'text-warn-ink';
/** 「验收」页签：每次页面验收的结论、判定理由、逐条检查、截图（灯箱） */
export function TaskAcceptance({ task }: { task: TaskDTO }) {
  const runs = useRefreshing<UiRun[]>(`/api/agent-jobs/ui-acceptance?taskId=${encodeURIComponent(task.id)}`, 0, 30000);
  if (!runs) return <p className="text-sm text-ink-3">加载中…</p>;
  if (runs.length === 0) return <Card title="页面验收"><p className="text-sm text-ink-2" data-testid="accept-empty">这个任务还没有页面验收记录。</p><p className="mt-1 text-caption text-ink-3">页面验收需要项目开启「页面验收」，并且服务端打开 AGENT_RUNTIME=remote-docker 与 UI_ACCEPTANCE=on。</p></Card>;
  return <div className="space-y-gap" data-testid="task-accept">{runs.map((r, i) => <Card key={r.id} title={<span className="inline-flex items-center gap-2"><NodeIcon name="node-accept" size={16} />{i === 0 ? '最近一次' : '更早'} · {fmtTime(r.created_at)}<span className={cx('ml-2', verdictCls(r))}>{VERDICT_ZH[r.verdict] ?? r.verdict}{r.blocked ? '（已退回修改）' : r.report.observeOnly && r.verdict === 'FAIL' ? '（观察期只记录）' : ''}</span></span>}>
    <div className="grid gap-gap lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <div className="min-w-0 space-y-2 text-sm">
        {r.report.summary && <p className="text-ink-2" data-testid="accept-summary">{r.report.summary}</p>}
        <p className="text-caption text-ink-3">判定：{r.report.source === 'deterministic' ? '脚本 / 启动结果' : `模型${r.report.usedImages ? '（看了截图）' : '（仅文字）'}`}{r.report.passed != null ? ` · 验收脚本 ${r.report.passed} 通过 / ${r.report.failed ?? 0} 失败` : ''}</p>
        <Checks checks={runChecks(r)} />
        {r.report.appLogTail && <Disclosure summaryClassName="text-caption text-accent-ink" summary="应用日志（末尾）"><pre className="mt-1 max-h-48 overflow-auto whitespace-pre-wrap break-all bg-surface p-2 font-mono text-[11px]">{r.report.appLogTail}</pre></Disclosure>}
      </div>
      <div className="min-w-0"><ShotStrip shots={runShots(r)} />{runShots(r).length === 0 && <p className="text-caption text-ink-3">没有截图。</p>}</div>
    </div>
  </Card>)}</div>;
}
