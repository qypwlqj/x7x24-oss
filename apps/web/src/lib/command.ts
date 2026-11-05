import { appPath } from './navigation';
/** 指挥台首屏：按角色显示的能力按钮、问候语、输入框轮换示例、快捷指令、历史筛选（docs/design/chat-workspace-redesign.md 第 4、8 节） */
export type Role = 'owner' | 'admin' | 'member' | 'viewer' | string | undefined;
export type Group = '需求' | '产品线与项目' | '成员' | '查询';
export interface Capability { id: string; group: Group; label: string; text: string; presetId?: string; send?: boolean; need: 'viewer' | 'member' | 'admin' | 'owner' }
const RANK: Record<string, number> = { owner: 0, admin: 1, member: 2, viewer: 3 };
const rank = (r: Role) => RANK[r ?? 'owner'] ?? 3;

export const CAPABILITIES: Capability[] = [
  { id: 'req', group: '需求', label: '提一个需求', text: '我想要', need: 'member' },
  { id: 'req-backend', group: '需求', label: '后端接口', text: '后端接口：', presetId: 'backend', need: 'member' },
  { id: 'req-frontend', group: '需求', label: '前端页面', text: '前端页面：', presetId: 'frontend', need: 'member' },
  { id: 'req-migration', group: '需求', label: '数据迁移', text: '数据迁移：', presetId: 'migration', need: 'member' },
  { id: 'req-fast', group: '需求', label: '小改动·极速', text: '小改动：', presetId: 'fast', need: 'member' },
  { id: 'line', group: '产品线与项目', label: '新建产品线', text: '新建产品线 ', need: 'admin' },
  { id: 'project', group: '产品线与项目', label: '新建项目', text: '新建项目 ', need: 'admin' },
  { id: 'invite', group: '成员', label: '邀请成员', text: '邀请 @ 为成员', need: 'admin' },
  { id: 'invite-admin', group: '成员', label: '邀请管理员', text: '邀请 @ 为管理员', need: 'owner' },
  { id: 'q-mine', group: '查询', label: '我的任务进度', text: '我的任务现在进展到哪了？', send: true, need: 'viewer' },
  { id: 'q-stuck', group: '查询', label: '哪些任务卡住了', text: '这周有哪些任务卡住了？', send: true, need: 'viewer' },
  { id: 'q-projects', group: '查询', label: '项目列表', text: '列出当前工作区的项目', send: true, need: 'viewer' },
];
export const GROUPS: Group[] = ['需求', '产品线与项目', '成员', '查询'];

/** 按钮状态：hidden = 不显示（只读成员看不到会改数据的按钮）；locked = 能发起，但要更高角色确认 */
export function capabilityState(c: Capability, role: Role): { hidden: boolean; locked: boolean; reason?: string } {
  if (rank(role) === 3 && c.need !== 'viewer') return { hidden: true, locked: true };
  if (rank(role) <= rank(c.need)) return { hidden: false, locked: false };
  return { hidden: false, locked: true, reason: c.need === 'owner' ? '只有所有者可以邀请管理员' : '需要工作区管理员确认。你可以先发起，卡片会留给管理员处理' };
}
/** 新建项目的示例句带上当前产品线 */
export function capabilityText(c: Capability, lineName?: string): string { return c.id === 'project' && lineName ? `新建项目  到产品线 ${lineName}` : c.text; }

export const ROLE_ZH: Record<string, string> = { owner: '所有者', admin: '管理员', member: '成员', viewer: '只读成员' };
export function greeting(login: string | undefined, ws: string | undefined, role: Role): string {
  const who = login ? `@${login}` : '';
  if (rank(role) <= 1) return `你好 ${who}，你在「${ws ?? '当前工作区'}」是${ROLE_ZH[role ?? 'owner']}。今天想做什么？可以提需求，也可以直接让我建产品线、建项目、邀请成员。`;
  if (rank(role) === 2) return `你好 ${who}。你可以提需求、查进度；建产品线、建项目、邀请成员需要管理员确认，我可以先帮你把操作卡准备好。`;
  return `你好 ${who}。你是只读成员，可以提问、查进度和看历史记录，不能发起操作。需要权限请找工作区管理员。`;
}

