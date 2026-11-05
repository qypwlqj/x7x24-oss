import { Component, type ReactNode } from 'react';
import { Button } from './Button';

/** 页面级错误边界：某个页面渲染出错时只替换这一块，不让整站白屏；换路由（resetKey 变化）自动恢复 */
export class PageBoundary extends Component<{ resetKey: string; children: ReactNode }, { error: Error | null; key: string }> {
  state = { error: null as Error | null, key: this.props.resetKey };
  static getDerivedStateFromError(error: Error) { return { error }; }
  static getDerivedStateFromProps(p: { resetKey: string }, s: { error: Error | null; key: string }) { return p.resetKey !== s.key ? { error: null, key: p.resetKey } : null; }
  componentDidCatch(error: Error) { console.error('页面渲染出错', error); }
  render() {
    if (!this.state.error) return this.props.children;
    return <div role="alert" className="border-2 border-ink bg-surface p-5" data-testid="page-error">
      <div className="font-semibold">这个页面出错了</div>
      <p className="mt-1 text-sm text-ink-3">其他页面不受影响。可以重试，或回到上一页。错误：{String(this.state.error.message).slice(0, 200)}</p>
      <div className="mt-3 flex gap-2"><Button size="sm" variant="primary" onClick={() => this.setState({ error: null })}>重试</Button><Button size="sm" onClick={() => history.back()}>返回</Button></div>
    </div>;
  }
}
