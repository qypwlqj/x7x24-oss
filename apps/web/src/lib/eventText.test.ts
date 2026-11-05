import { describe, it, expect } from 'vitest';
import { describeEvent, anomalyText, parseSize } from './eventText';

describe('event copy', () => {
  it('describes manual repair rounds without promising automatic model switching or replanning', () => {
    for (const cause of ['same_failure', 'rework_exhausted']) {
      const event = anomalyText(cause, '检查失败');
      expect(event.text).toContain('手动恢复'); expect(event.text).toContain('新一轮返修额度');
      expect(event.text).not.toMatch(/备用模型|重新规划/);
    }
    const event = describeEvent({ event_type: 'TASK_RESUMED', payload: { reworkReset: { after: { round: 2 } } } });
    expect(event.title).toBe('手动恢复，开始新一轮返修'); expect(event.text).toContain('第 2 轮');
    expect(describeEvent({ event_type: 'TASK_RESUMED', payload: {} }).title).toBe('手动恢复执行');
  });
  it('explains missing CI and Actions permissions without model retries', () => {
    for (const category of ['ci_broken', 'ci_permission', 'project_not_ready']) {
      const event = describeEvent({ event_type: 'AGENT_FAILED', payload: { category, retryable: false, error: '缺少项目初始化配置' } });
      expect(event.title).toContain('暂停');
      expect(event.actions?.some(a => a.kind === 'link')).toBe(true);
      expect(event.text).not.toMatch(/切换模型|重新规划|自动重试/);
    }
  });
  it('shows a verified repository baseline as ready', () => {
    const event = describeEvent({ event_type: 'REPOSITORY_INITIALIZED', payload: { repo: 'owner/repo', branch: 'main', sha: 'a'.repeat(40) } });
    expect(event.title).toBe('仓库基线已就绪'); expect(event.text).toContain('owner/repo'); expect(event.text).toContain('main');
  });
  it('change_too_large from legacy detail text', () => {
    const t = describeEvent({ event_type: 'ANOMALY_OPENED', payload: { type: 'change_too_large', detail: '13 个文件 / 加权 124 行（原始 124 行；上限 20 文件 / 100 行） 超过上限（上限来源：治理配置）' } });
    expect(t.title).toBe('改动量超出限制');
    expect(t.text).toBe('本次改了 13 个文件、124 行，超过了治理配置里设定的每次最多 100 行。系统将自动精简 / 拆分；你也可以在「治理配置」调整上限。');
    expect(t.actions?.map(a => a.label)).toEqual(['去治理配置', '恢复执行']);
  });
  it('uses structured facts incl. segment', () => {
    expect(anomalyText('change_too_large', '', { rawFiles: 3, files: 3, weightedLines: 130, maxLines: 100, maxFiles: 20, segment: '2/5' }).text).toContain('第 2/5 段');
    expect(parseSize('加权 5 个文件（实际 30 个） / 加权 57 行（原始 300 行；上限 20 文件 / 1500 行）')?.maxLines).toBe(1500);
  });
  it('agent failure and heal are human readable', () => {
    expect(describeEvent({ event_type: 'AGENT_FAILED', payload: { attempt: 2, error: 'LLM 输出重试后仍不合格（新增源码文件必须同时包含项目对应的单元测试）：model=x' } }).text).toBe('执行助手的输出不符合要求：新增源码文件必须同时包含项目对应的单元测试');
    expect(describeEvent({ event_type: 'SELF_HEAL', payload: { anomaly: 'change_too_large', action: 'shrink' } }).text).toBe('针对「改动量超出限制」采取：精简改动。');
  });
  it('shows permanent model configuration errors as paused, including legacy typed events', () => {
    for (const fields of [{ retryable: false, category: 'model_config_error' }, { type: 'IsolationError' }]) {
      const t = describeEvent({ event_type: 'AGENT_FAILED', payload: { ...fields, attempt: 1, error: '评审委员会至少需要 2 个不同模型' } });
      expect(t.title).toBe('模型配置有误，已暂停'); expect(t.tone).toBe('danger');
      expect(t.text).toContain('评审委员会至少需要 2 个不同模型');
      expect(t.title + t.text).not.toContain('自动重试');
      expect(t.actions?.find(a => a.kind === 'link')?.href).toBe('/admin/models');
    }
    const a = anomalyText('model_config_error', '评审委员会至少需要 2 个不同模型');
    expect(a.tone).toBe('danger'); expect(a.text).toContain('恢复执行');
  });
  it('distinguishes a scheduled retry from exhausted attempts', () => {
    expect(describeEvent({ event_type: 'AGENT_FAILED', payload: { retryable: true, willRetry: true, attempt: 1, error: 'timeout' } }).title).toContain('正在自动重试');
    const stopped = describeEvent({ event_type: 'AGENT_FAILED', payload: { retryable: true, willRetry: false, attempt: 3, error: 'timeout' } });
    expect(stopped.title).toBe('执行出错，已进入异常处理'); expect(stopped.tone).toBe('danger');
  });
});

