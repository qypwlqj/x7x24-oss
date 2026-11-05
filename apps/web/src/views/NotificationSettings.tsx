import { AppLink } from '@/components/AppLink';
import { useEffect, useState } from 'react';
import { api, fmtTime, type ProjectDTO } from '../api';
import { Badge, Button, Callout, Card, Field, FormBar, Input, PageHeader, PageSkeleton, Select, Toggle, toast } from '../ui';
import { Checkbox } from '@/components/ui/misc';
import { deliveryLabel, NOTIFICATION_EVENTS, preferencesOf, type NotificationInbox, type NotificationPreferences, type NotificationSettings as Settings } from '../lib/notifications';

export default function NotificationSettings({ projects }: { projects: ProjectDTO[] }) {
  const [data, setData] = useState<Settings>(); const [draft, setDraft] = useState<NotificationPreferences>();
  const [email, setEmail] = useState(''); const [code, setCode] = useState(''); const [busy, setBusy] = useState(''); const [error, setError] = useState('');
  const [history, setHistory] = useState<NotificationInbox>();
  const loadHistory = () => api<NotificationInbox>('/api/me/notifications?history=true').then(setHistory);
  useEffect(() => {
    let active = true;
    api<Settings>('/api/me/notifications/settings').then(s => { if (active) { setData(s); setDraft(preferencesOf(s)); setEmail(s.email); } }).catch(e => { if (active) setError(e.message); });
    loadHistory().catch(e => { if (active) setError(e.message); });
    const timer = setInterval(() => loadHistory().catch(() => {}), 15000);
    return () => { active = false; clearInterval(timer); };
  }, []);
  const run = async (name: string, fn: () => Promise<void>) => { setBusy(name); setError(''); try { await fn(); await loadHistory(); } catch (e: any) { setError(e.message); } finally { setBusy(''); } };
  const emailAction = (name: string, path: string, body?: unknown) => run(name, async () => {
    const s = await api<Settings>(`/api/me/notifications/${path}`, { method: name === 'email' ? 'PUT' : 'POST', ...(body !== undefined ? { body } : {}) });
    setData(s); setEmail(s.email); setCode('');
    if (!s.verified) setDraft(prev => prev ? { ...prev, emailEnabled: false } : prev);
    toast.success(name === 'verify' ? '邮箱已验证，可开启邮件通知' : name === 'code' ? '验证邮件已提交，请查收邮件中的验证码' : '收件邮箱已保存');
  });
  if (!data || !draft) return error ? <Callout tone="danger">{error}</Callout> : <PageSkeleton title="通知设置" />;
  const dirty = JSON.stringify(preferencesOf(draft)) !== JSON.stringify(preferencesOf(data));
  const selectedError = draft.scope === 'projects' && draft.projectIds.length === 0 ? ['请选择至少一个项目'] : [];
  return <div className="space-y-gap" data-testid="notification-settings">
    <PageHeader title="个人通知设置" desc="收件邮箱按账号保存；通知事件和订阅项目仅对当前工作区生效" actions={<AppLink className="text-sm text-primary-ink hover:underline" href="/notifications">查看通知中心 →</AppLink>} />
    {error && <Callout tone="danger" onClose={() => setError('')}>{error}</Callout>}
    {!data.mailReady && <Callout tone="warn">系统尚未配置发信服务，站内通知可以正常使用。请联系管理员配置发信服务和站点地址后，再验证邮箱与发送测试邮件。</Callout>}
    <Card title="收件邮箱" subtitle="系统统一发信，你只需要填写接收通知的邮箱">
      <div className="max-w-2xl space-y-4">
        <Field label="邮箱地址" hint="更换或移除邮箱后，需要重新验证；之前排队的邮件会在发送前重新核对。">{id => <div className="flex flex-wrap gap-2"><Input id={id} type="email" autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} placeholder="name@example.com" className="min-w-0 flex-1" /><Button disabled={!!busy || email.trim() === data.email} loading={busy === 'email'} onClick={() => emailAction('email', 'email', { email })}>保存邮箱</Button></div>}</Field>
        <div className="flex flex-wrap items-center gap-2"><Badge tone={data.verified ? 'success' : 'neutral'}>{data.verified ? '已验证' : data.email ? '待验证' : '尚未填写邮箱'}</Badge>{data.email && <span className="break-all text-xs text-ink-3">{data.email}</span>}
          {!data.verified && <Button size="sm" disabled={!!busy || !data.email || email.trim() !== data.email || !data.mailReady} loading={busy === 'code'} onClick={() => emailAction('code', 'email/send-code')}>{data.codeExpiresAt ? '重新发送验证码' : '发送验证码'}</Button>}
        </div>
        {!data.verified && data.codeExpiresAt && <Field label="邮箱验证码" hint="验证码 10 分钟内有效，重新发送需间隔一分钟。">{id => <div className="flex flex-wrap gap-2"><Input id={id} className="w-44" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={e => setCode(e.target.value)} placeholder="6 位验证码" /><Button variant="primary" disabled={!!busy || !/^\d{6}$/.test(code)} loading={busy === 'verify'} onClick={() => emailAction('verify', 'email/verify', { code })}>验证邮箱</Button></div>}</Field>}
        <Button size="sm" disabled={!!busy || !data.verified || !data.mailReady} loading={busy === 'test'} onClick={() => run('test', async () => { await api('/api/me/notifications/test', { method: 'POST' }); toast.success('测试邮件已提交，可在最近通知中查看发送状态'); })}>发送测试邮件</Button>
      </div>
    </Card>
    <Card title="当前工作区的通知偏好" subtitle="只有订阅范围内的事件会通知你，退出工作区后停止投递">
      <div className="space-y-5">
        <div className="grid gap-4 sm:grid-cols-2"><Toggle label="站内通知" checked={draft.inAppEnabled} onChange={v => setDraft({ ...draft, inAppEnabled: v })} hint="在通知中心查看消息与未读状态。" /><Toggle label="邮件通知" checked={draft.emailEnabled} disabled={!data.verified} onChange={v => setDraft({ ...draft, emailEnabled: v })} hint={data.verified ? '发送到已验证的收件邮箱。' : '验证收件邮箱后可以开启。'} /></div>
        <Field label="订阅范围">{id => <Select id={id} className="max-w-sm" value={draft.scope} onChange={e => setDraft({ ...draft, scope: e.target.value as 'workspace' | 'projects' })}><option value="workspace">整个工作区</option><option value="projects">指定项目</option></Select>}</Field>
        {draft.scope === 'projects' && <div className="grid gap-2 sm:grid-cols-2" role="group" aria-label="订阅项目">{projects.length ? projects.map(p => <label key={p.id} className="flex items-center gap-2 border border-line bg-sunken p-3 text-sm"><Checkbox aria-label={p.name} checked={draft.projectIds.includes(p.id)} onCheckedChange={checked => setDraft({ ...draft, projectIds: checked === true ? [...draft.projectIds, p.id] : draft.projectIds.filter(id => id !== p.id) })} /><span>{p.name}</span></label>) : <p className="text-sm text-ink-3">当前工作区暂无项目。</p>}</div>}
        <div className="grid gap-4 border-t border-line pt-4 sm:grid-cols-2">{NOTIFICATION_EVENTS.map(e => <Toggle key={e.id} label={e.label} hint={e.hint} checked={draft.events[e.id]} onChange={v => setDraft({ ...draft, events: { ...draft.events, [e.id]: v } })} />)}</div>
        <FormBar dirty={dirty} busy={!!busy} errors={selectedError} onReset={() => setDraft(preferencesOf(data))} onSave={() => run('settings', async () => {
          const s = await api<Settings>('/api/me/notifications/settings', { method: 'PUT', body: preferencesOf(draft) }); setData(s); setDraft(preferencesOf(s)); toast.success('通知偏好已保存');
        })} />
      </div>
    </Card>
    <Card title="最近通知与发送记录" subtitle="提交发送后由后台处理，发送失败时自动重试">
      {!history?.items.length ? <p className="text-sm text-ink-3">暂无通知记录。</p> : <ul className="divide-y divide-line">{history.items.map(n => <li key={n.id} className="py-3 first:pt-0 last:pb-0"><div className="flex flex-wrap justify-between gap-2"><span className="text-sm text-ink">{n.subject}</span><Badge tone={n.state === 'FAILED' ? 'danger' : n.state === 'SENT' ? 'success' : 'neutral'}>{deliveryLabel(n.state)}</Badge></div><p className="mt-1 text-xs text-ink-3">{fmtTime(n.created_at)}{n.attempts > 0 ? ` · 已尝试 ${n.attempts} 次` : ''}</p>{n.last_error && <p className="mt-1 text-xs text-ink-2">{n.last_error}</p>}</li>)}</ul>}
    </Card>
  </div>;
}
