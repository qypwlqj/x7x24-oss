import { AppLink } from '@/components/AppLink';
import { memo } from 'react';
import Markdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { CodeBlock } from './CodeBlock';
import '../styles/content.css';

const plugins = [remarkGfm];
const components: Components = {
  h1: ({ children }) => <h3 className="markdown-h1">{children}</h3>,
  h2: ({ children }) => <h4 className="markdown-h2">{children}</h4>,
  h3: ({ children }) => <h5 className="markdown-h3">{children}</h5>,
  h4: ({ children }) => <h6>{children}</h6>,
  h5: ({ children }) => <h6>{children}</h6>,
  h6: ({ children }) => <h6>{children}</h6>,
  pre({ children, node }) {
    const code = node?.children.find(child => child.type === 'element' && child.tagName === 'code');
    if (code?.type !== 'element') return <pre>{children}</pre>;
    const languageClass = (code.properties.className as string[] | undefined)?.find(name => name.startsWith('language-'));
    const source = code.children.map(child => child.type === 'text' ? child.value : '').join('');
    return <CodeBlock code={source} language={languageClass?.slice(9) || 'text'} />;
  },
  table({ children }) {
    return <div className="markdown-table-scroll" tabIndex={0} role="region" aria-label="表格"><table>{children}</table></div>;
  },
  a({ children, href }) {
    const external = /^https?:\/\//i.test(href ?? '');
    return <AppLink href={href} {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}>{children}</AppLink>;
  },
};

export const MarkdownContent = memo(function MarkdownContent({ text }: { text: string }) {
  return <div className="markdown-content"><Markdown remarkPlugins={plugins} components={components} skipHtml>{text}</Markdown></div>;
});
