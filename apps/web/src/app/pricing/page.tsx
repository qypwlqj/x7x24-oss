import type { Metadata } from 'next';
import Pricing from '@/public-pages/Pricing';
export const metadata: Metadata = { title: 'Pricing — 7x24 Agent', robots: { index: false, follow: false } };
export default function PricingPage() { return <Pricing />; }
