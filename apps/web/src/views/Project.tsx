import { useCallback, useEffect, useState } from 'react';
import { api, fmtTime, type BoardResponse, type ProjectDTO } from '../api';
import { catalogStateName, isUndetected, type StackSuggestion } from '../catalog';
import { StackSuggestionView } from '../components/StackSuggestionView';
import { PipelineFlow } from '../components/PipelineFlow';
import { ProjectPipelineSettings } from '../components/ProjectPipelineSettings';
import { NodeBoardCard } from '../components/NodeBoard';
import { Badge, Button, Callout, Card, EmptyState, Field, Input, Modal, PageHeader, PageSkeleton, Textarea } from '../ui';
import { ProjectForm } from './Catalog';
import Tasks, { NodeDrawer } from './Tasks';
import { Disclosure } from '@/components/ui/collapsible';

interface Candidate { id: string; source: string; title: string; problem: string; acceptance_criteria: string; state: string; risk_level: string; occurrences: number; task_id: string | null; evidence: unknown; last_seen_at: string }
export default function Project({ id, today, onBack, onTask, onCreate, onChanged, canEdit = true }: { canEdit?: boolean; id: string; today: string; onBack: (lineId: string) => void; onTask: (id: string) => void; onCreate: () => void; onChanged: () => Promise<unknown> }) {
  const [project, setProject] = useState<ProjectDTO | null>(null); const [board, setBoard] = useState<BoardResponse | null>(null); const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [busy, setBusy] = useState('');
  const [suggestion, setSuggestion] = useState<StackSuggestion | null>(null);
  const [edit, setEdit] = useState(false); const [logForm, setLogForm] = useState(false); const [node, setNode] = useState<string | null>(null);
  const params = new URLSearchParams({ projectId: id, from: '2000-01-01', to: today }).toString();
  const load = useCallback(async () => {
    const [p, b, c] = await Promise.all([api<ProjectDTO>(`/api/projects/${id}`), api<BoardResponse>(`/api/board?${params}`), api<Candidate[]>(`/api/projects/${id}/discovery/candidates`)]);
    setProject(p); setBoard(b); setCandidates(c);
  }, [id, params]);
  useEffect(() => { let active = true; const refresh = () => load().catch(e => { if (active) setError(e.message); }); refresh(); const timer = setInterval(refresh, 12000); return () => { active = false; clearInterval(timer); }; }, [load]);
  const act = async (name: string, action: () => Promise<unknown>) => { setBusy(name); setError(''); try { await action(); await Promise.all([load(), onChanged()]); } catch (e: any) { setError(e.message); } finally { setBusy(''); } };
  if (!project) return error ? <Callout tone="danger">{error}</Callout> : <PageSkeleton />;
  const stateLabel = project.paused && project.state === 'ACTIVE' ? '随产品线暂停' : catalogStateName(project.state);
  return <div className="space-y-gap" data-testid="project-detail">
    <Button variant="ghost" size="sm" icon="back" onClick={() => onBack(project.productLineId)}>返回 {project.productLineName || '产品线'}</Button>
    <PageHeader title={project.name} desc={`${project.productLineName} / ${project.name} · ${project.config.repo ?? '未绑定仓库'}`} actions={<div className="flex flex-wrap gap-2"><Badge tone={project.paused ? 'warn' : 'success'}>{stateLabel}</Badge><Button onClick={() => setEdit(true)}>项目配置</Button><Button variant="primary" icon="plus" disabled={project.paused} onClick={onCreate}>新增任务</Button></div>} />
    {error && <Callout tone="danger" onClose={() => setError('')}>{error}</Callout>}{notice && <Callout tone="info" onClose={() => setNotice('')}>{notice}</Callout>}
    {project.releaseCapability && <Callout tone={project.releaseCapability.ready ? 'info' : 'warn'}><b>发布目标：{project.config.releaseTarget === 'wechat_miniprogram' ? '微信小程序' : 'GitHub Release'}</b><p className="mt-1">{project.releaseCapability.detail}</p></Callout>}
    <div className="grid gap-gap md:grid-cols-3">
      <Card title="交付上下文"><p className={isUndetected(project.config.technology) ? 'text-sm text-warn-ink' : 'text-sm'}>{project.config.technology}</p>
        <Button size="sm" className="mt-2" icon="refresh" loading={busy === 'detect'} onClick={() => act('detect', async () => { const r: any = await api(`/api/projects/${id}/detect`, { method: 'POST' }); setSuggestion(r.suggestion); })} data-testid="detect-stack">自动识别技术栈</Button>
        {suggestion && <div className="mt-3 border border-primary/50 bg-primary-soft/40 p-2"><StackSuggestionView s={suggestion} /><div className="mt-2 flex gap-2"><Button size="sm" variant="primary" loading={busy === 'apply'} onClick={() => act('apply', async () => { await api(`/api/projects/${id}/detect?apply=true`, { method: 'POST' }); setSuggestion(null); setNotice('已应用识别结果，新任务将使用新的目录、文件类型与测试命令'); })} data-testid="apply-stack">确认应用</Button><Button size="sm" onClick={() => setSuggestion(null)}>忽略</Button></div></div>}<p className="mt-2 text-xs text-ink-3">分支 {project.config.baseBranch} · CI {project.config.workflow}</p><div className="mt-3"><Button size="sm" loading={busy === 'connection'} onClick={() => act('connection', async () => { const result: any = await api(`/api/projects/${id}/connection`, { method: 'POST', body: {} }); if (!result.ok) throw new Error(result.error || '连接校验失败'); setNotice('仓库、目标分支和 CI 配置校验通过'); })}>测试连接</Button></div></Card>
      <Card title="项目任务"><p className="text-2xl font-bold tabular">{project.taskCount ?? 0}<span className="ml-2 text-sm font-normal text-ink-3">个任务</span></p><p className="mt-2 text-sm text-ink-3">{project.activeTasks ?? 0} 个进行中 · 当前自治等级 {project.effectiveAutonomy}</p></Card>
      <Card title="项目成本与运行"><p className="text-2xl font-bold tabular">${(project.spentUsd ?? 0).toFixed(2)}</p><div className="mt-3 flex gap-2"><Button size="sm" disabled={project.state === 'ARCHIVED'} loading={busy === 'pause'} onClick={() => act('pause', () => api(`/api/projects/${id}`, { method: 'PUT', body: { state: project.state === 'PAUSED' ? 'ACTIVE' : 'PAUSED' } }))}>{project.state === 'PAUSED' ? '恢复项目' : '暂停项目'}</Button></div></Card>
    </div>
    {board && <Card title="项目交付流水线" subtitle="任务当前处于一个阶段，节点执行记录保留整个交付过程" flush><PipelineFlow board={board} selected={node} onSelect={setNode} /></Card>}
    <NodeBoardCard projectId={id} />
    <ProjectPipelineSettings projectId={id} lineId={project.productLineId} canEdit={canEdit} />
    <Card title="需求候选" subtitle={project.lastScan ? `最近扫描：${fmtTime(project.lastScan.started_at)} · ${project.lastScan.state === 'COMPLETED' ? '完成' : project.lastScan.state === 'FAILED' ? '失败' : '进行中'}` : '从源码、主干 CI 失败或错误日志发现可交付的问题'} actions={<div className="flex gap-2"><Button size="sm" disabled={project.paused} onClick={() => setLogForm(true)}>导入错误日志</Button><Button size="sm" icon="play" loading={busy === 'scan'} disabled={project.paused} onClick={() => act('scan', async () => { const result: any = await api(`/api/projects/${id}/discovery/scan`, { method: 'POST', body: {} }); setNotice(`扫描完成，涉及 ${result.candidateCount} 个候选。${result.notes?.join('；') || ''}`); })}>发现需求</Button></div>}>
      {project.lastScan?.state === 'FAILED' && <Callout tone="warn">上次扫描失败：{project.lastScan.summary}</Callout>}
      {candidates.length === 0 ? <EmptyState title="暂无候选需求" hint="点击上方的扫描，或导入错误日志，发现的问题会出现在这里。" /> : <div className="space-y-3">{candidates.map(c => <div key={c.id} className="border border-line p-3"><div className="flex flex-wrap items-center justify-between gap-2"><b className="text-sm">{c.title}</b><div className="flex gap-2"><Badge tone={c.state === 'CONVERTED' ? 'success' : 'neutral'}>{c.state === 'CANDIDATE' ? '候选' : c.state === 'CONVERTED' ? '已转任务' : '已忽略'}</Badge><Badge>{c.source === 'source' ? '源码' : c.source === 'test' ? 'CI 失败' : '错误日志'}</Badge></div></div>
        <p className="mt-2 whitespace-pre-wrap text-sm text-ink-2">{c.problem}</p><p className="mt-2 text-xs text-ink-3">验收标准：{c.acceptance_criteria}</p><Disclosure className="mt-2 text-xs" summaryClassName="cursor-pointer" summary={<>查看证据 · 出现 {c.occurrences} 次</>}><pre className="mt-1 max-h-48 overflow-auto whitespace-pre-wrap border border-line bg-sunken p-2">{JSON.stringify(c.evidence, null, 2)}</pre></Disclosure>
        <div className="mt-3 flex gap-2">{c.task_id ? <Button size="sm" onClick={() => onTask(c.task_id!)}>查看任务</Button> : c.state === 'CANDIDATE' && <><Button size="sm" variant="primary" disabled={project.paused || busy !== ''} loading={busy === c.id} onClick={() => act(c.id, async () => { const result: any = await api(`/api/projects/${id}/discovery/candidates/${c.id}/tasks`, { method: 'POST', body: {} }); setNotice(result.duplicate ? '该候选已有关联任务' : '候选已进入任务池'); })}>转为任务</Button><Button size="sm" disabled={busy !== ''} onClick={() => act(c.id, () => api(`/api/projects/${id}/discovery/candidates/${c.id}/dismiss`, { method: 'PUT', body: {} }))}>忽略</Button></>}</div>
      </div>)}</div>}
    </Card>
    <Tasks params={params} onOpen={onTask} />
    {node && <NodeDrawer node={node} label={board?.nodes.find(n => n.id === node)?.label ?? node} params={params} onClose={() => setNode(null)} onOpen={taskId => { setNode(null); onTask(taskId); }} />}
    {edit && <ProjectForm lineId={project.productLineId} project={project} onClose={() => setEdit(false)} onSaved={async () => { setEdit(false); await Promise.all([load(), onChanged()]); }} />}
    {logForm && <LogForm projectId={id} onClose={() => setLogForm(false)} onSaved={async () => { setLogForm(false); await load(); }} />}
  </div>;
}
function LogForm({ projectId, onClose, onSaved }: { projectId: string; onClose: () => void; onSaved: () => Promise<unknown> }) {
  const [title, setTitle] = useState(''); const [log, setLog] = useState(''); const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const save = async () => { setBusy(true); try { await api(`/api/projects/${projectId}/discovery/logs`, { method: 'POST', body: { title, log } }); await onSaved(); } catch (e: any) { setError(e.message); } finally { setBusy(false); } };
  return <Modal title="导入错误日志" onClose={onClose} footer={<><Button onClick={onClose}>取消</Button><Button variant="primary" loading={busy} disabled={title.trim().length < 2 || !log.trim()} onClick={save}>生成候选需求</Button></>}><div className="space-y-3"><Field label="问题标题">{fieldId => <Input id={fieldId} value={title} onChange={e => setTitle(e.target.value)} />}</Field><Field label="错误日志">{fieldId => <Textarea id={fieldId} rows={8} value={log} onChange={e => setLog(e.target.value)} />}</Field>{error && <Callout tone="danger">{error}</Callout>}</div></Modal>;
}
