import { AppLink } from '@/components/AppLink';
import { useEffect, useRef, useState } from 'react';
import { api } from '../api';
import { AUTH_ERRORS, loginUrl, setToken } from '../lib/auth';
import { Button, Callout } from '../ui';

export interface AuthConfig { required: boolean; webFlow: boolean; deviceFlow: boolean; clientId: string; allowlist: boolean; callbackUrl: string }

/** 登录页。优先 GitHub 网页授权；服务端未配置 client secret 或用户选择时走设备码。 */
export default function Login({ config, initialError, onLoggedIn }: { config: AuthConfig | null; initialError?: string; onLoggedIn: () => void }) {
  const [err, setErr] = useState(initialError ?? ''); const [device, setDevice] = useState<null | { sessionId: string; userCode: string; verificationUri: string; interval: number }>(null);
  const [busy, setBusy] = useState(false); const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);
  const startDevice = async () => {
    setErr(''); setBusy(true);
    try {
      const d = await api('/api/auth/device/start', { method: 'POST' }); setDevice(d);
      const poll = async (iv: number) => {
        timer.current = setTimeout(async () => {
          try {
            const r = await api(`/api/auth/device/poll?sessionId=${encodeURIComponent(d.sessionId)}`, { method: 'POST' });
            if (r.status === 'ok') { setToken(r.token); onLoggedIn(); return; }
            if (r.status === 'pending') return poll(r.interval ?? iv);
            setDevice(null); setErr(r.status === 'denied' ? '你在 GitHub 上拒绝了授权' : r.status === 'expired' ? '验证码已过期，请重新获取' : r.message ?? '登录失败');
          } catch (e: any) { setDevice(null); setErr(e.message); }
        }, iv * 1000);
      };
      poll(d.interval ?? 5);
    } catch (e: any) { setErr(e.message); } finally { setBusy(false); }
  };
  return <div className="grid min-h-screen place-items-center px-4" data-testid="login">
    <div className="w-full max-w-md border border-ink bg-surface p-8">
      <div className="mb-6 flex items-center gap-3">
        <Mark />
        <div><h1 className="font-display text-2xl font-medium text-ink">7x24</h1><p className="text-caption text-ink-3">持续产品交付</p></div>
      </div>
      <p className="mb-5 text-sm text-ink-2">使用 GitHub 账号登录，随后选择要交付的仓库。授权凭据会在服务器加密保存，7x24 仅访问你允许的仓库。</p>
      {err && <div className="mb-4"><Callout tone="danger" onClose={() => setErr('')}>{AUTH_ERRORS[err] ?? err}</Callout></div>}
      {!config ? <p className="text-sm text-ink-3">正在连接后端…</p> : device ? <div className="space-y-3 text-sm" data-testid="device-code">
        <p className="text-ink-2">在 GitHub 打开下面的页面并输入验证码，完成后这里会自动登录：</p>
        <AppLink className="block text-primary-ink underline" href={device.verificationUri} target="_blank" rel="noreferrer">{device.verificationUri}</AppLink>
        <div className="select-all border border-ink bg-sunken py-3 text-center font-mono text-3xl tracking-[0.3em] text-ink">{device.userCode}</div>
        <Button className="w-full" onClick={() => { if (timer.current) clearTimeout(timer.current); setDevice(null); }}>取消</Button>
      </div> : <div className="space-y-3">
        {config.webFlow && <Button variant="primary" className="w-full" data-testid="login-github" onClick={() => { location.href = loginUrl(location); }}>使用 GitHub 登录</Button>}
        {config.deviceFlow && <Button className="w-full" loading={busy} onClick={startDevice} data-testid="login-device">{config.webFlow ? '改用设备码登录' : '使用 GitHub 设备码登录'}</Button>}
        {!config.webFlow && !config.deviceFlow && <Callout tone="warn">服务器没有配置 GitHub 登录（GITHUB_OAUTH_CLIENT_ID），请联系管理员。</Callout>}
        {config.allowlist && <p className="text-xs text-ink-3">仅限管理员允许的 GitHub 账号登录。</p>}
      </div>}
    </div>
  </div>;
}

function Mark() {
  return <svg viewBox="0 0 22 22" aria-hidden="true" className="h-[22px] w-[22px] shrink-0">
    <path fill="#050505" d="M0 0Q5.5 4.6 7 2.6Q11-1.6 15 2.6Q16.500 4.6 22 0V22Q16.500 17.400 15 19.400Q11 23.600 7 19.400Q5.500 17.400 0 22Z" />
    <path fill="#ececea" d="M5.800 6.600Q6.500 10.300 10.200 11Q6.500 11.700 5.800 15.400Q5.100 11.700 1.400 11Q5.100 10.300 5.800 6.600ZM16.200 6.600Q16.900 10.300 20.600 11Q16.900 11.700 16.200 15.400Q15.500 11.700 11.800 11Q15.500 10.300 16.200 6.600Z" />
  </svg>;
}
