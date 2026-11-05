import type { Metadata, Viewport } from 'next';
import type { ReactNode } from 'react';
import { AppProviders } from './providers';
import '../index.css';

export const metadata: Metadata = { title: '7x24 · 持续产品交付', description: '持续产品交付工作区' };
export const viewport: Viewport = { width: 'device-width', initialScale: 1, themeColor: '#e7e7e7', colorScheme: 'light' };

export default function RootLayout({ children }: { children: ReactNode }) {
  return <html lang="zh-CN"><body><div id="root"><AppProviders>{children}</AppProviders></div></body></html>;
}
