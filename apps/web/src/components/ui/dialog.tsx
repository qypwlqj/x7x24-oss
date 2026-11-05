import * as React from 'react';
import * as D from '@radix-ui/react-dialog';
import { cn } from '@/lib/utils';

export const Dialog = D.Root; export const DialogTrigger = D.Trigger; export const DialogClose = D.Close; export const DialogPortal = D.Portal;
export const DialogOverlay = React.forwardRef<React.ElementRef<typeof D.Overlay>, React.ComponentPropsWithoutRef<typeof D.Overlay>>(({ className, ...p }, ref) =>
  <D.Overlay ref={ref} className={cn('fixed inset-0 z-50 bg-scrim data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0', className)} {...p} />);
DialogOverlay.displayName = 'DialogOverlay';
/** 居中切角面板（Modal 用）；side="right" 为右侧抽屉（Drawer 用） */
export const DialogContent = React.forwardRef<React.ElementRef<typeof D.Content>, React.ComponentPropsWithoutRef<typeof D.Content> & { side?: 'center' | 'right' }>(({ className, side = 'center', children, ...p }, ref) =>
  <DialogPortal><DialogOverlay /><D.Content ref={ref} className={cn(side === 'right'
    ? 'fixed right-0 top-0 z-50 flex h-full max-w-full flex-col border-l border-primary/60 bg-surface shadow-pop outline-none data-[state=open]:animate-slide-in'
    : 'cut fixed left-1/2 top-1/2 z-50 flex max-h-[calc(100dvh-2rem)] max-w-[calc(100vw-2rem)] -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden border border-primary/60 bg-surface shadow-pop outline-none [--cut-line:rgb(var(--c-primary))] data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=open]:zoom-in-95', className)} {...p}>{children}</D.Content></DialogPortal>);
DialogContent.displayName = 'DialogContent';
export const DialogHeader = ({ className, ...p }: React.HTMLAttributes<HTMLDivElement>) => <header className={cn('flex shrink-0 items-center justify-between gap-2 border-b border-line px-card py-3', className)} {...p} />;
export const DialogFooter = ({ className, ...p }: React.HTMLAttributes<HTMLDivElement>) => <footer className={cn('flex shrink-0 justify-end gap-2 border-t border-line px-card py-3', className)} {...p} />;
export const DialogTitle = React.forwardRef<React.ElementRef<typeof D.Title>, React.ComponentPropsWithoutRef<typeof D.Title>>(({ className, ...p }, ref) => <D.Title ref={ref} className={cn('hud-title truncate text-base text-primary-ink', className)} {...p} />);
DialogTitle.displayName = 'DialogTitle';
export const DialogDescription = React.forwardRef<React.ElementRef<typeof D.Description>, React.ComponentPropsWithoutRef<typeof D.Description>>(({ className, ...p }, ref) => <D.Description ref={ref} className={cn('text-sm text-ink-2', className)} {...p} />);
DialogDescription.displayName = 'DialogDescription';
