import { sessionHref } from './nav';
import { apiUrl, authHeaders, notifyUnauthorized } from './auth';

/** 指挥台（原需求对话）：类型、SSE 解析与流式请求（POST 返回 text/event-stream，EventSource 只支持 GET，所以用 fetch 读流） */
export interface Draft {
  presetId?: string | null;
  title?: string | null; goal?: string | null; projectId?: string | null; risk?: string | null; size?: string | null; classification?: string | null;
  scope: string[]; outOfScope: string[]; acceptance: string[]; targetPaths: string[]; classifiedBy: string[]; openQuestions: string[]; adjustments?: any;
}
export interface Precheck { ok: boolean; problems: string[]; warnings: string[]; risk: string; duplicates: { id: string; title: string; similarity: number }[]; vagueAcceptance: number[] }
export interface ChatMessage { id: number; role: 'user' | 'assistant' | 'tool' | 'reasoning'; content?: string | null; tool_name?: string | null; tool_summary?: string | null; model?: string | null; usd?: number | null; created_at: string }
export interface ChatBubble { key: string; role: ChatMessage['role'] | 'error' | 'guide'; text: string; pending?: boolean; at?: string }
export interface ChatSession {
  id: string; title: string | null; status: 'open' | 'confirmed' | 'abandoned' | 'pending_approval'; project_id: string | null; product_line_id?: string | null; action_count?: number; suggestions?: string[]; phase?: 'setup' | 'drafting'; pending_actions?: number; task_id: string | null; draft_version: number;
  usd_spent: number; turns: number; created_by: string; created_at: string; updated_at: string;
  /** 归属：任务 > 项目 > 产品线 > 未归属（V48） */
  anchor_kind?: 'task' | 'project' | 'line' | 'none' | null; anchor_id?: string | null; anchor_name?: string | null;
  messages?: ChatMessage[]; actions?: ChatAction[]; draft?: Draft; precheck?: Precheck; running?: boolean; limits?: { sessionUsd: number; maxTurns: number }; plan?: any;
}
export type ChatEvent = { type: 'tool' | 'status' | 'draft' | 'delta' | 'reasoning' | 'reasoning_done' | 'reset' | 'done' | 'error' | 'stopped' | 'action' | 'guide' | 'suggest'; data: any };

/** 每次模型尝试有独立思考块；重试只撤掉该次内容，结束事件将正在输出的块收起。 */
export function applyReasoningEvent(bubbles: ChatBubble[], replyKey: string, e: ChatEvent): ChatBubble[] {
  if (e.type === 'reasoning') {
    const key = `r:${e.data.id}`; const i = bubbles.findIndex(b => b.key === key);
    if (i >= 0) return bubbles.map(b => b.key === key ? { ...b, text: b.text + e.data.text, pending: true } : b);
    const thought: ChatBubble = { key, role: 'reasoning', text: e.data.text, pending: true };
    const reply = bubbles.findIndex(b => b.key === replyKey);
    return reply < 0 ? [...bubbles, thought] : [...bubbles.slice(0, reply), thought, ...bubbles.slice(reply)];
  }
  if (e.type === 'reset') return e.data.reasoningId ? bubbles.filter(b => b.key !== `r:${e.data.reasoningId}`) : bubbles;
  if (e.type === 'reasoning_done') return bubbles.map(b => b.key === `r:${e.data.id}` ? { ...b, pending: false } : b);
  if (e.type === 'done' || e.type === 'error' || e.type === 'stopped') return bubbles.map(b => b.role === 'reasoning' && b.pending ? { ...b, pending: false } : b);
  return bubbles;
}

