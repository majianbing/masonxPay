'use client';

import { useState } from 'react';
import { ChevronDown, Building2, Store, Check } from 'lucide-react';
import { useAuthStore } from '@/store/auth';
import { cn } from '@/lib/utils';

export default function OrgMerchantSwitcher() {
  const memberships = useAuthStore((s) => s.memberships);
  const activeOrgId = useAuthStore((s) => s.activeOrgId);
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const setActiveMerchant = useAuthStore((s) => s.setActiveMerchant);

  const [open, setOpen] = useState(false);

  const activeOrg = memberships.find((o) => o.organizationId === activeOrgId);
  const activeMerchant = activeOrg?.merchants.find((m) => m.merchantId === activeMerchantId);

  if (!activeOrg) return null;

  return (
    <div className="relative px-3 pb-3">
      <button
        onClick={() => setOpen((v) => !v)}
        className="w-full flex items-center gap-2 rounded-md border bg-gray-50 px-3 py-2 text-sm hover:bg-gray-100 transition-colors"
      >
        <Building2 className="size-4 text-muted-foreground shrink-0" />
        <div className="flex-1 text-left min-w-0">
          <p className="font-medium truncate text-xs">{activeOrg.organizationName}</p>
          <p className="text-xs text-muted-foreground truncate">{activeMerchant?.merchantName ?? 'Select merchant'}</p>
        </div>
        <ChevronDown className={cn('size-4 text-muted-foreground transition-transform shrink-0', open && 'rotate-180')} />
      </button>

      {open && (
        <div className="absolute left-3 right-3 top-full mt-1 z-50 bg-white border rounded-md shadow-lg py-1 max-h-72 overflow-y-auto">
          {memberships.map((org) => (
            <div key={org.organizationId}>
              <div className="flex items-center gap-2 px-3 py-1.5 text-xs font-semibold text-muted-foreground uppercase tracking-wide">
                <Building2 className="size-3" />
                {org.organizationName}
                <span className="text-[10px] normal-case font-normal bg-gray-100 px-1.5 rounded ml-auto">
                  {org.orgRole.replace('ORG_', '')}
                </span>
              </div>
              {org.merchants.map((m) => {
                const isActive = m.merchantId === activeMerchantId;
                return (
                  <button
                    key={m.merchantId}
                    onClick={() => {
                      setActiveMerchant(org.organizationId, m.merchantId);
                      setOpen(false);
                    }}
                    className={cn(
                      'w-full flex items-center gap-2 px-4 py-2 text-sm hover:bg-gray-50 transition-colors',
                      isActive && 'bg-primary/5 text-primary',
                    )}
                  >
                    <Store className="size-3.5 shrink-0" />
                    <span className="flex-1 text-left truncate">{m.merchantName}</span>
                    <span className="text-xs text-muted-foreground">{m.role}</span>
                    {isActive && <Check className="size-3.5 shrink-0" />}
                  </button>
                );
              })}
              {org.merchants.length === 0 && (
                <p className="px-4 py-2 text-xs text-muted-foreground italic">No merchants</p>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
