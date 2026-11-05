import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ShadButton } from '@/components/ui/button';
import { Label, ShadInput, ShadTextarea } from '@/components/ui/misc';

export interface ConfirmOptions { title: ReactNode; description?: ReactNode; confirmText?: string; cancelText?: string; destructive?: boolean }
export interface PromptOptions extends ConfirmOptions { label?: ReactNode; placeholder?: string; defaultValue?: string; multiline?: boolean; required?: boolean }
type Pending = { kind: 'confirm'; opts: ConfirmOptions; resolve: (v: boolean) => void } | { kind: 'prompt'; opts: PromptOptions; resolve: (v: string | null) => void };
interface Ctx { confirm: (o: ConfirmOptions) => Promise<boolean>; prompt: (o: PromptOptions) => Promise<string | null> }
const C = createContext<Ctx | null>(null);

/** 替代原生 window.confirm / prompt：Promise 风格，shadcn AlertDialog / Dialog（焦点陷阱、Esc = 取消） */
export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [p, setP] = useState<Pending | null>(null); const [value, setValue] = useState('');
  const settled = useRef(false);
  const confirm = useCallback((opts: ConfirmOptions) => new Promise<boolean>(resolve => { settled.current = false; setP({ kind: 'confirm', opts, resolve }); }), []);
  const prompt = useCallback((opts: PromptOptions) => new Promise<string | null>(resolve => { settled.current = false; setValue(opts.defaultValue ?? ''); setP({ kind: 'prompt', opts, resolve }); }), []);
  const done = (v: boolean) => { if (!p || settled.current) return; settled.current = true;
    if (p.kind === 'confirm') p.resolve(v); else p.resolve(v ? value : null); setP(null); };
  const o = p?.opts; const destructive = !!o?.destructive;
  const promptInvalid = p?.kind === 'prompt' && p.opts.required !== false && !value.trim();
  return <C.Provider value={{ confirm, prompt }}>
    {children}
    <AlertDialog open={p?.kind === 'confirm'} onOpenChange={(open) => { if (!open) done(false); }}>
      {p?.kind === 'confirm' && <AlertDialogContent tone={destructive ? 'destructive' : 'default'} data-testid="confirm-dialog">
        <AlertDialogHeader><AlertDialogTitle className={destructive ? 'text-danger-ink' : undefined}>{o!.title}</AlertDialogTitle>{o!.description && <AlertDialogDescription>{o!.description}</AlertDialogDescription>}</AlertDialogHeader>
        <AlertDialogFooter><AlertDialogCancel onClick={() => done(false)}>{o!.cancelText ?? '取消'}</AlertDialogCancel><AlertDialogAction destructive={destructive} onClick={() => done(true)}>{o!.confirmText ?? '确定'}</AlertDialogAction></AlertDialogFooter>
      </AlertDialogContent>}
    </AlertDialog>
    <Dialog open={p?.kind === 'prompt'} onOpenChange={(open) => { if (!open) done(false); }}>
      {p?.kind === 'prompt' && <DialogContent style={{ width: 480 }} data-testid="prompt-dialog" className={destructive ? 'border-danger/80 [--cut-line:rgb(var(--c-danger))]' : undefined}>
        <form onSubmit={(e) => { e.preventDefault(); if (!promptInvalid) done(true); }} className="flex min-h-0 flex-col">
          <DialogHeader><DialogTitle className={destructive ? 'text-danger-ink' : undefined}>{p.opts.title}</DialogTitle></DialogHeader>
          <div className="space-y-2 p-card">
            {p.opts.description && <DialogDescription className="whitespace-pre-line">{p.opts.description}</DialogDescription>}
            {p.opts.label && <Label htmlFor="x7-prompt">{p.opts.label}</Label>}
            {p.opts.multiline
              ? <ShadTextarea id="x7-prompt" autoFocus rows={4} className="w-full" value={value} placeholder={p.opts.placeholder} onChange={e => setValue(e.target.value)} />
              : <ShadInput id="x7-prompt" autoFocus className="w-full" value={value} placeholder={p.opts.placeholder} onChange={e => setValue(e.target.value)} />}
          </div>
          <DialogFooter><ShadButton type="button" size="sm" onClick={() => done(false)}>{p.opts.cancelText ?? '取消'}</ShadButton><ShadButton type="submit" size="sm" variant={destructive ? 'destructive' : 'default'} disabled={promptInvalid}>{p.opts.confirmText ?? '确定'}</ShadButton></DialogFooter>
        </form>
      </DialogContent>}
    </Dialog>
  </C.Provider>;
}
function useCtx() { const c = useContext(C); if (!c) throw new Error('ConfirmProvider 缺失'); return c; }
export function useConfirm() { return useCtx().confirm; }
export function usePrompt() { return useCtx().prompt; }
