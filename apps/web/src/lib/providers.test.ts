import { describe, expect, it } from 'vitest';
import { addManual, applyPreset, changeJudgePrimary, judgeDraftErrors, maskKey, picksFrom, saveBody, type Preset } from './providers';

const presets: Preset[] = [
  { id: 'deepseek', name: 'DeepSeek', baseUrl: 'https://api.deepseek.com', models: [{ id: 'deepseek-chat', contextTokens: 64000, maxOutputTokens: 8192 }] },
  { id: 'openai', name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', models: [] },
  { id: 'custom', name: '自定义', baseUrl: '', models: [] },
];
describe('providers', () => {
  it('preset fills name + baseUrl but keeps custom name', () => {
    const a = applyPreset({ name: '', preset: '', baseUrl: '', apiKey: '' }, presets, 'deepseek');
    expect(a).toMatchObject({ name: 'DeepSeek', baseUrl: 'https://api.deepseek.com' });
    expect(applyPreset(a, presets, 'openai')).toMatchObject({ name: 'OpenAI', baseUrl: 'https://api.openai.com/v1' });
    expect(applyPreset({ ...a, name: '我的' }, presets, 'openai').name).toBe('我的');
    expect(applyPreset(a, presets, 'custom').baseUrl).toBe('');
  });
  it('picks use known limits and manual entry', () => {
    const p = picksFrom([{ id: 'deepseek-chat' }, { id: 'x' }], presets[0], []);
    expect(p[0]).toMatchObject({ checked: true, contextTokens: 64000 }); expect(p[1].checked).toBe(false);
    const m = addManual(p, ' y '); expect(m.at(-1)).toMatchObject({ id: 'y', checked: true, contextTokens: 32000 }); expect(addManual(m, 'y')).toHaveLength(3);
  });
  it('save body omits blank key and unchecked models', () => {
    const b = saveBody({ name: 'D', preset: 'deepseek', baseUrl: ' https://a ', apiKey: '  ' }, [{ id: 'a', checked: true }, { id: 'b', checked: false }], ['exec']);
    expect(b.apiKey).toBeUndefined(); expect(b.models.map(m => m.id)).toEqual(['a']); expect(b.baseUrl).toBe('https://a');
    expect(maskKey({ id: 'd', name: 'd', baseUrl: '', keySource: 'db', last4: 'ab12', available: true, models: [] })).toBe('••••ab12');
  });
  it('changing a judge primary preserves backups without mutating saved seats', () => {
    const saved = [{ chain: ['a', 'b'], temperature: 0.2 }, { chain: ['a', 'b'] }, { chain: ['a', 'b'] }];
    const draft = changeJudgePrimary(saved, 1, 'b');
    expect(saved[1].chain).toEqual(['a', 'b']);
    expect(draft[1].chain).toEqual(['b', 'a']);
    expect(judgeDraftErrors(saved)).toContain('至少需要两个不同的评审模型');
    expect(judgeDraftErrors(draft)).toEqual([]);
    expect(judgeDraftErrors([{ chain: ['a'] }, { chain: [] }])).toHaveLength(2);
  });
});
