import { describe, expect, it } from 'vitest';
import { legacyHashTarget, navigationTarget } from './navigation';
import { parseRoute } from '../router';
import { inviteLink } from './workspace';
import { chatLink } from './command';
import { parseAuthCallback } from './auth';

describe('App Router migration', () => {
  it('retains old saved page links, query parameters and encoded IDs', () => {
    expect(legacyHashTarget('#/t/task%201/detail?tab=evidence&focus=QA')).toBe('/t/task%201/detail?tab=evidence&focus=QA');
    expect(legacyHashTarget('#/integrations/status?source=notice')).toBe('/admin/integrations/status?source=notice');
    expect(navigationTarget('/project/p_1?from=s%2Fchat_1')).toBe('/p/p_1/tasks?from=s%2Fchat_1');
    expect(legacyHashTarget('#main')).toBeNull();
    expect(navigationTarget('s/chat_1')).toBe('/s/chat_1');
    expect(parseRoute('/t/task%201/detail?tab=evidence&focus=QA')).toEqual(['t', 'task 1', 'detail', 'evidence', 'QA']);
  });
  it('keeps OAuth credentials in a fragment instead of a route query', () => {
    expect(legacyHashTarget('#/auth/callback?token=a.b', '?github_reconnect=1')).toBe('/overview?github_reconnect=1#token=a.b');
    expect(parseAuthCallback('#token=a.b')).toMatchObject({ token: 'a.b' });
    expect(parseAuthCallback('#/auth/callback?token=a.b')).toMatchObject({ token: 'a.b' });
  });
  it('shared links are independent of the current nested page', () => {
    const location = { origin: 'https://app.example', pathname: '/admin/members' };
    expect(inviteLink(location, 'invite-1')).toBe('https://app.example/invite/invite-1');
    expect(chatLink(location, 'chat_1')).toBe('https://app.example/s/chat_1');
  });
});