/** 对话里 agent 提议、等用户确认的操作（新增产品线 / 项目、邀请成员） */
export type ChatActionKind = 'create_product_line' | 'create_project' | 'bind_repo' | 'invite_member';
export interface ChatAction {
  id: string; kind: ChatActionKind | string; params: Record<string, any>; status: 'proposed' | 'executed' | 'cancelled' | 'failed';
  result?: Record<string, any> | null; error?: string | null; blockedRole?: 'admin' | 'owner' | 'login' | null; proposedBy: 'agent' | 'rules'; decidedBy?: string | null; createdAt: string; decidedAt?: string | null;
}
export const ACTION_TITLE: Record<string, string> = { create_product_line: '新增产品线', create_project: '新增项目', bind_repo: '绑定仓库', invite_member: '邀请成员' };
export const ACTION_STATUS: Record<ChatAction['status'], [string, 'primary' | 'success' | 'neutral' | 'danger']> = { proposed: ['待确认', 'primary'], executed: ['已完成', 'success'], cancelled: ['已取消', 'neutral'], failed: ['没有完成', 'danger'] };
const ROLE_ZH: Record<string, string> = { admin: '管理员', member: '成员', viewer: '只读' };
/** 确认卡上给人看的参数（中文标签；空值不显示） */
export function actionFields(a: Pick<ChatAction, 'kind' | 'params'>): [string, string][] {
  const p = a.params ?? {}; const out: [string, string][] = [];
  const add = (k: string, v: unknown) => { if (v !== null && v !== undefined && String(v).trim() !== '') out.push([k, String(v)]); };
  if (a.kind === 'create_product_line') { add('名称', p.name); add('说明', p.description); }
  else if (a.kind === 'bind_repo') { add('项目', p.projectName ?? p.projectId); add('仓库', p.repo); add('基础分支', p.baseBranch); if (p.previousRepo) add('原仓库', p.previousRepo); }
  else if (a.kind === 'create_project') { add('名称', p.name); add('所属产品线', p.productLineName ?? p.productLineId); add('仓库', p.repo ?? '暂不绑定'); add('基础分支', p.baseBranch); add('说明', p.description); }
  else if (a.kind === 'invite_member') { add('GitHub 账号', p.login ? '@' + p.login : '任何拿到链接的人'); add('角色', ROLE_ZH[p.role] ?? p.role); add('想加入的项目', p.projectName); }
  return out;
}
/** 需要所有者 / 管理员才能执行的操作；邀请管理员只有所有者能做 */
export function actionAllowed(a: Pick<ChatAction, 'kind' | 'params'>, role: string | undefined): boolean {
  const manager = role === 'owner' || role === 'admin';
  if (a.kind === 'invite_member' && a.params?.role === 'admin') return role === 'owner';
  return manager;
}
/** 卡片当前该显示的状态（设计文档第 5 节）：执行中 / 等待有权限的人 是前端根据点击和 blockedRole 推出来的 */
export function actionView(a: Pick<ChatAction, 'kind' | 'params' | 'status' | 'result' | 'error' | 'blockedRole'>, role: string | undefined, busy = false): { label: string; tone: 'primary' | 'accent' | 'success' | 'warn' | 'neutral' | 'danger'; body: string; link?: { href: string; label: string }; object?: { kind: 'line' | 'project'; id: string; name: string } } {
  if (busy) return { label: '执行中…', tone: 'accent', body: '正在执行，请稍等。' };
  if (a.status === 'executed') {
    const r = a.result ?? {};
    if (a.kind === 'create_product_line') return { label: '已完成', tone: 'success', body: `已新建产品线「${r.name ?? a.params.name}」。`, object: r.productLineId ? { kind: 'line', id: r.productLineId, name: r.name ?? a.params.name } : undefined };
    if (a.kind === 'create_project') return { label: '已完成', tone: 'success', body: `已新建项目「${r.name ?? a.params.name}」。`, object: r.projectId ? { kind: 'project', id: r.projectId, name: r.name ?? a.params.name } : undefined };
    if (a.kind === 'bind_repo') return { label: '已完成', tone: 'success', body: `已把 ${r.repo ?? a.params.repo} 绑定到项目「${r.name ?? a.params.projectName}」。`, object: r.projectId ? { kind: 'project', id: r.projectId, name: r.name ?? a.params.projectName } : undefined };
    return { label: '已完成', tone: 'success', body: '邀请已创建，7 天内有效。邀请链接只在确认时显示一次。' };
  }
  if (a.status === 'cancelled') return { label: '已取消', tone: 'neutral', body: '已取消，没有做任何改动。' };
  if (a.status === 'failed') return { label: '没有完成', tone: 'danger', body: `没有完成：${a.error ?? '未知原因'}。没有做任何改动。` };
  if (!actionAllowed(a, role)) {
    const who = a.blockedRole === 'login' ? '登录后' : a.kind === 'invite_member' && a.params?.role === 'admin' ? '所有者' : '管理员';
    return { label: '等待有权限的人', tone: 'warn', body: `你是${({ owner: '所有者', admin: '管理员', member: '成员', viewer: '只读成员' } as Record<string, string>)[role ?? ''] ?? role}，不能执行这个操作。可以把这个对话发给工作区${who}来确认。` };
  }
  return { label: a.blockedRole ? '等待有权限的人' : '待确认', tone: a.blockedRole ? 'warn' : 'primary', body: a.blockedRole ? '之前有人没有权限确认，这张卡留给你处理。检查没问题就点「确认」。' : '请检查下面的内容，确认后我会用你的身份执行。' };
}
/** 合并 SSE 推来的新卡片（按 id 去重，新的覆盖旧的） */
export function mergeActions(list: ChatAction[], incoming: ChatAction): ChatAction[] {
  const i = list.findIndex(x => x.id === incoming.id); if (i < 0) return [...list, incoming];
  const next = list.slice(); next[i] = incoming; return next;
}

