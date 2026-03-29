'use client';

import { useEffect, useRef, useState } from 'react';
import { useParams } from 'next/navigation';
import { Loader2, CheckCircle2, XCircle, ShieldCheck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { GatewayEmbedded, type CheckoutResult } from '@gateway/browser';

// ─── Types ────────────────────────────────────────────────────────────────────

interface CheckoutSession {
  merchantName: string;
  mode: string;
  providers: { provider: string }[];
  amount: number;
  currency: string;
  title: string;
  description: string | null;
}

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

async function fetchSession(linkToken: string): Promise<CheckoutSession> {
  const res = await fetch(`${API_BASE}/pub/checkout-session?linkToken=${linkToken}`);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error((err as { detail?: string }).detail ?? 'Payment link not found');
  }
  return res.json();
}

function fmt(amount: number, currency: string) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: currency.toUpperCase() }).format(amount / 100);
}

// ─── Success / failed screens ─────────────────────────────────────────────────

function SuccessScreen({ result, session }: { result: CheckoutResult; session: CheckoutSession }) {
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

function FailedScreen({ result, onRetry }: { result: CheckoutResult; onRetry: () => void }) {
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

// ─── Checkout form — SDK owns the payment area ────────────────────────────────

function CheckoutForm({
  session,
  linkToken,
  onSuccess,
}: {
  session: CheckoutSession;
  linkToken: string;
  onSuccess: (r: CheckoutResult) => void;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const gatewayRef = useRef<GatewayEmbedded | null>(null);
  const [sdkReady, setSdkReady] = useState(false);
  const [sdkError, setSdkError] = useState<string | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;
    const gw = new GatewayEmbedded('', { baseUrl: API_BASE });
    gatewayRef.current = gw;

    gw.on('ready', () => setSdkReady(true));

    gw.mountCheckout(containerRef.current, {
      linkToken,
      onSuccess,
      onError: (err) => setSdkError(err.message),
    }).catch((err: Error) => setSdkError(err.message));

    return () => {
      gatewayRef.current?.destroy();
      gatewayRef.current = null;
      setSdkReady(false);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linkToken]);

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

      {/* SDK-rendered: provider picker + payment form + pay button */}
      {!sdkReady && !sdkError && (
        <div className="animate-pulse space-y-3 py-1">
          <div className="flex gap-2">
            <div className="h-10 w-24 rounded-lg bg-gray-200" />
            <div className="h-10 w-24 rounded-lg bg-gray-200" />
          </div>
          <div className="h-10 w-full rounded-md bg-gray-200" />
          <div className="flex gap-3">
            <div className="h-10 flex-1 rounded-md bg-gray-200" />
            <div className="h-10 flex-1 rounded-md bg-gray-200" />
          </div>
          <div className="h-12 w-full rounded-lg bg-gray-200" />
        </div>
      )}

      {sdkError && (
        <p className="text-xs text-red-500 flex items-center gap-1">
          <XCircle className="size-3" /> {sdkError}
        </p>
      )}

      <div ref={containerRef} className={!sdkReady ? 'hidden' : ''} />

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
  const [result, setResult] = useState<CheckoutResult | null>(null);

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
