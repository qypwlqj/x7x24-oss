/**
 * 统一术语表（文案规范见 docs/copy-guide.md）。
 * 页面正文只用 name；内部 id / 配置键放到「详情」或灰色小字。新增概念先在这里登记。
 */
export const TERMS = {
  loop: { name: '返修', avoid: ['Loop', 'qa_loop', '迭代回流'], text: '测试没通过，回到开发重新修改' },
  reject: { name: '退回修改', avoid: ['驳回', 'reject'], text: '验收没通过，打回重做' },
  rollback: { name: '撤回上线', avoid: ['回滚', 'rollback'], text: '已上线的版本被撤下，恢复到上一个版本' },
  degrade: { name: '省钱模式', avoid: ['降级', '软阈值'], text: '预算快用完时，改用便宜的模型、缩小范围继续做' },
  heal: { name: '自动修复', avoid: ['自愈', 'heal'], text: '出问题时系统自动重试、换方案或换模型' },
  breaker: { name: '暂停使用', avoid: ['熔断'], text: '某个模型连续出错，先停一会儿，稍后自动恢复' },
  fallback: { name: '备用模型', avoid: ['fallback', 'chain', '兜底', '档位链'], text: '主模型不可用时按顺序换下一个' },
  dryRun: { name: '只记录模式', avoid: ['dry-run', '演练', '观察模式（dry-run）'], text: '不改动你的仓库，只记录本来要做的操作' },
  killSwitch: { name: '紧急停止', avoid: ['Kill switch'], text: '立即暂停全部或部分自动操作，直到手动解除' },
  snapshot: { name: '配置版本', avoid: ['快照'], text: '每次保存配置生成一个版本，任务会记住自己用的版本' },
  inherit: { name: '基于内置角色', avoid: ['继承'], text: '在内置角色的基础上增加技能或要求' },
  gate: { name: '通过分', avoid: ['门槛', 'gate'], text: '评分达到这个分数才算通过' },
  verdict: { name: '评审结果', avoid: ['裁决', 'verdict'], text: '评审给出的结论' },
  stage: { name: '流程步骤', avoid: ['节点', 'stage'], text: '需求从提出到上线要经过的环节' },
  evidence: { name: '过程记录', avoid: ['证据', '台账', '链头'], text: '系统在每一步留下的资料，便于追溯' },
  tick: { name: '立即运行一次', avoid: ['立即调度 tick', 'tick'], text: '不等定时器，马上处理一轮任务' },
  idempotent: { name: '重复请求已忽略', avoid: ['幂等'], text: '同一个操作只会执行一次' },
  backoff: { name: '限流等待', avoid: ['退避'], text: '为了不被 GitHub 限流，系统主动等一会儿' },
  agent: { name: '执行助手', avoid: ['Agent', 'agent'], text: '负责评审、规划、开发、测试的 AI 助手' },
  mergeGate: { name: '合并关卡', avoid: ['merge gate', '合并闸门'], text: '按改动大小和风险决定能否自动合并，较大的改动合并前要你确认' },
  ownerConfirm: { name: '负责人确认', avoid: ['approval', '审批流'], text: '你在任务详情里一键确认合并或退回修改，确认只对当时的提交有效' },
  suggestedSize: { name: '建议大小', avoid: ['硬上限', '变更预算'], text: '每段提交建议的大小，方便审阅；超出不会让任务失败' },
  iteration: { name: '上线次数', avoid: ['迭代'], text: '一条需求从提出到上线算一次' },
} as const;
export type TermKey = keyof typeof TERMS;
export const term = (k: TermKey) => TERMS[k].name;

/** 紧急停止的模式 / 范围 → 中文 */
export const KILL_MODE: Record<string, string> = { READ_ONLY: '只读（停止所有写操作）', FREEZE_RELEASE: '冻结发布', HALT: '全部停止' };
export const killModeName = (m: string) => KILL_MODE[m] ?? m;
export function scopeName(scope: string, names?: { lines?: { id: string; name: string }[]; projects?: { id: string; name: string }[] }) {
  if (!scope || scope === 'global') return '全局';
  const [k, id] = scope.split(':');
  if (k === 'line') return `产品线「${names?.lines?.find(l => l.id === id)?.name ?? id}」`;
  if (k === 'project') return `项目「${names?.projects?.find(p => p.id === id)?.name ?? id}」`;
  return scope;
}

/** 正文禁用词（守护测试使用）：这些词只能出现在「详情」、<code>、注释或本文件里 */
export const BANNED = ['继承', '拼进', '注入到提示词', '提示词', 'fallback', 'dry-run', 'Kill switch', 'Loop', '熔断', '自愈', '快照', '降级', '门槛', '裁决', '分诊', '链头', '幂等', '退避', '台账', 'tick', 'chain', 'switch_model', 'Agent', 'agent'];

/** 执行角色 id → 中文名（id 只作灰色小字） */
export const ROLE_NAME: Record<string, string> = {
  'prd.draft': '写需求文档', 'prd.review': '需求评审', 'plan.make': '任务规划', 'spec.write': '需求策划', 'design.make': '体验设计',
  'dev.code': '开发', 'qa.test': '测试', 'acceptance.review': '业务验收', 'chat.intake': '指挥台助手',
};
export const roleName = (id: string) => ROLE_NAME[id] ?? ROLE_NAME[id.split('.').slice(-2).join('.')] ?? id;
