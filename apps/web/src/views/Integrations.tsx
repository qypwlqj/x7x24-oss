import { AppLink } from '@/components/AppLink';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, fmtFull, type LineMeta, type Meta } from '../api';
import { DeviceFlow, fmtCountdown, remainingSeconds, type DeviceState } from '../lib/deviceFlow';
import { CHECK_ICON, CHECK_TEXT, CHECK_TONE, MODE_NAME, MODE_TEXT, SOURCE_NAME, STATE_TEXT, STATE_TONE, fmtExpiry, summarizeChecks, tabOf } from '../lib/integration';
import { Badge, Button, Callout, Card, EmptyState, Field, InfoTip, Input, Modal, OptionCard, PageHeader, PageSkeleton, Select, Table, Tabs, Term } from '../ui';
import { Disclosure } from '@/components/ui/collapsible';
import { ListItemButton } from '@/components/ui/button';
import { RadioGroup } from '@/components/ui/radio-group';
import { CONNECTION_LABEL, type GitHubState } from '../lib/githubConnection';
import { ManageRepositoryAuthorization } from '../components/ManageRepositoryAuthorization';

type GhState = GitHubState;

export default function Integrations({ github: gh, onGitHubRefresh, onRecover, tab, onTab, meta, onChanged, onProject }: { github: GhState | null; onGitHubRefresh: () => Promise<void>; onRecover: () => void; tab?: string; onTab: (t: string) => void; meta: Meta | null; onChanged: () => void; onProject: (id: string) => void }) {
  const [authCfg, setAuthCfg] = useState<{ required: boolean; allowlist: boolean } | null>(null);
  useEffect(() => { api('/api/auth/config').then(setAuthCfg).catch(() => setAuthCfg(null)); }, []);
  const changed = async () => { await onGitHubRefresh(); await onChanged(); };
  const t = tabOf(tab);
  return (
    <div className="space-y-gap" data-testid="integrations">
      <PageHeader title="集成" desc="管理 GitHub 仓库授权，选择要交付的仓库，并查看连接状态" actions={gh && <Badge tone={gh.mode === 'execute' ? 'danger' : gh.mode === 'observe' ? 'info' : 'neutral'} dot>{MODE_NAME[gh.mode]}</Badge>} />
      <Callout tone={authCfg && authCfg.required && !authCfg.allowlist ? 'warn' : 'info'} icon="shield" data-testid="security-notice">{securityNotice(authCfg)}</Callout>
      <Tabs label="集成页签" value={t} onChange={onTab} items={[{ id: 'connect', label: '连接与仓库' }, { id: 'status', label: '连接状态' }]} />
      {!gh ? <PageSkeleton /> : t === 'connect' ? <ConnectTab gh={gh} onRecover={onRecover} meta={meta} onChanged={changed} onProject={onProject} /> : <StatusTab onTab={onTab} />}
    </div>
  );
}

// ───────────────────────── 连接 ─────────────────────────
function ConnectTab({ gh, onRecover, meta, onChanged, onProject }: { gh: GhState; onRecover: () => void; meta: Meta | null; onChanged: () => Promise<void>; onProject: (id: string) => void }) {
  const [switching, setSwitching] = useState(false);
  const showOptions = switching;
  return <div className="space-y-gap">
    {gh.authorizationAvailable ? <ConnectedCard gh={gh} onRecover={onRecover} onChanged={onChanged} onSwitch={() => setSwitching(s => !s)} switching={switching} /> :
      <Card title="GitHub 仓库授权"><p className="mb-3 text-sm text-ink-2">{gh.signedIn ? '更新一次 GitHub 登录授权，就可以选择要交付的仓库。' : '登录 GitHub 后，选择要交付的仓库。也可以使用高级连接方式。'}</p>{gh.signedIn && <Button variant="primary" onClick={onRecover}>更新 GitHub 授权</Button>}<Button className="ml-2" onClick={() => setSwitching(s => !s)}>{switching ? '收起高级连接方式' : '高级连接方式'}</Button></Card>}
    {showOptions && <div className="grid items-start gap-gap lg:grid-cols-2"><DeviceCard gh={gh} onConnected={async () => { setSwitching(false); await onChanged(); }} /><TokenCard gh={gh} onConnected={async () => { setSwitching(false); await onChanged(); }} /></div>}
    {gh.connected && <ModeCard gh={gh} onChanged={onChanged} />}
    <ReposCard gh={gh} onRecover={onRecover} meta={meta} onProject={onProject} onChanged={onChanged} />
  </div>;
}

