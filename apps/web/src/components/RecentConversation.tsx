import { useEffect, useRef, useState, type ReactNode } from 'react';
import * as Popover from '@radix-ui/react-popover';
import { Info } from 'lucide-react';
import type { TaskDTO } from '../api';
import { fmtTime } from '../api';
import type { ChatSession } from '../lib/chat';
import { anchorHref, anchorOf, sessionHref } from '../lib/nav';
import { cx } from '../ui/cx';
import { Tip } from '../ui/InfoTip';
import { NodeIcon } from '../ui/NodeIcon';
import { AppLink } from './AppLink';
import { taskStatusLabel } from './TaskInfo';

/** 标题独占主要空间；归属、时间和会话元数据只在可悬停、可点击的详情中展示。 */
export function RecentConversation({ session, task, context, active, menu }: { session: ChatSession; task?: TaskDTO; context: string; active: boolean; menu: ReactNode }) {
  const anchor = anchorOf(session);
  const title = anchor.kind === 'task' ? task?.title || anchor.name || session.title || '新对话' : session.title || '新对话';
  const [open, setOpen] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pinned = useRef(false);
  const restoreFocus = useRef(false);
  const cancelTimer = () => { if (timer.current) clearTimeout(timer.current); timer.current = null; };
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);
  const enter = () => { cancelTimer(); timer.current = setTimeout(() => setOpen(true), 300); };
  const leave = () => { cancelTimer(); if (!pinned.current) timer.current = setTimeout(() => setOpen(false), 180); };
  return <li className={cx('group flex min-w-0 items-center gap-1 rounded-sm px-1.5 py-1', active ? 'bg-ink text-ink-inverse' : 'hover:bg-sunken')} data-testid="recent-session">
    <Tip content={title} placement="right"><AppLink href={sessionHref(session)} aria-current={active ? 'page' : undefined} className="flex min-w-0 flex-1 items-center gap-1.5 py-1 text-sm">
      <NodeIcon name={anchor.kind === 'task' ? 'node-plan' : 'user-input'} size={13} className="shrink-0" /><span className="truncate">{title}</span>
    </AppLink></Tip>
    {Number(session.pending_actions ?? 0) > 0 && <span className="shrink-0 text-caption text-warn-ink group-[.bg-ink]:text-ink-inverse" aria-label={`${session.pending_actions} 张待确认卡片`} data-testid="recent-session-pending">待确认{Number(session.pending_actions) > 1 ? session.pending_actions : ''}</span>}
    <Popover.Root open={open} onOpenChange={value => { cancelTimer(); pinned.current = value; if (value) restoreFocus.current = true; setOpen(value); }}>
      <Popover.Trigger asChild><button type="button" aria-label={`查看「${title}」的会话详情`} className="grid h-7 w-7 shrink-0 place-items-center text-ink-3 opacity-0 group-hover:opacity-100 group-focus-within:opacity-100 group-[.bg-ink]:text-ink-inverse [@media(hover:none)]:opacity-100 [@media(pointer:coarse)]:h-11 [@media(pointer:coarse)]:w-11" data-testid="recent-session-info"
        onPointerEnter={e => { if (e.pointerType === 'mouse') enter(); }} onPointerLeave={e => { if (e.pointerType === 'mouse') leave(); }} onClick={e => { if (open && !pinned.current) { e.preventDefault(); pinned.current = true; restoreFocus.current = true; cancelTimer(); } }}><Info size={14} aria-hidden="true" /></button></Popover.Trigger>
      <Popover.Portal><Popover.Content side="right" align="start" sideOffset={8} collisionPadding={12} onOpenAutoFocus={e => { if (!pinned.current) e.preventDefault(); }} onCloseAutoFocus={e => { if (!restoreFocus.current) e.preventDefault(); restoreFocus.current = false; }} onPointerEnter={cancelTimer} onPointerLeave={e => { if (e.pointerType === 'mouse') leave(); }} className="z-50 w-80 max-w-[calc(100vw-24px)] border-2 border-ink bg-raised p-3 text-sm shadow-[4px_4px_0_rgb(var(--c-line-strong))]" aria-label="会话详情" data-testid="recent-session-popover">
        <p className="break-words text-caption text-ink-3">{context}</p><p className="mt-1 break-words font-bold">{title}</p>
        {task && <p className="mt-2 text-caption text-ink-2">{taskStatusLabel(task)}</p>}
        <p className="mt-1 text-caption text-ink-3">最近更新 {fmtTime(session.updated_at)}</p>
        {anchor.kind === 'task' && session.title && session.title !== title && <p className="mt-1 break-words text-caption text-ink-3">会话：{session.title}</p>}
        {anchorHref(session) && <AppLink className="mt-3 inline-block text-caption underline" href={anchorHref(session)!}>打开所属{anchor.kind === 'task' ? '任务' : anchor.kind === 'project' ? '项目' : '产品线'} →</AppLink>}
      </Popover.Content></Popover.Portal>
    </Popover.Root>
    {menu}
  </li>;
}
