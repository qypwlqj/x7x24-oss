import { AppLink } from '@/components/AppLink';
import { DevModeChoice } from '../components/DevModeChoice';
import { useCallback, useEffect, useState } from 'react';
import type { ProjectConfig, BudgetLimits, CatalogState } from '@x7x24/shared';
import { api, type LineMeta, type ProjectDTO } from '../api';
import { applySuggestion, catalogStateName, defaultProjectConfig, isUndetected, type StackSuggestion } from '../catalog';
import { StackSuggestionView } from '../components/StackSuggestionView';
import LinePipeline from './LinePipeline';
import { Badge, Button, Callout, Card, EmptyState, Field, Input, Modal, optionSheet, PageHeader, PageSkeleton, PencilTick, Select, Textarea , Checkbox } from '../ui';
import { ListItemButton } from '@/components/ui/button';

export default function Catalog({ lineId, onLine, onProject, onChanged, killSwitches = [] }: { killSwitches?: any[]; lineId?: string; onLine: (id: string) => void; onProject: (id: string) => void; onChanged: () => Promise<unknown> }) {
  const [lines, setLines] = useState<LineMeta[]>([]); const [projects, setProjects] = useState<ProjectDTO[]>([]);
  const [error, setError] = useState(''); const [loaded, setLoaded] = useState(false);
  const [lineForm, setLineForm] = useState<LineMeta | 'new' | null>(null); const [projectForm, setProjectForm] = useState(false);
  const load = useCallback(async () => { const [a, b] = await Promise.all([api<LineMeta[]>('/api/product-lines'), api<ProjectDTO[]>('/api/projects')]); setLines(a); setProjects(b); setLoaded(true); }, []);
  useEffect(() => { load().catch(e => setError(e.message)); }, [load]);
  const line = lines.find(item => item.id === lineId) ?? lines[0];
  const saved = async () => { setLineForm(null); setProjectForm(false); await Promise.all([load(), onChanged()]); };
  return <div className="space-y-gap" data-testid="catalog">
    <PageHeader title="产品线与项目" desc="产品线管理业务归属，项目连接仓库并执行交付任务" actions={<Button variant="primary" icon="plus" onClick={() => setLineForm('new')}>新增产品线</Button>} />
    {error && <Callout tone="danger" onClose={() => setError('')}>{error}</Callout>}
    {!loaded ? <PageSkeleton /> : lines.length === 0 ? <EmptyState title="还没有产品线" hint="产品线代表一个业务目标，下面挂项目和仓库。先新增一条。" /> : <div className="grid items-start gap-gap lg:grid-cols-[260px_1fr]">
      <Card title="产品线"><div className="space-y-2">{lines.map(item => <ListItemButton key={item.id} onClick={() => onLine(item.id)} className={optionSheet(item.id === line?.id)}>
        {item.id === line?.id && <PencilTick className="absolute right-2.5 top-2.5" />}
        <div className="flex justify-between gap-2"><b>{item.name}</b><Badge tone={item.state === 'ACTIVE' ? 'success' : 'neutral'}>{catalogStateName(item.state)}</Badge></div>
        {killSwitches.filter((k: any) => k.scope === `line:${item.id}` || k.scope === 'global').map((k: any) => <div key={k.id} className="mt-1"><Badge tone="danger" dot>{k.mode === 'FREEZE_RELEASE' ? '发布已冻结' : k.mode === 'READ_ONLY' ? '只读' : '全停'}{k.scope === 'global' ? '（全局）' : ''}</Badge></div>)}
        <p className="mt-1 text-xs text-ink-3">{item.projectCount ?? 0} 个项目 · {item.taskCount ?? 0} 个任务</p>
      </ListItemButton>)}</div></Card>
      {line && <div className="space-y-gap">
        <Card title={line.name} subtitle={line.description || '尚未填写业务目标'} actions={<Button size="sm" onClick={() => setLineForm(line)}>编辑产品线</Button>}>
          <div className="flex flex-wrap gap-4 text-sm text-ink-2"><span>负责人：{line.owner || '未设置'}</span><span>自治上限：{line.autonomy}</span><span>累计成本：${(line.spentUsd ?? 0).toFixed(2)}</span></div>
        </Card>
        <LinePipeline key={line.id} lineId={line.id} />
        <div className="flex items-center justify-between"><h2 className="font-bold">旗下项目</h2><Button icon="plus" disabled={line.state === 'ARCHIVED'} onClick={() => setProjectForm(true)}>新增项目</Button></div>
        <div className="grid gap-gap xl:grid-cols-2">{projects.filter(p => p.productLineId === line.id).map(p => <Card key={p.id} title={p.name} subtitle={p.config.repo ?? '未绑定仓库'} actions={<Badge tone={p.paused ? 'warn' : 'success'}>{p.paused && p.state === 'ACTIVE' ? '随产品线暂停' : catalogStateName(p.state)}</Badge>}>
          <p className="mb-3 text-sm text-ink-3">{p.description || p.config.technology}</p><div className="mb-3 flex gap-4 text-sm"><span>{p.taskCount ?? 0} 个任务</span><span>{p.activeTasks ?? 0} 个进行中</span><span>${(p.spentUsd ?? 0).toFixed(2)}</span></div>
          <Button size="sm" onClick={() => onProject(p.id)}>进入项目</Button>
        </Card>)}</div>
        {!projects.some(p => p.productLineId === line.id) && <Card><EmptyState title="这条产品线还没有项目" hint="点击“新增项目”连接一个仓库；也可以到「集成」页直接从已连接的 GitHub 里选仓库。" action={<AppLink className="text-primary-ink underline" href="/integrations">去集成页选仓库</AppLink>} /></Card>}
      </div>}
    </div>}
    {lineForm && <LineForm line={lineForm === 'new' ? undefined : lineForm} onClose={() => setLineForm(null)} onSaved={saved} />}
    {projectForm && line && <ProjectForm lineId={line.id} onClose={() => setProjectForm(false)} onSaved={saved} />}
  </div>;
}

