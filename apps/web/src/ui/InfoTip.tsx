import type { ReactNode } from 'react';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/misc';
import { cx } from './cx';
import { lookup } from '../lib/glossary';

/** 悬停 / 聚焦显示的解释气泡（shadcn Tooltip / Radix：键盘聚焦即显示，Esc 关闭，aria-describedby 关联）。 */
export function InfoTip({ text, title, label = '说明', className }: { text: ReactNode; title?: string; label?: string; className?: string }) {
  return (
    <TooltipProvider delayDuration={150}><Tooltip>
      <span className={cx('relative inline-flex align-middle', className)}>
        <TooltipTrigger type="button" aria-label={title ? `${label}：${title}` : label} data-infotip
          className="inline-grid h-4 w-4 place-items-center rounded-full border border-line-strong text-[10px] font-bold leading-none text-ink-3 hover:border-primary hover:text-primary-ink focus-visible:border-primary focus-visible:outline-none">?</TooltipTrigger>
      </span>
      <TooltipContent>{title && <b className="mb-0.5 block text-primary-ink">{title}</b>}{text}</TooltipContent>
    </Tooltip></TooltipProvider>
  );
}
/** 术语：文字带虚线下划线，旁边跟一个 InfoTip；k 是 glossary 的键 */
export function Term({ k, children, bare }: { k: string; children?: ReactNode; bare?: boolean }) {
  const g = lookup(k);
  if (!g) return <>{children ?? k}</>;
  return <span className="inline-flex items-center gap-1"><span className={bare ? undefined : 'border-b border-dotted border-ink-3'}>{children ?? g.name}</span><InfoTip title={g.name} text={g.text} /></span>;
}
/** 设置项下方的“改了会怎样”一句话 */
export function Effect({ children }: { children: ReactNode }) { return <p className="mt-1 text-xs leading-5 text-ink-3"><span className="mr-1 text-primary-ink">改了会怎样：</span>{children}</p>; }
/** 给任意元素加 shadcn Tooltip（替代原生 title 悬停提示）：content 为空时原样返回；禁用元素外包一层 span 以便仍能悬停 */
export function Tip({ content, children, wrap, placement }: { content?: ReactNode; children: ReactNode; wrap?: boolean; placement?: 'top' | 'right' | 'bottom' | 'left' }) {
  if (content == null || content === '' || content === false) return <>{children}</>;
  return (
    <TooltipProvider delayDuration={200}><Tooltip>
      <TooltipTrigger asChild>{wrap ? <span className="inline-flex">{children}</span> : children}</TooltipTrigger>
      <TooltipContent side={placement}>{content}</TooltipContent>
    </Tooltip></TooltipProvider>
  );
}
