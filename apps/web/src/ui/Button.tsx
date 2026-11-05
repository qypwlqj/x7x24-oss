import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { cx } from './cx';
import { ShadButton } from '@/components/ui/button';
import { Icon, type IconName } from './Icon';
import { Tip } from './InfoTip';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';
type Size = 'sm' | 'md';
export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> { variant?: Variant; size?: Size; icon?: IconName; loading?: boolean }

/** 旧 API → shadcn 变体（样式见 components/ui/button.tsx） */
const V = { primary: 'default', secondary: 'outline', ghost: 'ghost', danger: 'destructive' } as const;
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button({ variant = 'secondary', size = 'md', icon, loading, className, children, disabled, title, ...p }, ref) {
  return (
    <Tip content={title} wrap={disabled || loading}>
    <ShadButton ref={ref} type="button" disabled={disabled || loading} aria-busy={loading || undefined} variant={V[variant]} size={size === 'sm' ? 'sm' : 'default'} className={className} {...p}>
      {icon && <Icon name={icon} size={size === 'sm' ? 14 : 16} className={loading ? 'animate-pulse2' : undefined} />}{children}
    </ShadButton></Tip>
  );
});
export function IconButton({ label, icon, className, ...p }: { label: string; icon: IconName } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return <Tip content={label}><button type="button" aria-label={label} className={cx('inline-flex h-8 w-8 items-center justify-center border border-transparent text-ink-2 transition-colors hover:border-ink hover:text-ink', className)} {...p}><Icon name={icon} /></button></Tip>;
}
