import { useEffect, useState } from 'react';
import { api, fmtTime } from '../api';
import { apiUrl, authHeaders } from '../lib/auth';
import { quotaPatch, type PlatformWorkspace } from '../lib/workspace';
import { Badge, Button, Callout, Card, Field, Input, Modal, PageHeader, Table, Toggle, toast } from '../ui';

/** 平台管理：所有工作区（只有计数，不含业务数据）与配额 / 平台模型兜底开关（平台管理员） */
export default function Platform() {
  const [rows, setRows] = useState<PlatformWorkspace[] | null>(null); const [err, setErr] = useState('');
  const [edit, setEdit] = useState<PlatformWorkspace | null>(null);
  const load = () => api<PlatformWorkspace[]>('/api/platform/workspaces').then(setRows).catch(e => setErr(e.message));
  useEffect(() => { load(); }, []);
  return (
    <div className="space-y-gap" data-testid="platform-page">
      <PageHeader title="平台 · 工作区" desc="只有平台管理员可见。这里只显示计数和配额，不显示任何工作区的业务内容。" />
      {err && <Callout tone="danger">{err}</Callout>}
      <Card flush title="工作区">
        <Table caption="工作区列表" rows={rows ?? []} rowKey={w => w.id} empty={rows ? '暂无工作区' : '加载中…'} columns={[
          { key: 'n', header: '工作区', render: w => <span><span className="font-semibold">{w.name}</span> <span className="font-mono text-xs text-ink-3">{w.slug}</span></span> },
          { key: 'o', header: '所有者', render: w => w.owner ? <span className="font-mono text-xs">@{w.owner}</span> : '-' },
          { key: 's', header: '状态', render: w => <Badge tone={w.status === 'active' ? 'success' : 'danger'}>{w.status}</Badge> },
          { key: 'c', header: '成员 / 项目 / 任务', align: 'right', render: w => <span className="tabular">{w.members} / {w.projects} / {w.tasks}</span> },
          { key: 'f', header: '平台模型兜底', render: w => w.quota.llmFallback ? <Badge tone="warn">开启{w.quota.llmFallbackUsdMonth != null ? ` · $${w.quota.llmFallbackUsdMonth}/月` : ''}</Badge> : <Badge>关闭</Badge> },
          { key: 'q', header: '并发上限', align: 'right', render: w => w.quota.maxConcurrentTasks ?? '默认' },
          { key: 't', header: '创建', render: w => <span className="tabular text-ink-3">{fmtTime(w.created_at)}</span> },
          { key: 'e', header: '', render: w => <Button size="sm" onClick={() => setEdit(w)}>配额</Button> },
        ]} />
      </Card>
      <WaitlistCard />
      {edit && <QuotaDialog w={edit} onClose={() => setEdit(null)} onSaved={() => { setEdit(null); load(); }} />}
    </div>
  );
}

function QuotaDialog({ w, onClose, onSaved }: { w: PlatformWorkspace; onClose: () => void; onSaved: () => void }) {
  const [fb, setFb] = useState(!!w.quota.llmFallback); const [usd, setUsd] = useState(w.quota.llmFallbackUsdMonth?.toString() ?? ''); const [conc, setConc] = useState(w.quota.maxConcurrentTasks?.toString() ?? '');
  const [err, setErr] = useState(''); const [busy, setBusy] = useState(false);
  const save = async () => {
    setErr(''); setBusy(true);
    try { await api(`/api/platform/workspaces/${encodeURIComponent(w.id)}/quota`, { method: 'PUT', body: quotaPatch({ llmFallback: fb, usd, conc }) }); toast.success('配额已保存'); onSaved(); }
    catch (e: any) { setErr(e.message); } finally { setBusy(false); }
  };
  return (
    <Modal title={`配额 · ${w.name}`} onClose={onClose} footer={<><Button onClick={onClose}>取消</Button><Button variant="primary" loading={busy} onClick={save}>保存</Button></>}>
      <div className="space-y-3">
        {err && <Callout tone="danger">{err}</Callout>}
        <Toggle checked={fb} onChange={setFb} label="允许使用平台模型兜底" hint="开启后，这个工作区没有配置自己的模型时会用平台的模型密钥，费用记在平台账上。" />
        <Field label="平台模型月额度（美元，留空 = 不限）">{id => <Input id={id} inputMode="decimal" value={usd} onChange={e => setUsd(e.target.value)} />}</Field>
        <Field label="并发任务上限（留空 = 默认）">{id => <Input id={id} inputMode="numeric" value={conc} onChange={e => setConc(e.target.value)} />}</Field>
      </div>
    </Modal>
  );
}

/** 首页等待名单（只读 + 导出 CSV）；不会给名单发送邮件 */
function WaitlistCard() {
  const [d, setD] = useState<{ total: number; items: { email: string; source?: string; created_at: string }[] } | null>(null); const [err, setErr] = useState('');
  useEffect(() => { api('/api/platform/waitlist').then(setD).catch(e => setErr(e.message)); }, []);
  const exportCsv = async () => {
    try {
      const r = await fetch(apiUrl('/api/platform/waitlist.csv'), { headers: authHeaders(), credentials: 'include' }); if (!r.ok) throw new Error(`导出失败（${r.status}）`);
      const url = URL.createObjectURL(await r.blob()); const a = document.createElement('a'); a.href = url; a.download = 'waitlist.csv'; a.click(); URL.revokeObjectURL(url);
    } catch (e: any) { toast.error(e.message); }
  };
  return <Card flush title="等待名单" subtitle={d ? `共 ${d.total} 个邮箱（显示最近 500 个）` : '加载中…'} actions={<Button size="sm" onClick={exportCsv} disabled={!d?.total} data-testid="waitlist-export">导出 CSV</Button>}>
    {err && <Callout tone="danger">{err}</Callout>}
    <Table caption="等待名单" rows={d?.items ?? []} rowKey={x => x.email} empty={d ? '还没有人加入' : '加载中…'} columns={[
      { key: 'e', header: '邮箱', render: x => <span className="font-mono text-xs">{x.email}</span> },
      { key: 's', header: '来源', render: x => x.source ?? '-' },
      { key: 't', header: '加入时间', render: x => <span className="tabular text-ink-3">{fmtTime(x.created_at)}</span> },
    ]} />
  </Card>;
}
