import { describe, expect, it } from 'vitest';
import { deliveryLabel, preferencesOf, NOTIFICATION_EVENTS, type NotificationPreferences } from './notifications';

describe('notification preferences', () => {
  const events = Object.fromEntries(NOTIFICATION_EVENTS.map(e => [e.id, true])) as NotificationPreferences['events'];
  it('normalizes a workspace subscription without leaking previously selected projects', () => {
    expect(preferencesOf({ emailEnabled: false, inAppEnabled: true, scope: 'workspace', projectIds: ['other'], events }).projectIds).toEqual([]);
  });
  it('keeps selected projects stable without mutating the input', () => {
    const projectIds = ['b', 'a'];
    const normalized = preferencesOf({ emailEnabled: true, inAppEnabled: true, scope: 'projects', projectIds, events });
    expect(normalized.projectIds).toEqual(['a', 'b']); expect(projectIds).toEqual(['b', 'a']);
    expect(Object.keys(normalized.events)).toHaveLength(6);
  });
  it('distinguishes delivery failures and queued retries from in-app messages', () => {
    expect(deliveryLabel('RETRY')).toBe('等待重试'); expect(deliveryLabel('FAILED')).toBe('发送失败'); expect(deliveryLabel('IN_APP')).toBe('仅站内');
  });
});
