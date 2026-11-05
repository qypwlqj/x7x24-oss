/** 指挥台 v2 · M2：任务事件信封 → 时间线卡片（docs/command-center-v2.md 第 2、4 节）。纯函数，便于测试 */
import { describeEvent, type EvtText } from './eventText';
import { statusName } from '../labels';
import type { NodeIconName } from '../ui/NodeIcon';

export interface TaskEnvelope { seq: number; taskId: string; type: string; nodeId?: string | null; iteration?: number | null; at: string; actor?: string; model?: string | null; from?: string | null; to?: string | null; edge?: string | null; data?: any; evidence?: { id: string; kind: string; stage: string; content: any } }
export type CardKind = 'node.entered' | 'node.finished' | 'agent.thinking' | 'agent.tools' | 'agent.browser' | 'pr.opened' | 'pr.merged' | 'ci.running' | 'ci.passed' | 'ci.failed' | 'acceptance.result' | 'task.blocked' | 'approval.waiting' | 'agent.question' | 'direction.changed' | 'budget' | 'task.done' | 'event';
/** 展开方式：popover = 短的只读信息；collapse = 行内展开（工具调用、原因）；card = 重要卡片（自带 collapse）；none = 只有一行 */
export type Expand = 'popover' | 'collapse' | 'card' | 'none';
/** 一次工具调用（runner 精简后）：耗时 / 结果 / 截断的输出 */
export interface ToolItem { title: string; text?: string; at: string; ms?: number; ok?: boolean | null; out?: string; count?: number; browser?: boolean }
export interface TimelineCard { key: string; seq: number; kind: CardKind; icon: NodeIconName; title: string; text?: string; tone: EvtText['tone']; at: string; nodeId?: string | null; expand: Expand; models?: string[]; items?: ToolItem[]; checks?: { name: string; ok: boolean | null; reason?: string }[]; verdict?: string; link?: { href: string; label: string }; shots?: string[]; suggestions?: string[]; raw: TaskEnvelope[] }

