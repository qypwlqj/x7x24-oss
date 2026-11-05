import * as React from 'react';
import { cn } from '@/lib/utils';

/** shadcn Table 原语，样式沿用霓虹表头 / 细分隔线 */
export const TableRoot = React.forwardRef<HTMLTableElement, React.TableHTMLAttributes<HTMLTableElement>>(({ className, ...p }, ref) => <table ref={ref} className={cn('w-full border-collapse text-sm', className)} {...p} />);
TableRoot.displayName = 'Table';
export const TableCaption = (p: React.HTMLAttributes<HTMLTableCaptionElement>) => <caption {...p} />;
export const TableHeader = (p: React.HTMLAttributes<HTMLTableSectionElement>) => <thead {...p} />;
export const TableBody = (p: React.HTMLAttributes<HTMLTableSectionElement>) => <tbody {...p} />;
export const TableRow = React.forwardRef<HTMLTableRowElement, React.HTMLAttributes<HTMLTableRowElement>>(({ className, ...p }, ref) => <tr ref={ref} className={className} {...p} />);
TableRow.displayName = 'TableRow';
export const TableHead = ({ className, ...p }: React.ThHTMLAttributes<HTMLTableCellElement>) => <th className={className} {...p} />;
export const TableCell = ({ className, ...p }: React.TdHTMLAttributes<HTMLTableCellElement>) => <td className={className} {...p} />;
