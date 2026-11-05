import { describe, expect, it } from 'vitest';
import { parseAuthCallback, loginUrl, apiUrl, authHeaders, eventsUrl, setToken, setWorkspace } from './auth';

describe('auth helpers', () => {
  it('parses token callback', () => { expect(parseAuthCallback('#token=a.b%2Bc')).toEqual({ token: 'a.b+c', error: undefined, message: undefined }); });
  it('parses error callback', () => { expect(parseAuthCallback('#error=not_allowed&message=x')?.error).toBe('not_allowed'); });
  it('ignores other hashes', () => { expect(parseAuthCallback('/overview')).toBeNull(); });
  it('builds login url with redirect without hash', () => { expect(loginUrl({ origin: 'https://demo.example.invalid', pathname: '/7x24/' })).toBe('/api/auth/login?redirect=' + encodeURIComponent('https://demo.example.invalid/7x24/')); });
  it('sends the selected workspace on every request and on SSE', async () => {
    if (!(globalThis as any).localStorage?.setItem) { const m = new Map<string, string>(); Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => { m.set(k, v); }, removeItem: (k: string) => { m.delete(k); } } }); }
    setToken('tok'); setWorkspace('ws_b');
    expect(authHeaders()).toEqual({ authorization: 'Bearer tok', 'x-workspace': 'ws_b' });
    const calls: any[] = [];
    const f = (async (u: string, init: any) => { calls.push([u, init]); return new Response(JSON.stringify({ ticket: 'tk1', expiresIn: 60 }), { status: 200 }); }) as any;
    const url = await eventsUrl(f);
    expect(url).toBe('/api/events?ticket=tk1&ws=ws_b'); expect(url).not.toContain('tok&');
    expect(calls[0][0]).toBe('/api/events/ticket'); expect(calls[0][1].method).toBe('POST'); expect(calls[0][1].headers.authorization).toBe('Bearer tok');
    await expect(eventsUrl((async () => new Response('', { status: 401 })) as any)).rejects.toThrow('unauthorized');
    setWorkspace(null); setToken(null);
    expect(authHeaders()).toEqual({}); expect(await eventsUrl(f)).toBe('/api/events'); expect(calls).toHaveLength(1);
  });
  it('keeps absolute urls', () => { expect(apiUrl('https://x/y')).toBe('https://x/y'); expect(apiUrl('/api/meta')).toBe('/api/meta'); });
});

import { securityNotice } from '../views/Integrations';
describe('securityNotice', () => {
  it('reflects allowlist', () => { expect(securityNotice({ required: true, allowlist: true })).toContain('只有名单里的账号'); expect(securityNotice({ required: true, allowlist: false })).toContain('还没设置允许登录的账号名单'); expect(securityNotice({ required: false, allowlist: false })).toContain('已关闭'); });
});
