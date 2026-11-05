import { describe, expect, it } from 'vitest';
import { DeviceFlow, fmtCountdown, reduce, remainingSeconds, type DeviceApi, type DeviceState } from './deviceFlow';

const started = { ok: true, sessionId: 's1', userCode: 'ABCD-1234', verificationUri: 'https://github.com/login/device', expiresIn: 900, interval: 5 };
function harness(polls: any[], start: any = started) {
  let now = 1_000_000; const timers: { fn: () => void; ms: number }[] = []; const seen: DeviceState[] = [];
  const api: DeviceApi = { start: async () => start, poll: async () => { const r = polls.shift(); if (r instanceof Error) throw r; return r; } };
  const f = new DeviceFlow(api, () => now, (fn, ms) => { timers.push({ fn, ms }); return timers.length; }, () => {});
  f.subscribe(s => seen.push(s));
  const fire = async () => { const t = timers.shift(); if (!t) throw new Error('no timer'); t.fn(); await new Promise(r => setTimeout(r, 0)); };
  return { f, timers, seen, fire, advance: (ms: number) => { now += ms; } };
}
describe('deviceFlow reducer', () => {
  it('start → started → waiting with expiry', () => {
    const s = reduce({ phase: 'idle' }, { type: 'started', sessionId: 'x', userCode: 'C', verificationUri: 'u', expiresIn: 60, interval: 0, now: 1000 });
    expect(s).toMatchObject({ phase: 'waiting', expiresAt: 61000, interval: 1 });
  });
  it('ignores late poll results once terminal', () => {
    expect(reduce({ phase: 'connected', login: 'a' }, { type: 'poll-denied', message: 'x' }).phase).toBe('connected');
  });
  it('tick after expiry → expired', () => {
    const w = reduce({ phase: 'idle' }, { type: 'started', sessionId: 'x', userCode: 'C', verificationUri: 'u', expiresIn: 1, interval: 5, now: 0 });
    expect(reduce(w, { type: 'tick', now: 500 }).phase).toBe('waiting'); expect(reduce(w, { type: 'tick', now: 1000 }).phase).toBe('expired');
  });
  it('countdown helpers', () => {
    const w: DeviceState = { phase: 'waiting', sessionId: '', userCode: '', verificationUri: '', expiresAt: 125_000, interval: 5 };
    expect(remainingSeconds(w, 0)).toBe(125); expect(fmtCountdown(125)).toBe('2:05'); expect(remainingSeconds({ phase: 'idle' }, 0)).toBe(0);
  });
});
describe('DeviceFlow controller', () => {
  it('polls at the server interval, honours slow_down, then connects', async () => {
    const h = harness([{ status: 'pending' }, { status: 'pending', interval: 10 }, { status: 'connected', login: 'octo', avatarUrl: 'a.png' }]);
    await h.f.start(); expect(h.f.state.phase).toBe('waiting'); expect(h.timers[0].ms).toBe(5000);
    await h.fire(); expect(h.timers[0].ms).toBe(5000);
    await h.fire(); expect(h.timers[0].ms).toBe(10000);
    await h.fire(); expect(h.f.state).toMatchObject({ phase: 'connected', login: 'octo' }); expect(h.timers.length).toBe(0);
  });
  it('denied and expired are terminal and stop polling', async () => {
    const d = harness([{ status: 'denied', message: 'no' }]); await d.f.start(); await d.fire(); expect(d.f.state.phase).toBe('denied'); expect(d.timers.length).toBe(0);
    const e = harness([{ status: 'expired' }]); await e.f.start(); await e.fire(); expect(e.f.state.phase).toBe('expired');
  });
  it('device_flow_disabled start failure surfaces message + paste-token fallback', async () => {
    const h = harness([], { ok: false, error: 'device_flow_disabled', message: '请到 GitHub App 设置里勾选 Enable Device Flow', fallback: 'paste_token' });
    await h.f.start(); expect(h.f.state).toMatchObject({ phase: 'error', code: 'device_flow_disabled', fallback: true }); expect((h.f.state as any).message).toContain('Enable Device Flow');
  });
  it('network error while polling keeps waiting', async () => {
    const h = harness([new Error('net')]); await h.f.start(); await h.fire(); expect(h.f.state.phase).toBe('waiting'); expect(h.timers.length).toBe(1);
  });
  it('cancel stops timers and ignores in-flight results', async () => {
    const h = harness([{ status: 'connected', login: 'x' }]); await h.f.start(); h.f.cancel(); expect(h.f.state.phase).toBe('idle');
  });
  it('restart supersedes the previous session', async () => {
    const h = harness([]); await h.f.start(); await h.f.start(); expect(h.f.state.phase).toBe('waiting');
  });
});
