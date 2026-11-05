import { useEffect, useState } from 'react';
import { api, fmtTime } from '../api';
import { EVT } from '../labels';
import { Badge, Button, Callout, Card, PageHeader, Select, Table } from '../ui';

const TYPES = ['', 'STATUS_CHANGED', 'VERDICT', 'ADAPTER_CALL', 'ROLLBACK', 'ACTION_DENIED', 'KILL_SWITCH', 'BUDGET_HIT', 'AUTONOMY_CHANGED', 'CONFIG_UPDATED', 'SELF_HEAL', 'DEGRADED', 'POLICY_VIOLATION'];

export default function Audit() {
  const [type, setType] = useState(''); const [offset, setOffset] = useState(0);
  const [data, setData] = useState<any>(null); const [verify, setVerify] = useState<any>(null); const limit = 50;
  useEffect(() => { api(`/api/audit?limit=${limit}&offset=${offset}${type ? `&type=${type}` : ''}`).then(setData); }, [type, offset]);
  return (
    <div className="space-y-gap" data-testid="audit">
      <PageHeader title="审计日志" desc="只追加、哈希链；数据库触发器禁止修改与删除" actions={
        <div className="flex flex-wrap items-center gap-2">
          <Select aria-label="事件类型" value={type} onChange={(e) => { setType(e.target.value); setOffset(0); }} className="w-44">{TYPES.map((t) => <option key={t} value={t}>{t ? EVT[t] ?? t : '全部类型'}</option>)}</Select>
          <Button variant="primary" icon="shield" onClick={() => api('/api/audit/verify').then(setVerify)}>校验哈希链</Button>
        </div>} />
      {verify && <Callout tone={verify.ok ? 'success' : 'danger'} icon={verify.ok ? 'check' : 'alert'}>{verify.ok ? `链完整，已校验 ${verify.checked} 条` : `在 #${verify.brokenAt} 断裂：${verify.reason}`}</Callout>}
      <Card flush actions={data && <span className="text-xs text-ink-3">共 {data.total} 条 · 链头 <span className="font-mono">{data.head.hash.slice(0, 12)}…</span></span>} title="事件">
        <Table caption="审计事件" dense rows={data?.items ?? []} rowKey={(e: any) => e.id} columns={[
          { key: 'id', header: '#', render: (e: any) => <span className="tabular text-ink-3">{e.id}</span> },
          { key: 't', header: '时间', render: (e: any) => <span className="tabular text-ink-2">{fmtTime(e.occurred_at)}</span> },
          { key: 'ty', header: '类型', render: (e: any) => <Badge tone={e.event_type === 'ACTION_DENIED' || e.event_type === 'POLICY_VIOLATION' ? 'danger' : e.event_type === 'ADAPTER_CALL' ? 'info' : 'neutral'}>{EVT[e.event_type] ?? e.event_type}</Badge> },
          { key: 'k', header: '任务', render: (e: any) => <span className="font-mono text-xs text-ink-3">{e.task_id?.slice(0, 6) ?? '-'}</span> },
          { key: 'm', header: '迁移', render: (e: any) => <span className="text-ink-2">{e.from_status ? `${e.from_status}→${e.to_status}` : ''} {e.edge && <Badge tone="primary">{e.edge}</Badge>}</span> },
          { key: 'a', header: '执行者', render: (e: any) => e.actor },
          { key: 's', header: '摘要', className: 'max-w-[300px] truncate text-ink-3', render: (e: any) => (e.payload ? JSON.stringify(e.payload).slice(0, 90) : '') },
          { key: 'h', header: '哈希', render: (e: any) => <span className="font-mono text-caption text-ink-3">{e.prev_hash.slice(0, 6)}→{e.hash.slice(0, 8)}</span> },
        ]} />
      </Card>
      <div className="flex gap-2"><Button disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - limit))}>上一页</Button><Button disabled={!data || offset + limit >= data.total} onClick={() => setOffset(offset + limit)}>下一页</Button></div>
    </div>
  );
}
