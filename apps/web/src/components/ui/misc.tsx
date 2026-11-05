import * as React from 'react';
import * as T from '@radix-ui/react-tabs';
import * as S from '@radix-ui/react-switch';
import * as C from '@radix-ui/react-checkbox';
import * as Tip from '@radix-ui/react-tooltip';
import * as L from '@radix-ui/react-label';
import { cn } from '@/lib/utils';

export const Label = React.forwardRef<React.ElementRef<typeof L.Root>, React.ComponentPropsWithoutRef<typeof L.Root>>(({ className, ...p }, ref) => <L.Root ref={ref} className={cn('label-caps block text-caption font-semibold text-ink-2', className)} {...p} />);
Label.displayName = 'Label';

const field = 'sheet sheet-sm px-2.5 text-sm text-ink num placeholder:text-ink-3 transition-colors hover:border-ink focus:border-ink disabled:opacity-50';
export const ShadInput = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(({ className, ...p }, ref) => <input ref={ref} className={cn(field, 'h-9', className)} {...p} />);
ShadInput.displayName = 'Input';
export const ShadTextarea = React.forwardRef<HTMLTextAreaElement, React.TextareaHTMLAttributes<HTMLTextAreaElement>>(({ className, ...p }, ref) => <textarea ref={ref} className={cn(field, 'py-1.5', className)} {...p} />);
ShadTextarea.displayName = 'Textarea';
export const fieldClass = field;

export const TabsRoot = T.Root;
export const TabsList = React.forwardRef<React.ElementRef<typeof T.List>, React.ComponentPropsWithoutRef<typeof T.List>>(({ className, ...p }, ref) => <T.List ref={ref} className={cn('flex gap-1 border-b border-line-strong/60', className)} {...p} />);
TabsList.displayName = 'TabsList';
export const TabsTrigger = React.forwardRef<React.ElementRef<typeof T.Trigger>, React.ComponentPropsWithoutRef<typeof T.Trigger>>(({ className, ...p }, ref) =>
  <T.Trigger ref={ref} className={cn('label-caps -mb-px border-b-2 border-transparent px-3 py-2 text-xs font-medium text-ink-2 transition-colors hover:text-ink data-[state=active]:border-ink data-[state=active]:text-ink', className)} {...p} />);
TabsTrigger.displayName = 'TabsTrigger';

export const Switch = React.forwardRef<React.ElementRef<typeof S.Root>, React.ComponentPropsWithoutRef<typeof S.Root>>(({ className, ...p }, ref) =>
  <S.Root ref={ref} className={cn('rule-slot group relative h-5 w-10 shrink-0 border border-line-strong transition-colors disabled:opacity-45 data-[state=checked]:border-ink', className)} {...p}>
    <S.Thumb className="absolute left-[2px] top-[2px] block h-3.5 w-3.5 border border-line-strong bg-surface shadow-[var(--sheet-shadow-sm)] transition-all data-[state=checked]:left-[22px] data-[state=checked]:border-ink" />
  </S.Root>);
Switch.displayName = 'Switch';

export const Checkbox = React.forwardRef<React.ElementRef<typeof C.Root>, React.ComponentPropsWithoutRef<typeof C.Root>>(({ className, ...p }, ref) =>
  <C.Root ref={ref} className={cn('sheet sheet-sm grid h-4 w-4 shrink-0 place-items-center p-0 text-ink transition-colors hover:border-ink disabled:opacity-45 data-[state=checked]:border-ink', className)} {...p}>
    <C.Indicator><svg width="10" height="8" viewBox="0 0 12 10" aria-hidden><path d="M1,5.2 C2.4,6.6 3.6,8.6 4.6,8.3 C6.4,4.6 8.6,2.4 11,1.3" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg></C.Indicator>
  </C.Root>);
Checkbox.displayName = 'Checkbox';

export const TooltipProvider = Tip.Provider; export const Tooltip = Tip.Root; export const TooltipTrigger = Tip.Trigger;
export const TooltipContent = React.forwardRef<React.ElementRef<typeof Tip.Content>, React.ComponentPropsWithoutRef<typeof Tip.Content>>(({ className, sideOffset = 6, ...p }, ref) =>
  <Tip.Portal><Tip.Content ref={ref} sideOffset={sideOffset} className={cn('z-50 w-64 border border-primary/60 bg-raised px-3 py-2 text-left text-xs font-normal normal-case leading-5 tracking-normal text-ink shadow-pop data-[state=delayed-open]:animate-in data-[state=delayed-open]:fade-in-0', className)} {...p} /></Tip.Portal>);
TooltipContent.displayName = 'TooltipContent';
