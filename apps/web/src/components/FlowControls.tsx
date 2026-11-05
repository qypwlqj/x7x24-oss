import { AppLink } from '@/components/AppLink';
/** 指挥台 M3：阻塞卡（原因 / 证据 / 建议 + 快捷按钮）、确认卡（高危二次确认）、输入框（自由文本 → 提议）。只读成员只看得到说明和提问 */
import { useState, type ReactNode } from 'react';
import { Button, Input, Textarea, cx } from '../ui';
import { NodeIcon } from '../ui/NodeIcon';
import { DANGER_ZH, causeText, failureCauseText, errText, paramLines, resumeDescription, type ActionKind, type ActionState, type TaskAction, type useTaskActions } from '../lib/taskActions';
import { fmtFull } from '../api';
import { Disclosure } from './ui/collapsible';
import { buttonVariants } from './ui/button';

type Api = ReturnType<typeof useTaskActions>;
const DANGER_CLS = { low: 'border-line-strong text-ink-2', mid: 'border-warn text-warn-ink', high: 'border-danger text-danger-ink' } as const;

export function BlockedCard({ s, act, onProposed }: { s: ActionState; act: Api; onProposed: (e?: string) => void }) {
  const [busy, setBusy] = useState<string | null>(null);
  if (!s.blocked) return null;
  const restriction = s.resumeBlocker;
  const budgetCleared = restriction === null && (s.cause === 'budget_paused' || s.cause === 'safety_cap');
  const title = restriction ? causeText(restriction.code) : budgetCleared ? '预算限制已解除' : causeText(s.cause);
  const detail = restriction?.detail ?? (budgetCleared ? '可以从保存的节点继续执行，已有计划和分段进度会保留。' : s.detail);
  const go = async (kind: ActionKind, params: Record<string, any>) => { setBusy(kind); try { await act.propose(kind, params); onProposed(); } catch (e) { onProposed(errText(e)); } finally { setBusy(null); } };
  return <div className="border-2 border-danger bg-danger-soft/50 p-3 shadow-[3px_3px_0_rgb(var(--c-line-strong))]" data-testid="blocked-card">
    <div className="flex items-center gap-2"><span className="grid h-8 w-8 place-items-center rounded-sm border-[1.5px] border-ink bg-raised text-danger-ink"><NodeIcon name="blocked" size={18} /></span><b className="flex-1 text-danger-ink">{title} · 停在「{s.node}」</b></div>
    {s.recovery?.checkpoint && <p className="mt-2 text-sm text-ink-2" data-testid="saved-progress">进度已保存{s.recovery.checkpoint.phase === 'DEV' && s.recovery.checkpoint.segmentIndex != null ? ` · 当前第 ${s.recovery.checkpoint.segmentIndex + 1} 段` : ''}；{s.recovery.rework ? '已有计划和提交保留。' : '恢复后继续当前步骤，保留已有计划和提交。'}</p>}
    {s.recovery?.rework && !restriction && <p className="mt-2 text-sm text-ink-2" data-testid="rework-resume">{resumeDescription(s.recovery)}<span className="mt-1 block text-caption text-ink-3">本轮额度已用：{Math.min(s.recovery.rework.used, s.recovery.rework.limit)} / {s.recovery.rework.limit}；历史累计计数：{s.recovery.rework.total}。</span></p>}
    {detail && <p className="mt-1.5 text-sm text-ink-2">{detail}</p>}
    {restriction && restriction.code !== s.cause && <p className="mt-1.5 text-caption text-ink-3">本次停止原因：{causeText(s.cause)}。</p>}
    {!!s.evidence?.length && <ul className="mt-2 space-y-0.5 border-l-2 border-dashed border-danger pl-2.5 font-mono text-[12px] text-ink-2" data-testid="blocked-evidence">{s.evidence.map((e, i) => <li key={i}>{e}</li>)}</ul>}
    {s.guide && <div className="mt-3"><AppLink className={s.guide.recommended ? buttonVariants({ size: 'sm', variant: 'default' }) : 'inline-block text-sm underline'} href={s.guide.href} data-testid={restriction ? 'budget-guide' : undefined}>{s.guide.label} →</AppLink>{s.guide.recommended && <span className="ml-2 border border-success px-1 text-caption text-success-ink">推荐</span>}{restriction && !s.can.admin && <p className="mt-1 text-caption text-ink-3">调整需要工作区管理员。</p>}</div>}
    <div className="mt-3 space-y-1.5" data-testid="blocked-suggestions">
      {s.suggestions.map((g, i) => <div key={i} className="flex flex-wrap items-center gap-2 border border-dashed border-ink bg-raised px-2 py-1.5 text-sm">
        <span className="font-bold">{i + 1}.</span><span className="flex-1">{g.title}{g.recommended && <span className="ml-1.5 border border-success px-1 text-caption text-success-ink">推荐</span>}<span className="ml-1.5 text-caption text-ink-3">{g.why}</span></span>
        {s.can.act ? <Button size="sm" variant={g.recommended ? 'primary' : 'secondary'} loading={busy === g.kind} onClick={() => go(g.kind, g.params)} data-testid="suggestion-btn">{g.minRole === 'admin' && !s.can.admin ? '提交给管理员' : '用这个'}</Button>
          : <span className="text-caption text-ink-3">只读</span>}
      </div>)}
    </div>
    {!!s.failureHistory?.length && <Disclosure className="mt-3 text-sm" summary={<>历史失败记录（{s.failureHistory.length}）</>} summaryClassName="text-ink-3" data-testid="failure-history">
      <p className="mt-2 text-caption text-ink-3">以下为过去的执行记录，当前能否恢复以上方检查为准。</p>
      <ul className="mt-2 space-y-2 border-l-2 border-dashed border-line pl-2.5">{s.failureHistory.map((f, i) => <li key={i}>
        <div className="flex flex-wrap gap-2 text-caption"><b>{failureCauseText(f.code)}</b>{f.at && <time className="text-ink-3" dateTime={f.at}>{fmtFull(f.at)} UTC</time>}</div>
        {f.detail && f.detail !== f.code && <p className="mt-0.5 text-caption text-ink-2">{f.detail}</p>}
      </li>)}</ul>
    </Disclosure>}
    {!s.can.act && <p className="mt-2 text-caption text-ink-3" data-testid="readonly-note">你是只读成员：可以在下面提问，操作需要成员或管理员。</p>}
  </div>;
}

