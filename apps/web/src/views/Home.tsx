import { AppLink } from '@/components/AppLink';
import { useEffect, useRef, useState } from 'react';
import '@fontsource/ibm-plex-mono/latin-400.css';
import '@fontsource/ibm-plex-mono/latin-500.css';
import '../styles/home.css';

/** 4px stroke, 21px cap, two baselines. Ink ends at x=96, so the title width is 318/436 of the original 132-unit lockup. */
const HEADLINE =
  'M0 0h16v4h-16zM12 4h4v4h-4zM12 8h4v4h-4zM8 12h4v4h-4zM8 16h4v5h-4zM20 0h4v4h-4zM32 0h4v4h-4zM20 4h4v4h-4zM32 4h4v4h-4zM24 8h8v4h-8zM20 12h4v4h-4zM32 12h4v4h-4zM20 16h4v5h-4zM32 16h4v5h-4zM40 0h16v4h-16zM52 4h4v4h-4zM40 8h16v4h-16zM40 12h4v4h-4zM40 16h16v5h-16zM60 0h4v4h-4zM72 0h4v4h-4zM60 4h4v4h-4zM72 4h4v4h-4zM60 8h16v4h-16zM72 12h4v4h-4zM72 16h4v5h-4zM4 25h8v4h-8zM0 29h4v4h-4zM12 29h4v4h-4zM0 33h16v4h-16zM0 37h4v4h-4zM12 37h4v4h-4zM0 41h4v5h-4zM12 41h4v5h-4zM20 25h16v4h-16zM20 29h4v4h-4zM20 33h4v4h-4zM28 33h8v4h-8zM20 37h4v4h-4zM32 37h4v4h-4zM20 41h16v5h-16zM40 25h16v4h-16zM40 29h4v4h-4zM40 33h12v4h-12zM40 37h4v4h-4zM40 41h16v5h-16zM60 25h4v4h-4zM72 25h4v4h-4zM60 29h8v4h-8zM72 29h4v4h-4zM60 33h4v4h-4zM68 33h8v4h-8zM60 37h4v4h-4zM72 37h4v4h-4zM60 41h4v5h-4zM72 41h4v5h-4zM80 25h16v4h-16zM84 29h8v4h-8zM84 33h8v4h-8zM84 37h8v4h-8zM84 41h8v5h-8z';

const NAV = [
  ['能力', 'ability'],
  ['流程', 'flow'],
  ['治理', 'govern'],
  ['现状', 'status'],
] as const;

const ABILITY: [string, string, string][] = [
  ['01', '对话建项目', '说一句话，建产品线、项目、需求卡。会改数据的操作都先给确认卡。'],
  ['02', '绑定仓库', '一个项目绑定一个 GitHub 仓库；可在对话里绑定或新建（新建需授权）。'],
  ['03', '自动交付流水线', '需求 → 计划 → 实现 → 测试 → 审查 → 合并，失败自动返修，次数有上限。'],
  ['04', '合并门禁', 'CI 不绿不合并；高风险改动必须人工验收。'],
  ['05', '逐行审查', '测试通过后用开源项目 open-code-review 逐行看改动（观察期，先试跑）。'],
  ['06', '仓库体检', '检查分支保护、CI、权限是否齐，缺什么直接说。'],
];
const FLOW = ['说需求', '确认卡', '需求草稿', '流水线执行', '你来验收'];
const GOVERN: [string, string][] = [
  ['预算上限', '按工作区设月度上限，到省钱线自动降级模型。'],
  ['权限分级', '所有者 / 管理员 / 成员 / 只读，不会替你提权。'],
  ['审计', '每次确认、合并、回滚都有记录可查。'],
  ['治理配置', '返修次数、验收退回上限等都能调。'],
];

