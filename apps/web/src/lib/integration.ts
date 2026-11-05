export const SOURCE_NAME: Record<string, string> = { oauth: 'GitHub 授权登录', pat: '粘贴的令牌', env: '服务器环境变量', none: '未连接' };
export const MODE_NAME: Record<string, string> = { experience: '体验模式', observe: '只记录模式', execute: '真实执行模式' };
export const MODE_TEXT: Record<string, string> = {
  experience: '还没有连接 GitHub：所有写操作都不会发出，也不会访问 GitHub。',
  observe: '只看不改：会读取仓库与 Issue，但创建 Issue、分支、PR、发布这些操作只记录、不发出。',
  execute: '会真实创建 Issue、分支、PR，触发 CI、合并并发布。请确认目标仓库可以被改动。',
};
export const CHECK_ICON: Record<string, string> = { ok: '✓', fail: '✗', warn: '!', unknown: '?' };
export const CHECK_TONE: Record<string, 'success' | 'danger' | 'warn' | 'neutral'> = { ok: 'success', fail: 'danger', warn: 'warn', unknown: 'neutral' };
export const CHECK_TEXT: Record<string, string> = { ok: '通过', fail: '未通过', warn: '建议处理', unknown: '无法确认' };
export const STATE_TONE: Record<string, 'success' | 'warn' | 'neutral'> = { ok: 'success', attention: 'warn', disconnected: 'neutral' };
export const STATE_TEXT: Record<string, string> = { ok: '正常', attention: '需要处理', disconnected: '未连接', unchecked: '未检查' };
/** 路由里的 integrations/projects 等旧别名 → 标签页 */
export const tabOf = (seg?: string): 'connect' | 'status' => (seg === 'status' ? 'status' : 'connect');
/** 体检结果汇总 */
export function summarizeChecks(items: { status: string }[]) {
  const c = { ok: 0, fail: 0, warn: 0, unknown: 0 } as Record<string, number>; for (const i of items) c[i.status] = (c[i.status] ?? 0) + 1; return c;
}
export const fmtExpiry = (iso?: string | null) => (iso ? iso.replace('T', ' ').slice(0, 16) + ' UTC' : '长期有效（或 GitHub 未提供有效期）');
