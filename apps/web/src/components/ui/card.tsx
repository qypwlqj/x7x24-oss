import * as React from 'react';
import { cn } from '@/lib/utils';

/** shadcn Card 结构（Card / CardHeader / CardTitle / CardDescription / CardContent），样式为切角霓虹面板 */
export const ShadCard = React.forwardRef<HTMLElement, React.HTMLAttributes<HTMLElement>>(({ className, ...p }, ref) => <section ref={ref} className={cn('sheet cut', className)} {...p} />);
ShadCard.displayName = 'Card';
export const CardHeader = ({ className, ...p }: React.HTMLAttributes<HTMLElement>) => <header className={cn('flex items-start justify-between gap-3 border-b border-line px-card py-3', className)} {...p} />;
export const CardTitle = ({ className, ...p }: React.HTMLAttributes<HTMLHeadingElement>) => <h3 className={cn('text-sm font-bold uppercase text-ink', className)} {...p} />;
export const CardDescription = ({ className, ...p }: React.HTMLAttributes<HTMLParagraphElement>) => <p className={cn('mt-0.5 text-xs font-medium normal-case tracking-normal text-ink-3', className)} style={{ fontFamily: 'var(--font-sans)' }} {...p} />;
export const CardContent = ({ className, ...p }: React.HTMLAttributes<HTMLDivElement>) => <div className={className} {...p} />;
