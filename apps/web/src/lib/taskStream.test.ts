import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api';
import { getToken, notifyUnauthorized } from './auth';
import { watchTaskStream, POLL_MS, type TaskStreamState } from './taskStream';

vi.mock('../api', () => ({ api: vi.fn() }));
vi.mock('./auth', () => ({ apiUrl: (p: string) => p, getWorkspace: () => null, getToken: vi.fn(), authHeaders: () => ({}), notifyUnauthorized: vi.fn() }));
const fetchTimeline = vi.mocked(api), empty = { events: [], seq: 0, more: false };
let stop: (() => void) | undefined;
class Source {
  static all: Source[] = [];
  onerror?: () => void;
  listeners = new Map<string, (m: { data: string }) => void>();
  close = vi.fn();
  constructor(public url: string) { Source.all.push(this); }
  addEventListener(name: string, cb: (m: { data: string }) => void) { this.listeners.set(name, cb); }
  emit(name: string, data: unknown = {}) { this.listeners.get(name)?.({ data: JSON.stringify(data) }); }
}
const flush = () => vi.advanceTimersByTimeAsync(0);
const latest = () => Source.all.at(-1)!;
function start() {
  const snapshots: TaskStreamState[] = [];
  stop = watchTaskStream('t', s => snapshots.push(s));
  return { snapshots, state: () => snapshots.at(-1)! };
}
async function fallback() {
  for (const delay of [1000, 2000, 4000]) { latest().onerror!(); await vi.advanceTimersByTimeAsync(delay); }
  latest().onerror!(); await flush();
}
beforeEach(() => {
  vi.useFakeTimers(); Source.all = [];
  vi.stubGlobal('EventSource', Source); vi.spyOn(Math, 'random').mockReturnValue(.5);
  vi.mocked(getToken).mockReturnValue(null); fetchTimeline.mockResolvedValue(empty);
});
afterEach(() => { stop?.(); stop = undefined; vi.unstubAllGlobals(); vi.useRealTimers(); vi.restoreAllMocks(); vi.resetAllMocks(); });

