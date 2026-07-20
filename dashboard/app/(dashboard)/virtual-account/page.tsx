'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, CircleHelp, Landmark, RefreshCcw, WalletCards } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';

interface LedgerAccount {
  ledgerAccountId: string;
  mode: string;
  ledgerAccountType: string;
  accountClass: string;
  normalBalance: string;
  merchantId: string;
  asset: string;
  balance: number | string;
  status: string;
}

interface VaAccountsResponse {
  enabled: boolean;
  unavailableReason: string | null;
  content: LedgerAccount[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

const ACCOUNT_LABELS: Record<string, string> = {
  CASH: 'Cash',
  WALLET: 'Wallet',
  MERCHANT_RECEIVABLE: 'Merchant receivable',
};

const MERCHANT_FINANCIAL_ACCOUNT_TYPES = new Set(['CASH', 'WALLET', 'MERCHANT_RECEIVABLE']);

const SUMMARY_HELP: Record<string, string> = {
  WALLET: 'Funds MasonXPay is holding for this merchant. This is the merchant-available balance inside VA.',
  CASH: 'External cash mirror for bank/provider settlement activity. It helps reconcile money that moved through outside rails.',
  MERCHANT_RECEIVABLE: 'Amount the merchant owes back after returns or reversals exceed the available wallet balance.',
};

const ACCOUNT_HELP: Record<string, string> = {
  CASH: 'Tracks external settlement cash from the platform-book view. Usually changes when bank or provider settlement events arrive.',
  WALLET: 'Tracks merchant funds held by the platform. Credits increase the merchant wallet; debits spend or withdraw from it.',
  MERCHANT_RECEIVABLE: 'Tracks merchant debt to the platform. It is created when a return cannot be fully taken from the wallet, then reduced by later settlements.',
};

const ACCOUNT_CLASS_HELP: Record<string, string> = {
  ASSET: 'Something owed to the platform or controlled by the platform, such as cash mirrors or receivables.',
  LIABILITY: 'Funds the platform owes to a merchant, cardholder, or outside party.',
  REVENUE: 'Platform-earned income.',
  EXPENSE: 'Platform cost or write-off.',
};

const NORMAL_BALANCE_HELP: Record<string, string> = {
  DEBIT: 'Debit-normal accounts increase with debit entries and decrease with credit entries.',
  CREDIT: 'Credit-normal accounts increase with credit entries and decrease with debit entries.',
};

function accountLabel(type: string) {
  return ACCOUNT_LABELS[type] ?? type.replaceAll('_', ' ').toLowerCase();
}

function accountHelp(type: string) {
  return ACCOUNT_HELP[type] ?? 'Ledger account used by VA to keep double-entry balances balanced and auditable.';
}

function amountValue(balance: number | string) {
  return typeof balance === 'number' ? balance : Number(balance);
}

function formatAmount(balance: number | string, asset: string) {
  const value = amountValue(balance);
  if (!Number.isFinite(value)) return `0.00 ${asset}`;
  return `${value.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })} ${asset}`;
}

export default function VirtualAccountPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const size = 100;

  const { data, isLoading, refetch, isFetching } = useQuery<VaAccountsResponse>({
    queryKey: ['va-accounts', activeMerchantId, mode],
    enabled: !!activeMerchantId,
    queryFn: () =>
      apiFetch<VaAccountsResponse>(
        `/api/v1/merchants/${activeMerchantId}/va/accounts?mode=${mode}&page=0&size=${size}`,
      ),
  });

  const accounts = data?.content ?? [];
  const financialAccounts = useMemo(
    () => accounts.filter((account) => MERCHANT_FINANCIAL_ACCOUNT_TYPES.has(account.ledgerAccountType)),
    [accounts],
  );
  const cardBackingAccountCount = accounts.length - financialAccounts.length;
  const summary = useMemo(() => {
    const byType = new Map<string, LedgerAccount>();
    for (const account of financialAccounts) {
      byType.set(account.ledgerAccountType, account);
    }
    return [
      { key: 'WALLET', label: 'Wallet balance', account: byType.get('WALLET') },
      { key: 'CASH', label: 'Cash mirror', account: byType.get('CASH') },
      { key: 'MERCHANT_RECEIVABLE', label: 'Merchant receivable', account: byType.get('MERCHANT_RECEIVABLE') },
    ];
  }, [financialAccounts]);

  if (!activeMerchantId) {
    return (
      <div className="space-y-4">
        <PageHeader onRefresh={() => refetch()} refreshing={isFetching} />
        <EmptyState title="No merchant selected" detail="Select a merchant to view Virtual Account balances." />
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <PageHeader onRefresh={() => refetch()} refreshing={isFetching} />

      {data && !data.enabled && (
        <div className="flex items-start gap-3 rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          <AlertTriangle className="mt-0.5 size-4 shrink-0" />
          <div>
            <p className="font-medium">Virtual Account service is not available</p>
            <p className="text-amber-800">{data.unavailableReason ?? 'Start VA to view ledger accounts.'}</p>
          </div>
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-3">
        {summary.map((item) => (
          <Card key={item.key}>
            <CardHeader className="pb-2">
              <CardTitle className="flex items-center gap-1.5 text-sm font-medium text-muted-foreground">
                {item.label}
                <HelpTip label={`${item.label} help`} content={SUMMARY_HELP[item.key]} />
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-semibold tracking-normal">
                {item.account ? formatAmount(item.account.balance, item.account.asset) : `0.00 ${accounts[0]?.asset ?? 'USD'}`}
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                {item.account ? `${item.account.normalBalance} normal` : mode}
              </p>
            </CardContent>
          </Card>
        ))}
      </div>

      <section className="rounded-md border bg-white">
        <div className="flex items-center justify-between border-b px-4 py-3">
          <div>
            <h2 className="text-sm font-semibold">Ledger Accounts</h2>
            <p className="text-xs text-muted-foreground">
              {mode} mode merchant financial accounts
              {cardBackingAccountCount > 0 ? `; ${cardBackingAccountCount} card backing accounts hidden` : ''}
            </p>
          </div>
          <Badge variant="outline">{financialAccounts.length} accounts</Badge>
        </div>

        {isLoading ? (
          <div className="px-4 py-10 text-center text-sm text-muted-foreground">Loading accounts...</div>
        ) : financialAccounts.length === 0 ? (
          <EmptyState title="No VA accounts found" detail="Merchant ledger accounts appear here after VA provisioning completes." compact />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-left text-xs uppercase text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">Account</th>
                  <th className="px-4 py-3 font-medium">
                    <span className="flex items-center gap-1.5">
                      Class
                      <HelpTip label="Account class help" content="Accounting category from the platform-book view: asset, liability, revenue, or expense." />
                    </span>
                  </th>
                  <th className="px-4 py-3 font-medium">Balance</th>
                  <th className="px-4 py-3 font-medium">
                    <span className="flex items-center gap-1.5">
                      Normal
                      <HelpTip label="Normal balance help" content="The ledger side that increases this account. This explains whether debit or credit entries raise the balance." />
                    </span>
                  </th>
                  <th className="px-4 py-3 font-medium">Mode</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                  <th className="px-4 py-3 font-medium">ID</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {financialAccounts.map((account) => (
                  <tr key={account.ledgerAccountId} className="hover:bg-gray-50">
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1.5 font-medium">
                        <Link
                          href={`/virtual-account/${account.ledgerAccountId}`}
                          className="text-primary hover:underline"
                        >
                          {accountLabel(account.ledgerAccountType)}
                        </Link>
                        <HelpTip label={`${accountLabel(account.ledgerAccountType)} help`} content={accountHelp(account.ledgerAccountType)} />
                      </div>
                      <div className="text-xs text-muted-foreground">{account.ledgerAccountType}</div>
                    </td>
                    <td className="px-4 py-3">
                      <span className="inline-flex items-center gap-1.5">
                        {account.accountClass}
                        <HelpTip label={`${account.accountClass} class help`} content={ACCOUNT_CLASS_HELP[account.accountClass]} />
                      </span>
                    </td>
                    <td className="px-4 py-3 font-medium">{formatAmount(account.balance, account.asset)}</td>
                    <td className="px-4 py-3">
                      <span className="inline-flex items-center gap-1.5">
                        {account.normalBalance}
                        <HelpTip label={`${account.normalBalance} normal balance help`} content={NORMAL_BALANCE_HELP[account.normalBalance]} />
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <Badge variant={account.mode === 'TEST' ? 'secondary' : 'outline'}>{account.mode}</Badge>
                    </td>
                    <td className="px-4 py-3">{account.status}</td>
                    <td className="px-4 py-3 font-mono text-xs text-muted-foreground">{account.ledgerAccountId}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

function PageHeader({ onRefresh, refreshing }: { onRefresh: () => void; refreshing: boolean }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div>
        <div className="flex items-center gap-2">
          <Landmark className="size-5 text-primary" />
          <h1 className="text-2xl font-semibold tracking-normal">Virtual Account</h1>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">Ledger balances and tenant accounts for the active merchant.</p>
      </div>
      <Button variant="outline" onClick={onRefresh} disabled={refreshing}>
        <RefreshCcw className="mr-2 size-4" />
        Refresh
      </Button>
    </div>
  );
}

function EmptyState({ title, detail, compact = false }: { title: string; detail: string; compact?: boolean }) {
  return (
    <div className={compact ? 'px-4 py-10 text-center' : 'rounded-md border bg-white px-4 py-10 text-center'}>
      <WalletCards className="mx-auto mb-3 size-8 text-muted-foreground" />
      <p className="font-medium">{title}</p>
      <p className="mt-1 text-sm text-muted-foreground">{detail}</p>
    </div>
  );
}

function HelpTip({ label, content }: { label: string; content?: string }) {
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState({ top: 0, left: 0 });

  useEffect(() => {
    if (!open || !buttonRef.current) return;
    const updatePosition = () => {
      const rect = buttonRef.current!.getBoundingClientRect();
      const tooltipWidth = 256;
      const margin = 12;
      const left = Math.min(
        Math.max(rect.left + rect.width / 2, tooltipWidth / 2 + margin),
        window.innerWidth - tooltipWidth / 2 - margin,
      );
      setPosition({ top: rect.bottom + 8, left });
    };
    updatePosition();
    window.addEventListener('scroll', updatePosition, true);
    window.addEventListener('resize', updatePosition);
    return () => {
      window.removeEventListener('scroll', updatePosition, true);
      window.removeEventListener('resize', updatePosition);
    };
  }, [open]);

  if (!content) return null;
  return (
    <span className="inline-flex">
      <button
        ref={buttonRef}
        type="button"
        aria-label={label}
        onMouseEnter={() => setOpen(true)}
        onMouseLeave={() => setOpen(false)}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        className="inline-flex size-4 items-center justify-center rounded-full text-muted-foreground outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
      >
        <CircleHelp className="size-3.5" />
      </button>
      {open && createPortal(
        <span
          className="pointer-events-none fixed z-[1000] w-64 -translate-x-1/2 rounded-md border bg-popover px-3 py-2 text-left text-xs normal-case leading-5 text-popover-foreground shadow-lg"
          style={{ top: position.top, left: position.left }}
        >
          {content}
        </span>,
        document.body,
      )}
    </span>
  );
}
