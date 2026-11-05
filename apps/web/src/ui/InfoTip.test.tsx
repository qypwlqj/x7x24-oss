import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { Effect, InfoTip, Term } from './InfoTip';
import { EmptyState, Skeleton } from './States';
import { FormBar } from './FormBar';

describe('InfoTip / Term', () => {
  it('renders an accessible keyboard-focusable trigger', () => {
    const html = renderToStaticMarkup(<InfoTip title="软阈值" text="说明文字" />);
    expect(html).toContain('<button'); expect(html).toContain('type="button"'); expect(html).toContain('aria-label="说明：软阈值"'); expect(html).not.toContain('role="tooltip"');
  });
  it('Term renders the glossary name with a tip, and falls back for unknown keys', () => {
    expect(renderToStaticMarkup(<Term k="soft" />)).toContain('省钱线'); expect(renderToStaticMarkup(<Term k="soft" />)).toContain('data-infotip');
    expect(renderToStaticMarkup(<Term k="nope">自定义</Term>)).toBe('自定义'); expect(renderToStaticMarkup(<Term k="soft">省钱线</Term>)).toContain('省钱线');
  });
  it('Effect states the consequence', () => { expect(renderToStaticMarkup(<Effect>会更严格</Effect>)).toContain('改了会怎样'); });
});
describe('states and form bar', () => {
  it('skeleton is announced, empty state offers an action', () => {
    expect(renderToStaticMarkup(<Skeleton />)).toContain('aria-busy="true"');
    const h = renderToStaticMarkup(<EmptyState title="没有项目" hint="先创建" action={<a href="/x">去创建</a>} />); expect(h).toContain('没有项目'); expect(h).toContain('去创建');
  });
  it('FormBar disables save when clean or invalid, and shows errors/success', () => {
    const clean = renderToStaticMarkup(<FormBar dirty={false} onSave={() => {}} onReset={() => {}} />); expect(clean).toContain('disabled'); expect(clean).toContain('恢复默认');
    const bad = renderToStaticMarkup(<FormBar dirty onSave={() => {}} errors={['不能为空']} />); expect(bad).toContain('不能为空'); expect(bad).toContain('disabled');
    const ok = renderToStaticMarkup(<FormBar dirty onSave={() => {}} success="已保存" />); expect(ok).toContain('已保存'); expect(ok).toContain('有未保存的修改');
  });
});
