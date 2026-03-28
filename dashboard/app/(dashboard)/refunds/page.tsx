'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { format } from 'date-fns';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';

interface Refund {
  id: string;
  paymentIntentId: string;
  amount: number;
  currency: string;
  status: string;
  reason: string | null;
  providerRefundId: string | null;
  createdAt: string;
}

interface PageResponse {
  content: Refund[];
  totalElements: number;
  totalPages: number;
  number: number;
}

const STATUS_COLORS: Record<string, string> = {
  SUCCEEDED: 'bg-green-100 text-green-700',
  PENDING:   'bg-yellow-100 text-yellow-700',
  FAILED:    'bg-red-100 text-red-700',
};

export default function RefundsPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const router = useRouter();
  const [page, setPage] = useState(0);

  const { data, isLoading } = useQuery<PageResponse>({
    queryKey: ['refunds', activeMerchantId, mode, page],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '20', mode });
      return apiFetch<PageResponse>(
        `/api/v1/merchants/${activeMerchantId}/payment-intents/refunds?${params}`,
      );
    },
    enabled: !!activeMerchantId,
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Refunds</h1>
          <p className="text-sm text-muted-foreground mt-1">
            To issue a new refund, open the payment detail page.
          </p>
        </div>
      </div>

      <Card>
        <CardContent className="p-0">
          <table className="w-full text-sm">
            <thead className="border-b bg-gray-50">
              <tr>
                {['Refund ID', 'Payment', 'Amount', 'Status', 'Reason', 'Date', ''].map((h) => (
                  <th key={h} className="px-4 py-3 text-left font-medium text-muted-foreground text-xs">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr><td colSpan={7} className="py-8 text-center text-muted-foreground">Loading…</td></tr>
              ) : !data?.content?.length ? (
                <tr><td colSpan={7} className="py-8 text-center text-muted-foreground">No refunds found</td></tr>
              ) : data.content.map((r) => (
                <tr key={r.id} className="border-b last:border-0 hover:bg-gray-50">
                  <td className="px-4 py-3 font-mono text-xs">{r.id.slice(0, 12)}…</td>
                  <td className="px-4 py-3">
                    <button
                      className="font-mono text-xs text-primary hover:underline"
                      onClick={() => router.push(`/payments/${r.paymentIntentId}`)}
                    >
                      {r.paymentIntentId.slice(0, 12)}…
                    </button>
                  </td>
                  <td className="px-4 py-3">{(r.amount / 100).toFixed(2)} {r.currency.toUpperCase()}</td>
                  <td className="px-4 py-3">
                    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${STATUS_COLORS[r.status] ?? 'bg-gray-100 text-gray-600'}`}>
                      {r.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">
                    {r.reason ? r.reason.replace(/_/g, ' ') : '—'}
                  </td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">
                    {format(new Date(r.createdAt), 'MMM d, yyyy HH:mm')}
                  </td>
                  <td className="px-4 py-3">
                    <button
                      className="text-xs text-primary hover:underline"
                      onClick={() => router.push(`/payments/${r.paymentIntentId}`)}
                    >
                      View payment →
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      <div className="flex items-center justify-between text-sm text-muted-foreground">
        <span>{data?.totalElements ?? 0} total refunds</span>
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
