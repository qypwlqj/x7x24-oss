import * as React from 'react';
import * as A from '@radix-ui/react-alert-dialog';
import { cn } from '@/lib/utils';
import { buttonVariants } from './button';

export const AlertDialog = A.Root; export const AlertDialogTrigger = A.Trigger; export const AlertDialogPortal = A.Portal;
export const AlertDialogOverlay = React.forwardRef<React.ElementRef<typeof A.Overlay>, React.ComponentPropsWithoutRef<typeof A.Overlay>>(({ className, ...p }, ref) =>
  <A.Overlay ref={ref} className={cn('fixed inset-0 z-50 bg-scrim data-[state=open]:animate-in data-[state=open]:fade-in-0', className)} {...p} />);
AlertDialogOverlay.displayName = 'AlertDialogOverlay';
export const AlertDialogContent = React.forwardRef<React.ElementRef<typeof A.Content>, React.ComponentPropsWithoutRef<typeof A.Content> & { tone?: 'default' | 'destructive' }>(({ className, tone = 'default', ...p }, ref) =>
  <AlertDialogPortal><AlertDialogOverlay /><A.Content ref={ref} className={cn('cut fixed left-1/2 top-1/2 z-50 flex w-[440px] max-w-[calc(100vw-2rem)] -translate-x-1/2 -translate-y-1/2 flex-col border bg-surface shadow-pop outline-none data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=open]:zoom-in-95',
    tone === 'destructive' ? 'border-danger' : 'border-ink', className)} {...p} /></AlertDialogPortal>);
AlertDialogContent.displayName = 'AlertDialogContent';
export const AlertDialogHeader = ({ className, ...p }: React.HTMLAttributes<HTMLDivElement>) => <div className={cn('space-y-2 px-card pb-2 pt-4', className)} {...p} />;
export const AlertDialogFooter = ({ className, ...p }: React.HTMLAttributes<HTMLDivElement>) => <div className={cn('flex justify-end gap-2 border-t border-line px-card py-3', className)} {...p} />;
export const AlertDialogTitle = React.forwardRef<React.ElementRef<typeof A.Title>, React.ComponentPropsWithoutRef<typeof A.Title>>(({ className, ...p }, ref) => <A.Title ref={ref} className={cn('hud-title text-base text-ink', className)} {...p} />);
AlertDialogTitle.displayName = 'AlertDialogTitle';
export const AlertDialogDescription = React.forwardRef<React.ElementRef<typeof A.Description>, React.ComponentPropsWithoutRef<typeof A.Description>>(({ className, ...p }, ref) => <A.Description ref={ref} className={cn('whitespace-pre-line text-sm leading-6 text-ink-2', className)} {...p} />);
AlertDialogDescription.displayName = 'AlertDialogDescription';
export const AlertDialogAction = React.forwardRef<React.ElementRef<typeof A.Action>, React.ComponentPropsWithoutRef<typeof A.Action> & { destructive?: boolean }>(({ className, destructive, ...p }, ref) => <A.Action ref={ref} className={cn(buttonVariants({ variant: destructive ? 'destructive' : 'default', size: 'sm' }), className)} {...p} />);
AlertDialogAction.displayName = 'AlertDialogAction';
export const AlertDialogCancel = React.forwardRef<React.ElementRef<typeof A.Cancel>, React.ComponentPropsWithoutRef<typeof A.Cancel>>(({ className, ...p }, ref) => <A.Cancel ref={ref} className={cn(buttonVariants({ variant: 'outline', size: 'sm' }), className)} {...p} />);
AlertDialogCancel.displayName = 'AlertDialogCancel';