export function BudgetFields({ budget, onChange }: { budget: BudgetLimits; onChange: (budget: BudgetLimits) => void }) {
  return <div className="grid gap-3 md:grid-cols-3">{([['dailyUsd', '每日金额（USD）'], ['dailyTokens', '每日 Token'], ['dailyCiMinutes', '每日 CI 分钟']] as const).map(([key, label]) => <Field key={key} label={label} hint="留空继承默认上限">{id => <Input id={id} type="number" min="0.01" step="any" value={budget[key] ?? ''} onChange={e => onChange({ ...budget, [key]: e.target.value ? Number(e.target.value) : null })} />}</Field>)}</div>;
}
function LineForm({ line, onClose, onSaved }: { line?: LineMeta; onClose: () => void; onSaved: () => Promise<unknown> }) {
  const [name, setName] = useState(line?.name ?? ''); const [description, setDescription] = useState(line?.description ?? ''); const [owner, setOwner] = useState(line?.owner ?? '');
  const [autonomy, setAutonomy] = useState(line?.autonomy ?? 'L1'); const [state, setState] = useState<CatalogState>(line?.state ?? 'ACTIVE');
  const [budget, setBudget] = useState<BudgetLimits>(line?.config.budget ?? { dailyUsd: null, dailyTokens: null, dailyCiMinutes: null }); const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const save = async () => { setBusy(true); try { await api(line ? `/api/product-lines/${line.id}` : '/api/product-lines', { method: line ? 'PUT' : 'POST', body: { name, description, owner, autonomy, state, budget } }); await onSaved(); } catch (e: any) { setError(e.message); } finally { setBusy(false); } };
  return <Modal title={line ? '编辑产品线' : '新增产品线'} onClose={onClose} footer={<><Button onClick={onClose}>取消</Button><Button variant="primary" loading={busy} disabled={!name.trim()} onClick={save}>保存</Button></>}>
    <div className="space-y-3"><Field label="产品线名称">{id => <Input id={id} data-autofocus value={name} onChange={e => setName(e.target.value)} />}</Field><Field label="业务目标">{id => <Textarea id={id} value={description} onChange={e => setDescription(e.target.value)} />}</Field><Field label="负责人">{id => <Input id={id} value={owner} onChange={e => setOwner(e.target.value)} />}</Field>
      <div className="grid grid-cols-2 gap-3"><Field label="自治上限">{id => <Select id={id} value={autonomy} onChange={e => setAutonomy(e.target.value as typeof autonomy)}>{['L1', 'L2', 'L3'].map(x => <option key={x}>{x}</option>)}</Select>}</Field>{line && <Field label="状态">{id => <Select id={id} value={state} onChange={e => setState(e.target.value as CatalogState)}>{['ACTIVE', 'PAUSED', 'ARCHIVED'].map(x => <option key={x} value={x}>{catalogStateName(x)}</option>)}</Select>}</Field>}</div>
      <BudgetFields budget={budget} onChange={setBudget} />{error && <Callout tone="danger">{error}</Callout>}
    </div>
  </Modal>;
}

