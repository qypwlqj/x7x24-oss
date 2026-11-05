import { describe, expect, it } from 'vitest';
import { runningText } from './CommandCenter';
import type { TaskDTO } from '@x7x24/shared';
import type { PlanData } from '../lib/taskData';

const task = (over: Partial<TaskDTO>) => ({ id: 't', status: 'DEV', currentStage: 'DEV', executionState: 'RUNNING', paused: false, ...over }) as TaskDTO;
describe('runningText', () => {
  it('shows the running line for an active task', () => { expect(runningText(task({}))).toMatch(/^正在/); });
  it('uses the same node name as the progress strip, including child checks', () => {
    expect(runningText(task({}))).toContain('正在编码');
    expect(runningText(task({}), [], { status: 'QA', currentNode: 'CHECK', records: {}, graph: { schema: 2, edges: [], nodes: [{ id: 'CHECK', stage: 'QA', parent: 'QA', label: '安全检查' }] } })).toContain('正在安全检查');
  });
  it.each([null, { status: 'QA', currentNode: 'QA', records: {}, graph: { schema: 2, edges: [], nodes: [{ id: 'QA', stage: 'QA', label: '质量测试', executor: 'system' }] } } satisfies PlanData])('identifies CI testing without model calls, including legacy tasks (plan: %j)', plan => {
    expect(runningText(task({ status: 'QA', currentStage: 'QA' }), [], plan)).toBe('等待 CI 测试结果 · GitHub Actions');
  });
  it('keeps the name of an automatic child check without implying a missing model', () => {
    const plan: PlanData = { status: 'QA', currentNode: 'CHECK', records: {}, graph: { schema: 2, edges: [], nodes: [
      { id: 'QA', stage: 'QA', label: '测试', executor: 'system' },
      { id: 'CHECK', stage: 'QA', parent: 'QA', label: '安全检查', executor: 'check' },
    ] } };
    expect(runningText(task({ status: 'QA', currentStage: 'QA' }), [], plan)).toBe('正在安全检查…');
  });
  it('uses the configured model executor rather than inferring CI from the QA stage', () => {
    const plan: PlanData = { status: 'QA', currentNode: 'QA', records: {}, graph: { schema: 2, edges: [], nodes: [
      { id: 'QA', stage: 'QA', label: '测试评审', executor: 'review' },
    ] } };
    expect(runningText(task({ status: 'QA', currentStage: 'QA' }), [], plan)).toBe('正在测试评审…');
  });
  it('hides it for discarded / delivered / released / stopped tasks', () => {
    expect(runningText(task({ status: 'DISCARDED', currentStage: 'DISCARDED' as any, executionState: 'DISCARDED' }))).toBeNull();
    expect(runningText(task({ status: 'DISCARDED', executionState: 'WAITING' }))).toBeNull();
    expect(runningText(task({ status: 'RELEASED' }))).toBeNull();
    expect(runningText(task({ executionState: 'FAILED' }))).toBeNull();
    expect(runningText(task({ paused: true }))).toBeNull();
  });
});
