import { getWorkspace } from './auth';

export type GitHubConnectionState = 'unchecked' | 'ready' | 'disconnected' | 'reauth_required' | 'repositories_required' | 'approval_pending' | 'permissions_required' | 'network_error';
export interface GitHubConnection { state: GitHubConnectionState; message: string; checkedAt?: string }
export interface GitHubState {
  connected: boolean; authorizationAvailable: boolean; signedIn: boolean; canManage: boolean;
  connection: GitHubConnection; backgroundIdentity: 'app' | 'user';
  mode: 'experience' | 'observe' | 'execute';
  credential: { source: string; login?: string; avatarUrl?: string; scopes?: string; permissionsSummary?: string; createdAt?: string; expiresAt?: string; deletable: boolean };
  oauthAvailable: boolean; minPermissions: string; appInstallUrl?: string | null; notice: string;
}
export const CONNECTION_LABEL: Record<GitHubConnectionState, string> = {
  unchecked: '检查中', ready: '正常', disconnected: '待授权', reauth_required: '需要更新授权', repositories_required: '待选择仓库', approval_pending: '等待管理员批准', permissions_required: '权限待更新', network_error: '连接异常',
};
export function githubFailure(data: { code?: string; githubStatus?: number }): GitHubConnection | null {
  if (data.code !== 'github_unauthorized' && data.githubStatus === undefined) return null;
  if (data.code === 'github_rate_limited') return { state: 'network_error', message: 'GitHub 请求较多，请稍后重试' };
  if (data.code === 'github_unauthorized' || data.githubStatus === 401) return { state: 'reauth_required', message: 'GitHub 授权需要更新，请重新授权' };
  if (data.githubStatus === 403 || data.githubStatus === 404) return { state: 'permissions_required', message: 'GitHub 仓库权限需要更新' };
  return { state: 'network_error', message: '暂时无法连接 GitHub，请稍后重试' };
}
type Event = { workspace: string | null; connection?: GitHubConnection };
const listeners = new Set<(event: Event) => void>();
export const onGitHubChanged = (listener: (event: Event) => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; };
export const notifyGitHubChanged = (connection?: GitHubConnection, workspace = getWorkspace()) => listeners.forEach(listener => listener({ workspace, connection }));
