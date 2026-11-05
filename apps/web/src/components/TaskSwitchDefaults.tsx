import { useState } from 'react';
import { api, ApiError, type Meta } from '../api';
import { Callout, Card, Select, Toggle } from '../ui';

type Tri = 'inherit' | 'on' | 'off';
const tri = (v: boolean | null | undefined): Tri => (v == null ? 'inherit' : v ? 'on' : 'off');
const TRI_TEXT: Record<Tri, string> = { inherit: '跟随默认', on: '打开', off: '关闭' };

/**
 * 任务预算开关的默认值（预算页）：工作区默认 + 每个项目可单独设置。
 * 「不限预算」仍记录花费，并有开发轮次安全上限；「省钱模式」只由预算触发，关掉后预算用完会暂停等负责人。
 */
export function TaskSwitchDefaults({ cfg, meta, onSaved }: { cfg: any; meta: Meta | null; onSaved: (msg: string) => Promise<void> }) {
  const g = cfg?.budgets?.task ?? {}; const [busy, setBusy] = useState(''); const [err, setErr] = useState('');
  const put = async (key: string, body: Record<string, unknown>, msg: string) => {
    setBusy(key); setErr('');
    try { await api('/api/budget/task-switches', { method: 'PUT', body }); await onSaved(msg); }
    catch (e: unknown) { setErr(e instanceof ApiError && e.status === 403 && (e.code === 'forbidden' || e.code === 'read_only') ? '只有工作区所有者或管理员可以修改预算设置。' : e instanceof Error ? e.message : String(e)); }
    finally { setBusy(''); }
  };
  const projects = (meta?.projects ?? []).filter(p => p.state !== 'ARCHIVED');
  const select = (pid: string, field: 'unlimited' | 'degrade', cur: Tri) => (
    <Select aria-label={field === 'unlimited' ? '不限预算' : '省钱模式'} className="h-8 w-32 text-xs" value={cur} disabled={!!busy}
      onChange={e => { const v = e.target.value as Tri; put(pid + field, v === 'inherit' ? { scope: 'project', id: pid, [field === 'unlimited' ? 'clearUnlimited' : 'clearDegrade']: true } : { scope: 'project', id: pid, [field]: v === 'on' }, '项目的任务预算设置已保存'); }}>
      {(['inherit', 'on', 'off'] as Tri[]).map(o => <option key={o} value={o}>{TRI_TEXT[o]}</option>)}
    </Select>
  );
  return <Card title="任务预算开关" subtitle="对没有单独设置的任务立即生效；单个任务也可以在任务详情里单独设置">
    <div className="space-y-3" data-testid="task-switch-defaults">
      <Toggle checked={!!g.unlimited} disabled={!!busy} onChange={v => put('g-u', { scope: 'governance', unlimited: v }, '默认设置已保存')} label="默认不限预算"
        hint={`金额、Token、CI 分钟都不再拦截任务，花费照常记录；开发做到 ${g.safetyMaxIterations ?? 150} 轮会暂停等负责人确认，防止无限循环。`} />
      <Toggle checked={g.degrade !== false} disabled={!!busy} onChange={v => put('g-d', { scope: 'governance', degrade: v }, '默认设置已保存')} label="默认在预算用完时改用省钱模式"
        hint="省钱模式只由预算触发：先换个做法，再缩小范围。关掉后，预算用完时任务暂停等负责人处理。返修次数用完不会进入省钱模式。" />
      {projects.length > 0 && <table className="w-full text-sm"><caption className="sr-only">各项目的任务预算开关</caption>
        <thead><tr className="text-left text-xs text-ink-3"><th className="py-1 font-normal">项目</th><th className="py-1 font-normal">不限预算</th><th className="py-1 font-normal">省钱模式</th></tr></thead>
        <tbody>{projects.map(p => { const pb: any = p.config.budget ?? {}; return <tr key={p.id} className="border-t border-line"><td className="py-1.5 text-ink">{p.name}</td><td>{select(p.id, 'unlimited', tri(pb.taskUnlimited))}</td><td>{select(p.id, 'degrade', tri(pb.taskDegrade))}</td></tr>; })}</tbody>
      </table>}
      <div className="text-xs text-ink-3">只有工作区所有者或管理员可以修改，每次修改都会记入审计。</div>
      {err && <Callout tone="danger" onClose={() => setErr('')}>{err}</Callout>}
    </div>
  </Card>;
}
