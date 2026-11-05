import { AppLink } from '@/components/AppLink';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, fmtFull, type Meta } from '../api';
import { LIMIT_FIELDS, POLICIES, TASK_FIELDS, levelText, parseNum, pctText, previewImpact, validateLimits, validateTask, type Limits, type Scope } from '../lib/budget';
import { Badge, Button, Callout, Card, Drawer, Effect, EmptyState, Field, FormBar, InfoTip, Input, OptionCard, PageHeader, PageSkeleton, Progress, Table, Term } from '../ui';
import { RadioGroup } from '@/components/ui/radio-group';
import { TaskSwitchDefaults } from '../components/TaskSwitchDefaults';

const levelTone = (l: string) => (l === 'hard' ? 'danger' : l === 'soft' ? 'warn' : 'success') as 'danger' | 'warn' | 'success';
type Target = { scope: Scope; id?: string; name: string };

export default function Budget({ meta, onChanged }: { meta: Meta | null; onChanged?: () => void }) {
  const [b, setB] = useState<any>(null); const [sum, setSum] = useState<any>(null); const [cfg, setCfg] = useState<any>(null); const [changes, setChanges] = useState<any[]>([]); const [err, setErr] = useState('');
  const [edit, setEdit] = useState<Target | null>(null); const [top, setTop] = useState<{ id: string; name: string } | null>(null); const [notice, setNotice] = useState('');
  const load = useCallback(async () => {
    try { const [bb, ss, cc, ch] = await Promise.all([api('/api/budget'), api('/api/budget/summary'), api('/api/config'), api('/api/budget/changes')]); setB(bb); setSum(ss); setCfg(cc.config); setChanges(ch); setErr(''); } catch (e: any) { setErr(e.message); }
  }, []);
  useEffect(() => { load(); }, [load]);
  if (err && !b) return <Callout tone="danger">{err}</Callout>;
  if (!b || !sum || !cfg) return <PageSkeleton title="预算" />;
  const g = b.global, lim = g.limits; const soft = cfg.budgets.line.softRatio as number;
  const lineName = (id: string) => meta?.lines.find(l => l.id === id)?.name ?? id;
  const saved = async () => { setEdit(null); setNotice('已保存，立即生效，并写入了变更历史'); await load(); onChanged?.(); };
  const rows = (u: any, l: any): [string, number, number, string][] => [['金额', u.usd, l.dailyUsd, '$'], ['Token', u.tokens, l.dailyTokens, ''], ['CI 分钟', u.ci, l.dailyCiMinutes, '']];
  const bars = (u: any, l: any, name: string) => <div className="space-y-3">{rows(u, l).map(([k, used, max, pre]) => <div key={k}><div className="mb-1 flex justify-between text-xs text-ink-2"><span>{k}</span><span className="tabular">{pre}{Number(used.toFixed?.(2) ?? used).toLocaleString()} / {pre}{Number(max).toLocaleString()}</span></div><Progress label={`${name}${k}`} value={used} max={max} /></div>)}</div>;
  return (
    <div className="space-y-gap" data-testid="budget">
      <PageHeader title="预算" desc={`${b.day} · 费用逐层汇总：任务 → 项目 → 产品线 → 全局；下层额度不能超过上层`} />
      {notice && <Callout tone="success" icon="check" onClose={() => setNotice('')}>{notice}</Callout>}
      <Callout tone={levelTone(sum.level)} icon={sum.level === 'ok' ? 'coins' : 'alert'}><span data-testid="budget-summary"><b>{sum.text}</b>{' '}<Badge tone={levelTone(sum.level)} dot>{levelText(sum.level)}</Badge></span></Callout>
      {g.status.level === 'hard' && <Callout tone="danger">全局预算已触顶，系统已自动暂停执行：{g.status.reason}。调高额度后会自动恢复。</Callout>}
      <div className="grid gap-gap lg:grid-cols-3">
        <Card title="全局（今日）" actions={<Button size="sm" icon="sliders" onClick={() => setEdit({ scope: 'global', name: '全局' })} data-testid="edit-global">编辑额度</Button>} className="lg:col-span-2">
          {bars(g.daily, lim, '全局')}
          <p className="mt-3 text-xs text-ink-3">本月金额 ${g.monthly.usd.toFixed(2)} / ${lim.monthlyUsd}{g.status.monthlyAlert ? '（已超告警线）' : ''}。省钱线 {pctText(soft)}：超过后自动改用低成本模型。</p>
        </Card>
        <Card title="单任务额度" actions={<Button size="sm" icon="sliders" onClick={() => setEdit({ scope: 'task', name: '单任务' })} data-testid="edit-task">编辑</Button>}>
          <dl className="space-y-1 text-sm"><div className="flex justify-between"><dt className="text-ink-3">金额</dt><dd className="tabular">${b.task.usd}</dd></div><div className="flex justify-between"><dt className="text-ink-3">Token</dt><dd className="tabular">{Number(b.task.tokens).toLocaleString()}</dd></div><div className="flex justify-between"><dt className="text-ink-3">CI 分钟</dt><dd className="tabular">{b.task.ciMinutes}</dd></div><div className="flex justify-between"><dt className="text-ink-3">最多返修</dt><dd className="tabular">{b.task.iterations} 轮</dd></div></dl>
          <p className="mt-2 text-xs text-ink-3">只对之后新建的任务生效。</p>
        </Card>
      </div>
      <OnExceedCard policy={sum.policy} soft={soft} onSaved={async () => { setNotice('触顶行为已保存，立即生效'); await load(); }} />
      <TaskSwitchDefaults cfg={cfg} meta={meta} onSaved={async (m) => { setNotice(m); await load(); onChanged?.(); }} />
      <div className="grid gap-gap lg:grid-cols-2">
        {b.lines.length === 0 && <EmptyState title="还没有产品线" hint="先到「产品线与项目」创建一个产品线，才能为它分配额度。" action={<AppLink className="text-primary-ink underline" href="/catalog">去创建</AppLink>} />}
        {b.lines.map((l: any) => <Card key={l.id} title={<>产品线 · {lineName(l.id)}</>} actions={<div className="flex items-center gap-2"><Badge tone={levelTone(l.status)} dot>{levelText(l.status)}</Badge><Button size="sm" icon="sliders" onClick={() => setEdit({ scope: 'line', id: l.id, name: lineName(l.id) })} data-testid={`edit-line-${l.id}`}>编辑额度</Button></div>}>{bars(l.daily, l.limits, lineName(l.id))}</Card>)}
        {(b.projects ?? []).map((p: any) => <Card key={p.id} title={<AppLink href={`/project/${p.id}`} className="hover:text-primary-ink">项目 · {p.name}</AppLink>} actions={<div className="flex items-center gap-2"><Badge tone={levelTone(p.status)} dot>{levelText(p.status)}</Badge><Button size="sm" onClick={() => setTop({ id: p.id, name: p.name })} data-testid={`top-${p.id}`}>谁花得最多</Button><Button size="sm" icon="sliders" onClick={() => setEdit({ scope: 'project', id: p.id, name: p.name })} data-testid={`edit-project-${p.id}`}>编辑额度</Button></div>}>{bars(p.daily, p.limits, p.name)}</Card>)}
      </div>
      <Card title="变更历史" subtitle="谁、什么时候、改了什么（当前版本无登录，不区分具体用户）" flush>
        <Table caption="预算变更历史" dense rows={changes} rowKey={(c: any) => c.at + JSON.stringify(c.payload).length} empty={<EmptyState title="还没有变更" hint="修改任何额度后，这里会留下记录。" />} columns={[
          { key: 't', header: '时间（UTC）', render: (c: any) => <span className="tabular text-xs">{fmtFull(c.at)}</span> },
          { key: 'w', header: '谁', render: (c: any) => <span className="text-xs">{c.payload?.by ?? c.actor}</span> },
          { key: 'c', header: '改了什么', render: (c: any) => <span className="text-xs">{describeChange(c.payload, lineName, meta)}</span> },
        ]} />
      </Card>
      {edit && <EditDrawer target={edit} b={b} cfg={cfg} meta={meta} onClose={() => setEdit(null)} onSaved={saved} />}
      {top && <TopTasks target={top} onClose={() => setTop(null)} />}
    </div>
  );
}