export function ConfirmCard({ a, act, can, onDone }: { a: TaskAction; act: Api; can: ActionState['can']; onDone: (msg: string, tone: 'ok' | 'err') => void }) {
  const [word, setWord] = useState(''); const [busy, setBusy] = useState(false); const [err, setErr] = useState<string | null>(null);
  const needAdmin = a.minRole === 'admin' && !can.admin;
  const ok = async () => { setBusy(true); setErr(null); try { const r = await act.confirm(a.id, a.confirmWord ? word : undefined); onDone(`已执行「${r.label}」`, 'ok'); } catch (e) { const m = errText(e); setErr(m); onDone(m, 'err'); } finally { setBusy(false); } };
  return <div className={cx('border-2 bg-raised p-3', a.danger === 'high' ? 'border-danger' : 'border-ink')} data-testid="confirm-card" data-kind={a.kind}>
    <div className="flex flex-wrap items-center gap-2"><NodeIcon name={a.danger === 'high' ? 'blocked' : 'direction'} size={16} /><b className="flex-1">确认操作：{a.label}</b>
      <span className={cx('border px-1.5 text-caption', DANGER_CLS[a.danger])}>{DANGER_ZH[a.danger]}</span>
      <span className="text-caption text-ink-3">{a.source === 'text' ? '从你的话里理解出来的' : '来自建议'} · @{a.proposedBy}</span></div>
    <ul className="mt-1.5 list-disc pl-5 text-sm text-ink-2">{paramLines(a).map((l, i) => <li key={i}>{l}</li>)}</ul>
    {a.kind === 'repository_initialize_and_resume' && <pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap border border-line bg-sunken p-2 text-sm" aria-label="README 首次提交内容">{a.params.content}</pre>}
    {needAdmin ? <p className="mt-2 text-sm text-warn-ink" data-testid="needs-admin">这个操作需要工作区管理员确认，已放进管理员的「待我处理」。</p>
      : a.confirmWord && <div className="mt-2 text-sm">这是高风险操作，请输入「<b>{a.confirmWord}</b>」确认：<Input className="ml-2 inline-block w-28" value={word} onChange={e => setWord(e.target.value)} aria-label={`输入${a.confirmWord}确认`} data-testid="confirm-word" /></div>}
    {err && <p className="mt-2 border border-danger bg-danger-soft px-2 py-1 text-sm text-danger-ink" role="alert" data-testid="action-error">{err}</p>}
    {can.act && <div className="mt-2 flex gap-2">
      {!needAdmin && <Button size="sm" variant={a.danger === 'high' ? 'danger' : 'primary'} loading={busy} disabled={!!a.confirmWord && word.trim() !== a.confirmWord} onClick={ok} data-testid="confirm-btn">确认执行</Button>}
      <Button size="sm" variant="ghost" onClick={() => act.cancel(a.id).then(() => onDone('已取消', 'ok')).catch(e => setErr(errText(e)))}>取消</Button>
    </div>}
  </div>;
}

const QUICK: { kind: ActionKind; label: string; when: (s: ActionState) => boolean; params?: Record<string, any> }[] = [
  { kind: 'task_resume', label: '恢复执行', when: s => !s.resumeBlocker && s.status !== 'DISCARDED' && (s.paused || s.blocked || s.status === 'ANOMALY') },
  { kind: 'node_retry', label: '重试当前节点', when: s => s.blocked && !s.resumeBlocker && !['RELEASED', 'DISCARDED', 'DELIVERED'].includes(s.status) },
  { kind: 'task_restore', label: '恢复原进度', when: s => s.status === 'DISCARDED' && s.canRestore === true },
];
export function Composer({ s, act, onSaid, status }: { s: ActionState; act: Api; onSaid: (r: { text: string; reply: string; err?: boolean }) => void; status?: ReactNode }) {
  const [text, setText] = useState(''); const [busy, setBusy] = useState(false);
  const send = async () => { const t = text.trim(); if (!t) return; setBusy(true); try { const r = await act.say(t); setText(''); onSaid({ text: t, reply: r.reply }); } catch (e) { onSaid({ text: t, reply: errText(e), err: true }); } finally { setBusy(false); } };
  const quick = async (k: ActionKind, p: Record<string, any> = {}) => { try { await act.propose(k, p); } catch (e) { onSaid({ text: '', reply: errText(e), err: true }); } };
  return <div className="shrink-0 border-t-2 border-dashed border-line-strong p-3" data-testid="cc-composer">
    {s.can.act && QUICK.some(q => q.when(s)) && <div className="mb-2 flex flex-wrap gap-1.5" data-testid="quick-actions">{QUICK.filter(q => q.when(s)).map(q => <Button key={q.kind} size="sm" variant="secondary" onClick={() => quick(q.kind, q.params)}>{q.label}</Button>)}</div>}
    <div className="flex items-end gap-2">
      <Textarea rows={2} className="min-w-0 flex-1" value={text} onChange={e => setText(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey) && !e.nativeEvent.isComposing) { e.preventDefault(); if (!busy) void send(); } }}
        placeholder={s.can.act ? '补充要求，或询问任务进度…' : '只读成员可以提问：为什么卡住？这一步在干嘛？'} aria-label="给 agent 发消息" data-testid="composer-input" />
      <Button variant="primary" loading={busy} onClick={send} data-testid="composer-send">发送</Button>
    </div>
    <div className="mt-1 flex flex-wrap items-center justify-between gap-2 text-caption text-ink-3"><span>⌘ / Ctrl + Enter 发送 · 操作会先出确认卡</span>{status}</div>
  </div>;
}
