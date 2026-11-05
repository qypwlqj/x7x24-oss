'use client';

import { AppLink } from '@/components/AppLink';
// 公开落地页（未登录可访问）：刻意使用原生表单控件和首页的纸笔样式，不走控制台的 shadcn 组件
import { useState } from 'react';
import '@fontsource/ibm-plex-mono/latin-400.css';
import '../styles/home.css';
import { EMAIL_RE } from '../lib/publicRoute';
import { apiUrl } from '../lib/auth';

/** 只列线上已有的功能；演练中 / 需要另外开启的照实标注，未上线的不写 */
export const PRO_FEATURES = [
  '对话建产品线、项目和需求卡（先确认再执行）',
  '在对话或「集成」页绑定 GitHub 仓库',
  '需求 → 计划 → 实现 → 测试 → 审查流水线（真实改动仓库需管理员开启执行模式）',
  'CI 未通过不合并，高风险改动需人工验收',
  '预算上限与省钱线',
  '成员角色权限与操作审计',
  '治理配置（返修次数、验收退回上限等）',
];

const Top = () => <div className="top"><AppLink href="/">← 7x24 Agent</AppLink><span>草案页面 · 不对外公开</span></div>;

export function Waitlist() {
  const [email, setEmail] = useState(''); const [ok, setOk] = useState(false); const [hp, setHp] = useState('');
  const [busy, setBusy] = useState(false); const [msg, setMsg] = useState<{ err: boolean; text: string } | null>(null);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault(); setMsg(null);
    const v = email.trim(); if (!EMAIL_RE.test(v)) { setMsg({ err: true, text: '请填写有效的邮箱地址' }); return; }
    if (!ok) { setMsg({ err: true, text: '请先阅读并同意隐私政策' }); return; }
    setBusy(true);
    try {
      const r = await fetch(apiUrl('/api/public/waitlist'), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: v, consent: true, source: 'pricing', website: hp }) });
      const d = await r.json().catch(() => ({}));
      if (r.ok) { setMsg({ err: false, text: d.message ?? '已加入等待名单' }); setEmail(''); }
      else setMsg({ err: true, text: d.message ?? '提交失败，请稍后再试' });
    } catch { setMsg({ err: true, text: '网络错误，请稍后再试' }); } finally { setBusy(false); }
  };
  return <form className="wl" onSubmit={submit} noValidate data-testid="waitlist-form">
    <input type="email" aria-label="邮箱" placeholder="you@example.com" value={email} onChange={e => setEmail(e.target.value)} maxLength={254} data-testid="waitlist-email" />
    <button type="submit" disabled={busy} data-testid="waitlist-submit">{busy ? '提交中…' : '加入等待名单'}</button>
    <span className="hp" aria-hidden="true"><input tabIndex={-1} autoComplete="off" value={hp} onChange={e => setHp(e.target.value)} name="website" /></span>
    <label><input type="checkbox" checked={ok} onChange={e => setOk(e.target.checked)} data-testid="waitlist-consent" /><span>我同意按照<AppLink href="/legal/privacy" style={{ textDecoration: 'underline' }}>隐私政策（草案）</AppLink>保存我的邮箱，仅用于付费功能开通时联系我；可随时要求删除。</span></label>
    {msg && <div className={msg.err ? 'msg err' : 'msg'} role={msg.err ? 'alert' : 'status'} data-testid="waitlist-msg">{msg.text}</div>}
  </form>;
}

export default function Pricing() {
  return <div className="home-page" data-testid="pricing-page"><Top />
    <main className="more">
      <section id="home-pricing"><h2>Pricing <small>// 一个档位</small></h2>
        <div className="grid g3">
          <div className="card plan hot"><h3>Pro</h3><div className="price">$20 <small>/ 月 · 价格已含税</small></div>
            <ul>{PRO_FEATURES.map(x => <li key={x}>{x}</li>)}</ul>
            <p className="note">在线支付尚未开通，暂不收费。留下邮箱，开通时通知你。</p>
            <Waitlist />
          </div>
          <div className="card wide terms"><h3>计费说明</h3><ul>
            <li>价格为美元，价格已含税。</li>
            <li>按月计费，到期自动续费；可随时取消，取消后当期结束前仍可使用。</li>
            <li>退款规则见 <AppLink href="/legal/refund" style={{ textDecoration: 'underline' }}>退款政策（草案）</AppLink>。</li>
            <li>第三方费用另计：模型 API 调用按你的服务商账单收费；GitHub 等服务按其自身价格。</li>
            <li>不承诺交付效果或可用性 SLA。</li>
            <li><AppLink href="/legal/terms" style={{ textDecoration: 'underline' }}>服务条款（草案）</AppLink> · <AppLink href="/legal/privacy" style={{ textDecoration: 'underline' }}>隐私政策（草案）</AppLink></li>
          </ul></div>
        </div>
      </section>
    </main></div>;
}

const DOCS = { terms: '服务条款', privacy: '隐私政策', refund: '退款政策' } as const;
export function LegalDraft({ doc, body }: { doc: keyof typeof DOCS; body: string }) {
  const title = DOCS[doc];
  return <div className="home-page" data-testid="legal-page"><Top />
    <main className="more"><h2>{title}<span className="badge">草案 · 未生效</span></h2><div className="card"><div className="draft">{body}</div></div></main></div>;
}
