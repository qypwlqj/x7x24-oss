import { useState } from 'react';
import { api } from '../api';
import type { GitHubState } from '../lib/githubConnection';
import { Button } from '../ui';

export function ManageRepositoryAuthorization({ gh }: { gh: GitHubState }) {
  const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const open = async () => {
    const popup = window.open('about:blank', 'x7-github-repositories', 'width=900,height=850');
    setBusy(true); setError('');
    try {
      const redirect = location.origin + location.pathname + '?github_setup=1';
      const result = await api<{ url: string }>('/api/integrations/github/install', { method: 'POST', body: { redirect } });
      if (!popup) throw new Error('请允许打开 GitHub 仓库授权窗口');
      popup.location.href = result.url;
    } catch (e: any) { popup?.close(); setError(e.message); } finally { setBusy(false); }
  };
  return <span className="inline-flex flex-wrap items-center gap-2"><Button size="sm" disabled={!gh.canManage} loading={busy} onClick={open}>管理仓库授权</Button>{error && <span role="alert" className="text-xs text-danger-ink">{error}</span>}</span>;
}
