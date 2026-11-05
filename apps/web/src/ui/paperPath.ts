/** 固定铅笔矩形。四边各弓出约 1.5px，拐角略出头。同一宽高永远得到同一条路径。 */
export function sheetPath(w: number, h: number): string {
  const bow = 1.5;
  const over = 0.8;
  return [
    `M${(-over).toFixed(2)},1.10`,
    `C${(w * 0.22).toFixed(2)},${(-bow).toFixed(2)} ${(w * 0.68).toFixed(2)},${(bow * 0.55).toFixed(2)} ${(w + over * 0.6).toFixed(2)},-0.30`,
    `C${(w + bow).toFixed(2)},${(h * 0.28).toFixed(2)} ${(w - 0.2).toFixed(2)},${(h * 0.7).toFixed(2)} ${(w + 0.4).toFixed(2)},${(h + over).toFixed(2)}`,
    `C${(w * 0.74).toFixed(2)},${(h + bow).toFixed(2)} ${(w * 0.24).toFixed(2)},${(h - 0.35).toFixed(2)} -0.50,${(h + 0.45).toFixed(2)}`,
    `C${(-bow).toFixed(2)},${(h * 0.62).toFixed(2)} 0.35,${(h * 0.3).toFixed(2)} ${(-over).toFixed(2)},1.10`,
    'Z',
  ].join(' ');
}

/** 泳道横格的 y。从顶边往下每 8px 一条，不贴底边。 */
export function laneRuleYs(top: number, height: number, step = 8): number[] {
  const ys: number[] = [];
  for (let y = top + step; y < top + height - 1; y += step) ys.push(y);
  return ys;
}

export function marginDash(kind: 'solid' | 'dashed' | 'dotted'): string | undefined {
  if (kind === 'dashed') return '4 3';
  if (kind === 'dotted') return '1.5 3';
  return undefined;
}

export const PENCIL_TICK = 'M1,5.2 C2.4,6.6 3.6,8.6 4.6,8.3 C6.4,4.6 8.6,2.4 11,1.3';
export const PENCIL_CARET = 'M1,1.5 L5,6.2 L9,1.2';
export const PENCIL_RULE = 'M0.6,3.1 C7,1.1 13,4.2 20,2.2 C23.5,1.4 26,2.8 27.4,2';
export const FIBER_MATRIX = '0 0 0 0 0.1  0 0 0 0 0.09  0 0 0 0 0.08  0 0 0 0.18 0';
