'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useQuery } from '@tanstack/react-query';
import { format } from 'date-fns';
import { ArrowLeft, BookOpen, RefreshCcw } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';

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

interface LedgerEntry {
  entryId: string;
  transactionId: string;
  direction: 'DEBIT' | 'CREDIT';
  amount: number | string;
  asset: string;
  balanceAfter: number | string;
  entrySeq: number;
  effectiveDate: string;
  status: string;
  createdAt: string;
}

interface EntryPage {
  content: LedgerEntry[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

interface Statement {
  enabled: boolean;
  unavailableReason: string | null;
  ledgerAccountId: string | null;
  asset: string | null;
  normalBalance: string | null;
  fromDate: string | null;
  toDate: string | null;
  openingBalance: number | string | null;
  closingBalance: number | string | null;
  totalDebits: number | string | null;
  totalCredits: number | string | null;
  netChange: number | string | null;
  entries: LedgerEntry[];
}

const ACCOUNT_LABELS: Record<string, string> = {
  CASH: 'Cash',
  WALLET: 'Wallet',
  MERCHANT_RECEIVABLE: 'Merchant receivable',
  PREPAID_CARD: 'Prepaid card',
  PREPAID_CARD_HOLD: 'Card holds',
};

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

function daysAgoIso(days: number) {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}

function accountLabel(type?: string) {
  if (!type) return 'Ledger account';
  return ACCOUNT_LABELS[type] ?? type.replaceAll('_', ' ').toLowerCase();
}

function formatAmount(value: number | string | null | undefined, asset?: string | null) {
  const n = typeof value === 'number' ? value : Number(value ?? 0);
  return `${Number.isFinite(n) ? n.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }) : '0.00'} ${asset ?? ''}`.trim();
}

export default function VirtualAccountDetailPage() {
  const { accountId } = useParams<{ accountId: string }>();
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const [from, setFrom] = useState(daysAgoIso(30));
  const [to, setTo] = useState(todayIso());
  const [page, setPage] = useState(0);
  const size = 20;

  const accountQuery = useQuery<LedgerAccount>({
    queryKey: ['va-account', activeMerchantId, accountId],
    enabled: !!activeMerchantId && !!accountId,
    queryFn: () => apiFetch<LedgerAccount>(`/api/v1/merchants/${activeMerchantId}/va/accounts/${accountId}`),
  });

  const entriesQuery = useQuery<EntryPage>({
    queryKey: ['va-account-entries', activeMerchantId, accountId, mode, page],
    enabled: !!activeMerchantId && !!accountId,
    queryFn: () =>
      apiFetch<EntryPage>(
        `/api/v1/merchants/${activeMerchantId}/va/accounts/${accountId}/entries?mode=${mode}&page=${page}&size=${size}`,
      ),
  });

  const statementQuery = useQuery<Statement>({
    queryKey: ['va-account-statement', activeMerchantId, accountId, mode, from, to],
    enabled: !!activeMerchantId && !!accountId,
    queryFn: () =>
      apiFetch<Statement>(
        `/api/v1/merchants/${activeMerchantId}/va/accounts/${accountId}/statement?mode=${mode}&from=${from}&to=${to}`,
      ),
  });

  const account = accountQuery.data;
  const entries = entriesQuery.data?.content ?? [];
  const statement = statementQuery.data;
  const refreshing = accountQuery.isFetching || entriesQuery.isFetching || statementQuery.isFetching;

  const summary = useMemo(() => [
    { label: 'Opening', value: statement?.openingBalance, asset: statement?.asset ?? account?.asset },
    { label: 'Closing', value: statement?.closingBalance, asset: statement?.asset ?? account?.asset },
    { label: 'Net change', value: statement?.netChange, asset: statement?.asset ?? account?.asset },
    { label: 'Debits', value: statement?.totalDebits, asset: statement?.asset ?? account?.asset },
    { label: 'Credits', value: statement?.totalCredits, asset: statement?.asset ?? account?.asset },
  ], [account?.asset, statement]);

  function refreshAll() {
    accountQuery.refetch();
    entriesQuery.refetch();
    statementQuery.refetch();
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Link href="/virtual-account" className="mb-2 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
            <ArrowLeft className="size-4" />
            Accounts
          </Link>
          <div className="flex items-center gap-2">
            <BookOpen className="size-5 text-primary" />
            <h1 className="text-2xl font-semibold tracking-normal">{accountLabel(account?.ledgerAccountType)}</h1>
            {account && <Badge variant={account.mode === 'TEST' ? 'secondary' : 'outline'}>{account.mode}</Badge>}
          </div>
          <p className="mt-1 font-mono text-xs text-muted-foreground">{accountId}</p>
        </div>
        <Button variant="outline" onClick={refreshAll} disabled={refreshing}>
          <RefreshCcw className="mr-2 size-4" />
          Refresh
        </Button>
      </div>

      {account && (
        <div className="grid gap-4 md:grid-cols-4">
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-sm text-muted-foreground">Live balance</CardTitle></CardHeader>
            <CardContent><div className="text-2xl font-semibold">{formatAmount(account.balance, account.asset)}</div></CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-sm text-muted-foreground">Class</CardTitle></CardHeader>
            <CardContent><div className="text-lg font-semibold">{account.accountClass}</div></CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-sm text-muted-foreground">Normal balance</CardTitle></CardHeader>
            <CardContent><div className="text-lg font-semibold">{account.normalBalance}</div></CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-sm text-muted-foreground">Status</CardTitle></CardHeader>
            <CardContent><div className="text-lg font-semibold">{account.status}</div></CardContent>
          </Card>
        </div>
      )}

      <section className="rounded-md border bg-white">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3">
          <div>
            <h2 className="text-sm font-semibold">Statement</h2>
            <p className="text-xs text-muted-foreground">Effective-date activity for the selected period</p>
          </div>
          <div className="flex items-center gap-2">
            <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="h-9 w-36" />
            <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="h-9 w-36" />
          </div>
        </div>
        {statement && !statement.enabled ? (
          <div className="px-4 py-8 text-sm text-muted-foreground">{statement.unavailableReason}</div>
        ) : (
          <div className="grid gap-3 p-4 md:grid-cols-5">
            {summary.map((item) => (
              <div key={item.label} className="rounded-md border px-3 py-3">
                <p className="text-xs text-muted-foreground">{item.label}</p>
                <p className="mt-1 text-lg font-semibold">{formatAmount(item.value, item.asset)}</p>
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="rounded-md border bg-white">
        <div className="flex items-center justify-between border-b px-4 py-3">
          <div>
            <h2 className="text-sm font-semibold">Ledger Entries</h2>
            <p className="text-xs text-muted-foreground">Newest entries first</p>
          </div>
          <Badge variant="outline">{entriesQuery.data?.totalElements ?? 0} entries</Badge>
        </div>
        {entriesQuery.isLoading ? (
          <div className="px-4 py-10 text-center text-sm text-muted-foreground">Loading entries...</div>
        ) : entries.length === 0 ? (
          <div className="px-4 py-10 text-center text-sm text-muted-foreground">No ledger entries for this account yet.</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-left text-xs uppercase text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">Seq</th>
                  <th className="px-4 py-3 font-medium">Direction</th>
                  <th className="px-4 py-3 font-medium">Amount</th>
                  <th className="px-4 py-3 font-medium">Balance after</th>
                  <th className="px-4 py-3 font-medium">Effective</th>
                  <th className="px-4 py-3 font-medium">Created</th>
                  <th className="px-4 py-3 font-medium">Transaction</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {entries.map((entry) => (
                  <tr key={entry.entryId}>
                    <td className="px-4 py-3 font-mono text-xs">{entry.entrySeq}</td>
                    <td className="px-4 py-3">
                      <Badge variant={entry.direction === 'DEBIT' ? 'secondary' : 'outline'}>{entry.direction}</Badge>
                    </td>
                    <td className="px-4 py-3 font-medium">{formatAmount(entry.amount, entry.asset)}</td>
                    <td className="px-4 py-3">{formatAmount(entry.balanceAfter, entry.asset)}</td>
                    <td className="px-4 py-3">{entry.effectiveDate}</td>
                    <td className="px-4 py-3">{format(new Date(entry.createdAt), 'MMM d, yyyy HH:mm')}</td>
                    <td className="px-4 py-3 font-mono text-xs text-muted-foreground">{entry.transactionId}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {(entriesQuery.data?.totalPages ?? 0) > 1 && (
          <div className="flex items-center justify-end gap-2 border-t px-4 py-3">
            <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
              Previous
            </Button>
            <span className="text-xs text-muted-foreground">Page {page + 1} of {entriesQuery.data?.totalPages}</span>
            <Button
              variant="outline"
              size="sm"
              disabled={page + 1 >= (entriesQuery.data?.totalPages ?? 0)}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </Button>
          </div>
        )}
      </section>
    </div>
  );
}
