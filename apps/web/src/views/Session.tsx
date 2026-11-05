/** 新对话（/new）与未绑定任务的会话（/s/<id>）。取代原来的指挥台首页（docs/command-center-v2-nav.md 2.1、4） */
import { useEffect, useState } from 'react';
import { api, type LineMeta, type ProjectDTO } from '../api';
import type { ChatSession } from '../lib/chat';
import { makeBinder } from '../lib/session';
import { intentText, newSessionHref } from '../lib/nav';
import { AppLink } from '../components/AppLink';
import { Callout } from '../ui';
import { BlankComposer, SessionBody } from './Chat';

interface Props { me?: string; route: string[]; query: URLSearchParams; role?: string; lines: LineMeta[]; projects: ProjectDTO[]; canWrite: boolean; canApprove: boolean; onCatalogChanged?: () => void; onSessionsChanged?: () => void; go: (to: string) => void; replace: (to: string) => void }

export default function Session({ me, route, query, role, lines, projects, canWrite, canApprove, onCatalogChanged, onSessionsChanged, go, replace }: Props) {
  const id = route[0] === 's' ? route[1] : undefined;
  const [s, setS] = useState<ChatSession | null>(null); const [err, setErr] = useState('');
  // 会话一旦绑定任务（打开时已绑定，或这一轮刚建了任务），就转到 /t/<taskId>/chat，之后它只挂在任务下面
  const bind = makeBinder<ChatSession>({ fetch: x => api<ChatSession>(`/api/chats/${x}`), replace, show: setS, onBound: onSessionsChanged });
  const load = () => id ? bind(id).catch(e => setErr(e.message)) : Promise.resolve();
  useEffect(() => { setS(null); setErr(''); load(); }, [id]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!id) {
    const project = projects.find(p => p.id === query.get('project'));
    const line = project?.productLineId ?? query.get('line'); const lineName = lines.find(l => l.id === line)?.name;
    return <div data-testid="new-conversation-page">
      <div className="mx-auto w-full max-w-2xl"><h1 className="text-lg font-bold">新对话</h1><p className="mt-2 text-sm text-ink-3">{canWrite ? projects.length === 0 ? '还没有项目？描述你想做的事，从这里创建项目和任务。' : '描述需求，创建项目或任务，也可以直接询问进度。' : '可以在这里提问、查看项目和任务进度。'}</p>
        {canWrite && <div className="mt-3 flex flex-wrap gap-3 text-sm" data-testid="new-conversation-starters"><AppLink className="underline" href={newSessionHref(line, 'project')}>创建项目</AppLink><AppLink className="underline" href={newSessionHref(line, 'task', project?.id)}>创建任务</AppLink></div>}
      </div>
      <BlankComposer key={[line, project?.id, query.get('do')].join(':')} initial={intentText(query.get('do'), lineName)} context={{ productLineId: line, projectId: project?.id }} onCreated={x => { onSessionsChanged?.(); go(`s/${x}`); }}
        hint={<>{project ? <>项目：{project.name} · </> : lineName ? <>产品线：{lineName} · </> : null}{canWrite ? '会改动数据的操作都会先给你一张确认卡' : '你是只读成员：可以提问、查进度'} · ⌘K / ⌃K 随时新开</>} />
    </div>;
  }
  return <div className="space-y-gap" data-testid="session-view">
    {err && <Callout tone="danger" onClose={() => setErr('')}>{err}</Callout>}
    {s ? <SessionBody s={s} me={me} onDeleted={() => { onSessionsChanged?.(); replace('new'); }} role={role} lines={lines} projects={projects} canAsk canWrite={canWrite} canApprove={canApprove} onCatalogChanged={onCatalogChanged}
      onChanged={x => setS(x)} reload={() => { load(); onSessionsChanged?.(); }} onTask={t => replace(`t/${encodeURIComponent(t)}/chat`)} /> : !err && <p className="text-sm text-ink-3">加载中…</p>}
  </div>;
}
