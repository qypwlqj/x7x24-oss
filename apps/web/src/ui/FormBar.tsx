import type { ReactNode } from 'react';
import { Button } from './Button';
import { Callout } from './Misc';

/** 统一的表单底栏：保存 / 恢复默认 / 校验提示 / 保存成功反馈 */
export function FormBar({ dirty, busy, onSave, onReset, resetLabel = '恢复默认', saveLabel = '保存', errors = [], success, extra }: {
  dirty: boolean; busy?: boolean; onSave: () => void; onReset?: () => void; resetLabel?: string; saveLabel?: string; errors?: string[]; success?: string | null; extra?: ReactNode;
}) {
  return (
    <div className="mt-4 space-y-2" data-testid="form-bar">
      {errors.length > 0 && <Callout tone="danger" icon="alert"><ul className="list-disc pl-4">{errors.map((e, i) => <li key={i}>{e}</li>)}</ul></Callout>}
      {success && errors.length === 0 && <Callout tone="success" icon="check">{success}</Callout>}
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="primary" loading={busy} disabled={!dirty || errors.length > 0} onClick={onSave}>{saveLabel}</Button>
        {onReset && <Button onClick={onReset} disabled={busy}>{resetLabel}</Button>}
        {dirty && errors.length === 0 && <span className="text-xs text-warn-ink">有未保存的修改</span>}
        {extra}
      </div>
    </div>
  );
}