function ConnectedCard({ gh, onRecover, onChanged, onSwitch, switching }: { gh: GhState; onRecover: () => void; onChanged: () => Promise<void>; onSwitch: () => void; switching: boolean }) {
  const c = gh.credential; const [confirm, setConfirm] = useState(false); const [busy, setBusy] = useState(false); const [err, setErr] = useState('');
  const disconnect = async () => { setBusy(true); try { await api('/api/integrations/github', { method: 'DELETE' }); setConfirm(false); await onChanged(); } catch (e: any) { setErr(e.message); } finally { setBusy(false); } };
  return <Card title="GitHub 仓库授权" actions={<Badge tone={gh.connection.state === 'ready' ? 'success' : 'warn'} dot>{CONNECTION_LABEL[gh.connection.state]}</Badge>}>
    <div className="flex flex-wrap items-center gap-4">
      {c.avatarUrl ? <img src={c.avatarUrl} alt={`${c.login} 的头像`} className="h-12 w-12 border border-primary/60" /> : <div aria-hidden className="grid h-12 w-12 place-items-center border border-line-strong text-lg text-ink-3">GH</div>}
      <div className="min-w-0 flex-1">
        <div className="text-base font-bold text-ink" data-testid="gh-login">{c.login ?? '服务器令牌'}</div>
        <div className="text-xs text-ink-3">来源：{SOURCE_NAME[c.source] ?? c.source} · 有效期：{fmtExpiry(c.expiresAt)}</div>
        {c.permissionsSummary && <div className="mt-1 text-xs text-ink-2">{c.permissionsSummary}</div>}
      </div>
      <div className="flex flex-wrap gap-2"><ManageRepositoryAuthorization gh={gh} />{gh.signedIn && gh.connection.state === 'reauth_required' && <Button size="sm" onClick={onRecover}>更新授权</Button>}<Button size="sm" disabled={!gh.canManage} onClick={onSwitch}>{switching ? '收起高级连接方式' : '高级连接方式'}</Button>
        <Button size="sm" variant="danger" disabled={!c.deletable || !gh.canManage} onClick={() => setConfirm(true)}>断开工作区连接</Button></div>
    </div>
    {c.source === 'env' && <p className="mt-2 text-xs text-ink-3">这个连接由管理员在服务器上设置，这里不能删除；连接你自己的账号即可替换它。<span className="ml-1">（<code>GITHUB_TOKEN</code>）</span></p>}
    {confirm && <Modal title="断开 GitHub 连接" onClose={() => setConfirm(false)} footer={<><Button onClick={() => setConfirm(false)}>取消</Button><Button variant="danger" loading={busy} onClick={disconnect}>确认断开</Button></>}>
      <p className="text-sm text-ink-2">断开后会删除服务器上保存的令牌密文。7x24 将回到{gh.credential.source === 'env' ? '' : '服务器环境变量或'}体验模式，正在执行的任务会因为没有凭据而停下。GitHub 上的授权不会被撤销，如需彻底撤销请到 GitHub 的设置页。</p>{err && <Callout tone="danger">{err}</Callout>}
    </Modal>}
  </Card>;
}