/** 增量解析 SSE：返回已完整的事件和剩余缓冲 */
export function parseSse(buf: string): { events: ChatEvent[]; rest: string } {
  const events: ChatEvent[] = []; const norm = buf.replace(/\r\n/g, '\n'); const blocks = norm.split('\n\n'); const rest = blocks.pop() ?? '';
  for (const b of blocks) {
    let type = 'message'; const data: string[] = [];
    for (const line of b.split('\n')) { if (line.startsWith('event:')) type = line.slice(6).trim(); else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, '')); }
    if (!data.length) continue; let parsed: any = data.join('\n'); try { parsed = JSON.parse(parsed); } catch { /* 保留原文 */ }
    events.push({ type: type as ChatEvent['type'], data: parsed });
  }
  return { events, rest };
}

/** 发消息并逐条回调事件；HTTP 错误（上限 / 并发 / 不存在）抛出带服务端说明的错误 */
export function streamChat(id: string, text: string, onEvent: (e: ChatEvent) => void, signal?: AbortSignal): Promise<void> { return streamSse(`/api/chats/${encodeURIComponent(id)}/messages`, { text }, onEvent, signal); }
/** 操作完成后的「下一步」提示（流式），每个操作只续一次 */
export function streamFollowup(id: string, actionId: string, onEvent: (e: ChatEvent) => void, signal?: AbortSignal): Promise<void> { return streamSse(`/api/chats/${encodeURIComponent(id)}/actions/${encodeURIComponent(actionId)}/followup`, {}, onEvent, signal); }
async function streamSse(path: string, body: unknown, onEvent: (e: ChatEvent) => void, signal?: AbortSignal): Promise<void> {
  const res = await fetch(apiUrl(path), { method: 'POST', headers: { ...authHeaders(), 'content-type': 'application/json', accept: 'text/event-stream' }, body: JSON.stringify(body), credentials: 'include', signal });
  if (res.status === 401) notifyUnauthorized();
  if (!res.ok || !res.body) { const d = await res.json().catch(() => ({})); throw new Error(d?.message || d?.error || `HTTP ${res.status}`); }
  const reader = res.body.getReader(); const dec = new TextDecoder(); let buf = '';
  for (;;) {
    const { value, done } = await reader.read(); if (done) break;
    buf += dec.decode(value, { stream: true }); const { events, rest } = parseSse(buf); buf = rest; events.forEach(onEvent);
  }
  const { events } = parseSse(buf + '\n\n'); events.forEach(onEvent);
}

export const CLASS_LABEL: Record<string, string> = { frontend: '前端', backend: '后端', fullstack: '全栈', docs: '文档', migration: '数据迁移', infra: '基础设施' };
export const RISK_ZH: Record<string, string> = { low: '低', mid: '中', high: '高', forbidden: '禁区' };
export const lines = (s: string) => s.split('\n').map(x => x.trim()).filter(Boolean);

