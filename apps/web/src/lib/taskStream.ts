/** 任务事件流：独立维护 SSE、心跳监测与轮询兜底；所有回调绑定订阅/连接代次。 */
import { useEffect, useState } from 'react';
import { api } from '../api';
import { apiUrl, authHeaders, getToken, getWorkspace, notifyUnauthorized } from './auth';
import { mergeEvents, type TaskEnvelope } from './timeline';

export type StreamMode = 'connecting' | 'live' | 'polling' | 'error' | 'offline' | 'blocked';
export type DisconnectReason = 'transport_error' | 'heartbeat_timeout' | 'handshake_timeout' | 'offline' | 'unauthorized' | 'forbidden' | 'not_found' | 'capacity_limit' | 'request_failed' | 'unsupported';
export interface StreamDisconnect { reason: DisconnectReason; at: number; httpStatus?: number }
export interface TaskStreamState {
  events: TaskEnvelope[]; mode: StreamMode; seq: number; lastSyncAt?: number | null;
  lastHeartbeatAt?: number | null; connectionId?: string | null; retryCount?: number;
  nextRetryAt?: number | null; lastDisconnect?: StreamDisconnect | null; terminalStatus?: number;
}
export const POLL_MS = 3000;
const REQUEST_MS = 10_000, HEARTBEAT_TIMEOUT_MS = 45_000;
class StreamHttpError extends Error { constructor(public status: number) { super(`ticket ${status}`); } }
const httpStatus = (e: unknown): number | undefined => typeof (e as { status?: unknown })?.status === 'number' ? (e as { status: number }).status : undefined;
const reasonFor = (status?: number): DisconnectReason => status === 401 ? 'unauthorized' : status === 403 ? 'forbidden' : status === 404 ? 'not_found' : status === 429 ? 'capacity_limit' : 'request_failed';
const terminal = (status?: number) => status === 401 || status === 403 || status === 404;
const online = () => typeof navigator === 'undefined' || navigator.onLine !== false;
const initial = (): TaskStreamState => ({ events: [], mode: 'connecting', seq: 0, lastSyncAt: null, lastHeartbeatAt: null, connectionId: null, retryCount: 0, nextRetryAt: null, lastDisconnect: null });

export async function streamUrl(taskId: string, after: number, fetchImpl: typeof fetch = (...a) => fetch(...a), signal?: AbortSignal): Promise<string> {
  const q = new URLSearchParams({ after: String(after) }); const w = getWorkspace();
  if (getToken()) {
    const r = await fetchImpl(apiUrl('/api/events/ticket'), { method: 'POST', headers: authHeaders(), credentials: 'include', signal });
    if (!r.ok) { if (r.status === 401) notifyUnauthorized(); throw new StreamHttpError(r.status); }
    const { ticket } = await r.json() as { ticket?: string }; if (ticket) q.set('ticket', ticket);
  }
  if (w) q.set('ws', w);
  return apiUrl(`/api/tasks/${encodeURIComponent(taskId)}/stream?${q}`);
}

