import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { api, isSessionExpired } from '../api';
import { GitHubConnectionNotice, GitHubStatusBadge } from '../components/GitHubConnectionNotice';
import { githubFailure, onGitHubChanged, type GitHubConnection, type GitHubState } from './githubConnection';

const ready: GitHubState = { connected: true, authorizationAvailable: true, signedIn: true, canManage: true, connection: { state: 'ready', message: 'GitHub 授权正常' }, backgroundIdentity: 'app', credential: { source: 'oauth', login: 'octo', deletable: true }, mode: 'observe', oauthAvailable: true, minPermissions: '', notice: '' };
afterEach(() => vi.unstubAllGlobals());

describe('GitHub authorization visible outside the repository picker', () => {
  it('a real repository API failure updates the global notice without logging out', async () => {
    const response = { code: 'github_unauthorized', githubStatus: 401, error: '令牌无效或已过期，请重新连接 GitHub' };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(response), { status: 502 })));
    let connection: GitHubConnection | undefined;
    const off = onGitHubChanged(event => { connection = event.connection; });
    try {
      await expect(api('/api/integrations/github/repos')).rejects.toThrow(response.error);
      expect(connection?.state).toBe('reauth_required');
      const github = { ...ready, connected: false, connection: connection! };
      const outside = renderToStaticMarkup(<><GitHubStatusBadge github={github} /><GitHubConnectionNotice github={github} onRefresh={async () => {}} onRecover={() => {}} /></>);
      expect(outside).toContain('GitHub 授权需要更新'); expect(outside).toContain('更新 GitHub 授权');
      expect(outside).not.toContain('GitHub · 正常');
      expect(isSessionExpired(502, '/api/integrations/github/repos', response)).toBe(false);
    } finally { off(); }
  });
  it('healthy authorization does not display a reconnect prompt', () => {
    expect(renderToStaticMarkup(<GitHubConnectionNotice github={ready} onRefresh={async () => {}} onRecover={() => {}} />)).toBe('');
  });
  it('network problems offer a retry while permissions direct members to an administrator', () => {
    expect(githubFailure({ githubStatus: 503 })?.state).toBe('network_error');
    expect(githubFailure({ githubStatus: 403 })?.state).toBe('permissions_required');
    expect(githubFailure({ code: 'unauthorized' })).toBeNull();
    const github: GitHubState = { ...ready, canManage: false, connection: { state: 'permissions_required', message: 'GitHub 仓库权限需要更新' } };
    const outside = renderToStaticMarkup(<GitHubConnectionNotice github={github} onRefresh={async () => {}} onRecover={() => {}} />);
    expect(outside).toContain('请联系工作区管理员'); expect(outside).not.toContain('href=');
  });
});
