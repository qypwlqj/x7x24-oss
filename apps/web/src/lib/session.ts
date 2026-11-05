/** 新对话 → 任务的自动绑定（docs/command-center-v2-nav.md 2.1）。纯逻辑，便于测试 */
import { sessionRedirect } from './commandCenter';

export interface Bindable { id: string; task_id: string | null }
/** 读一次会话：已绑定任务就转到 /t/<id>/chat 并返回 true；否则交给 show 显示 */
export function makeBinder<T extends Bindable>(deps: { fetch: (id: string) => Promise<T>; replace: (to: string) => void; show: (s: T) => void; onBound?: () => void }) {
  return async (id: string): Promise<boolean> => {
    const s = await deps.fetch(id); const to = sessionRedirect(s);
    if (to) { deps.onBound?.(); deps.replace(to); return true; }
    deps.show(s); return false;
  };
}