export function ProjectForm({ lineId, project, onClose, onSaved }: { lineId: string; project?: ProjectDTO; onClose: () => void; onSaved: () => Promise<unknown> }) {
  const [name, setName] = useState(project?.name ?? ''); const [description, setDescription] = useState(project?.description ?? ''); const [owner, setOwner] = useState(project?.owner ?? '');
  const [state, setState] = useState<CatalogState>(project?.state ?? 'ACTIVE'); // 项目可以不绑仓库（repo=null，#18）：表单里统一当空字符串，保存时空串 = 不绑定
  const [config, setConfig] = useState<ProjectConfig>(() => project ? { ...structuredClone(project.config), repo: project.config.repo ?? '', baseBranch: project.config.baseBranch ?? 'main' } : defaultProjectConfig());
  const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const [detecting, setDetecting] = useState(false); const [suggestion, setSuggestion] = useState<StackSuggestion | null>(null); const [detectErr, setDetectErr] = useState('');
  const detect = async () => { if (!(config.repo ?? '').trim()) return; setDetecting(true); setDetectErr(''); try { const r: any = await api(`/api/integrations/github/detect?repo=${encodeURIComponent((config.repo ?? '').trim().replace(/^https:\/\/github\.com\//, '').replace(/\.git$/, '').replace(/\/$/, ''))}&branch=${encodeURIComponent(config.baseBranch)}`, { method: 'POST' }); setSuggestion(r.suggestion); } catch (e: any) { setDetectErr(e.message); } finally { setDetecting(false); } };
  const change = <K extends keyof ProjectConfig>(key: K, value: ProjectConfig[K]) => setConfig(c => ({ ...c, [key]: value }));
  const save = async () => { setBusy(true); try { await api(project ? `/api/projects/${project.id}` : `/api/product-lines/${lineId}/projects`, { method: project ? 'PUT' : 'POST', body: { name, description, owner, state, config } }); await onSaved(); } catch (e: any) { setError(e.message); } finally { setBusy(false); } };
  return <Modal title={project ? '编辑项目' : '新增项目'} width={820} onClose={onClose} footer={<><Button onClick={onClose}>取消</Button><Button variant="primary" loading={busy} disabled={!name.trim()} onClick={save}>保存项目</Button></>}>
    <div className="space-y-5">
      <div className="grid gap-3 md:grid-cols-2"><Field label="项目名称">{id => <Input id={id} data-autofocus value={name} onChange={e => setName(e.target.value)} placeholder="例如 LMS" />}</Field><Field label="GitHub 仓库">{id => <Input id={id} value={config.repo ?? ''} onChange={e => change('repo', e.target.value)} onBlur={() => { if (!project && isUndetected(config.technology) && (config.repo ?? '').trim()) detect(); }} placeholder="可留空（暂不绑定）；owner/repo 或仓库地址" />}</Field><Field label="负责人">{id => <Input id={id} value={owner} onChange={e => setOwner(e.target.value)} />}</Field>{project && <Field label="状态">{id => <Select id={id} value={state} onChange={e => setState(e.target.value as CatalogState)}>{['ACTIVE', 'PAUSED', 'ARCHIVED'].map(x => <option key={x} value={x}>{catalogStateName(x)}</option>)}</Select>}</Field>}</div>
      <Field label="项目说明">{id => <Textarea id={id} rows={2} value={description} onChange={e => setDescription(e.target.value)} />}</Field>
      <Field label="发布目标" hint="变更会作用于未完成任务；已停在待配置状态的任务需手动恢复。">{id => <Select id={id} value={config.releaseTarget ?? 'github_release'} onChange={e => change('releaseTarget', e.target.value as ProjectConfig['releaseTarget'])}><option value="github_release">GitHub Release（仅发布仓库版本）</option><option value="wechat_miniprogram">微信小程序</option></Select>}</Field>
      <Callout tone={config.releaseTarget === 'wechat_miniprogram' ? 'warn' : 'info'}>{config.releaseTarget === 'wechat_miniprogram' ? '微信小程序发布能力尚未接入。任务会在合并前停在「待配置发布」，保留已有进度；不会把 GitHub Release 当作小程序上线。' : '只创建并校验 GitHub Release，完成后显示「版本已发布」。实际应用上线需单独接入并验证。'}</Callout>
      <div className="grid gap-3 md:grid-cols-3"><Field label="目标分支">{id => <Input id={id} value={config.baseBranch} onChange={e => change('baseBranch', e.target.value)} />}</Field><Field label="CI 文件">{id => <Input id={id} value={config.workflow} onChange={e => change('workflow', e.target.value)} />}</Field><Field label="技术栈识别">{() => <Button className="w-full" icon="refresh" loading={detecting} disabled={!(config.repo ?? '').trim()} onClick={detect} data-testid="detect-stack">自动识别</Button>}</Field></div>
      {detectErr && <Callout tone="danger" onClose={() => setDetectErr('')}>{detectErr}</Callout>}
      {suggestion && <Callout tone="info" icon="check"><StackSuggestionView s={suggestion} /><div className="mt-2 flex gap-2"><Button size="sm" variant="primary" onClick={() => { setConfig(c => applySuggestion(c, suggestion)); setSuggestion(null); }} data-testid="apply-stack">应用识别结果</Button><Button size="sm" onClick={() => setSuggestion(null)}>忽略</Button></div></Callout>}
      <Field label="技术栈与约定">{id => <Textarea id={id} rows={2} value={config.technology} onChange={e => change('technology', e.target.value)} />}</Field>
      <Field label="开发方式" hint="只有项目负责人 / 管理员能改。在途任务下一轮开发生效；单个任务可以在任务信息里另选。">{() => <DevModeChoice name="project-dev-mode" value={config.devMode ?? 'sandbox'} onChange={v => change('devMode', v === 'legacy' ? 'legacy' : 'sandbox')} />}</Field>
      <Field label="空仓库初始化" hint="管理员授权后，平台会在开发前创建固定 README 首次提交，再继续任务。目标分支须与 GitHub 默认分支一致。">{id => <label className="text-sm" htmlFor={id}><Checkbox id={id} className="mr-2 inline-grid align-middle" checked={config.autoInitializeRepository === true} onCheckedChange={v => change('autoInitializeRepository', v === true)} />允许自动初始化空仓库</label>}</Field>
      <div className="space-y-2"><div className="flex items-center justify-between"><b className="text-sm">项目模块与验证方式</b><Button size="sm" onClick={() => change('modules', [...config.modules, { name: '', path: '', language: '', testCommand: '', buildCommand: '' }])}>添加模块</Button></div>{config.modules.map((module, index) => <div key={index} className="grid gap-2 border border-line p-3 md:grid-cols-2">{([['name', '模块名称'], ['path', '模块目录'], ['language', '语言／框架'], ['testCommand', '测试方式'], ['buildCommand', '构建方式']] as const).map(([key, label]) => <Field key={key} label={label}>{id => <Input id={id} value={module[key]} onChange={e => change('modules', config.modules.map((m, i) => i === index ? { ...m, [key]: e.target.value } : m))} />}</Field>)}<Button size="sm" onClick={() => change('modules', config.modules.filter((_, i) => i !== index))}>移除模块</Button></div>)}</div>
      <div className="grid gap-3 md:grid-cols-2"><Field label="允许修改的目录" hint="逗号分隔，相对目录以 / 结尾">{id => <Input id={id} value={config.allowedPaths.join(', ')} onChange={e => change('allowedPaths', e.target.value.split(',').map(x => x.trim()))} />}</Field><Field label="允许的文件类型" hint="逗号分隔，不带点">{id => <Input id={id} value={config.allowedExtensions.join(', ')} onChange={e => change('allowedExtensions', e.target.value.split(',').map(x => x.trim()))} />}</Field></div>
      <div className="grid gap-3 md:grid-cols-2"><Field label="服务端环境变量名" hint="逗号分隔，只填写变量名；不填写密钥值">{id => <Input id={id} value={(config.allowedEnvNames ?? []).join(', ')} onChange={e => change('allowedEnvNames', e.target.value.split(',').map(x => x.trim()).filter(Boolean))} />}</Field><Field label="服务端配置入口目录" hint="允许目录内的相对目录，以 / 结尾；已有任务需明确调整规划">{id => <Input id={id} value={(config.serverEnvPaths ?? []).join(', ')} onChange={e => change('serverEnvPaths', e.target.value.split(',').map(x => x.trim()).filter(Boolean))} />}</Field></div>
      <div className="grid gap-3 md:grid-cols-3"><Field label="项目自治等级">{id => <Select id={id} value={config.autonomy} onChange={e => change('autonomy', e.target.value as ProjectConfig['autonomy'])}>{['L1', 'L2', 'L3'].map(x => <option key={x}>{x}</option>)}</Select>}</Field><Field label="同时执行任务数">{id => <Input id={id} type="number" min={1} max={32} value={config.maxConcurrentTasks} onChange={e => change('maxConcurrentTasks', Number(e.target.value))} />}</Field><Field label="Issue 收录标签">{id => <Input id={id} value={config.intakeLabel} onChange={e => change('intakeLabel', e.target.value)} />}</Field></div>
      <BudgetFields budget={config.budget} onChange={value => change('budget', value)} />
      <div className="space-y-3 border-t border-line pt-3"><b className="text-sm">需求来源</b><div className="grid gap-2 text-sm md:grid-cols-2">{([['intake', '收录 GitHub Issues']] as const).map(([key, label]) => <label key={key}><Checkbox className="mr-2 inline-grid align-middle" checked={config[key]} onCheckedChange={v => change(key, v === true)} />{label}</label>)}{([['enabled', '定期发现需求'], ['source', '分析源码'], ['testFailures', '收录主干 CI 失败'], ['autoCreateTasks', '低风险候选自动进入任务池']] as const).map(([key, label]) => <label key={key}><Checkbox className="mr-2 inline-grid align-middle" checked={config.discovery[key]} onCheckedChange={v => change('discovery', { ...config.discovery, [key]: v === true })} />{label}</label>)}</div>
        <div className="grid gap-3 md:grid-cols-2"><Field label="扫描间隔（分钟）">{id => <Input id={id} type="number" min={5} value={config.discovery.intervalMinutes} onChange={e => change('discovery', { ...config.discovery, intervalMinutes: Number(e.target.value) })} />}</Field><Field label="源码扫描目录">{id => <Input id={id} value={config.discovery.roots.join(', ')} onChange={e => change('discovery', { ...config.discovery, roots: e.target.value.split(',').map(x => x.trim()) })} />}</Field></div>
      </div>{error && <Callout tone="danger">{error}</Callout>}
    </div>
  </Modal>;
}
