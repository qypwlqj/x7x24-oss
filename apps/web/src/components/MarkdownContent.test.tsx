import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { CodeBlock } from './CodeBlock';
import { MarkdownContent } from './MarkdownContent';

const render = (text: string) => renderToStaticMarkup(<MarkdownContent text={text} />);

describe('task Markdown content', () => {
  it('renders document structure, GFM tables and read-only task lists', () => {
    const html = render('# 需求说明\n\n**重点**与 `inline()`\n\n> 引用\n\n- [x] 已完成\n- [ ] 待验收\n\n| 项目 | 结果 |\n| --- | --- |\n| 构建 | 通过 |');
    expect(html).toContain('<h3 class="markdown-h1">需求说明</h3>');
    expect(html).not.toContain('<h1>');
    expect(html).toContain('<strong>重点</strong>');
    expect(html).toContain('<code>inline()</code>');
    expect(html).toContain('<blockquote>');
    expect(html).toContain('class="markdown-table-scroll"');
    expect(html).toContain('<table>');
    expect(html).toContain('type="checkbox" disabled="" checked=""');
    expect(html).toContain('type="checkbox" disabled=""');
  });

  it('uses the shared code viewer for fenced, unlabelled and indented code', () => {
    const labelled = render('```json\n{"ok": true}\n```');
    expect(labelled).toContain('class="code-panel"');
    expect(labelled).toContain('JSON');
    expect(labelled).toContain('{&quot;ok&quot;: true}\n');
    expect(render('```\n<widget>\n```')).toContain('&lt;widget&gt;\n');
    expect(render('    const a = 1;')).toContain('class="code-panel"');
  });

  it('keeps ordinary Chinese text and soft line breaks', () => {
    expect(render('普通说明第一行\n第二行')).toContain('<p>普通说明第一行\n第二行</p>');
    expect(render('')).toBe('<div class="markdown-content"></div>');
  });

  it('keeps raw HTML inert and rejects executable links while preserving code examples', () => {
    const html = render('<script>evil()</script>\n\n<img src=x onerror="evil()">\n\n[危险链接](javascript:evil%28%29)\n\n```html\n<img src=x onerror="evil()">\n```');
    expect(html).not.toContain('<script');
    expect(html).not.toContain('<img');
    expect(html).not.toContain('href="javascript:');
    expect(html).toContain('&lt;img src=x onerror=');
  });

  it('opens web links safely and preserves in-page links', () => {
    const html = render('[文档](https://example.com/docs) [章节](#section)');
    expect(html).toContain('href="https://example.com/docs" target="_blank" rel="noopener noreferrer"');
    expect(html).toContain('href="#section"');
  });
});

describe('code viewer content', () => {
  it('preserves long source text, indentation and HTML as text without truncation', () => {
    const source = '  <script>evil("x")</script>\n' + 'long-value'.repeat(1500) + '\n';
    const html = renderToStaticMarkup(<CodeBlock code={source} language="unknown" title="原始记录" />);
    expect(html).toContain('  &lt;script&gt;evil(&quot;x&quot;)&lt;/script&gt;\n');
    expect(html).toContain('long-value'.repeat(1500));
    expect(html).not.toContain('<script>');
    expect(html).toContain('aria-label="复制原始记录"');
  });
});
