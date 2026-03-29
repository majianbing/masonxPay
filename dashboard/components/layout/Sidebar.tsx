'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useState } from 'react';
import {
  LayoutDashboard, CreditCard, RotateCcw, GitBranch,
  Key, Webhook, FileText, Users, Settings, ChevronRight, Plug, Link2, Zap, ShieldCheck,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import OrgMerchantSwitcher from './OrgMerchantSwitcher';

const nav = [
  { href: '/overview', label: 'Overview', icon: LayoutDashboard },
  { href: '/payments', label: 'Payments', icon: CreditCard },
  { href: '/refunds', label: 'Refunds', icon: RotateCcw },
  { href: '/routing/rules', label: 'Routing Rules', icon: GitBranch },
  { href: '/connectors', label: 'Connectors', icon: Plug },
  { href: '/payment-links', label: 'Payment Links', icon: Link2 },
  {
    label: 'Developers', icon: Key,
    children: [
      { href: '/developers/quickstart', label: 'Quickstart', icon: Zap },
      { href: '/developers/api-keys', label: 'API Keys', icon: Key },
      { href: '/developers/webhooks', label: 'Webhooks', icon: Webhook },
      { href: '/developers/logs', label: 'Logs', icon: FileText },
    ],
  },
  { href: '/team', label: 'Team', icon: Users },
  {
    label: 'Settings', icon: Settings,
    children: [
      { href: '/settings/merchant', label: 'Merchant', icon: Settings },
      { href: '/settings/security', label: 'Security', icon: ShieldCheck },
    ],
  },
];

export default function Sidebar() {
  const pathname = usePathname();
  const [open, setOpen] = useState<Record<string, boolean>>({
    Developers: pathname.startsWith('/developers'),
    Settings: pathname.startsWith('/settings'),
  });

  function toggle(label: string) {
    setOpen((prev) => ({ ...prev, [label]: !prev[label] }));
  }

  return (
    <aside className="w-60 shrink-0 border-r bg-white flex flex-col h-full">
      <div className="px-6 py-5 border-b">
        <span className="font-semibold text-lg tracking-tight">MasonX</span>
      </div>

      <div className="border-b pt-3">
        <OrgMerchantSwitcher />
      </div>

      <nav className="flex-1 overflow-y-auto py-4 px-3">
        {nav.map((item) =>
          'children' in item ? (
            <div key={item.label} className="mb-1">
              <button
                onClick={() => toggle(item.label)}
                className="flex w-full items-center gap-2 px-3 py-2 text-xs font-medium text-muted-foreground uppercase tracking-wider hover:text-foreground transition-colors"
              >
                <item.icon className="size-4" />
                {item.label}
                <ChevronRight className={cn('size-3 ml-auto transition-transform', open[item.label] && 'rotate-90')} />
              </button>
              {open[item.label] && item.children?.map((child) => (
                <NavLink key={child.href} href={child.href} label={child.label} icon={child.icon} pathname={pathname} indent />
              ))}
            </div>
          ) : (
            <NavLink key={item.href} href={item.href} label={item.label} icon={item.icon} pathname={pathname} />
          )
        )}
      </nav>
    </aside>
  );
}

function NavLink({
  href, label, icon: Icon, pathname, indent,
}: {
  href: string; label: string; icon: React.ElementType; pathname: string; indent?: boolean;
}) {
  const active = pathname.startsWith(href);
  return (
    <Link
      href={href}
      className={cn(
        'flex items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors',
        indent && 'pl-6',
        active
          ? 'bg-primary/10 text-primary font-medium'
          : 'text-muted-foreground hover:bg-gray-100 hover:text-foreground',
      )}
    >
      <Icon className="size-4" />
      {label}
      {active && <ChevronRight className="size-3 ml-auto" />}
    </Link>
  );
}
