'use client';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { CalendarClock, CheckCircle2, Loader2, ShieldCheck, XCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';

interface SubscriptionItem {
  id: string;
  description: string;
  amount: number;
  quantity: number;
}

interface SubscriptionCheckoutInfo {
  token: string;
  subscriptionId: string;
  merchantName: string;
  customerName: string | null;
  customerEmail: string | null;
  mode: 'TEST' | 'LIVE';
  status: string;
  currency: string;
  intervalUnit: 'DAY' | 'WEEK' | 'MONTH' | 'YEAR';
  intervalCount: number;
  trialEndsAt: string | null;
  active: boolean;
  items: SubscriptionItem[];
}

interface SubscriptionCheckoutResult {
  success: boolean;
  status: string;
  subscriptionId: string;
  paymentIntentId: string | null;
  failureCode: string | null;
  failureMessage: string | null;
}

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

async function fetchSubscription(token: string): Promise<SubscriptionCheckoutInfo> {
  const res = await fetch(`${API_BASE}/pub/subscription-checkout/${token}`);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error((err as { detail?: string }).detail ?? 'Subscription link not found');
  }
  return res.json();
}

export default function SubscribePage() {
  const { token } = useParams<{ token: string }>();
  const [info, setInfo] = useState<SubscriptionCheckoutInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activating, setActivating] = useState(false);
  const [result, setResult] = useState<SubscriptionCheckoutResult | null>(null);

  useEffect(() => {
    if (!token) return;
    fetchSubscription(token)
      .then(setInfo)
      .catch((e: Error) => setError(e.message));
  }, [token]);

  if (error) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-50 p-4">
        <div className="space-y-3 text-center">
          <XCircle className="mx-auto size-12 text-red-400" strokeWidth={1.5} />
          <p className="font-medium text-gray-900">Link unavailable</p>
          <p className="text-sm text-muted-foreground">{error}</p>
        </div>
      </div>
    );
  }

  if (!info) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-50">
        <Loader2 className="size-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  const total = info.items.reduce((sum, item) => sum + item.amount * item.quantity, 0);

  async function activateWithSimulator() {
    setActivating(true);
    setError(null);
    try {
      const tokenRes = await fetch(`${API_BASE}/pub/tokenize`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          provider: 'SIMULATOR',
          providerPmId: `sim_pm_${Date.now()}`,
          subscriptionToken: token,
        }),
      });
      const tokenBody = await tokenRes.json().catch(() => ({}));
      if (!tokenRes.ok) {
        throw new Error((tokenBody as { detail?: string }).detail ?? 'Could not prepare payment method');
      }

      const checkoutRes = await fetch(`${API_BASE}/pub/subscription-checkout/${token}/checkout`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ gatewayToken: (tokenBody as { gatewayToken: string }).gatewayToken }),
      });
      const checkoutBody = await checkoutRes.json().catch(() => ({}));
      if (!checkoutRes.ok) {
        throw new Error((checkoutBody as { detail?: string }).detail ?? 'Could not activate subscription');
      }
      setResult(checkoutBody as SubscriptionCheckoutResult);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setActivating(false);
    }
  }

  if (result?.success) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-50 p-4">
        <div className="w-full max-w-md rounded-2xl border bg-white p-6 text-center shadow-sm">
          <CheckCircle2 className="mx-auto size-14 text-green-500" strokeWidth={1.5} />
          <h1 className="mt-4 text-xl font-semibold">
            {result.status === 'TRIALING' ? 'Trial started' : 'Subscription active'}
          </h1>
          <p className="mt-2 text-sm text-muted-foreground">
            {info.merchantName || 'The merchant'} can now manage this subscription.
          </p>
          {result.paymentIntentId && (
            <div className="mt-4 break-all rounded-lg bg-gray-50 px-3 py-2 font-mono text-xs text-muted-foreground">
              {result.paymentIntentId}
            </div>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 p-4">
      {info.mode === 'TEST' && (
        <div className="fixed left-0 right-0 top-0 z-50 bg-yellow-400 py-1.5 text-center text-xs font-semibold text-yellow-900">
          TEST MODE
        </div>
      )}

      <div className="w-full max-w-md rounded-2xl border bg-white p-6 shadow-sm">
        <div className="mb-5 flex items-start justify-between gap-4">
          <div>
            <p className="text-sm text-muted-foreground">{info.merchantName || 'MasonXPay merchant'}</p>
            <h1 className="mt-1 text-xl font-semibold">Subscription checkout</h1>
          </div>
          <Badge variant={info.active ? 'secondary' : 'outline'}>{info.active ? 'ACTIVE LINK' : 'UNAVAILABLE'}</Badge>
        </div>

        <div className="space-y-4">
          <div className="rounded-xl border bg-gray-50 p-4">
            <div className="flex items-center gap-2 text-sm font-medium">
              <CalendarClock className="size-4 text-muted-foreground" />
              Every {info.intervalCount} {info.intervalUnit.toLowerCase()}{info.intervalCount === 1 ? '' : 's'}
            </div>
            {info.trialEndsAt && (
              <p className="mt-2 text-sm text-muted-foreground">
                Free trial until {new Intl.DateTimeFormat('en-US', {
                  month: 'short',
                  day: 'numeric',
                  year: 'numeric',
                }).format(new Date(info.trialEndsAt))}
              </p>
            )}
          </div>

          <div className="space-y-2">
            {info.items.map((item) => (
              <div key={item.id} className="flex items-start justify-between gap-4 text-sm">
                <div>
                  <p className="font-medium">{item.description}</p>
                  {item.quantity > 1 && <p className="text-xs text-muted-foreground">Quantity {item.quantity}</p>}
                </div>
                <p className="font-semibold">{formatMoney(item.amount * item.quantity, info.currency)}</p>
              </div>
            ))}
            <div className="border-t pt-3">
              <div className="flex items-center justify-between font-semibold">
                <span>Recurring total</span>
                <span>{formatMoney(total, info.currency)}</span>
              </div>
            </div>
          </div>

          {(info.customerName || info.customerEmail) && (
            <div className="rounded-lg border px-3 py-2 text-sm">
              <p className="text-muted-foreground">Customer</p>
              <p className="font-medium">{info.customerName || info.customerEmail}</p>
              {info.customerName && info.customerEmail && (
                <p className="text-xs text-muted-foreground">{info.customerEmail}</p>
              )}
            </div>
          )}

          {result && !result.success && (
            <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
              <p className="font-medium">{result.failureCode || 'activation_failed'}</p>
              {result.failureMessage && <p className="mt-1 text-xs">{result.failureMessage}</p>}
            </div>
          )}

          <Button className="w-full" disabled={!info.active || activating || info.mode !== 'TEST'} onClick={activateWithSimulator}>
            <CheckCircle2 className="size-4" />
            {activating ? 'Processing...' : info.mode === 'TEST' ? 'Continue with Mason Simulator' : 'Live activation pending'}
          </Button>

          <p className="flex items-center justify-center gap-1.5 text-center text-xs text-muted-foreground">
            <ShieldCheck className="size-3.5" />
            Secured by MasonXPay
          </p>
        </div>
      </div>
    </div>
  );
}

function formatMoney(amount: number, currency: string) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: currency || 'USD',
  }).format(amount / 100);
}
