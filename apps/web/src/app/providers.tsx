'use client';

import type { ReactNode } from 'react';
import { ConfirmProvider, Toaster } from '../ui';
import { TooltipProvider } from '@/components/ui/misc';
import { LegacyHashBridge } from '@/components/LegacyHashBridge';

export function AppProviders({ children }: { children: ReactNode }) {
  return <TooltipProvider delayDuration={150}><ConfirmProvider><LegacyHashBridge />{children}<Toaster /></ConfirmProvider></TooltipProvider>;
}
