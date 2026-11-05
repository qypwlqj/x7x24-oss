import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api';
import { getWorkspace } from './auth';
import { onGitHubChanged, type GitHubState } from './githubConnection';

/** One subscription at the app boundary supplies both the page and the global banner. */
export function useGitHubConnection(workspaceId?: string) {
  const [github, setGitHub] = useState<GitHubState | null>(null);
  const generation = useRef(0);
  const refresh = useCallback(async (force = false) => {
    const request = ++generation.current;
    try {
      const state = await api<GitHubState>(`/api/integrations/github${force ? '?refresh=true' : ''}`);
      if (request === generation.current) setGitHub(state);
    } catch {
      if (request === generation.current) setGitHub(previous => ({ ...(previous ?? { authorizationAvailable: false, signedIn: false, canManage: false, backgroundIdentity: 'user', mode: 'experience', credential: { source: 'none', deletable: false }, oauthAvailable: false, minPermissions: '', notice: '' }), connected: false, connection: { state: 'network_error', message: '暂时无法检查 GitHub 连接，请重试' } }));
    }
  }, [workspaceId]);
  useEffect(() => {
    setGitHub(null); void refresh();
    const off = onGitHubChanged(event => {
      if (event.workspace !== getWorkspace()) return;
      if (event.connection) { generation.current++; setGitHub(previous => previous && { ...previous, connected: false, connection: event.connection! }); }
      else void refresh();
    });
    const focus = () => { void refresh(true); };
    const message = (event: MessageEvent) => { if (event.origin === location.origin && event.data?.type === 'x7-github-reconnected') void refresh(true); };
    const timer = setInterval(() => { if (document.visibilityState === 'visible') void refresh(); }, 60_000);
    window.addEventListener('focus', focus); window.addEventListener('message', message);
    return () => { generation.current++; off(); clearInterval(timer); window.removeEventListener('focus', focus); window.removeEventListener('message', message); };
  }, [refresh]);
  return { github, refreshGitHub: refresh };
}
