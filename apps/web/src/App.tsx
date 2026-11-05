'use client';

import { AppLink } from '@/components/AppLink';
import { DevModeChoice, DEV_MODE_ZH, type DevModeValue } from './components/DevModeChoice';


import { createContext, lazy, Suspense, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { presetOptions, type Preset } from './lib/pipeline';
import { PulseStore, connectFlowEvents, type LiveStatus } from './pulse';
import Login, { type AuthConfig } from './views/Login';
import { eventsUrl, getWorkspace, onUnauthorized, parseAuthCallback, setToken, setWorkspace } from './lib/auth';
import { rememberInvite, roleLabel, takePendingInvite, type MyWorkspaces, type WorkspaceInfo } from './lib/workspace';
import { api, errorDetail, fmtTime, type BoardResponse, type Meta } from './api';
import { useRoute } from './router';
import { useRouter } from 'next/navigation';
import { appPath, navigationTarget } from './lib/navigation';
import { PageBoundary } from './ui/PageBoundary';
import Overview from './views/Overview';
import Tasks, { NodeDrawer } from './views/Tasks';
import TaskDetail from './views/TaskDetail';
import Governance from './views/Governance';
import Budget from './views/Budget';
import Audit from './views/Audit';
import Integrations from './views/Integrations';
import Catalog from './views/Catalog';
import Project from './views/Project';
import Models from './views/Models';
import WorkspacePage, { AcceptInvite } from './views/Workspace';
import Platform from './views/Platform';
import Session from './views/Session';
import Chats from './views/Chats';
import type { ChatSession } from './lib/chat';
import ObjectView from './views/ObjectView';
import Inbox, { inboxGroups } from './views/Inbox';
import { WorkspaceTree } from './components/WorkspaceTree';
import { UserMenu } from './components/UserMenu';
import { NodeIcon } from './ui/NodeIcon';
import { PRIMARY, contextLine, isNewSessionKey, newSessionHref, objRoute } from './lib/nav';
import { useWorkspaceData } from './lib/workspaceData';
import { Badge, Button, Callout, Field, Icon, IconButton, Input, Modal, PageSkeleton, Select, Textarea, cx, type IconName, useConfirm } from './ui';
import type { NotificationInbox } from './lib/notifications';
import { Tip } from './ui/InfoTip';
import { killModeName, scopeName } from './copy/terms';
import { Disclosure } from '@/components/ui/collapsible';
import { useGitHubConnection } from './lib/useGitHubConnection';
import type { GitHubState } from './lib/githubConnection';
import { GitHubConnectionNotice, GitHubRecovery, GitHubStatusBadge } from './components/GitHubConnectionNotice';

const NotificationSettings = lazy(() => import('./views/NotificationSettings'));
const Notifications = lazy(() => import('./views/Notifications'));


function Mark() {
  return <svg viewBox="0 0 22 22" aria-hidden="true" className="h-[22px] w-[22px] shrink-0">
    <path fill="#050505" d="M0 0Q5.5 4.6 7 2.6Q11-1.6 15 2.6Q16.500 4.6 22 0V22Q16.500 17.400 15 19.400Q11 23.600 7 19.400Q5.500 17.400 0 22Z" />
    <path fill="#ececea" d="M5.800 6.600Q6.500 10.300 10.200 11Q6.500 11.700 5.800 15.400Q5.100 11.700 1.400 11Q5.100 10.300 5.800 6.600ZM16.200 6.600Q16.900 10.300 20.600 11Q16.900 11.700 16.200 15.400Q15.500 11.700 11.800 11Q15.500 10.300 16.200 6.600Z" />
  </svg>;
}

export interface AuthUser { id: string; login: string; name?: string | null; avatarUrl?: string | null }

const ConsoleScreens = createContext<Record<string, ReactNode> | null>(null);

export default function Root({ children }: { children: ReactNode }) {
  const [config, setConfig] = useState<AuthConfig | null>(null); const [user, setUser] = useState<AuthUser | null | undefined>(undefined); const [err, setErr] = useState('');
  const check = useCallback(async () => {
    if (new URLSearchParams(location.search).get('github_setup') === '1' && window.opener) { window.opener.postMessage({ type: 'x7-github-reconnected' }, location.origin); window.close(); return; }
    rememberInvite(location.pathname);
    const cb = parseAuthCallback(location.hash);
    if (cb) {
      if (cb.token) setToken(cb.token);
      if (new URLSearchParams(location.search).get('github_reconnect') === '1' && window.opener) {
        window.opener.postMessage(cb.token ? { type: 'x7-github-reconnected' } : { type: 'x7-github-reconnect-error', message: cb.message || cb.error }, location.origin);
        window.close(); return;
      }
      if (cb.error) { setErr(cb.message || cb.error); }
      history.replaceState(null, '', location.pathname + location.search);
    }
    try { setConfig(await api<AuthConfig>('/api/auth/config')); } catch (e: any) { setErr('无法连接后端：' + e.message); }
    try { setUser(await api<AuthUser>('/api/auth/me')); } catch { setUser(null); }
  }, []);
  useEffect(() => { check(); }, [check]);
  useEffect(() => onUnauthorized(() => { setToken(null); setUser(null); }), []);
  const [mine, setMine] = useState<MyWorkspaces | null>(null);
  useEffect(() => {
    if (user === undefined) return;
    (async () => {
      try { setMine(await api<MyWorkspaces>('/api/me/workspaces')); }
      catch { if (getWorkspace()) { setWorkspace(null); try { setMine(await api<MyWorkspaces>('/api/me/workspaces')); } catch { /* ignore */ } } }
    })();
  }, [user]);
  const logout = async () => { try { await api('/api/auth/logout', { method: 'POST' }); } catch { /* ignore */ } setToken(null); setUser(null); };
  if (user === undefined) return <div className="grid min-h-screen place-items-center text-sm text-ink-3">正在验证身份…</div>;
  if (!user && (config?.required ?? true)) return <Login config={config} initialError={err} onLoggedIn={check} />;
  return <App key={(mine?.current as WorkspaceInfo | undefined)?.id ?? '-'} user={user} onLogout={logout} mine={mine}>{children}</App>;
}

function App({ children, user, onLogout, mine }: { children: ReactNode; user: AuthUser | null; onLogout: () => void; mine: MyWorkspaces | null }) {
  const current = mine?.current && 'id' in mine.current ? (mine.current as WorkspaceInfo) : null;
  const { github, refreshGitHub } = useGitHubConnection(current?.id);
  const [githubRecovery, setGitHubRecovery] = useState(false);
  const switchWs = (id: string) => { setWorkspace(id); location.assign(appPath('/overview')); };
  const router = useRouter();
  const [route, go] = useRoute(); const page = route[0];
  const wsData = useWorkspaceData(!!user, current?.id); const obj = objRoute(route); const activeObj = obj ? { kind: obj.kind, id: obj.id } : null;
  const inboxCount = useMemo(() => { const g = inboxGroups(wsData.tasks ?? [], [], wsData); return g.blocked.length + g.waiting.length + g.overTasks.length; }, [wsData.tasks, wsData.budget]);
  useEffect(() => { const t = takePendingInvite(); if (t && page !== 'invite') go(`invite/${encodeURIComponent(t)}`); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const [meta, setMeta] = useState<Meta | null>(null); const [board, setBoard] = useState<BoardResponse | null>(null);
  const [line, setLine] = useState(''); const [project, setProject] = useState(''); const [from, setFrom] = useState(''); const [to, setTo] = useState('');
  const [node, setNode] = useState<string | null>(null);
  const [busy, setBusy] = useState(''); const [revision, setRevision] = useState(0);
  const [showCreate, setShowCreate] = useState(false); const [showKill, setShowKill] = useState(false); const [error, setError] = useState(''); const [errDetail, setErrDetail] = useState<string | undefined>();
  const [unread, setUnread] = useState(0);
  // 新对话：记住当前产品线作为上下文；Ctrl/Cmd+K 随时新开；没绑定任务的会话放到侧边栏底部「其他对话」
  const ctxLine = useMemo(() => contextLine(activeObj, meta?.projects ?? [], wsData.tasks), [activeObj?.kind, activeObj?.id, meta?.projects, wsData.tasks]); // eslint-disable-line react-hooks/exhaustive-deps
  const [lastLine, setLastLine] = useState<string | null>(() => { try { return localStorage.getItem('x7_ctx_line'); } catch { return null; } });
  useEffect(() => { if (!obj) return; setLastLine(ctxLine); try { ctxLine ? localStorage.setItem('x7_ctx_line', ctxLine) : localStorage.removeItem('x7_ctx_line'); } catch { /* ignore */ } }, [ctxLine, obj?.kind, obj?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const newHref = newSessionHref(lastLine);
  useEffect(() => { const f = (e: KeyboardEvent) => { if (!isNewSessionKey(e)) return; e.preventDefault(); go(newHref); setMobileNavOpen(false); }; window.addEventListener('keydown', f); return () => window.removeEventListener('keydown', f); }, [newHref, go]);
  const [sessions, setSessions] = useState<ChatSession[] | null>(null);
  const loadSessions = useCallback(() => { api<ChatSession[]>('/api/chats').then(setSessions).catch(() => {}); }, [user, current?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { loadSessions(); }, [loadSessions, page, route[1]]);
  const replace = useCallback((to: string) => router.replace(navigationTarget(to)), [router]);
  useEffect(() => {
    if (!user) return;
    let active = true;
    const load = () => api<NotificationInbox>('/api/me/notifications').then(d => { if (active) setUnread(d.unread); }).catch(() => {});
    load(); const timer = setInterval(load, 15000); window.addEventListener('x7-notifications-changed', load);
    return () => { active = false; clearInterval(timer); window.removeEventListener('x7-notifications-changed', load); };
  }, [user?.id, current?.id]);

  // 响应式三档侧边栏：'auto' 跟随断点（<md 隐藏抽屉，md~<xl 64px 仅图标轨道，>=xl 232px 完整展开），支持手动切换 'rail' / 'expanded'
  const [sidebarPref, setSidebarPref] = useState<'auto' | 'rail' | 'expanded'>(() => (localStorage.getItem('x7_sidebar') as any) || 'auto');
  const [isXl, setIsXl] = useState(() => typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia('(min-width: 1280px)').matches : true);
  const [mobileNavOpen, setMobileNavOpen] = useState(false); const [railTree, setRailTree] = useState(false);
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const mq = window.matchMedia('(min-width: 1280px)');
    const onChange = (e: MediaQueryListEvent) => setIsXl(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);
  const isRail = sidebarPref === 'rail' || (sidebarPref === 'auto' && !isXl);
  const toggleRail = () => {
    const next = isRail ? 'expanded' : 'rail';
    setSidebarPref(next);
    localStorage.setItem('x7_sidebar', next);
  };

  const store = useMemo(() => new PulseStore(), [line, project]);
  const [live, setLive] = useState<LiveStatus>('connecting');
  const refreshRef = useRef<() => void>(() => {});
  useEffect(() => {
    let t: ReturnType<typeof setTimeout> | null = null;
    const kick = () => { if (!t) t = setTimeout(() => { t = null; refreshRef.current(); }, 800); };
    const off = connectFlowEvents(() => eventsUrl(), (e) => { if ((line && e.productLine !== line) || (project && e.projectId !== project)) return; store.push(e); kick(); }, setLive, undefined, (b) => { if ((line && b.productLine && b.productLine !== line) || (project && b.projectId && b.projectId !== project)) return; kick(); });
    return () => { off(); if (t) clearTimeout(t); };
  }, [store, line, project]);

  const q = new URLSearchParams({ ...(line ? { productLine: line } : {}), ...(project ? { projectId: project } : {}), ...(from && to ? { from, to } : {}) }).toString();
  const scopeValid = !!meta && (!line || meta.lines.some(l => l.id === line)) && (!project || meta.projects.some(p => p.id === project && (!line || p.productLineId === line)));
  useEffect(() => {
    if (!meta || scopeValid) return;
    if (line && !meta.lines.some(l => l.id === line)) setLine('');
    setProject(''); setNode(null); setError('');
  }, [meta, scopeValid, line]);
  const loadMeta = useCallback(async () => { const m = await api<Meta>('/api/meta'); setMeta(m); setFrom((f) => f || m.range.from); setTo((t) => t || m.range.to); }, []);
  const githubRecovered = useCallback(async () => { await Promise.all([refreshGitHub(true), loadMeta()]); }, [refreshGitHub, loadMeta]);
  const closeGitHubRecovery = useCallback(() => setGitHubRecovery(false), []);
  const recoverGitHub = () => { if (github?.signedIn) setGitHubRecovery(true); else go('integrations/connect'); };
  const boardRequest = useRef(0);
  const loadBoard = useCallback(async () => {
    const request = ++boardRequest.current; if (!scopeValid) return;
    try { const result = await api<BoardResponse>(`/api/board?${q}`); if (request === boardRequest.current) { setBoard(result); setError(''); } }
    catch (e: any) { if (request === boardRequest.current) { setError(String(e.message)); setErrDetail(errorDetail(e)); } }
  }, [q, scopeValid]);
  useEffect(() => { loadMeta().catch((e) => setError(String(e.message))); }, [loadMeta]);
  useEffect(() => { if (from && to) loadBoard(); }, [loadBoard, from, to]);
  const refresh = useCallback(async () => { await Promise.all([loadMeta(), loadBoard()]); }, [loadMeta, loadBoard]);
  refreshRef.current = () => { if (from && to) loadBoard(); };
  useEffect(() => {
    if (page !== 'overview') return;
    refreshRef.current();
    const tick = () => { if (document.visibilityState === 'visible') refreshRef.current(); };
    const timer = setInterval(tick, 15000); window.addEventListener('focus', tick); document.addEventListener('visibilitychange', tick);
    return () => { clearInterval(timer); window.removeEventListener('focus', tick); document.removeEventListener('visibilitychange', tick); };
  }, [page]);
  const act = async (label: string, fn: () => Promise<unknown>) => { setBusy(label); try { await fn(); await refresh(); } catch (e: any) { setError(String(e.message)); setErrDetail(errorDetail(e)); } finally { setBusy(''); } };

  const killed = meta?.killSwitches ?? [];
  const nodeLabel = node ? meta?.nodes.find((n) => n.id === node)?.label ?? node : '';
  const canWrite = current?.role !== 'viewer';
  const pageName = obj ? ({ line: '产品线', project: '项目', task: '任务' } as const)[obj.kind] : page === 'admin' ? `工作区管理 · ${ADMIN_LABEL[route[1]] ?? ''}` : page === 'me' ? (route[1] === 'notifications' ? '通知中心' : '个人通知设置') : page === 'inbox' ? '待我处理' : page === 'tasks' ? '全部任务' : page === 'new' ? '新对话' : page === 's' ? '对话' : page === 'chats' ? '全部对话' : page === 'overview' ? '总览' : page === 'platform' ? '平台管理' : '工作区';
  const scopeFilters = <>
    <Select aria-label="产品线" value={line} onChange={(e) => { setLine(e.target.value); setProject(''); setNode(null); }} className="w-40 sm:w-44"><option value="">全部产品线</option>{meta?.lines.map((l) => <option key={l.id} value={l.id}>{l.name}（{l.autonomy}）</option>)}</Select>
    <Select aria-label="项目" value={project} onChange={e => { setProject(e.target.value); setNode(null); }} className="w-40 sm:w-44"><option value="">全部项目</option>{meta?.projects.filter(p => !line || p.productLineId === line).map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</Select>
    <div className="flex flex-wrap items-center gap-1"><Input aria-label="开始日期" type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="w-36" /><span className="text-ink-3">–</span><Input aria-label="结束日期" type="date" value={to} onChange={(e) => setTo(e.target.value)} className="w-36" /></div>
  </>;

  const navLink = (n: typeof PRIMARY[number], compactRail: boolean) => {
    const active = page === n.id; const badge = n.id === 'inbox' ? inboxCount : 0;
    const el = <AppLink key={n.id} href={`/${n.id}`} onClick={() => setMobileNavOpen(false)} aria-current={active ? 'page' : undefined} aria-label={compactRail ? n.label : undefined} data-testid={`nav-${n.id.replace('/', '-')}`}
      className={cx('relative flex items-center py-2 text-sm font-medium', compactRail ? 'justify-center px-0' : 'gap-2.5 px-3.5', active ? 'bg-primary text-primary-fg' : 'text-ink-2 hover:bg-sunken hover:text-ink')}>
      <NodeIcon name={n.icon} size={17} />{!compactRail && <span className="truncate">{n.label}</span>}
      {badge > 0 && <span className={cx('rounded-full border-[1.5px] border-current px-1.5 font-mono text-[10px] leading-4', compactRail ? 'absolute right-1 top-0.5 bg-danger text-white' : 'ml-auto')}>{badge > 99 ? '99+' : badge}</span>}
    </AppLink>;
    return compactRail ? <Tip key={n.id} content={n.label} placement="right">{el}</Tip> : el;
  };
  const newBtn = (compactRail: boolean) => { const el = <AppLink key="new" href={newHref} onClick={() => setMobileNavOpen(false)} aria-current={page === 'new' ? 'page' : undefined} aria-label={compactRail ? '新对话（⌘K / ⌃K）' : undefined} data-testid="nav-new"
      className={cx('mx-2 mb-1 flex items-center border-2 border-ink py-1.5 text-sm font-bold', compactRail ? 'justify-center px-0' : 'gap-2 px-2.5', page === 'new' ? 'bg-primary text-primary-fg' : 'bg-raised text-ink hover:bg-sunken')}>
      <span aria-hidden className="text-base leading-none">＋</span>{!compactRail && <><span>新对话</span><kbd className="ml-auto font-mono text-[10px] font-normal text-ink-3">⌘K</kbd></>}</AppLink>;
    return compactRail ? <Tip key="new" content="新对话（⌘K / ⌃K）" placement="right">{el}</Tip> : el; };
  const treeEl = <WorkspaceTree sessions={sessions} onSessionsChanged={loadSessions} activeSession={page === 's' ? route[1] : undefined} lines={meta?.lines ?? []} projects={meta?.projects ?? []} tasks={wsData.tasks} budget={wsData.budget} role={current?.role ?? 'owner'} me={user?.login}
    active={activeObj} onNavigate={() => { setMobileNavOpen(false); setRailTree(false); }}
    onTaskAction={async (t, a) => { if (a.id === 'pause' || a.id === 'resume') { try { await api(`/api/tasks/${t.id}/${a.id}`, { method: 'POST', body: {} }); wsData.reload(); } catch (e: any) { setError(String(e.message)); } } }} />;
  const userEl = (compact: boolean) => user && <UserMenu compact={compact} login={user.login} name={user.name} avatarUrl={user.avatarUrl} role={current?.role ?? 'owner'} roleText={roleLabel(current?.role ?? 'owner')} wsName={current?.name}
    platformAdmin={!!mine?.platformAdmin} workspaces={mine?.workspaces ?? []} currentWs={current?.id} onSwitch={switchWs} onLogout={onLogout} />;

  const screens = {
    overview: <Overview filters={scopeFilters} runAction={canWrite && <Button size="sm" icon="play" loading={busy === 'tick'} onClick={() => act('tick', async () => { await api('/api/engine/intake', { method: 'POST', body: {} }); await api('/api/engine/tick', { method: 'POST', body: {} }); })}>运行一次</Button>} connected={meta?.connected} scope={{ productLine: line || undefined, projectId: project || undefined }} board={board} node={node} onNode={setNode} store={store} speed={1} live={live} />,
    tasks: <Tasks filters={scopeFilters} onCreate={canWrite ? () => setShowCreate(true) : undefined} params={q} onOpen={(id) => go(`task/${id}`)} />,
    invite: <AcceptInvite token={route[1]} />,
    platform: <Platform />,
    session: <Session me={user?.login} route={route} query={new URLSearchParams(location.search)} role={current?.role ?? 'owner'} lines={meta?.lines ?? []} onCatalogChanged={refresh} onSessionsChanged={loadSessions} projects={meta?.projects ?? []} canWrite={!current || current.role !== 'viewer'} canApprove={!current || current.role === 'owner' || current.role === 'admin'} go={go} replace={replace} />,
    chats: <Chats key={sessions?.length ?? 0} />,
    inbox: <Inbox data={wsData} projects={meta?.projects ?? []} role={current?.role ?? 'owner'} />,
    object: obj && <ObjectView key={`${obj.kind}:${obj.id}:${revision}`} r={obj} role={current?.role ?? 'owner'} me={user?.login} meta={meta} data={wsData} current={current} go={go} refresh={refresh} onCreateTask={(pid) => { setProject(pid); setShowCreate(true); }} />,
    notifications: <Suspense fallback={<PageSkeleton title="通知中心" />}><Notifications /></Suspense>,
    settings: <Suspense fallback={<PageSkeleton title="个人通知设置" />}><NotificationSettings projects={meta?.projects ?? []} /></Suspense>,
    admin: <AdminArea github={github} onGitHubRefresh={githubRecovered} onRecover={recoverGitHub} section={route[1]} sub={route[2]} isAdmin={!current || current.role === 'owner' || current.role === 'admin'} meta={meta} refresh={refresh} go={go} killed={killed} current={current} me={user?.login} />,
  };
  return (<ConsoleScreens.Provider value={screens}>
    <div className={cx('min-h-full transition-[padding] duration-150', isRail ? 'md:pl-sidebar-rail' : 'md:pl-sidebar')}>
      <AppLink href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-2 focus:top-2 focus:z-[60] focus:bg-primary focus:px-3 focus:py-2 focus:text-primary-fg">跳到主要内容</AppLink>

      {/* 移动端抽屉：主菜单 + 工作区树 + 用户菜单 */}
      {mobileNavOpen && (
        <div className="fixed inset-0 z-40 bg-scrim/40 md:hidden" onClick={() => setMobileNavOpen(false)}>
          <aside className="flex h-full w-[288px] max-w-[85vw] flex-col border-r-2 border-ink bg-surface" onClick={e => e.stopPropagation()} data-testid="mobile-sidebar">
            <div className="flex items-center justify-between border-b-2 border-ink px-4 py-3"><div className="flex items-center gap-2"><Mark /><span className="font-display text-base font-medium">7x24</span></div><IconButton label="关闭导航菜单" icon="x" onClick={() => setMobileNavOpen(false)} /></div>
            <nav aria-label="移动端导航" className="space-y-0.5 py-2">{newBtn(false)}{PRIMARY.map(n => navLink(n, false))}</nav>
            {treeEl}
            {userEl(false)}
          </aside>
        </div>
      )}

      {/* 桌面侧边栏：新对话 + 2 个主菜单 + 工作区树 + 左下角用户菜单；收起时 64px 图标栏，工作区树改为浮层 */}
      <aside data-testid="sidebar" data-mode={isRail ? 'rail' : 'expanded'}
        className={cx('hidden md:fixed md:inset-y-0 md:left-0 md:z-20 md:flex md:flex-col md:border-r-2 md:border-ink md:bg-surface transition-[width] duration-150', isRail ? 'md:w-sidebar-rail' : 'md:w-sidebar')}>
        <div className={cx('flex items-center border-b-2 border-ink py-3', isRail ? 'justify-center px-2' : 'justify-between px-3.5')}>
          <div className="flex min-w-0 items-center gap-2.5"><span aria-label="7x24 Agent" className="grid h-8 w-8 shrink-0 place-items-center"><Mark /></span>
            {!isRail && <div className="min-w-0"><div className="font-display text-base font-medium leading-5 text-ink">7x24</div><div className="truncate text-[10px] text-ink-3">{current?.name ?? '持续产品交付'}</div></div>}</div>
          {!isRail && <IconButton label="折叠为图标侧栏" icon="panelLeft" onClick={toggleRail} data-testid="sidebar-toggle" />}
        </div>
        <nav aria-label="主导航" className="space-y-0.5 py-2">{newBtn(isRail)}{PRIMARY.map(n => navLink(n, isRail))}
          {isRail && <Tip content="工作区" placement="right"><button type="button" aria-label="工作区" aria-expanded={railTree} onClick={() => setRailTree(o => !o)} data-testid="rail-tree"
            className={cx('relative flex w-full justify-center py-2', railTree ? 'bg-primary text-primary-fg' : 'text-ink-2 hover:bg-sunken')}><NodeIcon name="node-plan" size={17} />{inboxCount > 0 && <span className="absolute right-3 top-1.5 h-2 w-2 rounded-full bg-danger" />}</button></Tip>}
        </nav>
        {!isRail ? treeEl : <div className="flex-1" />}
        {userEl(isRail)}
        {isRail && <div className="flex justify-center border-t border-line py-2"><Tip content="展开完整侧边栏" placement="right"><IconButton label="展开完整侧边栏" icon="panelRight" onClick={toggleRail} data-testid="sidebar-toggle" /></Tip></div>}
      </aside>
      {isRail && railTree && <div className="fixed inset-y-0 left-[var(--sidebar-rail-w)] z-30 hidden w-[280px] flex-col border-r-2 border-ink bg-surface shadow-[6px_0_0_rgb(var(--c-line-strong)/0.4)] md:flex" data-testid="rail-tree-panel">
        <div className="flex items-center justify-between border-b-2 border-ink px-3 py-3 font-bold">工作区<IconButton label="关闭工作区" icon="x" onClick={() => setRailTree(false)} /></div>{treeEl}</div>}

      <div className="sticky top-0 z-10 border-b border-line bg-canvas/90 backdrop-blur">
        <div className="flex flex-wrap items-center gap-2 px-page py-2.5">
          <IconButton className="md:hidden" label="打开导航菜单" icon="menu" onClick={() => setMobileNavOpen(true)} />
          <span className="inline-flex items-center gap-2 font-display text-sm font-medium text-ink md:hidden"><Mark />7x24</span>
          <span className="min-w-0 truncate text-xs text-ink-3"><span className="hidden sm:inline">{current?.name ?? '当前工作区'} / </span>{pageName}</span>
          <AppLink href={newHref} aria-label="新对话" className="ml-auto inline-flex min-h-9 items-center gap-1 border border-ink px-2 text-xs md:hidden" data-testid="mobile-new"><span aria-hidden="true">＋</span>新对话</AppLink>
          <div className="ml-auto flex flex-wrap items-center gap-1.5">
            <AppLink href="/integrations" className="text-xs text-ink-3 hover:text-primary-ink" aria-label="查看工作区运行模式"><Badge tone="neutral" dot>{!meta ? '加载状态…' : meta.executeMode ? '真实执行' : meta.connected ? '只记录模式' : '体验模式'}</Badge></AppLink>
            <AppLink href="/integrations/connect" className="text-xs" aria-label="查看 GitHub 授权状态"><GitHubStatusBadge github={github} /></AppLink>
            {user && <div className="relative"><IconButton label={unread ? `通知中心，${unread} 条未读` : '通知中心'} icon="bell" onClick={() => go('notifications')} data-testid="notification-entry" />{unread > 0 && <span className="pointer-events-none absolute -right-1 -top-1 min-w-4 border border-primary/50 bg-primary-soft px-1 text-center text-[10px] leading-4 text-primary-ink" aria-hidden>{unread > 99 ? '99+' : unread}</span>}</div>}
            <IconButton label={killed.length ? `紧急停止已启用，${killed.length} 项保护` : '紧急停止'} icon="power" disabled={!meta} className={killed.length ? 'border-danger text-danger-ink' : ''} onClick={() => setShowKill(true)} data-testid="emergency-stop-entry" />
          </div>
        </div>
        {error && <div className="px-page pb-2"><Callout tone="danger" onClose={() => { setError(''); setErrDetail(undefined); }}>{error}{errDetail && <Disclosure className="mt-1" summaryClassName="text-caption text-ink-3" summary={<>详情</>}><pre className="mt-1 whitespace-pre-wrap font-mono text-caption text-ink-2">{errDetail}</pre></Disclosure>}</Callout></div>}
        {github && github.connection.state !== 'ready' && <div className="px-page pb-2"><GitHubConnectionNotice github={github} onRefresh={githubRecovered} onRecover={recoverGitHub} /></div>}
        {killed.length > 0 && <div className="px-page pb-2"><Callout tone="danger" icon="power">紧急停止已启用：{killed.map((k: any) => `${scopeName(k.scope, meta ?? undefined)} · ${killModeName(k.mode)}（${k.source === 'auto' ? '系统自动' : '手动'}，原因：${k.reason}）`).join('；')}<Button size="sm" variant="ghost" className="ml-2 text-danger-ink" onClick={() => setShowKill(true)}>查看保护</Button></Callout></div>}
      </div>

      <main id="main" tabIndex={-1} className="px-page pb-12 pt-5 outline-none"><PageBoundary resetKey={route.join('/')}>
        {children}
      </PageBoundary></main>

      {node && page === 'overview' && <NodeDrawer node={node} label={nodeLabel} params={q} onClose={() => setNode(null)} onOpen={(id) => { setNode(null); go(`task/${id}`); }} />}
      {showCreate && meta && <CreateModal initialProjectId={page === 'project' ? route[1] : project} meta={meta} onClose={() => setShowCreate(false)} onDone={() => { setShowCreate(false); setRevision(v => v + 1); refresh(); }} />}
      {showKill && meta && <KillModal meta={meta} canWrite={canWrite} onClose={() => setShowKill(false)} onChanged={refresh} />}
      {githubRecovery && github && <GitHubRecovery github={github} onClose={closeGitHubRecovery} onRecovered={githubRecovered} />}
    </div>
  </ConsoleScreens.Provider>);
}

function CreateModal({ meta, initialProjectId, onClose, onDone }: { meta: Meta; initialProjectId?: string; onClose: () => void; onDone: () => void }) {
  const eligible = meta.projects.filter(p => !p.paused && p.state === 'ACTIVE');
  const initial = eligible.find(p => p.id === initialProjectId) ?? eligible[0];
  const [line, setLine] = useState(initial?.productLineId ?? ''); const [projectId, setProjectId] = useState(initial?.id ?? '');
  const [title, setTitle] = useState(''); const [content, setContent] = useState(''); const [acceptanceCriteria, setAcceptance] = useState(''); const [msg, setMsg] = useState(''); const [busy, setBusy] = useState(false);
  const [presets, setPresets] = useState<Preset[] | null>(null); const [preset, setPreset] = useState(''); const [devMode, setDevMode] = useState<DevModeValue>('inherit');
  const projectMode = DEV_MODE_ZH[eligible.find(p => p.id === projectId)?.config.devMode ?? 'sandbox'];
  useEffect(() => { if (!line) return; api<{ presets: Preset[]; pipelineV2: boolean }>(`/api/pipeline/presets?line=${encodeURIComponent(line)}`).then(r => setPresets(r.pipelineV2 ? r.presets : null)).catch(() => setPresets(null)); setPreset(''); }, [line]);
  const submit = async () => { setBusy(true); try { const r: any = await api(`/api/projects/${projectId}/tasks`, { method: 'POST', body: { title, content, acceptanceCriteria, ...(preset ? { preset } : {}), ...(devMode !== 'inherit' ? { devMode } : {}) } }); if (r.duplicate) setMsg('重复需求，已去重'); else onDone(); } catch (e: any) { setMsg(e.message); } finally { setBusy(false); } };
  return <Modal title="新增交付任务" onClose={onClose} footer={<><Button onClick={onClose}>取消</Button><Button variant="primary" loading={busy} disabled={title.trim().length < 2 || !projectId} onClick={submit}>创建任务</Button></>}>
    <div className="space-y-3">
      {eligible.length === 0 ? <Callout tone="warn">请先在“产品线与项目”中创建或启用一个项目</Callout> : <>
        <Field label="产品线">{id => <Select id={id} value={line} onChange={e => { setLine(e.target.value); setProjectId(eligible.find(p => p.productLineId === e.target.value)?.id ?? ''); }}>{meta.lines.filter(l => eligible.some(p => p.productLineId === l.id)).map(l => <option key={l.id} value={l.id}>{l.name}</option>)}</Select>}</Field>
        <Field label="所属项目">{id => <Select id={id} value={projectId} onChange={e => setProjectId(e.target.value)}>{eligible.filter(p => p.productLineId === line).map(p => <option key={p.id} value={p.id}>{p.name} · {p.config.repo}</option>)}</Select>}</Field>
      </>}
      <Field label="任务标题">{id => <Input id={id} data-autofocus placeholder="例如：补全实验记录校验" value={title} onChange={e => setTitle(e.target.value)} />}</Field>
      <Field label="问题与需求描述">{id => <Textarea id={id} rows={3} value={content} onChange={e => setContent(e.target.value)} />}</Field>
      <Field label="验收标准">{id => <Textarea id={id} rows={3} placeholder="描述可验证的预期行为" value={acceptanceCriteria} onChange={e => setAcceptance(e.target.value)} />}</Field>
      {presets && <Field label="流程起点" hint="不选则用项目的默认预设">{id => <Select id={id} value={preset} onChange={e => setPreset(e.target.value)} data-testid="create-preset"><option value="">项目默认</option>{presetOptions(presets).map(o => <option key={o.value} value={o.value}>{o.label}</option>)}</Select>}</Field>}
      <Field label="开发方式" hint="不改就跟随项目设置；任务创建后也能在任务信息里改。">{() => <DevModeChoice compact name="create-dev-mode" value={devMode} onChange={setDevMode} inheritLabel={`用项目设置（现在是「${projectMode}」）。`} />}</Field>
      {msg && <Callout tone="warn">{msg}</Callout>}
    </div>
  </Modal>;
}

function KillModal({ meta, canWrite, onClose, onChanged }: { meta: Meta; canWrite: boolean; onClose: () => void; onChanged: () => void }) {
  const [scope, setScope] = useState('global'); const [reason, setReason] = useState('手动保护');
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  const call = async (fn: () => Promise<unknown>) => { setBusy(true); setError(''); try { await fn(); await onChanged(); } catch (e: any) { setError(e.message); } finally { setBusy(false); } };
  const confirm = useConfirm();
  const activate = async (m: string, l: string) => { if (await confirm({ title: `启用紧急停止：${l}`, description: `范围：${scopeName(scope, meta)}。启用后，相关的写操作和发布会立即停止，直到你手动解除。`, confirmText: '立即启用', destructive: true })) await call(() => api('/api/kill-switch', { method: 'POST', body: { mode: m, scope, reason } })); };
  return (
    <Modal title="紧急停止" width={560} onClose={onClose}>
      <p className="mb-3 text-sm text-ink-3">用于出问题时立刻刹车。系统在以下情况会自动启用：工作区预算用完、24 小时内撤回上线过多、操作记录校验失败；情况消除后自动解除。</p>
      {error && <Callout tone="danger">{error}</Callout>}
      {!canWrite && <p className="mb-3 text-xs text-ink-3">你可以查看保护状态，启用或解除需要工作区操作权限。</p>}
      <div className="space-y-3">
        {meta.killSwitches.length === 0 ? <p className="text-sm text-ink-3">当前没有启用紧急停止</p> : meta.killSwitches.map((k: any) => (
          <div key={k.id} className="flex items-center justify-between gap-2 border border-danger bg-danger-soft px-3 py-2 text-sm text-danger-ink"><span>{scopeName(k.scope, meta)} · {killModeName(k.mode)} · {k.source === 'auto' ? '系统自动' : '手动'} · {k.reason}</span><Button size="sm" disabled={!canWrite || busy} onClick={() => call(() => api(`/api/kill-switch/${k.id}`, { method: 'DELETE' }))}>解除</Button></div>))}
        <div className="grid grid-cols-2 gap-2"><Field label="范围">{(id) => <Select id={id} value={scope} onChange={(e) => setScope(e.target.value)}><option value="global">全局</option>{meta.lines.map((l) => <option key={l.id} value={`line:${l.id}`}>产品线 {l.name}</option>)}{meta.projects.map(p => <option key={p.id} value={`project:${p.id}`}>项目 {p.name}</option>)}</Select>}</Field><Field label="原因">{(id) => <Input id={id} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field></div>
        <div className="grid gap-2 sm:grid-cols-3">{([['READ_ONLY', '只读：停止所有写操作'], ['FREEZE_RELEASE', '冻结发布'], ['HALT', '全部停止']] as const).map(([m, l]) => <Button key={m} variant="danger" disabled={!canWrite || busy} onClick={() => activate(m, l)}>{l}</Button>)}</div>
      </div>
    </Modal>
  );
}

const ADMIN_LABEL: Record<string, string> = { integrations: '集成', models: '模型与流程', governance: '治理默认值', budget: '预算总额与变更历史', catalog: '产品线与项目', members: '成员与邀请', audit: '审计日志' };
/** 用户菜单里的工作区管理页（/admin/<section>）；成员列表对所有人可见（页面自己按角色只读），其余只给管理员 */
function AdminArea({ github, onGitHubRefresh, onRecover, section, sub, isAdmin, meta, refresh, go, killed, current, me }: { github: GitHubState | null; onGitHubRefresh: () => Promise<void>; onRecover: () => void; section?: string; sub?: string; isAdmin: boolean; meta: Meta | null; refresh: () => Promise<unknown>; go: (to: string) => void; killed: any[]; current: WorkspaceInfo | null; me?: string }) {
  if (section === 'members') return <WorkspacePage current={current} me={me} />;
  if (!isAdmin) return <Callout tone="warn">这里是工作区管理，需要管理员权限。</Callout>;
  switch (section) {
    case 'integrations': return <Integrations github={github} onGitHubRefresh={onGitHubRefresh} onRecover={onRecover} tab={sub} onTab={(t) => go(`admin/integrations/${t}`)} meta={meta} onChanged={refresh} onProject={(id) => go(`p/${id}/detail`)} />;
    case 'models': return <Models />;
    case 'governance': return <Governance meta={meta} onChanged={refresh} />;
    case 'budget': return <Budget meta={meta} onChanged={refresh} />;
    case 'catalog': return <Catalog lineId={sub} onLine={id => go(`admin/catalog/${id}`)} onProject={id => go(`p/${id}/detail`)} onChanged={refresh} killSwitches={killed} />;
    case 'audit': return <Audit />;
    default: return <Callout tone="info">从左下角用户菜单选择一个管理项。</Callout>;
  }
}

function useConsoleScreen(name: string) {
  const screens = useContext(ConsoleScreens);
  if (!screens) throw new Error("Console layout is missing");
  return screens[name] ?? null;
}
export function OverviewScreen() { return useConsoleScreen("overview"); }
export function TasksScreen() { return useConsoleScreen("tasks"); }
export function InviteScreen() { return useConsoleScreen("invite"); }
export function PlatformScreen() { return useConsoleScreen("platform"); }
export function SessionScreen() { return useConsoleScreen("session"); }
export function ChatsScreen() { return useConsoleScreen("chats"); }
export function InboxScreen() { return useConsoleScreen("inbox"); }
export function ObjectScreen() { return useConsoleScreen("object"); }
export function NotificationsScreen() { return useConsoleScreen("notifications"); }
export function SettingsScreen() { return useConsoleScreen("settings"); }
export function AdminScreen() { return useConsoleScreen("admin"); }
