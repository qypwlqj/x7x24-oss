/** 侧栏树、对象页、待我处理共用的数据：任务列表（15s）+ 预算用量（60s）。只读接口，无后端改动 */
import { useEffect, useMemo, useSyncExternalStore } from 'react';
import { api, ApiError, type TaskDTO } from '../api';
import { budgetLevel, budgetPct, type BudgetLevel } from './nav';

export interface Usage { usd: number; tokens: number; ci: number }
export interface ScopeBudget { id: string; name: string; productLineId?: string; daily: Usage; limits: { dailyUsd: number | null; dailyTokens?: number | null; dailyCiMinutes?: number | null }; status?: { level?: string; reason?: string } }
export interface BudgetData { day?: string; global?: { daily?: Usage; limits?: any }; lines: ScopeBudget[]; projects: ScopeBudget[] }
export type TaskLookup = { status: 'loading' } | { status: 'ready'; task: TaskDTO } | { status: 'not_found' } | { status: 'error'; message: string };
export interface WorkspaceData {
  tasks: TaskDTO[] | null; budget: BudgetData | null; reload: () => void;
  taskLookups: Readonly<Record<string, TaskLookup | undefined>>;
  loadTask: (id: string) => Promise<void>;
}

/** 每个工作区独立持有列表与按 ID 补查的任务；旧请求只会更新它所属的实例。 */
export function createWorkspaceData() {
  const listeners = new Set<() => void>();
  const pending = new Map<string, Promise<void>>();
  let listed: TaskDTO[] | null = null, listRequest = 0, budgetRequest = 0;
  let snapshot: WorkspaceData;
  const publish = (patch: Partial<Pick<WorkspaceData, 'budget' | 'taskLookups'>> = {}) => {
    const next = { ...snapshot, ...patch };
    const tasks = new Map((listed ?? []).map(t => [t.id, t]));
    for (const lookup of Object.values(next.taskLookups)) {
      if (lookup?.status === 'ready' && !tasks.has(lookup.task.id)) tasks.set(lookup.task.id, lookup.task);
    }
    snapshot = { ...next, tasks: listed === null && tasks.size === 0 ? null : [...tasks.values()] };
    listeners.forEach(fn => fn());
  };
  const loadTasks = async () => {
    const request = ++listRequest;
    try {
      const tasks = await api<TaskDTO[]>('/api/tasks?limit=300');
      if (request !== listRequest) return;
      listed = tasks;
      // 列表已接住的任务继续由轮询更新，补查缓存只保留列表范围之外的任务。
      const taskLookups = { ...snapshot.taskLookups };
      for (const task of tasks) delete taskLookups[task.id];
      publish({ taskLookups });
    } catch { if (request === listRequest) { listed ??= []; publish(); } }
  };
  const loadBudget = async () => {
    const request = ++budgetRequest;
    try {
      const b = await api<BudgetData>('/api/budget');
      if (request === budgetRequest) publish({ budget: { ...b, lines: b?.lines ?? [], projects: b?.projects ?? [] } });
    } catch { /* 保留上次的预算快照 */ }
  };
  const loadTask = (id: string): Promise<void> => {
    const existing = pending.get(id); if (existing) return existing;
    if (snapshot.tasks?.some(t => t.id === id)) return Promise.resolve();
    publish({ taskLookups: { ...snapshot.taskLookups, [id]: { status: 'loading' } } });
    const request = api<{ task: TaskDTO }>(`/api/tasks/${encodeURIComponent(id)}`).then(
      ({ task }) => publish({ taskLookups: { ...snapshot.taskLookups, [id]: { status: 'ready', task } } }),
      (error: unknown) => publish({ taskLookups: { ...snapshot.taskLookups, [id]: error instanceof ApiError && error.status === 404
        ? { status: 'not_found' } : { status: 'error', message: error instanceof Error ? error.message : '请求失败，请稍后重试' } } }),
    ).finally(() => { pending.delete(id); });
    pending.set(id, request); return request;
  };
  const reload = () => { void loadTasks(); void loadBudget(); };
  snapshot = { tasks: null, budget: null, taskLookups: {}, loadTask, reload };
  return {
    getSnapshot: () => snapshot,
    subscribe: (fn: () => void) => { listeners.add(fn); return () => { listeners.delete(fn); }; },
    loadTasks, loadBudget,
  };
}

export function useWorkspaceData(enabled: boolean, key?: string): WorkspaceData {
  const store = useMemo(() => createWorkspaceData(), [enabled, key]);
  useEffect(() => {
    if (!enabled) return;
    store.getSnapshot().reload();
    const a = setInterval(store.loadTasks, 15000), b = setInterval(store.loadBudget, 60000);
    return () => { clearInterval(a); clearInterval(b); };
  }, [enabled, store]);
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot);
}

export function scopeBudget(b: BudgetData | null, kind: 'line' | 'project', id: string): { level: BudgetLevel; pct: number; used: number; limit: number | null } {
  const s = (kind === 'line' ? b?.lines : b?.projects)?.find(x => x.id === id);
  const used = s?.daily?.usd ?? 0, limit = s?.limits?.dailyUsd ?? null;
  return { level: budgetLevel(used, limit), pct: budgetPct(used, limit), used, limit };
}
export function taskBudget(t: Pick<TaskDTO, 'spentUsd' | 'budgetUsd'>) { return { level: budgetLevel(t.spentUsd, t.budgetUsd), pct: budgetPct(t.spentUsd, t.budgetUsd), used: t.spentUsd ?? 0, limit: t.budgetUsd || null }; }
