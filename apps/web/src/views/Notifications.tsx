import { AppLink } from '@/components/AppLink';
import { useEffect, useState } from 'react';
import { api, fmtTime } from '../api';
import { Badge, Button, Callout, Card, EmptyState, PageHeader, PageSkeleton } from '../ui';
import { deliveryLabel, notificationsChanged, type NotificationInbox } from '../lib/notifications';

export default function Notifications() {
  const [data, setData] = useState<NotificationInbox>(); const [error, setError] = useState(''); const [busy, setBusy] = useState('');
  useEffect(() => {
    let active = true;
    const load = () => api<NotificationInbox>('/api/me/notifications').then(d => { if (active) { setData(d); setError(''); } }).catch(e => { if (active) setError(e.message); });
    load(); const timer = setInterval(load, 15000); return () => { active = false; clearInterval(timer); };
  }, []);
  const read = async (id: string) => { setBusy(id); setError(''); try { await api(`/api/me/notifications/${encodeURIComponent(id)}/read`, { method: 'PUT' }); setData(await api<NotificationInbox>('/api/me/notifications')); notificationsChanged(); } catch (e: any) { setError(e.message); } finally { setBusy(''); } };
  if (!data && !error) return <PageSkeleton title="通知中心" />;
  return <div className="space-y-gap" data-testid="notification-center">
    <PageHeader title="通知中心" desc={`当前工作区${data?.unread ? ` · ${data.unread} 条未读` : ' · 暂无未读通知'}`} actions={<div className="flex flex-wrap items-center gap-3"><AppLink href="/settings/notifications" className="text-sm text-primary-ink hover:underline">通知设置</AppLink><Button size="sm" disabled={!!busy || !data?.unread} onClick={() => read('all')}>全部标为已读</Button></div>} />
    {error && <Callout tone="danger" onClose={() => setError('')}>{error}</Callout>}
    {!data?.items.length ? <Card><EmptyState title="暂无通知" hint="订阅的任务完成、出现异常或需要你处理时，会在这里留下记录。" action={<AppLink href="/settings/notifications" className="text-primary-ink hover:underline">设置通知偏好 →</AppLink>} /></Card> : <ul className="space-y-gap">{data.items.map(n => <li key={n.id}><Card className={!n.read_at ? 'border-primary/50' : ''}><div className="flex flex-wrap justify-between gap-2"><div className="flex min-w-0 items-center gap-2">{!n.read_at && <span className="h-1.5 w-1.5 shrink-0 bg-primary" aria-label="未读" />}<h2 className="break-words text-sm font-semibold">{n.subject}</h2></div><span className="text-xs text-ink-3">{fmtTime(n.created_at)}</span></div><p className="mt-3 whitespace-pre-wrap break-words text-sm leading-6 text-ink-2">{n.message}</p><div className="mt-3 flex flex-wrap items-center gap-3"><Badge tone={n.state === 'FAILED' ? 'danger' : 'neutral'}>{deliveryLabel(n.state)}</Badge>{n.task_id && <AppLink className="text-sm text-primary-ink hover:underline" href={`/task/${encodeURIComponent(n.task_id)}`} onClick={() => { if (!n.read_at) read(n.id); }}>查看任务 →</AppLink>}{!n.read_at && <Button size="sm" variant="ghost" disabled={!!busy} onClick={() => read(n.id)}>标为已读</Button>}</div></Card></li>)}</ul>}
  </div>;
}