const EXAMPLES: { text: string; need: Capability['need'] }[] = [
  { text: '成绩页想加个导出 CSV', need: 'member' }, { text: '新建产品线 教育', need: 'admin' }, { text: '新建项目 官网 到产品线 教育 仓库 acme/site', need: 'admin' },
  { text: '邀请 @octocat 为成员', need: 'admin' }, { text: '这周有哪些任务卡住了？', need: 'viewer' },
];
/** 输入框轮换示例：只轮换当前角色用得上的句子 */
export function examples(role: Role): string[] { return EXAMPLES.filter(e => rank(role) <= rank(e.need)).map(e => `试试：「${e.text}」 · 输入 / 查看快捷指令`); }

export interface Slash { cmd: string[]; label: string; text: string; need: Capability['need']; presetId?: string; help?: boolean }
export const SLASH: Slash[] = [
  { cmd: ['/需求', '/req'], label: '提需求', text: '我想要', need: 'member' },
  { cmd: ['/产品线', '/line'], label: '新建产品线', text: '新建产品线 ', need: 'member' },
  { cmd: ['/项目', '/project'], label: '新建项目', text: '新建项目 ', need: 'member' },
  { cmd: ['/邀请', '/invite'], label: '邀请成员', text: '邀请 @ 为成员', need: 'member' },
  { cmd: ['/查', '/status'], label: '查询进度', text: '这周有哪些任务卡住了？', need: 'viewer' },
  { cmd: ['/帮助', '/help'], label: '能做什么', text: '', need: 'viewer', help: true },
];
/** 输入以 / 开头时给出匹配的快捷指令（快捷指令只填句子，不会跳过确认卡） */
export function matchSlash(input: string, role: Role): Slash[] {
  if (!input.startsWith('/')) return [];
  const q = input.trim().toLowerCase();
  return SLASH.filter(s => rank(role) <= rank(s.need) && s.cmd.some(c => c.toLowerCase().startsWith(q) || q.startsWith(c.toLowerCase())));
}
export const HELP_TEXT = '在对话里可以：提需求、新建产品线、新建项目、邀请成员、查进度。会改动数据的操作都会先给你一张确认卡。删除、改权限、改预算、改密钥和配置、审批这些操作需要你到对应页面上自己完成。';

export type HistoryFilter = 'all' | 'req' | 'ops';
/** 历史筛选（D7）：需求 = 有标题或已建任务；操作 = 有操作卡 */
export function filterSessions<T extends { title: string | null; task_id: string | null; action_count?: number }>(list: T[], f: HistoryFilter): T[] {
  if (f === 'req') return list.filter(x => !!x.task_id || !!x.title);
  if (f === 'ops') return list.filter(x => (x.action_count ?? 0) > 0);
  return list;
}

/** B 类引导卡（D6）：跳转页面以服务端 Guides 固定表为准，这里只用 topic 取文案 */
export const GUIDES: Record<string, { title: string; reason: string; page: string; href: string }> = {
  delete_project: { title: '删除或归档项目', reason: '不可逆', page: '产品线与项目', href: '/catalog' },
  delete_line: { title: '删除或归档产品线', reason: '不可逆', page: '产品线与项目', href: '/catalog' },
  member_role: { title: '改角色、移出成员或转让所有者', reason: '涉及权限', page: '工作区成员', href: '/workspace' },
  budget: { title: '调整预算或配额', reason: '涉及费用', page: '预算', href: '/budget' },
  approval: { title: '合并或发布审批', reason: '需要你亲自把关', page: '任务', href: '/tasks' },
  secrets: { title: '模型、密钥配置', reason: '涉及密钥', page: '模型', href: '/models' },
  integrations: { title: '集成和通知配置', reason: '涉及凭据', page: '集成', href: '/integrations' },
  governance: { title: '治理配置（自治等级、风险规则）', reason: '影响所有任务', page: '治理', href: '/governance' },
};
/** 对话链接：发给有权限的人继续确认（D4） */
export const chatLink = (loc: { origin: string; pathname: string }, id: string) => `${loc.origin}${appPath(`/s/${encodeURIComponent(id)}`)}`;
