import * as React from 'react';
import * as R from '@radix-ui/react-radio-group';
import { cn } from '@/lib/utils';

export const RadioGroup = React.forwardRef<React.ElementRef<typeof R.Root>, React.ComponentPropsWithoutRef<typeof R.Root>>(({ className, ...p }, ref) => <R.Root ref={ref} className={className} {...p} />);
RadioGroup.displayName = 'RadioGroup';
/** 单选项按钮。稿纸卡片用 `ui/OptionCard`，边框和铅笔勾不写在这里。 */
export const RadioGroupItem = React.forwardRef<React.ElementRef<typeof R.Item>, React.ComponentPropsWithoutRef<typeof R.Item>>(({ className, ...p }, ref) => <R.Item ref={ref} className={cn(className)} {...p} />);
RadioGroupItem.displayName = 'RadioGroupItem';
