/** 指挥台 v2 导航（docs/command-center-v2-nav.md）：主菜单、用户菜单、行菜单、对象页签、旧路由重定向、行图标。全是纯函数，便于测试 */
import type { NodeIconName } from '../ui/NodeIcon';
import type { Dot } from './commandCenter';

export type Role = 'owner' | 'admin' | 'member' | 'viewer' | string | undefined;
const RANK: Record<string, number> = { owner: 0, admin: 1, member: 2, viewer: 3 };
export const rank = (r: Role) => RANK[r ?? 'owner'] ?? 3;
export const isAdmin = (r: Role) => rank(r) <= 1;
export const isViewer = (r: Role) => rank(r) === 3;

/** 侧边栏顶部：「＋ 新对话」按钮（单独渲染）+ 2 个主菜单。通知入口保留在顶栏（docs/command-center-v2-nav.md 2.1） */
export const PRIMARY = [
  { id: 'overview', label: '总览', icon: 'node-plan' as NodeIconName },
  { id: 'inbox', label: '待我处理', icon: 'await-approval' as NodeIconName },
];

export interface MenuItem { id: string; label: string; href: string; icon: NodeIconName; group: '个人' | '工作区 · 管理' | '其它'; edit?: boolean; danger?: boolean }
/** 用户名弹出菜单：管理区只给管理员；只读/成员只看到个人项 + 成员列表（只读） */
export function userMenu(role: Role, platformAdmin = false): MenuItem[] {
  const out: MenuItem[] = [
    { id: 'settings', label: '个人通知设置', href: '/me/settings', icon: 'node-enter', group: '个人' },
  ];
  if (isAdmin(role)) out.push(
    { id: 'integrations', label: '集成（GitHub 与仓库）', href: '/admin/integrations', icon: 'node-design', group: '工作区 · 管理', edit: true },
    { id: 'models', label: '模型与流程', href: '/admin/models', icon: 'agent-think', group: '工作区 · 管理', edit: true },
    { id: 'governance', label: '治理默认值', href: '/admin/governance', icon: 'node-audit', group: '工作区 · 管理', edit: true },
    { id: 'budget', label: '预算总额与变更历史', href: '/admin/budget', icon: 'budget', group: '工作区 · 管理', edit: true },
    { id: 'catalog', label: '产品线与项目管理', href: '/admin/catalog', icon: 'node-plan', group: '工作区 · 管理', edit: true },
    { id: 'members', label: '成员与邀请', href: '/admin/members', icon: 'user-input', group: '工作区 · 管理', edit: true },
    { id: 'audit', label: '审计日志', href: '/admin/audit', icon: 'node-intake', group: '工作区 · 管理' },
  ); else out.push({ id: 'members', label: '成员列表（只读）', href: '/admin/members', icon: 'user-input', group: '工作区 · 管理' });
  if (platformAdmin) out.push({ id: 'platform', label: '平台管理', href: '/platform', icon: 'tool-run', group: '其它', edit: true });
  return out;
}

export type ObjKind = 'line' | 'project' | 'task';
export interface RowAction { id: string; label: string; href?: string; edit?: boolean; danger?: boolean; needAdmin?: boolean }
interface TaskControlState { paused?: boolean; blocked?: boolean; status?: string; executionState?: string }
export const canPauseTask = (s: TaskControlState) => !s.paused && !s.blocked
  && !['ANOMALY', 'DEGRADED', 'RELEASED', 'DELIVERED', 'DISCARDED'].includes(s.status ?? '')
  && !['BLOCKED', 'FAILED', 'PAUSED', 'COMPLETED', 'DISCARDED'].includes(s.executionState ?? '');
