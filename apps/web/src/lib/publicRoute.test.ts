import { describe, expect, it } from 'vitest';
import { EMAIL_RE, publicHref, publicRoute } from './publicRoute';
describe('publicRoute', () => {
  it('reaches pricing by path or hash', () => {
    expect(publicRoute('/pricing', '')).toEqual({ page: 'pricing' });
    expect(publicRoute('/pricing/', '')).toEqual({ page: 'pricing' });
    expect(publicRoute('/', '#/pricing')).toEqual({ page: 'pricing' });
    expect(publicRoute('/', '#/legal/privacy')).toEqual({ page: 'legal', doc: 'privacy' });
    expect(publicRoute('/', '#/overview')).toBeNull(); expect(publicRoute('/', '')).toBeNull(); expect(publicRoute('/', '#/legal/x')).toBeNull();
  });
  it('validates email', () => { expect(EMAIL_RE.test('a.b@example.com')).toBe(true); expect(EMAIL_RE.test('a@b')).toBe(false); expect(EMAIL_RE.test('x y@a.com')).toBe(false); });
  it('generates internal history links', () => { expect(publicHref({ page: 'pricing' })).toBe('/pricing'); expect(publicHref({ page: 'legal', doc: 'privacy' })).toBe('/legal/privacy'); });
});
