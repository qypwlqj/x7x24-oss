import { appPath, BASE_PATH } from './navigation';
/** 多工作区：前端类型与纯函数（可测试） */
export interface WorkspaceInfo { id: string; slug: string; name: string; kind: string; role: string; status: string }
export interface MyWorkspaces { current: WorkspaceInfo | Record<string, never>; workspaces: WorkspaceInfo[]; platformAdmin?: boolean }
export interface Quota { llmFallback?: boolean; llmFallbackUsdMonth?: number | null; maxConcurrentTasks?: number | null }
export interface PlatformWorkspace { id: string; slug: string; name: string; kind: string; status: string; created_at: string; owner: string | null; quota: Quota; members: number; projects: number; tasks: number }

export const ROLE_LABEL: Record<string, string> = { owner: '所有者', admin: '管理员', member: '成员', viewer: '只读' };
export const roleLabel = (r?: string | null) => (r ? ROLE_LABEL[r] ?? r : '-');
export const canManage = (r?: string | null) => r === 'owner' || r === 'admin';
export const canWrite = (r?: string | null) => !!r && r !== 'viewer';

/** 配额表单 → 请求体：空串 = 清除（null，回到默认）；非法数字报错 */
export function quotaPatch(form: { llmFallback: boolean; usd: string; conc: string }): Quota {
  const num = (s: string, name: string, int: boolean) => {
    const t = s.trim(); if (t === '') return null;
    const n = Number(t); if (!Number.isFinite(n) || n < 0 || (int && !Number.isInteger(n))) throw new Error(`${name}必须是${int ? '非负整数' : '非负数'}`);
    return n;
  };
  return { llmFallback: form.llmFallback, llmFallbackUsdMonth: num(form.usd, '平台模型月额度', false), maxConcurrentTasks: num(form.conc, '并发任务上限', true) };
}

/** 邀请链接：当前页面地址 + /invite/<令牌> */
export const inviteLink = (loc: { origin: string; pathname: string }, token: string) => `${loc.origin}${appPath(`/invite/${encodeURIComponent(token)}`)}`;
/** 当前用户能把目标成员改成哪些角色（与后端规则一致；空数组 = 不能改） */
export function assignableRoles(actorRole: string | undefined | null, targetRole: string, self: boolean): string[] {
  if (self || targetRole === 'owner') return [];
  if (actorRole === 'owner') return ['admin', 'member', 'viewer'];
  if (actorRole === 'admin' && targetRole !== 'admin') return ['member', 'viewer'];
  return [];
}
const PENDING = 'x7_invite';
/** 登录前打开邀请链接：先记下令牌，登录回跳后再处理 */
export const rememberInvite = (path: string) => { const value = BASE_PATH && path.startsWith(BASE_PATH + '/') ? path.slice(BASE_PATH.length) : path; const m = value.match(/^#?\/?invite\/([^/?]+)/); if (m) { try { localStorage.setItem(PENDING, decodeURIComponent(m[1])); } catch { /* ignore */ } } };
export const takePendingInvite = () => { try { const t = localStorage.getItem(PENDING); if (t) localStorage.removeItem(PENDING); return t; } catch { return null; } };
