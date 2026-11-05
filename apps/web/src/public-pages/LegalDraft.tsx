'use client';

import { AppLink } from '@/components/AppLink';
// 公开落地页（未登录可访问）：刻意使用首页的纸笔样式，不走控制台的 shadcn 组件
import '@fontsource/ibm-plex-mono/latin-400.css';
import '../styles/home.css';

const Top = () => <div className="top"><AppLink href="/">← 7x24 Agent</AppLink><span>草案页面 · 不对外公开</span></div>;

const DOCS = { terms: '服务条款', privacy: '隐私政策', refund: '退款政策' } as const;
export function LegalDraft({ doc, body }: { doc: keyof typeof DOCS; body: string }) {
  const title = DOCS[doc];
  return <div className="home-page" data-testid="legal-page"><Top />
    <main className="more"><h2>{title}<span className="badge">草案 · 未生效</span></h2><div className="card"><div className="draft">{body}</div></div></main></div>;
}
