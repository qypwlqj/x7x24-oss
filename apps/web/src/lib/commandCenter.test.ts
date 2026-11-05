import { describe, expect, it } from 'vitest';
import { buildTree, commandView, progressSteps, sessionForTask, sessionRedirect, taskDot } from './commandCenter';
import { parseRoute } from '../router';

const T = (o: any) => ({ id: 't', projectId: 'p1', status: 'DEV', currentStage: 'DEV', executionState: 'RUNNING', paused: false, path: [], statusChangedAt: '2026-01-01', ...o });
describe('指挥台 v2 M1', () => {
  it('状态圆点', () => {
    expect(taskDot(T({ executionState: 'BLOCKED' }) as any)).toBe('blocked');
    expect(taskDot(T({ paused: true }) as any)).toBe('waiting');
    expect(taskDot(T({}) as any)).toBe('running');
    expect(taskDot(T({ status: 'BACKLOG', executionState: 'WAITING' }) as any)).toBe('idle');
    expect(taskDot(T({ status: 'RELEASED' }) as any)).toBe('done');
  });
  it('工作区树：产品线→项目→任务，阻塞的总是显示', () => {
    const tasks = Array.from({ length: 8 }, (_, i) => T({ id: 'a' + i, executionState: i === 7 ? 'BLOCKED' : 'COMPLETED', status: i === 7 ? 'QA' : 'RELEASED' }));
    const tree = buildTree([{ id: 'l1', name: '教育' }], [{ id: 'p1', productLineId: 'l1', name: '官网' } as any], tasks as any, 3);
    expect(tree[0].projects[0].tasks[0].id).toBe('a7');
    expect(tree[0].projects[0].tasks.length).toBe(3); expect(tree[0].projects[0].hidden).toBe(5);
    expect(buildTree([{ id: 'l1', name: '教育' }], [{ id: 'p1', productLineId: 'l1' } as any], tasks as any, 3, new Set(['p1']))[0].projects[0].hidden).toBe(0);
  });
  it('进度条：当前节点按执行状态标阻塞', () => {
    const s = progressSteps(T({ status: 'QA', currentStage: 'QA', executionState: 'BLOCKED' }) as any);
    expect(s.find(x => x.stage === 'DEV')!.state).toBe('done'); expect(s.find(x => x.stage === 'QA')!.state).toBe('blocked'); expect(s.at(-1)!.state).toBe('pending');
    expect(progressSteps(T({ status: 'RELEASED', currentStage: 'RELEASED' }) as any).every(x => x.state === 'done')).toBe(true);
  });
  it('路由', () => {
    expect(commandView(parseRoute('/command'))).toEqual({ kind: 'home' });
    expect(commandView(parseRoute('/command/t/T-1'))).toEqual({ kind: 'task', taskId: 'T-1' });
    expect(commandView(parseRoute('/command/t/T-1/node/DEV'))).toEqual({ kind: 'node', taskId: 'T-1', nodeId: 'DEV' });
    expect(commandView(parseRoute('/command/chat_1'))).toEqual({ kind: 'session', sessionId: 'chat_1' });
  });
  it('旧会话链接跳到绑定的任务', () => {
    expect(sessionRedirect({ task_id: 'T-9' })).toBe('t/T-9/chat'); expect(sessionRedirect({ task_id: null })).toBeNull();
    expect(sessionForTask([{ task_id: 'x', updated_at: '1' }, { task_id: 'x', updated_at: '2' }, { task_id: 'y', updated_at: '3' }], 'x')!.updated_at).toBe('2');
  });
});
