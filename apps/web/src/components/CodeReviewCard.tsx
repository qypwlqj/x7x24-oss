import { Badge, Callout, Card } from '../ui';
import { Disclosure } from '@/components/ui/collapsible';

/** 逐行审查（CODE_REVIEW 节点 / 试跑）的发现：严重程度、位置、说明与修复建议；被过滤的意见和原始数据放在「详情」里 */
export const SEVERITY_LABEL: Record<string, string> = { critical: '严重', high: '高', medium: '中', low: '低' };
export const CATEGORY_LABEL: Record<string, string> = { bug: '缺陷', security: '安全', performance: '性能', maintainability: '可维护性', test: '测试', style: '代码风格', documentation: '文档', other: '其它' };
const TONE: Record<string, 'danger' | 'warn' | 'info' | 'neutral'> = { critical: 'danger', high: 'warn', medium: 'info', low: 'neutral' };

export interface CodeReviewFinding { path: string; startLine: number; endLine?: number; category: string; severity: string; content: string; existingCode?: string; suggestion?: string; blocking?: boolean; relocated?: boolean }
export interface CodeReviewData {
  findings: CodeReviewFinding[]; unlocated?: CodeReviewFinding[]; dropped?: any[]; skipped?: { path: string; reason: string }[]; unreviewed?: { path: string; reason: string }[];
  groups?: number | { files: string[]; rules: string[] }[]; reviewedFiles?: number; totalFiles?: number; partial?: boolean; engine?: string; sessionId?: string;
  summary?: Record<string, number>; observeOnly?: boolean; wouldBlock?: boolean; decision?: string; blockingCount?: number; tokens?: number; usd?: number; models?: string[]; reason?: string; errors?: string[]; tier?: string; tierName?: string; weightedLines?: number;
}

export function summaryText(d: CodeReviewData) {
  const s = d.summary ?? {};
  const files = d.reviewedFiles ?? (Array.isArray(d.groups) ? d.groups.reduce((n, g) => n + g.files.length, 0) : 0);
  const of = d.totalFiles != null && d.totalFiles !== files ? `（共 ${d.totalFiles} 个）` : '';
  const un = d.unlocated?.length ? `，另有 ${d.unlocated.length} 条没定位到行` : '';
  return `审查了 ${files} 个文件${of}，发现 ${d.findings.length} 个问题（严重 ${s.critical ?? 0}、高 ${s.high ?? 0}、中 ${s.medium ?? 0}、低 ${s.low ?? 0}）${un}`;
}

