import * as Popover from '@radix-ui/react-popover';
import { Wifi, WifiOff } from 'lucide-react';
import type { StreamMode, TaskStreamState } from '../lib/taskStream';
import { Tip } from '../ui/InfoTip';
import { cx } from '../ui';

export function TaskStreamStatus({ mode, state }: { mode: StreamMode; state?: TaskStreamState }) {
  const label = mode === 'live' ? '实时连接正常，自动同步' : mode === 'polling' ? '实时连接恢复中，每 3 秒同步进度' : mode === 'offline' ? '网络离线，等待恢复' : mode === 'blocked' ? state?.terminalStatus === 401 ? '登录已失效，请重新登录' : state?.terminalStatus === 403 ? '没有权限查看此任务' : '任务不存在或无权查看' : mode === 'error' ? '状态更新失败，正在重试' : '正在连接实时更新…';
  const time = (at?: number | null) => at ? new Date(at).toLocaleTimeString('zh-CN', { hour12: false }) : '尚未收到';
  const reason = state?.lastDisconnect;
  const reasons: Record<string, string> = { transport_error: '实时传输异常（浏览器未提供具体原因）', heartbeat_timeout: '超过心跳等待时间', handshake_timeout: '连接建立超时', offline: '浏览器报告网络离线', unauthorized: '登录已失效', forbidden: '没有访问权限', not_found: '任务不存在或无权查看', capacity_limit: '服务端连接容量已满', request_failed: '进度请求失败', unsupported: '浏览器不支持实时推送' };
  const ConnectionIcon = mode === 'live' ? Wifi : WifiOff;
  return <Popover.Root><Tip content={label}><Popover.Trigger asChild><button type="button" aria-label={label} className={cx('inline-flex min-h-6 items-center gap-1.5 text-caption [@media(pointer:coarse)]:min-h-11', mode === 'live' ? 'text-ink-3' : 'text-warn-ink')} data-testid="cc-stream-mode" data-mode={mode}><ConnectionIcon size={14} aria-hidden="true" />{mode !== 'live' && <span role="status">{label}</span>}</button></Popover.Trigger></Tip>
    <Popover.Portal><Popover.Content side="top" align="end" sideOffset={6} collisionPadding={12} className="z-50 max-w-[calc(100vw-24px)] border-2 border-ink bg-raised p-3 text-sm shadow-[4px_4px_0_rgb(var(--c-line-strong))]"><p>{label}</p>{state && <dl className="mt-2 space-y-1 text-caption text-ink-3" aria-label="连接诊断">
      <div><dt className="inline">最后成功同步：</dt><dd className="inline">{time(state.lastSyncAt)}</dd></div>
      <div><dt className="inline">最后心跳：</dt><dd className="inline">{time(state.lastHeartbeatAt)}</dd></div>
      <div><dt className="inline">重试次数：</dt><dd className="inline">{state.retryCount ?? 0}</dd></div>
      {state.nextRetryAt && <div><dt className="inline">下次连接尝试：</dt><dd className="inline">{time(state.nextRetryAt)}</dd></div>}
      {reason && <div><dt className="inline">最近异常：</dt><dd className="inline">{reasons[reason.reason] ?? '原因未确认'}{reason.httpStatus ? `（HTTP ${reason.httpStatus}）` : ''} · {time(reason.at)}</dd></div>}
      {state.connectionId && <div className="max-w-80 break-all"><dt className="inline">连接编号：</dt><dd className="inline">{state.connectionId}</dd></div>}
    </dl>}</Popover.Content></Popover.Portal>
  </Popover.Root>;
}
