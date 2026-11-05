import type { SVGProps } from 'react';
import {
  LayoutGrid, List, SlidersVertical, Cpu, Layers, Users, Terminal,
  PanelLeftClose, PanelLeftOpen, Menu, Zap, Coins, Shield, Plug, Sun, Moon,
  X, Trash2, Plus, MessageCircle, Play, Pause, Power, ArrowRight, ArrowLeft,
  Check, TriangleAlert, Clock, Bell, Link, RefreshCw,
} from 'lucide-react';

// Named imports keep unused Lucide icons out of the production bundle.
// Original upstream notices are emitted by build-licenses.ts; see THIRD_PARTY.md.
const icons = {
  grid: LayoutGrid,
  list: List,
  sliders: SlidersVertical,
  cpu: Cpu,
  layers: Layers,
  users: Users,
  terminal: Terminal,
  panelLeft: PanelLeftClose,
  panelRight: PanelLeftOpen,
  menu: Menu,
  bolt: Zap,
  coins: Coins,
  shield: Shield,
  plug: Plug,
  sun: Sun,
  moon: Moon,
  x: X,
  trash: Trash2,
  plus: Plus,
  chat: MessageCircle,
  play: Play,
  pause: Pause,
  power: Power,
  arrow: ArrowRight,
  back: ArrowLeft,
  check: Check,
  alert: TriangleAlert,
  clock: Clock,
  bell: Bell,
  link: Link,
  refresh: RefreshCw,
} as const;

export type IconName = keyof typeof icons;

export function Icon({ name, size = 16, ...rest }: { name: IconName; size?: number } & Omit<SVGProps<SVGSVGElement>, 'name'>) {
  const Component = icons[name];
  return <Component size={size} strokeWidth={1.8} aria-hidden="true" focusable="false" {...rest} />;
}
