'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import Home from '../views/Home';
import { api } from '../api';

export function HomeEntry() {
  const router = useRouter();
  useEffect(() => {
    let active = true;
    Promise.all([api<{ required: boolean }>('/api/auth/config'), api('/api/auth/me').catch(() => null)]).then(([config, user]) => { if (active && (user || !config.required)) router.replace('/overview'); }).catch(() => {});
    return () => { active = false; };
  }, [router]);
  return <Home onEnter={() => router.push('/overview')} onWatch={() => router.push('/new')} />;
}
