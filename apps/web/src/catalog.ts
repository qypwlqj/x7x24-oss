import type { ProjectConfig, ProjectModule } from '@x7x24/shared';

export const UNDETECTED = '未识别（请点「自动识别」）';
export const isUndetected = (t?: string) => !t || t.startsWith('未识别') || t === 'Node.js CommonJS 工具函数项目';

/** 后端技术栈识别结果 */
export interface StackSuggestion { technology: string; modules: ProjectModule[]; allowedPaths: string[]; allowedExtensions: string[]; pathRules: { prefix: string; level: string }[]; discoveryRoots: string[]; workflow: string | null; evidence: string[] }
export function applySuggestion(config: ProjectConfig, s: StackSuggestion): ProjectConfig {
  return { ...config, technology: s.technology, modules: s.modules, allowedPaths: s.allowedPaths, allowedExtensions: s.allowedExtensions, pathRules: s.pathRules as ProjectConfig['pathRules'], workflow: s.workflow ?? config.workflow, discovery: { ...config.discovery, roots: s.discoveryRoots } };
}

export const catalogStateName = (state: string) => ({ ACTIVE: '启用', PAUSED: '暂停', ARCHIVED: '已归档' }[state] ?? state);
export const executionStateName = (state: string) => ({ WAITING: '等待执行', RUNNING: '执行中', RETRYING: '重试中', FAILED: '失败', BLOCKED: '等待处理', WAITING_CONFIGURATION: '待配置发布', WAITING_INPUT: '等待补充', WAITING_PLAN: '等待计划', WAITING_APPROVAL: '等待确认', WAITING_EXTERNAL: '等待外部结果', WAITING_VALIDATION: '等待验证', RECOVERY_REQUIRED: '等待核对结果', CANCELLED: '已停止，可恢复', PAUSED: '暂停', COMPLETED: '已完成', DISCARDED: '已废弃', SUCCEEDED: '执行成功', SKIPPED: '按规划跳过' }[state] ?? state);
export const nodeOutcomeName = (outcome?: string) => ({ PASSED: '通过', FAILED: '未通过', ERROR: '执行出错', BLOCKED: '阻塞', PENDING: '尚无结论', RECOVERED: '已安排恢复', SKIPPED: '已跳过' }[outcome ?? ''] ?? '尚无结论');
export const defaultProjectConfig = (): ProjectConfig => ({
  repo: '', baseBranch: 'main', workflow: 'ci.yml', autonomy: 'L2', credentialRef: 'GITHUB_TOKEN', technology: UNDETECTED,
  intake: true, intakeLabel: '7x24:signal', intakeAll: false, releaseTarget: 'github_release', releasePrefix: 'x7x24', maxConcurrentTasks: 4, autoInitializeRepository: false, devMode: 'sandbox',
  budget: { dailyUsd: null, dailyTokens: null, dailyCiMinutes: null }, modules: [],
  allowedPaths: ['src/', 'tests/', 'docs/', 'examples/'], allowedExtensions: ['js', 'jsx', 'mjs', 'cjs', 'ts', 'tsx', 'css', 'md', 'txt'],
  pathRules: [{ prefix: '.github/', level: 'forbidden' }, { prefix: 'CODEOWNERS', level: 'forbidden' }, { prefix: 'deploy/', level: 'forbidden' }, { prefix: 'src/', level: 'mid' }, { prefix: 'tests/', level: 'low' }, { prefix: 'docs/', level: 'low' }],
  discovery: { enabled: false, source: true, testFailures: true, autoCreateTasks: false, intervalMinutes: 60, maxFiles: 12, roots: ['src/', 'tests/'] },
});