function DeviceCard({ gh, onConnected }: { gh: GhState; onConnected: () => Promise<void> }) {
  const flow = useMemo(() => new DeviceFlow({
    start: () => api('/api/integrations/github/device/start', { method: 'POST', body: {} }),
    poll: (sessionId) => api(`/api/integrations/github/device/poll`, { method: 'POST', body: { sessionId } }),
  }), []);
  const [st, setSt] = useState<DeviceState>(flow.state); const [now, setNow] = useState(Date.now()); const doneRef = useRef(false);
  useEffect(() => { const off = flow.subscribe(setSt); const t = setInterval(() => setNow(Date.now()), 500); return () => { off(); clearInterval(t); flow.stop(); }; }, [flow]);
  useEffect(() => { if (st.phase === 'connected' && !doneRef.current) { doneRef.current = true; setTimeout(() => { onConnected(); }, 900); } }, [st, onConnected]);
  const left = remainingSeconds(st, now);
  return <Card title={<span>方式 A · GitHub 授权登录 <Badge tone="primary">推荐</Badge></span>} subtitle="在 GitHub 页面输入验证码并授权，不需要手动复制令牌">
    {!gh.oauthAvailable && <Callout tone="warn">管理员还没开启授权登录，请先用右侧的「粘贴令牌」。<span className="ml-1 text-xs text-ink-3">（需要服务器配置 <code>GITHUB_OAUTH_CLIENT_ID</code>）</span></Callout>}
    {gh.oauthAvailable && (st.phase === 'idle' || st.phase === 'starting') && <div className="space-y-3">
      <ol className="list-decimal space-y-1 pl-5 text-sm text-ink-2"><li>点击下方按钮获取验证码</li><li>在打开的 GitHub 页面输入验证码并授权</li><li>回到这里，页面会自动检测到连接成功</li></ol>
      <p className="text-xs text-ink-3">授权登录使用 GitHub App：需要先在 GitHub 上<b>安装该 App 并选择可访问的仓库</b>，否则连接后仓库列表会是空的。</p>
      <Button variant="primary" icon="link" loading={st.phase === 'starting'} onClick={() => flow.start()} data-testid="device-start">获取验证码</Button></div>}
    {st.phase === 'waiting' && <div className="space-y-3" data-testid="device-waiting">
      <div className="text-xs text-ink-3">1. 打开 <AppLink className="text-primary-ink underline" href={st.verificationUri} target="_blank" rel="noreferrer">{st.verificationUri.replace(/^https?:\/\//, '')}</AppLink>　2. 输入验证码：</div>
      <div className="cut border border-ink bg-sunken px-4 py-3 text-center font-mono text-3xl tracking-[0.3em] text-ink" data-testid="user-code" aria-label={`验证码 ${st.userCode}`}>{st.userCode}</div>
      <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-ink-3"><span data-testid="countdown">剩余 {fmtCountdown(left)}</span><span>{st.message ?? '等待你在 GitHub 上授权…'}</span></div>
      <div className="flex gap-2"><Button size="sm" onClick={() => window.open(st.verificationUri, '_blank', 'noopener')}>打开 GitHub 页面</Button><Button size="sm" onClick={() => navigator.clipboard?.writeText(st.userCode)}>复制验证码</Button><Button size="sm" variant="ghost" onClick={() => flow.cancel()}>取消</Button></div></div>}
    {st.phase === 'connected' && <Callout tone="success" icon="check"><span data-testid="device-connected">已连接为 <b>{st.login}</b></span></Callout>}
    {(st.phase === 'denied' || st.phase === 'expired') && <div className="space-y-2"><Callout tone="warn">{st.message}</Callout><Button onClick={() => flow.start()}>重新获取验证码</Button></div>}
    {st.phase === 'error' && <div className="space-y-2" data-testid="device-error"><Callout tone="danger">{st.message}</Callout>{st.fallback && <p className="text-xs text-ink-3">你可以先用右侧的「粘贴令牌」连接，之后再回来配置授权登录。</p>}<Button onClick={() => flow.start()}>重试</Button></div>}
  </Card>;
}

function TokenCard({ gh, onConnected }: { gh: GhState; onConnected: () => Promise<void> }) {
  const [token, setToken] = useState(''); const [busy, setBusy] = useState(false); const [err, setErr] = useState(''); const [ok, setOk] = useState<{ login: string; warnings: string[] } | null>(null);
  const save = async () => { setBusy(true); setErr(''); try { const r: any = await api('/api/integrations/github/token', { method: 'POST', body: { token } }); setToken(''); setOk({ login: r.credential.login, warnings: r.warnings ?? [] }); await onConnected(); } catch (e: any) { setErr(e.message); } finally { setBusy(false); } };
  return <Card title="方式 B · 粘贴令牌" subtitle="备选：自己在 GitHub 生成令牌后粘贴。保存前会先向 GitHub 校验">
    <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); if (token.trim()) save(); }}>
      <Field label="GitHub 令牌" hint={<>{gh.minPermissions}</>}>{(id) => <Input id={id} type="password" autoComplete="off" spellCheck={false} placeholder="ghp_… 或 github_pat_…" /* copy-ok */ value={token} onChange={(e) => setToken(e.target.value)} data-testid="token-input" />}</Field>
      {err && <Callout tone="danger">{err}</Callout>}
      {ok && <Callout tone={ok.warnings.length ? 'warn' : 'success'} icon="check">已校验并加密保存，当前账号 <b>{ok.login}</b>。{ok.warnings.map((w) => <div key={w}>⚠ {w}</div>)}</Callout>}
      <Button variant="primary" type="submit" loading={busy} disabled={token.trim().length < 20}>校验并保存</Button>
    </form>
  </Card>;
}

