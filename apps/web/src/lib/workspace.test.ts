import { describe, expect, it } from 'vitest';
import { canManage, canWrite, quotaPatch, roleLabel } from './workspace';

describe('workspace helpers', () => {
  it('roles', () => {
    expect(canManage('owner')).toBe(true); expect(canManage('admin')).toBe(true); expect(canManage('member')).toBe(false);
    expect(canWrite('viewer')).toBe(false); expect(canWrite('member')).toBe(true); expect(canWrite(undefined)).toBe(false);
    expect(roleLabel('viewer')).toBe('只读'); expect(roleLabel('x')).toBe('x');
  });
  it('quota patch', () => {
    expect(quotaPatch({ llmFallback: true, usd: '5', conc: '' })).toEqual({ llmFallback: true, llmFallbackUsdMonth: 5, maxConcurrentTasks: null });
    expect(() => quotaPatch({ llmFallback: false, usd: '-1', conc: '' })).toThrow();
    expect(() => quotaPatch({ llmFallback: false, usd: '', conc: '1.5' })).toThrow();
  });
});

import { assignableRoles, inviteLink } from './workspace';
describe('member management rules', () => {
  it('invite link', () => { expect(inviteLink({ origin: 'https://x7x24.vercel.app', pathname: '/' }, 'a-b_c')).toBe('https://x7x24.vercel.app/invite/a-b_c'); });
  it('assignable roles mirror backend', () => {
    expect(assignableRoles('owner', 'admin', false)).toEqual(['admin', 'member', 'viewer']);
    expect(assignableRoles('owner', 'owner', false)).toEqual([]);
    expect(assignableRoles('owner', 'member', true)).toEqual([]);
    expect(assignableRoles('admin', 'member', false)).toEqual(['member', 'viewer']);
    expect(assignableRoles('admin', 'admin', false)).toEqual([]);
    expect(assignableRoles('member', 'viewer', false)).toEqual([]);
  });
});
