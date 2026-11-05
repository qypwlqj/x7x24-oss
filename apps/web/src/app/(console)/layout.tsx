import { Suspense, type ReactNode } from 'react';
import { ConsoleRuntime } from '@/components/ConsoleRuntime';

export default function ConsoleLayout({ children }: { children: ReactNode }) {
  return <Suspense fallback={<div className="p-page text-sm text-ink-3">正在打开工作区…</div>}><ConsoleRuntime>{children}</ConsoleRuntime></Suspense>;
}
