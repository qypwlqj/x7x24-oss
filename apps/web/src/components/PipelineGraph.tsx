import { Badge, cx } from '../ui';
import { MODULE_LABEL, SOURCE_LABEL, bySegment, childChecks, type GNode, type Graph } from '../lib/pipeline';

const SOURCE_TONE: Record<string, 'neutral' | 'info' | 'warn' | 'accent'> = { preset: 'neutral', rule: 'info', governance: 'warn', agent: 'accent' };
const MODULE_EDGE: Record<string, string> = { sensing: 'border-info/60', production: 'border-accent/60', delivery: 'border-success/60' };

/** 组装出的流程图：感知 / 生产 / 交付三段，节点带来源（预设 / 规则 / 治理必选 / AI 提议）、必选锁、技能与检查 */
export function PipelineGraph({ graph, compact, active }: { graph: Graph; compact?: boolean; active?: string | null }) {
  const segs = bySegment(graph);
  return <div className="space-y-1.5" data-testid="pipeline-graph">
    <div className="flex flex-wrap items-stretch gap-1">
      {segs.map((s, i) => <div key={i} className={cx('flex flex-wrap items-center gap-1 border-l-2 pl-1.5', MODULE_EDGE[s.module] ?? 'border-line')}>
        <span className="text-caption font-semibold uppercase tracking-wider text-ink-3">{MODULE_LABEL[s.module] ?? s.module}</span>
        {s.nodes.map(n => <NodeChip key={n.id} n={n} checks={childChecks(graph, n.id)} compact={compact} active={active === n.id || active === n.stage} />)}
      </div>)}
    </div>
    {graph.estimate && <div className="text-caption text-ink-3">预计 {graph.estimate.llmCalls} 次模型调用 · 约 ${graph.estimate.usd} · {graph.estimate.minutes} 分钟</div>}
    {(graph.warnings ?? []).map((w, i) => <div key={i} className="text-caption text-warn-ink">⚠ {w}</div>)}
  </div>;
}

function NodeChip({ n, checks, compact, active }: { n: GNode; checks: GNode[]; compact?: boolean; active?: boolean }) {
  const tip = [SOURCE_LABEL[n.source ?? 'preset'], n.reason, n.mandatory && `必选：${n.mandatory}`, n.optional && '由规划器按需求决定是否执行', n.skills?.length && `技能：${n.skills.join(', ')}`].filter(Boolean).join(' · ');
  return <span className="inline-flex flex-col gap-0.5" data-testid={`gnode-${n.id}`}>
    <Badge tone={active ? 'primary' : SOURCE_TONE[n.source ?? 'preset']} title={tip} className={cx(n.optional && 'border-dashed opacity-70', active && 'ring-1 ring-primary')}>
      {n.mandatory ? '◆ ' : ''}{n.label}{!compact && n.source && n.source !== 'preset' ? <span className="ml-1 opacity-80">· {SOURCE_LABEL[n.source]}</span> : null}{n.skills?.length ? ` [${n.skills.length}]` : ''}
    </Badge>
    {checks.length > 0 && <span className="flex flex-wrap gap-0.5 pl-1">{checks.map(c => <span key={c.id} title={c.reason ?? ''} className="border border-line px-1 text-[10px] text-ink-3">✓ {c.label}</span>)}</span>}
  </span>;
}