export interface ModelCall { callId: string; groupId: string; runId: string; role: string; kind: string; source: string; state: string; model?: string; total: number; fileGroupId?: string; slot?: number }
export function modelDisplayName(model: string | null | undefined, state: string, source?: string) {
  if (model) return model;
  if (state === 'QUEUED' && source === 'agent') return '等待开发助手启动';
  if (state === 'QUEUED' || state === 'RUNNING') return '等待模型上报';
  return state === 'UNCONFIRMED' ? '模型状态待确认' : '本次未记录模型';
}
export const modelStateName = (state: string) => ({ QUEUED: '等待调用', RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已停止', UNCONFIRMED: '运行状态待确认' }[state] ?? state);
export const reviewerName = (role: string) => ({ 'prd.draft': '需求起草', 'plan.make': '任务规划', 'spec.write': '需求策划', 'design.make': '设计助手', 'prd.value': '价值评审', 'prd.feasibility': '可行性评审', 'prd.risk': '风险评审', 'accept.cases': '用例评审', 'accept.ux': '体验评审', 'accept.risk': '风险评审', 'review.design': '设计评审', 'review.code': '代码评审', 'review.code.verify': '复核评审' }[role] ?? (role.includes('arbiter') ? '仲裁员' : role.startsWith('dev.') ? '开发助手' : '评审员'));
/** 仅重建当前一轮调用；节点变化、重试和恢复后不沿用上轮状态，迟到的结束事件不重建旧调用。 */
export function modelActivity(events: TaskEnvelope[], scope: { taskId: string; nodeId?: string | null; iteration?: number | null }): ModelCall[] {
  const calls = new Map<string, ModelCall>(); const retired = new Set<string>(); const retiredRuns = new Set<string>(); let runId: string | undefined;
  for (const e of [...events].sort((a, b) => a.seq - b.seq)) {
    if (e.taskId !== scope.taskId) continue;
    if (['STATUS_CHANGED', 'TASK_RESTARTED', 'TASK_RESUMED', 'NODE_RETRY_REQUESTED', 'DIRECTION_CHANGED', 'TASK_PAUSED', 'AGENT_FAILED'].includes(e.type)) { calls.forEach(c => retired.add(c.callId)); if (runId) retiredRuns.add(runId); calls.clear(); runId = undefined; }
    if (e.type !== 'MODEL_CALL' || e.nodeId !== scope.nodeId || scope.iteration != null && e.iteration !== scope.iteration) continue;
    const d = e.data;
    if (!d?.callId || !d.runId || retired.has(d.callId) || retiredRuns.has(d.runId) || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(d.state)) continue;
    const starting = d.state === 'QUEUED' || d.state === 'RUNNING';
    if (starting && d.runId !== runId) { calls.forEach(c => retired.add(c.callId)); if (runId) retiredRuns.add(runId); calls.clear(); runId = d.runId; }
    if (d.runId !== runId || !starting && !calls.has(d.callId)) continue;
    calls.set(d.callId, { ...d, state: d.stale && starting ? 'UNCONFIRMED' : d.state, model: typeof d.model === 'string' && d.model.trim() ? d.model : undefined });
  }
  return [...calls.values()];
}

const END = new Set(['DELIVERED', 'RELEASED']);
const NODE_ICON: Record<string, NodeIconName> = { BACKLOG: 'node-intake', INTAKE: 'node-intake', SPEC: 'node-spec', PRD_REVIEW: 'node-spec', PLANNING: 'node-plan', DESIGN: 'node-design', API_DESIGN: 'node-design', DB_SCHEMA: 'node-design', UX_DESIGN: 'node-design', DEV: 'node-code', QA: 'node-test', SEC_AUDIT: 'node-audit', ACCEPTANCE: 'node-accept', DELIVERED: 'done', RELEASED: 'done', ANOMALY: 'blocked', DEGRADED: 'budget' };
export const nodeIcon = (id?: string | null): NodeIconName => (id && (NODE_ICON[id] ?? (id.startsWith('CHECK_') ? 'node-test' : undefined))) || 'node-enter';
const legacy = (e: TaskEnvelope) => ({ event_type: e.type, payload: e.data ?? {}, from_status: e.from, to_status: e.to, edge: e.edge, actor: e.actor, occurred_at: e.at });
const TOOL_ZH: Record<string, string> = { Bash: '命令', Read: '读文件', Edit: '改文件', Write: '写文件', Glob: '找文件', Grep: '搜代码', LS: '看目录', TodoWrite: '计划' };
export const toolName = (n: string) => n?.startsWith('mcp__playwright__browser_') ? `浏览器·${n.slice(25)}` : TOOL_ZH[n] ?? n;
const short = (s: unknown, n = 140) => { const t = String(s ?? '').replace(/\s+/g, ' ').trim(); return t.length > n ? t.slice(0, n) + '…' : t; };

/** 单个事件 → 卡片；null = 不在时间线上显示（太琐碎，只在「详情」里） */
export function toCard(e: TaskEnvelope): TimelineCard | null {
  if (e.type === 'CODE_REVIEW_PROGRESS') return null; // 运行面板消费高频分组状态，不重复插入时间线。
  const base = { key: `e${e.seq}`, seq: e.seq, at: e.at, nodeId: e.nodeId, raw: [e] };
  const d = e.data ?? {};
  if (e.type === 'VERDICT' && d.gate === 'code_review') return null;
  const ev = e.evidence; const c = ev?.content ?? {};
  switch (e.type) {
    case 'MODEL_CALL': return d.previousModel && e.model && d.previousModel !== e.model
      ? { ...base, kind: 'direction.changed', icon: 'direction', tone: 'info', expand: 'popover', title: `已切换模型：${d.previousModel} → ${e.model}`, text: `${reviewerName(d.role ?? '')}${d.source === 'agent' ? '更新了运行模型' : '上一个模型调用失败，继续执行'}` } : null;
    case 'STATUS_CHANGED': {
      if (e.to && END.has(e.to)) return { ...base, kind: 'task.done', icon: 'done', tone: 'success', expand: 'card', title: e.to === 'RELEASED' ? 'GitHub Release 已创建，等待收尾' : '已验收，等待发布', text: e.edge ? `经过：${e.edge}` : undefined };
      if (e.to === 'ANOMALY') return { ...base, kind: 'task.blocked', icon: 'blocked', tone: 'danger', expand: 'card', title: `流程卡在「${statusName(e.from ?? '')}」`, text: '系统已暂停推进，正在判断原因。' };
      if (e.to === 'DISCARDED') return { ...base, kind: 'task.done', icon: 'skipped', tone: 'neutral', expand: 'card', title: '任务已放弃' };
      return { ...base, kind: 'node.entered', icon: nodeIcon(e.to), tone: 'info', expand: 'popover', title: `进入「${statusName(e.to ?? '')}」`, text: e.from ? `从「${statusName(e.from)}」过来${e.iteration ? ` · 第 ${e.iteration} 轮` : ''}` : undefined };
    }
    case 'STAGE_RESUMED': { const t = describeEvent(legacy(e)); return { ...base, kind: 'node.entered', icon: 'loop-back', tone: t.tone, expand: 'popover', title: t.title, text: t.text }; }
    case 'NODE_PASSED': case 'VERDICT': case 'CODE_REVIEW_DONE': { const t = describeEvent(legacy(e)); const accept = e.nodeId === 'ACCEPTANCE' || d.node === 'ACCEPTANCE';
      return { ...base, kind: accept ? 'acceptance.result' : 'node.finished', icon: accept ? 'node-accept' : 'done', tone: t.tone === 'neutral' ? 'success' : t.tone, expand: accept ? 'card' : e.type === 'CODE_REVIEW_DONE' && d.blocked ? 'collapse' : 'popover', title: t.title, text: t.text, shots: accept ? shots(d) : undefined }; }
    case 'NODE_REJECTED': { const t = describeEvent(legacy(e)); return { ...base, kind: 'node.finished', icon: 'loop-back', tone: 'warn', expand: 'collapse', title: t.title, text: t.text }; }
    case 'STAGE_SKIPPED': { const t = describeEvent(legacy(e)); return { ...base, kind: 'node.finished', icon: 'skipped', tone: 'neutral', expand: 'popover', title: t.title, text: t.text }; }
    case 'ADAPTER_CALL': return { ...base, kind: 'agent.tools', icon: 'tool-run', tone: 'neutral', expand: 'collapse', title: '运行工具', items: [{ title: toolTitle(d), text: d.ok === false || d.error ? `失败：${short(d.error ?? d.message)}` : undefined, at: e.at }] };
    case 'TASK_BLOCKED': case 'AGENT_FAILED': case 'ANOMALY_OPENED': case 'OWNER_NEEDED': case 'PATH_POLICY_BLOCK': { const t = describeEvent(legacy(e));
      const link = t.actions?.find(a => a.kind === 'link' && a.href);
      const sug = (t.actions ?? []).filter(a => a.kind !== 'link').map(a => a.label); const heal = Array.isArray(d.healPlan) ? d.healPlan.map((x: any) => String(x.action ?? x)) : [];
      const retrying = e.type === 'AGENT_FAILED' && t.tone !== 'danger';
      return { ...base, kind: retrying ? 'agent.thinking' : 'task.blocked', icon: retrying ? 'agent-think' : 'blocked', tone: retrying ? 'warn' : 'danger', expand: retrying ? 'collapse' : 'card', title: t.title, text: t.text, suggestions: [...sug, ...heal], link: link ? { href: link.href!, label: link.label } : undefined }; }
    case 'SELF_HEAL': { const t = describeEvent(legacy(e)); return { ...base, kind: 'agent.thinking', icon: 'agent-think', tone: 'info', expand: 'popover', title: t.title, text: t.text }; }
    case 'MERGE_APPROVAL_REQUESTED': case 'MERGE_APPROVAL_REMINDER': case 'MERGE_APPROVAL_STALE': { const t = describeEvent(legacy(e)); return { ...base, kind: 'approval.waiting', icon: 'await-approval', tone: 'warn', expand: 'card', title: t.title, text: t.text, link: { href: `/t/${e.taskId}/detail?tab=info`, label: '去任务详情审批 →' } }; }
    case 'MERGE_APPROVED': { const t = describeEvent(legacy(e)); return { ...base, kind: 'pr.merged', icon: 'pr-merged', tone: 'success', expand: 'popover', title: t.title, text: t.text }; }
    case 'MERGE_RETURNED': { const t = describeEvent(legacy(e)); return { ...base, kind: 'direction.changed', icon: 'loop-back', tone: 'warn', expand: 'collapse', title: t.title, text: t.text }; }
    case 'CHAT_CLARIFICATION_REQUESTED': { const t = describeEvent(legacy(e)); return { ...base, kind: 'agent.question', icon: 'user-input', tone: 'warn', expand: 'card', title: t.title, text: t.text }; }
    case 'PLAN_REVISED': case 'TASK_EDITED': case 'TASK_RESTARTED': case 'TASK_RESUMED': case 'TASK_PAUSED': { const t = describeEvent(legacy(e)); return { ...base, kind: 'direction.changed', icon: e.type === 'TASK_PAUSED' ? 'await-approval' : 'direction', tone: t.tone, expand: 'popover', title: t.title || (e.type === 'TASK_PAUSED' ? '已暂停' : '流程有调整'), text: t.text }; }
    case 'BUDGET_HIT': case 'DEGRADED': case 'TASK_BUDGET_SWITCH': { const t = describeEvent(legacy(e)); return { ...base, kind: 'budget', icon: 'budget', tone: e.type === 'TASK_BUDGET_SWITCH' ? 'info' : 'warn', expand: 'card', title: t.title, text: t.text, link: { href: `/t/${e.taskId}/budget`, label: '看预算 →' } }; }
    case 'TASK_ACTION_EXECUTED': return { ...base, kind: 'direction.changed', icon: d.danger === 'high' ? 'blocked' : 'direction', tone: d.danger === 'high' ? 'warn' : 'info', expand: 'popover', title: `@${e.actor} 确认了「${d.label ?? d.kind}」`, text: d.proposedBy && d.proposedBy !== e.actor ? `由 @${d.proposedBy} 提议${d.source === 'text' ? '（从对话理解）' : ''}` : d.source === 'text' ? '从对话理解' : undefined };
    case 'TASK_ACTION_FAILED': return { ...base, kind: 'event', icon: 'blocked', tone: 'danger', expand: 'popover', title: '操作没有执行', text: short(d.error) };
    case 'DIRECTION_CHANGED': return { ...base, kind: 'direction.changed', icon: 'direction', tone: 'warn', expand: 'collapse', title: `改方向：退回「${statusName(d.to ?? 'DEV')}」`, text: d.instruction };
    case 'NODE_RETRY_REQUESTED': return { ...base, kind: 'direction.changed', icon: 'loop-back', tone: 'info', expand: 'popover', title: `重试「${statusName(d.node ?? '')}」`, text: d.hint || undefined };
    case 'QUESTION_ANSWERED': return { ...base, kind: 'agent.question', icon: 'user-input', tone: 'success', expand: 'popover', title: `@${e.actor} 回答了问题`, text: short(d.answer) };
    case 'AGENT_TOOLS': case 'AGENT_BROWSER_CHECK': {
      const items: ToolItem[] = (d.items ?? []).map((x: any) => ({ title: `${toolName(x.name)}${x.count > 1 ? ` ×${x.count}` : ''}${x.args ? `  ${x.args}` : ''}`, text: x.ok === false ? `失败${x.out ? `：${short(x.out, 160)}` : ''}` : undefined, at: e.at, ms: x.ms, ok: x.ok, out: x.out, count: x.count, browser: !!x.browser }));
      const browser = e.type === 'AGENT_BROWSER_CHECK';
      return { ...base, kind: browser ? 'agent.browser' : 'agent.tools', icon: browser ? 'screenshot' : 'tool-run', tone: d.failed > 0 ? 'warn' : 'neutral', expand: 'collapse', title: browser ? 'AI 在浏览器里自测' : `运行了 ${items.length} 个工具`, items };
    }
    case 'AGENT_NOTE': return { ...base, kind: 'agent.thinking', icon: 'agent-think', tone: 'neutral', expand: 'popover', title: short(d.text, 60), text: d.text && d.text.length > 60 ? d.text : undefined };
    case 'AGENT_RESULT': return { ...base, kind: 'agent.thinking', icon: 'done', tone: 'info', expand: 'popover', title: `AI 本轮结束：${short(d.text, 40)}` };
    case 'EVIDENCE_ADDED': return evidenceCard(e, base);
    default: { const t = describeEvent(legacy(e)); if (!t.title || t.title === e.type) return { ...base, kind: 'event', icon: 'node-enter', tone: 'neutral', expand: 'none', title: e.type }; return { ...base, kind: 'event', icon: 'node-enter', tone: t.tone, expand: t.text ? 'popover' : 'none', title: t.title, text: t.text }; }
  }
}
function toolTitle(d: any) { const op = d.op ?? d.operation ?? d.tool ?? d.name ?? d.kind ?? '调用'; const tgt = d.target ?? d.path ?? d.url ?? d.repo ?? ''; return short(`${op}${tgt ? ' ' + tgt : ''}`, 90); }
export const shotUrl = (jobId: string, name: string) => `/api/agent-jobs/${encodeURIComponent(jobId)}/screenshots/${encodeURIComponent(name)}`;
function shots(d: any): string[] | undefined { const s = d.screenshots ?? d.shots; return Array.isArray(s) ? s.map((x: any) => String(x.url ?? x.uri ?? x)).slice(0, 6) : undefined; }

function evidenceCard(e: TaskEnvelope, base: Pick<TimelineCard, 'key' | 'seq' | 'at' | 'nodeId' | 'raw'>): TimelineCard | null {
  const ev = e.evidence; if (!ev) return null; const c = ev.content ?? {};
  switch (ev.kind) {
    case 'pr': return { ...base, kind: 'pr.opened', icon: 'pr-open', tone: 'info', expand: 'popover', title: `开了 PR #${c.number ?? '?'}${c.lines != null ? ` · 改动 ${c.lines} 行` : ''}`, text: [c.summary && short(c.summary), Array.isArray(c.files) ? `${c.filesTruncated ?? c.files.length} 个文件` : ''].filter(Boolean).join(' · '), link: c.url ? { href: String(c.url), label: '在 GitHub 打开 →' } : undefined };
    case 'ci_log': { const st = String(c.state ?? c.conclusion ?? ''); const ok = st === 'passed' || st === 'success'; const run = st === 'running' || st === 'pending' || st === 'queued';
      return { ...base, kind: ok ? 'ci.passed' : run ? 'ci.running' : 'ci.failed', icon: ok ? 'ci-pass' : run ? 'ci-running' : 'ci-fail', tone: ok ? 'success' : run ? 'info' : 'danger', expand: ok || run ? 'popover' : 'collapse', title: ok ? 'CI 通过' : run ? 'CI 运行中' : `CI 没通过${c.conclusion && c.conclusion !== st ? `（${c.conclusion}）` : ''}`, text: !ok && c.log ? short(String(c.log).split('\n').filter(Boolean).slice(-3).join(' / '), 200) : undefined, link: c.runUrl ? { href: String(c.runUrl), label: '看 CI 日志 →' } : undefined }; }
    case 'test_report': return { ...base, kind: 'ci.passed', icon: c.passed === false || c.ok === false ? 'ci-fail' : 'ci-pass', tone: c.passed === false || c.ok === false ? 'danger' : 'success', expand: 'popover', title: '测试报告', text: c.summary ? short(c.summary) : undefined };
    case 'verdict': if (ev.stage === 'ACCEPTANCE') return { ...base, kind: 'acceptance.result', icon: 'node-accept', tone: c.pass === false || c.passed === false ? 'danger' : 'success', expand: 'card', title: c.pass === false || c.passed === false ? '验收没通过' : '验收结果', text: c.summary ? short(c.summary, 200) : c.reason ? short(c.reason, 200) : undefined, shots: shots(c) }; return null;
    case 'ui_report': { const v = String(c.verdict ?? ''); const pass = v === 'PASS' && !c.blocked;
      const checks = [...(c.criteria ?? []).map((x: any) => ({ name: String(x.criterion ?? x.name ?? ''), ok: x.verdict === 'PASS' ? true : x.verdict === 'FAIL' ? false : null, reason: x.reason })),
        ...(c.tests ?? []).map((x: any) => ({ name: `脚本：${x.title}`, ok: x.status === 'passed' ? true : x.status === 'skipped' ? null : false, reason: x.error ?? undefined }))];
      return { ...base, kind: 'acceptance.result', icon: 'node-accept', tone: pass ? 'success' : c.blocked ? 'danger' : 'warn', expand: 'card', verdict: v,
        title: c.blocked ? '页面验收没通过，退回修改' : v === 'PASS' ? '页面验收通过' : v === 'FAIL' ? '页面验收：判定没通过（观察期只记录）' : '页面验收：需要人工确认',
        text: [c.summary && short(c.summary, 240), c.passed != null ? `验收脚本 ${c.passed} 通过 / ${c.failed ?? 0} 失败` : ''].filter(Boolean).join(' · '),
        shots: (c.screenshots ?? []).slice(0, 12).map((n: string) => shotUrl(String(c.jobId), n)), checks, link: { href: `/t/${e.taskId}/accept`, label: '看验收详情 →' } }; }
    case 'merge_record': return { ...base, kind: 'pr.merged', icon: 'pr-merged', tone: 'success', expand: 'popover', title: `PR 已合并${c.sha ? ` · ${String(c.sha).slice(0, 7)}` : ''}` };
    case 'plan': case 'spec': case 'prd': case 'design': return { ...base, kind: 'node.finished', icon: nodeIcon(ev.stage), tone: 'success', expand: 'popover', title: `「${statusName(ev.stage)}」产出了${({ plan: '任务规划', spec: '需求策划', prd: 'PRD', design: '设计方案' } as Record<string, string>)[ev.kind]}`, text: c.summary ? short(c.summary) : c.goal ? short(c.goal) : undefined };
    default: return null;
  }
}

/** 全部事件 → 卡片；连续的工具调用合并成一组（同一节点内） */
export function buildCards(events: TaskEnvelope[]): TimelineCard[] {
  const out: TimelineCard[] = []; const seen = new Set<number>();
  const visited: TaskEnvelope[] = [];
  const lastModel = new Map<string, TaskEnvelope>();
  const switches = new Map<string, { card: TimelineCard; groups: Set<string> }>();
  let blocked: TimelineCard | undefined;
  for (const e of [...events].sort((a, b) => a.seq - b.seq)) {
    if (seen.has(e.seq)) continue; seen.add(e.seq);
    visited.push(e);
    let c = toCard(e);
    if (e.type === 'MODEL_CALL' && e.model) {
      const key = JSON.stringify([e.taskId, e.nodeId, e.iteration, e.data?.role]);
      const previous = lastModel.get(key);
      if (!c && e.data?.state === 'RUNNING' && e.data.kind === 'execution' && e.data.source === 'llm' && previous?.data?.source === 'agent' && previous.data.state === 'FAILED' && previous.model !== e.model) {
        c = toCard({ ...e, data: { ...e.data, previousModel: previous.model } });
        if (c) { c.raw = [previous, e]; c.text = `${reviewerName(e.data.role ?? '')}改用新的模型继续执行`; }
      }
      if (e.data?.state !== 'QUEUED') lastModel.set(key, e);
    }
    if (!c) continue; const last = out[out.length - 1];
    if (['node.finished', 'pr.opened', 'acceptance.result'].includes(c.kind) && e.type !== 'STAGE_SKIPPED') {
      const calls = modelActivity(visited, { taskId: e.taskId, nodeId: c.nodeId, iteration: e.iteration });
      const relevant = c.kind === 'pr.opened' ? calls.filter(x => x.kind === 'execution') : calls;
      const votes = e.data?.votes ?? e.evidence?.content?.votes ?? [];
      const listed = e.type === 'CODE_REVIEW_DONE' && Array.isArray(e.data?.models) ? e.data.models.filter((m: unknown) => typeof m === 'string' && m) as string[] : [];
      c.models = [...new Set([...relevant.filter(x => x.state === 'SUCCEEDED' && x.model).map(x => x.model!), ...(Array.isArray(votes) ? votes.filter((v: any) => typeof v.model === 'string' && v.model).map((v: any) => v.model as string) : []), ...listed])];
    }
    if (endsBlockingEpisode(c)) blocked = undefined;
    if (c.kind === 'task.blocked') {
      if (blocked && blocked.raw[0].taskId === e.taskId && blocked.raw[0].iteration === e.iteration) {
        const raw = [...blocked.raw, e];
        const priority = (x: TaskEnvelope) => x.type === 'ANOMALY_OPENED' ? 3 : x.type === 'AGENT_FAILED' ? 2 : x.type === 'OWNER_NEEDED' ? 1 : 0;
        if (priority(e) > Math.max(...blocked.raw.map(priority))) {
          Object.assign(blocked, c, { key: blocked.key, seq: blocked.seq, at: blocked.at, nodeId: blocked.nodeId });
        }
        blocked.raw = raw;
        continue;
      }
      blocked = c;
    }
    // 逐行审查同一轮多组各自换模型：合成一条「N 组换到了 X」
    if (e.type === 'MODEL_CALL' && c.kind === 'direction.changed' && e.data?.kind === 'review' && e.data?.runId && e.data?.fileGroupId) {
      const k = JSON.stringify([e.taskId, e.iteration, e.data.runId, e.model]); const prev = switches.get(k);
      if (prev) {
        prev.groups.add(String(e.data.fileGroupId)); prev.card.raw.push(e);
        if (prev.groups.size > 1) prev.card.title = `已切换模型：${prev.groups.size} 组换到了 ${e.model}`;
        continue;
      }
      switches.set(k, { card: c, groups: new Set([String(e.data.fileGroupId)]) });
    }
    if (c.kind === 'agent.tools' && last?.kind === 'agent.tools' && last.nodeId === c.nodeId) { last.items = [...(last.items ?? []), ...(c.items ?? [])]; last.raw.push(e); last.title = `运行了 ${last.items.length} 个工具`; continue; }
    out.push(c);
  }
  return out;
}

function endsBlockingEpisode(c: TimelineCard): boolean {
  return c.kind === 'node.entered' || c.kind === 'task.done' || c.raw.some(e =>
    ['TASK_RESUMED', 'TASK_RESTARTED', 'NODE_RETRY_REQUESTED', 'DIRECTION_CHANGED', 'PLAN_REVISED'].includes(e.type)
    || e.type === 'TASK_PAUSED' && e.data?.paused === false);
}

export interface ChatLine { id: string | number; role: 'user' | 'assistant' | 'tool' | 'reasoning'; text: string; at: string }
export type Entry = { t: 'card'; card: TimelineCard; at: string } | { t: 'chat'; msg: ChatLine; at: string } | { t: 'divider'; nodeId: string; label: string; at: string };
/** 事件卡片与对话消息按时间合并；每次进入节点前插一条分隔线 */
export function mergeTimeline(cards: TimelineCard[], chat: ChatLine[]): Entry[] {
  const all: Entry[] = [...cards.map(c => ({ t: 'card' as const, card: c, at: c.at })), ...chat.map(m => ({ t: 'chat' as const, msg: m, at: m.at }))];
  all.sort((a, b) => a.at.localeCompare(b.at) || (a.t === 'chat' ? -1 : 1));
  const out: Entry[] = [];
  for (const x of all) { if (x.t === 'card' && x.card.kind === 'node.entered' && x.card.nodeId) out.push({ t: 'divider', nodeId: x.card.nodeId, label: statusName(x.card.nodeId), at: x.at }); out.push(x); }
  return out;
}

/** 合并新到的事件（按 seq 去重、保持顺序），返回最大 seq */
export function mergeEvents(list: TaskEnvelope[], incoming: TaskEnvelope[]): { list: TaskEnvelope[]; seq: number } {
  const m = new Map(list.map(e => [e.seq, e])); for (const e of incoming) m.set(e.seq, e);
  const l = [...m.values()].sort((a, b) => a.seq - b.seq); return { list: l, seq: l.length ? l[l.length - 1].seq : 0 };
}

/** 会话里已经显示新的阻塞卡（含建议按钮）时，去掉当前这次阻塞的旧「流程卡住」卡片（最后一次进入节点 / 调整之后的） */
export function dropCurrentBlocked(cards: TimelineCard[], blockedShown: boolean): TimelineCard[] {
  if (!blockedShown) return cards;
  let cut = -1; cards.forEach((c, i) => { if (endsBlockingEpisode(c)) cut = i; });
  return cards.filter((c, i) => !(i > cut && c.kind === 'task.blocked'));
}