function taskControl(s: TaskControlState): RowAction[] {
  if (s.executionState === 'WAITING_CONFIGURATION') return [{ id: 'resume', label: '重新检查发布配置', edit: true }];
  if (['RELEASED', 'DELIVERED', 'DISCARDED'].includes(s.status ?? '')) return [];
  if (s.paused || s.status === 'ANOMALY' || s.blocked || ['BLOCKED','FAILED','WAITING_INPUT','RECOVERY_REQUIRED'].includes(s.executionState ?? '')) return [{ id: 'resume', label: '恢复执行', edit: true }];
  return canPauseTask(s) ? [{ id: 'pause', label: '暂停', edit: true }] : [];
}
/** 行 ⋯ 菜单。edit = 会改数据；只读成员一个都看不到；成员看到的改数据项标 needAdmin（走确认卡） */
export function rowMenu(kind: ObjKind, id: string, role: Role, extra: TaskControlState = {}): RowAction[] {
  const e = encodeURIComponent(id);
  const all: RowAction[] = kind === 'line' ? [
    { id: 'overview', label: '打开', href: `/l/${e}/overview` },
    { id: 'budget', label: '预算', href: `/l/${e}/budget` },
    { id: 'governance', label: '治理', href: `/l/${e}/governance` },
    { id: 'new-project', label: '新建项目', href: newSessionHref(id, 'project'), edit: true, needAdmin: true },
    { id: 'invite', label: '邀请成员', href: newSessionHref(id, 'invite'), edit: true, needAdmin: true },
  ] : kind === 'project' ? [
    { id: 'tasks', label: '任务', href: `/p/${e}/tasks` },
    { id: 'budget', label: '预算', href: `/p/${e}/budget` },
    { id: 'governance', label: '治理', href: `/p/${e}/governance` },
    { id: 'detail', label: '仓库与上下文', href: `/p/${e}/detail` },
    { id: 'new-task', label: '新任务', href: `/p/${e}/chat`, edit: true },
  ] : [
    { id: 'chat', label: '打开会话', href: `/t/${e}/chat` },
    { id: 'detail', label: '任务详情', href: `/t/${e}/detail` },
    { id: 'budget', label: '预算', href: `/t/${e}/budget` },
    ...taskControl(extra),
  ];
  if (isViewer(role)) return all.filter(a => !a.edit);
  return all.map(a => a.needAdmin && !isAdmin(role) ? { ...a, label: a.label + '（需管理员确认）' } : a);
}

export interface Tab { id: string; label: string; icon: NodeIconName }
export const TABS: Record<ObjKind, Tab[]> = {
  line: [{ id: 'overview', label: '项目', icon: 'node-plan' }, { id: 'chat', label: '对话', icon: 'user-input' }, { id: 'budget', label: '预算', icon: 'budget' }, { id: 'governance', label: '治理', icon: 'node-audit' }, { id: 'members', label: '成员', icon: 'user-input' }],
  project: [{ id: 'chat', label: '对话', icon: 'user-input' }, { id: 'tasks', label: '任务', icon: 'node-plan' }, { id: 'budget', label: '预算', icon: 'budget' }, { id: 'governance', label: '治理', icon: 'node-audit' }, { id: 'detail', label: '仓库与上下文', icon: 'node-design' }, { id: 'members', label: '成员', icon: 'user-input' }],
  task: [{ id: 'chat', label: '会话', icon: 'user-input' }, { id: 'pr', label: 'PR / CI', icon: 'pr-open' }, { id: 'accept', label: '验收', icon: 'node-accept' }, { id: 'delivery', label: '交付', icon: 'done' }, { id: 'budget', label: '预算', icon: 'budget' }],
};
export const defaultTab = (k: ObjKind) => TABS[k][0].id;

/** 对象页头部按钮：只读成员没有任何会改数据的按钮 */
export function headerActions(kind: ObjKind, role: Role, extra: TaskControlState = {}): RowAction[] {
  if (isViewer(role)) return [];
  if (kind === 'task') return taskControl(extra);
  if (kind === 'project') return [{ id: 'new-task', label: '＋ 新任务', edit: true }];
  return isAdmin(role) ? [{ id: 'new-project', label: '＋ 新建项目', edit: true }] : [];
}

