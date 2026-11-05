import { useState } from 'react';
import { api, ApiError, type TaskDTO } from '../api';
import { Callout, Card, Progress, Toggle } from '../ui';

const FROM: Record<string, string> = { task: '这个任务单独设置', project: '跟随项目默认', governance: '跟随治理默认' };

/** 任务详情的预算卡片：用量 + 两个开关（不限预算 / 预算用完改用省钱模式）。只有所有者 / 管理员能改，改动记入审计 */
export function TaskBudgetCard({ t, onChanged, canManageBudget = false }: { t: TaskDTO; onChanged: () => Promise<void>; canManageBudget: boolean }) {
  const sw = t.budgetSwitches; const [busy, setBusy] = useState(false); const [err, setErr] = useState('');
  const ended = t.status === 'RELEASED' || t.status === 'DISCARDED';
  const save = async (body: Record<string, boolean | null>) => {
    if (!canManageBudget || busy || ended) return;
    setBusy(true); setErr('');
    try { await api(`/api/tasks/${t.id}/budget-switches`, { method: 'PATCH', body }); await onChanged(); }
    catch (e: unknown) { setErr(e instanceof ApiError && e.status === 403 && (e.code === 'forbidden' || e.code === 'read_only') ? '只有工作区所有者或管理员可以修改预算设置。' : e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };
  const off = !!sw?.budgetOff;
  return <Card title="预算消耗"><div className="space-y-3 text-sm" data-testid="task-budget">
    <div><div className="mb-1 flex justify-between text-xs text-ink-2"><span>金额</span><span className="tabular">${t.spentUsd} / {off ? '不限' : `$${t.budgetUsd}`}</span></div>{off ? <div className="text-xs text-ink-3">不限预算：仍然记录每一笔花费</div> : <Progress label="金额预算" value={t.spentUsd} max={t.budgetUsd} />}</div>
    <div><div className="mb-1 flex justify-between text-xs text-ink-2"><span>Token</span><span className="tabular">{t.spentTokens.toLocaleString()} / {off ? '不限' : t.budgetTokens.toLocaleString()}</span></div>{!off && <Progress label="Token 预算" value={t.spentTokens} max={t.budgetTokens} />}</div>
    {sw && <div className="space-y-2 border-t border-line pt-3">
      <Toggle checked={off} disabled={!canManageBudget || busy || ended} onChange={v => save({ budgetOff: v })} label="不限预算"
        hint={<>{FROM[sw.budgetFrom]}。打开后金额、Token、CI 分钟都不再拦截，花费照常记录；开发做到 {sw.safetyMaxIterations} 轮会暂停等你确认，防止无限循环。</>} />
      <Toggle checked={!sw.degradeOff} disabled={!canManageBudget || busy || ended || off} onChange={v => save({ degradeOff: !v })} label="预算用完时改用省钱模式"
        hint={<>{FROM[sw.degradeFrom]}。{sw.degradeOff ? '已关闭：预算用完时任务暂停等你处理，不会自动换方案或缩小范围。' : '预算用完时先换个做法、再缩小范围；只由预算触发，和返修次数无关。'}</>} />
      {(sw.budgetFrom === 'task' || sw.degradeFrom === 'task') && !ended && <button type="button" className="text-xs text-primary-ink underline" disabled={!canManageBudget || busy} onClick={() => save({ budgetOff: null, degradeOff: null })}>恢复为项目默认</button>}
      <div className="text-xs text-ink-3">只有工作区所有者或管理员可以修改，每次修改都会记入审计。</div>
    </div>}
    {err && <Callout tone="danger" onClose={() => setErr('')}>{err}</Callout>}
  </div></Card>;
}
