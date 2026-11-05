import { AppLink } from '@/components/AppLink';
import type { ReactNode } from 'react';
import { type BoardResponse, fmtTime, pct } from '../api';
import { PipelineFlow } from '../components/PipelineFlow';
import { NodeBoardCard } from '../components/NodeBoard';
import type { PulseStore } from '../pulse';
import { Badge, Callout, Card, PageSkeleton, PageHeader, Sparkline, StatTile } from '../ui';

export default function Overview({ connected, scope, board, node, onNode, store, speed, live, filters, runAction }: { connected?: boolean; scope?: { productLine?: string; projectId?: string }; board: BoardResponse | null; node: string | null; onNode: (id: string | null) => void; store?: PulseStore; speed?: number; live?: string; filters?: ReactNode; runAction?: ReactNode }) {
  if (!board) return <PageSkeleton title="总览看板" />;
  const h = board.health; const t = board.totals;
  const tiles: [string, string, string?][] = [
    ['PRD 自动通过率', pct(h.prdPassRate)], ['验收通过率', pct(h.acceptancePassRate)], ['评委意见分歧率', pct(h.committeeDisagreementRate)], ['灰度发布拦截', String(h.canaryBlocks)],
    ['自动撤回上线', String(h.rollbacks)], ['自动修复成功率', pct(h.healSuccessRate)], ['省钱模式 / 自动废弃', `${h.degradeEvents} / ${h.autoDiscards}`], ['单任务成本', `$${h.avgCostUsd.toFixed(2)}`],
    ['预算用完', String(h.budgetHits)], ['禁止区域拦截', String(h.actionsDenied)], ['紧急停止', String(h.killSwitchEvents)],
  ];
  return (
    <div className="space-y-gap">
      <PageHeader title="总览看板" desc={`${board.range.from} ~ ${board.range.to} · 点击节点查看任务`} actions={<span className="text-xs text-ink-3">数据更新于 {fmtTime(board.now)}</span>} />
      {filters && <div className="flex flex-wrap items-center gap-2">{filters}</div>}
      {connected === false && <AppLink href="/integrations" className="block" data-testid="connect-guide"><Callout tone="warn" icon="plug"><b>先连接 GitHub</b>：现在是体验模式，不会访问你的仓库。点击这里，用 GitHub 授权登录或粘贴令牌，几步就能开始。</Callout></AppLink>}
      <div className="grid grid-cols-2 gap-gap lg:grid-cols-4" data-testid="stats">
        <StatTile tone="primary" label="区间任务" value={t.all} unit="个" hint={`巡查发现 ${t.watch} · 人工提出 ${t.prism}`} />
        <StatTile tone="success" label="版本发布" value={board.iterations} unit="次" hint={`其中返修 ${board.loops} 次`} title="创建 GitHub 仓库版本算一次发布，不代表平台上线" />
        <StatTile tone="info" label="闭环率" value={pct(board.closureRate)} hint="版本已发布 / 区间任务总数" />
        <StatTile tone="neutral" label="过程记录" value={board.evidence.count} unit="份" hint={`已验证平台上线 ${board.evidence.productionDeliveries} 项 · 返工率 ${pct(board.evidence.recheckRate)}`} />
      </div>
      <Card title="交付流水线" actions={runAction} subtitle={<>{board.pipeline ? `流程模板 ${board.pipeline.template}${board.pipeline.skipped.length ? ` · 跳过 ${board.pipeline.skipped.join("、")}（这些步骤不会有任务进入）` : ""}` : "任务由系统自动推进"}{runAction && <span className="mt-1 block">手动运行会抓取需求并推进整个工作区，不受上方筛选影响。</span>}</>} flush>
        <div className="p-card"><PipelineFlow board={board} selected={node} onSelect={(id) => onNode(node === id ? null : id)} store={store} speed={speed} live={live} /></div>
      </Card>
      <NodeBoardCard productLine={scope?.productLine} projectId={scope?.projectId} />
      <div className="grid gap-gap lg:grid-cols-3">
        <Card title="近 30 天趋势" className="lg:col-span-2" actions={<div className="flex gap-3 text-xs text-ink-3"><span className="flex items-center gap-1"><i className="h-0.5 w-4 bg-primary" />新增</span><span className="flex items-center gap-1"><i className="h-0.5 w-4 bg-success" />交付</span></div>}>
          <Sparkline label="近 30 天新增与交付" width={640} height={120} labels={board.trend.map((d) => d.date.slice(5))} series={[{ name: '新增', values: board.trend.map((d) => d.created), color: 'primary', fill: true }, { name: '交付', values: board.trend.map((d) => d.delivered), color: 'success' }]} />
        </Card>
        <Card title="废弃原因分布" subtitle="自动废弃时记录原因，可冷却后重新入池">
          {Object.keys(board.discardReasons).length === 0 ? <p className="text-sm text-ink-3">暂无废弃任务</p> : (
            <ul className="space-y-1.5">{Object.entries(board.discardReasons).sort((a, b) => b[1] - a[1]).map(([k, v]) => <li key={k} className="flex items-center justify-between text-sm"><span className="text-ink-2">{k}</span><Badge tone="neutral">{v}</Badge></li>)}</ul>
          )}
        </Card>
      </div>
      <Card title="自动运行健康度" subtitle="由系统按规则自动统计" data-testid="health">
        <div className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3 lg:grid-cols-6">
          {tiles.map(([k, v]) => <div key={k}><div className="label-caps text-caption text-ink-3">{k}</div><div className="num text-lg font-semibold text-ink">{v}</div></div>)}
        </div>
      </Card>
    </div>
  );
}
