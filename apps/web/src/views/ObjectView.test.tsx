import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import ObjectView, { type ObjectViewProps } from './ObjectView';
import { createWorkspaceData } from '../lib/workspaceData';
import { makeBinder } from '../lib/session';
import { api, type TaskDTO } from '../api';

afterEach(() => vi.unstubAllGlobals());

const props: Omit<ObjectViewProps, 'data'> = {
  r: { kind: 'task', id: 'T-new', tab: 'chat', rest: [] }, role: 'viewer',
  meta: null, current: null, go() {}, refresh: async () => {}, onCreateTask() {},
};
const task: TaskDTO = {
  id: 'T-new', title: '新建需求', projectId: 'p1', projectName: '项目', productLine: 'l1', productLineName: '产品线',
  status: 'BACKLOG', currentStage: 'BACKLOG', executionState: 'WAITING', path: [], problem: '', acceptanceCriteria: '',
  pipelineVersion: 'v1', projectConfigVersion: 1, priorityScore: 0, riskLevel: 'low', scopeLevel: 0, strategyVariant: 0,
  currentIteration: 1, loopCount: 0, rejectCount: 0, reviewRetry: 0, rollbackCount: 0, requeueCount: 0, degradeCount: 0, healCount: 0,
  discardReason: null, cooldownUntil: null, parentTaskId: null, paused: false, budgetTokens: 1000, spentTokens: 0, spentUsd: 0, budgetUsd: 3,
  createdAt: '2026-10-26T00:00:00Z', statusChangedAt: '2026-10-26T00:00:00Z', releasedAt: null, nextRunAt: null,
};
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status });
const render = (store: ReturnType<typeof createWorkspaceData>, id = task.id) => {
  vi.stubGlobal('location', { pathname: `/t/${id}/chat`, search: '' });
  return renderToStaticMarkup(<ObjectView {...props} r={{ ...props.r, id }} data={store.getSnapshot()} />);
};

describe('任务入口的数据尚未同步', () => {
  it('passes blocked execution state to the header and offers recovery instead of pause', () => {
    vi.stubGlobal('location', { pathname: `/t/${task.id}/chat`, search: '' });
    const blocked = { ...task, status: 'ANOMALY' as const, currentStage: 'PRD_REVIEW' as const, executionState: 'FAILED' as const };
    const data = { ...createWorkspaceData().getSnapshot(), tasks: [blocked] };
    const html = renderToStaticMarkup(<ObjectView {...props} role="owner" data={data} />);
    expect(html).not.toContain('>暂停</button>'); expect(html).toContain('>恢复执行</button>');
  });
  it('新任务尚未出现在列表时，先显示加载而不是找不到任务', () => {
    vi.stubGlobal('location', { pathname: '/t/T-new/chat', search: '' });
    const data = { ...createWorkspaceData().getSnapshot(), tasks: [] };
    const html = renderToStaticMarkup(<ObjectView {...props} data={data} />);
    expect(html).not.toContain('找不到这个任务');
    expect(html).toContain('加载中');
  });

  it.each(['确认创建后跳转', '会话自动绑定'] as const)('%s：慢请求和旧列表刷新期间均不闪现不存在', async entry => {
    let resolve!: (response: Response) => void;
    const response = new Promise<Response>(r => { resolve = r; });
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/api/tasks?')) return json([]);
      if (url === '/api/chats/c1') return json({ id: 'c1', task_id: task.id });
      if (url === `/api/tasks/${task.id}`) return response;
      throw new Error(`unexpected request: ${url}`);
    }));
    const store = createWorkspaceData(); await store.loadTasks();
    const frames: string[] = []; let target = `t/${task.id}/chat`;
    if (entry === '会话自动绑定') {
      await makeBinder({ fetch: id => api<{ id: string; task_id: string }>(`/api/chats/${id}`), replace: to => { target = to; }, show: vi.fn() })('c1');
    }
    expect(target).toBe(`t/${task.id}/chat`);
    frames.push(render(store));
    const off = store.subscribe(() => frames.push(render(store)));
    try {
      const pending = store.getSnapshot().loadTask(task.id);
      expect(render(store)).toContain('加载中');
      await store.loadTasks(); // 补查未完成，15 秒轮询仍返回旧列表
      resolve(json({ task })); await pending;
      expect(render(store)).toContain('data-testid="object-task"');
      expect(store.getSnapshot().tasks).toContainEqual(task); // 侧栏立即共享新任务
      await store.loadTasks(); // 任务暂不在列表范围内时，已补查结果仍可展示
      expect(render(store)).toContain(task.title);
      expect(frames.every(html => !html.includes('找不到这个任务'))).toBe(true);
    } finally { off(); }
  });

  it('只有按 ID 查询返回 404 才展示不存在，初始列表尚未加载也能判定', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: '找不到这条记录', code: 'not_found' }, 404)));
    const store = createWorkspaceData();
    expect(render(store)).toContain('加载中');
    await store.getSnapshot().loadTask(task.id);
    expect(render(store)).toContain('找不到这个任务');
    expect(render(store)).not.toContain('加载中');
  });

  it.each([403, 503])('%s 是加载失败，提供重试且不误报任务不存在', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ error: status === 403 ? '无权访问这个任务' : '服务暂不可用' }, status)).mockResolvedValueOnce(json({ task })));
    const store = createWorkspaceData(); await store.getSnapshot().loadTask(task.id);
    const html = render(store);
    expect(html).toContain('任务加载失败'); expect(html).toContain('重试');
    expect(html).not.toContain('找不到这个任务');
    const retry = store.getSnapshot().loadTask(task.id);
    expect(render(store)).toContain('加载中');
    await retry;
    expect(render(store)).toContain(task.title);
  });

  it('从任务 A 切换到任务 B 后，A 的迟到响应不会替换 B', async () => {
    let resolve!: (response: Response) => void;
    const response = new Promise<Response>(r => { resolve = r; });
    const other = { ...task, id: 'T-other', title: '另一个任务' };
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith(task.id) ? response : json({ task: other })));
    const store = createWorkspaceData(); const pending = store.getSnapshot().loadTask(task.id);
    await store.getSnapshot().loadTask(other.id);
    resolve(json({ task })); await pending;
    const html = render(store, other.id);
    expect(html).toContain(other.title); expect(html).not.toContain(task.title);
  });
});
