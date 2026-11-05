import type { ReactNode } from 'react';
import { cx } from './cx';
import { TableRoot, TableCaption, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table';

export interface Column<R> { key: string; header: ReactNode; render: (row: R, i: number) => ReactNode; align?: 'left' | 'right' | 'center'; className?: string; width?: string }
export function Table<R>({ columns, rows, rowKey, onRowClick, empty = '暂无数据', caption, dense, className }: {
  columns: Column<R>[]; rows: R[]; rowKey: (r: R) => string | number; onRowClick?: (r: R) => void; empty?: ReactNode; caption: string; dense?: boolean; className?: string;
}) {
  const al = { left: 'text-left', right: 'text-right', center: 'text-center' };
  return (
    <div className={cx('overflow-x-auto', className)}>
      <TableRoot>
        <TableCaption className="sr-only">{caption}</TableCaption>
        <TableHeader><TableRow className="border-b border-primary/40 bg-sunken/80 text-caption text-primary-ink">{columns.map((c) => <TableHead key={c.key} scope="col" style={{ width: c.width }} className={cx('label-caps whitespace-nowrap px-3 py-2 font-semibold', al[c.align ?? 'left'])}>{c.header}</TableHead>)}</TableRow></TableHeader>
        <TableBody>
          {rows.length === 0 && <TableRow><TableCell colSpan={columns.length} className="px-3 py-8 text-center text-ink-3">{empty}</TableCell></TableRow>}
          {rows.map((r, i) => (
            <TableRow key={rowKey(r)} onClick={onRowClick ? () => onRowClick(r) : undefined} onKeyDown={onRowClick ? (e) => { if (e.key === 'Enter') onRowClick(r); } : undefined} tabIndex={onRowClick ? 0 : undefined}
              className={cx('border-t border-line transition-colors', onRowClick && 'cursor-pointer hover:bg-primary-soft/60 hover:shadow-[inset_3px_0_0_rgb(var(--c-primary))] focus-visible:bg-primary-soft/60')}>
              {columns.map((c) => <TableCell key={c.key} className={cx(dense ? 'px-3 py-1.5' : 'px-3 py-2.5', 'align-middle', al[c.align ?? 'left'], c.className)}>{c.render(r, i)}</TableCell>)}
            </TableRow>
          ))}
        </TableBody>
      </TableRoot>
    </div>
  );
}
