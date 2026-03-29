'use client';

import { useEffect, useRef, useState } from 'react';
import { useParams } from 'next/navigation';
import { loadStripe } from '@stripe/stripe-js';
import type { Stripe, StripeElements } from '@stripe/stripe-js';
import { Loader2, CheckCircle2, XCircle, ShieldCheck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { PROVIDER_BRAND } from '@/lib/provider-brands';

// ─── Types ────────────────────────────────────────────────────────────────────

interface ProviderOption {
  provider: string;
  clientKey: string;
  clientConfig: Record<string, string>;
}

interface CheckoutSession {
  merchantName: string;
  mode: string;
  providers: ProviderOption[];
  amount: number;
  currency: string;
  title: string;
  description: string | null;
}

interface CheckoutResponse {
  success: boolean;
  status: string;
  paymentIntentId: string;
  redirectUrl?: string;
  failureCode?: string;
  failureMessage?: string;
}

// ─── API helpers ──────────────────────────────────────────────────────────────

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

async function fetchSession(linkToken: string): Promise<CheckoutSession> {
  const res = await fetch(`${API_BASE}/pub/checkout-session?linkToken=${linkToken}`);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error((err as { detail?: string }).detail ?? 'Payment link not found');
  }
  return res.json();
}

async function tokenize(provider: string, providerPmId: string, linkToken: string): Promise<string> {
  const res = await fetch(`${API_BASE}/pub/tokenize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, providerPmId, linkToken }),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error((err as { detail?: string }).detail ?? 'Tokenization failed');
  }
  const data = await res.json();
  return (data as { gatewayToken: string }).gatewayToken;
}

async function submitCheckout(linkToken: string, gatewayToken: string): Promise<CheckoutResponse> {
  const res = await fetch(`${API_BASE}/pub/pay/${linkToken}/checkout`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ gatewayToken }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error((data as { detail?: string }).detail ?? 'Payment failed');
  return data as CheckoutResponse;
}

function fmt(amount: number, currency: string) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: currency.toUpperCase() }).format(amount / 100);
}


async function fetchBraintreeClientToken(linkToken: string): Promise<string> {
  const res = await fetch(`${API_BASE}/pub/braintree-client-token?linkToken=${linkToken}`);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error((err as { detail?: string }).detail ?? 'Failed to get Braintree client token');
  }
  const data = await res.json();
  return (data as { clientToken: string }).clientToken;
}

// ─── Loading skeleton ─────────────────────────────────────────────────────────

function PaymentFormSkeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div className="animate-pulse space-y-3 py-1">
      {/* payment method tabs */}
      {rows > 1 && (
        <div className="flex gap-2">
          <div className="h-10 w-24 rounded-lg bg-gray-200" />
          <div className="h-10 w-24 rounded-lg bg-gray-200" />
          <div className="h-10 w-20 rounded-lg bg-gray-200" />
        </div>
      )}
      {/* card number */}
      <div className="h-10 w-full rounded-md bg-gray-200" />
      {/* expiry + cvc */}
      {rows > 1 && (
        <div className="flex gap-3">
          <div className="h-10 flex-1 rounded-md bg-gray-200" />
          <div className="h-10 flex-1 rounded-md bg-gray-200" />
        </div>
      )}
    </div>
  );
}

// ─── Stripe Payment Element (supports cards, wallets, bank transfers, BNPL…) ──

function StripeCardForm({
  clientKey,
  session,
  linkToken,
  onSuccess,
}: {
  clientKey: string;
  session: CheckoutSession;
  linkToken: string;
  onSuccess: (r: CheckoutResponse) => void;
}) {
  const mountRef = useRef<HTMLDivElement>(null);
  const stripeRef = useRef<Stripe | null>(null);
  const elementsRef = useRef<StripeElements | null>(null);
  const [ready, setReady] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    loadStripe(clientKey).then((stripe) => {
      if (!stripe || cancelled || !mountRef.current) return;
      stripeRef.current = stripe;

      const elements = stripe.elements({
        mode: 'payment',
        amount: session.amount,
        currency: session.currency.toLowerCase(),
        paymentMethodCreation: 'manual',
        appearance: { theme: 'stripe' },
      });
      elementsRef.current = elements;

      const paymentElement = elements.create('payment');
      paymentElement.mount(mountRef.current);
      paymentElement.on('ready', () => setReady(true));
      paymentElement.on('change', (e) => { if (e.complete) setError(null); });
    });

    return () => {
      cancelled = true;
      elementsRef.current = null;
      stripeRef.current = null;
      setReady(false);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clientKey, session.amount, session.currency]);

  async function handlePay() {
    if (!stripeRef.current || !elementsRef.current) return;
    setLoading(true);
    setError(null);

    const { error: submitError } = await elementsRef.current.submit();
    if (submitError) {
      setError(submitError.message ?? 'Validation error');
      setLoading(false);
      return;
    }

    const { paymentMethod, error: pmError } = await stripeRef.current.createPaymentMethod({
      elements: elementsRef.current,
    });

    if (pmError || !paymentMethod) {
      setError(pmError?.message ?? 'Payment method error');
      setLoading(false);
      return;
    }

    try {
      const gatewayToken = await tokenize('STRIPE', paymentMethod.id, linkToken);
      const result = await submitCheckout(linkToken, gatewayToken);
      if (result.success && result.redirectUrl) {
        window.location.href = result.redirectUrl;
      } else {
        onSuccess(result);
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="space-y-4">
      <div className="relative">
        {!ready && <PaymentFormSkeleton />}
        <div ref={mountRef} className={!ready ? 'opacity-0' : ''} />
      </div>

      {error && (
        <p className="text-xs text-red-500 flex items-center gap-1">
          <XCircle className="size-3" /> {error}
        </p>
      )}

      <Button
        className="w-full"
        size="lg"
        onClick={handlePay}
        disabled={loading || !ready}
      >
        {loading
          ? <><Loader2 className="size-4 mr-2 animate-spin" /> Processing…</>
          : `Pay ${fmt(session.amount, session.currency)}`}
      </Button>
    </div>
  );
}

// ─── Braintree Drop-in UI ─────────────────────────────────────────────────────

declare global {
  interface Window {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    Square?: any;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    braintree?: any;
  }
}

function BraintreeDropInForm({
  session,
  linkToken,
  onSuccess,
}: {
  clientKey: string; // merchantId — unused here, client token is fetched separately
  session: CheckoutSession;
  linkToken: string;
  onSuccess: (r: CheckoutResponse) => void;
}) {
  const mountRef = useRef<HTMLDivElement>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const dropinRef = useRef<any>(null);
  const [ready, setReady] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function init() {
      const clientToken = await fetchBraintreeClientToken(linkToken);
      if (cancelled) return;

      if (!window.braintree?.dropin) {
        const script = document.createElement('script');
        script.src = 'https://js.braintreegateway.com/web/dropin/1.43.0/js/dropin.min.js';
        script.async = true;
        await new Promise<void>((resolve, reject) => {
          script.onload = () => resolve();
          script.onerror = () => reject(new Error('Failed to load Braintree SDK'));
          document.head.appendChild(script);
        });
      }

      if (cancelled || !mountRef.current) return;

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const dropin = await (window.braintree as any).dropin.create({
        authorization: clientToken,
        container: mountRef.current,
      });

      if (cancelled) { dropin.teardown().catch(() => {}); return; }
      dropinRef.current = dropin;
      setReady(true);
    }

    init().catch((e: Error) => setError(e.message));

    return () => {
      cancelled = true;
      setReady(false);
      const d = dropinRef.current;
      dropinRef.current = null;
      if (d) d.teardown().catch(() => {});
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linkToken]);

  async function handlePay() {
    if (!dropinRef.current) return;
    setLoading(true);
    setError(null);
    try {
      const { nonce } = await dropinRef.current.requestPaymentMethod();
      const gatewayToken = await tokenize('BRAINTREE', nonce, linkToken);
      const result = await submitCheckout(linkToken, gatewayToken);
      if (result.success && result.redirectUrl) {
        window.location.href = result.redirectUrl;
      } else {
        onSuccess(result);
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="space-y-4">
      <div className="relative">
        {!ready && <PaymentFormSkeleton />}
        <div ref={mountRef} className={!ready ? 'opacity-0' : ''} />
      </div>
      {error && (
        <p className="text-xs text-red-500 flex items-center gap-1">
          <XCircle className="size-3" /> {error}
        </p>
      )}
      <Button
        className="w-full"
        size="lg"
        onClick={handlePay}
        disabled={loading || !ready}
      >
        {loading
          ? <><Loader2 className="size-4 mr-2 animate-spin" /> Processing…</>
          : `Pay ${fmt(session.amount, session.currency)}`}
      </Button>
    </div>
  );
}

// ─── Square card input (Square Web Payments SDK) ──────────────────────────────

function SquareCardForm({
  clientKey,
  locationId,
  session,
  linkToken,
  onSuccess,
}: {
  clientKey: string;
  locationId: string;
  session: CheckoutSession;
  linkToken: string;
  onSuccess: (r: CheckoutResponse) => void;
}) {
  const cardMountRef   = useRef<HTMLDivElement>(null);
  const googleMountRef = useRef<HTMLDivElement>(null);
  const appleMountRef  = useRef<HTMLDivElement>(null);
  const cashMountRef   = useRef<HTMLDivElement>(null);

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const cardRef   = useRef<any>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const methodRefs = useRef<any[]>([]);

  const [ready, setReady] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function init() {
      if (!window.Square) {
        const script = document.createElement('script');
        script.src = session.mode === 'TEST'
          ? 'https://sandbox.web.squarecdn.com/v1/square.js'
          : 'https://web.squarecdn.com/v1/square.js';
        script.async = true;
        await new Promise<void>((resolve, reject) => {
          script.onload = () => resolve();
          script.onerror = () => reject(new Error('Failed to load Square SDK'));
          document.head.appendChild(script);
        });
      }

      if (cancelled || !window.Square) return;

      const payments = window.Square.payments(clientKey, locationId);

      const paymentRequest = payments.paymentRequest({
        countryCode: 'US',
        currencyCode: session.currency?.toUpperCase() ?? 'USD',
        total: { amount: ((session.amount ?? 0) / 100).toFixed(2), label: session.title ?? 'Total' },
      });

      // ── mount card (always) ──
      const card = await payments.card();
      if (cancelled) { card.destroy().catch(() => {}); return; }
      if (cardMountRef.current) cardMountRef.current.innerHTML = '';
      await card.attach(cardMountRef.current!);
      if (cancelled) { card.destroy().catch(() => {}); return; }
      cardRef.current = card;

      // ── mount wallet buttons (best-effort — silently skip if unavailable) ──
      const wallets: { factory: () => Promise<any>; ref: React.RefObject<HTMLDivElement | null> }[] = [
        { factory: () => payments.googlePay(paymentRequest),  ref: googleMountRef },
        { factory: () => payments.applePay(paymentRequest),   ref: appleMountRef  },
        { factory: () => payments.cashAppPay(paymentRequest), ref: cashMountRef   },
      ];

      for (const { factory, ref } of wallets) {
        if (cancelled) break;
        try {
          const method = await factory();
          if (cancelled) { method.destroy().catch(() => {}); break; }
          if (ref.current) {
            ref.current.innerHTML = '';
            await method.attach(ref.current);
          }
          if (!cancelled) {
            methodRefs.current.push(method);
            method.addEventListener('ontokenization', async (event: any) => {
              const { tokenResult } = event.detail;
              if (tokenResult.status !== 'OK') return;
              await handleToken(tokenResult.token);
            });
          } else {
            method.destroy().catch(() => {});
          }
        } catch {
          // wallet not available in this browser / environment — skip
        }
      }

      if (!cancelled) setReady(true);
    }

    init().catch((e: Error) => setError(e.message));

    return () => {
      cancelled = true;
      setReady(false);
      const card = cardRef.current;
      cardRef.current = null;
      if (card) card.destroy().catch(() => {});
      const methods = methodRefs.current.splice(0);
      methods.forEach((m) => m.destroy().catch(() => {}));
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clientKey, locationId]);

  async function handleToken(sourceId: string) {
    setLoading(true);
    setError(null);
    try {
      const gatewayToken = await tokenize('SQUARE', sourceId, linkToken);
      const checkout = await submitCheckout(linkToken, gatewayToken);
      if (checkout.success && checkout.redirectUrl) {
        window.location.href = checkout.redirectUrl;
      } else {
        onSuccess(checkout);
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  async function handleCardPay() {
    if (!cardRef.current) return;
    setLoading(true);
    setError(null);
    try {
      const result = await cardRef.current.tokenize();
      if (result.status !== 'OK') {
        setError(result.errors?.map((e: { message: string }) => e.message).join(', ') ?? 'Card error');
        return;
      }
      await handleToken(result.token);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="space-y-4">
      {/* Wallet buttons — each div is empty until the SDK mounts into it */}
      <div ref={googleMountRef} />
      <div ref={appleMountRef} />
      <div ref={cashMountRef} />

      {(googleMountRef.current?.children.length || appleMountRef.current?.children.length || cashMountRef.current?.children.length)
        ? <div className="relative flex items-center gap-2 text-xs text-muted-foreground"><div className="flex-1 border-t"/><span>or pay by card</span><div className="flex-1 border-t"/></div>
        : null}

      <div className="space-y-1.5">
        <label className="text-sm font-medium">Card details</label>
        <div className="relative">
          {!ready && <PaymentFormSkeleton rows={1} />}
          <div ref={cardMountRef} className={!ready ? 'opacity-0' : ''} />
        </div>
      </div>

      {error && (
        <p className="text-xs text-red-500 flex items-center gap-1">
          <XCircle className="size-3" /> {error}
        </p>
      )}

      <Button className="w-full" size="lg" onClick={handleCardPay} disabled={loading || !ready}>
        {loading
          ? <><Loader2 className="size-4 mr-2 animate-spin" /> Processing…</>
          : `Pay ${fmt(session.amount, session.currency)}`}
      </Button>
    </div>
  );
}

// ─── Provider picker ──────────────────────────────────────────────────────────

function ProviderPicker({
  providers,
  selected,
  onSelect,
}: {
  providers: ProviderOption[];
  selected: string;
  onSelect: (p: string) => void;
}) {
  if (providers.length <= 1) return null;
  return (
    <div className="space-y-2">
      <p className="text-sm font-medium text-muted-foreground">Pay with</p>
      <div className="flex gap-2 flex-wrap">
        {providers.map((p) => {
          const brand = PROVIDER_BRAND[p.provider];
          const isSelected = selected === p.provider;
          return (
            <button
              key={p.provider}
              onClick={() => onSelect(p.provider)}
              className={cn(
                'flex items-center gap-2 px-3 py-2 rounded-xl border-2 text-sm font-semibold transition-all',
                isSelected
                  ? (brand?.color ?? 'border-primary bg-primary/5 text-primary')
                  : 'border-gray-200 text-muted-foreground hover:border-gray-300',
              )}
            >
              {brand?.icon}
              {brand?.name ?? p.provider}
            </button>
          );
        })}
      </div>
    </div>
  );
}

// ─── Success / failed screens ─────────────────────────────────────────────────

function SuccessScreen({ result, session }: { result: CheckoutResponse; session: CheckoutSession }) {
  return (
    <div className="flex flex-col items-center justify-center py-8 space-y-4 text-center">
      <CheckCircle2 className="size-16 text-green-500" strokeWidth={1.5} />
      <div>
        <p className="text-xl font-semibold">Payment complete</p>
        <p className="text-muted-foreground text-sm mt-1">
          {fmt(session.amount, session.currency)} · {session.title}
        </p>
      </div>
      <div className="bg-gray-50 rounded-lg px-4 py-2 text-xs font-mono text-muted-foreground max-w-full break-all">
        {result.paymentIntentId}
      </div>
      <p className="text-xs text-muted-foreground">Keep this reference for your records.</p>
    </div>
  );
}

function FailedScreen({ result, onRetry }: { result: CheckoutResponse; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center py-8 space-y-4 text-center">
      <XCircle className="size-16 text-red-500" strokeWidth={1.5} />
      <p className="text-xl font-semibold">Payment failed</p>
      <div className="bg-red-50 border border-red-200 rounded-lg px-4 py-3 text-sm text-red-700 max-w-sm">
        <p className="font-medium">{result.failureCode ?? 'card_declined'}</p>
        {result.failureMessage && <p className="mt-0.5 opacity-80">{result.failureMessage}</p>}
      </div>
      <Button variant="outline" onClick={onRetry}>Try again</Button>
    </div>
  );
}

// ─── Checkout form ────────────────────────────────────────────────────────────

function CheckoutForm({
  session,
  linkToken,
  onSuccess,
}: {
  session: CheckoutSession;
  linkToken: string;
  onSuccess: (r: CheckoutResponse) => void;
}) {
  const [selectedProvider, setSelectedProvider] = useState(session.providers[0]?.provider ?? '');
  const providerOpt = session.providers.find((p) => p.provider === selectedProvider);

  return (
    <div className="space-y-5">
      {/* Order summary */}
      <div className="rounded-xl bg-gray-50 border p-4 space-y-2">
        <div className="flex justify-between items-start">
          <div>
            <p className="font-medium text-sm">{session.title}</p>
            {session.description && (
              <p className="text-xs text-muted-foreground mt-0.5">{session.description}</p>
            )}
          </div>
          <p className="font-semibold text-sm">{fmt(session.amount, session.currency)}</p>
        </div>
        <div className="border-t pt-2 flex justify-between font-semibold text-base">
          <span>Total</span>
          <span>{fmt(session.amount, session.currency)}</span>
        </div>
      </div>

      <ProviderPicker
        providers={session.providers}
        selected={selectedProvider}
        onSelect={setSelectedProvider}
      />

      {selectedProvider === 'STRIPE' && providerOpt && (
        <StripeCardForm
          key={providerOpt.clientKey}
          clientKey={providerOpt.clientKey}
          session={session}
          linkToken={linkToken}
          onSuccess={onSuccess}
        />
      )}

      {selectedProvider === 'SQUARE' && providerOpt && (
        <SquareCardForm
          key={providerOpt.clientKey}
          clientKey={providerOpt.clientKey}
          locationId={providerOpt.clientConfig?.locationId ?? ''}
          session={session}
          linkToken={linkToken}
          onSuccess={onSuccess}
        />
      )}

      {selectedProvider === 'BRAINTREE' && providerOpt && (
        <BraintreeDropInForm
          key={providerOpt.clientKey}
          clientKey={providerOpt.clientKey}
          session={session}
          linkToken={linkToken}
          onSuccess={onSuccess}
        />
      )}

      <p className="text-center text-xs text-muted-foreground flex items-center justify-center gap-1.5">
        <ShieldCheck className="size-3.5" />
        Secured · {session.merchantName}
      </p>
    </div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function PayPage() {
  const { token } = useParams<{ token: string }>();
  const [session, setSession] = useState<CheckoutSession | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [result, setResult] = useState<CheckoutResponse | null>(null);

  useEffect(() => {
    if (!token) return;
    fetchSession(token)
      .then(setSession)
      .catch((e: Error) => setLoadError(e.message));
  }, [token]);

  if (loadError) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center p-4">
        <div className="text-center space-y-3">
          <XCircle className="size-12 text-red-400 mx-auto" strokeWidth={1.5} />
          <p className="font-medium text-gray-900">Link unavailable</p>
          <p className="text-sm text-muted-foreground">{loadError}</p>
        </div>
      </div>
    );
  }

  if (!session) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <Loader2 className="size-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (session.providers.length === 0) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center p-4">
        <div className="text-center space-y-3">
          <XCircle className="size-12 text-red-400 mx-auto" strokeWidth={1.5} />
          <p className="font-medium">No payment methods available</p>
          <p className="text-sm text-muted-foreground">The merchant has not configured a payment connector yet.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center p-4">
      {session.mode === 'TEST' && (
        <div className="fixed top-0 left-0 right-0 bg-yellow-400 text-yellow-900 text-center text-xs font-semibold py-1.5 z-50">
          TEST MODE — No real charge will be made
        </div>
      )}

      <div className="w-full max-w-md">
        <div className="mb-6 text-center">
          <p className="text-sm text-muted-foreground">{session.merchantName}</p>
          <h1 className="text-2xl font-semibold mt-1">{session.title}</h1>
          {session.description && (
            <p className="text-sm text-muted-foreground mt-1">{session.description}</p>
          )}
        </div>

        <div className="bg-white rounded-2xl shadow-sm border p-6">
          {result ? (
            result.success
              ? <SuccessScreen result={result} session={session} />
              : <FailedScreen result={result} onRetry={() => setResult(null)} />
          ) : (
            <CheckoutForm session={session} linkToken={token} onSuccess={setResult} />
          )}
        </div>
      </div>
    </div>
  );
}
