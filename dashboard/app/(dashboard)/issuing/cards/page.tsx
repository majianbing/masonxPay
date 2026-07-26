'use client';

import { FormEvent, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Copy, CreditCard, Lock, Plus, RefreshCcw, Unlock } from 'lucide-react';
import { ApiError, apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

interface LedgerAccount {
  ledgerAccountId: string;
  mode: string;
  ledgerAccountType: string;
  asset: string;
  balance: number | string;
  status: string;
}

interface CardProgram {
  programId: string;
  name: string;
  currency: string;
  status: string;
}

interface Cardholder {
  cardholderId: string;
  displayName: string | null;
  displayRef: string | null;
  kycStatus: string;
}

interface CardRecord {
  cardId: string;
  cardTokenId: string;
  programId: string;
  issuerPartnerId: string;
  cardholderId: string;
  externalIssuerCardId: string | null;
  externalCardToken: string | null;
  maskedPan: string;
  bin: string;
  status: string;
  balance: number | string;
  frozenBalance: number | string;
  availableBalance: number | string;
  spendingLimit: number | string | null;
  currency: string;
  expiry: string | null;
}

interface CreatedCard extends CardRecord {
  testPan: string | null;
}

export default function IssuingCardsPage() {
  const queryClient = useQueryClient();
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const [page, setPage] = useState(0);
  const [programId, setProgramId] = useState('');
  const [cardholderId, setCardholderId] = useState('');
  const [ownerAccountId, setOwnerAccountId] = useState('');
  const [spendingLimit, setSpendingLimit] = useState('');
  const [expiry, setExpiry] = useState('');
  const [fundAmounts, setFundAmounts] = useState<Record<string, string>>({});
  const [withdrawAmounts, setWithdrawAmounts] = useState<Record<string, string>>({});
  const [createdCard, setCreatedCard] = useState<CreatedCard | null>(null);

  const accountsQuery = useQuery<PageResponse<LedgerAccount> & { enabled?: boolean }>({
    queryKey: ['va-accounts', activeMerchantId, mode],
    enabled: !!activeMerchantId,
    queryFn: () => apiFetch(`/api/v1/merchants/${activeMerchantId}/va/accounts?mode=${mode}&page=0&size=100`),
  });

  const programsQuery = useQuery<PageResponse<CardProgram>>({
    queryKey: ['card-programs', activeMerchantId, mode],
    enabled: !!activeMerchantId,
    queryFn: () => apiFetch(`/api/v1/merchants/${activeMerchantId}/va/card-programs?mode=${mode}&page=0&size=100`),
  });

  const cardholdersQuery = useQuery<PageResponse<Cardholder>>({
    queryKey: ['cardholders', activeMerchantId, mode],
    enabled: !!activeMerchantId,
    queryFn: () => apiFetch(`/api/v1/merchants/${activeMerchantId}/va/cardholders?mode=${mode}&page=0&size=100`),
  });

  const cardsQuery = useQuery<PageResponse<CardRecord>>({
    queryKey: ['cards', activeMerchantId, page],
    enabled: !!activeMerchantId,
    queryFn: () => apiFetch(`/api/v1/merchants/${activeMerchantId}/va/cards?page=${page}&size=20`),
  });

  const walletAccounts = useMemo(
    () => (accountsQuery.data?.content ?? []).filter((account) => (
      account.ledgerAccountType === 'WALLET' && account.mode === mode && account.status === 'ACTIVE'
    )),
    [accountsQuery.data?.content, mode],
  );
  const activePrograms = useMemo(
    () => (programsQuery.data?.content ?? []).filter((program) => program.status === 'ACTIVE'),
    [programsQuery.data?.content],
  );
  const activeCardholders = useMemo(
    () => (cardholdersQuery.data?.content ?? []).filter((cardholder) => cardholder.kycStatus === 'ACTIVE'),
    [cardholdersQuery.data?.content],
  );

  const selectedProgram = activePrograms.find((program) => program.programId === programId) ?? activePrograms[0];
  const selectedProgramId = programId || selectedProgram?.programId || '';
  const selectedCardholderId = cardholderId || activeCardholders[0]?.cardholderId || '';
  const selectedOwnerAccountId = ownerAccountId || walletAccounts[0]?.ledgerAccountId || '';
  const selectedCurrency = selectedProgram?.currency ?? walletAccounts[0]?.asset ?? 'USD';

  const createCard = useMutation({
    mutationFn: () => apiFetch<CreatedCard>(`/api/v1/merchants/${activeMerchantId}/va/cards`, {
      method: 'POST',
      body: JSON.stringify({
        mode,
        ownerAccountId: selectedOwnerAccountId,
        programId: selectedProgramId,
        cardholderId: selectedCardholderId,
        currency: selectedCurrency,
        spendingLimit: spendingLimit.trim() || null,
        expiry: expiry || null,
      }),
    }),
    onSuccess: (card) => {
      setCreatedCard(card);
      queryClient.invalidateQueries({ queryKey: ['cards', activeMerchantId] });
      toast.success('Card created');
    },
    onError: (error) => {
      toast.error(errorMessage(error, 'Could not create card'));
    },
  });

  const fundCard = useMutation({
    mutationFn: ({ cardId, amount }: { cardId: string; amount: string }) => apiFetch<CardRecord>(
      `/api/v1/merchants/${activeMerchantId}/va/cards/${cardId}/fund`,
      {
        method: 'POST',
        body: JSON.stringify({
          mode,
          amount,
          idempotencyKey: `dashboard-fund-${cardId}-${Date.now()}`,
        }),
      },
    ),
    onSuccess: (_, vars) => {
      setFundAmounts((current) => ({ ...current, [vars.cardId]: '' }));
      queryClient.invalidateQueries({ queryKey: ['cards', activeMerchantId] });
      toast.success('Card funded');
    },
    onError: (error) => toast.error(errorMessage(error, 'Could not fund card')),
  });

  const withdrawCard = useMutation({
    mutationFn: ({ cardId, amount }: { cardId: string; amount: string }) => apiFetch<CardRecord>(
      `/api/v1/merchants/${activeMerchantId}/va/cards/${cardId}/withdraw`,
      {
        method: 'POST',
        body: JSON.stringify({
          mode,
          amount,
          idempotencyKey: `dashboard-withdraw-${cardId}-${Date.now()}`,
        }),
      },
    ),
    onSuccess: (_, vars) => {
      setWithdrawAmounts((current) => ({ ...current, [vars.cardId]: '' }));
      queryClient.invalidateQueries({ queryKey: ['cards', activeMerchantId] });
      queryClient.invalidateQueries({ queryKey: ['va-accounts', activeMerchantId, mode] });
      toast.success('Card funds withdrawn');
    },
    onError: (error) => toast.error(errorMessage(error, 'Could not withdraw card funds')),
  });

  const lifecycle = useMutation({
    mutationFn: ({ cardId, action }: { cardId: string; action: 'lock' | 'unlock' }) => apiFetch<CardRecord>(
      `/api/v1/merchants/${activeMerchantId}/va/cards/${cardId}/${action}`,
      {
        method: 'POST',
        body: JSON.stringify({ mode, reason: `dashboard ${action}` }),
      },
    ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['cards', activeMerchantId] });
      toast.success('Card updated');
    },
    onError: (error) => toast.error(errorMessage(error, 'Could not update card')),
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!activeMerchantId || !selectedOwnerAccountId || !selectedProgramId || !selectedCardholderId) return;
    createCard.mutate();
  }

  if (!activeMerchantId) {
    return (
      <div className="space-y-4">
        <PageHeader refreshing={false} onRefresh={() => undefined} />
        <section className="rounded-md border bg-white px-4 py-10 text-center text-sm text-muted-foreground">
          Select a merchant to manage prepaid cards.
        </section>
      </div>
    );
  }

  const cards = cardsQuery.data?.content ?? [];
  const loading = accountsQuery.isLoading || programsQuery.isLoading || cardholdersQuery.isLoading;

  return (
    <div className="space-y-5">
      <PageHeader
        refreshing={accountsQuery.isFetching || programsQuery.isFetching || cardholdersQuery.isFetching || cardsQuery.isFetching}
        onRefresh={() => {
          accountsQuery.refetch();
          programsQuery.refetch();
          cardholdersQuery.refetch();
          cardsQuery.refetch();
        }}
      />

      {createdCard?.testPan && (
        <Card className="border-amber-200 bg-amber-50">
          <CardHeader>
            <CardTitle className="text-sm text-amber-950">One-Time Simulator PAN</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-wrap items-center justify-between gap-3 text-sm text-amber-950">
            <div>
              <div className="font-mono text-base font-semibold">{createdCard.testPan}</div>
              <p className="mt-1 text-amber-800">Shown once for simulator testing. Core stores only masked PAN.</p>
            </div>
            <Button variant="outline" onClick={() => navigator.clipboard.writeText(createdCard.testPan ?? '')}>
              <Copy className="mr-2 size-4" />
              Copy
            </Button>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-sm">Create Card</CardTitle>
        </CardHeader>
        <CardContent>
          <form className="grid gap-3 lg:grid-cols-3" onSubmit={submit}>
            <div className="space-y-1.5">
              <Label>Card Program</Label>
              <Select value={selectedProgramId} onValueChange={(v) => setProgramId(v ?? '')}>
                <SelectTrigger className="w-full">
                  <SelectValue placeholder="Select program">
                    {selectedProgram ? programLabel(selectedProgram) : null}
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  {activePrograms.map((program) => (
                    <SelectItem key={program.programId} value={program.programId}>
                      {programLabel(program)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label>Cardholder</Label>
              <Select value={selectedCardholderId} onValueChange={(v) => setCardholderId(v ?? '')}>
                <SelectTrigger className="w-full">
                  <SelectValue placeholder="Select cardholder">
                    {cardholderLabel(activeCardholders.find((cardholder) => cardholder.cardholderId === selectedCardholderId))}
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  {activeCardholders.map((cardholder) => (
                    <SelectItem key={cardholder.cardholderId} value={cardholder.cardholderId}>
                      {cardholderLabel(cardholder)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label>Funding Wallet</Label>
              <Select value={selectedOwnerAccountId} onValueChange={(v) => setOwnerAccountId(v ?? '')}>
                <SelectTrigger className="w-full">
                  <SelectValue placeholder="Select wallet">
                    {walletLabel(walletAccounts.find((account) => account.ledgerAccountId === selectedOwnerAccountId))}
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  {walletAccounts.map((account) => (
                    <SelectItem key={account.ledgerAccountId} value={account.ledgerAccountId}>
                      {walletLabel(account)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label>Currency</Label>
              <Input value={selectedCurrency} disabled />
            </div>
            <div className="space-y-1.5">
              <Label>Spending Limit</Label>
              <Input value={spendingLimit} onChange={(e) => setSpendingLimit(e.target.value)} placeholder="Optional" />
            </div>
            <div className="space-y-1.5">
              <Label>Expiry</Label>
              <Input type="date" value={expiry} onChange={(e) => setExpiry(e.target.value)} />
            </div>
            <div className="lg:col-span-3">
              <Button
                type="submit"
                disabled={createCard.isPending || loading || !selectedOwnerAccountId || !selectedProgramId || !selectedCardholderId}
              >
                <Plus className="mr-2 size-4" />
                Create card
              </Button>
            </div>
          </form>
          {!loading && (!selectedProgramId || !selectedCardholderId || !selectedOwnerAccountId) && (
            <p className="mt-3 text-sm text-amber-700">
              Create an active program, active cardholder, and active wallet account before issuing a card.
            </p>
          )}
        </CardContent>
      </Card>

      <section className="rounded-md border bg-white">
        <div className="flex items-center justify-between border-b px-4 py-3">
          <div>
            <h2 className="text-sm font-semibold">Cards</h2>
            <p className="text-xs text-muted-foreground">Masked prepaid cards and available balances</p>
          </div>
          <Badge variant="outline">{cardsQuery.data?.totalElements ?? 0} total</Badge>
        </div>
        {cardsQuery.isLoading ? (
          <div className="px-4 py-10 text-center text-sm text-muted-foreground">Loading cards...</div>
        ) : cards.length === 0 ? (
          <div className="px-4 py-10 text-center text-sm text-muted-foreground">No cards issued yet.</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-left text-xs uppercase text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">Card</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                  <th className="px-4 py-3 font-medium">Available</th>
                  <th className="px-4 py-3 font-medium">Hold</th>
                  <th className="px-4 py-3 font-medium">Limit</th>
                  <th className="px-4 py-3 font-medium">Fund</th>
                  <th className="px-4 py-3 font-medium">Withdraw</th>
                  <th className="px-4 py-3 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {cards.map((card) => (
                  <tr key={card.cardId} className="hover:bg-gray-50">
                    <td className="px-4 py-3">
                      <div className="font-medium">{card.maskedPan}</div>
                      <div className="text-xs text-muted-foreground">
                        {programName(activePrograms, card.programId)}; expires {card.expiry || '-'}
                      </div>
                    </td>
                    <td className="px-4 py-3"><StatusBadge status={card.status} /></td>
                    <td className="px-4 py-3 font-medium">{formatAmount(card.availableBalance, card.currency)}</td>
                    <td className="px-4 py-3">{formatAmount(card.frozenBalance, card.currency)}</td>
                    <td className="px-4 py-3">{card.spendingLimit ? formatAmount(card.spendingLimit, card.currency) : '-'}</td>
                    <td className="px-4 py-3">
                      <div className="flex min-w-44 gap-2">
                        <Input
                          value={fundAmounts[card.cardId] ?? ''}
                          onChange={(e) => setFundAmounts((current) => ({ ...current, [card.cardId]: e.target.value }))}
                          placeholder="Amount"
                          className="h-9"
                        />
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={fundCard.isPending || card.status !== 'ACTIVE' || !(fundAmounts[card.cardId] ?? '').trim()}
                          onClick={() => fundCard.mutate({ cardId: card.cardId, amount: fundAmounts[card.cardId] ?? '' })}
                        >
                          Fund
                        </Button>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex min-w-44 gap-2">
                        <Input
                          value={withdrawAmounts[card.cardId] ?? ''}
                          onChange={(e) => setWithdrawAmounts((current) => ({ ...current, [card.cardId]: e.target.value }))}
                          placeholder="Amount"
                          className="h-9"
                        />
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={withdrawCard.isPending || !['ACTIVE', 'LOCKED'].includes(card.status) || !(withdrawAmounts[card.cardId] ?? '').trim()}
                          onClick={() => withdrawCard.mutate({ cardId: card.cardId, amount: withdrawAmounts[card.cardId] ?? '' })}
                        >
                          Withdraw
                        </Button>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      {card.status === 'LOCKED' ? (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={lifecycle.isPending}
                          onClick={() => lifecycle.mutate({ cardId: card.cardId, action: 'unlock' })}
                        >
                          <Unlock className="mr-2 size-4" />
                          Unlock
                        </Button>
                      ) : (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={lifecycle.isPending || card.status !== 'ACTIVE'}
                          onClick={() => lifecycle.mutate({ cardId: card.cardId, action: 'lock' })}
                        >
                          <Lock className="mr-2 size-4" />
                          Lock
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="flex items-center justify-between border-t px-4 py-3 text-sm">
          <span className="text-muted-foreground">
            Page {page + 1} of {Math.max(cardsQuery.data?.totalPages ?? 1, 1)}
          </span>
          <div className="flex gap-2">
            <Button variant="outline" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
              Previous
            </Button>
            <Button
              variant="outline"
              disabled={page + 1 >= (cardsQuery.data?.totalPages ?? 1)}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </Button>
          </div>
        </div>
      </section>
    </div>
  );
}

function PageHeader({ refreshing, onRefresh }: { refreshing: boolean; onRefresh: () => void }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div>
        <div className="flex items-center gap-2">
          <CreditCard className="size-5 text-primary" />
          <h1 className="text-2xl font-semibold tracking-normal">Cards</h1>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">Issue, fund, and operate simulator-backed prepaid cards.</p>
      </div>
      <Button variant="outline" onClick={onRefresh} disabled={refreshing}>
        <RefreshCcw className="mr-2 size-4" />
        Refresh
      </Button>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const variant = status === 'ACTIVE' ? 'default' : status === 'LOCKED' ? 'secondary' : 'outline';
  return <Badge variant={variant}>{status}</Badge>;
}

function formatAmount(value: number | string | null | undefined, currency: string) {
  const n = typeof value === 'number' ? value : Number(value ?? 0);
  return `${Number.isFinite(n) ? n.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }) : '0.00'} ${currency}`;
}

function programName(programs: CardProgram[], programId: string) {
  return programs.find((program) => program.programId === programId)?.name ?? 'Card program';
}

function programLabel(program?: CardProgram) {
  return program ? `${program.name} (${program.currency})` : null;
}

function cardholderLabel(cardholder?: Cardholder) {
  if (!cardholder) return null;
  return cardholder.displayName || cardholder.displayRef || 'Active cardholder';
}

function walletLabel(account?: LedgerAccount) {
  return account ? `${formatAmount(account.balance, account.asset)} wallet` : null;
}

function errorMessage(error: unknown, fallback: string) {
  if (error instanceof ApiError) {
    return error.detail ?? error.title;
  }
  return fallback;
}
