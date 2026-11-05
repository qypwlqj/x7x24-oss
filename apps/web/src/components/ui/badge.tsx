import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

/** shadcn Badge，变体为赛博朋克状态色：暗底 + 同色细边 + 亮字 */
export const badgeVariants = cva('num inline-flex items-center gap-1 border px-1.5 py-px text-caption font-semibold leading-4 tracking-wide', {
  variants: {
    tone: {
      neutral: 'bg-sunken text-ink-2 border-line', primary: 'bg-primary-soft text-primary-ink border-primary/60', accent: 'bg-accent-soft text-accent-ink border-accent/60',
      success: 'bg-success-soft text-success-ink border-success/60', warn: 'bg-warn-soft text-warn-ink border-warn/60', danger: 'bg-danger-soft text-danger-ink border-danger/70', info: 'bg-info-soft text-info-ink border-info/60',
    },
  },
  defaultVariants: { tone: 'neutral' },
});
export function ShadBadge({ className, tone, ...p }: React.HTMLAttributes<HTMLSpanElement> & VariantProps<typeof badgeVariants>) { return <span className={cn(badgeVariants({ tone }), className)} {...p} />; }
