import { describe, expect, it } from 'vitest';
import { publicHref, publicRoute } from './publicRoute';
describe('publicRoute', () => {
  it('reaches legal pages by path or hash; unknown paths including old pricing return null', () => {
    expect(publicRoute('/legal/privacy', '')).toEqual({ page: 'legal', doc: 'privacy' });
    expect(publicRoute('/legal/privacy/', '')).toEqual({ page: 'legal', doc: 'privacy' });
    expect(publicRoute('/', '#/legal/privacy')).toEqual({ page: 'legal', doc: 'privacy' });
    expect(publicRoute('/pricing', '')).toBeNull();
    expect(publicRoute('/', '#/pricing')).toBeNull();
    expect(publicRoute('/', '#/overview')).toBeNull(); expect(publicRoute('/', '')).toBeNull(); expect(publicRoute('/', '#/legal/x')).toBeNull();
  });
  it('generates internal history links', () => { expect(publicHref({ page: 'legal', doc: 'privacy' })).toBe('/legal/privacy'); });
});
