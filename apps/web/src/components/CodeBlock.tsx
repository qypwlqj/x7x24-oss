import { memo, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react';
import { Button } from '../ui/Button';
import '../styles/content.css';

type Lowlight = ReturnType<typeof import('lowlight')['createLowlight']>;
type Token = ReturnType<Lowlight['highlight']>['children'][number];

const grammars = {
  json: () => import('highlight.js/lib/languages/json'),
  javascript: () => import('highlight.js/lib/languages/javascript'),
  typescript: () => import('highlight.js/lib/languages/typescript'),
  bash: () => import('highlight.js/lib/languages/bash'),
  css: () => import('highlight.js/lib/languages/css'),
  xml: () => import('highlight.js/lib/languages/xml'),
  python: () => import('highlight.js/lib/languages/python'),
  java: () => import('highlight.js/lib/languages/java'),
  sql: () => import('highlight.js/lib/languages/sql'),
  yaml: () => import('highlight.js/lib/languages/yaml'),
  diff: () => import('highlight.js/lib/languages/diff'),
};
const aliases: Record<string, keyof typeof grammars> = {
  js: 'javascript', jsx: 'javascript', ts: 'typescript', tsx: 'typescript',
  sh: 'bash', shell: 'bash', html: 'xml', svg: 'xml', py: 'python', yml: 'yaml',
};
let highlighter: Promise<Lowlight> | undefined;
const loadedLanguages = new Map<string, Promise<Lowlight>>();

function loadLanguage(language: keyof typeof grammars): Promise<Lowlight> {
  let pending = loadedLanguages.get(language);
  if (!pending) {
    highlighter ??= import('lowlight').then(({ createLowlight }) => createLowlight());
    pending = Promise.all([highlighter, grammars[language]()]).then(([instance, grammar]) => {
      instance.register(language, grammar.default);
      return instance;
    });
    loadedLanguages.set(language, pending);
    pending.catch(() => { loadedLanguages.delete(language); highlighter = undefined; });
  }
  return pending;
}

// Lowlight produces text and span nodes, so code never becomes executable HTML.
function renderTokens(nodes: Token[]): ReactNode {
  return nodes.map((node, index) => node.type === 'text' ? node.value : node.type === 'element'
    ? <span key={index} className={String((node.properties.className as string[] | undefined)?.join(' ') ?? '')}>{renderTokens(node.children)}</span>
    : null);
}

const HighlightedCode = memo(function HighlightedCode({ code, language }: { code: string; language: string }) {
  const [result, setResult] = useState<{ code: string; language: string; nodes: Token[] }>();
  const normalized = aliases[language] ?? language;
  useEffect(() => {
    // Large logs remain selectable and copyable without expensive tokenization.
    if (!Object.prototype.hasOwnProperty.call(grammars, normalized) || code.length > 100_000) return;
    let active = true;
    loadLanguage(normalized as keyof typeof grammars).then(instance => {
      if (active) setResult({ code, language, nodes: instance.highlight(normalized, code).children });
    }).catch(() => { /* Plain text remains available when highlighting cannot load. */ });
    return () => { active = false; };
  }, [code, language, normalized]);
  return <>{result?.code === code && result.language === language ? renderTokens(result.nodes) : code}</>;
});

export const CodeBlock = memo(function CodeBlock({ code, language = 'text', title = '代码' }: { code: string; language?: string; title?: string }) {
  const id = useId();
  const [wrap, setWrap] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle');
  const resetCopy = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const lines = useMemo(() => code ? code.split('\n').length - (code.endsWith('\n') ? 1 : 0) : 0, [code]);
  useEffect(() => {
    setCopyState('idle');
    return () => clearTimeout(resetCopy.current);
  }, [code]);

  const copy = async () => {
    clearTimeout(resetCopy.current);
    try {
      await navigator.clipboard.writeText(code);
      setCopyState('copied');
    } catch {
      setCopyState('failed');
    }
    resetCopy.current = setTimeout(() => setCopyState('idle'), 2500);
  };

  return <div className="code-panel" role="group" aria-label={title}>
    <div className="code-panel-toolbar">
      <div className="code-panel-label"><span className="code-panel-language">{language === 'text' ? '纯文本' : language.toUpperCase()}</span><span className="truncate">{title}</span><span className="code-panel-lines">{lines} 行</span></div>
      <div className="code-panel-actions">
        <Button variant="ghost" size="sm" aria-pressed={wrap} aria-controls={id} onClick={() => setWrap(v => !v)}>自动换行</Button>
        <Button variant="ghost" size="sm" aria-expanded={expanded} aria-controls={id} onClick={() => setExpanded(v => !v)}>{expanded ? '收起' : '展开'}</Button>
        <Button variant="ghost" size="sm" aria-label={`复制${title}`} onClick={copy}><span aria-live="polite">{copyState === 'copied' ? '已复制' : '复制'}</span></Button>
      </div>
    </div>
    {copyState === 'failed' && <p className="code-panel-error" role="status">复制失败，请选中文本后复制。</p>}
    <pre id={id} className="code-panel-body" data-wrap={wrap} data-expanded={expanded} tabIndex={0} aria-label={`${title}内容`}><code><HighlightedCode code={code} language={language.toLowerCase()} /></code></pre>
  </div>;
});
