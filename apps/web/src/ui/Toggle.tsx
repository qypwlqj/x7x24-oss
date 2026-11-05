import { useId, type ReactNode } from 'react';
import { Switch } from '@/components/ui/misc';

export function Toggle({ checked, onChange, label, hint, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: ReactNode; hint?: ReactNode; disabled?: boolean }) {
  const id = useId();
  return (
    <div className="flex items-center justify-between gap-3">
      <label htmlFor={id} className="min-w-0 cursor-pointer"><span className="block text-sm text-ink">{label}</span>{hint && <span className="block text-xs text-ink-3">{hint}</span>}</label>
      <Switch id={id} checked={checked} disabled={disabled} onCheckedChange={onChange} />
    </div>
  );
}
export { Checkbox } from '@/components/ui/misc';