export default function Home({ onEnter, onWatch }: { onEnter: () => void; onWatch: () => void }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const prevTitle = document.title;
    document.title = '7x24 Agent — 持续产品交付';
    return () => { document.title = prevTitle; };
  }, []);

  useEffect(() => {
    const video = videoRef.current;
    if (!video || typeof window.matchMedia !== 'function') return;
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const apply = () => { if (mq.matches) video.pause(); else void video.play().catch(() => {}); };
    apply();
    mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
  }, []);

  // Keep section scrolling inside the landing page.
  const stay = (event: { preventDefault: () => void }) => { event.preventDefault(); setOpen(false); };
  // 页内跳转直接滚动到对应区块
  const go = (id: string) => (event: { preventDefault: () => void }) => { stay(event); document.getElementById('home-' + id)?.scrollIntoView({ behavior: 'smooth' }); };

  return <div className="home-page" data-testid="home"><section className="home hero" id="home-hero">
    <video ref={videoRef} className="hero__media" autoPlay muted loop playsInline preload="auto" poster="https://d8j0ntlcm91z4.cloudfront.net/user_38xzZboKViGWJOttwIXH07lWA1P/hf_20261008_131819_f6c1a554-6417-497f-a8b7-585449018c39.png">
      <source src="https://d8j0ntlcm91z4.cloudfront.net/user_38xzZboKViGWJOttwIXH07lWA1P/hf_20261008_131936_fe93478c-8802-4266-a258-b4f95767462c.mp4" type="video/mp4" />
    </video>
    <header className={open ? 'nav is-open' : 'nav'} id="nav">
      <AppLink className="nav__logo" href="#hero" aria-label="7x24 Agent 首页" onClick={go('hero')}>
        <svg viewBox="0 0 22 22" aria-hidden="true">
          <path fill="#050505" d="M0 0Q5.5 4.6 7 2.6Q11-1.6 15 2.6Q16.500 4.6 22 0V22Q16.500 17.400 15 19.400Q11 23.600 7 19.400Q5.500 17.400 0 22Z" />
          <path fill="#ececea" d="M5.800 6.600Q6.500 10.300 10.200 11Q6.500 11.700 5.800 15.400Q5.100 11.700 1.400 11Q5.100 10.300 5.800 6.600ZM16.200 6.600Q16.900 10.300 20.600 11Q16.900 11.700 16.200 15.400Q15.500 11.700 11.800 11Q15.500 10.300 16.200 6.600Z" />
        </svg>
      </AppLink>
      <button className="nav__toggle" id="navToggle" type="button" aria-label="菜单" aria-expanded={open} aria-controls="navLinks" onClick={() => setOpen(v => !v)}><span /></button>
      <ul className="nav__links" id="navLinks">
        {NAV.map(([label, id]) => <li key={id}><AppLink href={`#${id}`} onClick={go(id)}>{label}</AppLink></li>)}
      </ul>
    </header>
    <h1 className="hero__title">
      <span className="sr-only">7x24 Agent</span>
      <svg viewBox="0 0 96 46" shapeRendering="crispEdges" aria-hidden="true"><path d={HEADLINE} /></svg>
    </h1>
    <p className="bubble" aria-label="7x24：需求说给我，交付给你验收。">
      需求说给我，<br />交付给你验收。
      <span className="bubble__cursor" aria-hidden="true" />
      <svg className="bubble__tail" viewBox="0 0 6 6" shapeRendering="crispEdges" aria-hidden="true">
        <path fill="#e7e7e7" d="M1 0h4v1h-1v1h-1v1h-1v1h-1z" />
        <path fill="#050505" d="M0 0h1v5h-1zM1 4h1v1h-1zM2 3h1v1h-1zM3 2h1v1h-1zM4 1h1v1h-1zM5 0h1v1h-1z" />
      </svg>
    </p>
    <div className="hero__body">
      <p className="hero__lede">在对话里提需求，7x24 按你的规则<br />计划、写代码、测试、审查，交给你验收。</p>
      <div className="hero__actions">
        <AppLink className="btn" href="#enter" onClick={(e) => { e.preventDefault(); onEnter(); }}>进入控制台</AppLink>
        <AppLink className="link" href="/command" onClick={(e) => { e.preventDefault(); onWatch(); }}>
          看它怎么交付
          <svg viewBox="0 0 5 8" aria-hidden="true"><path d="M.8.8 4 4 .8 7.200" /></svg>
        </AppLink>
      </div>
      <ul className="hero__stats" aria-label="7x24 一览">
        <li>先确认再执行</li>
        <li>CI 不绿不合并</li>
        <li>全程可审计</li>
      </ul>
    </div>
  </section>
  <main className="more">
    <section id="home-ability"><h2>能力 <small>// 现在能做的</small></h2><div className="grid">{ABILITY.map(([n, t, d]) => <div className="card" key={n}><b>{n}</b><h3>{t}</h3><p>{d}</p></div>)}</div></section>
    <section id="home-flow"><h2>流程 <small>// 每一步都可以喊停</small></h2><ol className="flow">{FLOW.map((f, i) => <li key={f}><b>{String(i + 1).padStart(2, '0')}</b>{f}</li>)}</ol></section>
    <section id="home-govern"><h2>治理 <small>// 花多少、谁能做，都由你定</small></h2><div className="grid g4">{GOVERN.map(([t, d]) => <div className="card" key={t}><h3>{t}</h3><p>{d}</p></div>)}</div></section>
    <section id="home-status"><h2>现状 <small>// 如实说</small></h2><div className="card wide"><p>当前线上是<b>观察（演练）模式</b>：会出计划和预览，不会真的改你的仓库。管理员在「集成」里切换为执行模式后才会提交 PR、合并。对话里新建仓库需要先给 GitHub App 开通权限。</p></div></section>
    <footer>7x24 Agent · <AppLink href="#enter" onClick={(e) => { e.preventDefault(); onEnter(); }}>进入控制台</AppLink></footer>
  </main></div>;
}
