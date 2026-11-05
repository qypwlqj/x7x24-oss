import { AppLink } from '@/components/AppLink';
import { useEffect, useState } from 'react';
import { api, errorDetail, fmtTime } from '../api';
import { Badge, Button, Card, Field, Textarea } from '../ui';
import { Disclosure } from '@/components/ui/collapsible';
import { refreshResource } from '../lib/resource';
import { RISK_LABEL } from '../labels';

/** 合并关卡：改动较大 / 风险高时，合并前等负责人一键确认（确认绑定当时的提交，新提交需重新确认） */
type Gate = { tier: string; tierName: string; weightedLines: number; risk: string; autonomy: string; needsApproval: boolean; reason: string };
type Approval = { id: string; tier: string; head_sha: string; status: string; decided_by?: string; decided_at?: string; note?: string; reminded_at?: string; created_at: string; summary?: any };
type GateView = { gate: Gate | null; pr: { number: number; url: string; headSha: string; files: number; lines: number; weightedLines: number } | null; approvals: Approval[]; waiting: boolean; pending: Approval | null };

const STATUS: Record<string, { name: string; tone: 'warn' | 'success' | 'danger' | 'neutral' }> = {
  pending: { name: '等你确认', tone: 'warn' }, approved: { name: '已确认', tone: 'success' }, returned: { name: '已退回修改', tone: 'danger' }, stale: { name: '有新提交，已作废', tone: 'neutral' },
};

export function MergeGateCard({ taskId, status, onChanged }: { taskId: string; status: string; onChanged: () => void }) {
  const [v, setV] = useState<GateView | null>(null); const [note, setNote] = useState(''); const [busy, setBusy] = useState<'' | 'approve' | 'return'>(''); const [err, setErr] = useState(''); const [returning, setReturning] = useState(false);
  useEffect(() => { let on = true; const load = () => refreshResource(() => api<GateView>(`/api/tasks/${taskId}/merge-gate`), x => { if (on) setV(x); }, e => { if (on) setErr(e); }); load(); const t = setInterval(load, 8000); return () => { on = false; clearInterval(t); }; }, [taskId, status]);
  if (err) return <Card title="合并确认信息加载失败"><p role="alert" className="text-danger-ink">{err}</p><p>正在重试；恢复前暂停提交审批。</p></Card>;
  if (!v || (!v.waiting && v.approvals.length === 0)) return null;
  const g = v.gate; const pr = v.pr; const head = v.pending?.head_sha ?? pr?.headSha ?? '';
  const decide = async (decision: 'approve' | 'return') => {
    if (decision === 'return' && !note.trim()) { setErr('请写明要改什么，执行助手会按你的意见返修'); return; }
    setBusy(decision); setErr('');
    try { await api(`/api/tasks/${taskId}/merge-approval`, { method: 'POST', body: { decision, headSha: head, note } }); setNote(''); setReturning(false); setV(await api<GateView>(`/api/tasks/${taskId}/merge-gate`)); onChanged(); }
    catch (e: any) { setErr([e.message, errorDetail(e)].filter(Boolean).join('：')); }
    finally { setBusy(''); }
  };
  return (
    <Card title={v.waiting ? '改动较大，合并前需要你确认' : '合并确认记录'} subtitle={v.waiting ? '已通过测试与验收。确认只对当前这次提交有效；之后再有新提交，需要重新确认。系统不会自动合并，也不会自动放弃。' : '合并关卡的确认历史'}
      className={v.waiting ? 'border-warn/60 shadow-[0_0_18px_-6px_rgb(var(--c-warn)/0.6)]' : undefined} data-testid="merge-gate-card">
      {v.waiting && g && pr && <div className="space-y-3 text-sm">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={g.tier === 'G3' ? 'danger' : 'warn'} dot>{g.tierName}改动</Badge>
          <span className="text-ink-2">约 <b className="font-mono text-ink">{g.weightedLines}</b> 行（按权重折算），{pr.files} 个文件</span>
          <Badge tone={g.risk === 'high' ? 'danger' : g.risk === 'mid' ? 'warn' : 'success'}>{(RISK_LABEL as Record<string, string>)[g.risk] ?? g.risk}</Badge>
        </div>
        <p className="text-ink-2">{g.reason}。</p>
        <div className="flex flex-wrap items-center gap-2 text-xs text-ink-3">
          <AppLink href={pr.url} target="_blank" rel="noreferrer" className="text-primary-ink hover:underline">查看改动（PR #{pr.number}）</AppLink>
          <span>当前提交 <code className="font-mono text-ink-2">{head.slice(0, 7)}</code></span>
          {v.pending?.created_at && <span>{fmtTime(v.pending.created_at)} 开始等待</span>}
          {v.pending?.reminded_at && <Badge tone="warn">已超过 72 小时，已提醒</Badge>}
        </div>
        {returning && <Field label="要改什么" hint="写清楚问题和期望，执行助手会回到开发按意见返修">{id => <Textarea id={id} rows={3} value={note} onChange={e => { setNote(e.target.value); if (err) setErr(''); }} placeholder="例如：日语文案请用敬体，登录页的按钮文字漏翻了" data-testid="merge-return-note" />}</Field>}
        {err && <p className="text-danger text-xs" role="alert">{err}</p>}
        <div className="flex flex-wrap gap-2">
          <Button variant="primary" icon="check" loading={busy === 'approve'} disabled={!!busy} onClick={() => decide('approve')} data-testid="merge-approve">确认合并</Button>
          {returning ? <><Button variant="danger" loading={busy === 'return'} disabled={!!busy} onClick={() => decide('return')} data-testid="merge-return-submit">提交退回意见</Button><Button variant="ghost" onClick={() => { setReturning(false); setErr(''); }}>取消</Button></>
            : <Button variant="secondary" disabled={!!busy} onClick={() => setReturning(true)} data-testid="merge-return">退回修改</Button>}
        </div>
      </div>}
      {v.approvals.length > 0 && <ul className={v.waiting ? 'mt-4 space-y-1 border-t border-line pt-3 text-xs' : 'space-y-1 text-xs'}>
        {v.approvals.map(a => <li key={a.id} className="flex flex-wrap items-center gap-2 text-ink-3">
          <Badge tone={STATUS[a.status]?.tone ?? 'neutral'}>{STATUS[a.status]?.name ?? a.status}</Badge>
          <code className="font-mono">{a.head_sha.slice(0, 7)}</code><span>{fmtTime(a.decided_at ?? a.created_at)}</span>
          {a.decided_by && <span>· {a.decided_by}</span>}{a.note && <span className="text-ink-2">· {a.note}</span>}
        </li>)}
      </ul>}
      <Disclosure className="mt-3" summary={<span className="text-xs text-ink-3">详情</span>}>
        <pre className="mt-2 max-h-60 overflow-auto rounded bg-sunken p-2 font-mono text-[11px] text-ink-3">{JSON.stringify({ gate: g, pr, approvals: v.approvals }, null, 2)}</pre>
      </Disclosure>
    </Card>
  );
}
