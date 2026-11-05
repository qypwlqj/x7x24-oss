import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { CommitteeEditor, ModelHealth } from './Models';

const props = {
  initialSeats: [{ chain: ['a', 'b'] }, { chain: ['a', 'b'] }, { chain: ['a', 'b'] }],
  models: [{ id: 'a', provider: 'p' }, { id: 'b', provider: 'p' }], available: { p: true },
  status: { ready: false, models: ['a', 'a', 'a'], errors: ['评审委员会至少需要 2 个不同模型'] },
  inherited: false, canEdit: true, onSaved: async () => {},
};
describe('committee editor', () => {
  it('hides removed models and distinguishes failures and unavailable providers from healthy calls', () => {
    const breakers = { removed: { open: false, failures: 0, recent: 1 }, a: { open: false, failures: 1, recent: 1 } };
    const failed = renderToStaticMarkup(<ModelHealth breakers={breakers} models={props.models} available={{ p: true }} />);
    expect(failed).not.toContain('removed'); expect(failed).not.toContain('>正常<'); expect(failed).toContain('近期调用有失败');
    const unavailable = renderToStaticMarkup(<ModelHealth breakers={breakers} models={props.models} available={{ p: false }} />);
    expect(unavailable).toContain('不可用'); expect(unavailable).not.toContain('>正常<');
  });
  it('shows the latest model error under the row', () => {
    const breakers = { a: { open: true, failures: 4, recent: 4, lastError: 'LLM HTTP 401: Invalid API key', lastErrorAt: 1, lastStatus: 401 } };
    const html = renderToStaticMarkup(<ModelHealth breakers={breakers} models={props.models} available={{ p: true }} />);
    expect(html).toContain('最近错误'); expect(html).toContain('LLM HTTP 401: Invalid API key'); expect(html).toContain('最近 4 次，失败 4');
  });
  it('shows the configured seats and prevents applying a single-model committee', () => {
    const html = renderToStaticMarkup(<CommitteeEditor {...props} />);
    expect(html).toContain('未就绪'); expect(html).toContain('一键自动分配');
    expect(html).toContain('评委 1 主模型'); expect(html).toContain('兼任仲裁');
    expect(html).toMatch(/<button[^>]*disabled[^>]*>保存评审委员会/);
  });
  it('read-only users see status and model assignments without write actions', () => {
    const html = renderToStaticMarkup(<CommitteeEditor {...props} canEdit={false} />);
    expect(html).toContain('当前生效模型'); expect(html).not.toContain('一键自动分配'); expect(html).not.toContain('保存评审委员会');
  });
});
