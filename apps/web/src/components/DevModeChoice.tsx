/** 开发方式选择（项目设置 / 新建任务 / 任务信息共用）。文案大白话，稿纸卡片。 */
import { RadioGroup } from '@/components/ui/radio-group';
import { Badge, OptionCard } from '../ui';
import type { DevMode } from '@x7x24/shared';

export type DevModeValue = DevMode | 'inherit';
export const DEV_MODE_ZH: Record<DevMode, string> = { sandbox: '沙盒', legacy: '快速方式' };
export const LEGACY_LIMITS = '最多参考 10 个文件，不在本地跑构建和测试，只靠 CI 检查。';

const OPTS: { id: DevModeValue; name: string; text: string; recommended?: boolean }[] = [
  { id: 'sandbox', name: '沙盒', recommended: true, text: '在隔离机器里拉代码、改代码、跑构建和测试，再交给 CI。' },
  { id: 'legacy', name: '快速方式', text: `模型直接写补丁。${LEGACY_LIMITS}适合改文案、小修、测试。` },
];

export function DevModeChoice({ value, onChange, inheritLabel, disabled, name = 'dev-mode', compact }: { value: DevModeValue; onChange: (v: DevModeValue) => void; inheritLabel?: string; disabled?: boolean; name?: string; compact?: boolean }) {
  const opts = inheritLabel ? [{ id: 'inherit' as const, name: '跟随项目', text: inheritLabel }, ...OPTS] : OPTS;
  return <div data-testid={name}>
    <RadioGroup className={compact ? 'grid gap-2' : inheritLabel ? 'grid gap-2 md:grid-cols-3' : 'grid gap-2 md:grid-cols-2'} aria-label="开发方式" value={value} onValueChange={v => onChange(v as DevModeValue)} disabled={disabled}>
      {opts.map(o => <OptionCard key={o.id} value={o.id} data-testid={`${name}-${o.id}`} selected={value === o.id} disabled={disabled}>
        <div className="flex items-center gap-2 text-sm font-bold">{o.name}{'recommended' in o && o.recommended && <Badge tone="primary">推荐</Badge>}</div>
        <p className="mt-1 text-xs text-ink-3">{o.text}</p>
      </OptionCard>)}
    </RadioGroup>
    {value === 'legacy' && <p className="mt-1.5 border-l-2 border-dashed border-warn pl-2 text-caption text-warn-ink" data-testid={`${name}-legacy-note`}>提醒：快速方式{LEGACY_LIMITS}需求偏大时建议用沙盒。</p>}
  </div>;
}
