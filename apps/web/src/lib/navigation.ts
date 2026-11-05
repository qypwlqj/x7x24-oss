import { navRedirect } from './nav';

export const BASE_PATH = (process.env.NEXT_PUBLIC_BASE_PATH ?? '').replace(/\/+$/, '');
export const appPath = (path: string) => { const value = path.replace(/^#?\/?/, '/'); return BASE_PATH + value; };
export function navigationTarget(path: string): string {
  const value = path.replace(/^#?\/?/, '/');
  return navRedirect(value) ?? value;
}
/** Legacy page hashes redirect once. OAuth credentials stay in a fragment, never a query. */
export function legacyHashTarget(hash: string, search = ''): string | null {
  if (!hash.startsWith('#/')) return null;
  const callback = /^#\/auth\/callback\?(.*)$/.exec(hash);
  if (callback) return `/overview${search}#${callback[1]}`;
  return navigationTarget(hash);
}
