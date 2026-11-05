import { appPath } from '../lib/navigation';
import { ComposableSettings } from '../components/ComposableSettings';
import { useEffect, useState } from 'react';
import { api, fmtTime } from '../api';
import { setWorkspace } from '../lib/auth';
import { assignableRoles, canManage, inviteLink, roleLabel, type WorkspaceInfo } from '../lib/workspace';
import { Badge, Button, Callout, Card, Field, Input, PageHeader, Select, Table, toast, useConfirm } from '../ui';

interface Member { login: string; name: string | null; avatar_url: string | null; role: string; created_at: string }
interface Invite { id: string; role: string; login: string | null; created_at: string; expires_at: string; accepted_at: string | null; revoked_at: string | null; created_by: string | null; accepted_by: string | null }

/** 工作区成员：所有成员可见；所有者 / 管理员可以邀请、改角色、移出；所有者可以转让 */
export default function Workspace({ current, me }: { current: WorkspaceInfo | null; me?: string | null }) {
  const [rows, setRows] = useState<Member[] | null>(null); const [invites, setInvites] = useState<Invite[]>([]); const [err, setErr] = useState('');
  const [role, setRole] = useState('member'); const [login, setLogin] = useState(''); const [link, setLink] = useState('');
  const [transferTo, setTransferTo] = useState('');
  const confirm = useConfirm();
  const manage = canManage(current?.role); const owner = current?.role === 'owner';
  const load = () => {
    api<Member[]>('/api/workspace/members').then(setRows).catch(e => setErr(e.message));
    if (manage) api<Invite[]>('/api/workspace/invites').then(setInvites).catch(() => setInvites([]));
  };
  useEffect(load, [current?.id]);
  const run = async (f: () => Promise<unknown>, ok: string) => { setErr(''); try { await f(); toast.success(ok); load(); } catch (e: any) { setErr(e.message); } };
  const invite = () => run(async () => {
    const r = await api<{ token: string }>('/api/workspace/invites', { method: 'POST', body: { role, login: login.trim() || null } });
    const l = inviteLink(location, r.token); setLink(l); setLogin('');
    try { await navigator.clipboard.writeText(l); } catch { /* ignore */ }
  }, '邀请已创建');
  const leave = async () => {
    if (!(await confirm({ title: '退出工作区', description: `确定退出「${current?.name}」？退出后需要重新被邀请才能回来。`, confirmText: '退出', destructive: true }))) return;
    try { await api('/api/me/leave', { method: 'POST' }); setWorkspace(null); location.assign(appPath('/overview')); } catch (e: any) { setErr(e.message); }
  };
  const isMe = (m: Member) => !!me && m.login.toLowerCase() === me.toLowerCase();
  return (
    <div className="space-y-gap" data-testid="workspace-page">
      <PageHeader title="工作区成员" desc={current ? <>当前工作区 <b>{current.name}</b>（{current.slug}）· 你的角色：{roleLabel(current.role)}</> : undefined}
        actions={current && !owner && me ? <Button variant="danger" onClick={leave}>退出工作区</Button> : undefined} />
      {err && <Callout tone="danger" onClose={() => setErr('')}>{err}</Callout>}
      <Card flush title="成员" subtitle="工作区之间的产品线、项目、任务、审计、预算和集成配置完全隔离。">
        <Table caption="工作区成员" rows={rows ?? []} rowKey={m => m.login} empty={rows ? '暂无成员' : '加载中…'} columns={[
          { key: 'u', header: '成员', render: m => <span className="flex items-center gap-2">{m.avatar_url ? <img src={m.avatar_url} alt="" className="h-6 w-6 border border-line" /> : null}<span className="font-semibold">{m.name || m.login}</span><span className="font-mono text-xs text-ink-3">@{m.login}</span>{isMe(m) && <Badge tone="primary">你</Badge>}</span> },
          { key: 'r', header: '角色', render: m => {
            const opts = assignableRoles(current?.role, m.role, isMe(m));
            return opts.length ? <Select aria-label={`${m.login} 的角色`} value={m.role} className="w-28" onChange={e => run(() => api(`/api/workspace/members/${encodeURIComponent(m.login)}`, { method: 'PUT', body: { role: e.target.value } }), '角色已更新')}>{opts.map(o => <option key={o} value={o}>{roleLabel(o)}</option>)}</Select>
              : <Badge tone={m.role === 'owner' ? 'primary' : m.role === 'viewer' ? 'neutral' : 'info'}>{roleLabel(m.role)}</Badge>;
          } },
          { key: 't', header: '加入时间', render: m => <span className="tabular text-ink-3">{fmtTime(m.created_at)}</span> },
          { key: 'x', header: '', render: m => assignableRoles(current?.role, m.role, isMe(m)).length > 0 ? <Button size="sm" variant="ghost" onClick={async () => { if (await confirm({ title: '移出成员', description: `确定把 @${m.login} 移出工作区？`, confirmText: '移出', destructive: true })) run(() => api(`/api/workspace/members/${encodeURIComponent(m.login)}`, { method: 'DELETE' }), '已移出'); }}>移出</Button> : null },
        ]} />
      </Card>
      <ComposableSettings manage={manage} owner={owner} />
      {manage && <Card title="邀请成员" subtitle="生成一次性邀请链接（7 天内有效）。对方需要在登录白名单里，用 GitHub 登录后打开链接即可加入。">
        <div className="flex flex-wrap items-end gap-2">
          <Field label="角色">{id => <Select id={id} value={role} onChange={e => setRole(e.target.value)} className="w-32">{(owner ? ['admin', 'member', 'viewer'] : ['member', 'viewer']).map(r => <option key={r} value={r}>{roleLabel(r)}</option>)}</Select>}</Field>
          <Field label="限定 GitHub 登录名（可选）">{id => <Input id={id} value={login} placeholder="例如 teammate" onChange={e => setLogin(e.target.value)} className="w-56" />}</Field>
          <Button variant="primary" onClick={invite}>生成邀请链接</Button>
        </div>
        {link && <Callout tone="success" icon="link">邀请链接（已尝试复制，只显示这一次）：<span className="break-all font-mono text-xs">{link}</span></Callout>}
        {invites.length > 0 && <Table className="mt-3" caption="邀请" dense rows={invites} rowKey={i => i.id} columns={[
          { key: 'r', header: '角色', render: i => roleLabel(i.role) },
          { key: 'l', header: '限定', render: i => i.login ? `@${i.login}` : '任何人' },
          { key: 's', header: '状态', render: i => i.accepted_at ? <Badge tone="success">已被 @{i.accepted_by} 接受</Badge> : i.revoked_at ? <Badge>已撤销</Badge> : i.expires_at < new Date().toISOString() ? <Badge>已过期</Badge> : <Badge tone="info">待接受</Badge> },
          { key: 'c', header: '创建', render: i => <span className="tabular text-ink-3">{fmtTime(i.created_at)} · @{i.created_by ?? '-'}</span> },
          { key: 'x', header: '', render: i => !i.accepted_at && !i.revoked_at ? <Button size="sm" variant="ghost" onClick={() => run(() => api(`/api/workspace/invites/${i.id}`, { method: 'DELETE' }), '邀请已撤销')}>撤销</Button> : null },
        ]} />}
      </Card>}
      {owner && (rows?.length ?? 0) > 1 && <Card title="转让所有权" subtitle="转让后你变为管理员。对方必须已经是成员。">
        <div className="flex flex-wrap items-end gap-2">
          <Field label="新所有者">{id => <Select id={id} value={transferTo} onChange={e => setTransferTo(e.target.value)} className="w-56"><option value="">选择成员</option>{rows!.filter(m => !isMe(m)).map(m => <option key={m.login} value={m.login}>@{m.login}（{roleLabel(m.role)}）</option>)}</Select>}</Field>
          <Button variant="danger" disabled={!transferTo} onClick={async () => { if (await confirm({ title: '转让所有权', description: `确定把「${current?.name}」转让给 @${transferTo}？你将变为管理员。`, confirmText: '转让', destructive: true })) { await run(() => api('/api/workspace/transfer', { method: 'POST', body: { login: transferTo } }), '已转让'); location.reload(); } }}>转让</Button>
        </div>
      </Card>}
    </div>
  );
}

/** 打开邀请链接：确认后加入并切换到该工作区 */
export function AcceptInvite({ token }: { token: string }) {
  const [err, setErr] = useState(''); const [busy, setBusy] = useState(false);
  const accept = async () => {
    setBusy(true); setErr('');
    try { const r = await api<{ workspace: string; name: string }>('/api/me/invites/accept', { method: 'POST', body: { token } }); toast.success(`已加入「${r.name}」`); setWorkspace(r.workspace); location.assign(appPath('/overview')); }
    catch (e: any) { setErr(e.message); } finally { setBusy(false); }
  };
  return (
    <div className="mx-auto max-w-lg space-y-gap" data-testid="accept-invite">
      <PageHeader title="加入工作区" desc="你收到了一个工作区邀请。加入后可以在侧栏切换工作区。" />
      {err && <Callout tone="danger">{err}</Callout>}
      <Card><div className="flex items-center justify-between gap-3"><span className="text-sm text-ink-2">邀请令牌 <span className="font-mono text-xs text-ink-3">{token.slice(0, 6)}…</span></span><Button variant="primary" loading={busy} onClick={accept}>接受邀请</Button></div></Card>
    </div>
  );
}
