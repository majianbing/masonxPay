'use client';

import { useEffect, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { format, differenceInDays } from 'date-fns';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { toast } from 'sonner';

interface Dispute {
  id: string;
  paymentIntentId: string | null;
  provider: string;
  providerDisputeId: string;
  status: string;
  reason: string | null;
  amount: number;
  currency: string;
  evidenceDueBy: string | null;
  submittedAt: string | null;
  resolvedAt: string | null;
  createdAt: string;
}

interface PageResponse {
  content: Dispute[];
  totalElements: number;
  totalPages: number;
  number: number;
}

const STATUS_COLORS: Record<string, string> = {
  NEEDS_RESPONSE:          'bg-red-100 text-red-700',
  UNDER_REVIEW:            'bg-blue-100 text-blue-700',
  WON:                     'bg-green-100 text-green-700',
  LOST:                    'bg-gray-200 text-gray-600',
  CHARGE_REFUNDED:         'bg-gray-200 text-gray-600',
  WARNING_NEEDS_RESPONSE:  'bg-yellow-100 text-yellow-700',
  WARNING_UNDER_REVIEW:    'bg-yellow-100 text-yellow-700',
  WARNING_CLOSED:          'bg-gray-200 text-gray-600',
};

const STATUSES = [
  'NEEDS_RESPONSE', 'UNDER_REVIEW', 'WON', 'LOST',
  'CHARGE_REFUNDED', 'WARNING_NEEDS_RESPONSE', 'WARNING_UNDER_REVIEW', 'WARNING_CLOSED',
];

function DeadlineBadge({ dueBy }: { dueBy: string | null }) {
  if (!dueBy) return null;
  const days = differenceInDays(new Date(dueBy), new Date());
  if (days < 0) return <span className="text-xs text-red-600 font-medium">Overdue</span>;
  if (days <= 3) return <span className="text-xs text-red-600 font-medium">{days}d left</span>;
  if (days <= 7) return <span className="text-xs text-yellow-600 font-medium">{days}d left</span>;
  return <span className="text-xs text-muted-foreground">{days}d left</span>;
}

export default function DisputesPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const router = useRouter();

  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState('');
  const [seedStatus, setSeedStatus] = useState('NEEDS_RESPONSE');
  const [seedProvider, setSeedProvider] = useState('STRIPE');

  useEffect(() => { setPage(0); }, [statusFilter]);

  const seedMutation = useMutation({
    mutationFn: () =>
      apiFetch(`/api/v1/merchants/${activeMerchantId}/dev/disputes/seed?status=${seedStatus}&provider=${seedProvider}`, {
        method: 'POST',
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['disputes', activeMerchantId] });
      toast.success('Test dispute created');
    },
    onError: () => toast.error('Failed to seed dispute — is the backend running in non-preview mode?'),
  });

  const { data, isLoading } = useQuery<PageResponse>({
    queryKey: ['disputes', activeMerchantId, mode, page, statusFilter],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '20', mode });
      if (statusFilter) params.set('status', statusFilter);
      return apiFetch<PageResponse>(
        `/api/v1/merchants/${activeMerchantId}/disputes?${params}`,
      );
    },
    enabled: !!activeMerchantId,
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Disputes</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Respond to chargebacks and inquiries from card networks.
          </p>
        </div>
        {mode === 'TEST' && (
        <div className="flex items-center gap-2 border rounded-md px-3 py-2 bg-yellow-50 border-yellow-200">
          <span className="text-xs font-medium text-yellow-700">Dev seed</span>
          <select
            value={seedStatus}
            onChange={(e) => setSeedStatus(e.target.value)}
            className="h-7 rounded border border-yellow-300 bg-white px-2 text-xs"
          >
            {['NEEDS_RESPONSE', 'UNDER_REVIEW', 'WON', 'LOST', 'WARNING_NEEDS_RESPONSE'].map((s) => (
              <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>
            ))}
          </select>
          <select
            value={seedProvider}
            onChange={(e) => setSeedProvider(e.target.value)}
            className="h-7 rounded border border-yellow-300 bg-white px-2 text-xs"
          >
            {['STRIPE', 'SQUARE', 'BRAINTREE'].map((p) => (
              <option key={p} value={p}>{p}</option>
            ))}
          </select>
          <Button
            size="sm"
            variant="outline"
            className="h-7 text-xs border-yellow-300 hover:bg-yellow-100"
            onClick={() => seedMutation.mutate()}
            disabled={seedMutation.isPending}
          >
            {seedMutation.isPending ? 'Creating…' : '+ Seed'}
          </Button>
        </div>
        )}
      </div>

      <div className="flex gap-2 items-center">
        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
          className="h-9 rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
        >
          <option value="">All statuses</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>
          ))}
        </select>
        {statusFilter && (
          <Button variant="ghost" size="sm" onClick={() => setStatusFilter('')}>
            Clear
          </Button>
        )}
      </div>

      <Card>
        <CardContent className="p-0">
          <table className="w-full text-sm">
            <thead className="border-b bg-gray-50">
              <tr>
                {['Dispute ID', 'Amount', 'Provider', 'Status', 'Reason', 'Deadline', 'Created', ''].map((h) => (
                  <th key={h} className="px-4 py-3 text-left font-medium text-muted-foreground text-xs">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr><td colSpan={8} className="py-8 text-center text-muted-foreground">Loading…</td></tr>
              ) : !data?.content?.length ? (
                <tr><td colSpan={8} className="py-8 text-center text-muted-foreground">No disputes found</td></tr>
              ) : data.content.map((d) => (
                <tr key={d.id} className="border-b last:border-0 hover:bg-gray-50">
                  <td className="px-4 py-3 font-mono text-xs">{d.providerDisputeId.slice(0, 16)}…</td>
                  <td className="px-4 py-3 font-medium">
                    {(d.amount / 100).toFixed(2)} {d.currency.toUpperCase()}
                  </td>
                  <td className="px-4 py-3 text-xs">{d.provider}</td>
                  <td className="px-4 py-3">
                    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${STATUS_COLORS[d.status] ?? 'bg-gray-100 text-gray-600'}`}>
                      {d.status.replace(/_/g, ' ')}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">
                    {d.reason ? d.reason.replace(/_/g, ' ') : '—'}
                  </td>
                  <td className="px-4 py-3">
                    {d.status === 'NEEDS_RESPONSE' || d.status === 'WARNING_NEEDS_RESPONSE'
                      ? <DeadlineBadge dueBy={d.evidenceDueBy} />
                      : <span className="text-xs text-muted-foreground">—</span>}
                  </td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">
                    {format(new Date(d.createdAt), 'MMM d, yyyy')}
                  </td>
                  <td className="px-4 py-3">
                    <button
                      className="text-xs text-primary hover:underline"
                      onClick={() => router.push(`/disputes/${d.id}`)}
                    >
                      View →
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      <div className="flex items-center justify-between text-sm text-muted-foreground">
        <span>{data?.totalElements ?? 0} total disputes</span>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Previous
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={page >= (data?.totalPages ?? 1) - 1}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </Button>
        </div>
      </div>
    </div>
  );
}
