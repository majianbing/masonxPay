'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
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

interface CheckoutConnectorInfo {
  provider: string;
  accountId: string;
  clientKey: string;
  clientConfig: Record<string, string>;
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
  connectors: CheckoutConnectorInfo[];
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

// --- SDK loaders ---

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    if (document.querySelector(`script[src="${src}"]`)) { resolve(); return; }
    const s = document.createElement('script');
    s.src = src;
    s.onload = () => resolve();
    s.onerror = () => reject(new Error(`Failed to load ${src}`));
    document.head.appendChild(s);
  });
}

async function fetchSubscription(token: string): Promise<SubscriptionCheckoutInfo> {
  const res = await fetch(`${API_BASE}/pub/subscription-checkout/${token}`);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error((err as { detail?: string }).detail ?? 'Subscription link not found');
  }
  return res.json();
}

async function tokenizeAndCheckout(
  subscriptionToken: string,
  provider: string,
  providerPmId: string,
): Promise<SubscriptionCheckoutResult> {
  const tokenRes = await fetch(`${API_BASE}/pub/tokenize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, providerPmId, subscriptionToken }),
  });
  const tokenBody = await tokenRes.json().catch(() => ({}));
  if (!tokenRes.ok) throw new Error((tokenBody as { detail?: string }).detail ?? 'Could not prepare payment');

  const checkoutRes = await fetch(`${API_BASE}/pub/subscription-checkout/${subscriptionToken}/checkout`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ gatewayToken: (tokenBody as { gatewayToken: string }).gatewayToken }),
  });
  const checkoutBody = await checkoutRes.json().catch(() => ({}));
  if (!checkoutRes.ok) throw new Error((checkoutBody as { detail?: string }).detail ?? 'Could not activate subscription');
  return checkoutBody as SubscriptionCheckoutResult;
}

// --- Provider label helpers ---

const PROVIDER_LABELS: Record<string, string> = {
  STRIPE: 'Stripe',
  SQUARE: 'Square',
  BRAINTREE: 'Braintree',
  MOLLIE: 'Mollie',
  SIMULATOR: 'Mason Simulator',
};

function providerLabel(provider: string) {
  return PROVIDER_LABELS[provider] ?? provider;
}

// --- Main page component ---

export default function SubscribePage() {
  const { token } = useParams<{ token: string }>();
  const [info, setInfo] = useState<SubscriptionCheckoutInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activating, setActivating] = useState(false);
  const [result, setResult] = useState<SubscriptionCheckoutResult | null>(null);
  const [selectedConnector, setSelectedConnector] = useState<CheckoutConnectorInfo | null>(null);
  const [sdkReady, setSdkReady] = useState(false);

  // Refs for PSP SDK instances
  const stripeRef = useRef<{ stripe: unknown; card: unknown } | null>(null);
  const squareRef = useRef<{ payments: unknown; card: unknown } | null>(null);
  const braintreeRef = useRef<{ dropin: unknown } | null>(null);

  // Mount containers
  const stripeContainerRef = useRef<HTMLDivElement>(null);
  const squareContainerRef = useRef<HTMLDivElement>(null);
  const braintreeContainerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!token) return;
    fetchSubscription(token).then(data => {
      setInfo(data);
      // Deduplicate by brand so the picker shows one button per PSP, not one per account
      const seen = new Set<string>();
      const unique = (data.connectors ?? []).filter(c => {
        if (seen.has(c.provider)) return false;
        seen.add(c.provider);
        return true;
      });
      setSelectedConnector(unique[0] ?? null);
    }).catch((e: Error) => setError(e.message));
  }, [token]);

  // Load the selected PSP SDK whenever the brand selection changes.
  // Returns a cleanup function that destroys the mounted SDK instance so switching
  // A→B→A never leaves a stale mounted element in the container.
  useEffect(() => {
    if (!selectedConnector || !info?.active) return;
    setSdkReady(false);

    let cancelled = false;
    const connector = selectedConnector;

    (async () => {
      try {
        if (connector.provider === 'STRIPE') {
          await loadScript('https://js.stripe.com/v3/');
          if (cancelled) return;
          const stripe = (window as unknown as { Stripe: (key: string) => unknown }).Stripe(connector.clientKey);
          const elements = (stripe as { elements: () => unknown }).elements() as unknown as {
            create: (type: string, opts?: object) => { mount: (el: HTMLElement) => void; destroy: () => void };
          };
          const card = elements.create('card', {
            style: { base: { fontSize: '16px', color: '#1a1a1a', '::placeholder': { color: '#9ca3af' } } },
          });
          if (cancelled) { card.destroy(); return; }
          if (stripeContainerRef.current) card.mount(stripeContainerRef.current);
          stripeRef.current = { stripe, card };
          if (!cancelled) setSdkReady(true);
        } else if (connector.provider === 'SQUARE') {
          const sandbox = connector.clientConfig?.sandbox !== 'false';
          await loadScript(sandbox
            ? 'https://sandbox.web.squarecdn.com/v1/square.js'
            : 'https://web.squarecdn.com/v1/square.js');
          if (cancelled) return;
          const Square = (window as unknown as { Square: unknown }).Square as {
            payments: (appId: string, locationId: string) => Promise<{
              card: () => Promise<{ attach: (el: HTMLElement) => Promise<void>; tokenize: () => Promise<{ token: string }>; destroy: () => Promise<void> }>;
            }>;
          };
          const payments = await Square.payments(connector.clientKey, connector.clientConfig?.locationId ?? '');
          const card = await payments.card();
          if (cancelled) { await card.destroy(); return; }
          if (squareContainerRef.current) await card.attach(squareContainerRef.current);
          squareRef.current = { payments, card };
          if (!cancelled) setSdkReady(true);
        } else if (connector.provider === 'BRAINTREE') {
          const tokenRes = await fetch(
            `${API_BASE}/pub/subscription-checkout/braintree-client-token?accountId=${connector.accountId}&subscriptionToken=${token}`,
          );
          if (cancelled) return;
          if (!tokenRes.ok) throw new Error('Could not load Braintree credentials');
          const { clientToken } = await tokenRes.json();
          await loadScript('https://js.braintreegateway.com/web/dropin/1.43.0/js/dropin.min.js');
          if (cancelled) return;
          const braintree = (window as unknown as { braintree: unknown }).braintree as {
            dropin: {
              create: (opts: { authorization: string; container: HTMLElement | null }) => Promise<{
                requestPaymentMethod: () => Promise<{ nonce: string }>;
                teardown: () => Promise<void>;
              }>;
            };
          };
          const dropin = await braintree.dropin.create({
            authorization: clientToken,
            container: braintreeContainerRef.current,
          });
          if (cancelled) { dropin.teardown().catch(() => {}); return; }
          braintreeRef.current = { dropin };
          if (!cancelled) setSdkReady(true);
        } else {
          if (!cancelled) setSdkReady(true);
        }
      } catch (e) {
        if (!cancelled) setError((e as Error).message);
      }
    })();

    return () => {
      cancelled = true;
      // Unmount Stripe card element from DOM before re-mounting
      (stripeRef.current?.card as { destroy?: () => void } | null)?.destroy?.();
      stripeRef.current = null;
      // Destroy Square card
      (squareRef.current?.card as { destroy?: () => Promise<void> } | null)?.destroy?.();
      squareRef.current = null;
      // Teardown Braintree Drop-in (async — fire and forget)
      (braintreeRef.current?.dropin as { teardown?: () => Promise<void> } | null)?.teardown?.().catch(() => {});
      braintreeRef.current = null;
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedConnector?.provider, selectedConnector?.accountId]);

  async function activate() {
    if (!info || !selectedConnector) return;
    setActivating(true);
    setError(null);
    try {
      let providerPmId = '';

      if (selectedConnector.provider === 'STRIPE' && stripeRef.current) {
        const { stripe, card } = stripeRef.current as {
          stripe: { createPaymentMethod: (opts: object) => Promise<{ paymentMethod?: { id: string }; error?: { message: string } }> };
          card: unknown;
        };
        const { paymentMethod, error: stripeErr } = await stripe.createPaymentMethod({ type: 'card', card });
        if (stripeErr || !paymentMethod) throw new Error(stripeErr?.message ?? 'Could not tokenize card');
        providerPmId = paymentMethod.id;
      } else if (selectedConnector.provider === 'SQUARE' && squareRef.current) {
        const { card } = squareRef.current as {
          card: { tokenize: () => Promise<{ token?: string; errors?: { message: string }[] }> };
        };
        const result = await card.tokenize();
        if (!result.token) throw new Error(result.errors?.[0]?.message ?? 'Could not tokenize card');
        providerPmId = result.token;
      } else if (selectedConnector.provider === 'BRAINTREE' && braintreeRef.current) {
        const { dropin } = braintreeRef.current as {
          dropin: { requestPaymentMethod: () => Promise<{ nonce: string }> };
        };
        const { nonce } = await dropin.requestPaymentMethod();
        providerPmId = nonce;
      } else if (selectedConnector.provider === 'SIMULATOR') {
        providerPmId = `sim_pm_${Date.now()}`;
      } else {
        throw new Error(`${providerLabel(selectedConnector.provider)} checkout is not supported in this flow yet`);
      }

      const r = await tokenizeAndCheckout(info.token, selectedConnector.provider, providerPmId);
      setResult(r);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setActivating(false);
    }
  }

  // --- Render ---

  if (error && !info) {
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

  const total = info.items.reduce((sum, item) => sum + item.amount * item.quantity, 0);

  // One entry per PSP brand — multiple accounts of the same brand are collapsed;
  // the routing engine selects the specific account.
  const connectors = useMemo(() => {
    const seen = new Set<string>();
    return (info.connectors ?? []).filter(c => {
      if (seen.has(c.provider)) return false;
      seen.add(c.provider);
      return true;
    });
  }, [info.connectors]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 p-4">
      {info.mode === 'TEST' && (
        <div className="fixed left-0 right-0 top-0 z-50 bg-yellow-400 py-1.5 text-center text-xs font-semibold text-yellow-900">
          TEST MODE
        </div>
      )}

      <div className="w-full max-w-md rounded-2xl border bg-white p-6 shadow-sm">
        {/* Header */}
        <div className="mb-5 flex items-start justify-between gap-4">
          <div>
            <p className="text-sm text-muted-foreground">{info.merchantName || 'MasonXPay merchant'}</p>
            <h1 className="mt-1 text-xl font-semibold">Subscription checkout</h1>
          </div>
          <Badge variant={info.active ? 'secondary' : 'outline'}>{info.active ? 'ACTIVE LINK' : 'UNAVAILABLE'}</Badge>
        </div>

        <div className="space-y-4">
          {/* Billing summary */}
          <div className="rounded-xl border bg-gray-50 p-4">
            <div className="flex items-center gap-2 text-sm font-medium">
              <CalendarClock className="size-4 text-muted-foreground" />
              Every {info.intervalCount} {info.intervalUnit.toLowerCase()}{info.intervalCount === 1 ? '' : 's'}
            </div>
            {info.trialEndsAt && (
              <p className="mt-2 text-sm text-muted-foreground">
                Free trial until {new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
                  .format(new Date(info.trialEndsAt))}
              </p>
            )}
          </div>

          {/* Line items */}
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

          {/* Customer */}
          {(info.customerName || info.customerEmail) && (
            <div className="rounded-lg border px-3 py-2 text-sm">
              <p className="text-muted-foreground">Customer</p>
              <p className="font-medium">{info.customerName || info.customerEmail}</p>
              {info.customerName && info.customerEmail && (
                <p className="text-xs text-muted-foreground">{info.customerEmail}</p>
              )}
            </div>
          )}

          {/* Provider selection (only shown when multiple connectors) */}
          {info.active && connectors.length > 1 && (
            <div className="space-y-2">
              <p className="text-sm font-medium text-muted-foreground">Pay with</p>
              <div className="flex flex-wrap gap-2">
                {connectors.map((c) => (
                  <button
                    key={c.accountId}
                    onClick={() => { setSelectedConnector(c); setSdkReady(false); }}
                    className={`rounded-lg border px-3 py-1.5 text-sm font-medium transition-colors ${
                      selectedConnector?.accountId === c.accountId
                        ? 'border-primary bg-primary text-primary-foreground'
                        : 'border-gray-200 bg-white hover:border-gray-300'
                    }`}
                  >
                    {providerLabel(c.provider)}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* PSP card form containers — only one is shown at a time */}
          {info.active && selectedConnector && (
            <div>
              <div
                ref={stripeContainerRef}
                id="stripe-card-element"
                className={`rounded-lg border bg-white p-3 ${selectedConnector.provider === 'STRIPE' ? '' : 'hidden'}`}
              />
              <div
                ref={squareContainerRef}
                id="square-card-container"
                className={selectedConnector.provider === 'SQUARE' ? '' : 'hidden'}
              />
              <div
                ref={braintreeContainerRef}
                id="braintree-container"
                className={selectedConnector.provider === 'BRAINTREE' ? '' : 'hidden'}
              />
            </div>
          )}

          {/* Error */}
          {error && (
            <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
              {error}
            </div>
          )}

          {/* Failure from checkout attempt */}
          {result && !result.success && (
            <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
              <p className="font-medium">{result.failureCode || 'activation_failed'}</p>
              {result.failureMessage && <p className="mt-1 text-xs">{result.failureMessage}</p>}
            </div>
          )}

          {/* Submit button */}
          {connectors.length === 0 ? (
            <p className="text-center text-sm text-muted-foreground">No payment connectors configured for this merchant.</p>
          ) : (
            <Button
              className="w-full"
              disabled={!info.active || activating || !selectedConnector || (
                ['STRIPE', 'SQUARE', 'BRAINTREE'].includes(selectedConnector.provider) && !sdkReady
              )}
              onClick={activate}
            >
              {activating
                ? <><Loader2 className="mr-2 size-4 animate-spin" />Processing…</>
                : !sdkReady && selectedConnector && ['STRIPE', 'SQUARE', 'BRAINTREE'].includes(selectedConnector.provider)
                  ? <><Loader2 className="mr-2 size-4 animate-spin" />Loading…</>
                  : <><CheckCircle2 className="mr-2 size-4" />
                      {selectedConnector
                        ? `Continue with ${providerLabel(selectedConnector.provider)}`
                        : 'Continue'}
                    </>
              }
            </Button>
          )}

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
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: currency || 'USD' }).format(amount / 100);
}