function describeChange(p: any, lineName: (s: string) => string, meta: Meta | null): string {
  if (!p) return '-'; const parts: string[] = [];
  if (p.scope === 'task_switches') {
    const who = p.target === 'project' ? `项目「${meta?.projects.find(x => x.id === p.id)?.name ?? p.id}」` : '默认';
    const v = (x: any, on: string, off: string) => (x == null ? '跟随默认' : x ? on : off); const a = p.after ?? {}, bf = p.before ?? {};
    if (a.unlimited !== bf.unlimited) parts.push(`不限预算 ${v(bf.unlimited, '打开', '关闭')} → ${v(a.unlimited, '打开', '关闭')}`);
    if (a.degrade !== bf.degrade) parts.push(`省钱模式 ${v(bf.degrade, '打开', '关闭')} → ${v(a.degrade, '打开', '关闭')}`);
    return `${who}任务预算开关：${parts.join('；') || '无实质变化'}`;
  }
  const scope = p.scope === 'global' ? '全局' : p.scope === 'line' ? `产品线「${lineName(p.id)}」` : p.scope === 'project' ? `项目「${meta?.projects.find(x => x.id === p.id)?.name ?? p.id}」` : p.scope === 'task' ? '单任务额度' : '预算设置';
  const names: Record<string, string> = { dailyUsd: '日金额', dailyTokens: '日 Token', dailyCiMinutes: '日 CI 分钟', monthlyUsd: '月金额', usd: '金额', tokens: 'Token', ciMinutes: 'CI 分钟', iterations: '返修轮数' };
  const a = p.after ?? {}, bf = p.before ?? {};
  for (const k of Object.keys(names)) if (k in a || k in bf) if (a[k] !== bf[k]) parts.push(`${names[k]} ${bf[k] ?? '默认'} → ${a[k] ?? '默认'}`);
  if (p.policy) parts.push(`触顶行为 ${POLICIES.find(x => x.id === p.policy.from)?.name ?? p.policy.from} → ${POLICIES.find(x => x.id === p.policy.to)?.name ?? p.policy.to}`);
  if (p.softRatio != null) parts.push(`省钱线 → ${pctText(p.softRatio)}`);
  return `${scope}：${parts.join('；') || '无实质变化'}`;
}

