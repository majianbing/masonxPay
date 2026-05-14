'use client';

import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useParams, useRouter } from 'next/navigation';
import { format } from 'date-fns';
import { toast } from 'sonner';
import { ArrowLeft, CornerDownRight, RotateCcw } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

interface Refund {
  id: string;
  amount: number;
  currency: string;
  status: string;
  reason: string | null;
  providerRefundId: string | null;
  failureReason: string | null;
  createdAt: string;
}

const METHOD_LABELS: Record<string, string> = {
  card: 'Card', google_pay: 'Google Pay', apple_pay: 'Apple Pay',
  ideal: 'iDEAL', amazon_pay: 'Amazon Pay', sofort: 'Sofort',
  link: 'Link', paypal: 'PayPal', cash_app: 'Cash App', cashapp: 'Cash App',
};

interface PaymentAttempt {
  id: string;
  attemptNumber: number;
  attemptType: string | null;
  connectorAccountId: string | null;
  status: string;
  paymentMethodType: string;
  providerRequestId: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  createdAt: string;
}

const ATTEMPT_LABELS: Record<string, string> = {
  PRIMARY: 'Primary',
  SAME_ACCOUNT_RETRY: 'Same account retry',
  FALLBACK: 'Fallback',
  FALLBACK_RETRY: 'Fallback retry',
};

const ATTEMPT_STYLES: Record<string, string> = {
  PRIMARY: 'bg-slate-100 text-slate-700',
  SAME_ACCOUNT_RETRY: 'bg-amber-50 text-amber-700',
  FALLBACK: 'bg-blue-50 text-blue-700',
  FALLBACK_RETRY: 'bg-violet-50 text-violet-700',
};

interface Address {
  line1: string | null;
  line2: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;
  country: string | null;
}

interface BillingDetails {
  firstName: string | null;
  lastName: string | null;
  email: string | null;
  phone: string | null;
  address: Address | null;
}

interface ShippingDetails {
  firstName: string | null;
  lastName: string | null;
  phone: string | null;
  address: Address | null;
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
  orderId: string | null;
  description: string | null;
  billingDetails: BillingDetails | null;
  shippingDetails: ShippingDetails | null;
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

