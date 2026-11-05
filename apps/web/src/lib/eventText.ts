/** 时间线事件 → 给人看的中文说明（原始 JSON 只放在「详情」里） */
import { EDGE, statusName } from '../labels';

export type EvtAction = { label: string; kind: 'link' | 'resume'; href?: string };
export type EvtText = { title: string; text?: string; tone: 'info' | 'warn' | 'danger' | 'success' | 'neutral'; actions?: EvtAction[] };

export const HEAL_ACTION: Record<string, string> = {
  shrink: '精简改动', split: '拆分成多段提交', replan: '重新规划', retry_same: '原样重试', switch_model: '换用备用模型', rerun_ci: '重跑 CI', rebase_branch: '基于最新主干重建分支', trip_breaker: '暂停使用该模型', wait_recover: '等待恢复', size_accepted: '接受当前大小继续，合并前请你确认',
};
const GOV: EvtAction = { label: '去治理配置', kind: 'link', href: '/governance' };
const RESUME: EvtAction = { label: '恢复执行', kind: 'resume' };
const RESUME_OWNER: EvtAction = { label: '处理好了，恢复执行', kind: 'resume' };
const INTEG: EvtAction = { label: '去集成页修 CI', kind: 'link', href: '/integrations' };
const BUDGET_LINK: EvtAction = { label: '去预算页', kind: 'link', href: '/budget' };
const MODEL_CONFIG: EvtAction = { label: '前往模型配置', kind: 'link', href: '/admin/models' };

/** 从「13 个文件 / 加权 124 行（原始 124 行；上限 20 文件 / 100 行）」这类文字里取数字（兼容旧事件） */
export function parseSize(detail: string | undefined) {
  const m = /(?:加权 )?(\d+) 个文件[^/]*\/ 加权 (\d+) 行（原始 (\d+) 行；上限 (\d+) 文件 \/ (\d+) 行/.exec(detail ?? '');
  return m ? { files: +m[1], weightedLines: +m[2], rawLines: +m[3], maxFiles: +m[4], maxLines: +m[5] } : null;
}

