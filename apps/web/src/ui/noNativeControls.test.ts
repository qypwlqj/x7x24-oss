import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

/** 页面层只用 shadcn 组件：原生 select / details / table / 手写 radio 只允许出现在 components/ui 封装里 */
const files = (d: string): string[] => readdirSync(d).flatMap(f => { const p = join(d, f); return statSync(p).isDirectory() ? files(p) : /\.tsx$/.test(f) && !/\.test\./.test(f) ? [p] : []; });
const pages = [...files(join(__dirname, '..', 'views')), join(__dirname, '..', 'App.tsx')];
describe('no native controls in pages', () => {
  it('pages use shadcn Select / Collapsible / Table / RadioGroup / Tooltip', () => {
    const bad: string[] = [];
    for (const f of pages) readFileSync(f, 'utf8').split('\n').forEach((l, i) => {
      if (/<(select|details|summary|table|textarea|input|dialog)\b/.test(l) || /role="radio(group)?"/.test(l) || /<(div|span|button|label|td|li|p|a)\b[^>]*\stitle=/.test(l)) bad.push(`${f.split('/src/')[1]}:${i + 1}`);
    });
    expect(bad).toEqual([]);
  });
});
