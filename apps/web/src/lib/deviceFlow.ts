/** GitHub 设备码登录的前端状态机（纯逻辑，不依赖 React，便于测试）。 */
export type DeviceState =
  | { phase: 'idle' }
  | { phase: 'starting' }
  | { phase: 'waiting'; sessionId: string; userCode: string; verificationUri: string; expiresAt: number; interval: number; message?: string }
  | { phase: 'connected'; login: string; avatarUrl?: string }
  | { phase: 'denied'; message: string }
  | { phase: 'expired'; message: string }
  | { phase: 'error'; message: string; code?: string; fallback?: boolean };

export type DeviceEvent =
  | { type: 'start' }
  | { type: 'started'; sessionId: string; userCode: string; verificationUri: string; expiresIn: number; interval: number; now: number }
  | { type: 'start-failed'; message: string; code?: string; fallback?: boolean }
  | { type: 'poll-pending'; interval?: number; message?: string }
  | { type: 'poll-connected'; login: string; avatarUrl?: string }
  | { type: 'poll-denied'; message: string } | { type: 'poll-expired'; message: string } | { type: 'poll-error'; message: string }
  | { type: 'tick'; now: number } | { type: 'cancel' };

export function reduce(s: DeviceState, e: DeviceEvent): DeviceState {
  switch (e.type) {
    case 'start': return { phase: 'starting' };
    case 'started': return { phase: 'waiting', sessionId: e.sessionId, userCode: e.userCode, verificationUri: e.verificationUri, expiresAt: e.now + e.expiresIn * 1000, interval: Math.max(1, e.interval) };
    case 'start-failed': return { phase: 'error', message: e.message, code: e.code, fallback: e.fallback };
    case 'cancel': return { phase: 'idle' };
    default: break;
  }
  if (s.phase !== 'waiting') return s; // 终态或空闲时忽略迟到的轮询结果
  switch (e.type) {
    case 'poll-pending': return { ...s, interval: Math.max(1, e.interval ?? s.interval), message: e.message ?? s.message };
    case 'poll-connected': return { phase: 'connected', login: e.login, avatarUrl: e.avatarUrl };
    case 'poll-denied': return { phase: 'denied', message: e.message };
    case 'poll-expired': return { phase: 'expired', message: e.message };
    case 'poll-error': return { phase: 'error', message: e.message };
    case 'tick': return e.now >= s.expiresAt ? { phase: 'expired', message: '验证码已过期，请重新获取。' } : s;
    default: return s;
  }
}
export const remainingSeconds = (s: DeviceState, now: number) => (s.phase === 'waiting' ? Math.max(0, Math.ceil((s.expiresAt - now) / 1000)) : 0);
export const fmtCountdown = (sec: number) => `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;

export interface DeviceApi {
  start(): Promise<{ ok: boolean; sessionId?: string; userCode?: string; verificationUri?: string; expiresIn?: number; interval?: number; error?: string; message?: string; fallback?: string }>;
  poll(sessionId: string): Promise<{ status: 'pending' | 'connected' | 'expired' | 'denied' | 'error'; interval?: number; message?: string; login?: string; avatarUrl?: string }>;
}
/** 控制器：start() 取码 → 按服务端给的 interval 轮询 → 终态后自动停止。timer / now 可注入。 */
export class DeviceFlow {
  state: DeviceState = { phase: 'idle' };
  private listeners = new Set<(s: DeviceState) => void>(); private timer: unknown = null; private gen = 0;
  constructor(private api: DeviceApi, private now: () => number = Date.now, private setTimer: (fn: () => void, ms: number) => unknown = (fn, ms) => setTimeout(fn, ms), private clearTimer: (t: unknown) => void = (t) => clearTimeout(t as any)) {}
  subscribe(fn: (s: DeviceState) => void) { this.listeners.add(fn); return () => { this.listeners.delete(fn); }; }
  private dispatch(e: DeviceEvent) { this.state = reduce(this.state, e); this.listeners.forEach((l) => l(this.state)); }
  async start() {
    this.stop(); const g = ++this.gen; this.dispatch({ type: 'start' });
    try {
      const r = await this.api.start(); if (g !== this.gen) return;
      if (!r.ok || !r.sessionId) { this.dispatch({ type: 'start-failed', message: r.message ?? '无法获取验证码', code: r.error, fallback: r.fallback === 'paste_token' }); return; }
      this.dispatch({ type: 'started', sessionId: r.sessionId, userCode: r.userCode!, verificationUri: r.verificationUri!, expiresIn: r.expiresIn ?? 900, interval: r.interval ?? 5, now: this.now() });
      this.schedule(g);
    } catch (e: any) { if (g === this.gen) this.dispatch({ type: 'start-failed', message: String(e?.message ?? e), fallback: true }); }
  }
  private schedule(g: number) {
    const s = this.state; if (s.phase !== 'waiting') return;
    this.timer = this.setTimer(async () => {
      if (g !== this.gen) return; this.dispatch({ type: 'tick', now: this.now() }); const cur = this.state; if (cur.phase !== 'waiting') return;
      try {
        const r = await this.api.poll(cur.sessionId); if (g !== this.gen) return;
        if (r.status === 'connected') this.dispatch({ type: 'poll-connected', login: r.login ?? '', avatarUrl: r.avatarUrl });
        else if (r.status === 'denied') this.dispatch({ type: 'poll-denied', message: r.message ?? '你在 GitHub 上拒绝了授权。' });
        else if (r.status === 'expired') this.dispatch({ type: 'poll-expired', message: r.message ?? '验证码已过期，请重新获取。' });
        else if (r.status === 'error') this.dispatch({ type: 'poll-error', message: r.message ?? '授权失败，请重试' });
        else this.dispatch({ type: 'poll-pending', interval: r.interval, message: r.message });
      } catch { this.dispatch({ type: 'poll-pending', message: '暂时连不上服务器，稍后自动重试' }); }
      this.schedule(g);
    }, s.interval * 1000);
  }
  stop() { this.gen++; if (this.timer != null) this.clearTimer(this.timer); this.timer = null; }
  cancel() { this.stop(); this.dispatch({ type: 'cancel' }); }
}
