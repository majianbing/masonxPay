'use client';

import { useEffect, useRef, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ArrowLeft, CheckCircle2, XCircle, Zap } from 'lucide-react';
import { GatewayEmbedded, type CheckoutResult } from '@gateway/browser';

// ─── Types ────────────────────────────────────────────────────────────────────

interface ProviderAccount {
  id: string;
  provider: string;
  label: string;
  status: string;
}

// Test card reference per provider — shown as a read-only helper panel
const TEST_CARDS: Record<string, { label: string; value: string }[]> = {
  STRIPE: [
    { label: 'Visa (success)', value: '4242 4242 4242 4242' },
    { label: 'Mastercard (success)', value: '5555 5555 5555 4444' },
    { label: 'Declined', value: '4000 0000 0000 0002' },
    { label: 'Insufficient funds', value: '4000 0000 0000 9995' },
  ],
  SQUARE: [
    { label: 'Visa (success)', value: '4111 1111 1111 1111' },
    { label: 'Declined', value: '4000 0000 0000 0002' },
  ],
  BRAINTREE: [
    { label: 'Visa (success)', value: '4111 1111 1111 1111' },
    { label: 'Mastercard (success)', value: '5431 1111 1111 1111' },
    { label: 'Declined', value: '4000 1111 1111 1115' },
  ],
};

// ─── Result overlay ───────────────────────────────────────────────────────────

