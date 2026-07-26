'use client';

import { FormEvent, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Plus, RefreshCcw, Users } from 'lucide-react';
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

interface Cardholder {
  cardholderId: string;
  merchantId: string;
  mode: string;
  type: string;
  externalIssuerCardholderId: string | null;
  kycStatus: string;
  displayName: string | null;
  displayRef: string | null;
  profileRef: string | null;
  createdAt: string;
  updatedAt: string;
}

const CARDHOLDER_TYPES = ['INDIVIDUAL', 'BUSINESS', 'EMPLOYEE', 'SERVICE_ACCOUNT'] as const;
const KYC_STATUSES = ['PENDING', 'ACTIVE', 'REJECTED', 'SUSPENDED', 'CLOSED'] as const;

export default function IssuingCardholdersPage() {
  const queryClient = useQueryClient();
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const [page, setPage] = useState(0);
  const [displayName, setDisplayName] = useState('');
  const [displayRef, setDisplayRef] = useState('');
  const [profileRef, setProfileRef] = useState('');
  const [externalIssuerCardholderId, setExternalIssuerCardholderId] = useState('');
  const [type, setType] = useState('INDIVIDUAL');
  const [kycStatus, setKycStatus] = useState('ACTIVE');

  const cardholdersQuery = useQuery<PageResponse<Cardholder>>({
    queryKey: ['cardholders', activeMerchantId, mode, page],
    enabled: !!activeMerchantId,
    queryFn: () => apiFetch(`/api/v1/merchants/${activeMerchantId}/va/cardholders?mode=${mode}&page=${page}&size=20`),
  });

  const createCardholder = useMutation({
    mutationFn: () => apiFetch<Cardholder>(`/api/v1/merchants/${activeMerchantId}/va/cardholders`, {
      method: 'POST',
      body: JSON.stringify({
        mode,
        type,
        externalIssuerCardholderId: externalIssuerCardholderId.trim() || null,
        kycStatus,
        displayName: displayName.trim() || null,
        displayRef: displayRef.trim() || null,
        profileRef: profileRef.trim() || null,
      }),
    }),
    onSuccess: () => {
      setDisplayName('');
      setDisplayRef('');
      setProfileRef('');
      setExternalIssuerCardholderId('');
      queryClient.invalidateQueries({ queryKey: ['cardholders', activeMerchantId, mode] });
      toast.success('Cardholder created');
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? (error.detail ?? error.title) : 'Could not create cardholder');
    },
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!activeMerchantId) return;
    createCardholder.mutate();
  }

  if (!activeMerchantId) {
    return (
      <div className="space-y-4">
        <PageHeader refreshing={false} onRefresh={() => undefined} />
        <section className="rounded-md border bg-white px-4 py-10 text-center text-sm text-muted-foreground">
          Select a merchant to manage cardholders.
        </section>
      </div>
    );
  }

  const rows = cardholdersQuery.data?.content ?? [];

  return (
    <div className="space-y-5">
      <PageHeader refreshing={cardholdersQuery.isFetching} onRefresh={() => cardholdersQuery.refetch()} />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm">Create Cardholder</CardTitle>
        </CardHeader>
        <CardContent>
          <form className="grid gap-3 md:grid-cols-2 lg:grid-cols-3" onSubmit={submit}>
            <div className="space-y-1.5">
              <Label>Display Name</Label>
              <Input value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="Jane Cardholder" />
            </div>
            <div className="space-y-1.5">
              <Label>Type</Label>
              <Select value={type} onValueChange={(v) => setType(v ?? 'INDIVIDUAL')}>
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {CARDHOLDER_TYPES.map((value) => <SelectItem key={value} value={value}>{value}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label>KYC Status</Label>
              <Select value={kycStatus} onValueChange={(v) => setKycStatus(v ?? 'ACTIVE')}>
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {KYC_STATUSES.map((value) => <SelectItem key={value} value={value}>{value}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label>Display Ref</Label>
              <Input value={displayRef} onChange={(e) => setDisplayRef(e.target.value)} placeholder="employee-042" />
            </div>
            <div className="space-y-1.5">
              <Label>Issuer Cardholder ID</Label>
              <Input value={externalIssuerCardholderId} onChange={(e) => setExternalIssuerCardholderId(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label>Profile Ref</Label>
              <Input value={profileRef} onChange={(e) => setProfileRef(e.target.value)} placeholder="identity-profile-ref" />
            </div>
            <div className="lg:col-span-3">
              <Button type="submit" disabled={createCardholder.isPending || !type}>
                <Plus className="mr-2 size-4" />
                Create cardholder
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>

      <section className="rounded-md border bg-white">
        <div className="flex items-center justify-between border-b px-4 py-3">
          <div>
            <h2 className="text-sm font-semibold">Cardholders</h2>
            <p className="text-xs text-muted-foreground">{mode} mode issuing identities</p>
          </div>
          <Badge variant="outline">{cardholdersQuery.data?.totalElements ?? 0} total</Badge>
        </div>
        {cardholdersQuery.isLoading ? (
          <div className="px-4 py-10 text-center text-sm text-muted-foreground">Loading cardholders...</div>
        ) : rows.length === 0 ? (
          <div className="px-4 py-10 text-center text-sm text-muted-foreground">
            Create a cardholder before issuing prepaid cards.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-left text-xs uppercase text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">Cardholder</th>
                  <th className="px-4 py-3 font-medium">KYC</th>
                  <th className="px-4 py-3 font-medium">Type</th>
                  <th className="px-4 py-3 font-medium">Display Ref</th>
                  <th className="px-4 py-3 font-medium">Issuer Ref</th>
                  <th className="px-4 py-3 font-medium">ID</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {rows.map((cardholder) => (
                  <tr key={cardholder.cardholderId} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-medium">{cardholder.displayName || 'Unnamed cardholder'}</td>
                    <td className="px-4 py-3"><KycBadge status={cardholder.kycStatus} /></td>
                    <td className="px-4 py-3">{cardholder.type}</td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">{cardholder.displayRef || '-'}</td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">{cardholder.externalIssuerCardholderId || '-'}</td>
                    <td className="px-4 py-3 font-mono text-xs text-muted-foreground">{cardholder.cardholderId}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="flex items-center justify-between border-t px-4 py-3 text-sm">
          <span className="text-muted-foreground">
            Page {page + 1} of {Math.max(cardholdersQuery.data?.totalPages ?? 1, 1)}
          </span>
          <div className="flex gap-2">
            <Button variant="outline" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
              Previous
            </Button>
            <Button
              variant="outline"
              disabled={page + 1 >= (cardholdersQuery.data?.totalPages ?? 1)}
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
          <Users className="size-5 text-primary" />
          <h1 className="text-2xl font-semibold tracking-normal">Cardholders</h1>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">
          Merchant-owned cardholder records used to issue and operate prepaid cards.
        </p>
      </div>
      <Button variant="outline" onClick={onRefresh} disabled={refreshing}>
        <RefreshCcw className="mr-2 size-4" />
        Refresh
      </Button>
    </div>
  );
}

function KycBadge({ status }: { status: string }) {
  const variant = status === 'ACTIVE' ? 'default' : status === 'REJECTED' || status === 'SUSPENDED' ? 'destructive' : 'outline';
  return <Badge variant={variant}>{status}</Badge>;
}
