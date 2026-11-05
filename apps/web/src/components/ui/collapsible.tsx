import * as React from 'react';
import * as C from '@radix-ui/react-collapsible';
import { cn } from '@/lib/utils';

export const Collapsible = C.Root;
export const CollapsibleTrigger = C.Trigger;
export const CollapsibleContent = C.Content;

/** 折叠块（替代原生 details/summary）：summary 为触发文字，子元素按需渲染；外观与原来一致（小字、指针、展开时三角旋转） */
export function Disclosure({ summary, children, className, summaryClassName, defaultOpen, ...p }: { summary: React.ReactNode; children: React.ReactNode; className?: string; summaryClassName?: string; defaultOpen?: boolean } & Omit<React.HTMLAttributes<HTMLDivElement>, 'children'>) {
  return (
    <C.Root defaultOpen={defaultOpen} className={className} {...p}>
      <C.Trigger className={cn('group flex w-full cursor-pointer items-center gap-1 text-left focus-visible:outline-none focus-visible:text-primary-ink', summaryClassName)}>
        <span aria-hidden className="inline-block text-[0.7em] transition-transform group-data-[state=open]:rotate-90">▶</span><span className="min-w-0 flex-1">{summary}</span>
      </C.Trigger>
      <C.Content>{children}</C.Content>
    </C.Root>
  );
}
