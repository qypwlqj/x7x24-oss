import { AppLink } from '@/components/AppLink';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import { setToken } from '../lib/auth';
import { DeviceFlow, type DeviceState } from '../lib/deviceFlow';
import { CONNECTION_LABEL, type GitHubState } from '../lib/githubConnection';
import { Badge, Button, Callout, Modal } from '../ui';

export function GitHubConnectionNotice({ github, onRefresh, onRecover }: { github: GitHubState | null; onRefresh: () => Promise<void>; onRecover: () => void }) {
  if (!github || github.connection.state === 'ready' || github.connection.state === 'unchecked') return null;
  const state = github.connection.state;
  const needsRepository = state === 'repositories_required' || state === 'permissions_required' || state === 'approval_pending';
  return <div data-testid="github-connection-notice"><Callout tone={state === 'network_error' ? 'warn' : 'danger'} icon="plug">
    <span>{github.connection.message}</span>
    {github.canManage === false && (needsRepository || github.credential.deletable) ? <span className="ml-2">请联系工作区管理员处理。</span> : state === 'network_error' || state === 'approval_pending' ? <Button size="sm" variant="ghost" className="ml-2" onClick={() => void onRefresh()}>重新检查</Button> : needsRepository ? <AppLink className="ml-3 text-primary-ink underline" href="/integrations/connect">管理仓库授权</AppLink> : <Button size="sm" variant="ghost" className="ml-2" onClick={onRecover}>{github.signedIn ? '更新 GitHub 授权' : '前往连接'}</Button>}
  </Callout></div>;
}

export function GitHubStatusBadge({ github }: { github: GitHubState | null }) {
  const state = github?.connection.state ?? 'unchecked';
  return <Badge tone={state === 'ready' ? 'success' : state === 'unchecked' ? 'neutral' : 'warn'} dot>GitHub · {CONNECTION_LABEL[state]}</Badge>;
}

/** Recovery stays in a modal, so project and picker state survive authorization. */
export function GitHubRecovery({ github, onClose, onRecovered }: { github: GitHubState; onClose: () => void; onRecovered: () => Promise<void> }) {
  const [config, setConfig] = useState<{ webFlow: boolean; deviceFlow: boolean } | null>(null);
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  const complete = useCallback(async () => {
    try { if (github.canManage) await api('/api/integrations/github/use-login', { method: 'POST' }); await onRecovered(); onClose(); }
    catch (e: any) { setError(e.message); }
  }, [github.canManage, onRecovered, onClose]);
  const flow = useMemo(() => new DeviceFlow({
    start: async () => ({ ...await api('/api/auth/device/start?reauthorize=true', { method: 'POST' }), ok: true }),
    poll: async sessionId => {
      const result = await api(`/api/auth/device/poll?sessionId=${encodeURIComponent(sessionId)}`, { method: 'POST' });
      if (result.status !== 'ok') return result;
      setToken(result.token); return { status: 'connected', login: result.user.login };
    },
  }), []);
  const [device, setDevice] = useState<DeviceState>(flow.state);
  useEffect(() => { api('/api/auth/config').then(setConfig).catch(e => setError(e.message)); const off = flow.subscribe(setDevice); return () => { off(); flow.stop(); }; }, [flow]);
  useEffect(() => { if (device.phase === 'connected') void complete(); }, [device, complete]);
  useEffect(() => {
    const done = (event: MessageEvent) => {
      if (event.origin !== location.origin) return;
      if (event.data?.type === 'x7-github-reconnected') void complete();
      if (event.data?.type === 'x7-github-reconnect-error') setError(event.data.message || 'GitHub 授权未完成，请重试');
    };
    window.addEventListener('message', done); return () => window.removeEventListener('message', done);
  }, [complete]);
  const authorize = async () => {
    const popup = window.open('about:blank', 'x7-github-authorization', 'width=700,height=800');
    setBusy(true); setError('');
    try {
      const redirect = location.origin + location.pathname + '?github_reconnect=1';
      const result = await api<{ url: string }>('/api/auth/reauthorize', { method: 'POST', body: { redirect } });
      if (!popup) throw new Error('请允许打开授权窗口，或使用下方的验证码更新授权');
      popup.location.href = result.url;
    } catch (e: any) { popup?.close(); setError(e.message); } finally { setBusy(false); }
  };
  return <Modal title="更新 GitHub 授权" onClose={onClose} footer={<Button onClick={onClose}>稍后处理</Button>}>
    <div className="space-y-3"><p className="text-sm text-ink-2">使用当前登录的 GitHub 账号完成授权，完成后继续当前操作。你的项目和绑定表单会保留。</p>{github.canManage && <p className="text-sm text-ink-3">当前工作区将使用这次登录授权访问仓库。</p>}
      {config?.webFlow && <Button variant="primary" loading={busy} onClick={authorize}>前往 GitHub 更新授权</Button>}
      {config?.deviceFlow && device.phase !== 'waiting' && <Button onClick={() => void flow.start()}>使用验证码更新授权</Button>}
      {device.phase === 'waiting' && <div className="space-y-2"><AppLink href={device.verificationUri} target="_blank" rel="noreferrer" className="text-primary-ink underline">打开 GitHub 输入验证码</AppLink><div className="select-all border border-line p-3 text-center font-mono text-2xl">{device.userCode}</div><p className="text-sm text-ink-3">完成后会自动检测授权状态。</p></div>}
      {(device.phase === 'error' || device.phase === 'denied' || device.phase === 'expired') && <Callout tone="danger">{device.message}</Callout>}
      {!config && !error && <p className="text-sm">正在读取授权方式…</p>}
      {config && !config.webFlow && !config.deviceFlow && <Callout tone="warn">管理员尚未配置 GitHub 登录，请到集成页使用高级连接方式。</Callout>}
      {error && <Callout tone="danger">{error}</Callout>}
    </div>
  </Modal>;
}