function ModeCard({ gh, onChanged }: { gh: GhState; onChanged: () => Promise<void> }) {
  const [target, setTarget] = useState<'observe' | 'execute' | null>(null); const [busy, setBusy] = useState(false); const [err, setErr] = useState('');
  const apply = async () => { setBusy(true); setErr(''); try { await api('/api/integrations/mode', { method: 'PUT', body: { mode: target, confirm: target === 'execute' } }); setTarget(null); await onChanged(); } catch (e: any) { setErr(e.message); } finally { setBusy(false); } };
  const opts: ('observe' | 'execute')[] = ['observe', 'execute'];
  return <Card title={<Term k="adapterMode">写入模式</Term>} subtitle="决定 7x24 能不能真正修改你的 GitHub 仓库；全局生效，立即保存">
    <RadioGroup className="grid gap-3 md:grid-cols-2" aria-label="写入模式" value={gh.mode} onValueChange={(v) => setTarget(v as (typeof opts)[number])}>
      {opts.map((m) => { const on = gh.mode === m; return <OptionCard key={m} value={m} data-testid={`mode-${m}`} selected={on} tone={m === 'execute' ? 'danger' : 'primary'}>
        <div className="flex items-center gap-2 font-bold">{MODE_NAME[m]}{m === 'observe' && <InfoTip title="只记录模式" text="不会改动你的仓库，只记录本来要做的操作。" />}{on && <Badge tone={m === 'execute' ? 'danger' : 'primary'}>当前</Badge>}</div>
        <p className="mt-1 text-xs text-ink-3">{m === 'observe' ? '只看不改' : '会创建 Issue、PR 和发布'}</p><p className="mt-1 text-xs text-ink-2">{MODE_TEXT[m]}</p></OptionCard>; })}
    </RadioGroup>
    {target && <Modal title={target === 'execute' ? '切换到执行模式？' : '切换到只记录模式？'} onClose={() => setTarget(null)} footer={<><Button onClick={() => setTarget(null)}>取消</Button><Button variant={target === 'execute' ? 'danger' : 'primary'} loading={busy} onClick={apply}>{target === 'execute' ? '确认，开始真实执行' : '确认切换'}</Button></>}>
      {target === 'execute' ? <div className="space-y-2 text-sm text-ink-2"><p>切换后，7x24 会对<b>所有已绑定的仓库</b>真实执行：</p><ul className="list-disc pl-5"><li>为需求创建 Issue、分支和 PR</li><li>触发 GitHub Actions（消耗 CI 分钟）</li><li>CI 通过并通过治理闸门后自动合并，再创建发布</li></ul><p>禁区操作（支付、权限、改 CI 配置等）仍然会被拦截。随时可以切回只记录模式，或点击顶栏的电源图标启用紧急停止。</p></div>
        : <p className="text-sm text-ink-2">切换后不会再向 GitHub 写入任何内容，只读取和记录；已经创建的 PR 和 Issue 保持不变。</p>}
      {err && <Callout tone="danger">{err}</Callout>}
    </Modal>}
  </Card>;
}

// ── 仓库与项目
interface Check { id: string; label: string; status: string; detail: string; fix?: string | null; action?: string | null }
interface Health { ok: boolean; items: Check[]; repo: string; baseBranch: string; checkedAt: string }
function ReposCard({ gh, onRecover, meta, onProject, onChanged }: { gh: GhState; onRecover: () => void; meta: Meta | null; onProject: (id: string) => void; onChanged: () => Promise<void> }) {
  const [picker, setPicker] = useState<{ projectId?: string } | null>(null); const [health, setHealth] = useState<Record<string, Health>>({}); const [busy, setBusy] = useState(''); const [err, setErr] = useState('');
  const projects = meta?.projects.filter(p => p.state !== 'ARCHIVED') ?? [];
  const check = async (id: string) => { setBusy(id); setErr(''); try { const h = await api<Health>(`/api/projects/${id}/connection`, { method: 'POST', body: {} }); setHealth(x => ({ ...x, [id]: h })); } catch (e: any) { setErr(e.message); } finally { setBusy(''); } };
  return <Card title="仓库与项目" subtitle="一个项目绑定一个仓库；先选仓库，再做体检确认它能被 7x24 使用" actions={<Button size="sm" variant="primary" icon="plus" disabled={!gh.connected || !gh.canManage} onClick={() => setPicker({})}>选择仓库</Button>}>
    {err && <Callout tone="danger" onClose={() => setErr('')}>{err}</Callout>}
    {!gh.connected && <Callout tone="warn">{gh.connection.message}{gh.signedIn && <Button size="sm" className="ml-2" onClick={onRecover}>更新授权</Button>}</Callout>}
    {projects.length === 0 ? <EmptyState title="还没有项目" hint="项目是 7x24 交付的最小单位，每个项目绑定一个 GitHub 仓库。" action={gh.connected && gh.canManage ? <Button variant="primary" onClick={() => setPicker({})}>选择仓库并创建项目</Button> : undefined} /> :
      <div className="space-y-3">{projects.map(p => { const h = health[p.id]; const sum = h ? summarizeChecks(h.items) : null; return <div key={p.id} className="border border-line-strong/50 border-l-2 border-l-primary bg-raised/60 p-3" data-testid={`project-${p.id}`}>
        <div className="flex flex-wrap items-center justify-between gap-2"><div><ListItemButton className="font-semibold text-ink hover:text-primary-ink" onClick={() => onProject(p.id)}>{p.name}</ListItemButton><div className="font-mono text-xs text-ink-3">{p.config.repo} · {p.config.baseBranch} · {p.config.workflow}</div></div>
          <div className="flex gap-2"><Button size="sm" onClick={() => setPicker({ projectId: p.id })} disabled={!gh.connected || !gh.canManage}>更换仓库</Button><Button size="sm" variant="primary" loading={busy === p.id} disabled={!gh.connected} onClick={() => check(p.id)} data-testid={`check-${p.id}`}>仓库体检</Button></div></div>
        {h && sum && <HealthPanel h={h} sum={sum} projectId={p.id} onRecheck={() => check(p.id)} onChanged={onChanged} />}
      </div>; })}</div>}
    {picker && <RepoPicker meta={meta} gh={gh} onRecover={onRecover} initialProject={picker.projectId} onClose={() => setPicker(null)} onBound={async (id) => { setPicker(null); await onChanged(); await check(id); }} />}
  </Card>;
}

