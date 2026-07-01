'use client';

import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { format } from 'date-fns';
import { useRouter } from 'next/navigation';
import { ExternalLink, RefreshCw } from 'lucide-react';
import { toast } from 'sonner';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

interface Invoice {
  id: string;
  externalId?: string | null;
  subscriptionId: string;
  status: 'OPEN' | 'PAID' | 'VOID' | 'UNCOLLECTIBLE';
  amountDue: number;
  amountPaid: number;
  currency: string;
  periodStart: string;
  periodEnd: string;
  dueAt: string | null;
  nextPaymentAttemptAt: string | null;
  createdAt: string;
  latestPaymentIntentId: string | null;
}

const STATUS_COLORS: Record<Invoice['status'], string> = {
  OPEN:           'border-amber-200 bg-amber-50 text-amber-700',
  PAID:           'border-green-200 bg-green-50 text-green-700',
  VOID:           'border-gray-200 bg-gray-50 text-gray-600',
  UNCOLLECTIBLE:  'border-red-200 bg-red-50 text-red-700',
};

export default function InvoicesPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const queryClient = useQueryClient();
  const router = useRouter();
  const [statusFilter, setStatusFilter] = useState<string>('ALL');

  const invoicesKey = useMemo(
    () => ['invoices-flat', activeMerchantId, mode],
    [activeMerchantId, mode],
  );

  const { data: invoices, isLoading } = useQuery<Invoice[]>({
    queryKey: invoicesKey,
    queryFn: () => apiFetch<Invoice[]>(`/api/v1/merchants/${activeMerchantId}/invoices?mode=${mode}`),
    enabled: !!activeMerchantId,
  });

  const payMutation = useMutation({
    mutationFn: (invoiceId: string) => apiFetch(
      `/api/v1/merchants/${activeMerchantId}/invoices/${invoiceId}/pay`,
      { method: 'POST' },
    ),
    onSuccess: () => {
      toast.success('Invoice payment initiated');
      queryClient.invalidateQueries({ queryKey: invoicesKey });
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string; title?: string };
      toast.error(e.detail ?? e.title ?? 'Payment failed');
    },
  });

  const markUncollectibleMutation = useMutation({
    mutationFn: (invoiceId: string) => apiFetch(
      `/api/v1/merchants/${activeMerchantId}/invoices/${invoiceId}/mark-uncollectible`,
      { method: 'POST' },
    ),
    onSuccess: () => {
      toast.success('Invoice marked uncollectible');
      queryClient.invalidateQueries({ queryKey: invoicesKey });
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string; title?: string };
      toast.error(e.detail ?? e.title ?? 'Could not update invoice');
    },
  });

  const rows = (invoices ?? []).filter(
    (inv) => statusFilter === 'ALL' || inv.status === statusFilter,
  );

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Invoices</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            All subscription invoices across all billing periods.
          </p>
        </div>
        <Select value={statusFilter} onValueChange={(v: string | null) => setStatusFilter(v ?? 'ALL')}>
          <SelectTrigger className="w-44">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All statuses</SelectItem>
            <SelectItem value="OPEN">Open</SelectItem>
            <SelectItem value="PAID">Paid</SelectItem>
            <SelectItem value="UNCOLLECTIBLE">Uncollectible</SelectItem>
            <SelectItem value="VOID">Void</SelectItem>
          </SelectContent>

        </Select>
      </div>

      <Card>
        <CardContent className="p-0">
          <table className="w-full text-sm">
            <thead className="border-b bg-gray-50">
              <tr>
                {['Invoice', 'Period', 'Subscription', 'Amount', 'Status', 'Next attempt', 'Actions'].map((h) => (
                  <th key={h} className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr><td colSpan={7} className="py-10 text-center text-muted-foreground">Loading…</td></tr>
              ) : rows.length === 0 ? (
                <tr><td colSpan={7} className="py-10 text-center text-muted-foreground">No invoices found</td></tr>
              ) : rows.map((invoice) => (
                <tr key={invoice.id} className="border-b last:border-0 hover:bg-gray-50">
                  <td className="px-4 py-3 font-mono text-xs text-muted-foreground">
                    {invoicePublicId(invoice)}
                  </td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">
                    {format(new Date(invoice.periodStart), 'MMM d')}
                    {' – '}
                    {format(new Date(invoice.periodEnd), 'MMM d, yyyy')}
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-muted-foreground">
                    <button
                      className="hover:text-foreground hover:underline"
                      onClick={() => router.push(`/subscriptions`)}
                    >
                      {invoice.subscriptionId.slice(0, 8)}…
                    </button>
                  </td>
                  <td className="px-4 py-3 font-medium">
                    {formatMoney(invoice.amountDue, invoice.currency)}
                    {invoice.status === 'PAID' && invoice.amountPaid > 0 && (
                      <span className="ml-1 text-xs font-normal text-green-600">
                        ({formatMoney(invoice.amountPaid, invoice.currency)} paid)
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <Badge variant="outline" className={STATUS_COLORS[invoice.status]}>
                      {invoice.status}
                    </Badge>
                  </td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">
                    {invoice.nextPaymentAttemptAt
                      ? format(new Date(invoice.nextPaymentAttemptAt), 'MMM d, HH:mm')
                      : '—'}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1.5">
                      {invoice.status === 'OPEN' && (
                        <>
                          <Button
                            size="sm"
                            variant="outline"
                            className="h-7 gap-1 px-2 text-xs"
                            disabled={payMutation.isPending}
                            onClick={() => payMutation.mutate(invoice.id)}
                          >
                            {payMutation.isPending
                              ? <RefreshCw className="size-3 animate-spin" />
                              : null}
                            Pay
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            className="h-7 gap-1 px-2 text-xs text-muted-foreground"
                            disabled={markUncollectibleMutation.isPending}
                            onClick={() => markUncollectibleMutation.mutate(invoice.id)}
                          >
                            Write off
                          </Button>
                        </>
                      )}
                      {invoice.latestPaymentIntentId && (
                        <button
                          className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
                          onClick={() => router.push(`/payments/${invoice.latestPaymentIntentId}`)}
                        >
                          <ExternalLink className="size-3" />
                          Payment
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}

function formatMoney(amount: number, currency: string) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: currency || 'USD',
  }).format(amount / 100);
}

function invoicePublicId(invoice: Invoice) {
  return invoice.externalId || `${invoice.id.slice(0, 12)}...`;
}
