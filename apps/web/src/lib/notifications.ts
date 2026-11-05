export const NOTIFICATION_EVENTS = [
  { id: 'completed', label: '任务完成', hint: 'GitHub Release 校验、CI 观测与收尾全部完成，不代表平台上线。' },
  { id: 'anomaly', label: '任务异常', hint: '任务正式进入异常处理，普通自动重试不会触发。' },
  { id: 'action_required', label: '需要你处理', hint: '预算不足、测试服务故障或合并需要确认。' },
  { id: 'published', label: '版本已发布', hint: 'GitHub Release 已创建并校验，仍在 CI 观测中；未验证平台上线。' },
  { id: 'recovered', label: '恢复正常', hint: '异常处理后任务恢复执行。' },
  { id: 'discarded', label: '任务废弃', hint: '任务结束，详情中保留原因与处理记录。' },
] as const;
export type NotificationKind = typeof NOTIFICATION_EVENTS[number]['id'];
export interface NotificationPreferences {
  emailEnabled: boolean; inAppEnabled: boolean; scope: 'workspace' | 'projects'; projectIds: string[]; events: Record<NotificationKind, boolean>;
}
export interface NotificationSettings extends NotificationPreferences {
  email: string; verified: boolean; mailReady: boolean; codeExpiresAt: string | null;
}
export interface NotificationItem {
  id: string; task_id: string | null; project_id: string | null; kind: string; purpose: string;
  subject: string; message: string | null; state: string; attempts: number; last_error: string | null;
  created_at: string; sent_at: string | null; read_at: string | null;
}
export interface NotificationInbox { items: NotificationItem[]; unread: number }
export const deliveryLabel = (state: string) => ({ IN_APP: '仅站内', PENDING: '待发送', SENDING: '发送中', RETRY: '等待重试', SENT: '已发送', FAILED: '发送失败', SKIPPED: '已取消' }[state] ?? state);
export const preferencesOf = (s: NotificationPreferences): NotificationPreferences => ({ emailEnabled: s.emailEnabled, inAppEnabled: s.inAppEnabled, scope: s.scope, projectIds: s.scope === 'workspace' ? [] : [...s.projectIds].sort(), events: Object.fromEntries(NOTIFICATION_EVENTS.map(e => [e.id, s.events[e.id]])) as Record<NotificationKind, boolean> });
export const notificationsChanged = () => window.dispatchEvent(new Event('x7-notifications-changed'));
