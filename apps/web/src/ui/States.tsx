import type { ReactNode } from 'react';
import { cx } from './cx';

export function Skeleton({ lines = 3, className }: { lines?: number; className?: string }) {
  return <div role="status" aria-label="加载中" aria-busy="true" className={cx('space-y-2', className)} data-testid="skeleton">{Array.from({ length: lines }, (_, i) => <div key={i} className="h-4 animate-pulse2 bg-sunken" style={{ width: `${95 - (i % 3) * 18}%` }} />)}<span className="sr-only">加载中…</span></div>;
}
export function PageSkeleton({ title }: { title?: string }) {
  return <div className="space-y-gap" data-testid="page-skeleton">{title && <div className="hud-title text-xl text-ink">{title}</div>}<div className="grid grid-cols-2 gap-gap lg:grid-cols-4">{[0, 1, 2, 3].map(i => <div key={i} className="cut h-24 animate-pulse2 border border-line bg-surface/70" />)}</div><div className="cut border border-line bg-surface/70 p-card"><Skeleton lines={5} /></div></div>;
}
/** 空状态：说明发生了什么 + 下一步动作 */
export function EmptyState({ title, hint, action, icon = '∅' }: { title: ReactNode; hint?: ReactNode; action?: ReactNode; icon?: string }) {
  return <div className="px-4 py-10 text-center" data-testid="empty-state"><div aria-hidden className="mx-auto mb-2 grid h-10 w-10 place-items-center border border-line-strong text-lg text-ink-3">{icon}</div><div className="text-sm font-semibold text-ink">{title}</div>{hint && <p className="mx-auto mt-1 max-w-md text-xs leading-5 text-ink-3">{hint}</p>}{action && <div className="mt-3 flex justify-center gap-2">{action}</div>}</div>;
}