import { budgetHitText } from './eventText';
describe('budgetHitText', () => {
  it('names the task-level dimension with numbers', () => {
    const t = budgetHitText({ scope: 'task', worst: { key: 'iterations', label: '开发轮次', used: 50, limit: 50 }, dims: [{ key: 'usd', label: '金额(USD)', used: 0.514, limit: 3 }, { key: 'iterations', label: '开发轮次', used: 50, limit: 50 }] });
    expect(t.title).toBe('单任务开发轮次用完');
    expect(t.text).toContain('50'); expect(t.text).toContain('不是工作区总预算'); expect(t.text).toContain('$0.514 / $3.000');
  });
  it('old events without data still explain scope', () => { expect(budgetHitText({ scope: 'task' }).text).toContain('本任务自身'); });
  it('workspace scope', () => { expect(budgetHitText({ scope: 'global', level: 'hard' }).title).toBe('工作区今日预算触顶'); });
});


import { failureText } from './eventText';
describe('failureText', () => {
  it('explains an uninitialized repository without suggesting model retries or replanning', () => {
    const failed = describeEvent({ event_type: 'AGENT_FAILED', payload: { category: 'repository_not_initialized', retryable: false, error: '仓库尚未初始化，请创建首个提交' } });
    const opened = anomalyText('repository_not_initialized', '仓库尚未初始化，请创建首个提交');
    for (const event of [failed, opened]) {
      expect(event.title).toContain('仓库尚未初始化');
      expect(event.text).toContain('首个提交');
      expect(event.text).toContain('保留');
      expect(event.text).not.toMatch(/切换模型|重新规划|自动重试/);
      expect(event.actions?.some(a => a.label.includes('恢复'))).toBe(true);
    }
  });
  it('translates structured failures', () => {
    expect(failureText({ stage: 'PLANNING', category: 'budget', code: 'budget', params: { key: 'iterations', label: '开发轮次', used: 50, limit: 50 } })).toBe('任务规划：单任务开发轮次用完（50 / 50）');
    expect(failureText({ stage: 'DELIVERED', category: 'merge_gate:checks_not_green', code: 'merge_gate', params: { sub: 'checks_not_green' } })).toContain('CI 检查没有全部通过');
    expect(failureText({ stage: 'DEV', category: 'change_too_large', detail: '13 个文件 / 加权 124 行（原始 124 行；上限 20 文件 / 100 行）' })).toBe('开发执行：改动量超出限制');
    expect(failureText({ category: 'weird' })).toBe('处理没有成功');
  });
  it('code review summary: observe mode vs blocked', () => {
    const base = { files: 3, total: 2, summary: { critical: 1, high: 0, medium: 1, low: 0 }, blockingCount: 1 };
    const obs = describeEvent({ event_type: 'CODE_REVIEW_DONE', payload: { ...base, observeOnly: true, wouldBlock: true, blocked: false } });
    expect(obs.title).toBe('发现 1 项阻断级问题 · 观察模式继续'); expect(obs.text).toContain('严重 1');
    const blk = describeEvent({ event_type: 'CODE_REVIEW_DONE', payload: { ...base, observeOnly: false, wouldBlock: true, blocked: true, returnTo: 'DEV', loops: 1, maxLoops: 2 } });
    expect(blk.title).toContain('已退回开发 · 第 1/2 轮返修');
    expect(describeEvent({ event_type: 'CODE_REVIEW_DONE', payload: { files: 2, total: 0 } }).text).toContain('没有发现问题');
  });
  it('合并关卡事件用大白话说明，不出现内部编号', () => {
    const req = describeEvent({ event_type: 'MERGE_APPROVAL_REQUESTED', payload: { tier: 'G2', tierName: '大', weightedLines: 640, url: 'https://example.test/pull/1' } });
    expect(req.title).toBe('改动较大，合并前等你确认'); expect(req.text).toContain('640 行'); expect(req.text).not.toContain('G2'); expect(req.actions?.[0].href).toBe('https://example.test/pull/1');
    expect(describeEvent({ event_type: 'MERGE_APPROVAL_STALE', payload: {} }).text).toContain('重新确认');
    expect(describeEvent({ event_type: 'SIZE_LARGE', payload: { weightedLines: 2600, tierName: '很大', needsApproval: true } }).text).toContain('不会因为大而放弃');
    expect(describeEvent({ event_type: 'SELF_HEAL', payload: { anomaly: 'change_too_large', action: 'size_accepted' } }).text).toContain('接受当前大小继续');
  });
  it('stuck rework / CI broken / budget pause are explained without calling them 省钱模式', () => {
    const ci = anomalyText('ci_broken', '', { kind: 'pre_existing', headline: 'CI 在「Install」这一步就失败了：仓库根目录没有 package.json', baseState: 'failed' });
    expect(ci.title).toContain('CI 本身跑不起来'); expect(ci.text).toContain('不是这次改动造成的'); expect(ci.actions?.map(a => a.label)).toContain('去集成页修 CI');
    expect(anomalyText('rework_exhausted', '', { what: '测试返修' }).text).toContain('预算与安全上限继续独立生效');
    expect(anomalyText('same_failure', '', {}).text).toContain('同一个错误');
    expect(anomalyText('budget_paused', '', { what: '单任务金额 3 / 3' }).title).toBe('预算用完，已暂停');
    expect(anomalyText('safety_cap', '', { iterations: 150, limit: 150 }).text).toContain('安全上限 150 轮');
    expect(describeEvent({ event_type: 'OWNER_NEEDED', payload: { anomaly: 'ci_broken' } }).title).toBe('需要你处理');
    expect(describeEvent({ event_type: 'TASK_BUDGET_SWITCH', payload: { after: { budgetOff: true, degradeOff: false } } }).text).toContain('不限预算（仍记录花费）');
  });
});
