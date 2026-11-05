export type ClassValue = string | false | null | undefined;
export const cx = (...v: ClassValue[]) => v.filter(Boolean).join(' ');
export type Tone = 'neutral' | 'primary' | 'success' | 'warn' | 'danger' | 'info' | 'accent';
/** 状态色 → 样式（soft 暗底 + ink 亮字 + 同色霓虹边），全部来自 tokens。soft/ink 组合对比度 ≥ 8:1（见 docs/design-system.md） */
export const TONE: Record<Tone, { soft: string; solid: string; text: string; border: string; glow: string }> = {
  neutral: { soft: 'bg-sunken text-ink-2', solid: 'bg-ink-3', text: 'text-ink-2', border: 'border-line-strong', glow: '' },
  primary: { soft: 'bg-primary-soft text-primary-ink', solid: 'bg-primary', text: 'text-primary-ink', border: 'border-primary', glow: 'shadow-glow-cyan' },
  accent: { soft: 'bg-accent-soft text-accent-ink', solid: 'bg-accent', text: 'text-accent-ink', border: 'border-accent', glow: 'shadow-glow-magenta' },
  success: { soft: 'bg-success-soft text-success-ink', solid: 'bg-success', text: 'text-success-ink', border: 'border-success', glow: 'shadow-glow-lime' },
  warn: { soft: 'bg-warn-soft text-warn-ink', solid: 'bg-warn', text: 'text-warn-ink', border: 'border-warn', glow: '' },
  danger: { soft: 'bg-danger-soft text-danger-ink', solid: 'bg-danger', text: 'text-danger-ink', border: 'border-danger', glow: 'shadow-glow-red' },
  info: { soft: 'bg-info-soft text-info-ink', solid: 'bg-info', text: 'text-info-ink', border: 'border-info', glow: '' },
};
