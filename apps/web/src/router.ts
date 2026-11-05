'use client';

import { useCallback } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { navigationTarget } from './lib/navigation';

/** 旧路由重定向（D8：至少保留 2 个版本）：#/chat… → 新对话 / 会话视图；不是旧路由返回 null */
export function legacyRedirect(hash: string): string | null {
  const m = hash.match(/^#?\/?chat(?:\/([^?]*))?$/); return m ? (m[1] ? `/s/${m[1]}` : '/new') : null;
}
export function parseRoute(hash: string): string[] {
  const [path, query] = hash.replace(/^#?\/?/, '').split('?');
  const parts = (path || 'overview').split('/').map(x => { try { return decodeURIComponent(x); } catch { return x; } });
  if (parts[0] === 't' && parts[2] === 'detail' && query) { const q = new URLSearchParams(query); if (q.get('tab')) parts[3] = q.get('tab')!; if (q.get('focus')) parts[4] = q.get('focus')!; }
  if (parts[0] === 'task' && query) {
    const q = new URLSearchParams(query); parts[2] = q.get('tab') ?? parts[2] ?? 'timeline';
    if (q.has('focus')) parts[3] = q.get('focus')!;
  }
  return parts;
}
/** Existing view props use a thin adapter; App Router owns navigation and history. */
export function useRoute(): [string[], (to: string) => void] {
  const pathname = usePathname(); const search = useSearchParams(); const router = useRouter();
  const go = useCallback((to: string) => router.push(navigationTarget(to)), [router]);
  return [parseRoute(`${pathname}${search.size ? '?' + search.toString() : ''}`), go];
}