  const { data: refunds = [] } = useQuery<Refund[]>({
    queryKey: ['payment-refunds', id],
    queryFn: () => apiFetch<Refund[]>(`/api/v1/merchants/${activeMerchantId}/payment-intents/${id}/refunds`),
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
      qc.invalidateQueries({ queryKey: ['payment-refunds', id] });
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
          {payment.orderId && <Field label="Order ID" value={<span className="font-mono text-xs">{payment.orderId}</span>} />}
          {payment.description && <Field label="Description" value={payment.description} />}
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

      {payment.billingDetails && (
        <Card>
          <CardHeader><CardTitle className="text-sm font-medium">Customer / Billing</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-2 gap-4 text-sm">
            {(payment.billingDetails.firstName || payment.billingDetails.lastName) && (
              <Field label="Name" value={[payment.billingDetails.firstName, payment.billingDetails.lastName].filter(Boolean).join(' ')} />
            )}
            {payment.billingDetails.email && <Field label="Email" value={payment.billingDetails.email} />}
            {payment.billingDetails.phone && <Field label="Phone" value={payment.billingDetails.phone} />}
            {payment.billingDetails.address && (
              <div className="col-span-2">
                <p className="text-xs text-muted-foreground mb-0.5">Billing Address</p>
                <AddressBlock addr={payment.billingDetails.address} />
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {payment.shippingDetails && (
        <Card>
          <CardHeader><CardTitle className="text-sm font-medium">Shipping</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-2 gap-4 text-sm">
            {(payment.shippingDetails.firstName || payment.shippingDetails.lastName) && (
              <Field label="Name" value={[payment.shippingDetails.firstName, payment.shippingDetails.lastName].filter(Boolean).join(' ')} />
            )}
            {payment.shippingDetails.phone && <Field label="Phone" value={payment.shippingDetails.phone} />}
            {payment.shippingDetails.address && (
              <div className="col-span-2">
                <p className="text-xs text-muted-foreground mb-0.5">Shipping Address</p>
                <AddressBlock addr={payment.shippingDetails.address} />
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {payment.attempts?.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm font-medium">Charge Attempts</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {payment.attempts.map((a, i) => (
              <div key={a.id} className="relative pl-7">
                {i < payment.attempts.length - 1 && (
                  <div className="absolute left-3 top-7 bottom-0 w-px bg-border" />
                )}
                <AttemptIcon attemptType={a.attemptType} />
                <div className="border rounded-md p-3 text-sm space-y-2">
                  <div className="flex justify-between gap-3 items-start">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="font-medium">Attempt #{a.attemptNumber ?? i + 1}</span>
                        {a.attemptType && (
                          <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${ATTEMPT_STYLES[a.attemptType] ?? 'bg-gray-100 text-gray-600'}`}>
                            {ATTEMPT_LABELS[a.attemptType] ?? a.attemptType.replace(/_/g, ' ')}
                          </span>
                        )}
                        {a.paymentMethodType && (
                          <span className="text-xs px-2 py-0.5 rounded-full bg-gray-100 text-gray-600 font-medium">
                            {METHOD_LABELS[a.paymentMethodType.toLowerCase()] ?? a.paymentMethodType}
                          </span>
                        )}
                      </div>
                      <div className="text-muted-foreground text-xs mt-1">
                        {format(new Date(a.createdAt), 'MMM d, yyyy HH:mm:ss')}
                      </div>
                    </div>
                    <StatusBadge status={a.status} />
                  </div>
                  <div className="grid gap-1 text-xs">
                    {a.connectorAccountId && (
                      <div>
                        <span className="text-muted-foreground">Connector Account </span>
                        <span className="font-mono">{a.connectorAccountId}</span>
                      </div>
                    )}
                    {a.providerRequestId && (
                      <div>
                        <span className="text-muted-foreground">Provider Request </span>
                        <span className="font-mono">{a.providerRequestId}</span>
                      </div>
                    )}
                    {a.failureCode && (
                      <div className="text-red-600">
                        {a.failureCode}{a.failureMessage ? `: ${a.failureMessage}` : ''}
                      </div>
                    )}
                  </div>
                </div>
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

      {refunds.length > 0 && (
        <Card>
          <CardHeader><CardTitle className="text-sm font-medium">Refunds ({refunds.length})</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            {refunds.map((r, i) => (
              <div key={r.id} className="border rounded-md p-3 text-sm space-y-1">
                <div className="flex justify-between items-center">
                  <span className="font-medium">Refund #{i + 1}</span>
                  <StatusBadge status={r.status} />
                </div>
                <div className="flex justify-between text-muted-foreground text-xs">
                  <span>{(r.amount / 100).toFixed(2)} {r.currency}</span>
                  <span>{format(new Date(r.createdAt), 'MMM d, yyyy HH:mm:ss')}</span>
                </div>
                {r.reason && <div className="text-xs text-muted-foreground">Reason: {r.reason.replace(/_/g, ' ')}</div>}
                {r.providerRefundId && <div className="font-mono text-xs text-muted-foreground">{r.providerRefundId}</div>}
                {r.failureReason && <div className="text-red-600 text-xs">{r.failureReason}</div>}
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function AttemptIcon({ attemptType }: { attemptType: string | null }) {
  const isRetry = attemptType === 'SAME_ACCOUNT_RETRY' || attemptType === 'FALLBACK_RETRY';
  const isFallback = attemptType === 'FALLBACK' || attemptType === 'FALLBACK_RETRY';
  const Icon = isRetry ? RotateCcw : isFallback ? CornerDownRight : null;

  return (
    <div className="absolute left-0 top-3 flex size-6 items-center justify-center rounded-full border bg-background">
      {Icon ? <Icon className="size-3.5 text-muted-foreground" /> : <span className="size-2 rounded-full bg-muted-foreground" />}
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

function AddressBlock({ addr }: { addr: Address }) {
  const lines = [
    addr.line1,
    addr.line2,
    [addr.city, addr.state, addr.postalCode].filter(Boolean).join(', '),
    addr.country,
  ].filter(Boolean);
  return (
    <div className="text-sm">
      {lines.map((line, i) => <div key={i}>{line}</div>)}
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
