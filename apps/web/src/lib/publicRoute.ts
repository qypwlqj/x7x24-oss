/** Public page matching, retaining recognition of older hash links. App Router serves the pages. */
export type PublicRoute = { page: 'pricing' } | { page: 'legal'; doc: 'terms' | 'privacy' | 'refund' };
export function publicRoute(pathname: string, hash: string): PublicRoute | null {
  const h = hash.replace(/^#\/?/, '/').split('?')[0].replace(/\/+$/, ''); const p = pathname.replace(/\/+$/, '');
  for (const x of [h, p]) {
    if (x === '/pricing') return { page: 'pricing' };
    const m = /^\/legal\/(terms|privacy|refund)$/.exec(x); if (m) return { page: 'legal', doc: m[1] as 'terms' | 'privacy' | 'refund' };
  }
  return null;
}
/** Public links use history paths. */
export const publicHref = (r: PublicRoute) => r.page === 'pricing' ? '/pricing' : `/legal/${r.doc}`;
export const EMAIL_RE = /^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,24}$/;