describe('task stream recovery', () => {
  it('automatically restores SSE after polling without refreshing, then stops polling', async () => {
    const s = start(); await flush(); await fallback();
    expect(s.state().mode).toBe('polling'); expect(Source.all).toHaveLength(4);
    await vi.advanceTimersByTimeAsync(15000);
    expect(Source.all).toHaveLength(5);
    latest().emit('hello'); expect(s.state().mode).toBe('live');
    const count = fetchTimeline.mock.calls.length;
    await vi.advanceTimersByTimeAsync(9000); expect(fetchTimeline).toHaveBeenCalledTimes(count);
    expect(Source.all.slice(0, -1).every(es => es.close.mock.calls.length === 1)).toBe(true);
  });

  it('backs off failed probes at 15, 30 and 60 seconds while retaining polling', async () => {
    const s = start(); await flush(); await fallback();
    for (const delay of [15000, 30000, 60000, 60000]) {
      const count = Source.all.length;
      await vi.advanceTimersByTimeAsync(delay - 1); expect(Source.all).toHaveLength(count);
      await vi.advanceTimersByTimeAsync(1); expect(Source.all).toHaveLength(count + 1);
      latest().onerror!(); await flush(); expect(s.state().mode).toBe('polling');
    }
  });

  it('detects silent loss only when the server advertises heartbeats', async () => {
    const s = start(); await flush();
    latest().emit('hello', { heartbeatMs: 15000, connectionId: 'connection-a' });
    latest().emit('ev', { seq: 7, taskId: 't', type: 'MODEL_CALL', at: '2026-11-02T09:00:00Z' });
    await vi.advanceTimersByTimeAsync(30000); latest().emit('heartbeat', { seq: 999 });
    expect(s.state().seq).toBe(7); expect(s.state().events).toHaveLength(1);
    expect(s.state().connectionId).toBe('connection-a'); expect(s.state().lastHeartbeatAt).toBeTruthy();
    await vi.advanceTimersByTimeAsync(44999); expect(s.state().mode).toBe('live');
    await vi.advanceTimersByTimeAsync(1);
    expect(s.state().mode).toBe('polling'); expect(s.state().lastDisconnect?.reason).toBe('heartbeat_timeout');
    expect(latest().close).toHaveBeenCalledOnce();
  });

  it('keeps old servers with comment-only heartbeats connected', async () => {
    const s = start(); await flush(); latest().emit('hello');
    await vi.advanceTimersByTimeAsync(180000);
    expect(s.state().mode).toBe('live'); expect(Source.all).toHaveLength(1);
  });

  it('times out a stream that never completes its handshake', async () => {
    const s = start(); await flush();
    await vi.advanceTimersByTimeAsync(10000);
    expect(s.state().lastDisconnect?.reason).toBe('handshake_timeout'); expect(latest().close).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(1000); expect(Source.all).toHaveLength(2);
  });

  it('discards late polling responses and old SSE callbacks after a new connection succeeds', async () => {
    const s = start(); await flush(); const old = latest(); old.emit('hello');
    old.emit('ev', { seq: 7, taskId: 't', type: 'MODEL_CALL' });
    let resolve!: (v: unknown) => void;
    fetchTimeline.mockImplementationOnce(() => new Promise(r => { resolve = r; }));
    old.onerror!(); expect(s.state().mode).toBe('connecting'); await vi.advanceTimersByTimeAsync(15000);
    expect(latest().url).toContain('after=7'); latest().emit('hello', { heartbeatMs: 15000 });
    resolve({ events: [{ seq: 99, taskId: 't' }], more: false }); await flush();
    old.emit('ev', { seq: 100, taskId: 't' }); old.onerror!(); old.emit('hello');
    expect(s.state().mode).toBe('live'); expect(s.state().seq).toBe(7);
    await vi.advanceTimersByTimeAsync(9000); expect(Source.all).toHaveLength(2);
  });

  it('reports failed polling and recovers only after a successful request', async () => {
    vi.stubGlobal('EventSource', undefined);
    fetchTimeline.mockResolvedValueOnce(empty).mockRejectedValueOnce(new Error('offline')).mockResolvedValue(empty);
    const s = start(); await flush(); expect(s.state().mode).toBe('error');
    const last = s.state().lastSyncAt;
    await vi.advanceTimersByTimeAsync(POLL_MS);
    expect(s.state().mode).toBe('polling'); expect(s.state().lastSyncAt).toBeGreaterThan(last!);
  });

  it.each([401, 403, 404])('stops retrying a terminal HTTP %s response', async status => {
    fetchTimeline.mockRejectedValue({ status });
    const s = start(); await flush();
    expect(s.state().mode).toBe('blocked'); expect(s.state().terminalStatus).toBe(status);
    await vi.advanceTimersByTimeAsync(120000);
    expect(fetchTimeline).toHaveBeenCalledOnce(); expect(Source.all).toHaveLength(0); expect(vi.getTimerCount()).toBe(0);
  });

  it('handles expired tickets without retrying or retaining the sensitive URL', async () => {
    vi.mocked(getToken).mockReturnValue('sensitive-token');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 401 })));
    const s = start(); await flush();
    expect(s.state().mode).toBe('blocked'); expect(notifyUnauthorized).toHaveBeenCalledOnce();
    expect(JSON.stringify(s.snapshots)).not.toContain('sensitive-token'); expect(vi.getTimerCount()).toBe(0);
  });

  it('obtains a new one-time ticket on every reconnect', async () => {
    vi.mocked(getToken).mockReturnValue('token'); let n = 0;
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(JSON.stringify({ ticket: `ticket-${++n}` }))));
    const s = start(); await flush(); expect(latest().url).toContain('ticket=ticket-1');
    latest().emit('hello'); latest().onerror!(); await vi.advanceTimersByTimeAsync(15000);
    expect(latest().url).toContain('ticket=ticket-2'); latest().emit('hello');
    expect(JSON.stringify(s.state())).not.toContain('ticket-');
  });

  it('recovers immediately on online or visibility events and releases all resources on stop', async () => {
    const win = new EventTarget(), doc = Object.assign(new EventTarget(), { hidden: false, visibilityState: 'visible' });
    const nav = { onLine: true };
    vi.stubGlobal('window', win); vi.stubGlobal('document', doc); vi.stubGlobal('navigator', nav);
    const s = start(); await flush(); latest().emit('hello', { heartbeatMs: 15000 });
    nav.onLine = false; win.dispatchEvent(new Event('offline'));
    expect(s.state().mode).toBe('offline'); const count = fetchTimeline.mock.calls.length;
    await vi.advanceTimersByTimeAsync(90000); expect(fetchTimeline).toHaveBeenCalledTimes(count);
    nav.onLine = true; win.dispatchEvent(new Event('online')); await flush(); expect(Source.all).toHaveLength(2);
    latest().onerror!(); await flush();
    doc.dispatchEvent(new Event('visibilitychange')); await flush(); expect(Source.all).toHaveLength(3);
    stop!(); const calls = Source.all.length;
    win.dispatchEvent(new Event('online')); doc.dispatchEvent(new Event('visibilitychange')); await flush();
    expect(Source.all).toHaveLength(calls); expect(vi.getTimerCount()).toBe(0);
  });

  it('allows queued heartbeats to catch up after a background tab becomes visible', async () => {
    const doc = Object.assign(new EventTarget(), { hidden: true, visibilityState: 'hidden' }); vi.stubGlobal('document', doc);
    const s = start(); await flush(); latest().emit('hello', { heartbeatMs: 15000 });
    await vi.advanceTimersByTimeAsync(90000); expect(s.state().mode).toBe('live');
    doc.hidden = false; doc.visibilityState = 'visible'; doc.dispatchEvent(new Event('visibilitychange'));
    await vi.advanceTimersByTimeAsync(1000); latest().emit('heartbeat');
    await vi.advanceTimersByTimeAsync(5000); expect(s.state().mode).toBe('live'); expect(Source.all).toHaveLength(1);
  });

  it('ignores late initial responses after leaving the page', async () => {
    let resolve!: (v: typeof empty) => void;
    fetchTimeline.mockReturnValue(new Promise(r => { resolve = r; }));
    const s = start(); stop!(); resolve(empty); await flush();
    expect(s.snapshots).toHaveLength(1); expect(vi.getTimerCount()).toBe(0); expect(Source.all).toHaveLength(0);
  });

  it('aborts a hung timeline request so it can recover', async () => {
    fetchTimeline.mockImplementationOnce((_path, init) => new Promise((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(new Error('aborted')))));
    const s = start(); await vi.advanceTimersByTimeAsync(10000);
    expect(s.state().mode).toBe('error'); expect(Source.all).toHaveLength(1);
    latest().emit('hello'); expect(s.state().mode).toBe('live');
  });
});
