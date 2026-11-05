import { afterEach, describe, expect, it, vi } from 'vitest';
import type { TaskDTO } from '../api';
import { createWorkspaceData } from './workspaceData';

afterEach(() => vi.unstubAllGlobals());

const task = { id: 'T 9/新', title: '新建需求', status: 'BACKLOG' } as TaskDTO;
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status });
function deferred() {
  let resolve!: (response: Response) => void;
  const promise = new Promise<Response>(r => { resolve = r; });
  return { promise, resolve };
}

describe('工作区任务补查', () => {
  it('列表命中无需补查，同一缺失任务的并发查询只请求一次，并正确编码 ID', async () => {
    const response = deferred();
    const fetch = vi.fn().mockResolvedValueOnce(json([])).mockReturnValueOnce(response.promise);
    vi.stubGlobal('fetch', fetch);
    const store = createWorkspaceData(); await store.loadTasks();
    const first = store.getSnapshot().loadTask(task.id), second = store.getSnapshot().loadTask(task.id);
    expect(first).toBe(second);
    expect(fetch).toHaveBeenLastCalledWith(`/api/tasks/${encodeURIComponent(task.id)}`, expect.anything());
    response.resolve(json({ task })); await first;
    await store.getSnapshot().loadTask(task.id);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('列表未收录补查任务时保留它，列表收录后采用轮询的新状态', async () => {
    const updated = { ...task, status: 'DEV' };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ task })).mockResolvedValueOnce(json([])).mockResolvedValueOnce(json([updated])));
    const store = createWorkspaceData(); await store.getSnapshot().loadTask(task.id);
    await store.loadTasks(); expect(store.getSnapshot().tasks).toEqual([task]);
    await store.loadTasks(); expect(store.getSnapshot().tasks).toEqual([updated]);
    expect(store.getSnapshot().tasks).toHaveLength(1);
  });

  it('较早发出的列表请求迟到时，不覆盖后发请求的新数据', async () => {
    const response = deferred();
    vi.stubGlobal('fetch', vi.fn().mockReturnValueOnce(response.promise).mockResolvedValueOnce(json([task])));
    const store = createWorkspaceData(); const first = store.loadTasks();
    await store.loadTasks(); response.resolve(json([])); await first;
    expect(store.getSnapshot().tasks).toEqual([task]);
  });

  it('列表网络失败不影响按 ID 打开任务，补查网络失败后也可以重试', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new TypeError('Failed to fetch')).mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValueOnce(json({ task })));
    const store = createWorkspaceData(); await store.loadTasks();
    await store.getSnapshot().loadTask(task.id);
    expect(store.getSnapshot().taskLookups[task.id]).toEqual({ status: 'error', message: 'Failed to fetch' });
    await store.getSnapshot().loadTask(task.id);
    expect(store.getSnapshot().tasks).toEqual([task]);
  });

  it('切换工作区后，同 ID 任务的迟到结果和旧列表只更新旧工作区实例', async () => {
    const oldTask = deferred(), oldList = deferred();
    const currentTask = { ...task, title: '当前工作区的任务' };
    let workspace = 'old';
    vi.stubGlobal('localStorage', { getItem: (key: string) => key === 'x7_ws' ? workspace : null });
    const fetch = vi.fn().mockReturnValueOnce(oldTask.promise).mockReturnValueOnce(oldList.promise).mockResolvedValueOnce(json({ task: currentTask }));
    vi.stubGlobal('fetch', fetch);
    const old = createWorkspaceData(); const taskRequest = old.getSnapshot().loadTask(task.id), listRequest = old.loadTasks();
    workspace = 'current';
    const current = createWorkspaceData(); await current.getSnapshot().loadTask(task.id);
    const currentSnapshot = current.getSnapshot();
    oldTask.resolve(json({ task })); oldList.resolve(json([task])); await Promise.all([taskRequest, listRequest]);
    expect(current.getSnapshot()).toBe(currentSnapshot);
    expect(current.getSnapshot().tasks).toEqual([currentTask]);
    expect(fetch.mock.calls.map(call => call[1].headers['x-workspace'])).toEqual(['old', 'old', 'current']);
  });
});
