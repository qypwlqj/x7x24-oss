import type { ReactNode } from 'react';
import { TabsList, TabsRoot, TabsTrigger } from '@/components/ui/misc';

export interface TabItem<T extends string> { id: T; label: ReactNode; count?: number }
/** 下划线式 Tabs（Radix Tabs：方向键 / Home / End、roving tabindex 由 Radix 处理） */
export function Tabs<T extends string>({ items, value, onChange, label, className }: { items: TabItem<T>[]; value: T; onChange: (v: T) => void; label: string; className?: string }) {
  return (
    <TabsRoot value={value} onValueChange={(v) => onChange(v as T)} activationMode="automatic">
      <TabsList aria-label={label} className={className}>
        {items.map((t) => <TabsTrigger key={t.id} value={t.id} id={`tab-${t.id}`}>
          {t.label}{t.count !== undefined && <span className="ml-1.5 border border-line bg-sunken px-1 text-caption tabular text-ink-2">{t.count}</span>}
        </TabsTrigger>)}
      </TabsList>
    </TabsRoot>
  );
}