function OnExceedCard({ policy, soft, onSaved }: { policy: string; soft: number; onSaved: () => Promise<void> }) {
  const [p, setP] = useState(policy); const [s, setS] = useState(String(Math.round(soft * 100))); const [busy, setBusy] = useState(false); const [errs, setErrs] = useState<string[]>([]);
  useEffect(() => { setP(policy); setS(String(Math.round(soft * 100))); }, [policy, soft]);
  const sv = Number(s); const bad = !Number.isFinite(sv) || sv < 10 || sv > 100; const dirty = p !== policy || (!bad && Math.abs(sv / 100 - soft) > 1e-9);
  const save = async () => { setBusy(true); try { await api('/api/budget/limits', { method: 'PUT', body: { scope: 'policy', onExceed: p, softRatio: sv / 100 } }); setErrs([]); await onSaved(); } catch (e: any) { setErrs([e.message]); } finally { setBusy(false); } };
  return <Card title={<span>用量到顶时怎么办 <InfoTip title="触顶行为" text="决定额度快用完或用完之后，7x24 如何自动处理。全局生效。" /></span>} subtitle="选一个行为；推荐保持默认">
    <RadioGroup className="grid gap-3 md:grid-cols-3" aria-label="触顶行为" value={p} onValueChange={(v) => setP(v as typeof p)}>{POLICIES.map(o => <OptionCard key={o.id} value={o.id} data-testid={`policy-${o.id}`} selected={p === o.id}><div className="flex items-center gap-2 text-sm font-bold">{o.name}{o.recommended && <Badge tone="primary">推荐</Badge>}</div><p className="mt-1 text-xs text-ink-3">{o.text}</p></OptionCard>)}</RadioGroup>
    <div className="mt-3 max-w-xs"><Field label={<Term k="soft">省钱线（占上限的百分比）</Term>}>{id => <Input id={id} className="text-right tabular" inputMode="numeric" value={s} onChange={e => setS(e.target.value)} />}</Field><Effect>调低会更早改用便宜模型，省钱但质量可能下降；10%–100%，所有产品线和项目共用。</Effect></div>
    <FormBar dirty={dirty} busy={busy} onSave={save} onReset={() => { setP('degrade'); setS('80'); }} errors={[...(bad ? ['省钱线应在 10%–100% 之间'] : []), ...errs]} resetLabel="恢复推荐值" />
  </Card>;
}

