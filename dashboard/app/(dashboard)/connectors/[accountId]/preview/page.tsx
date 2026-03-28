'use client';

import { useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useQuery, useMutation } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ArrowLeft, CheckCircle2, XCircle, Loader2, CreditCard, Zap } from 'lucide-react';
import { cn } from '@/lib/utils';

// ─── Types ────────────────────────────────────────────────────────────────────

interface ProviderAccount {
  id: string;
  provider: string;
  label: string;
  secretKeyHint: string;
  status: string;
}

interface PreviewPaymentResponse {
  success: boolean;
  status: string;
  provider: string;
  connectorLabel: string;
  amount: number;
  currency: string;
  providerPaymentId?: string;
  failureCode?: string;
  failureMessage?: string;
}

// ─── Test scenarios ───────────────────────────────────────────────────────────

const TEST_SCENARIOS = [
  {
    id: 'pm_card_visa',
    label: 'Visa — Success',
    network: 'VISA',
    pan: '4242 4242 4242 4242',
    expiry: '12 / 34',
    cvc: '123',
    color: 'from-blue-600 to-blue-800',
    outcome: 'success' as const,
  },
  {
    id: 'pm_card_mastercard',
    label: 'Mastercard — Success',
    network: 'MC',
    pan: '5555 5555 5555 4444',
    expiry: '12 / 34',
    cvc: '456',
    color: 'from-orange-500 to-red-600',
    outcome: 'success' as const,
  },
  {
    id: 'pm_card_chargeDeclined',
    label: 'Visa — Declined',
    network: 'VISA',
    pan: '4000 0000 0000 0002',
    expiry: '12 / 34',
    cvc: '123',
    color: 'from-gray-500 to-gray-700',
    outcome: 'fail' as const,
  },
  {
    id: 'pm_card_chargeDeclinedInsufficientFunds',
    label: 'Visa — Insufficient Funds',
    network: 'VISA',
    pan: '4000 0000 0000 9995',
    expiry: '12 / 34',
    cvc: '123',
    color: 'from-gray-500 to-gray-700',
    outcome: 'fail' as const,
  },
];

// ─── Card visual ──────────────────────────────────────────────────────────────

function CardVisual({ scenario }: { scenario: typeof TEST_SCENARIOS[number] }) {
  return (
    <div className={cn(
      'relative w-full aspect-[1.586/1] rounded-2xl bg-gradient-to-br p-5 text-white shadow-xl select-none',
      scenario.color,
    )}>
      {/* chip */}
      <div className="w-9 h-7 rounded-md bg-yellow-300/80 mb-5 grid grid-cols-2 grid-rows-3 gap-px p-1">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="bg-yellow-500/40 rounded-sm" />
        ))}
      </div>
      {/* PAN */}
      <p className="font-mono text-base tracking-widest mb-3">{scenario.pan}</p>
      <div className="flex justify-between items-end">
        <div>
          <p className="text-xs uppercase opacity-60 mb-0.5">Expires</p>
          <p className="font-mono text-sm">{scenario.expiry}</p>
        </div>
        <div className="text-right">
          <p className="text-xs uppercase opacity-60 mb-0.5">CVC</p>
          <p className="font-mono text-sm">{scenario.cvc}</p>
        </div>
        <span className="text-xl font-bold opacity-90">{scenario.network}</span>
      </div>
      {/* outcome badge */}
      <span className={cn(
        'absolute top-4 right-4 text-xs px-2 py-0.5 rounded-full font-medium',
        scenario.outcome === 'success'
          ? 'bg-green-400/20 text-green-200 border border-green-400/30'
          : 'bg-red-400/20 text-red-200 border border-red-400/30',
      )}>
        {scenario.outcome === 'success' ? 'Will succeed' : 'Will decline'}
      </span>
    </div>
  );
}

// ─── Result overlay ───────────────────────────────────────────────────────────

