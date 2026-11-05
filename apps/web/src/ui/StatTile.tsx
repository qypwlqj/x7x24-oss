import type { ReactNode } from 'react';
import { cx, TONE, type Tone } from './cx';
import { Tip } from './InfoTip';
import { PENCIL_RULE } from './paperPath';

export function StatTile({ label, value, unit, hint, tone = 'neutral', children, className, title }: { label: ReactNode; value: ReactNode; unit?: string; hint?: ReactNode; tone?: Tone; children?: ReactNode; className?: string; title?: string }) {
  return (
    <Tip content={title}><div tabIndex={title ? 0 : undefined} className={cx('sheet cut relative px-card py-3', className)}>
      <div className="label-caps text-caption text-ink-2">{label}</div>
      <svg width="28" height="5" viewBox="0 0 28 5" aria-hidden className={cx('mt-1', TONE[tone].text)}><path d={PENCIL_RULE} fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg>
      <div className="mt-1 flex items-end justify-between gap-2">
        <div><span className="num text-2xl font-semibold leading-8 text-ink">{value}</span>{unit && <span className="ml-1 text-xs text-ink-3">{unit}</span>}</div>
        {children}
      </div>
      {hint && <div className="mt-0.5 text-xs text-ink-3">{hint}</div>}
    </div></Tip>
  );
}
