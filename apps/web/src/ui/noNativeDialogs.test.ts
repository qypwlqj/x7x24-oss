import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const files = (d: string): string[] => readdirSync(d).flatMap(f => { const p = join(d, f); return statSync(p).isDirectory() ? files(p) : /\.tsx?$/.test(f) && !f.endsWith('.test.ts') ? [p] : []; });
const src = files(join(__dirname, '..'));
describe('no native browser dialogs', () => {
  it('never calls window.confirm / alert / prompt', () => {
    const bad: string[] = [];
    for (const f of src) readFileSync(f, 'utf8').split('\n').forEach((l, i) => {
      if (/window\.(confirm|alert|prompt)\s*\(/.test(l) || /(^|[^\w.])(alert|prompt)\s*\(/.test(l) || /(^|[^\w.])confirm\s*\(/.test(l.replace(/await confirm\s*\(/g, ''))) bad.push(`${f}:${i + 1}`);
    });
    expect(bad).toEqual([]);
  });
  it('uses the shared confirm hook for destructive actions', () => {
    const all = src.map(f => readFileSync(f, 'utf8')).join('\n');
    expect(all).toContain('useConfirm()'); expect((all.match(/destructive: true/g) ?? []).length).toBeGreaterThanOrEqual(3);
  });
});