function ResultOverlay({
  result,
  amount,
  currency,
  onReset,
}: {
  result: PreviewPaymentResponse;
  amount: number;
  currency: string;
  onReset: () => void;
}) {
  const fmt = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: currency.toUpperCase(),
  });

  return (
    <div className="flex flex-col items-center justify-center py-10 space-y-4 text-center">
      {result.success ? (
        <CheckCircle2 className="size-16 text-green-500" strokeWidth={1.5} />
      ) : (
        <XCircle className="size-16 text-red-500" strokeWidth={1.5} />
      )}

      <div>
        <p className="text-2xl font-semibold">{result.success ? 'Payment succeeded' : 'Payment failed'}</p>
        <p className="text-muted-foreground text-sm mt-1">{fmt.format(amount / 100)} via {result.connectorLabel}</p>
      </div>

      {result.success && result.providerPaymentId && (
        <div className="bg-gray-50 rounded-lg px-4 py-2 text-xs font-mono text-muted-foreground">
          {result.providerPaymentId}
        </div>
      )}

      {!result.success && (
        <div className="bg-red-50 border border-red-200 rounded-lg px-4 py-3 text-sm text-red-700 max-w-sm">
          <p className="font-medium">{result.failureCode ?? 'card_declined'}</p>
          {result.failureMessage && <p className="mt-0.5 opacity-80">{result.failureMessage}</p>}
        </div>
      )}

      <div className="flex gap-2 pt-2">
        <Button variant="outline" onClick={onReset}>
          Try again
        </Button>
      </div>
    </div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function PreviewPage() {
  const params = useParams<{ accountId: string }>();
  const router = useRouter();
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);

  const [selectedScenario, setSelectedScenario] = useState(TEST_SCENARIOS[0]);
  const [amount, setAmount] = useState('42.00');
  const [currency, setCurrency] = useState('USD');
  const [result, setResult] = useState<PreviewPaymentResponse | null>(null);

  // Fetch connector info for the header — preview is always TEST mode
  const { data: connectors = [] } = useQuery<ProviderAccount[]>({
    queryKey: ['connectors', activeMerchantId, 'TEST'],
    queryFn: () => apiFetch<ProviderAccount[]>(`/api/v1/merchants/${activeMerchantId}/connectors?mode=TEST`),
    enabled: !!activeMerchantId,
  });
  const connector = connectors.find((c) => c.id === params.accountId);

  const previewMutation = useMutation({
    mutationFn: () => {
      const cents = Math.round(parseFloat(amount) * 100);
      return apiFetch<PreviewPaymentResponse>(
        `/api/v1/merchants/${activeMerchantId}/connectors/${params.accountId}/preview`,
        {
          method: 'POST',
          body: JSON.stringify({
            amount: cents,
            currency: currency.toLowerCase(),
            testCard: selectedScenario.id,
          }),
        },
      );
    },
    onSuccess: (data) => setResult(data),
  });

  const amountCents = Math.round(parseFloat(amount || '0') * 100);
  const fmt = new Intl.NumberFormat('en-US', { style: 'currency', currency });

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

        {/* Left — card selector + amount */}
        <div className="space-y-6">
          <div>
            <h2 className="text-sm font-medium text-muted-foreground uppercase tracking-wider mb-3">
              Test scenario
            </h2>
            <div className="grid grid-cols-2 gap-2 mb-4">
              {TEST_SCENARIOS.map((s) => (
                <button
                  key={s.id}
                  onClick={() => { setSelectedScenario(s); setResult(null); }}
                  className={cn(
                    'text-left rounded-lg border-2 px-3 py-2 text-xs transition-all',
                    selectedScenario.id === s.id
                      ? 'border-primary bg-primary/5'
                      : 'border-transparent bg-white hover:border-gray-200',
                  )}
                >
                  <span className={cn(
                    'block text-xs font-medium mb-0.5',
                    s.outcome === 'success' ? 'text-green-700' : 'text-red-600',
                  )}>
                    {s.outcome === 'success' ? '✓' : '✗'} {s.label}
                  </span>
                  <span className="font-mono text-muted-foreground">{s.pan}</span>
                </button>
              ))}
            </div>
            <CardVisual scenario={selectedScenario} />
          </div>

          <div className="bg-blue-50 border border-blue-200 rounded-lg p-3 text-xs text-blue-700">
            <strong>Test mode only.</strong> These are Stripe's built-in test payment method tokens.
            No real charge is made. Results reflect your connector's actual Stripe test key.
          </div>
        </div>

        {/* Right — checkout form */}
        <div className="bg-white rounded-2xl shadow-sm border p-6 space-y-6">
          {result ? (
            <ResultOverlay
              result={result}
              amount={amountCents}
              currency={currency}
              onReset={() => setResult(null)}
            />
          ) : (
            <>
              <div>
                <h1 className="text-xl font-semibold">Complete payment</h1>
                <p className="text-sm text-muted-foreground mt-0.5">
                  Using <strong>{connector?.label ?? '…'}</strong>
                </p>
              </div>

              {/* Amount + Currency */}
              <div className="space-y-3">
                <div className="space-y-1">
                  <Label>Amount</Label>
                  <div className="flex gap-2">
                    <Input
                      type="number"
                      min="0.50"
                      step="0.01"
                      value={amount}
                      onChange={(e) => setAmount(e.target.value)}
                      className="flex-1"
                      placeholder="42.00"
                    />
                    <select
                      value={currency}
                      onChange={(e) => setCurrency(e.target.value)}
                      className="border rounded-md px-3 text-sm bg-white"
                    >
                      {['USD', 'EUR', 'GBP', 'CAD', 'AUD'].map((c) => (
                        <option key={c}>{c}</option>
                      ))}
                    </select>
                  </div>
                </div>
              </div>

              {/* Card detail display (read-only, comes from selected scenario) */}
              <div className="space-y-3">
                <Label>Card details</Label>
                <div className="rounded-lg border bg-gray-50 p-3 space-y-2">
                  <div className="flex items-center gap-2">
                    <CreditCard className="size-4 text-muted-foreground" />
                    <span className="font-mono text-sm">{selectedScenario.pan}</span>
                  </div>
                  <div className="flex gap-4">
                    <div className="space-y-0.5">
                      <p className="text-xs text-muted-foreground">Expires</p>
                      <p className="font-mono text-sm">{selectedScenario.expiry}</p>
                    </div>
                    <div className="space-y-0.5">
                      <p className="text-xs text-muted-foreground">CVC</p>
                      <p className="font-mono text-sm">{selectedScenario.cvc}</p>
                    </div>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    Pre-filled from selected test scenario
                  </p>
                </div>
              </div>

              {/* Order summary */}
              <div className="border-t pt-4 space-y-1.5 text-sm">
                <div className="flex justify-between text-muted-foreground">
                  <span>Subtotal</span>
                  <span>{amountCents > 0 ? fmt.format(amountCents / 100) : '—'}</span>
                </div>
                <div className="flex justify-between font-semibold text-base pt-1">
                  <span>Total</span>
                  <span>{amountCents > 0 ? fmt.format(amountCents / 100) : '—'}</span>
                </div>
              </div>

              <Button
                className="w-full"
                size="lg"
                disabled={previewMutation.isPending || amountCents < 50}
                onClick={() => previewMutation.mutate()}
              >
                {previewMutation.isPending ? (
                  <><Loader2 className="size-4 mr-2 animate-spin" /> Processing…</>
                ) : (
                  <>Pay {amountCents > 0 ? fmt.format(amountCents / 100) : ''}</>
                )}
              </Button>

              {previewMutation.isError && (
                <p className="text-xs text-red-500 text-center">
                  {(previewMutation.error as { detail?: string })?.detail ?? 'Preview failed'}
                </p>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