function ResultOverlay({
  result,
  currency,
  onReset,
}: {
  result: CheckoutResult;
  currency: string;
  onReset: () => void;
}) {
  const fmt = new Intl.NumberFormat('en-US', { style: 'currency', currency: currency.toUpperCase() });

  return (
    <div className="flex flex-col items-center justify-center py-10 space-y-4 text-center">
      {result.success ? (
        <CheckCircle2 className="size-16 text-green-500" strokeWidth={1.5} />
      ) : (
        <XCircle className="size-16 text-red-500" strokeWidth={1.5} />
      )}

      <div>
        <p className="text-2xl font-semibold">{result.success ? 'Payment succeeded' : 'Payment failed'}</p>
        {result.paymentIntentId && (
          <p className="text-muted-foreground text-sm mt-1">{fmt.format(0)} · {result.paymentIntentId}</p>
        )}
      </div>

      {result.success && result.paymentIntentId && (
        <div className="bg-gray-50 rounded-lg px-4 py-2 text-xs font-mono text-muted-foreground">
          {result.paymentIntentId}
        </div>
      )}

      {!result.success && (
        <div className="bg-red-50 border border-red-200 rounded-lg px-4 py-3 text-sm text-red-700 max-w-sm">
          <p className="font-medium">{result.failureCode ?? 'card_declined'}</p>
          {result.failureMessage && <p className="mt-0.5 opacity-80">{result.failureMessage}</p>}
        </div>
      )}

      <Button variant="outline" onClick={onReset}>Try again</Button>
    </div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function PreviewPage() {
  const params = useParams<{ accountId: string }>();
  const router = useRouter();
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);

  const [amount, setAmount] = useState('42.00');
  const [currency, setCurrency] = useState('USD');
  const [linkToken, setLinkToken] = useState<string | null>(null);
  const [result, setResult] = useState<CheckoutResult | null>(null);
  const [linkError, setLinkError] = useState<string | null>(null);
  const [mounting, setMounting] = useState(false);

  const sdkContainerRef = useRef<HTMLDivElement>(null);
  const gwRef = useRef<GatewayEmbedded | null>(null);

  const apiBase = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

  const SESSION_KEY = `preview_link_token_${params.accountId}`;

  // On mount: if Stripe redirected back with a payment_intent_client_secret, restore the
  // saved linkToken from sessionStorage so the SDK can complete the flow.
  useEffect(() => {
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.get('payment_intent_client_secret')) {
      const saved = sessionStorage.getItem(SESSION_KEY);
      if (saved) {
        setMounting(true);
        setLinkToken(saved);
      }
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const { data: connectors = [] } = useQuery<ProviderAccount[]>({
    queryKey: ['connectors', activeMerchantId, 'TEST'],
    queryFn: () => apiFetch<ProviderAccount[]>(`/api/v1/merchants/${activeMerchantId}/connectors?mode=TEST`),
    enabled: !!activeMerchantId,
  });
  const connector = connectors.find((c) => c.id === params.accountId);
  const testCards = connector ? (TEST_CARDS[connector.provider] ?? []) : [];

  // Create a preview link then mount the SDK into the form panel
  async function startPreview() {
    if (!activeMerchantId) return;
    const cents = Math.round(parseFloat(amount) * 100);
    if (cents < 50) return;

    setLinkError(null);
    setResult(null);
    setLinkToken(null);
    setMounting(true);

    // Destroy any previous SDK instance
    gwRef.current?.destroy();
    gwRef.current = null;

    try {
      const data = await apiFetch<{ linkToken: string }>(
        `/api/v1/merchants/${activeMerchantId}/connectors/${params.accountId}/preview-link`,
        { method: 'POST', body: JSON.stringify({ amount: cents, currency: currency.toLowerCase() }) },
      );
      sessionStorage.setItem(SESSION_KEY, data.linkToken);
      setLinkToken(data.linkToken);
    } catch (e) {
      setLinkError((e as Error).message ?? 'Failed to start preview');
      setMounting(false);
    }
  }

  // Mount SDK once linkToken is set and container is in DOM
  useEffect(() => {
    if (!linkToken || !sdkContainerRef.current) return;

    const gw = new GatewayEmbedded('', { baseUrl: apiBase });
    gwRef.current = gw;

    gw.mountCheckout(sdkContainerRef.current, {
      linkToken,
      onSuccess: (r) => {
        sessionStorage.removeItem(SESSION_KEY);
        // Strip Stripe redirect params from the URL without a page reload
        const clean = window.location.pathname;
        window.history.replaceState({}, '', clean);
        setResult(r);
        setLinkToken(null);
      },
      onError: (e) => {
        sessionStorage.removeItem(SESSION_KEY);
        const clean = window.location.pathname;
        window.history.replaceState({}, '', clean);
        setLinkError(e.message);
        setLinkToken(null);
      },
    }).finally(() => setMounting(false));

    return () => { gw.destroy(); gwRef.current = null; };
  }, [linkToken, apiBase]);

  const amountCents = Math.round(parseFloat(amount || '0') * 100);
  const isReady = !!linkToken && !mounting;

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Top bar */}
      <div className="bg-white border-b px-6 py-3 flex items-center gap-4">
        <Button variant="ghost" size="icon" onClick={() => router.push('/connectors')}>
          <ArrowLeft className="size-4" />
        </Button>
        <div className="flex items-center gap-2">
          <Zap className="size-4 text-muted-foreground" />
          <span className="text-sm font-medium">Cashier Preview</span>
          {connector && (
            <>
              <span className="text-muted-foreground">·</span>
              <span className="text-sm text-muted-foreground">{connector.label}</span>
              <span className="text-xs px-1.5 py-0.5 rounded bg-yellow-100 text-yellow-700 font-medium">
                TEST MODE
              </span>
            </>
          )}
        </div>
      </div>

      <div className="max-w-4xl mx-auto px-4 py-10 grid grid-cols-1 md:grid-cols-2 gap-8 items-start">

        {/* Left — amount config + test card reference */}
        <div className="space-y-6">
          <div className="space-y-3">
            <h2 className="text-sm font-medium text-muted-foreground uppercase tracking-wider">
              Amount
            </h2>
            <div className="flex gap-2">
              <Input
                type="number"
                min="0.50"
                step="0.01"
                value={amount}
                onChange={(e) => { setAmount(e.target.value); setLinkToken(null); setResult(null); }}
                className="flex-1"
                placeholder="42.00"
              />
              <select
                value={currency}
                onChange={(e) => { setCurrency(e.target.value); setLinkToken(null); setResult(null); }}
                className="border rounded-md px-3 text-sm bg-white"
              >
                {['USD', 'EUR', 'GBP', 'CAD', 'AUD'].map((c) => (
                  <option key={c}>{c}</option>
                ))}
              </select>
            </div>
          </div>

          {testCards.length > 0 && (
            <div className="space-y-2">
              <h2 className="text-sm font-medium text-muted-foreground uppercase tracking-wider">
                Test card numbers
              </h2>
              <div className="bg-white border rounded-lg divide-y text-sm">
                {testCards.map((card) => (
                  <div key={card.value} className="flex justify-between items-center px-3 py-2">
                    <span className="text-muted-foreground">{card.label}</span>
                    <span className="font-mono text-xs">{card.value}</span>
                  </div>
                ))}
              </div>
              <p className="text-xs text-muted-foreground">Use any future expiry · any 3-digit CVC</p>
            </div>
          )}

          <div className="bg-blue-50 border border-blue-200 rounded-lg p-3 text-xs text-blue-700">
            <strong>Test mode only.</strong> No real charge is made. Results reflect your
            connector&apos;s actual {connector?.provider ?? 'provider'} test credentials.
          </div>
        </div>

        {/* Right — SDK checkout form */}
        <div className="bg-white rounded-2xl shadow-sm border p-6">
          {result ? (
            <ResultOverlay
              result={result}
              currency={currency}
              onReset={() => { sessionStorage.removeItem(SESSION_KEY); setResult(null); setLinkToken(null); }}
            />
          ) : (
            <div className="space-y-4">
              {!linkToken && (
                <>
                  <div>
                    <h1 className="text-xl font-semibold">Preview checkout</h1>
                    <p className="text-sm text-muted-foreground mt-0.5">
                      Using <strong>{connector?.label ?? '…'}</strong>
                    </p>
                  </div>
                  <Button
                    className="w-full"
                    size="lg"
                    disabled={amountCents < 50 || mounting}
                    onClick={startPreview}
                  >
                    {mounting ? 'Loading…' : `Launch ${amountCents >= 50
                      ? new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amountCents / 100)
                      : ''} checkout`}
                  </Button>
                  {linkError && (
                    <p className="text-xs text-red-500 text-center">{linkError}</p>
                  )}
                </>
              )}

              {/* SDK mounts here */}
              <div ref={sdkContainerRef} className={isReady ? '' : 'hidden'} />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