/** 旧地址 → App Router 地址（兼容原 hash）。返回 null = 不用跳 */
export function navRedirect(hash: string): string | null {
  const raw = hash.replace(/^#?\/?/, ''); const [path, query] = raw.split('?'); const q = query ? `?${query}` : '';
  const p = path.split('/'); const id = p[1];
  switch (p[0]) {
    // 指挥台已取消：/chat… 和 /command… 都转到新对话 / 会话视图；会话视图发现已绑定任务时再跳到任务
    case 'chat': case 'command':
      if (p[0] === 'command' && p[1] === 't' && p[2]) return p[3] === 'node' && p[4] ? `/t/${p[2]}/node/${p[4]}${q}` : `/t/${p[2]}/chat${q}`;
      return p[1] ? `/s/${p[1]}${q}` : `/new${q}`;
    case 'task': if (!id) return `/tasks${q}`; {
      const sp = new URLSearchParams(query ?? ''); if (p[2] && !sp.has('tab')) sp.set('tab', p[2]); if (p[3] && !sp.has('focus')) sp.set('focus', p[3]);
      const s = sp.toString(); return `/t/${id}/detail${s ? '?' + s : ''}`;
    }
    case 'project': return id ? `/p/${id}/tasks${q}` : `/admin/catalog${q}`;
    case 'catalog': return id ? `/l/${id}/overview${q}` : `/admin/catalog${q}`;
    case 'integrations': case 'adapters': return `/admin/integrations${p[0] === 'integrations' && id ? '/' + id : ''}${q}`;
    case 'governance': return `/admin/governance${q}`;
    case 'budget': return `/admin/budget${q}`;
    case 'models': return `/admin/models${q}`;
    case 'audit': return `/admin/audit${q}`;
    case 'workspace': return `/admin/members${q}`;
    case 'settings': return `/me/settings${q}`;
    case 'notifications': return `/me/notifications${q}`;
    default: return null;
  }
}

/** 预算等级：<80% ok，≥80% warn，≥100% over；没设上限 = none */
export type BudgetLevel = 'none' | 'ok' | 'warn' | 'over';
export function budgetLevel(used: number | null | undefined, limit: number | null | undefined, soft = 0.8): BudgetLevel {
  if (!limit || limit <= 0) return 'none'; const r = (used ?? 0) / limit; return r >= 1 ? 'over' : r >= soft ? 'warn' : 'ok';
}
export const budgetPct = (used: number | null | undefined, limit: number | null | undefined) => (!limit ? 0 : Math.round(((used ?? 0) / limit) * 100));

export type RowBadge = { kind: 'blocked'; count?: number } | { kind: 'waiting'; count?: number } | { kind: 'budget'; level: 'warn' | 'over'; pct: number } | { kind: 'dot'; dot: Dot };
/** 行右侧小图标：按 阻塞 > 等我 > 预算 > 状态点 取前 2 个 */
export function rowBadges(x: { blocked?: number; waiting?: number; budget?: { level: BudgetLevel; pct: number }; dot?: Dot }): RowBadge[] {
  const out: RowBadge[] = [];
  if (x.blocked) out.push({ kind: 'blocked', count: x.blocked });
  if (x.waiting) out.push({ kind: 'waiting', count: x.waiting });
  if (x.budget && (x.budget.level === 'warn' || x.budget.level === 'over')) out.push({ kind: 'budget', level: x.budget.level, pct: x.budget.pct });
  if (out.length === 0 && x.dot) out.push({ kind: 'dot', dot: x.dot });
  return out.slice(0, 2);
}

/** 新路由解析：['t', id, tab, ...] 等 */
export type ObjRoute = { kind: ObjKind; id: string; tab: string; rest: string[] };
export function objRoute(route: string[]): ObjRoute | null {
  const k = ({ l: 'line', p: 'project', t: 'task' } as const)[route[0] as 'l' | 'p' | 't'];
  if (!k || !route[1]) return null;
  return { kind: k, id: route[1], tab: route[2] || defaultTab(k), rest: route.slice(3) };
}

/** 新对话入口：带上当前产品线作为上下文 */
export type NewIntent = 'project' | 'task' | 'invite';
export function newSessionHref(line?: string | null, intent?: NewIntent, project?: string): string {
  const q = new URLSearchParams(); if (line) q.set('line', line); if (intent) q.set('do', intent); if (project) q.set('project', project); const s = q.toString(); return `/new${s ? '?' + s : ''}`;
}
/** 行菜单「新建项目 / 邀请成员」打开新对话时预填的一句话 */
export function intentText(intent: string | null, lineName?: string): string {
  if (intent === 'project') return lineName ? `新建项目  到产品线 ${lineName}` : '新建项目 ';
  if (intent === 'task') return '新建任务 ';
  if (intent === 'invite') return '邀请 @';
  return '';
}
/** 新对话快捷键：Ctrl/Cmd+K（不带 Shift/Alt） */
export const isNewSessionKey = (e: { key: string; metaKey: boolean; ctrlKey: boolean; shiftKey: boolean; altKey: boolean }) => (e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey && e.key.toLowerCase() === 'k';
/** 侧边栏底部「其他对话」：没绑定任务的会话，按最近更新排序 */
export function otherSessions<T extends { task_id: string | null; updated_at: string; status?: string }>(list: T[]): T[] {
  return list.filter(s => !s.task_id).sort((a, b) => b.updated_at.localeCompare(a.updated_at));
}
/** 当前对象推出的产品线上下文：产品线本身 / 项目所属 / 任务所属项目的产品线 */
export function contextLine(obj: { kind: ObjKind; id: string } | null, projects: { id: string; productLineId: string }[], tasks: { id: string; projectId?: string | null }[] | null): string | null {
  if (!obj) return null;
  if (obj.kind === 'line') return obj.id;
  const pid = obj.kind === 'project' ? obj.id : tasks?.find(t => t.id === obj.id)?.projectId;
  return projects.find(p => p.id === pid)?.productLineId ?? null;
}

type SessLike = { id: string; task_id: string | null; updated_at: string; anchor_kind?: string | null; anchor_id?: string | null; anchor_name?: string | null; title?: string | null };
/** 侧边栏「最近对话」：所有会话按最近更新排序，取前 n 条（工作区树下方） */
export function recentSessions<T extends { updated_at: string }>(list: T[], n = 10): T[] {
  return [...list].sort((a, b) => b.updated_at.localeCompare(a.updated_at)).slice(0, n);
}
/** 任务会话都指向同一个任务入口，最近列表按任务合并；独立会话继续逐条保留。 */
export function recentConversationEntries<T extends SessLike & { pending_actions?: number }>(list: T[], n = 10): T[] {
  const entries = new Map<string, T>();
  for (const session of recentSessions(list, list.length)) {
    const href = sessionHref(session); const existing = entries.get(href);
    if (!existing) entries.set(href, session);
    else entries.set(href, { ...existing, pending_actions: (existing.pending_actions ?? 0) + (session.pending_actions ?? 0) });
  }
  return [...entries.values()].slice(0, n);
}
export type AnchorKind = 'task' | 'project' | 'line' | 'none';
/** 会话归属：优先用后端 anchor_kind；旧数据按 task_id 推断 */
export function anchorOf(s: SessLike): { kind: AnchorKind; id: string | null; name: string | null } {
  const k = (s.anchor_kind as AnchorKind | null | undefined) ?? (s.task_id ? 'task' : 'none');
  return { kind: k, id: s.anchor_id ?? (k === 'task' ? s.task_id : null), name: s.anchor_name ?? null };
}
export const ANCHOR_ZH: Record<AnchorKind, string> = { task: '任务', project: '项目', line: '产品线', none: '未归属' };
/** 打开会话：绑定任务的会话在任务的对话页签里，其余在 /s/<id> */
export function sessionHref(s: SessLike): string {
  const a = anchorOf(s); return a.kind === 'task' && (a.id ?? s.task_id) ? `/t/${encodeURIComponent((a.id ?? s.task_id)!)}/chat` : `/s/${encodeURIComponent(s.id)}`;
}
/** 会话所属对象的页面 */
export function anchorHref(s: SessLike): string | null {
  const a = anchorOf(s); if (!a.id) return null;
  return a.kind === 'task' ? `/t/${encodeURIComponent(a.id)}/chat` : a.kind === 'project' ? `/p/${encodeURIComponent(a.id)}/chat` : a.kind === 'line' ? `/l/${encodeURIComponent(a.id)}/chat` : null;
}
/** 某个对象下的会话数（树行 💬 计数）：项目只数挂在该项目上的 */
export function sessionCount(list: SessLike[], kind: AnchorKind, id: string): number { return list.filter(s => { const a = anchorOf(s); return a.kind === kind && a.id === id; }).length; }
/** 对象页 ?from=s/<id> → 返回对话的会话 id */
export function fromSession(hash: string): string | null {
  const q = new URLSearchParams(hash.split('?')[1] ?? ''); const f = q.get('from'); const m = f && /^s\/([A-Za-z0-9_]{1,40})$/.exec(f); return m ? m[1] : null;
}
/** 最近对话 / 全部对话的筛选 */
export function filterSessions<T extends SessLike>(list: T[], q: string, kind: AnchorKind | 'all'): T[] {
  const s = q.trim().toLowerCase();
  return list.filter(x => (kind === 'all' || anchorOf(x).kind === kind) && (!s || (x.title ?? '').toLowerCase().includes(s) || (x.anchor_name ?? '').toLowerCase().includes(s)));
}
