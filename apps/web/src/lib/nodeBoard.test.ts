import { describe, expect, it } from 'vitest';
import { boardStacks, dwell, visibleCells, type BoardCell, type NodeBoardData } from './pipeline';
import { reviseOptions } from '../components/TaskPipeline';
import { toggleIn } from '../components/ProjectPipelineSettings';

const cell = (node: string, o: Partial<BoardCell> = {}): BoardCell => ({ node, label: node, current: 0, blocked: 0, avgDwellH: 0, through: 0, throughPct: 0, byStack: {}, rare: false, ...o });
describe('流程步骤看板', () => {
  it('罕见步骤默认折叠，展开后全部显示', () => {
    const cs = [cell('DEV'), cell('PERF_REVIEW', { rare: true })];
    expect(visibleCells(cs, false).map(c => c.node)).toEqual(['DEV']);
    expect(visibleCells(cs, true)).toHaveLength(2);
  });
  it('技术栈筛选项来自看板里出现的栈', () => {
    const b: NodeBoardData = { modules: { sensing: [cell('INTAKE', { byStack: { node: 1 } })], production: [{ family: 'build', label: '构建', rareCount: 0, nodes: [cell('DEV', { byStack: { react: 2, node: 1 } })] }], delivery: [] }, attention: { anomaly: 0, degraded: 0 }, window: '30d', windowTasks: 3, rarePct: 5 };
    expect(boardStacks(b)).toEqual(['node', 'react']); expect(boardStacks(null)).toEqual([]);
  });
  it('停留时长', () => { expect(dwell(0)).toBe('—'); expect(dwell(0.5)).toBe('30 分钟'); expect(dwell(3)).toBe('3.0 小时'); expect(dwell(72)).toBe('3.0 天'); });
});
describe('中途修订与流程设置', () => {
  const nodes = [
    { id: 'SEC_AUDIT', label: '安全审计', family: 'review', module: 'delivery', allowed: true, why: '' },
    { id: 'CHECK_MD', label: 'Markdown 检查', family: 'check', module: 'production', check: 'markdown-lint', allowed: true, why: '' },
    { id: 'CHECK_I18N', label: '多语言检查', family: 'check', module: 'production', check: 'i18n-keys-complete', allowed: false, why: '' },
    { id: 'DEV', label: '编码', family: 'build', module: 'production', allowed: true, why: '' },
  ];
  it('只提供尚未在流程里、且本项目允许的步骤', () => {
    const o = reviseOptions(nodes, { schema: 2, nodes: [{ id: 'CHECK_MD', stage: 'QA', label: 'x', parent: 'QA' }], edges: [] });
    expect(o.map(x => x.value)).toEqual(['SEC_AUDIT']);
    expect(o[0].adj).toMatchObject({ op: 'insertStage', stage: 'SEC_AUDIT', after: 'QA' });
    expect(reviseOptions(nodes, undefined).find(x => x.value === 'CHECK_MD')?.adj).toMatchObject({ op: 'addCheck', stage: 'QA', check: 'markdown-lint' });
  });
  it('toggleIn 去重增删', () => { expect(toggleIn(['A'], 'A', true)).toEqual(['A']); expect(toggleIn(['A', 'B'], 'A', false)).toEqual(['B']); });
});
import { presetOptions, presetKey, type Preset } from './pipeline';
describe('流程预设', () => {
  it('内置在前，非内置标来源', () => {
    const ps: Preset[] = [{ id: 'lean', version: 2, name: '精简', template: 'default@1', disable: ['SPEC'], source: 'workspace', nodes: [] }, { id: 'default', version: 1, name: '标准', template: 'default@1', disable: [], source: 'builtin', nodes: [] }];
    expect(presetOptions(ps)).toEqual([{ value: 'default@1', label: '标准' }, { value: 'lean@2', label: '精简（工作区预设）' }]);
    expect(presetKey(ps[0])).toBe('lean@2');
  });
});
