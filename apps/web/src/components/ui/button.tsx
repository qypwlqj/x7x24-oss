import * as React from 'react';
import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

/** 纸面按钮：default = 墨色实心，outline = 墨线，destructive = 暗红描边 */
export const buttonVariants = cva(
  'cut cut-sm inline-flex shrink-0 items-center justify-center gap-1.5 whitespace-nowrap font-medium tracking-normal transition-colors disabled:cursor-not-allowed disabled:opacity-45',
  {
    variants: {
      variant: {
        default: 'bg-primary text-primary-fg hover:bg-primary-hover',
        outline: 'border border-ink bg-surface text-ink hover:bg-sunken',
        ghost: 'text-ink-2 hover:bg-sunken hover:text-ink',
        destructive: 'border border-danger bg-danger-soft text-danger-ink hover:bg-danger hover:text-ink-inverse',
      },
      size: { sm: 'h-8 px-3 text-xs', default: 'h-10 px-4 text-sm' },
    },
    defaultVariants: { variant: 'outline', size: 'default' },
  });
export interface ShadButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof buttonVariants> { asChild?: boolean }
export const ShadButton = React.forwardRef<HTMLButtonElement, ShadButtonProps>(({ className, variant, size, asChild = false, ...props }, ref) => {
  const Comp = asChild ? Slot : 'button';
  return <Comp ref={ref} className={cn(buttonVariants({ variant, size }), className)} {...props} />;
});
ShadButton.displayName = 'Button';

/** 列表项 / 文字链接式按钮：无默认外观（由 className 决定），统一键盘焦点样式；用于会话列表、产品线列表、仓库列表等 */
export const ListItemButton = React.forwardRef<HTMLButtonElement, React.ButtonHTMLAttributes<HTMLButtonElement>>(({ className, type = 'button', ...p }, ref) =>
  <button ref={ref} type={type} className={cn(className)} {...p} />);
ListItemButton.displayName = 'ListItemButton';
