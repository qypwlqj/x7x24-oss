import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { LegalDraft } from '@/public-pages/Pricing';

const titles = { terms: '服务条款', privacy: '隐私政策', refund: '退款政策' } as const;
type Props = { params: Promise<{ doc: string }> };
export function generateStaticParams() { return Object.keys(titles).map(doc => ({ doc })); }
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { doc } = await params;
  return { title: `${titles[doc as keyof typeof titles] ?? '政策'}（草案）— 7x24 Agent`, robots: { index: false, follow: false } };
}
export default async function LegalPage({ params }: Props) {
  const { doc } = await params;
  if (!Object.hasOwn(titles, doc)) notFound();
  const body = await readFile(join(process.cwd(), 'src/legal', `${doc}.md`), 'utf8');
  return <LegalDraft doc={doc as keyof typeof titles} body={body} />;
}
