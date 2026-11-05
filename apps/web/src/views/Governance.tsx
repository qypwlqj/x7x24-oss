import { AppLink } from '@/components/AppLink';
import { useEffect, useMemo, useState } from 'react';
import { api, type Meta } from '../api';
import { Badge, Button, Callout, Card, Effect, EmptyState, Field, FormBar, Input, PageHeader, PageSkeleton, Select, Table, Term, Textarea, Toggle } from '../ui';
import { CodeReviewFindings, type CodeReviewData } from '../components/CodeReviewCard';
import { Disclosure } from '@/components/ui/collapsible';

const get = (o: any, p: string) => p.split('.').reduce((a, k) => a?.[k], o);
function patchOf(entries: [string, any][]) { const out: any = {}; for (const [p, v] of entries) { let t = out; const ks = p.split('.'); ks.slice(0, -1).forEach((k) => (t = t[k] ??= {})); t[ks[ks.length - 1]] = v; } return out; }

interface NumDef { p: string; label: string; term?: string; step?: number; effect: string }
const COMMON: NumDef[] = [
  { p: 'thresholds.prdPass', label: 'PRD 通过分', term: 'prdPassScore', step: 0.05, effect: '调高：需求更难通过评审，更多需求被打回补充；调低：更宽松，但低质量需求可能进入开发。' },
  { p: 'thresholds.acceptancePass', label: '验收委员会通过分', term: 'acceptancePass', step: 0.05, effect: '调高：验收更严格，返修更多、成本更高；调低：更快上线，但质量风险更大。' },
  { p: 'loops.maxLoops', label: '测试返修上限', term: 'maxLoops', effect: '调高：给开发更多返修机会，但会花更多钱；调低：更快放弃，节省成本。' },
  { p: 'loops.maxRejects', label: '验收退回上限', term: 'maxRejects', effect: '调高：允许验收反复退回；调低：更快改用省钱模式或废弃。' },
];
/** 改动规模规则原文（含配置键），只放在「高级说明」里 */
const SIZE_RULES = '改动规模 = 真实改动行数 × release.sizeWeights（按路径模式 0~1 打折，先匹配先生效；不以 / 开头的模式匹配任意目录）。weightFileCount=true 时文件数也按权重折算；打折文件超过 maxExemptLines 的部分按 1 计。规划预估超过 planSplitRatio 就预先拆段；开发超限先在步骤内精简（最多 maxShrinkAttempts 次），再拆分（最多 maxSplitParts 段），最后重新规划。'; // copy-ok
const ADVANCED: NumDef[] = [
  { p: 'thresholds.priority', label: '入评审优先级阈值', term: 'priorityThreshold', step: 0.05, effect: '调高：只有高优先级需求才会进入评审。' },
  { p: 'thresholds.prdRevise', label: 'PRD 需补充下限', step: 0.05, effect: '分数介于该值和通过分之间时，需求会被要求补充而不是直接废弃。' },
  { p: 'thresholds.disagreementStd', label: '评委分歧上限', term: 'disagreement', step: 0.05, effect: '调低：评委意见稍有不同就再加一位评委，更稳但更贵。' },
  { p: 'loops.maxRollbacks', label: '撤回上线上限', term: 'maxRollbacks', effect: '调低：撤回上线几次后就直接废弃该任务。' },
  { p: 'loops.maxRequeue', label: '冷却重新入池上限', term: 'maxRequeue', effect: '调高：被废弃的任务有更多机会重新来过。' },
  { p: 'loops.cooldownDays', label: '冷却天数（指数增长）', term: 'cooldownDays', effect: '调高：废弃的任务更晚才会重新入池。' },
  { p: 'autonomy.tightenDiscards', label: '24h 自动废弃 > N 收紧', term: 'tighten', effect: '24 小时内废弃数超过 N，自治等级会自动收紧一档；调低更敏感。' },
  { p: 'autonomy.tightenRollbacks', label: '24h 回滚 > N 收紧', term: 'tighten', effect: '24 小时内回滚数超过 N，自治等级会自动收紧一档。' },
];

