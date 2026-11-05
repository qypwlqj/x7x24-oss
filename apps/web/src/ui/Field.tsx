import { Children, forwardRef, isValidElement, useId, type ChangeEvent, type ReactElement, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { cx } from './cx';
import { ShadInput, ShadTextarea, Label, fieldClass } from '@/components/ui/misc';
import { SelectRoot, SelectTrigger, SelectValue, SelectContent, SelectItem } from '@/components/ui/select';
import { Tip } from './InfoTip';

const hasW = (c?: string) => /(^|\s)w-/.test(c ?? '');
const base = fieldClass;
export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input({ className, ...p }, ref) { return <ShadInput ref={ref} className={cx(!hasW(className) && 'w-full', className)} {...p} />; });
type Opt = { value: string; label: ReactNode; disabled?: boolean };
const EMPTY = '__x7_empty__'; // Radix Select 不允许空字符串作为值，内部映射
function collect(children: ReactNode, out: Opt[] = []): Opt[] {
  Children.forEach(children, (c) => {
    if (!isValidElement(c)) return; const el = c as ReactElement<any>;
    if (el.type === 'option') out.push({ value: String(el.props.value ?? (typeof el.props.children === 'string' ? el.props.children : '')), label: el.props.children, disabled: el.props.disabled });
    else if (el.props?.children) collect(el.props.children, out);
  });
  return out;
}
/** shadcn Select，保持原生 select 的用法：children 写 option，onChange 收到 e.target.value */
export const Select = forwardRef<HTMLButtonElement, SelectHTMLAttributes<HTMLSelectElement>>(function Select({ className, children, value, defaultValue, onChange, disabled, id, name, required, ...rest }, ref) {
  const opts = collect(children); const enc = (v: unknown) => (v == null || v === '' ? EMPTY : String(v));
  const fire = (v: string) => { const real = v === EMPTY ? '' : v; onChange?.({ target: { value: real, name }, currentTarget: { value: real, name } } as unknown as ChangeEvent<HTMLSelectElement>); };
  const { 'aria-label': al, 'aria-labelledby': alb, title, ...dataProps } = rest as Record<string, any>;
  return (
    <SelectRoot value={value === undefined ? undefined : enc(value)} defaultValue={defaultValue === undefined ? undefined : enc(defaultValue)} onValueChange={fire} disabled={disabled} name={name} required={required}>
      <Tip content={title}><SelectTrigger ref={ref} id={id} aria-label={al} aria-labelledby={alb} {...Object.fromEntries(Object.entries(dataProps).filter(([k]) => k.startsWith('data-')))} className={cx(base, !hasW(className) && 'w-full', 'h-9', className)}>
        <SelectValue placeholder={opts.find(o => o.value === '')?.label} />
      </SelectTrigger></Tip>
      <SelectContent>{opts.map(o => <SelectItem key={o.value || EMPTY} value={enc(o.value)} disabled={o.disabled}>{o.label}</SelectItem>)}</SelectContent>
    </SelectRoot>
  );
});
export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(function Textarea({ className, ...p }, ref) { return <ShadTextarea ref={ref} className={cx(!hasW(className) && 'w-full', className)} {...p} />; });
export function Field({ label, hint, children, className }: { label: ReactNode; hint?: ReactNode; children: (id: string) => ReactNode; className?: string }) {
  const id = useId();
  return <div className={cx('space-y-1', className)}><Label htmlFor={id}>{label}</Label>{children(id)}{hint && <div className="text-xs text-ink-3">{hint}</div>}</div>;
}
