import { AppLink } from '@/components/AppLink';
/** 左下角用户名弹出菜单：个人项 + 工作区管理项（按角色） + 切换工作区 / 平台管理 / 退出（docs/command-center-v2-nav.md 2.5） */
import * as Popover from '@radix-ui/react-popover';
import { userMenu, type Role } from '../lib/nav';
import { cx } from '../ui';
import { NodeIcon } from '../ui/NodeIcon';

export interface UserMenuProps { login: string; name?: string | null; avatarUrl?: string | null; role: Role; roleText: string; wsName?: string; platformAdmin?: boolean; workspaces?: { id: string; name: string }[]; currentWs?: string; onSwitch?: (id: string) => void; onLogout: () => void; compact?: boolean; defaultOpen?: boolean }
export function UserMenuItems({ role, platformAdmin, workspaces = [], currentWs, onSwitch, onLogout }: Pick<UserMenuProps, 'role' | 'platformAdmin' | 'workspaces' | 'currentWs' | 'onSwitch' | 'onLogout'>) {
  const items = userMenu(role, platformAdmin); const groups = ['个人', '工作区 · 管理', '其它'] as const;
  return <div className="py-1 text-sm" data-testid="user-menu-items">
    {groups.map(g => { const xs = items.filter(i => i.group === g); if (!xs.length) return null;
      return <div key={g} className="border-b border-dashed border-line pb-1 last:border-0"><div className="label-caps px-3 pb-0.5 pt-1.5 text-[10px] text-ink-3">{g}</div>
        {xs.map(i => <Popover.Close key={i.id} asChild><AppLink href={i.href} className="flex items-center gap-2 px-3 py-1.5 hover:bg-sunken" data-edit={i.edit ? '1' : undefined} data-testid={`user-menu-${i.id}`}><NodeIcon name={i.icon} size={14} />{i.label}</AppLink></Popover.Close>)}</div>; })}
    {workspaces.length > 1 && <div className="border-b border-dashed border-line py-1"><div className="label-caps px-3 pb-0.5 text-[10px] text-ink-3">切换工作区</div>
      {workspaces.map(w => <button key={w.id} type="button" onClick={() => onSwitch?.(w.id)} className={cx('block w-full px-3 py-1 text-left hover:bg-sunken', w.id === currentWs && 'font-bold')}>{w.id === currentWs ? '✓ ' : ''}{w.name}</button>)}</div>}
    <button type="button" onClick={onLogout} className="block w-full px-3 py-1.5 text-left text-danger-ink hover:bg-sunken" data-testid="logout">退出登录</button>
  </div>;
}
export function UserMenu(p: UserMenuProps) {
  return <Popover.Root defaultOpen={p.defaultOpen}>
    <Popover.Trigger className={cx('flex w-full items-center gap-2 border-t-2 border-ink py-2.5 text-left hover:bg-sunken', p.compact ? 'justify-center px-1' : 'px-3.5')} aria-label={`${p.login} 的菜单`} data-testid="user-menu">
      {p.avatarUrl ? <img src={p.avatarUrl} alt="" className="h-7 w-7 shrink-0 border-2 border-ink" /> : <span className="grid h-7 w-7 shrink-0 place-items-center border-2 border-ink bg-sunken font-mono text-xs font-bold">{p.login[0]}</span>}
      {!p.compact && <span className="min-w-0 flex-1"><span className="block truncate text-sm font-semibold">{p.name || p.login}</span><span className="block truncate text-caption text-ink-3">{p.roleText}{p.wsName ? ` · ${p.wsName}` : ''}</span></span>}
      {!p.compact && <span className="text-ink-3">⋯</span>}
    </Popover.Trigger>
    <Popover.Portal><Popover.Content side="top" align="start" sideOffset={6} className="z-50 w-64 border-2 border-ink bg-raised shadow-[5px_5px_0_rgb(var(--c-line-strong))]" data-testid="user-popover">
      <UserMenuItems {...p} />
    </Popover.Content></Popover.Portal>
  </Popover.Root>;
}
