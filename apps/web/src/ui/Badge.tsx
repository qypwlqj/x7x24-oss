import type { ReactNode } from 'react';
import { cx, TONE, type Tone } from './cx';
import { ShadBadge } from '@/components/ui/badge';
import { Tip } from './InfoTip';

/** 状态徽标（基于 shadcn Badge）：暗底 + 同色细边 + 文字（不单靠颜色），圆点带发光 */
export function Badge({ tone = 'neutral', dot, children, className, title }: { tone?: Tone; dot?: boolean; children: ReactNode; className?: string; title?: string }) {
  return <Tip content={title}><ShadBadge tone={tone} className={className} tabIndex={title ? 0 : undefined}>{dot && <i className={cx('h-1.5 w-1.5 rounded-full', TONE[tone].solid, TONE[tone].glow)} />}{children}</ShadBadge></Tip>;
}
