import { clsx, type ClassValue } from 'clsx';
import { extendTailwindMerge } from 'tailwind-merge';

/** tailwind-merge 需要认识自定义字号（caption / display），否则会把 text-caption 当颜色与 text-ink 冲突 */
const twMerge = extendTailwindMerge({ extend: { classGroups: { 'font-size': [{ text: ['caption', 'display'] }] } } });
export function cn(...inputs: ClassValue[]) { return twMerge(clsx(inputs)); }
