import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import type { TaskDTO } from '../api';
import { DeliverySummary } from '../components/DeliverySummary';
import { executionStateName } from '../catalog';
import { taskDot, progressSteps } from '../lib/commandCenter';
import { progressActivity, stripNodes, type PlanData } from '../lib/taskData';
import { statusName } from '../labels';

const task = { projectId: 'miniapp', status: 'DELIVERED', currentStage: 'DELIVERED', executionState: 'WAITING_CONFIGURATION', path: [], paused: false,
  delivery: { target: 'wechat_miniprogram', githubRelease: 'NOT_CREATED', deployment: 'WAITING_CONFIGURATION', detail: '微信发布能力尚未接入。' } } as unknown as TaskDTO;
const plan: PlanData = { status: 'DELIVERED', executionState: 'WAITING_CONFIGURATION', currentNode: 'RELEASE', records: {}, graph: { schema: 2, edges: [], nodes: [
  { id: 'DEV', label: '开发', stage: 'DEV', module: 'production' },
  { id: 'RELEASE', label: '发布', stage: 'RELEASE', module: 'delivery' },
] } };

describe('release target presentation', () => {
  it('keeps configuration waits visible as waiting across task rows and progress indicators', () => {
    expect(executionStateName(task.executionState)).toBe('待配置发布');
    expect(taskDot(task)).toBe('waiting');
    expect(progressSteps(task).at(-1)?.state).toBe('blocked');
    const steps = stripNodes(plan, task)!;
    expect(steps.at(-1)?.state).toBe('blocked');
    expect(progressActivity(task, plan, steps)).toMatchObject({ mode: 'blocked', text: '待配置发布' });
  });
  it('shows independent repository and platform delivery states with a configuration link', () => {
    const html = renderToStaticMarkup(<DeliverySummary task={task} />);
    expect(html).toContain('微信小程序'); expect(html).toContain('待配置发布');
    expect(html).toContain('未创建'); expect(html).toContain('/p/miniapp/detail');
    const withRelease = { ...task, status: 'RELEASED' as const, delivery: { ...task.delivery!, githubRelease: 'VERIFIED' as const } };
    expect(taskDot(withRelease)).toBe('waiting');
    const released = renderToStaticMarkup(<DeliverySummary task={withRelease} />);
    expect(released).toContain('已创建并校验'); expect(released).toContain('待配置发布');
  });
  it('does not call a GitHub release a platform launch', () => {
    const repository = { ...task, delivery: { target: 'github_release', githubRelease: 'VERIFIED' as const, deployment: 'NOT_REQUESTED' as const, detail: '仅发布仓库版本。' } };
    const html = renderToStaticMarkup(<DeliverySummary task={repository} />);
    expect(statusName('RELEASED')).toBe('版本已发布');
    expect(html).toContain('未验证'); expect(html).not.toContain('已上线');
  });
});