function NumForm({ title, subtitle, defs, cfg, onSave }: { title: string; subtitle?: string; defs: NumDef[]; cfg: any; onSave: (patch: any) => Promise<void> }) {
  const base = useMemo(() => Object.fromEntries(defs.map(d => [d.p, String(get(cfg, d.p))])), [cfg, defs]);
  const [v, setV] = useState(base); const [busy, setBusy] = useState(false); const [ok, setOk] = useState<string | null>(null); const [err, setErr] = useState('');
  useEffect(() => setV(base), [base]);
  const errors = defs.filter(d => v[d.p].trim() === '' || !Number.isFinite(Number(v[d.p])) || Number(v[d.p]) < 0).map(d => `${d.label}必须是不小于 0 的数字`);
  const dirty = defs.some(d => v[d.p] !== base[d.p]);
  const save = async () => { setBusy(true); setErr(''); try { await onSave(patchOf(defs.filter(d => v[d.p] !== base[d.p]).map(d => [d.p, Number(v[d.p])]))); setOk('已保存，立即生效并写入审计'); } catch (e: any) { setErr(e.message); } finally { setBusy(false); } };
  return <Card title={title} subtitle={subtitle}>
    <div className="grid gap-4 sm:grid-cols-2">{defs.map(d => <div key={d.p}><Field label={d.term ? <Term k={d.term}>{d.label}</Term> : d.label}>{id => <Input id={id} type="number" step={d.step ?? 1} className="text-right tabular" value={v[d.p]} onChange={e => { setV({ ...v, [d.p]: e.target.value }); setOk(null); }} />}</Field><Effect>{d.effect}</Effect></div>)}</div>
    <FormBar dirty={dirty} busy={busy} onSave={save} onReset={() => { setV(base); setOk(null); setErr(''); }} resetLabel="还原为已保存值" errors={[...errors, ...(err ? [err] : [])]} success={dirty ? null : ok} />
  </Card>;
}

