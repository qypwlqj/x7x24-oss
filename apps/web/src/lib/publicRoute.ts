/** Public page matching, retaining recognition of older hash links. App Router serves the pages. */
export type PublicRoute = { page: 'legal'; doc: 'terms' | 'privacy' | 'refund' };
export function publicRoute(pathname: string, hash: string): PublicRoute | null {
  const h = hash.replace(/^#\/?/, '/').split('?')[0].replace(/\/+$/, ''); const p = pathname.replace(/\/+$/, '');
  for (const x of [h, p]) {
    const m = /^\/legal\/(terms|privacy|refund)$/.exec(x); if (m) return { page: 'legal', doc: m[1] as 'terms' | 'privacy' | 'refund' };
  }
  return null;
}
/** Public links use history paths. */
export const publicHref = (r: PublicRoute) => `/legal/${r.doc}`;