export function anomalyText(type: string, detail?: string, facts?: any): EvtText {
  switch (type) {
    case 'change_too_large': {
      const f = facts ?? parseSize(detail) ?? {};
      const files = f.rawFiles ?? f.files, lines = f.weightedLines;
      const over: string[] = [];
      if (lines != null && f.maxLines != null && lines > f.maxLines) over.push(`每次最多 ${f.maxLines} 行`);
      if (f.files != null && f.maxFiles != null && f.files > f.maxFiles) over.push(`每次最多 ${f.maxFiles} 个文件`);
      const seg = f.segment ? `（拆分后的第 ${f.segment} 段）` : '';
      return { title: '改动量超出限制', tone: 'warn', actions: [GOV, RESUME],
        text: `本次${seg}改了 ${files ?? '?'} 个文件、${lines ?? '?'} 行，超过了治理配置里设定的${over.length ? over.join('、') : '上限'}。系统将自动精简 / 拆分；你也可以在「治理配置」调整上限。` };
    }
    case 'ci_flaky': return { title: 'CI 没有给出结果', tone: 'warn', actions: [RESUME], text: `${/超时/.test(detail ?? '') ? 'CI 运行超时。' : 'QA 阶段缺少 PR 或 CI 记录。'}系统会先重跑 CI，仍不行就重新规划。` };
    case 'spec_gap': return { title: '需求 / 验收用例不完整', tone: 'warn', actions: [RESUME], text: `${detail ?? ''}。系统会重新规划；如果需求本身说得不清楚，建议编辑任务补充验收标准。` };
    case 'merge_conflict': return { title: '合并冲突', tone: 'warn', actions: [RESUME], text: '任务分支与主干有冲突，系统会基于最新主干重建分支后再提交。' };
    case 'agent_error': return { title: '执行助手连续出错', tone: 'danger', actions: [RESUME], text: `${agentErrorText(detail)}。系统会在出错节点重试或切换模型；需要调整方案时才重新规划。` };
    case 'repository_not_initialized': return { title: '仓库尚未初始化，已暂停', tone: 'danger', actions: [RESUME_OWNER],
      text: `${detail ? short(detail, 400) : '仓库尚无首个提交'}。可在任务操作中选择「初始化仓库并继续」，由管理员确认首次说明文档提交后恢复执行，保留已生成的规划。` };
    case 'model_config_error': return { title: '模型配置有误，已暂停', tone: 'danger', actions: [MODEL_CONFIG, RESUME_OWNER],
      text: `${detail ? short(detail) : '评审模型配置不满足隔离要求'}。请在「模型与流程」中修正评审席位配置，再点「恢复执行」，从失败的评审步骤继续。` };
    case 'timeout': return { title: '步骤长时间没有进展', tone: 'warn', actions: [RESUME], text: '巡查发现这一步超时未完成，系统会自动重试。' };
    case 'ci_broken': {
      const f = facts ?? {};
      const why = f.headline ?? (detail ? short(detail) : 'CI 在运行测试之前就失败了');
      const base = f.kind === 'pre_existing' || f.baseState === 'failed' ? '目标分支（主干）上的 CI 也是同样失败，不是这次改动造成的。' : '';
      return { title: 'CI 本身跑不起来，已暂停等你处理', tone: 'danger', actions: [INTEG, RESUME_OWNER],
        text: `${why}。${base}编码助手无法修复 CI 配置，任务已暂停。请到「集成」页做「仓库体检」，用 PR 补充或更新 CI 模板并合并初始化 PR，然后点「恢复执行」，会更新业务 PR 分支并直接重跑测试。` };
    }
    case 'ci_permission': return { title: 'CI 访问权限不足，已暂停', tone: 'danger', actions: [INTEG, RESUME_OWNER],
      text: `${detail ? short(detail, 400) : '无法读取或触发 GitHub Actions'}。请到「集成」页核对仓库授权与 Actions 读写权限，再恢复执行。` };
    case 'project_not_ready': return { title: '项目工程尚未就绪，已暂停', tone: 'danger', actions: [INTEG, RESUME_OWNER],
      text: `${detail ? short(detail, 400) : '缺少工程依赖清单或测试入口'}。请通过独立的工程初始化 PR 补齐后恢复执行，已完成的开发与设计保留。` };
    case 'same_failure': {
      const f = facts ?? {};
      return { title: '同样的测试失败反复出现', tone: 'warn', actions: [RESUME_OWNER],
        text: `连续几次返修后，测试报的还是同一个错误${f.headline ? `（${short(f.headline, 80)}）` : ''}，按原来的做法改没有用。任务已暂停。手动恢复后会补充新一轮返修额度，基于已有代码继续修复；建议补充新的定位或修复提示。` };
    }
    case 'review_plan_adjustment':
      return { title: '待确认方案调整', tone: 'warn', text: detail };
    case 'review_same_failure':
      return { title: '评审反复发现相同问题，已暂停', tone: 'warn', actions: [RESUME_OWNER], text: detail };
    case 'rework_exhausted': {
      const f = facts ?? {};
      return { title: '返修次数用完，换个做法', tone: 'warn', actions: [RESUME_OWNER],
        text: `${f.what ?? '返修'}次数已经用完。手动恢复后会补充新一轮返修额度，保留已有计划、提交和历史记录。预算与安全上限继续独立生效。` };
    }
    case 'budget_paused': {
      const f = facts ?? {};
      return { title: '预算用完，已暂停', tone: 'danger', actions: [BUDGET_LINK, RESUME_OWNER],
        text: `${f.what ?? '任务预算用完'}。这个任务关闭了省钱模式，所以不会自动换方案或缩小范围，而是暂停等你处理：在任务详情的预算卡片里打开「不限预算」（会自动继续），或提高预算后点「恢复执行」。` };
    }
    case 'safety_cap': {
      const f = facts ?? {};
      return { title: '达到安全上限，已暂停', tone: 'danger', actions: [RESUME_OWNER],
        text: `这个任务不限预算，但开发已经做了 ${f.iterations ?? '?'} 轮，达到安全上限 ${f.limit ?? '?'} 轮。为了防止无限循环，先暂停等你确认；点「恢复执行」会再放行同样多的轮次。目前已花费 $${f.spentUsd ?? '?'}。` };
    }
    default: return { title: '发现异常', tone: 'warn', actions: [RESUME], text: detail ? short(detail) : undefined };
  }
}

