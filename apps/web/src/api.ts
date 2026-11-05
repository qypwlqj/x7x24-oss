import type { BoardResponse, TaskDTO, ProjectDTO, CatalogState, BudgetLimits } from '@x7x24/shared';

import { apiUrl, authHeaders, notifyUnauthorized } from './lib/auth';
import { githubFailure, notifyGitHubChanged } from './lib/githubConnection';

/** 接口错误：message 是给人看的中文说明；code / params / detail 供「详情」展示与程序判断 */
export class ApiError extends Error {
  constructor(message: string, public status: number, public code?: string, public params?: Record<string, unknown>, public detail?: string, public data?: any) { super(message); this.name = 'ApiError'; }
}
export const errorDetail = (e: unknown): string | undefined => { const x = e as ApiError; if (!(x instanceof ApiError)) return undefined; const parts = [x.code && `错误码：${x.code}`, x.detail, x.params && JSON.stringify(x.params)].filter(Boolean); return parts.length ? parts.join('\n') : undefined; };

/** 有的接口 error 是错误码、说明在 message 里；有的 error 直接是中文说明 */
const isCode = (x: unknown) => typeof x === 'string' && /^[a-z][a-z0-9_]*$/.test(x);
const errorMessage = (d: any, status: number) => (d?.message && (isCode(d?.error) || !d?.error) ? String(d.message) : d?.error && !isCode(d.error) ? String(d.error) : `请求失败（${status}），请稍后重试`);

export async function api<T = any>(path: string, init?: { method?: string; body?: unknown; signal?: AbortSignal }): Promise<T> {
  const headers = authHeaders();
  const res = await fetch(apiUrl(path), {
    method: init?.method ?? 'GET',
    headers: { ...headers, ...(init?.body !== undefined ? { 'content-type': 'application/json' } : {}) },
    body: init?.body !== undefined ? JSON.stringify(init.body) : undefined,
    credentials: 'include',
    signal: init?.signal,
  });
  const data = await res.json().catch(() => ({}));
  if (isSessionExpired(res.status, path, data)) notifyUnauthorized();
  if (!res.ok) { const connection = githubFailure(data); if (connection) notifyGitHubChanged(connection, headers['x-workspace'] ?? null); }
  else if (path.startsWith('/api/integrations/github/repos') || path === '/api/integrations/github/token' || path.includes('/integrations/github/device/poll') && data.status === 'connected') notifyGitHubChanged(undefined, headers['x-workspace'] ?? null);
  if (!res.ok) throw new ApiError(errorMessage(data, res.status), res.status, data?.code ?? (isCode(data?.error) ? data.error : undefined), data?.params, data?.detail ?? (data?.issues ? JSON.stringify(data.issues[0]) : undefined), data);
  return data as T;
}

export interface LineMeta { id: string; name: string; description?: string; owner?: string; state: CatalogState; autonomy: 'L1' | 'L2' | 'L3'; paused: boolean; intakePausedUntil: string | null; config: { budget: BudgetLimits }; projectCount?: number; taskCount?: number; spentUsd?: number }
export interface Meta {
  now: string; range: { from: string; to: string }; lines: LineMeta[]; projects: ProjectDTO[]; killSwitches: any[];
  nodes: { id: string; label: string; statuses: string[]; lane: string }[]; models: { exec: string; judges: string[]; arbiter: string }; configVersion: number; live?: boolean; adapterMode?: string; connected?: boolean; executeMode?: boolean;
}
export type { BoardResponse, TaskDTO, ProjectDTO };

export const fmtTime = (iso?: string | null) => (iso ? iso.replace('T', ' ').slice(5, 16) : '-');
export const pct = (x: number) => `${Math.round(x * 100)}%`;

export interface AdapterStatus {
  mode: 'offline' | 'real'; dryRun: boolean; ciDryRun?: boolean; tokenConfigured?: boolean; apiHost?: string; prometheusConfigured?: boolean;
  lines: {
    id: string; name: string; repo: string | null; baseBranch: string | null;
    git: { mode: 'offline' | 'real'; dryRun: boolean; reason: string | null }; ci: { mode: 'offline' | 'real'; dryRun: boolean; reason: string | null; workflow: string };
    metrics: { mode: 'offline' | 'real'; queries: string[] };
    connection?: { ok: boolean; error?: string; checkedAt?: string; private?: boolean; defaultBranch?: string; url?: string; permissions?: Record<string, boolean> };
    intake?: { label: string; all: boolean; enabled: boolean };
    rate: null | { limit: number | null; remaining: number | null; resetAt: string | null; retries: number; throttledMs: number; lastStatus: number | null; lastRetryAt: string | null; lastError: string | null };
  }[];
  stats: { adapter: string; calls: number; failures: number; dry_runs: number }[];
  recentCalls: { id: number; at: string; adapter: string; op: string; method: string | null; path: string | null; status: number | null; ok: number; dry_run: number; duration_ms: number; line: string | null; project_id?: string | null; note: string | null }[];
}
export const fmtFull = (iso?: string | null) => (iso ? iso.replace('T', ' ').slice(0, 19) : '-');

/** 只有本站会话失效（后端统一返回 error: "unauthorized"）才登出；第三方（如 GitHub）401 不算 */
export function isSessionExpired(status: number, path: string, data: any): boolean {
  return status === 401 && !path.startsWith('/api/auth/') && data?.error === 'unauthorized' && data?.githubStatus === undefined;
}
