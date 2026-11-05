import { describe, expect, it } from 'vitest';
import { legacyRedirect, parseRoute } from './router';

describe('旧指挥台路由', () => {
  it('旧的 /chat 重定向到新对话 / 会话视图，保留会话 id', () => {
    expect(legacyRedirect('/chat')).toBe('/new');
    expect(legacyRedirect('/chat/chat_1a2b')).toBe('/s/chat_1a2b');
    expect(legacyRedirect('#chat')).toBe('/new');
  });
  it('其它路由不受影响', () => {
    expect(legacyRedirect('/command/x')).toBeNull(); expect(legacyRedirect('/chatter')).toBeNull(); expect(legacyRedirect('/tasks')).toBeNull();
    expect(parseRoute('/command/x')).toEqual(['command', 'x']);
  });
});
