import { notFound, redirect } from 'next/navigation';
import { navRedirect } from '@/lib/nav';

/** Old path aliases redirect on the server; application pages have their own App Router entries. */
export default async function LegacyPage({ params, searchParams }: { params: Promise<{ legacy: string[] }>; searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const [{ legacy }, search] = await Promise.all([params, searchParams]);
  const query = new URLSearchParams();
  Object.entries(search).forEach(([key, value]) => (Array.isArray(value) ? value : value === undefined ? [] : [value]).forEach(item => query.append(key, item)));
  const target = navRedirect('/' + legacy.map(encodeURIComponent).join('/') + (query.size ? '?' + query : ''));
  if (target) redirect(target);
  notFound();
}
