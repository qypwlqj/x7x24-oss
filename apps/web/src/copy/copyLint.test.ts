import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { BANNED } from './terms';
import { readdirSync, statSync } from 'node:fs';
import { EVT } from '../labels';
import { describeEvent } from '../lib/eventText';

/** 文案守护：已整改的文件里，给用户看的中文文案不得出现禁用术语、内部 id 或多余英文。豁免：注释、<code>…</code>、行尾 copy-ok、正则字面量 */
export const CHECKED = ['views/TaskDetail.tsx', 'components/TaskBudgetCard.tsx', 'components/TaskSwitchDefaults.tsx', 'components/CodeReviewCard.tsx', 'views/Governance.tsx', 'components/PipelineGraph.tsx', 'components/ComposableSettings.tsx', 'components/NodeBoard.tsx', 'components/TaskPipeline.tsx', 'components/ProjectPipelineSettings.tsx', 'components/PresetsCard.tsx', 'components/TaskFlow.tsx', 'lib/taskFlow.ts', 'views/Project.tsx', 'api.ts', 'views/LinePipeline.tsx', 'views/Governance.tsx', 'views/Models.tsx', 'views/ProviderOnboarding.tsx', 'views/Integrations.tsx', 'lib/glossary.ts', 'lib/auth.ts', 'labels.ts', 'lib/eventText.ts', 'lib/budget.ts', 'lib/integration.ts', 'App.tsx', 'views/Overview.tsx', 'views/Tasks.tsx', 'components/MergeGateCard.tsx'];
const ALLOW_EN = new Set(['App', 'Actions', 'DeepSeek', 'OpenAI', 'https', 'GitHub', 'PR', 'PRD', 'CI', 'Token', 'USD', 'CSV', 'JSON', 'API', 'AI', 'Issue', 'Render', 'Vercel', 'URL', 'ID', 'OK', 'open-code-review']);
const CJK = /[\u4e00-\u9fff]/;
/** 把模板字符串里的 ${…}（可嵌套）替换成占位符，避免表达式里的代码被当成文案 */
function stripExpr(l: string) { let o = '', d = 0; for (let i = 0; i < l.length; i++) { if (d === 0 && l[i] === '$' && l[i + 1] === '{') { d = 1; i++; o += '§'; continue; } if (d > 0) { if (l[i] === '{') d++; else if (l[i] === '}') d--; continue; } o += l[i]; } return o; }
export function lintText(src: string, file = ''): string[] {
  const out: string[] = [];
  src.split('\n').forEach((raw, i) => {
    if (/copy-ok/.test(raw)) return;
    const line = raw.replace(/<code>.*?<\/code>/g, '').replace(/\/\*.*?\*\//g, '').replace(/^\s*(\/\/|\*|\/\*\*?).*$/, '').replace(/\/[^/\n]*[\u4e00-\u9fff][^/\n]*\/[gimsuy]*\.(test|exec)/g, ''); const ln = stripExpr(line);
    const texts = [...ln.matchAll(/'([^'\n]*)'|"([^"\n]*)"|`([^`\n]*)`|>([^<>{}\n]+)</g)].map(m => m[1] ?? m[2] ?? m[3] ?? m[4] ?? '').filter(t => CJK.test(t));
    for (const t of texts) {
      const plain = t;
      for (const w of BANNED) if (plain.includes(w)) out.push(`${file}:${i + 1} 禁用词「${w}」：${plain.slice(0, 60)}`);
      if (/\b[a-z]+\.[a-z]+\.[a-z]+\b/.test(plain) || /\b[A-Z]{2,}_[A-Z_]{2,}\b/.test(plain)) out.push(`${file}:${i + 1} 内部 id 出现在正文：${plain.slice(0, 60)}`);
      for (const en of plain.match(/[A-Za-z][A-Za-z-]{2,}/g) ?? []) if (!ALLOW_EN.has(en)) out.push(`${file}:${i + 1} 英文「${en}」：${plain.slice(0, 60)}`);
    }
  });
  return out;
}
describe('copy lint', () => {
  it('rejects banned terms, ids and stray English', () => {
    expect(lintText("const a = '技能会拼进提示词';")).toHaveLength(2);
    expect(lintText("const a = '角色 sandbox.dev.code';")).not.toHaveLength(0);
    expect(lintText("const a = '<code>dev.code</code> 已保存'; // copy-ok")).toHaveLength(0);
    expect(lintText("const a = '连接 GitHub 后创建 PR';")).toHaveLength(0);
  });
  it('checked files are clean', () => {
    const bad = CHECKED.flatMap(f => lintText(readFileSync(join(__dirname, '..', f), 'utf8'), f));
    expect(bad).toEqual([]);
  });
  it('every task event type the server writes has a Chinese label and a timeline text', () => {
    const root = join(__dirname, '..', '..', '..', 'server-java', 'src', 'main', 'java');
    const files: string[] = []; const walk = (d: string) => readdirSync(d).forEach(n => { const f = join(d, n); statSync(f).isDirectory() ? walk(f) : f.endsWith('.java') && files.push(f); }); walk(root);
    const types = new Set<string>();
    for (const f of files) for (const m of readFileSync(f, 'utf8').matchAll(/audit\.task\([^;]*?"([A-Z][A-Z_]{3,})"/g)) types.add(m[1]);
    expect(types.has('STAGE_SKIPPED')).toBe(true);
    const missing = [...types].filter(t => !EVT[t] || !CJK.test(EVT[t]));
    expect(missing).toEqual([]);
    expect(describeEvent({ event_type: 'STAGE_SKIPPED', payload: { stage: 'DESIGN', reason: '只改翻译' } }).text).toContain('只改翻译');
  });
});
