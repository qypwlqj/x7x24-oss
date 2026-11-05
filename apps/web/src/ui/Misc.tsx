import type { ReactNode } from 'react';
import { cx, TONE, type Tone } from './cx';
import { Icon, type IconName } from './Icon';

export function Progress({ value, max, tone, label }: { value: number; max: number; tone?: Tone; label: string }) {
  const r = max > 0 ? Math.min(1, value / max) : 0;
  const t: Tone = tone ?? (r >= 1 ? 'danger' : r >= 0.8 ? 'warn' : 'success');
  return <div role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={max} aria-valuenow={Math.min(value, max)} className="h-2 w-full overflow-hidden border border-line bg-sunken"><div className={cx('h-full transition-all', TONE[t].solid, TONE[t].glow)} style={{ width: `${r * 100}%` }} /></div>;
}
export function Callout({ tone = 'info', icon = 'alert', children, onClose }: { tone?: Tone; icon?: IconName; children: ReactNode; onClose?: () => void }) {
  return <div role={tone === 'danger' ? 'alert' : 'status'} className={cx('cut cut-sm flex items-start gap-2 border px-3 py-2 text-sm', TONE[tone].soft, TONE[tone].border)}><Icon name={icon} className="mt-0.5 shrink-0" /><div className="min-w-0 flex-1">{children}</div>{onClose && <button type="button" aria-label="关闭提示" onClick={onClose} className="shrink-0 p-0.5 hover:text-ink"><Icon name="x" size={14} /></button>}</div>;
}
export function Empty({ children }: { children: ReactNode }) { return <div className="px-4 py-10 text-center text-sm text-ink-3">{children}</div>; }
export function PageHeader({ title, desc, actions }: { title: ReactNode; desc?: ReactNode; actions?: ReactNode }) {
  return <div className="mb-4 flex flex-wrap items-end justify-between gap-3"><div><h1 className="hud-title text-xl text-ink"><span aria-hidden className="mr-2 text-ink-3">//</span>{title}</h1>{desc && <p className="mt-0.5 text-sm text-ink-3">{desc}</p>}</div>{actions}</div>;
}
export function KV({ k, v }: { k: ReactNode; v: ReactNode }) { return <div className="flex items-baseline justify-between gap-3 border-b border-line py-1.5 text-sm last:border-0"><dt className="label-caps text-caption text-ink-3">{k}</dt><dd className="text-right tabular text-ink">{v}</dd></div>; }
