import { forwardRef, type ComponentProps } from 'react';
import { RadioGroupItem } from '@/components/ui/radio-group';
import { cx } from './cx';
import { PENCIL_TICK } from './paperPath';

type OptionTone = 'primary' | 'danger';

/** 稿纸选项：未选浅墨边，选中墨线或暗红边，右上角铅笔勾。 */
export function optionSheet(selected?: boolean, tone: OptionTone = 'primary') {
  return cx(
    'sheet sheet-sm relative block w-full p-3 pr-8 text-left transition-colors',
    selected ? (tone === 'danger' ? 'border-danger bg-danger-soft' : 'border-ink bg-primary-soft') : 'hover:border-ink',
  );
}

export function PencilTick({ className }: { className?: string }) {
  return (
    <svg width="12" height="10" viewBox="0 0 12 10" aria-hidden className={cx('pointer-events-none text-ink', className)}>
      <path d={PENCIL_TICK} fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  );
}

export const OptionCard = forwardRef<HTMLButtonElement, ComponentProps<typeof RadioGroupItem> & { selected?: boolean; tone?: OptionTone }>(
  function OptionCard({ selected, tone = 'primary', className, children, ...p }, ref) {
    return (
      <RadioGroupItem ref={ref} className={cx(optionSheet(selected, tone), className)} {...p}>
        {selected ? <PencilTick className="absolute right-2.5 top-2.5" /> : null}
        {children}
      </RadioGroupItem>
    );
  },
);
