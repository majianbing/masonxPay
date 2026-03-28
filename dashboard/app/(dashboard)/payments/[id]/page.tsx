'use client';

import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useParams, useRouter } from 'next/navigation';
import { format } from 'date-fns';
import { toast } from 'sonner';
import { ArrowLeft } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

interface PaymentAttempt {
  id: string;
  status: string;
  paymentMethodType: string;
  providerRequestId: string;
  failureCode: string;
  failureMessage: string;
  createdAt: string;
}

interface PaymentIntent {
  id: string;
  merchantId: string;
  amount: number;
  currency: string;
  status: string;
  mode: string;
  captureMethod: string;
  resolvedProvider: string;
  providerPaymentId: string;
  metadata: Record<string, string>;
  idempotencyKey: string;
  createdAt: string;
  updatedAt: string;
  attempts: PaymentAttempt[];
}

export default function PaymentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const qc = useQueryClient();
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const [refundAmount, setRefundAmount] = useState('');
  const [refunding, setRefunding] = useState(false);

  const { data: payment, isLoading } = useQuery<PaymentIntent>({
    queryKey: ['payment-intent', id],
    queryFn: () => apiFetch<PaymentIntent>(`/api/v1/merchants/${activeMerchantId}/payment-intents/${id}`),
    enabled: !!activeMerchantId,
  });

  const refundMutation = useMutation({
    mutationFn: (amount: number) =>
      apiFetch(`/api/v1/merchants/${activeMerchantId}/payment-intents/${id}/refunds`, {
        method: 'POST',
        body: JSON.stringify({ amount, reason: 'CUSTOMER_REQUEST' }),
      }),
    onSuccess: () => {
      toast.success('Refund initiated');
      setRefunding(false);
      setRefundAmount('');
      qc.invalidateQueries({ queryKey: ['payment-intent', id] });
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string };
      toast.error(e.detail ?? 'Refund failed');
    },
  });

  if (isLoading) return <div className="py-12 text-center text-muted-foreground">Loading…</div>;
  if (!payment) return <div className="py-12 text-center text-muted-foreground">Payment not found</div>;

  return (
    <div className="space-y-6 max-w-3xl">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" onClick={() => router.back()}>
          <ArrowLeft className="size-4" />
        </Button>
        <h1 className="text-2xl font-semibold">Payment Detail</h1>
      </div>

      <Card>
        <CardHeader><CardTitle className="text-sm font-medium">Summary</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-2 gap-4 text-sm">
          <Field label="ID" value={<span className="font-mono text-xs">{payment.id}</span>} />
          <Field label="Status" value={<StatusBadge status={payment.status} />} />
          <Field label="Amount" value={`${(payment.amount / 100).toFixed(2)} ${payment.currency}`} />
          <Field label="Mode" value={payment.mode} />
          <Field label="Provider" value={payment.resolvedProvider ?? '—'} />
          <Field label="Provider Payment ID" value={<span className="font-mono text-xs">{payment.providerPaymentId ?? '—'}</span>} />
          <Field label="Capture Method" value={payment.captureMethod} />
          <Field label="Idempotency Key" value={<span className="font-mono text-xs">{payment.idempotencyKey ?? '—'}</span>} />
          <Field label="Created" value={format(new Date(payment.createdAt), 'MMM d, yyyy HH:mm:ss')} />
          <Field label="Updated" value={format(new Date(payment.updatedAt), 'MMM d, yyyy HH:mm:ss')} />
        </CardContent>
      </Card>

      {payment.metadata && Object.keys(payment.metadata).length > 0 && (
        <Card>
          <CardHeader><CardTitle className="text-sm font-medium">Metadata</CardTitle></CardHeader>
          <CardContent className="text-sm space-y-1">
            {Object.entries(payment.metadata).map(([k, v]) => (
              <div key={k} className="flex gap-2">
                <span className="font-medium min-w-32">{k}:</span>
                <span className="text-muted-foreground">{v}</span>
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      {payment.attempts?.length > 0 && (
        <Card>
          <CardHeader><CardTitle className="text-sm font-medium">Charge Attempts</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            {payment.attempts.map((a, i) => (
              <div key={a.id} className="border rounded-md p-3 text-sm space-y-1">
                <div className="flex justify-between">
                  <span className="font-medium">Attempt #{i + 1}</span>
                  <StatusBadge status={a.status} />
                </div>
                <div className="text-muted-foreground text-xs">{format(new Date(a.createdAt), 'MMM d, yyyy HH:mm:ss')}</div>
                {a.providerRequestId && <div className="font-mono text-xs">{a.providerRequestId}</div>}
                {a.failureCode && <div className="text-red-600 text-xs">{a.failureCode}: {a.failureMessage}</div>}
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      {payment.status === 'SUCCEEDED' && (
        <Card>
          <CardHeader><CardTitle className="text-sm font-medium">Refund</CardTitle></CardHeader>
          <CardContent>
            {refunding ? (
              <div className="flex gap-3 items-end">
                <div className="space-y-1">
                  <Label>Amount (cents)</Label>
                  <Input
                    type="number"
                    placeholder={String(payment.amount)}
                    value={refundAmount}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) => setRefundAmount(e.target.value)}
                    className="w-36"
                  />
                </div>
                <Button
                  onClick={() => refundMutation.mutate(parseInt(refundAmount) || payment.amount)}
                  disabled={refundMutation.isPending}
                >
                  Confirm Refund
                </Button>
                <Button variant="ghost" onClick={() => setRefunding(false)}>Cancel</Button>
              </div>
            ) : (
              <Button variant="outline" onClick={() => setRefunding(true)}>Issue Refund</Button>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function Field({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground mb-0.5">{label}</p>
      <div>{value}</div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const color: Record<string, string> = {
    SUCCEEDED: 'bg-green-100 text-green-700',
    FAILED: 'bg-red-100 text-red-700',
    CANCELED: 'bg-gray-100 text-gray-600',
    PROCESSING: 'bg-blue-100 text-blue-700',
  };
  return (
    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${color[status] ?? 'bg-gray-100 text-gray-600'}`}>
      {status.replace(/_/g, ' ')}
    </span>
  );
}