export default function Governance({ meta, onChanged }: { meta: Meta | null; onChanged: () => void }) {
  const [cfg, setCfg] = useState<any>(null); const [raw, setRaw] = useState(''); const [msg, setMsg] = useState<{ ok: boolean; t: string } | null>(null); const [showAdv, setShowAdv] = useState(false);
  const load = async () => { const c = await api('/api/config'); setCfg(c.config); setRaw(JSON.stringify({ release: c.config.release, canary: c.config.canary }, null, 2)); };
  useEffect(() => { load(); }, []);
  if (!cfg) return <PageSkeleton title="治理配置" />;
  const save = async (patch: any) => { await api('/api/config', { method: 'PUT', body: patch }); await load(); onChanged(); };
  return (
    <div className="space-y-gap" data-testid="config">
      <PageHeader title="治理配置" desc="决定系统能自动做什么、做到什么程度；修改立即生效，并记入审计日志" />
      {msg && <Callout tone={msg.ok ? 'success' : 'danger'} icon={msg.ok ? 'check' : 'alert'} onClose={() => setMsg(null)}>{msg.t}</Callout>}
      <h2 className="hud-title text-base text-ink">常用设置</h2>
      <div className="grid gap-gap lg:grid-cols-2">
        <Card title={<Term k="autonomy">产品线自治等级</Term>} subtitle="决定系统能自动处理多高风险的需求">
          {!meta || meta.lines.length === 0 ? <EmptyState title="还没有产品线" hint="创建产品线后，才能设置自治等级。" action={<AppLink className="text-primary-ink underline" href="/catalog">去创建产品线</AppLink>} /> :
            <div className="space-y-3">{meta.lines.map((l) => (
              <div key={l.id} className="border border-line-strong/50 border-l-2 border-l-primary bg-raised/70 p-3">
                <div className="flex items-center justify-between gap-2"><span className="text-sm font-medium">{l.name}</span>
                  <div className="flex gap-1" role="group" aria-label={`${l.name} 自治等级`}>{(['L1', 'L2', 'L3'] as const).map((lv) => <Button key={lv} size="sm" variant={l.autonomy === lv ? 'primary' : 'secondary'} aria-pressed={l.autonomy === lv} onClick={async () => { await api(`/api/product-lines/${l.id}`, { method: 'PUT', body: { autonomy: lv } }); setMsg({ ok: true, t: `${l.name} 自治等级 → ${lv}` }); onChanged(); }}>{lv}</Button>)}</div></div>
                <Effect>L1 只自动处理低风险（文档、测试）；L2 到中风险（普通业务代码）；L3 到高风险，但评审更严、灰度更久。{l.intakePausedUntil ? ` 当前入场暂停至 ${l.intakePausedUntil.slice(5, 16)}。` : ''}</Effect>
                {(() => { const ks = (meta.killSwitches ?? []).filter((k: any) => k.scope === `line:${l.id}`); return <div className="mt-2 flex flex-wrap items-center gap-2" data-testid={`freeze-${l.id}`}>
                  {ks.length === 0 ? <Button size="sm" onClick={async () => { await api('/api/kill-switch', { method: 'POST', body: { mode: 'FREEZE_RELEASE', scope: `line:${l.id}`, reason: '看板：全线冻结' } }); setMsg({ ok: true, t: `${l.name} 已冻结发布` }); onChanged(); }}>一键<Term k="freezeRelease" bare>冻结发布</Term></Button>
                    : ks.map((k: any) => <span key={k.id} className="flex items-center gap-2"><Badge tone="danger" dot>{k.mode === 'FREEZE_RELEASE' ? '发布已冻结' : k.mode === 'READ_ONLY' ? '只读' : '全停'} · {k.source === 'auto' ? '自动' : '手动'}：{k.reason}</Badge>
                      <Button size="sm" variant="danger" onClick={async () => { try { await api(`/api/kill-switch/${k.id}`, { method: 'DELETE' }); setMsg({ ok: true, t: `${l.name} 已解除 ${k.mode}` }); onChanged(); } catch (e: any) { setMsg({ ok: false, t: '解除失败：' + e.message }); } }}>解除</Button></span>)}
                </div>; })()}
              </div>))}</div>}
        </Card>
        <NumForm title="评审与返修" subtitle="控制需求通过评审、验收的松紧，以及最多返修几次" defs={COMMON} cfg={cfg} onSave={save} />
        <CodeReviewSettings cfg={cfg} meta={meta} onSave={save} />
      </div>
      <div className="flex items-center justify-between"><h2 className="hud-title text-base text-ink">高级设置</h2><Button size="sm" aria-expanded={showAdv} onClick={() => setShowAdv(s => !s)} data-testid="toggle-advanced">{showAdv ? '收起高级设置' : '展开高级设置'}</Button></div>
      {!showAdv && <p className="text-xs text-ink-3">包括更多阈值、<Term k="forbidden" bare>禁区清单</Term>、<Term k="canary" bare>金丝雀</Term>护栏与发布窗口。一般不需要修改。</p>}
      {showAdv && <div className="grid gap-gap lg:grid-cols-2" data-testid="advanced">
        <NumForm title="更多阈值" defs={ADVANCED} cfg={cfg} onSave={save} />
        <Card title={<Term k="forbidden">禁区清单</Term>} subtitle="命中即系统直接拒绝，没有审批通道（逗号分隔）">
          <ForbiddenForm forbidden={cfg.forbidden} onSave={save} />
        </Card>
        <Card title={<Term k="canary">金丝雀护栏</Term>} subtitle="灰度发布期间自动盯的指标" flush>
          <Table caption="金丝雀指标" dense rows={cfg.canary.metrics as any[]} rowKey={(m) => m.name} columns={[
            { key: 'n', header: '指标', render: (m) => <span className="font-medium">{m.name}</span> }, { key: 'c', header: <Term k="successCondition" bare>成功条件</Term>, render: (m) => <code className="font-mono text-xs">{m.successCondition}</code> },
            { key: 'f', header: <Term k="failureLimit" bare>允许失败次数</Term>, align: 'center', render: (m) => m.failureLimit }, { key: 'i', header: '间隔×次数', align: 'center', render: (m) => `${m.interval}×${m.count}` },
          ]} />
          <p className="px-card py-3 text-xs text-ink-3">灰度档位 {cfg.canary.steps.join('% → ')}% · 每档最短观察 {cfg.canary.minObserveMinutes} 分钟（高风险 {cfg.canary.highMinObserveMinutes}）· 回滚目标 ≤ {cfg.canary.rollbackTargetMinutes} 分钟。</p>
        </Card>
        <Card title="发布时间、冻结期与灰度发布（高级）" className="lg:col-span-2" actions={<Badge tone="info">配置版本 {meta?.configVersion}</Badge>}>
          <Field label="发布与改动规模配置（JSON）" hint={<>每次提交的大小按改动行数计算，测试和文档可以打折。超过上限时，系统先精简，再拆成几段提交，最后重新规划；上限不会被自动放宽。<Disclosure className="mt-1" summaryClassName="text-xs text-ink-3" summary={<>高级说明</>}><p className="mt-1 text-xs text-ink-3">{SIZE_RULES}</p></Disclosure></>}>{(id) => <Textarea id={id} value={raw} onChange={(e) => setRaw(e.target.value)} rows={12} className="font-mono text-xs" />}</Field>
          <Effect>直接改动发布窗口和灰度参数，格式错误会被拒绝；保存后新的发布立即按新配置执行。</Effect>
          <FormBar dirty={raw !== JSON.stringify({ release: cfg.release, canary: cfg.canary }, null, 2)} onSave={async () => { try { await save(JSON.parse(raw)); setMsg({ ok: true, t: '发布配置已保存并生效' }); } catch (e: any) { setMsg({ ok: false, t: 'JSON 无效或被拒绝：' + e.message }); } }} onReset={() => setRaw(JSON.stringify({ release: cfg.release, canary: cfg.canary }, null, 2))} resetLabel="还原为已保存值" />
        </Card>
      </div>}
    </div>
  );
}

