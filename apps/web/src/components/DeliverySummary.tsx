import type { TaskDTO } from '../api';
import { AppLink } from './AppLink';
import { Badge, Card } from '../ui';

export function DeliverySummary({ task, showEmpty = false }: { task: Pick<TaskDTO, 'delivery' | 'projectId' | 'executionState'>; showEmpty?: boolean }) {
  const delivery = task.delivery;
  if (!delivery) return showEmpty ? <Card title="交付"><p className="text-sm text-ink-2">还没有交付记录。发布产物、版本与上线状态将在这里展示。</p></Card> : null;
  const waiting = delivery.deployment === 'WAITING_CONFIGURATION';
  return <Card title="发布与上线" data-testid="delivery-summary">
    <p className="text-sm">发布目标：{delivery.target === 'wechat_miniprogram' ? '微信小程序' : 'GitHub Release'}</p>
    <div className="mt-2 flex flex-wrap gap-2">
      <Badge tone={delivery.githubRelease === 'VERIFIED' ? 'success' : 'neutral'}>GitHub Release：{({ NOT_CREATED: '未创建', CREATED: '已创建，待校验', VERIFIED: '已创建并校验' })[delivery.githubRelease]}</Badge>
      <Badge tone={waiting ? 'warn' : 'neutral'}>平台上线：{waiting ? '待配置发布' : '未验证'}</Badge>
    </div>
    <p className="mt-2 text-sm text-ink-2">{delivery.detail}</p>
    {waiting && <AppLink className="mt-2 inline-block text-sm underline" href={`/p/${encodeURIComponent(task.projectId)}/detail`}>查看项目发布配置 →</AppLink>}
  </Card>;
}
