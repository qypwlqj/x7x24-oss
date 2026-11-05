import { Toaster as Sonner } from 'sonner';

/** 赛博风 toast：切角、霓虹边、等宽字 */
export function Toaster() {
  return <Sonner position="bottom-right" theme="light" toastOptions={{ unstyled: true, classNames: {
    toast: 'cut cut-sm flex w-[360px] items-start gap-2 border bg-surface px-3 py-2 text-sm text-ink shadow-pop',
    success: 'border-success/70 [--cut-line:rgb(var(--c-success))]', error: 'border-danger/80 [--cut-line:rgb(var(--c-danger))]', info: 'border-primary/60', warning: 'border-warn/70',
    title: 'font-semibold', description: 'text-xs text-ink-3', closeButton: 'text-ink-3 hover:text-ink',
  } }} />;
}
export { toast } from 'sonner';
