'use client';

import dynamic from 'next/dynamic';
import type { ReactNode } from 'react';

const Console = dynamic(() => import('../App'), { ssr: false, loading: () => <div className="grid min-h-screen place-items-center text-sm text-ink-3">正在打开工作区…</div> });
export function ConsoleRuntime({ children }: { children: ReactNode }) { return <Console>{children}</Console>; }