/** 删除会话：发起人或工作区所有者 / 管理员可删（未开启登录的开发模式下没有登录名，按管理员处理） */
export function canDeleteChat(c: Pick<ChatSession, 'created_by'>, me: string | undefined, manager: boolean): boolean { return manager || (!!me && c.created_by === me); }
/** 这个会话建的任务是否正在等对话里补充信息（任务已建、会话又被重新打开） */
export const waitingClarification = (c: Pick<ChatSession, 'status' | 'task_id'>) => !!c.task_id && c.status === 'open';
/** 删除确认框的说明：先讲删掉什么，再讲任务会不会受影响 */
export function deleteChatCopy(c: Pick<ChatSession, 'title' | 'status' | 'task_id'>): { title: string; description: string } {
  const parts = ['这条记录里的对话、需求草稿、操作卡会一起删除，删了不能恢复。'];
  if (c.task_id) parts.push('已经执行的操作和创建的任务不受影响。');
  if (waitingClarification(c)) parts.push('这个任务正在等你在对话里补充信息；删除后，请到任务详情里修改需求再重新启动。');
  return { title: `删除「${c.title || '新对话'}」？`, description: parts.join('\n') };
}
/** 删除后选中哪个会话：优先列表里的下一个，没有就选上一个，都没有返回 null */
export function nextAfterDelete(list: Pick<ChatSession, 'id'>[], id: string): string | null {
  const i = list.findIndex(c => c.id === id); if (i < 0) return list[0]?.id ?? null;
  return list[i + 1]?.id ?? list[i - 1]?.id ?? null;
}

/** 对话里建好的对象卡片链接：带 from=s/<会话> 让对象页显示「← 返回对话」 */
export function objectHref(o: { kind: 'line' | 'project'; id: string }, chatId?: string): string {
  const base = o.kind === 'line' ? `/l/${encodeURIComponent(o.id)}/overview` : `/p/${encodeURIComponent(o.id)}/detail`;
  return chatId ? `${base}?from=${encodeURIComponent('s/' + chatId)}` : base;
}

/** 流式片段合并：每 ms 毫秒最多刷新一次（markdown 渲染节流）；reset 丢掉没刷出去的片段 */
export function deltaBuffer(apply: (text: string) => void, ms = 50) {
  let buf = ''; let t: ReturnType<typeof setTimeout> | null = null;
  const flush = () => { if (t) { clearTimeout(t); t = null; } if (buf) { const x = buf; buf = ''; apply(x); } };
  return { push(x: string) { buf += x; if (!t) t = setTimeout(flush, ms); }, flush, drop() { buf = ''; if (t) { clearTimeout(t); t = null; } } };
}

/** 把消息与操作卡按时间排在一起：卡片放在「它之前最后一条消息」后面；直播中到达的卡片用 anchor（到达时最后一条消息的 key）定位 */
export function interleave<B extends { key: string; at?: string }>(bubbles: B[], acts: ChatAction[], anchors: Record<string, string> = {}): ({ t: 'b'; b: B } | { t: 'a'; a: ChatAction })[] {
  const after = new Map<number, ChatAction[]>(); const push = (i: number, a: ChatAction) => { after.set(i, [...(after.get(i) ?? []), a]); };
  const T = (x?: string) => (x ? Date.parse(x) : NaN); const sorted = [...acts].sort((x, y) => T(x.createdAt) - T(y.createdAt));
  for (const a of sorted) {
    const k = anchors[a.id]; const ai = k ? bubbles.findIndex(b => b.key === k) : -1;
    if (ai >= 0) { push(ai, a); continue; }
    let idx = -1; bubbles.forEach((b, i) => { if (b.at && T(b.at) <= T(a.createdAt)) idx = i; });
    push(idx, a);
  }
  const out: ({ t: 'b'; b: B } | { t: 'a'; a: ChatAction })[] = (after.get(-1) ?? []).map(a => ({ t: 'a' as const, a }));
  bubbles.forEach((b, i) => { out.push({ t: 'b', b }); for (const a of after.get(i) ?? []) out.push({ t: 'a', a }); });
  return out;
}
/** 已完成 / 已取消的卡片默认收成一行 */
export const collapsible = (a: ChatAction) => a.status === 'executed' || a.status === 'cancelled';

/** 「待我处理」里的待确认操作卡 */
export interface PendingChatAction { id: string; session_id: string; anchor_kind?: string | null; anchor_id?: string | null; task_id?: string | null; kind: string; params: Record<string, any>; created_at: string; error?: string | null; session_title?: string | null }
export const pendingActionHref = (a: PendingChatAction) => sessionHref({ id: a.session_id, anchor_kind: a.anchor_kind, anchor_id: a.anchor_id, task_id: a.task_id } as any);

/** 会话阶段：setup 先定产品线 / 项目（草稿只暂存，不展示）；drafting 才展示需求草稿面板。老数据没有 phase 视为 drafting */
export const showDraft = (s: { phase?: string | null }) => s.phase !== 'setup';