function ForbiddenForm({ forbidden, onSave }: { forbidden: Record<string, string[]>; onSave: (p: any) => Promise<void> }) {
  const base = useMemo(() => Object.fromEntries(Object.entries(forbidden).map(([k, v]) => [k, v.join(', ')])), [forbidden]);
  const [v, setV] = useState(base); const [busy, setBusy] = useState(false); const [err, setErr] = useState(''); const [ok, setOk] = useState<string | null>(null);
  useEffect(() => setV(base), [base]);
  const dirty = Object.keys(base).some(k => v[k] !== base[k]);
  const save = async () => { setBusy(true); setErr(''); try { const f: any = {}; for (const k of Object.keys(base)) if (v[k] !== base[k]) f[k] = v[k].split(',').map(x => x.trim()).filter(Boolean); await onSave({ forbidden: f }); setOk('禁区已保存并立即生效'); } catch (e: any) { setErr(e.message); } finally { setBusy(false); } };
  return <div className="space-y-3">{Object.keys(base).map(k => <Field key={k} label={k}>{id => <Input id={id} value={v[k]} onChange={e => { setV({ ...v, [k]: e.target.value }); setOk(null); }} />}</Field>)}
    <Effect>删掉一项等于放开该类操作的自动执行，请谨慎；支付、权限类建议永远保留。</Effect>
    <FormBar dirty={dirty} busy={busy} onSave={save} onReset={() => setV(base)} resetLabel="还原为已保存值" errors={err ? [err] : []} success={dirty ? null : ok} /></div>;
}

