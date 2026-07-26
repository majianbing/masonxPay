'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Activity, RefreshCcw } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';

interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

interface CardAuthorization {
  authId: string;
  issuerId: string;
  authorizationId: string;
  cardId: string;
  stan: string | null;
  rrn: string | null;
  amount: number | string;
  currency: string;
  decision: string;
  declineReason: string | null;
  holdEventId: string | null;
  status: string;
  releasedAmount: number | string | null;
  releaseReason: string | null;
  releasedAt: string | null;
  settledAmount: number | string | null;
  settledAt: string | null;
  createdAt: string;
}

export default function IssuingAuthorizationsPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const [page, setPage] = useState(0);

  const authorizationsQuery = useQuery<PageResponse<CardAuthorization>>({
    queryKey: ['card-authorizations', activeMerchantId, mode, page],
    enabled: !!activeMerchantId,
    queryFn: () => apiFetch(`/api/v1/merchants/${activeMerchantId}/va/authorizations?mode=${mode}&page=${page}&size=20`),
  });

  if (!activeMerchantId) {
    return (
      <div className="space-y-4">
        <PageHeader refreshing={false} onRefresh={() => undefined} />
        <section className="rounded-md border bg-white px-4 py-10 text-center text-sm text-muted-foreground">
          Select a merchant to view authorization activity.
        </section>
      </div>
    );
  }

  const authorizations = authorizationsQuery.data?.content ?? [];

  return (
    <div className="space-y-5">
      <PageHeader refreshing={authorizationsQuery.isFetching} onRefresh={() => authorizationsQuery.refetch()} />

      <section className="rounded-md border bg-white">
        <div className="flex items-center justify-between border-b px-4 py-3">
          <div>
            <h2 className="text-sm font-semibold">Authorization History</h2>
            <p className="text-xs text-muted-foreground">{mode} issuer decisions, holds, releases, and settlement state</p>
          </div>
          <Badge variant="outline">{authorizationsQuery.data?.totalElements ?? 0} total</Badge>
        </div>
        {authorizationsQuery.isLoading ? (
          <div className="px-4 py-10 text-center text-sm text-muted-foreground">Loading authorizations...</div>
        ) : authorizations.length === 0 ? (
          <div className="px-4 py-10 text-center text-sm text-muted-foreground">
            No authorization decisions recorded yet.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-left text-xs uppercase text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">Time</th>
                  <th className="px-4 py-3 font-medium">Decision</th>
                  <th className="px-4 py-3 font-medium">Amount</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                  <th className="px-4 py-3 font-medium">Released</th>
                  <th className="px-4 py-3 font-medium">Settled</th>
                  <th className="px-4 py-3 font-medium">Issuer Reference</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {authorizations.map((auth) => (
                  <tr key={auth.authId} className="hover:bg-gray-50">
                    <td className="px-4 py-3">{formatDateTime(auth.createdAt)}</td>
                    <td className="px-4 py-3">
                      <DecisionBadge decision={auth.decision} declineReason={auth.declineReason} />
                    </td>
                    <td className="px-4 py-3 font-medium">{formatAmount(auth.amount, auth.currency)}</td>
                    <td className="px-4 py-3"><StatusBadge status={auth.status} /></td>
                    <td className="px-4 py-3">{formatAmount(auth.releasedAmount, auth.currency)}</td>
                    <td className="px-4 py-3">{formatAmount(auth.settledAmount, auth.currency)}</td>
                    <td className="px-4 py-3">
                      <div className="font-medium">{auth.issuerId}</div>
                      <div className="text-xs text-muted-foreground">
                        {auth.rrn || auth.stan || 'No network reference'}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="flex items-center justify-between border-t px-4 py-3 text-sm">
          <span className="text-muted-foreground">
            Page {page + 1} of {Math.max(authorizationsQuery.data?.totalPages ?? 1, 1)}
          </span>
          <div className="flex gap-2">
            <Button variant="outline" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
              Previous
            </Button>
            <Button
              variant="outline"
              disabled={page + 1 >= (authorizationsQuery.data?.totalPages ?? 1)}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </Button>
          </div>
        </div>
      </section>
    </div>
  );
}

function PageHeader({ refreshing, onRefresh }: { refreshing: boolean; onRefresh: () => void }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div>
        <div className="flex items-center gap-2">
          <Activity className="size-5 text-primary" />
          <h1 className="text-2xl font-semibold tracking-normal">Authorizations</h1>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">Review issuer decisions, hold state, reversals, and clearing progress.</p>
      </div>
      <Button variant="outline" onClick={onRefresh} disabled={refreshing}>
        <RefreshCcw className="mr-2 size-4" />
        Refresh
      </Button>
    </div>
  );
}

function DecisionBadge({ decision, declineReason }: { decision: string; declineReason: string | null }) {
  if (decision === 'APPROVED') {
    return <Badge>Approved</Badge>;
  }
  return <Badge variant="destructive">{declineReason || 'Declined'}</Badge>;
}

function StatusBadge({ status }: { status: string }) {
  const variant = status === 'AUTHORIZED' ? 'default' : status === 'DECLINED' ? 'destructive' : 'secondary';
  return <Badge variant={variant}>{status}</Badge>;
}

function formatAmount(value: number | string | null | undefined, currency: string) {
  const n = typeof value === 'number' ? value : Number(value ?? 0);
  return `${Number.isFinite(n) ? n.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }) : '0.00'} ${currency}`;
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}
