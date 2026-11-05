import type { HTMLAttributes, ReactNode } from 'react';
import { cx } from './cx';
import { ShadCard, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';

/** 纸面面板（基于 shadcn Card）：标题栏左侧一条墨色标记 */
export function Card({ title, subtitle, actions, children, className, bodyClassName, flush, ...p }: { title?: ReactNode; subtitle?: ReactNode; actions?: ReactNode; flush?: boolean; bodyClassName?: string } & Omit<HTMLAttributes<HTMLElement>, 'title'>) {
  return (
    <ShadCard className={className} {...p}>
      {(title || actions) && <CardHeader className="shrink-0">
        <div className="flex min-w-0 items-start gap-2.5"><span aria-hidden className="mt-1 flex h-4 shrink-0 gap-[3px]"><i className="w-px bg-ink" /><i className="w-px bg-ink" /></span><div className="min-w-0"><CardTitle>{title}</CardTitle>{subtitle && <CardDescription>{subtitle}</CardDescription>}</div></div>{actions}
      </CardHeader>}
      <CardContent className={cx(!flush && 'p-card', bodyClassName)}>{children}</CardContent>
    </ShadCard>
  );
}