/** 逐行审查：观察期开关 + 对已有 PR 试跑 */
function CodeReviewSettings({ cfg, meta, onSave }: { cfg: any; meta: Meta | null; onSave: (p: any) => Promise<void> }) {
  const cr = cfg.codeReview ?? {}; const projects = meta?.projects ?? [];
  const [busy, setBusy] = useState(false); const [err, setErr] = useState(''); const [ok, setOk] = useState('');
  const [pid, setPid] = useState(projects[0]?.id ?? ''); const [pr, setPr] = useState(''); const [running, setRunning] = useState(false); const [res, setRes] = useState<(CodeReviewData & { seconds?: number; pr?: number }) | null>(null);
  useEffect(() => { if (!pid && projects[0]) setPid(projects[0].id); }, [projects, pid]);
  const toggle = async (v: boolean) => { setBusy(true); setErr(''); setOk(''); try { await onSave({ codeReview: { blocking: v } }); setOk(v ? '已打开：发现必须修的问题会退回修改' : '已关闭：只记录，不退回'); } catch (e: any) { setErr(e.message); } finally { setBusy(false); } };
  const trial = async () => { setRunning(true); setErr(''); setRes(null); try { setRes(await api('/api/code-review/trial', { method: 'POST', body: { projectId: pid, pr } })); } catch (e: any) { setErr(e.message); } finally { setRunning(false); } };
  return <Card title="逐行审查" subtitle="测试通过后，用开源项目 open-code-review 逐行看本次改动，给出带行号的问题（改动较大或风险中高的任务才会加这一步）" className="lg:col-span-2" data-testid="code-review-settings"
    actions={<Badge tone={cr.blocking ? 'warn' : 'info'}>{cr.blocking ? '会退回修改' : '观察期 · 只记录'}</Badge>}>
    <div className="space-y-4">
      <Toggle checked={!!cr.blocking} disabled={busy} onChange={toggle} label="发现必须修的问题时退回修改" hint="必须修：严重问题，或高级别的缺陷 / 安全问题。打开后会像测试没通过一样退回编码；关闭时只记录在任务里，方便先观察一周效果。" />
      <Effect>在 runner 沙箱里跑 open-code-review（ocr v1.13.5），由它自己挑文件、分组、复核；用的模型避开写代码的那个，没审完会换 {(cr.fallbackModels ?? ['deepseek-chat']).join('、') || '备用模型'} 接着审。单个任务用量上限 {Number(cr.maxTokens ?? 600000).toLocaleString()} Token，超出的文件标成「没审」。</Effect>
      <div className="border-t border-line pt-3">
        <p className="text-sm font-medium text-ink">试跑</p>
        <p className="mt-1 text-xs text-ink-3">对项目里已有的一个 PR 跑一次逐行审查，看看效果和花费。不会建任务，也不会在 GitHub 上留评论。</p>
        <div className="mt-2 grid gap-3 sm:grid-cols-[1fr_10rem_auto] sm:items-end">
          <Field label="项目">{id => <Select id={id} value={pid} onChange={e => setPid(e.target.value)}>{projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</Select>}</Field>
          <Field label="PR 编号">{id => <Input id={id} inputMode="numeric" value={pr} onChange={e => setPr(e.target.value)} placeholder="例如 42" />}</Field>
          <Button variant="primary" disabled={!pid || !/^\d+$/.test(pr.trim()) || running} onClick={trial} data-testid="code-review-trial">{running ? '审查中…' : '试跑'}</Button>
        </div>
      </div>
      {err && <Callout tone="danger">{err}</Callout>}
      {ok && !err && <Callout tone="success" onClose={() => setOk('')}>{ok}</Callout>}
      {res && <div data-testid="code-review-trial-result"><p className="mb-2 text-xs text-ink-3">PR #{res.pr} · 用时 {res.seconds} 秒{res.models?.length ? ` · 模型 ${res.models.join('、')}` : ''}</p><CodeReviewFindings data={res} /></div>}
    </div>
  </Card>;
}
