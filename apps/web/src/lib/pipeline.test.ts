import { describe, expect, it } from 'vitest';
import { bindable, bySegment, childChecks, diffEffective, topNodes, type Graph, setDevSkills, setRoleSkills, toggleStage, type Effective, type Template } from './pipeline';

const tpl = (id: string, extra: Partial<Template> = {}): Template => ({ id, version: 1, status: 'approved', stages: [], ...extra });

describe('pipeline helpers', () => {
  it('only lists approved platform or own-line templates', () => {
    const ts = [tpl('default'), tpl('mine', { owner: 'lms' }), tpl('other', { owner: 'docs' }), tpl('wait', { owner: 'lms', status: 'pending' })];
    expect(bindable(ts, 'lms').map(t => t.id)).toEqual(['default', 'mine']);
  });
  it('toggles optional stages and drops no-op overrides', () => {
    const off = toggleStage({ template: 'default@1' }, 'SPEC', false, true);
    expect(off.stageOverrides).toEqual({ SPEC: { enabled: false } });
    expect(toggleStage(off, 'SPEC', true, true).stageOverrides).toBeUndefined();
  });
  it('derives a line dev role with skills and binds it to DEV', () => {
    const b = setDevSkills({ template: 'web-fullstack@1' }, 'lms', ['i18n-json@1', 'react-ts@1'], ' 敬体 ');
    expect(b.roles?.['lms.dev.code']).toEqual({ extendsRole: 'dev.code', skills: ['i18n-json@1', 'react-ts@1'], instructions: '敬体' });
    expect(b.stageOverrides?.DEV?.maker).toBe('lms.dev.code');
    const cleared = setDevSkills(b, 'lms', [], '');
    expect(cleared.roles).toBeUndefined(); expect(cleared.stageOverrides).toBeUndefined();
  });
  it('derives arbitrary maker roles such as design.make and binds to corresponding stage', () => {
    const b = setRoleSkills({ template: 'backend-service@1' }, 'pay', 'design.make', ['api-contract@1'], '输出 OpenAPI 3.0 契约');
    expect(b.roles?.['pay.design.make']).toEqual({ extendsRole: 'design.make', skills: ['api-contract@1'], instructions: '输出 OpenAPI 3.0 契约' });
    expect(b.stageOverrides?.DESIGN?.maker).toBe('pay.design.make');
  });
  it('diffs effective snapshots', () => {
    const a = { template: 'default@1', stages: { SPEC: { type: 'SPEC' } }, heal: {}, roles: {}, skillText: {}, models: {}, sources: {}, id: 'a', lineId: 'l' } as Effective;
    const b = { ...a, template: 'docs-only@1', stages: { SPEC: { type: 'SPEC', enabled: false } } } as Effective;
    expect(diffEffective(a, b)).toEqual([{ path: 'stages.SPEC.enabled', from: '—', to: 'false' }, { path: 'template', from: '"default@1"', to: '"docs-only@1"' }]);
  });
});

describe('assembled graph helpers', () => {
  const g: Graph = { schema: 2, edges: [], nodes: [
    { id: 'PLANNING', stage: 'PLANNING', label: '任务规划', module: 'sensing' },
    { id: 'API_DESIGN', stage: 'API_DESIGN', label: '接口契约设计', module: 'production', source: 'rule' },
    { id: 'DEV', stage: 'DEV', label: '编码', module: 'production' },
    { id: 'QA', stage: 'QA', label: '测试', module: 'delivery' },
    { id: 'CHECK_SQL_SAFETY', stage: 'QA', label: 'SQL 安全检查', parent: 'QA', module: 'production', family: 'check' },
    { id: 'SEC_AUDIT', stage: 'SEC_AUDIT', label: '安全审计', module: 'delivery', source: 'governance', mandatory: '敏感改动' },
  ] };
  it('groups top-level nodes by module and keeps checks under QA', () => {
    expect(topNodes(g).map(n => n.id)).not.toContain('CHECK_SQL_SAFETY');
    expect(bySegment(g).map(s => [s.module, s.nodes.length])).toEqual([['sensing', 1], ['production', 2], ['delivery', 2]]);
    expect(childChecks(g, 'QA').map(n => n.id)).toEqual(['CHECK_SQL_SAFETY']);
  });
});
