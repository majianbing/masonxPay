'use client';

import { FormEvent, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Plus, RefreshCcw, WalletCards } from 'lucide-react';
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

interface IssuerPartner {
  issuerPartnerId: string;
  merchantId: string;
  mode: string;
  name: string;
  adapterType: string;
  status: string;
  externalProgramId: string | null;
  externalFundingSourceId: string | null;
  createdAt: string;
  updatedAt: string;
}

interface CardProgram {
  programId: string;
  merchantId: string;
  mode: string;
  issuerPartnerId: string;
  name: string;
  currency: string;
  status: string;
  systemOfRecord: string;
  fundingModel: string;
  feeScheduleId: string | null;
  externalProgramId: string | null;
  externalFundingSourceId: string | null;
  createdAt: string;
  updatedAt: string;
}

const SYSTEM_OF_RECORD = ['INTERNAL', 'EXTERNAL'] as const;
const FUNDING_MODELS = [
  'PREFUNDED_CARD_BALANCE',
  'PROGRAM_BALANCE',
  'JIT_FUNDING',
  'EXTERNAL_ISSUER_BALANCE',
  'SIMULATED',
] as const;

export default function IssuingProgramsPage() {
  const queryClient = useQueryClient();
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const [page, setPage] = useState(0);
  const [programName, setProgramName] = useState('Prepaid Card Program');
  const [currency, setCurrency] = useState('USD');
  const [issuerPartnerId, setIssuerPartnerId] = useState('');
  const [systemOfRecord, setSystemOfRecord] = useState('INTERNAL');
  const [fundingModel, setFundingModel] = useState('PREFUNDED_CARD_BALANCE');

  const partnersQuery = useQuery<PageResponse<IssuerPartner>>({
    queryKey: ['issuer-partners', activeMerchantId, mode],
    enabled: !!activeMerchantId,
    queryFn: () => apiFetch(`/api/v1/merchants/${activeMerchantId}/va/issuer-partners?mode=${mode}&page=0&size=100`),
  });

  const programsQuery = useQuery<PageResponse<CardProgram>>({
    queryKey: ['card-programs', activeMerchantId, mode, page],
    enabled: !!activeMerchantId,
    queryFn: () => apiFetch(`/api/v1/merchants/${activeMerchantId}/va/card-programs?mode=${mode}&page=${page}&size=20`),
  });

  const partners = partnersQuery.data?.content ?? [];
  const activePartners = useMemo(() => partners.filter((p) => p.status === 'ACTIVE'), [partners]);
  const selectedPartnerId = issuerPartnerId || activePartners[0]?.issuerPartnerId || partners[0]?.issuerPartnerId || '';

  const createProgram = useMutation({
    mutationFn: () => apiFetch<CardProgram>(`/api/v1/merchants/${activeMerchantId}/va/card-programs`, {
      method: 'POST',
      body: JSON.stringify({
        mode,
        issuerPartnerId: selectedPartnerId,
        name: programName,
        currency,
        status: 'ACTIVE',
        systemOfRecord,
        fundingModel,
        binRangeMetadata: '{}',
        featureFlagsJson: '{}',
        defaultControlsJson: '{}',
        settlementModelJson: '{}',
      }),
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['card-programs', activeMerchantId, mode] });
      toast.success('Card program created');
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? (error.detail ?? error.title) : 'Could not create card program');
    },
  });

  function submitProgram(e: FormEvent) {
    e.preventDefault();
    if (!activeMerchantId || !selectedPartnerId) return;
    createProgram.mutate();
  }

  if (!activeMerchantId) {
    return (
      <div className="space-y-4">
        <PageHeader refreshing={false} onRefresh={() => undefined} />
        <section className="rounded-md border bg-white px-4 py-10 text-center text-sm text-muted-foreground">
          Select a merchant to manage prepaid card programs.
        </section>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <PageHeader
        refreshing={programsQuery.isFetching || partnersQuery.isFetching}
        onRefresh={() => {
          partnersQuery.refetch();
          programsQuery.refetch();
        }}
      />

      <div className="grid gap-4 lg:grid-cols-[0.9fr_1.6fr]">
        <IssuerPartnerPanel
          partners={partners}
          selectedPartnerId={selectedPartnerId}
          onSelect={setIssuerPartnerId}
          mode={mode}
          loading={partnersQuery.isLoading}
          error={partnersQuery.error}
        />
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Card Program</CardTitle>
          </CardHeader>
          <CardContent>
            <form className="grid gap-3 md:grid-cols-2" onSubmit={submitProgram}>
              <div className="space-y-1.5">
                <Label>Name</Label>
                <Input value={programName} onChange={(e) => setProgramName(e.target.value)} />
              </div>
              <div className="space-y-1.5">
                <Label>Currency</Label>
                <Input value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} maxLength={20} />
              </div>
              <div className="space-y-1.5">
                <Label>Issuer Partner</Label>
                <Select value={selectedPartnerId} onValueChange={(v) => setIssuerPartnerId(v ?? '')}>
                  <SelectTrigger className="w-full">
                    <SelectValue placeholder="Select issuer" />
                  </SelectTrigger>
                  <SelectContent>
                    {activePartners.map((partner) => (
                      <SelectItem key={partner.issuerPartnerId} value={partner.issuerPartnerId}>
                        {partner.name} ({partner.adapterType})
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1.5">
                <Label>System of Record</Label>
                <Select value={systemOfRecord} onValueChange={(v) => setSystemOfRecord(v ?? 'INTERNAL')}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {SYSTEM_OF_RECORD.map((value) => <SelectItem key={value} value={value}>{value}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1.5 md:col-span-2">
                <Label>Funding Model</Label>
                <Select value={fundingModel} onValueChange={(v) => setFundingModel(v ?? 'PREFUNDED_CARD_BALANCE')}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {FUNDING_MODELS.map((value) => <SelectItem key={value} value={value}>{value}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <div className="md:col-span-2">
                <Button
                  type="submit"
                  disabled={createProgram.isPending || !programName.trim() || !currency.trim() || !selectedPartnerId}
                >
                  <Plus className="mr-2 size-4" />
                  Create program
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      </div>

      <section className="rounded-md border bg-white">
        <div className="flex items-center justify-between border-b px-4 py-3">
          <div>
            <h2 className="text-sm font-semibold">Programs</h2>
            <p className="text-xs text-muted-foreground">{mode} mode prepaid card programs</p>
          </div>
          <Badge variant="outline">{programsQuery.data?.totalElements ?? 0} total</Badge>
        </div>
        {programsQuery.isLoading ? (
          <div className="px-4 py-10 text-center text-sm text-muted-foreground">Loading programs...</div>
        ) : (programsQuery.data?.content ?? []).length === 0 ? (
          <div className="px-4 py-10 text-center text-sm text-muted-foreground">
            Configure an issuer partner first, then create a card program to start issuing simulator-backed prepaid cards.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-left text-xs uppercase text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">Program</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                  <th className="px-4 py-3 font-medium">Currency</th>
                  <th className="px-4 py-3 font-medium">Funding</th>
                  <th className="px-4 py-3 font-medium">System</th>
                  <th className="px-4 py-3 font-medium">Issuer</th>
                  <th className="px-4 py-3 font-medium">ID</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {(programsQuery.data?.content ?? []).map((program) => (
                  <tr key={program.programId} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-medium">{program.name}</td>
                    <td className="px-4 py-3"><StatusBadge status={program.status} /></td>
                    <td className="px-4 py-3">{program.currency}</td>
                    <td className="px-4 py-3">{program.fundingModel}</td>
                    <td className="px-4 py-3">{program.systemOfRecord}</td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">{issuerName(partners, program.issuerPartnerId)}</td>
                    <td className="px-4 py-3 font-mono text-xs text-muted-foreground">{program.programId}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="flex items-center justify-between border-t px-4 py-3 text-sm">
          <span className="text-muted-foreground">
            Page {page + 1} of {Math.max(programsQuery.data?.totalPages ?? 1, 1)}
          </span>
          <div className="flex gap-2">
            <Button variant="outline" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
              Previous
            </Button>
            <Button
              variant="outline"
              disabled={page + 1 >= (programsQuery.data?.totalPages ?? 1)}
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

function IssuerPartnerPanel({
  partners,
  selectedPartnerId,
  onSelect,
  mode,
  loading,
  error,
}: {
  partners: IssuerPartner[];
  selectedPartnerId: string;
  onSelect: (value: string) => void;
  mode: string;
  loading: boolean;
  error: unknown;
}) {
  const activePartners = partners.filter((partner) => partner.status === 'ACTIVE');
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm">Configured Issuer Partners</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="grid grid-cols-2 gap-3 text-sm">
          <div className="rounded-md border bg-gray-50 px-3 py-2">
            <div className="text-xs text-muted-foreground">Mode</div>
            <div className="font-medium">{mode}</div>
          </div>
          <div className="rounded-md border bg-gray-50 px-3 py-2">
            <div className="text-xs text-muted-foreground">Active</div>
            <div className="font-medium">{activePartners.length}</div>
          </div>
        </div>
        {loading ? (
          <div className="rounded-md border px-3 py-8 text-center text-sm text-muted-foreground">
            Loading issuer partners...
          </div>
        ) : error ? (
          <div className="rounded-md border border-red-200 bg-red-50 px-3 py-3 text-sm text-red-900">
            {errorMessage(error)}
          </div>
        ) : activePartners.length === 0 ? (
          <div className="rounded-md border border-amber-200 bg-amber-50 px-3 py-3 text-sm text-amber-900">
            No active issuer partner is configured for this merchant and mode.
          </div>
        ) : (
          <div className="space-y-2">
            {activePartners.map((partner) => (
              <button
                key={partner.issuerPartnerId}
                type="button"
                onClick={() => onSelect(partner.issuerPartnerId)}
                className={`w-full rounded-md border px-3 py-2 text-left text-sm transition-colors ${
                  selectedPartnerId === partner.issuerPartnerId
                    ? 'border-primary bg-primary/5'
                    : 'hover:bg-gray-50'
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium">{partner.name}</span>
                  <Badge variant="outline">{partner.adapterType}</Badge>
                </div>
                <div className="mt-1 text-xs text-muted-foreground">
                  {partner.status.toLowerCase()} issuer for {mode} programs
                </div>
              </button>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function PageHeader({ refreshing, onRefresh }: { refreshing: boolean; onRefresh: () => void }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div>
        <div className="flex items-center gap-2">
          <WalletCards className="size-5 text-primary" />
          <h1 className="text-2xl font-semibold tracking-normal">Card Programs</h1>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">
          Prepaid card program configuration for the active merchant.
        </p>
      </div>
      <Button variant="outline" onClick={onRefresh} disabled={refreshing}>
        <RefreshCcw className="mr-2 size-4" />
        Refresh
      </Button>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const variant = status === 'ACTIVE' ? 'default' : status === 'DRAFT' ? 'secondary' : 'outline';
  return <Badge variant={variant}>{status}</Badge>;
}

function issuerName(partners: IssuerPartner[], issuerPartnerId: string) {
  return partners.find((partner) => partner.issuerPartnerId === issuerPartnerId)?.name ?? issuerPartnerId;
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    return error.detail ?? error.title;
  }
  return 'Could not load issuer partners';
}