const short = (s: string, n = 160) => (s.length > n ? s.slice(0, n) + '…' : s);
export function agentErrorText(err?: string) {
  if (!err) return '执行助手出错';
  let m = /LLM 输出重试后仍不合格（(.+?)）：/.exec(err); if (m) return `执行助手的输出不符合要求：${short(m[1], 120)}`;
  m = /dry-run: 计划调用 (\S+)/.exec(err); if (m) return `当前是只记录模式，「${m[1]}」这一步没有真正执行`;
  if (/max_tokens|截断/.test(err)) return '执行助手的输出太长，被截断了';
  if (/timeout|超时/i.test(err)) return '调用模型超时';
  return `执行助手出错：${short(err, 120)}`;
}

/** 一条时间线事件的标题、说明和可点的下一步 */
function reviewRoute(p: Record<string, any>) {
  if (p.returnTo) return `已退回${p.returnTo === 'DEV' ? '开发' : statusName(p.returnTo)} · 第 ${p.loops}/${p.maxLoops} 轮返修`;
  if (p.pauseCode === 'review_plan_adjustment') return '待确认方案调整';
  if (p.paused) return '返修已暂停';
  return '需修复';
}

export function describeEvent(e: any): EvtText {
  const p = e.payload ?? {};
  switch (e.event_type) {
    case 'REPOSITORY_INITIALIZED': return { title: '仓库基线已就绪', tone: 'success', text: `已核验 ${p.repo ?? ''} 的 ${p.branch ?? ''} 分支，继续开发。` };
    case 'ANOMALY_OPENED': return anomalyText(p.type, p.detail, p.facts);
    case 'OWNER_NEEDED': { const a = anomalyText(p.anomaly, p.detail); return { title: '需要你处理', tone: 'danger', actions: a.actions, text: `${a.title}。任务已暂停，不会自动放弃；处理好后点「恢复执行」。` }; }
    case 'DEV_MODE': return { title: p.label ?? `本轮：${p.mode === 'legacy' ? '快速方式' : '沙盒'}`, tone: p.hint ? 'warn' : 'neutral', text: [p.source === 'task' ? '按本任务的设置' : p.source === 'project' ? '按项目设置' : '默认方式', p.hint].filter(Boolean).join('。') + '。' };
    case 'CODE_REVIEW_FAILED': {
      if (Array.isArray(p.failed)) { const fs: any[] = p.failed; return { title: `${p.label ?? '逐行审查'}有 ${fs.length} 组没审完，已停下`, tone: 'danger', text: [`已审完 ${p.completedGroups ?? 0}/${p.totalGroups ?? fs.length} 组，结果已保存。`, ...fs.slice(0, 5).map((f) => `${(f.files ?? []).join('、')}：${f.reason ?? '审查出错'}`), '可以「重试」，或由负责人「跳过并继续」。不占返修次数。'].join('\n') }; }
      if (p.error === 'no_review_model') return { title: `${p.label ?? '逐行审查'}没有可用的模型，已停下`, tone: 'danger', text: '请在「模型与流程」里配好审查模型和备用模型，再点「重试」。' };
      const fs: any[] = Array.isArray(p.failedFiles) ? p.failedFiles : [];
      const models: string[] = Array.isArray(p.attempts) ? p.attempts.map((a: any) => a.model).filter(Boolean) : [];
      return { title: `${p.label ?? '逐行审查'}没审完，已停下`, tone: 'danger', text: [
        p.totalFiles != null ? `已审完 ${p.reviewedFiles ?? 0}/${p.totalFiles} 个文件，结果已保存。` : '',
        models.length ? `试过的模型：${models.join(' → ')}` : '',
        ...fs.slice(0, 5).map((f) => `${f.path}：${f.reason ?? '审查出错'}`),
        fs.length ? '' : (p.error ?? ''),
        '可以「重试」（接着上次审，已审完的文件不重审），或由负责人「跳过并继续」。不占返修次数。'].filter(Boolean).join('\n') };
    }
    case 'CODE_REVIEW_SKIP_FAILED': return { title: '跳过了没审完的部分', tone: 'warn', text: '没审完的文件按「没审」记下，审查结论标成部分审查，接着往下走。' };
    case 'SANDBOX_RETRY': return { title: `沙盒没连上，${p.waitMin} 分钟后自动重试（第 ${p.attempt}/${p.of} 次）`, tone: 'warn', text: p.reason };
    case 'TASK_DEV_MODE': { const zh = (m?: string) => m === 'legacy' ? '快速方式' : m === 'sandbox' ? '沙盒' : '跟随项目'; return { title: '修改了本任务的开发方式', tone: 'info', text: `${zh(p.before)} → ${zh(p.after)}，下一轮开发生效。` }; }
    case 'TASK_BUDGET_SWITCH': { const s = p.after ?? {}; return { title: '修改了任务预算设置', tone: 'info', text: `${s.budgetOff ? '不限预算（仍记录花费）' : '按预算限制'}；${s.degradeOff ? '预算用完时暂停等你处理' : '预算用完时改用省钱模式'}。` }; }
    case 'AGENT_FAILED': {
      if (['ci_broken', 'ci_permission', 'project_not_ready'].includes(p.category)) return anomalyText(p.category, p.error);
      if (p.category === 'repository_not_initialized') return anomalyText('repository_not_initialized', p.error);
      if (p.category === 'model_config_error' || p.type === 'IsolationError') return anomalyText('model_config_error', p.error);
      if (p.willRetry === false || p.retryable === false) return { title: '执行出错，已进入异常处理', tone: 'danger', text: agentErrorText(p.error) };
      return { title: `执行出错，正在自动重试（第 ${p.attempt ?? '?'} 次）`, tone: 'warn', text: agentErrorText(p.error) };
    }
    case 'SELF_HEAL': return { title: '自动修复', tone: 'info', text: `针对「${anomalyText(p.anomaly).title}」采取：${HEAL_ACTION[p.action] ?? p.action}${p.model ? `（${p.model}）` : ''}。` };
    case 'SIZE_SPLIT': return { title: '改动太大，改为分段提交', tone: 'info', text: '开发产出精简后仍超过上限，系统回到规划，把工作拆成几段依次提交（每段都在上限内）。' };
    case 'SEGMENT_RESPLIT': return { title: '这一段还是太大，继续拆小', tone: 'info', text: `第 ${p.segment} 段拆成 ${p.into} 段，现在共 ${p.parts} 段。` };
    case 'SIZE_LARGE': return { title: '这个需求改动较大，照常推进', tone: 'info', text: `预计改动约 ${p.weightedLines ?? '?'} 行（${p.tierName ?? '较大'}），会分段提交方便审阅；不会因为大而放弃。${p.needsApproval ? '合并前需要你确认。' : ''}` };
    case 'SIZE_ADVISORY': return { title: '这一段超过建议大小，继续提交', tone: 'info', text: '建议大小只用来方便审阅，不再拦住任务；合并前按改动大小决定是否要你确认。' };
    case 'MERGE_APPROVAL_REQUESTED': return { title: '改动较大，合并前等你确认', tone: 'warn', text: `${p.tierName ?? '大'}改动，约 ${p.weightedLines ?? '?'} 行，已通过测试与验收。在任务详情里点「确认合并」或「退回修改」；不会自动合并或放弃。`, actions: p.url ? [{ label: '查看改动', kind: 'link', href: p.url }] : undefined };
    case 'MERGE_APPROVED': return { title: '你已确认合并', tone: 'success', text: p.note ? `备注：${p.note}` : '开始合并到主干。' };
    case 'MERGE_RETURNED': return { title: '你退回了修改', tone: 'warn', text: `按你的意见返修：${p.note ?? ''}` };
    case 'MERGE_APPROVAL_STALE': return { title: '有新提交，之前的确认作废', tone: 'warn', text: '确认只对当时的提交有效，新提交需要你重新确认。' };
    case 'MERGE_APPROVAL_REMINDER': return { title: `已经等了 ${p.hours ?? '?'} 小时，提醒你确认合并`, tone: 'warn', text: '任务会一直等你决定，不会自动合并或放弃。' };
    case 'SPLIT_PART_COMMITTED': return { title: `已提交第 ${p.part}/${p.parts} 段`, tone: 'success' };
    case 'VERDICT': return { title: `评审结论：${({ pass: '通过', revise: '打回修改', reject: '否决' } as Record<string, string>)[p.decision] ?? p.decision}`, tone: p.decision === 'pass' ? 'success' : p.decision === 'revise' ? 'warn' : 'danger', text: p.aggregate != null ? `综合分 ${Number(p.aggregate).toFixed(2)}` : undefined };
    case 'BUDGET_HIT': return budgetHitText(p);
    case 'PATH_POLICY_BLOCK': return { title: '当前补丁未通过内容或路径检查', tone: 'danger', text: (p.violations ?? []).slice(0, 3).join('；') };
    case 'STAGE_SKIPPED': return { title: `跳过「${statusName(p.stage)}」`, tone: 'neutral', text: p.reason ? `原因：${p.reason}` : '原因：任务规划判断这次不需要' };
    case 'STAGE_RESUMED': return { title: `从「${statusName(p.stage)}」接着做`, tone: 'info', text: `${p.reason ?? '返修'}。${(p.kept ?? []).length ? `已完成的${(p.kept as string[]).map(x => `「${statusName(x)}」`).join('')}保留，不重做。` : ''}` };
    case 'DEGRADED': return degradeText(p);
    case 'NODE_PASSED': return { title: `「${p.label ?? statusName(p.node)}」检查通过`, tone: 'success', text: p.aggregate != null ? `综合分 ${Number(p.aggregate).toFixed(2)}` : undefined };
    case 'CODE_REVIEW_DONE': {
      const sm = p.summary ?? {}; const n = Number(p.total ?? 0); const files = p.reviewedFiles ?? p.files ?? 0;
      const head = (n === 0 ? `审查了 ${files} 个文件，没有发现问题` : `审查了 ${files} 个文件，发现 ${n} 个问题（严重 ${sm.critical ?? 0}、高 ${sm.high ?? 0}、中 ${sm.medium ?? 0}、低 ${sm.low ?? 0}）`) + (p.unlocatedCount ? `，${p.unlocatedCount} 个没定位到行（不阻断）` : '');
      const title = (p.blocked ? `逐行审查未通过 → ${reviewRoute(p)}` : p.wouldBlock ? `发现 ${p.blockingCount} 项阻断级问题 · 观察模式继续` : '逐行审查完成') + (p.partial ? '（部分审查）' : '');
      const findings = p.blocked && Array.isArray(p.findings)
        ? p.findings.map((f: any) => `${f.path}:${f.line} · ${f.content}${f.suggestion ? `\n修复建议 · ${f.suggestion}` : ''}`).join('\n')
        : '';
      const body = [head, p.coverageText, findings].filter(Boolean).join('\n');
      return { title, tone: p.wouldBlock || p.blocked || p.partial ? 'warn' : n > 0 ? 'info' : 'success', text: body };
    }
    case 'UI_ACCEPTANCE_DONE': {
      const v = String(p.verdict ?? '');
      const word = v === 'PASS' ? '通过' : v === 'FAIL' ? '没通过' : '需要人工确认';
      return { title: p.blocked ? '页面验收没通过，退回修改' : `页面验收${word}`, tone: p.blocked ? 'warn' : v === 'PASS' ? 'success' : 'info',
        text: p.blocked ? '验收脚本失败或应用没起来，已退回编码' : v === 'FAIL' ? '判定模型认为有验收标准不符合；观察期只记录' : undefined };
    }
    case 'NODE_REJECTED': return { title: `${p.label ?? statusName(p.node)}未通过 → ${reviewRoute(p)}`, tone: 'warn', text: p.reason && p.reason !== 'null' ? String(p.reason) : undefined };
    case 'PLAN_REVISED': return { title: '修改了流程计划', tone: 'info', text: (p.applied ?? []).join('；') || undefined };
    case 'CONFIG_UPGRADED': return { title: '流程配置升级到最新版本', tone: 'info' };
    case 'ISSUE_SYNC_FAILED': return { title: '回写 Issue 失败（不影响任务推进）', tone: 'warn' };
    case 'PATH_FILTERED': return { title: '流程节点按模板调整', tone: 'neutral', text: `实际执行：${(p.path ?? []).map(statusName).join(' → ')}` };
    case 'TASK_RESUMED': return { title: p.reworkReset ? '手动恢复，开始新一轮返修' : '手动恢复执行', tone: 'info', text: p.reworkReset ? `已补充第 ${p.reworkReset.after?.round ?? ''} 轮返修额度，将基于已有代码继续修复，再运行检查。历史记录和花费保留。` : undefined };
    case 'TASK_BLOCKED': return { title: `停在「${statusName(p.node ?? '')}」，等待处理`, tone: 'warn', text: `已有进度已保存${p.parts ? ` · 已提交 ${Number(p.completedSegments ?? Math.max(0, Number(p.segment) - 1))}/${p.parts} 段` : ''}。处理后从当前步骤继续。${p.detail ? `原因：${p.detail}` : ''}` };
    case 'TASK_RESTARTED': return { title: '手动重启任务', tone: 'info' };
    case 'TASK_EDITED': return { title: '编辑了任务', tone: 'neutral', text: Object.keys(p.changes ?? {}).map(k => ({ title: '标题', problem: '描述', acceptanceCriteria: '验收标准' } as Record<string, string>)[k] ?? k).join('、') };
    case 'CHAT_CONFIRMED': return { title: p.restart ? '在对话里补充后重新确认' : '由对话确认创建', tone: 'info' };
    case 'CHAT_DELETED': return { title: '来源的对话已删除，任务保留', tone: 'info', text: p.waitingClarification ? '删除时任务正在等补充信息，请在任务详情里修改需求后重新启动' : undefined };
    case 'CHAT_CLARIFICATION_REQUESTED': return { title: '需求信息不够，已退回对话补充', tone: 'warn', text: (p.questions ?? []).join('；') };
    case 'STATUS_CHANGED': {
      const edge = EDGE[e.edge] ?? e.edge;
      const p2 = e.payload ?? {};
      let why = edge && e.from_status ? `原因：${edge}` : undefined;
      if (e.edge === 'dispatch' && p2.stage && p2.reason) why = `接着返修：${p2.reason}`;
      else if (e.edge === 'degrade' && p2.action) why = `原因：${edge}（${p2.level ?? ''} ${p2.action}${p2.resumeAt ? `，保留已完成的上游步骤，从「${statusName(p2.resumeAt)}」继续` : '，重新规划'}）`;
      else if (e.edge === 'qa_loop' && p2.loops != null) why = `原因：${edge}（第 ${p2.loops}${p2.max != null ? ` / ${p2.max}` : ''} 轮${e.to_status === 'DEGRADED' ? '，返修轮数用完' : ''}）`;
      return { title: e.from_status ? `${statusName(e.from_status)} → ${statusName(e.to_status)}` : `创建任务（${statusName(e.to_status)}）`, tone: e.to_status === 'DISCARDED' ? 'danger' : e.to_status === 'ANOMALY' ? 'warn' : 'neutral', text: why };
    }
    default: return { title: '', tone: 'neutral' };
  }
}

