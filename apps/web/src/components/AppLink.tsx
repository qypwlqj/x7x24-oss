import Link from 'next/link';
import type { AnchorHTMLAttributes } from 'react';
import { navigationTarget } from '../lib/navigation';

/** Keep anchors, downloads and external destinations native; Next owns application navigation. */
export function AppLink({ href, ...props }: AnchorHTMLAttributes<HTMLAnchorElement>) {
  if (href && !props.download && !href.startsWith('/api/') && (href.startsWith('#/') || href.startsWith('/') && !href.startsWith('//'))) return <Link {...props} href={navigationTarget(href)} />;
  return <a href={href} {...props} />;
}
