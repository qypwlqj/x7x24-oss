import type { StackSuggestion } from '../catalog';
import { Badge } from '../ui';

const LEVEL: Record<string, string> = { forbidden: 'danger', high: 'warn', mid: 'info', low: 'success' };
export function StackSuggestionView({ s }: { s: StackSuggestion }) {
  return <div className="space-y-2 text-sm" data-testid="stack-suggestion">
    <p className="font-medium text-ink">{s.technology}</p>
    <ul className="space-y-1">{s.modules.map(m => <li key={m.path} className="border-l-2 border-primary pl-2 text-xs text-ink-2"><b className="text-ink">{m.path}</b> · {m.language}{m.testCommand && <> · 测试 <code className="font-mono">{m.testCommand}</code></>}{m.buildCommand && <> · 构建 <code className="font-mono">{m.buildCommand}</code></>}</li>)}</ul>
    <p className="text-xs text-ink-3">允许修改：{s.allowedPaths.join('，')} · 文件类型：{s.allowedExtensions.join(' ')}{s.workflow ? ` · CI：${s.workflow}` : ''}</p>
    <div className="flex flex-wrap gap-1">{s.pathRules.filter(r => r.level === 'forbidden' || r.level === 'high').map(r => <Badge key={r.prefix} tone={(LEVEL[r.level] ?? 'neutral') as any}>{r.level === 'forbidden' ? '禁止' : '高风险'} {r.prefix}</Badge>)}</div>
    {s.evidence.length > 0 && <p className="text-caption text-ink-3">依据：{s.evidence.join('；')}</p>}
  </div>;
}
