import * as React from 'react';
import * as S from '@radix-ui/react-select';
import { cn } from '@/lib/utils';
import { PENCIL_CARET, PENCIL_TICK } from '@/ui/paperPath';

export const SelectRoot = S.Root; export const SelectValue = S.Value; export const SelectGroup = S.Group;
export const SelectTrigger = React.forwardRef<React.ElementRef<typeof S.Trigger>, React.ComponentPropsWithoutRef<typeof S.Trigger>>(({ className, children, ...p }, ref) => (
  <S.Trigger ref={ref} className={cn('flex items-center justify-between gap-2 text-left disabled:cursor-not-allowed disabled:opacity-50 data-[placeholder]:text-ink-3', className)} {...p}>
    <span className="min-w-0 truncate">{children}</span><S.Icon asChild><svg width="10" height="8" viewBox="0 0 10 8" aria-hidden className="shrink-0 text-ink"><path d={PENCIL_CARET} fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" /></svg></S.Icon>
  </S.Trigger>));
SelectTrigger.displayName = 'SelectTrigger';
export const SelectContent = React.forwardRef<React.ElementRef<typeof S.Content>, React.ComponentPropsWithoutRef<typeof S.Content>>(({ className, children, position = 'popper', ...p }, ref) => (
  <S.Portal><S.Content ref={ref} position={position} sideOffset={4} className={cn('sheet z-50 max-h-[min(24rem,var(--radix-select-content-available-height))] min-w-[var(--radix-select-trigger-width)] overflow-hidden text-sm text-ink', className)} {...p}>
    <S.Viewport className="p-1">{children}</S.Viewport></S.Content></S.Portal>));
SelectContent.displayName = 'SelectContent';
export const SelectItem = React.forwardRef<React.ElementRef<typeof S.Item>, React.ComponentPropsWithoutRef<typeof S.Item>>(({ className, children, ...p }, ref) => (
  <S.Item ref={ref} className={cn('relative flex cursor-pointer select-none items-center px-2 py-1.5 pr-6 outline-none data-[disabled]:pointer-events-none data-[disabled]:opacity-45 data-[highlighted]:bg-primary-soft data-[highlighted]:text-primary-ink data-[state=checked]:text-primary-ink', className)} {...p}>
    <S.ItemText>{children}</S.ItemText><S.ItemIndicator className="absolute right-2 text-ink"><svg width="12" height="10" viewBox="0 0 12 10" aria-hidden><path d={PENCIL_TICK} fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg></S.ItemIndicator></S.Item>));
SelectItem.displayName = 'SelectItem';