/** 仅返回安全的诊断字段，绝不保留带一次性票据的连接 URL。 */
export function watchTaskStream(taskId: string, onState: (state: TaskStreamState) => void): () => void {
  let state = initial(), stopped = false, halted = false, generation = 0, pollEpoch = 0, attempts = 0, failures = 0, probeRound = 0;
  let es: EventSource | null = null, ticketRequest: AbortController | null = null, pollRequest: AbortController | null = null;
  let retryTimer: ReturnType<typeof setTimeout> | undefined, pollTimer: ReturnType<typeof setTimeout> | undefined;
  let handshakeTimer: ReturnType<typeof setTimeout> | undefined, heartbeatTimer: ReturnType<typeof setTimeout> | undefined, requestTimer: ReturnType<typeof setTimeout> | undefined;
  let polling = false, lastContact = 0, heartbeatTimeout = 0;
  const alive = () => !stopped && !halted;
  const update = (change: Partial<TaskStreamState>) => { if (!stopped) { state = { ...state, ...change }; onState(state); } };
  const note = (reason: DisconnectReason, status?: number) => update({ lastDisconnect: { reason, at: Date.now(), ...(status ? { httpStatus: status } : {}) } });
  const clearRetry = () => { clearTimeout(retryTimer); retryTimer = undefined; update({ nextRetryAt: null }); };
  const closeStream = () => {
    generation++; const current = es; es = null; ticketRequest?.abort(); ticketRequest = null;
    clearTimeout(handshakeTimer); clearTimeout(heartbeatTimer); heartbeatTimeout = 0; current?.close();
  };
  const stopPolling = () => {
    polling = false; pollEpoch++; clearTimeout(pollTimer); clearTimeout(requestTimer); pollRequest?.abort(); pollRequest = null;
  };
  const halt = (status: number) => {
    halted = true; clearRetry(); closeStream(); stopPolling(); note(reasonFor(status), status); update({ mode: 'blocked', terminalStatus: status });
  };
  const offline = () => {
    if (!alive()) return;
    clearRetry(); closeStream(); stopPolling(); note('offline'); update({ mode: 'offline' });
  };
  const pull = async () => {
    if (!alive() || !online() || pollRequest) return;
    const epoch = pollEpoch, controller = new AbortController(); pollRequest = controller;
    requestTimer = setTimeout(() => controller.abort(), REQUEST_MS);
    const valid = () => alive() && epoch === pollEpoch && pollRequest === controller && online();
    try {
      for (let i = 0; i < 20; i++) {
        const r = await api<{ events: TaskEnvelope[]; seq: number; more: boolean }>(`/api/tasks/${encodeURIComponent(taskId)}/timeline?after=${state.seq}`, { signal: controller.signal });
        if (!valid()) return;
        const merged = mergeEvents(state.events, r.events); update({ events: merged.list, seq: merged.seq, lastSyncAt: Date.now() });
        if (!r.more) break;
      }
      if (valid() && polling) update({ mode: 'polling' });
    } catch (e) {
      if (!valid()) return;
      const status = httpStatus(e);
      if (terminal(status)) { halt(status!); return; }
      note(reasonFor(status), status); update({ mode: 'error' });
    } finally {
      if (epoch === pollEpoch && pollRequest === controller) {
        clearTimeout(requestTimer); pollRequest = null;
        if (polling && alive()) pollTimer = setTimeout(() => { void pull(); }, POLL_MS);
      }
    }
  };
  const startPolling = () => { if (!polling && alive()) { polling = true; void pull(); } };
  const scheduleConnect = (delay: number) => {
    clearTimeout(retryTimer); update({ nextRetryAt: Date.now() + delay });
    retryTimer = setTimeout(() => { retryTimer = undefined; update({ nextRetryAt: null }); void connect(); }, delay);
  };
  const failed = (reason: DisconnectReason, status?: number) => {
    if (!alive()) return;
    closeStream(); note(reason, status);
    if (terminal(status)) { halt(status!); return; }
    if (!online()) { offline(); return; }
    if (state.mode === 'live') update({ mode: 'connecting' });
    failures++;
    if (polling || failures > 3) {
      startPolling(); scheduleConnect(Math.min(60_000, 15_000 * 2 ** Math.min(probeRound++, 2)) * (.8 + Math.random() * .4));
    } else {
      update({ mode: state.mode === 'error' ? 'error' : 'connecting' }); scheduleConnect(1000 * 2 ** (failures - 1));
    }
  };
  const armHeartbeat = (delay = heartbeatTimeout) => {
    clearTimeout(heartbeatTimer);
    if (!heartbeatTimeout) return;
    const token = generation;
    heartbeatTimer = setTimeout(() => {
      if (!alive() || token !== generation) return;
      if (typeof document !== 'undefined' && document.hidden) { armHeartbeat(); return; }
      const remaining = heartbeatTimeout - (Date.now() - lastContact);
      if (remaining <= 0) { startPolling(); failed('heartbeat_timeout'); } else armHeartbeat(remaining);
    }, delay);
  };
  const connect = async () => {
    if (!alive()) return;
    if (!online()) { offline(); return; }
    if (typeof EventSource === 'undefined') { note('unsupported'); startPolling(); return; }
    if (es || ticketRequest) return;
    const token = ++generation, controller = new AbortController(); ticketRequest = controller;
    update({ retryCount: Math.max(0, attempts++), connectionId: null });
    const valid = () => alive() && token === generation;
    handshakeTimer = setTimeout(() => { if (valid()) failed('handshake_timeout'); }, REQUEST_MS);
    try {
      const url = await streamUrl(taskId, state.seq, undefined, controller.signal);
      if (!valid()) return;
      ticketRequest = null;
      const source = new EventSource(url, { withCredentials: true }); es = source;
      let ready = false;
      source.addEventListener('hello', (message: MessageEvent) => {
        if (!valid() || ready) return;
        let hello: { heartbeatMs?: number; connectionId?: string };
        try { hello = JSON.parse(message.data || '{}'); } catch { return; }
        ready = true; failures = 0; probeRound = 0; clearTimeout(handshakeTimer); clearRetry(); stopPolling();
        lastContact = Date.now();
        // 旧服务只发送注释心跳，不声明能力时不开启客户端静默超时检测。
        heartbeatTimeout = Number.isFinite(hello.heartbeatMs) && hello.heartbeatMs! > 0 ? Math.max(HEARTBEAT_TIMEOUT_MS, hello.heartbeatMs! * 3) : 0;
        update({ mode: 'live', lastSyncAt: lastContact, connectionId: hello.connectionId ?? null }); armHeartbeat();
      });
      source.addEventListener('heartbeat', () => {
        if (!valid() || !ready) return;
        lastContact = Date.now(); update({ lastHeartbeatAt: lastContact, lastSyncAt: lastContact }); armHeartbeat();
      });
      source.addEventListener('ev', (message: MessageEvent) => {
        if (!valid() || !ready) return;
        try {
          const event = JSON.parse(message.data) as TaskEnvelope;
          if (event.taskId !== taskId || !Number.isFinite(event.seq)) return;
          const merged = mergeEvents(state.events, [event]); lastContact = Date.now();
          update({ events: merged.list, seq: merged.seq, lastSyncAt: lastContact }); armHeartbeat();
        } catch { /* 非法事件不充当连接活跃证据。 */ }
      });
      source.onerror = () => { if (valid()) { if (ready) startPolling(); failed('transport_error'); } };
    } catch (e) { if (valid()) failed(reasonFor(httpStatus(e)), httpStatus(e)); }
  };
  const resume = () => {
    if (!alive() || !online()) return;
    if (state.mode === 'live') { armHeartbeat(Math.max(5000, heartbeatTimeout - (Date.now() - lastContact))); return; }
    clearRetry(); closeStream(); failures = 0; probeRound = 0; startPolling(); void connect();
  };
  const visible = () => { if (document.visibilityState === 'visible') resume(); };
  if (typeof window !== 'undefined') { window.addEventListener('offline', offline); window.addEventListener('online', resume); }
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', visible);
  onState(state);
  if (!online()) offline(); else void pull().finally(() => { if (alive()) void connect(); });
  return () => {
    stopped = true; clearRetry(); closeStream(); stopPolling();
    if (typeof window !== 'undefined') { window.removeEventListener('offline', offline); window.removeEventListener('online', resume); }
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', visible);
  };
}

export function useTaskStream(taskId: string): TaskStreamState {
  const [state, setState] = useState<TaskStreamState>(initial);
  useEffect(() => watchTaskStream(taskId, setState), [taskId]);
  return state;
}
