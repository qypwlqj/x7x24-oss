/** 验收截图：带登录凭据取图（<img> 不能带 Authorization 头，先 fetch 成 blob）+ 灯箱（左右切换、Esc 关闭） */
import { useEffect, useState } from 'react';
import { apiUrl, authHeaders } from '../lib/auth';
import { cx } from '../ui';

const cache = new Map<string, Promise<string>>();
export function authedSrc(url: string): Promise<string> {
  if (!url.startsWith('/api/')) return Promise.resolve(url);
  let p = cache.get(url);
  if (!p) { p = fetch(apiUrl(url), { headers: authHeaders(), credentials: 'include' }).then(r => { if (!r.ok) throw new Error(String(r.status)); return r.blob(); }).then(b => URL.createObjectURL(b)); p.catch(() => cache.delete(url)); cache.set(url, p); }
  return p;
}
export function AuthImg({ src, alt, className }: { src: string; alt: string; className?: string }) {
  const [u, setU] = useState<string | null>(null); const [err, setErr] = useState(false);
  useEffect(() => { let on = true; authedSrc(src).then(x => on && setU(x)).catch(() => on && setErr(true)); return () => { on = false; }; }, [src]);
  if (err) return <span className={cx('grid place-items-center bg-sunken text-caption text-ink-3', className)}>截图已过期或无权查看</span>;
  return u ? <img src={u} alt={alt} className={className} /> : <span className={cx('block animate-pulse bg-sunken', className)} aria-label="加载中" />;
}
export const shotName = (url: string) => decodeURIComponent(url.split('/').pop() ?? '').replace(/\.png$/, '');

export function ShotStrip({ shots, size = 'md', max }: { shots: string[]; size?: 'sm' | 'md'; max?: number }) {
  const [open, setOpen] = useState<number | null>(null); const list = max ? shots.slice(0, max) : shots;
  return <>
    <div className="flex flex-wrap gap-2" data-testid="shot-strip">{list.map((s, i) => <button key={s} type="button" onClick={() => setOpen(i)} aria-label={`查看截图 ${shotName(s)}`}
      className={cx('overflow-hidden border-[1.5px] border-ink bg-surface', size === 'sm' ? 'h-12 w-20' : 'h-20 w-32')} data-testid="shot-thumb"><AuthImg src={s} alt={shotName(s)} className="h-full w-full object-cover object-top" /></button>)}
      {max && shots.length > max && <span className="self-center text-caption text-ink-3">+{shots.length - max}</span>}</div>
    {open != null && <Lightbox shots={shots} index={open} onIndex={setOpen} onClose={() => setOpen(null)} />}
  </>;
}
export function Lightbox({ shots, index, onIndex, onClose }: { shots: string[]; index: number; onIndex: (i: number) => void; onClose: () => void }) {
  useEffect(() => { const k = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); if (e.key === 'ArrowRight') onIndex((index + 1) % shots.length); if (e.key === 'ArrowLeft') onIndex((index - 1 + shots.length) % shots.length); };
    window.addEventListener('keydown', k); return () => window.removeEventListener('keydown', k); }, [index, shots.length]);
  return <div className="fixed inset-0 z-50 flex flex-col bg-scrim/80 p-4" role="dialog" aria-modal="true" aria-label="截图" data-testid="lightbox" onClick={onClose}>
    <div className="flex items-center gap-3 text-ink-inverse" onClick={e => e.stopPropagation()}><b className="flex-1">{shotName(shots[index])}</b><span className="tabular">{index + 1} / {shots.length}</span>
      <button type="button" className="border border-ink-inverse px-2" onClick={() => onIndex((index - 1 + shots.length) % shots.length)} aria-label="上一张">←</button>
      <button type="button" className="border border-ink-inverse px-2" onClick={() => onIndex((index + 1) % shots.length)} aria-label="下一张">→</button>
      <button type="button" className="border border-ink-inverse px-2" onClick={onClose} aria-label="关闭">✕</button></div>
    <div className="grid min-h-0 flex-1 place-items-center pt-3"><AuthImg src={shots[index]} alt={shotName(shots[index])} className="max-h-full max-w-full border-2 border-ink bg-raised object-contain" /></div>
  </div>;
}
export function Checks({ checks }: { checks: { name: string; ok: boolean | null; reason?: string }[] }) {
  return <ul className="space-y-1 text-sm" data-testid="accept-checks">{checks.map((c, i) => <li key={i} className="flex gap-2">
    <span className={cx('mt-0.5 grid h-4 w-4 shrink-0 place-items-center border text-[10px] font-bold', c.ok === true ? 'border-success text-success-ink' : c.ok === false ? 'border-danger text-danger-ink' : 'border-line-strong text-ink-3')} aria-label={c.ok === true ? '通过' : c.ok === false ? '没通过' : '待定'}>{c.ok === true ? '✓' : c.ok === false ? '✗' : '?'}</span>
    <span className="min-w-0"><span className={c.ok === false ? 'font-semibold' : ''}>{c.name}</span>{c.reason && <span className="block text-caption text-ink-3">{c.reason}</span>}</span></li>)}</ul>;
}
