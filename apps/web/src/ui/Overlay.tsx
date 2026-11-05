import type { ReactNode } from 'react';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { IconButton } from './Button';

/** Drawer / Modal：基于 shadcn Dialog（Radix：焦点陷阱、Esc 关闭、关闭后焦点还原、点遮罩关闭） */
export function Drawer({ title, onClose, children, width = 520 }: { title: ReactNode; onClose: () => void; children: ReactNode; width?: number }) {
  return <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
    <DialogContent side="right" style={{ width }} data-testid="drawer" aria-describedby={undefined} onOpenAutoFocus={autoFocus}>
      <DialogHeader><DialogTitle>{title}</DialogTitle><IconButton label="关闭" icon="x" onClick={onClose} /></DialogHeader>
      <div className="flex-1 overflow-y-auto p-card">{children}</div>
    </DialogContent>
  </Dialog>;
}
export function Modal({ title, onClose, children, width = 480, footer, description }: { title: ReactNode; onClose: () => void; children: ReactNode; width?: number; footer?: ReactNode; description?: ReactNode }) {
  return <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
    <DialogContent style={{ width }} {...(description ? {} : { "aria-describedby": undefined })} onOpenAutoFocus={autoFocus}>
      <DialogHeader><DialogTitle>{title}</DialogTitle><IconButton label="关闭" icon="x" onClick={onClose} /></DialogHeader>
      {description && <DialogDescription className="px-card pt-3">{description}</DialogDescription>}
      <div className="min-h-0 flex-1 overflow-y-auto p-card">{children}</div>
      {footer && <DialogFooter>{footer}</DialogFooter>}
    </DialogContent>
  </Dialog>;
}
/** 保持原行为：优先聚焦 [data-autofocus] */
function autoFocus(e: Event) { const el = (e.currentTarget as HTMLElement | null)?.querySelector<HTMLElement>('[data-autofocus]'); if (el) { e.preventDefault(); el.focus(); } }
