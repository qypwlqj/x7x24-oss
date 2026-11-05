import { describe, expect, it } from 'vitest';
import { isSessionExpired } from '../api';

describe('isSessionExpired（登录后被踢回首页的回归）', () => {
  it('本站会话失效才登出', () => { expect(isSessionExpired(401, '/api/chats', { error: 'unauthorized', message: '请先用 GitHub 登录' })).toBe(true); });
  it('GitHub 令牌被拒（旧后端 401 + githubStatus）不登出', () => { expect(isSessionExpired(401, '/api/integrations/github/repos', { error: 'GitHub 拒绝了令牌', githubStatus: 401 })).toBe(false); });
  it('非 401 / auth 接口不登出', () => { expect(isSessionExpired(502, '/api/x', { error: 'unauthorized' })).toBe(false); expect(isSessionExpired(401, '/api/auth/me', { error: 'unauthorized' })).toBe(false); });
});