export function CodeReviewFindings({ data }: { data: CodeReviewData }) {
  return <div className="space-y-3" data-testid="code-review-findings">
    <p className="text-sm text-ink-2">{summaryText(data)}{data.tokens != null ? ` · 用量 ${data.tokens.toLocaleString()} Token` : ''}{data.usd != null ? ` · 约 ${data.usd.toFixed(4)} USD` : ''}</p>
    {data.observeOnly && data.wouldBlock && <Callout tone="warn">观察期：有 {data.blockingCount ?? 0} 个问题按规则应退回修改，目前只记录，不退回。</Callout>}
    {!data.observeOnly && data.decision === 'reject' && <Callout tone="danger">发现必须修的问题，已退回编码修改。</Callout>}
    {data.partial && <Callout tone="warn">部分审查：有 {data.unreviewed?.length ?? 0} 个文件没审，名单在「详情」里。</Callout>}
    {data.findings.length === 0 ? <p className="text-sm text-ink-3">没有发现需要处理的问题。</p> :
      <ul className="space-y-2">{data.findings.map((f, i) => (
        <li key={i} className="border border-line-strong/50 border-l-2 border-l-primary bg-raised/70 p-3 text-sm" data-testid="code-review-finding">
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone={TONE[f.severity] ?? 'neutral'} dot>{SEVERITY_LABEL[f.severity] ?? f.severity}</Badge>
            <Badge tone="neutral">{CATEGORY_LABEL[f.category] ?? f.category}</Badge>
            {f.blocking && <Badge tone="danger">必须修</Badge>}
            <code className="font-mono text-xs text-ink-2">{f.path}:{f.startLine}{f.endLine && f.endLine !== f.startLine ? `-${f.endLine}` : ''}</code>
          </div>
          <p className="mt-2 whitespace-pre-wrap text-ink">{f.content}</p>
          {f.existingCode && <pre className="mt-2 max-h-32 overflow-auto border border-line bg-sunken p-2 font-mono text-caption text-ink-2">{f.existingCode}</pre>}
          {f.suggestion && <p className="mt-2 whitespace-pre-wrap text-xs text-ink-2"><span className="text-ink-3">修复建议：</span>{f.suggestion}</p>}
        </li>))}</ul>}
    {(data.unlocated ?? []).length > 0 && <div data-testid="code-review-unlocated"><p className="text-xs text-ink-3">没定位到行的意见（只作参考，不会退回修改）</p>
      <ul className="mt-1 space-y-1">{data.unlocated!.map((f, i) => <li key={i} className="border border-dashed border-line-strong/60 bg-raised/50 p-2 text-sm">
        <div className="flex flex-wrap items-center gap-2"><Badge tone="neutral">{SEVERITY_LABEL[f.severity] ?? f.severity}</Badge><Badge tone="neutral">{CATEGORY_LABEL[f.category] ?? f.category}</Badge><code className="font-mono text-xs text-ink-2">{f.path || '（整体）'}</code></div>
        <p className="mt-1 whitespace-pre-wrap text-ink-2">{f.content}</p></li>)}</ul></div>}
    <Disclosure summaryClassName="cursor-pointer text-xs text-ink-3" summary={<>详情（没审 {(data.unreviewed ?? data.skipped)?.length ?? 0} 个文件{data.dropped?.length ? ` · 过滤掉 ${data.dropped.length} 条` : ''}）</>}>
      <div className="mt-2 space-y-2 text-xs text-ink-3">
        {(data.dropped ?? []).length > 0 && <div><p className="text-ink-2">过滤掉的意见（文件或行号对不上、核实未通过等）</p><ul className="mt-1 space-y-1">{data.dropped!.map((x, i) => <li key={i}><code className="font-mono">{String(x.path ?? '')}:{String(x.startLine ?? '')}</code> {String(x.dropReason ?? '')} · {String(x.content ?? '').slice(0, 80)}</li>)}</ul></div>}
        {(data.unreviewed ?? data.skipped ?? []).length > 0 && <div><p className="text-ink-2">没有审查的文件</p><ul className="mt-1 space-y-1">{(data.unreviewed ?? data.skipped)!.map((x, i) => <li key={i}><code className="font-mono">{x.path}</code> {x.reason}</li>)}</ul></div>}
        {(data.errors ?? []).length > 0 && <div><p className="text-ink-2">出错记录</p><ul className="mt-1 space-y-1">{data.errors!.map((x, i) => <li key={i}>{x}</li>)}</ul></div>}
        <pre className="max-h-64 overflow-auto border border-line bg-sunken p-2 font-mono text-caption text-ink-2">{JSON.stringify(data, null, 1)}</pre>
      </div>
    </Disclosure>
  </div>;
}

export function CodeReviewCard({ evidence }: { evidence: any[] }) {
  const ev = [...(evidence ?? [])].reverse().find(e => e.kind === 'code_review');
  if (!ev?.content) return null;
  const data = ev.content as CodeReviewData;
  const badge = data.decision === 'reject' ? <Badge tone="danger">已退回修改</Badge> : data.observeOnly ? <Badge tone="info">观察期 · 只记录</Badge> : <Badge tone="success">已通过</Badge>;
  return <Card title="逐行审查" subtitle={`第 ${ev.iteration} 轮迭代${data.tierName ? ` · 本次改动${data.tierName}（约 ${data.weightedLines ?? '?'} 行）` : ''} · ${data.engine ?? '开源项目 open-code-review'}`} actions={badge} data-testid="code-review-card"><CodeReviewFindings data={data} /></Card>;
}
