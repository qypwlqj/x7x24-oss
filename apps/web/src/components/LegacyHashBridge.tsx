'use client';

import { useEffect } from 'react';
import { appPath, legacyHashTarget } from '../lib/navigation';

export function LegacyHashBridge() {
  useEffect(() => {
    const redirect = () => { const target = legacyHashTarget(location.hash, location.search); if (target) location.replace(appPath(target)); };
    redirect(); window.addEventListener('hashchange', redirect);
    return () => window.removeEventListener('hashchange', redirect);
  }, []);
  return null;
}