function EditDrawer({ target, b, cfg, meta, onClose, onSaved }: { target: Target; b: any; cfg: any; meta: Meta | null; onClose: () => void; onSaved: () => Promise<void> }) {
  const isTask = target.scope === 'task'; const fields: readonly { key: string; label: string; unit: string; example: string }[] = isTask ? TASK_FIELDS : [...LIMIT_FIELDS, ...(target.scope === 'global' ? [{ key: 'monthlyUsd', label: '月金额', unit: '美元', example: '例如 1000 = 每月最多花 1000 美元' }] : [])];
  const current = useMemo<Record<string, number | null>>(() => {
    if (isTask) return { ...b.task };
    if (target.scope === 'global') return { ...b.global.limits };
    const o = target.scope === 'line' ? meta?.lines.find(l => l.id === target.id)?.config.budget : meta?.projects.find(p => p.id === target.id)?.config.budget;
    return { dailyUsd: o?.dailyUsd ?? null, dailyTokens: o?.dailyTokens ?? null, dailyCiMinutes: o?.dailyCiMinutes ?? null };
  }, [target, b, meta, isTask]);
  const effective = useMemo<Record<string, number>>(() => target.scope === 'line' ? (b.lines.find((l: any) => l.id === target.id)?.limits ?? {}) : target.scope === 'project' ? (b.projects.find((p: any) => p.id === target.id)?.limits ?? {}) : current as any, [target, b, current]);
  const [vals, setVals] = useState<Record<string, string>>(() => Object.fromEntries(fields.map(f => [f.key, current[f.key] == null ? '' : String(current[f.key])])));
  const [remote, setRemote] = useState<any>(null); const [busy, setBusy] = useState(false); const [saveErr, setSaveErr] = useState('');
  const parsed: Record<string, number | null | undefined> = Object.fromEntries(fields.map(f => [f.key, parseNum(vals[f.key])]));
  const invalid = Object.entries(parsed).filter(([, v]) => v === undefined).map(([k]) => `${fields.find(f => f.key === k)!.label}必须是数字`);
  const nextLimits: Limits = { dailyUsd: parsed.dailyUsd as any, dailyTokens: parsed.dailyTokens as any, dailyCiMinutes: parsed.dailyCiMinutes as any, monthlyUsd: parsed.monthlyUsd as any };
  const gl = b.global.limits as Limits;
  const parent: Limits | undefined = target.scope === 'line' ? gl : target.scope === 'project' ? (b.lines.find((l: any) => l.id === meta?.projects.find(p => p.id === target.id)?.productLineId)?.limits ?? gl) : undefined;
  const children = target.scope === 'global' ? [...(meta?.lines ?? []).map(l => ({ name: l.name, limits: (l.config.budget ?? {}) as Limits })), ...(meta?.projects ?? []).filter(p => p.state !== 'ARCHIVED').map(p => ({ name: p.name, limits: (p.config.budget ?? {}) as Limits }))] : target.scope === 'line' ? (meta?.projects ?? []).filter(p => p.productLineId === target.id && p.state !== 'ARCHIVED').map(p => ({ name: p.name, limits: (p.config.budget ?? {}) as Limits })) : [];
  const local = isTask ? validateTask(Object.fromEntries(Object.entries(parsed).filter(([, v]) => v != null)) as any, gl) : validateLimits(target.scope as any, nextLimits, parent, children);
  const body = () => ({ scope: target.scope, id: target.id, values: Object.fromEntries(fields.filter(f => parsed[f.key] !== undefined && (parsed[f.key] != null || (!isTask && target.scope !== 'global'))).map(f => [f.key, parsed[f.key]])) });
  const dirty = fields.some(f => (parsed[f.key] ?? null) !== (current[f.key] ?? null));
  const errors = [...invalid, ...local];
  useEffect(() => { if (!dirty || errors.length) { setRemote(null); return; } const t = setTimeout(() => { api('/api/budget/preview', { method: 'POST', body: body() }).then(setRemote).catch(() => setRemote(null)); }, 350); return () => clearTimeout(t); }, [vals]); // eslint-disable-line
  const used = target.scope === 'global' ? b.global.daily : target.scope === 'line' ? b.lines.find((l: any) => l.id === target.id)?.daily : target.scope === 'project' ? b.projects.find((p: any) => p.id === target.id)?.daily : null;
  const localImpact = used && !isTask ? previewImpact(used, { dailyUsd: nextLimits.dailyUsd ?? effective.dailyUsd, dailyTokens: nextLimits.dailyTokens ?? effective.dailyTokens, dailyCiMinutes: nextLimits.dailyCiMinutes ?? effective.dailyCiMinutes }, cfg.budgets.line.softRatio, target.scope !== 'global') : null;
  const save = async () => { setBusy(true); setSaveErr(''); try { await api('/api/budget/limits', { method: 'PUT', body: body() }); await onSaved(); } catch (e: any) { setSaveErr(e.message); } finally { setBusy(false); } };
  const blocking = [...errors, ...(remote && remote.ok === false ? remote.errors : [])];
  const inheritHint = !isTask && target.scope !== 'global';
  return <Drawer title={`编辑额度 · ${target.name}`} onClose={onClose} width={520}>
    <div className="space-y-4" data-testid="edit-drawer">
      <p className="text-xs text-ink-3">{isTask ? '单任务额度只对之后新建的任务生效，已有任务保持原来的额度。' : inheritHint ? `留空 = 沿用上级额度。额度不能超过${target.scope === 'line' ? '全局' : '所属产品线'}上限。` : '保存后立即生效。产品线和项目设置的额度不能高于这里。'}</p>
      {fields.map(f => <div key={f.key}><Field label={<span>{f.label}（{f.unit}）</span>} hint={f.example}>{id => <Input id={id} inputMode="decimal" className="text-right tabular" placeholder={inheritHint && effective[f.key] != null ? `沿用 ${effective[f.key]}` : undefined} value={vals[f.key]} onChange={e => setVals(v => ({ ...v, [f.key]: e.target.value }))} data-testid={`field-${f.key}`} />}</Field></div>)}
      {dirty && (remote?.impact?.length ? <div className="space-y-1" data-testid="impact">{remote.impact.map((r: any) => <Callout key={r.label} tone={r.status === 'hard' ? 'danger' : r.status === 'soft' ? 'warn' : 'info'}><b>{r.label}：</b>{r.text}</Callout>)}</div> : localImpact && errors.length === 0 ? <Callout tone={localImpact.level === 'hard' ? 'danger' : localImpact.level === 'soft' ? 'warn' : 'info'}><span data-testid="impact">{localImpact.text}</span></Callout> : null)}
      {remote?.warnings?.map((w: string) => <Callout key={w} tone="warn">{w}</Callout>)}
      <FormBar dirty={dirty} busy={busy} onSave={save} onReset={() => setVals(Object.fromEntries(fields.map(f => [f.key, current[f.key] == null ? '' : String(current[f.key])])))} resetLabel="还原" saveLabel="保存并立即生效" errors={[...blocking, ...(saveErr ? [saveErr] : [])]} extra={<Button variant="ghost" onClick={onClose}>取消</Button>} />
    </div>
  </Drawer>;
}

