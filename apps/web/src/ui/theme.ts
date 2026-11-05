import { useCallback, useEffect, useState } from 'react';

/** 只有纸面一套。`.light` 与 `:root` 相同，旧的 localStorage 不能换回另一套配色。 */
export type Theme = 'paper';
const KEY = 'x7x24-theme-v2';
export const readTheme = (): Theme => 'paper';
export function useTheme(): [Theme, () => void] {
  const [t] = useState<Theme>('paper');
  useEffect(() => {
    document.documentElement.classList.remove('light');
    try { localStorage.removeItem(KEY); } catch { /* ignore */ }
  }, []);
  return [t, useCallback(() => {}, [])];
}