function HealthPanel({ h, sum, projectId, onRecheck, onChanged }: { h: Health; sum: Record<string, number>; projectId: string; onRecheck: () => void; onChanged: () => Promise<void> }) {
  const [pr, setPr] = useState<any>(null); const [busy, setBusy] = useState(false); const [err, setErr] = useState('');
  const createPr = async (repair = false) => { setBusy(true); setErr(''); try { setPr(await api(`/api/projects/${projectId}/ci-template-pr`, { method: 'POST', body: repair ? { repair: true } : {} })); await onChanged(); } catch (e: any) { setErr(e.message); } finally { setBusy(false); } };
  return <div className="mt-3 space-y-2" data-testid="health-panel">
    <div className="flex flex-wrap items-center gap-2 text-xs"><Badge tone={h.ok ? 'success' : 'danger'} dot>{h.ok ? '可以使用' : '有未通过项'}</Badge><span className="text-ink-3">通过 {sum.ok} · 未通过 {sum.fail} · 建议处理 {sum.warn} · 无法确认 {sum.unknown} · 只读检查，没有修改任何内容</span></div>
    <ul className="divide-y divide-line border border-line">
      {h.items.map((i) => <li key={i.id} className="flex gap-3 px-3 py-2 text-sm" data-testid={`check-${i.id}`}>
        <Badge tone={CHECK_TONE[i.status]} title={CHECK_TEXT[i.status]}>{CHECK_ICON[i.status]} {CHECK_TEXT[i.status]}</Badge>
        <div className="min-w-0 flex-1"><div className="font-medium text-ink">{i.id === 'ci_dispatch' ? <Term k="workflowDispatch" bare>{i.label}</Term> : i.id === 'protection' ? <Term k="branchProtection" bare>{i.label}</Term> : i.label}</div><div className="text-xs text-ink-2">{i.detail}</div>
          {i.fix && i.status !== 'ok' && <div className="mt-0.5 text-xs text-warn-ink">怎么修：{i.fix}</div>}
          {i.action === 'create_ci_pr' && <div className="mt-2"><Button size="sm" variant="primary" loading={busy} onClick={() => createPr()} data-testid="ci-pr">用 PR 补充 CI 模板</Button></div>}
          {i.action === 'repair_ci_pr' && <div className="mt-2"><Button size="sm" variant="primary" loading={busy} onClick={() => createPr(true)} data-testid="ci-pr-repair">用 PR 更新 CI 模板</Button></div>}</div></li>)}
    </ul>
    {err && <Callout tone="danger">{err}</Callout>}
    {pr && <Callout tone={pr.mode === 'created' ? 'success' : 'info'} icon={pr.mode === 'created' ? 'check' : 'alert'}><div>{pr.message}</div>
      {pr.prUrl && <AppLink className="text-primary-ink underline" href={pr.prUrl} target="_blank" rel="noreferrer">打开 PR #{pr.prNumber}</AppLink>}
      {pr.mode === 'preview' && <Disclosure className="mt-1" summaryClassName="cursor-pointer text-xs" summary={<>查看将要提交的 {pr.path}</>}><pre className="mt-1 max-h-48 overflow-auto bg-sunken p-2 font-mono text-xs">{pr.content}</pre><ol className="mt-1 list-decimal pl-5 text-xs">{pr.steps.map((s: string) => <li key={s}>{s}</li>)}</ol></Disclosure>}</Callout>}
    <div className="flex items-center gap-3 text-xs text-ink-3"><span>检查于 {fmtFull(h.checkedAt)}（UTC）</span><Button size="sm" variant="ghost" icon="refresh" onClick={onRecheck}>重新体检</Button></div>
  </div>;
}

interface Repo { fullName: string; installationId?: number | null; private: boolean; defaultBranch: string; description: string; pushedAt?: string; canPush: boolean; boundProjectId?: string | null }
function RepoPicker({ meta, gh, onRecover, initialProject, onClose, onBound }: { meta: Meta | null; gh: GhState; onRecover: () => void; initialProject?: string; onClose: () => void; onBound: (projectId: string) => Promise<void> }) {
  const [q, setQ] = useState(''); const [page, setPage] = useState(1); const [data, setData] = useState<any>(null); const [loading, setLoading] = useState(true); const [err, setErr] = useState('');
  const [sel, setSel] = useState<Repo | null>(null); const projects = meta?.projects.filter(p => p.state !== 'ARCHIVED') ?? []; const lines = (meta?.lines ?? []) as LineMeta[];
  const [target, setTarget] = useState(initialProject ?? (projects[0]?.id ?? 'new')); const [lineId, setLineId] = useState(lines[0]?.id ?? ''); const [name, setName] = useState(''); const [branch, setBranch] = useState(''); const [busy, setBusy] = useState(false); const [nonce, setNonce] = useState(0);
  const ready = gh.connection.state === 'ready';
  useEffect(() => { let active = true; setLoading(true); const t = setTimeout(() => { api(`/api/integrations/github/repos?q=${encodeURIComponent(q)}&page=${page}&size=8`).then(d => { if (active) { setData(d); setErr(''); } }).catch(e => { if (active) { setData(null); setErr(e.message); } }).finally(() => { if (active) setLoading(false); }); }, q ? 300 : 0); return () => { active = false; clearTimeout(t); }; }, [q, page, nonce, ready]);
  useEffect(() => setPage(1), [q]);
  const pick = (r: Repo) => { setSel(r); setBranch(r.defaultBranch); if (!name) setName(r.fullName.split('/')[1]); };
  const bind = async () => { if (!sel) return; setBusy(true); setErr(''); try {
    if (sel.installationId) await api('/api/integrations/github/repositories/bind', { method: 'POST', body: { fullName: sel.fullName, installationId: sel.installationId } });
    let id = target;
    if (target === 'new') { const p: any = await api(`/api/product-lines/${lineId}/projects`, { method: 'POST', body: { name: name.trim() || sel.fullName.split('/')[1], config: { repo: sel.fullName, baseBranch: branch || sel.defaultBranch } } }); id = p.id; await api(`/api/projects/${id}/detect?apply=true`, { method: 'POST' }).catch(() => undefined); }
    else await api(`/api/projects/${target}`, { method: 'PUT', body: { config: { repo: sel.fullName, baseBranch: branch || sel.defaultBranch } } });
    await onBound(id); } catch (e: any) { setErr(e.message); } finally { setBusy(false); } };
  const total = data?.total ?? 0; const pages = Math.max(1, Math.ceil(total / 8));
  return <Modal title="选择 GitHub 仓库" width={760} onClose={onClose} footer={sel ? <><Button onClick={() => setSel(null)}>返回列表</Button><Button variant="primary" loading={busy} disabled={target === 'new' && !lineId} onClick={bind} data-testid="bind-confirm">{target === 'new' ? '创建项目并绑定' : '绑定到该项目'}</Button></> : <Button onClick={onClose}>关闭</Button>}>
    {!sel ? <div className="space-y-3">
      <Input aria-label="搜索仓库" data-autofocus placeholder="搜索仓库，例如 组织名/仓库名" value={q} onChange={e => setQ(e.target.value)} />
      {err && <Callout tone="danger">{err}{gh.connection.state === 'reauth_required' && <Button size="sm" className="ml-2" onClick={onRecover}>更新授权并继续</Button>}<Button size="sm" className="ml-2" onClick={() => setNonce(n => n + 1)}>重试</Button></Callout>}
      {data?.hint && <Callout tone="warn" icon="plug"><div>{data.hint}</div><div className="mt-2 flex gap-2"><ManageRepositoryAuthorization gh={gh} /><Button size="sm" icon="refresh" loading={loading} onClick={() => setNonce(n => n + 1)} data-testid="repos-refresh">重新检查</Button></div></Callout>}
      {loading ? <div role="status" className="py-6 text-center text-sm text-ink-3">正在从 GitHub 读取仓库…</div> : err ? null : (data?.items ?? []).length === 0 ? <EmptyState title={q ? `没有匹配「${q}」的仓库` : '选择可访问的仓库'} hint={q ? '换个关键词试试，或清空搜索。' : '前往 GitHub 选择允许 7x24 访问的仓库，完成后返回这里继续。'} action={<ManageRepositoryAuthorization gh={gh} />} /> :
        <ul className="divide-y divide-line border border-line">{(data.items as Repo[]).map(r => <li key={r.fullName}><ListItemButton onClick={() => pick(r)} className="flex w-full items-center justify-between gap-3 px-3 py-2 text-left hover:bg-primary-soft/60" data-testid="repo-item">
          <span className="min-w-0"><span className="font-mono text-sm text-ink">{r.fullName}</span>{r.description && <span className="block truncate text-xs text-ink-3">{r.description}</span>}</span>
          <span className="flex shrink-0 gap-1"><Badge tone={r.private ? 'warn' : 'neutral'}>{r.private ? '私有' : '公开'}</Badge>{!r.canPush && <Badge tone="danger">只读</Badge>}{r.boundProjectId && <Badge tone="info">已绑定</Badge>}</span></ListItemButton></li>)}</ul>}
      <div className="flex items-center justify-between text-xs text-ink-3"><span>共 {total} 个仓库</span><div className="flex items-center gap-2"><Button size="sm" disabled={page <= 1} onClick={() => setPage(p => p - 1)}>上一页</Button><span>{page} / {pages}</span><Button size="sm" disabled={page >= pages} onClick={() => setPage(p => p + 1)}>下一页</Button></div></div>
    </div> : <div className="space-y-3">
      <div className="border border-primary/60 bg-primary-soft px-3 py-2 font-mono text-sm">{sel.fullName}{!sel.canPush && <span className="ml-2 text-danger-ink">（当前令牌对它只有读权限，7x24 无法写入）</span>}</div>
      <Field label="绑定到">{id => <Select id={id} value={target} onChange={e => setTarget(e.target.value)}>{projects.map(p => <option key={p.id} value={p.id}>已有项目：{p.name}（{p.config.repo}）</option>)}<option value="new">新建项目…</option></Select>}</Field>
      {target === 'new' && <div className="grid gap-3 md:grid-cols-2"><Field label="所属产品线">{id => <Select id={id} value={lineId} onChange={e => setLineId(e.target.value)}>{lines.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}</Select>}</Field><Field label="项目名称">{id => <Input id={id} value={name} onChange={e => setName(e.target.value)} />}</Field></div>}
      <Field label="目标分支" hint={`仓库默认分支是 ${sel.defaultBranch}`}>{id => <Input id={id} value={branch} onChange={e => setBranch(e.target.value)} />}</Field>
      <p className="text-xs text-ink-3">一个未归档项目只能绑定一个仓库；如果项目还有未结束的任务，系统会拒绝切换仓库。绑定后会自动做一次体检。</p>
      {err && <Callout tone="danger">{err}{gh.connection.state === 'reauth_required' && <Button size="sm" className="ml-2" onClick={onRecover}>更新授权并继续</Button>}</Callout>}
    </div>}
  </Modal>;
}

// ───────────────────────── 连接状态 ─────────────────────────
function StatusTab({ onTab }: { onTab: (t: string) => void }) {
  const [s, setS] = useState<any>(null); const [error, setError] = useState('');
  const load = useCallback(() => api('/api/integrations/status').then(setS).catch(e => setError(e.message)), []);
  useEffect(() => { load(); const t = setInterval(load, 10000); return () => clearInterval(t); }, [load]);
  if (!s) return error ? <Callout tone="danger">{error}</Callout> : <PageSkeleton />;
  const d = s.diagnostics; const goto = (to: string) => onTab(to.split('/')[1] === 'status' ? 'status' : 'connect');
  return <div className="space-y-gap" data-testid="status-tab">
    {s.alerts.map((a: string) => <Callout key={a} tone="warn">{a}</Callout>)}
    <div className="grid gap-gap md:grid-cols-3">{s.cards.map((c: any) => <Card key={c.id} data-testid={`card-${c.id}`} title={c.title} actions={<Badge tone={STATE_TONE[c.state]} dot>{STATE_TEXT[c.state]}</Badge>}>
      <p className="text-sm text-ink-2">{c.text}</p>{c.fixLabel && <Button size="sm" variant="primary" className="mt-3" onClick={() => goto(c.fixTo)}>{c.fixLabel}</Button>}</Card>)}</div>
    <Card title="项目连接" flush>
      <Table caption="各项目的连接状态" rows={s.projects} rowKey={(r: any) => r.id} empty={<EmptyState title="还没有项目" hint="到「连接与仓库」页选择一个仓库。" action={<Button size="sm" variant="primary" onClick={() => onTab('connect')}>去选择仓库</Button>} />} columns={[
        { key: 'r', header: '仓库', render: (r: any) => <div><div className="font-mono text-sm">{r.repo}</div><div className="text-xs text-ink-3">{r.name}</div></div> },
        { key: 'b', header: '分支', render: (r: any) => <span className="font-mono text-xs">{r.branch}</span> },
        { key: 'c', header: '连接状态', render: (r: any) => <Badge tone={STATE_TONE[r.connection] ?? 'neutral'} dot>{STATE_TEXT[r.connection]}</Badge> },
        { key: 'ci', header: 'CI 文件', render: (r: any) => r.ci === 'exists' ? <Badge tone="success">有（{r.workflow}）</Badge> : r.ci === 'missing' ? <Badge tone="danger">缺少 {r.workflow}</Badge> : <span className="text-xs text-ink-3">未检查</span> },
        { key: 's', header: '最近同步', render: (r: any) => <span className="tabular text-xs text-ink-2">{r.lastSync ? fmtFull(r.lastSync).slice(5) : '暂无'}</span> },
      ]} />
    </Card>
    <Disclosure className="cut border border-line-strong/70 bg-surface/90" data-testid="diagnostics" summaryClassName="cursor-pointer px-card py-3 text-sm font-bold text-ink" summary={<>高级诊断 <span className="text-xs font-normal text-ink-3">（限流、重试、调用次数；一般不需要看）</span></>}>
      <div className="grid gap-3 border-t border-line p-card md:grid-cols-2 lg:grid-cols-3">
        <Metric k="rateLimit" label="请求额度" value={d.remaining != null && d.limit != null ? `${d.remaining} / ${d.limit}` : '暂无数据'} hint={d.limit ? `GitHub 每小时允许 ${d.limit} 次请求，当前剩余 ${d.remaining ?? '-'} 次${d.resetAt ? `，${fmtFull(d.resetAt).slice(11, 16)}（UTC）重置` : ''}` : 'GitHub 每小时允许约 5000 次请求；发起过真实调用后这里才有数字'} />
        <Metric k="retries" label="自动重试" value={String(d.retries)} hint="GitHub 临时出错或限流时自动重试的累计次数；偶尔有是正常的" />
        <Metric k="backoff" label="限流等待" value={`${(d.throttledMs / 1000).toFixed(1)} 秒`} hint="为了不被 GitHub 限流，系统主动等待的总时间" />
        <Metric k="idempotent" label="调用总数" value={String(d.calls)} hint="包括只记录模式下没有真正发出的调用" />
        <Metric k="idempotent" label="失败次数" value={String(d.failures)} hint="真实发出但失败的调用；失败原因见下方记录" />
        <Metric k="dryRun" label="仅记录未发出" value={String(d.observedOnly)} hint="只记录模式下，本来会写入 GitHub 的操作次数" />
      </div>
      <Disclosure className="border-t border-line" summaryClassName="cursor-pointer px-card py-2 text-xs text-ink-2" summary={<>最近调用记录（失败的排在最前，共 {s.recentCalls.length} 条）</>}>
        <ul className="divide-y divide-line">{s.recentCalls.length === 0 ? <li className="px-card py-4 text-xs text-ink-3">还没有调用记录</li> : s.recentCalls.map((c: any) => <li key={c.id} className="flex items-center gap-3 px-card py-1.5 text-xs"><Badge tone={c.observed ? 'info' : c.ok ? 'success' : 'danger'}>{c.observed ? '仅记录' : c.ok ? '成功' : '失败'}</Badge><span className="flex-1 text-ink">{c.text}</span><span className="tabular text-ink-3">{fmtFull(c.at).slice(5)}</span></li>)}</ul></Disclosure>
    </Disclosure>
  </div>;
}
function Metric({ k, label, value, hint }: { k: string; label: string; value: string; hint: string }) {
  return <div className="border border-line p-3"><div className="label-caps flex items-center gap-1 text-caption text-ink-3"><Term k={k} bare>{label}</Term></div><div className="mt-1 text-lg font-bold tabular text-ink">{value}</div><p className="mt-1 text-xs text-ink-3">{hint}</p></div>;
}

/** 与后端 /api/auth/config 一致的安全提示 */
export function securityNotice(c: { required: boolean; allowlist: boolean } | null): string {
  const tok = 'GitHub 令牌在服务器上加密保存，页面和接口都不会显示原文。';
  if (!c) return tok;
  if (!c.required) return '登录鉴权已关闭，任何能访问本服务的人都能操作，请只在本机或内网使用。' + tok;
  return (c.allowlist ? '已启用 GitHub 登录，只有名单里的账号可以访问。' : '已启用 GitHub 登录，但还没设置允许登录的账号名单，现在任何 GitHub 用户都能登录，建议让管理员设置名单。') + tok;
}