/** 省钱模式各级动作：D1 换方案（保留上游产物，从开发执行继续）、D2 缩小范围（重新规划）、D3 废弃 */
export function degradeText(p: any): EvtText {
  const lv = String(p?.level ?? '');
  const cause = p?.category === 'budget' ? '任务预算用完' : p?.category === 'test_fail' ? '测试返修轮数用完' : p?.category === 'node_reject' || p?.category === 'quality' ? '退回次数用完' : '多次没通过';
  if (p?.action === 'strategy' || lv === 'D1') return { title: `省钱模式第 1 级：换个做法`, tone: 'warn', text: `${cause}，追加一些额度后换个实现方案。${p?.resumeAt ? `已完成的需求策划 / 体验设计保留，从「${statusName(p.resumeAt)}」继续。` : '重新规划后继续。'}` };
  if (p?.action === 'scope' || lv === 'D2') return { title: '省钱模式第 2 级：缩小范围', tone: 'warn', text: `${cause}，缩小这次要做的范围并重新规划，剩下的部分拆成新任务。` };
  return { title: '省钱模式用完，任务将废弃', tone: 'danger', text: `${cause}，换方案和缩小范围都没能完成。` };
}

const BUDGET: EvtAction = { label: '去预算页', kind: 'link', href: '/budget' };
const fmtNum = (k: string, v: number) => k === 'usd' ? `$${Number(v).toFixed(3)}` : Number(v).toLocaleString('en-US');
/** 预算触顶：说清是哪一层预算（单任务 / 产品线日 / 工作区日 / 工作区月）、哪一项、用了多少 / 上限多少 */
export function budgetHitText(p: any): EvtText {
  const scope = String(p?.scope ?? 'task');
  if (scope === 'task') {
    const w = p?.worst ?? {};
    if (!w.key) return { title: '单任务预算触顶', tone: 'danger', actions: [BUDGET], text: '本任务自身的预算（不是工作区总预算）已用完，系统会先换个做法、再缩小范围。' };
    const others = (p.dims ?? []).filter((d: any) => d.key !== w.key).map((d: any) => `${d.label} ${fmtNum(d.key, d.used)} / ${fmtNum(d.key, d.limit)}`).join('，');
    const why = w.key === 'iterations' ? '开发轮次指本任务累计调用开发助手的次数（每段开发、每次精简重试各算一次），与金额 / Token 无关。' : '';
    return { title: `单任务${w.label}用完`, tone: 'danger', actions: [BUDGET],
      text: `本任务的${w.label}已用 ${fmtNum(w.key, w.used)}，达到单任务上限 ${fmtNum(w.key, w.limit)}（这是每个任务各自的额度，不是工作区总预算）。${why}系统会先换个做法、再缩小范围，并相应追加一些额度。${others ? `其他项：${others}。` : ''}` };
  }
  const name = scope === 'global' ? '工作区今日预算' : scope === 'global_month' ? '工作区本月预算' : scope.startsWith('line:') ? `产品线「${scope.slice(5)}」今日预算` : scope.startsWith('project') ? '项目今日预算' : '预算';
  const lv = p?.level === 'hard' ? '已触顶，新的自动操作会暂停' : p?.level === 'alert' ? '接近上限（提醒）' : '接近上限，系统会改用省钱模式';
  return { title: `${name}${p?.level === 'hard' ? '触顶' : '预警'}`, tone: p?.level === 'hard' ? 'danger' : 'warn', actions: [BUDGET], text: `${name}${lv}。${p?.reason && p.reason !== 'null' ? `原因：${p.reason}。` : ''}可在「预算」页查看用量或调整额度。` };
}

