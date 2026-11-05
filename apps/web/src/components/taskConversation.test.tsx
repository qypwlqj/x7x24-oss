import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import type { TaskDTO } from '../api';
import { TaskStatus, taskStatusLabel } from './TaskInfo';
import { Timeline } from './Timeline';
import { buildCards, mergeTimeline, type TaskEnvelope } from '../lib/timeline';
import { TaskStreamStatus } from '../views/CommandCenter';

const task = { id: 'task', status: 'DEV', currentStage: 'DEV', executionState: 'RUNNING', paused: false } as TaskDTO;

describe('轻量会话保留需要处理的信息', () => {
  it('运行中仅显示图标，失败、暂停和发布待配置始终显示文字', () => {
    const running = renderToStaticMarkup(<TaskStatus task={task} />);
    expect(running).toContain('aria-label="运行中，查看状态详情"');
    expect(running).not.toContain('>运行中</span>');
    expect(taskStatusLabel({ ...task, status: 'RELEASED', executionState: 'COMPLETED' })).toBe('版本已发布');
    expect(taskStatusLabel({ ...task, status: 'DELIVERED', executionState: 'WAITING' })).toBe('待发布');
    for (const [extra, label] of [
      [{ executionState: 'FAILED' }, '执行失败'], [{ paused: true }, '已暂停'],
      [{ status: 'RELEASED', executionState: 'WAITING_CONFIGURATION' }, '待配置发布'],
    ] as const) {
      const t = { ...task, ...extra } as TaskDTO;
      expect(taskStatusLabel(t)).toBe(label);
      expect(renderToStaticMarkup(<TaskStatus task={t} />)).toContain(`>${label}</span>`);
    }
  });
  it('折叠节点切换记录，但不会把失败原因、审批卡或用户指令一起隐藏', () => {
    const events: TaskEnvelope[] = [
      { seq: 1, taskId: 'task', type: 'STATUS_CHANGED', to: 'DEV', at: '2026-10-31T01:00:00Z' },
      { seq: 2, taskId: 'task', type: 'EVIDENCE_ADDED', at: '2026-10-31T01:01:00Z', evidence: { id: 'ci', kind: 'ci_log', stage: 'QA', content: { state: 'failed', log: 'FAIL: export is missing' } } },
      { seq: 3, taskId: 'task', type: 'MERGE_APPROVAL_REQUESTED', at: '2026-10-31T01:02:00Z', data: {} },
    ];
    const entries = mergeTimeline(buildCards(events), [{ id: 'msg', role: 'user', text: '保留现有接口', at: '2026-10-31T01:03:00Z' }]);
    const html = renderToStaticMarkup(<Timeline entries={entries} taskId="task" compact />);
    expect(html).toContain('执行记录（1）');
    expect(html).not.toContain('进入「开发执行」');
    expect(html).toContain('FAIL: export is missing');
    expect(html).toContain('data-kind="approval.waiting"');
    expect(html).toContain('保留现有接口');
  });
  it('正常实时连接不占文字行，轮询降级明确提示', () => {
    const live = renderToStaticMarkup(<TaskStreamStatus mode="live" />);
    expect(live).toContain('aria-label="实时连接正常，自动同步"');
    expect(live).not.toContain('role="status"');
    expect(renderToStaticMarkup(<TaskStreamStatus mode="polling" />)).toContain('>实时连接恢复中，每 3 秒同步进度</span>');
    expect(renderToStaticMarkup(<TaskStreamStatus mode="offline" />)).toContain('网络离线，等待恢复');
    expect(renderToStaticMarkup(<TaskStreamStatus mode="blocked" state={{ events: [], seq: 0, mode: 'blocked', terminalStatus: 401 }} />)).toContain('登录已失效，请重新登录');
  });
});
