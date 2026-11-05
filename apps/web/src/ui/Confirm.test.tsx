import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { Button, ConfirmProvider, Tabs, Toggle, useConfirm } from './index';

describe('shadcn-based primitives keep the old API', () => {
  it('Button maps variants to cyberpunk classes', () => {
    const p = renderToStaticMarkup(<Button variant="primary">提交</Button>); expect(p).toContain('bg-primary'); expect(p).toContain('cut');
    expect(renderToStaticMarkup(<Button variant="danger">删</Button>)).toContain('border-danger');
    expect(renderToStaticMarkup(<Button loading>x</Button>)).toContain('aria-busy="true"');
  });
  it('Tabs render accessible tablist with selected tab', () => {
    const h = renderToStaticMarkup(<Tabs label="t" value="b" onChange={() => {}} items={[{ id: 'a', label: 'A' }, { id: 'b', label: 'B', count: 2 }]} />);
    expect(h).toContain('role="tablist"'); expect(h).toMatch(/aria-selected="true"[^>]*id="tab-b"|id="tab-b"[^>]*aria-selected="true"/);
  });
  it('Toggle is a switch', () => { expect(renderToStaticMarkup(<Toggle checked label="x" onChange={() => {}} />)).toContain('role="switch"'); });
  it('ConfirmProvider provides the hook', () => {
    let got: unknown = null; const Probe = () => { got = useConfirm(); return null; };
    renderToStaticMarkup(<ConfirmProvider><Probe /></ConfirmProvider>); expect(typeof got).toBe('function');
  });
});