function TopTasks({ target, onClose }: { target: { id: string; name: string }; onClose: () => void }) {
  const [rows, setRows] = useState<any[] | null>(null);
  useEffect(() => { api(`/api/budget/projects/${target.id}/tasks`).then(setRows).catch(() => setRows([])); }, [target.id]);
  return <Drawer title={`「${target.name}」哪些任务花得最多`} onClose={onClose} width={620}>
    {!rows ? <PageSkeleton /> : <Table caption="按费用排序的任务" dense rows={rows} rowKey={(r: any) => r.id} empty={<EmptyState title="这个项目还没有产生费用" hint="创建任务并运行后，这里会按费用从高到低列出。" />} columns={[
      { key: 't', header: '任务', render: (r: any) => <AppLink className="text-primary-ink hover:underline" href={`/task/${r.id}`}>{r.title}</AppLink> },
      { key: 's', header: '状态', render: (r: any) => <Badge>{r.status}</Badge> },
      { key: 'u', header: '费用', align: 'right', render: (r: any) => <span className="tabular">${Number(r.spent_usd).toFixed(3)}</span> },
      { key: 'k', header: 'Token', align: 'right', render: (r: any) => <span className="tabular">{Number(r.spent_tokens).toLocaleString()}</span> },
    ]} />}
  </Drawer>;
}
