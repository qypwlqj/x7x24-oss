import Link from 'next/link';
export default function NotFound() { return <main className="mx-auto max-w-lg space-y-4 p-page"><h1 className="text-xl">找不到这个页面</h1><p className="text-sm text-ink-3">链接可能已经变更，或者页面已被移除。</p><Link href="/overview" className="text-primary-ink underline">返回工作区</Link></main>; }
