import { useId } from 'react';

/** 轻量折线/面积图。`series` 多条线；颜色用 CSS 变量名（如 'primary' 'ink-3'），无数据时显示占位线。 */
export interface Series { name: string; values: number[]; color?: string; dashed?: boolean; fill?: boolean }
export function Sparkline({ series, width = 240, height = 56, labels, label }: { series: Series[]; width?: number; height?: number; labels?: string[]; label: string }) {
  const id = useId(); const pad = 3; const bottom = labels ? 12 : 0; const h = height - bottom;
  const n = Math.max(...series.map((s) => s.values.length), 2); const max = Math.max(1, ...series.flatMap((s) => s.values));
  const x = (i: number) => pad + (i * (width - 2 * pad)) / (n - 1); const y = (v: number) => pad + (1 - v / max) * (h - 2 * pad);
  const desc = series.map((s) => `${s.name} 合计 ${s.values.reduce((a, b) => a + b, 0)}`).join('，');
  return (
    <svg role="img" aria-label={`${label}：${desc}`} width="100%" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" style={{ maxWidth: width, height }} className="overflow-visible">
      <defs>{series.map((s, k) => <linearGradient key={k} id={`${id}-${k}`} x1="0" x2="0" y1="0" y2="1"><stop offset="0" stopColor={`rgb(var(--c-${s.color ?? 'primary'}))`} stopOpacity=".32" /><stop offset="1" stopColor={`rgb(var(--c-${s.color ?? 'primary'}))`} stopOpacity="0" /></linearGradient>)}</defs>
      <line x1={pad} x2={width - pad} y1={h - pad} y2={h - pad} stroke="rgb(var(--c-line))" />
      {series.map((s, k) => {
        const pts = s.values.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`);
        return <g key={k}>
          {s.fill && s.values.length > 1 && <polygon points={`${x(0)},${h - pad} ${pts.join(' ')} ${x(s.values.length - 1)},${h - pad}`} fill={`url(#${id}-${k})`} />}
          <polyline points={pts.join(' ')} fill="none" stroke={`rgb(var(--c-${s.color ?? 'primary'}))`} strokeWidth={1.8} style={{ filter: `drop-shadow(0 0 3px rgb(var(--c-${s.color ?? 'primary'}) / .8))` }} strokeLinejoin="round" strokeLinecap="round" strokeDasharray={s.dashed ? '4 3' : undefined} vectorEffect="non-scaling-stroke" />
        </g>;
      })}
      {labels && [0, Math.floor(n / 2), n - 1].map((i) => <text key={i} x={x(i)} y={height - 1} textAnchor={i === 0 ? 'start' : i === n - 1 ? 'end' : 'middle'} fontSize="9" fill="rgb(var(--c-ink-3))">{labels[i]}</text>)}
    </svg>
  );
}
