/**
 * 账号体系（前端）：
 * - 后端地址来自 NEXT_PUBLIC_API_BASE；留空时通过 Next.js 同源 API 代理。
 * - 会话令牌放 localStorage，以 Bearer 发送；后端同时下发 HttpOnly Cookie（SameSite=None; Secure）作为补充。
 * - SSE 无法带请求头：先用 Bearer 调 POST /api/events/ticket 换一张 60 秒、一次性的票据，再放进 /api/events?ticket=…；URL 里不出现长期会话令牌。
 */
const KEY = 'x7_token';
export const API_BASE: string = (process.env.NEXT_PUBLIC_API_BASE || process.env.NEXT_PUBLIC_BASE_PATH || '').replace(/\/+$/, '');
export const apiUrl = (path: string) => (/^https?:\/\//.test(path) ? path : API_BASE + path);

const store = (): Storage | null => { try { return globalThis.localStorage ?? null; } catch { return null; } };
export const getToken = () => store()?.getItem(KEY) ?? null;
export const setToken = (t: string | null) => { const s = store(); if (!s) return; if (t) s.setItem(KEY, t); else s.removeItem(KEY); };
/** 当前工作区（多工作区）：存 localStorage，以 X-Workspace 请求头发送；SSE 用 ws 查询参数。未选择时后端用你的默认工作区。 */
const WS_KEY = 'x7_ws';
export const getWorkspace = () => store()?.getItem(WS_KEY) ?? null;
export const setWorkspace = (w: string | null) => { const s = store(); if (!s) return; if (w) s.setItem(WS_KEY, w); else s.removeItem(WS_KEY); };
export const authHeaders = (): Record<string, string> => {
  const t = getToken(); const w = getWorkspace(); const h: Record<string, string> = {};
  if (t) h.authorization = `Bearer ${t}`; if (w) h['x-workspace'] = w; return h;
};
/** 生成 SSE 地址：已登录时先换一次性票据（每次连接/重连都要重新换）。fetchImpl 便于测试注入。 */
export const eventsUrl = async (fetchImpl: typeof fetch = (...a) => fetch(...a)): Promise<string> => {
  const t = getToken(); const w = getWorkspace(); const q = new URLSearchParams();
  if (t) {
    const r = await fetchImpl(apiUrl('/api/events/ticket'), { method: 'POST', headers: authHeaders(), credentials: 'include' });
    if (r.status === 401) { notifyUnauthorized(); throw new Error('unauthorized'); }
    if (!r.ok) throw new Error(`ticket ${r.status}`);
    const { ticket } = await r.json() as { ticket?: string }; if (ticket) q.set('ticket', ticket);
  }
  if (w) q.set('ws', w);
  const s = q.toString(); return apiUrl('/api/events') + (s ? `?${s}` : '');
};

type Listener = () => void; const listeners = new Set<Listener>();
export const onUnauthorized = (f: Listener) => { listeners.add(f); return () => { listeners.delete(f); }; };
export const notifyUnauthorized = () => listeners.forEach(f => f());

/** OAuth credentials are a transient fragment, not a page route. Accept old callbacks too. */
export function parseAuthCallback(hash: string): null | { token?: string; error?: string; message?: string } {
  const m = hash.match(/^#(?:\/auth\/callback\?|(?=(?:token|error)=))(.*)$/); if (!m) return null;
  const q = new URLSearchParams(m[1]);
  return { token: q.get('token') ?? undefined, error: q.get('error') ?? undefined, message: q.get('message') ?? undefined };
}
/** 网页授权入口：回跳地址 = 当前页面（不含 hash），后端会校验它在 X7_ALLOWED_ORIGINS 内 */
export const loginUrl = (loc: { origin: string; pathname: string; search?: string }) => apiUrl('/api/auth/login') + '?redirect=' + encodeURIComponent(loc.origin + loc.pathname + (loc.search ?? ''));

export const AUTH_ERRORS: Record<string, string> = {
  not_allowed: '这个 GitHub 账号不在允许登录的名单里，请联系管理员把你加进去。',
  bad_state: '登录请求已过期或被篡改，请重新点击登录。',
  access_denied: '你在 GitHub 上取消了授权。',
  web_flow_disabled: '管理员还没开启网页授权登录，请改用验证码登录。',
  device_flow_disabled: 'GitHub 应用没有开启验证码登录，请联系管理员开启，或改用粘贴令牌。',
};