/** 后端失败记录 { stage, category, code, params, detail } → 一句人话（原始 detail 只放「详情」） */
export function failureText(f: any): string {
  if (!f || typeof f !== 'object') return String(f ?? '');
  const code = String(f.code ?? String(f.category ?? '').split(':')[0]); const p = f.params ?? {}; const at = f.stage ? `${statusName(f.stage)}：` : '';
  switch (code) {
    case 'budget': return p.key ? `${at}单任务${p.label}用完（${fmtNum(p.key, p.used)} / ${fmtNum(p.key, p.limit)}）` : `${at}单任务预算用完`;
    case 'test_fail': return `${at}测试没通过，已回到开发返修`;
    case 'merge_gate': return `${at}合并前检查没通过${p.sub ? `（${GATE_NAME[p.sub] ?? p.sub}）` : ''}，已撤回重新规划`;
    case 'discard': return `${at}任务被废弃`;
    case 'merge_return': return `${at}你退回了修改，已回到开发按意见返修`;
    case 'canary_regression': return `${at}灰度发布指标变差，已自动撤回上线`;
    default: {
      const a = anomalyText(code, typeof f.detail === 'string' ? f.detail : undefined, Object.keys(p).length ? p : undefined);
      if (a.title !== '发现异常') return `${at}${a.title}`;
      return `${at}${FAIL_CAT[code] ?? '处理没有成功'}`;
    }
  }
}
const GATE_NAME: Record<string, string> = { checks_not_green: 'CI 检查没有全部通过', no_checks: '没有 CI 检查结果', head_changed: '分支在检查期间有新提交', pr_state: '拉取请求状态不对', governance_file: '改动涉及治理文件', required_check: '缺少必需的检查', project_paused: '项目已暂停' };
const FAIL_CAT: Record<string, string> = { quality: '验收认为质量不够', scope: '验收认为范围不符', value: '验收认为价值不足', rollback: '上线后被撤回' };
