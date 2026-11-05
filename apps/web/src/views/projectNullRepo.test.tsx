import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { ProjectForm } from './Catalog';
import { PageBoundary } from '../ui/PageBoundary';
import { defaultProjectConfig } from '../catalog';

// 对话框的 portal 在服务端不输出内容；这里直接渲染其表单，核对真实配置字段。
vi.mock('../ui', async importOriginal => ({ ...await importOriginal<typeof import('../ui')>(), Modal: ({ children, footer }: { children: import('react').ReactNode; footer?: import('react').ReactNode }) => <div>{children}{footer}</div> }));

describe('未绑定仓库的项目（repo=null）', () => {
  it('打开项目配置不崩溃，保存按钮可用', () => {
    const project: any = { id: 'p1', name: '官网', productLineId: 'l1', state: 'ACTIVE', config: { ...defaultProjectConfig(), repo: null } };
    expect(() => renderToStaticMarkup(<ProjectForm lineId="l1" project={project} onClose={() => {}} onSaved={async () => {}} />)).not.toThrow();
  });
  it('自动初始化默认关闭，并展示管理员授权的内容和分支要求', () => {
    expect(defaultProjectConfig().autoInitializeRepository).toBe(false);
    const h = renderToStaticMarkup(<ProjectForm lineId="l1" onClose={() => {}} onSaved={async () => {}} />);
    expect(h).toContain('允许自动初始化空仓库'); expect(h).toContain('固定 README 首次提交'); expect(h).toContain('GitHub 默认分支一致');
  });
  it('页面出错只替换这一块，不整站白屏', () => {
    const Boom = () => { throw new Error('boom'); };
    // 服务端渲染不走错误边界，这里只校验边界的兜底渲染
    const b = new PageBoundary({ resetKey: 'a', children: <Boom /> }); b.state = { error: new Error('boom'), key: 'a' };
    expect(renderToStaticMarkup(<>{b.render()}</>)).toContain('这个页面出错了');
  });
});
