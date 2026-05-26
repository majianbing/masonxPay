'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Archive, Eye, Pencil, Plus, Send } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Badge } from '@/components/ui/badge';
import { Button, buttonVariants } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

interface RoutePolicy {
  id: string;
  mode: 'TEST' | 'LIVE';
  name: string;
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED';
  policyVersion: number;
  description?: string;
  publishedAt?: string;
  validationIssues?: Array<{ code: string; message: string }>;
}

const EMPTY_POLICIES: RoutePolicy[] = [];

export default function RoutingPoliciesListPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const qc = useQueryClient();
  const [statusFilter, setStatusFilter] = useState<'ALL' | RoutePolicy['status']>('ALL');

  const { data: policies = EMPTY_POLICIES, isLoading } = useQuery<RoutePolicy[]>({
    queryKey: ['route-policies', activeMerchantId],
    queryFn: () => apiFetch<RoutePolicy[]>(`/api/v1/merchants/${activeMerchantId}/route-policies`),
    enabled: !!activeMerchantId,
  });

  const visiblePolicies = useMemo(
    () => policies
      .filter((policy) => policy.mode === mode)
      .filter((policy) => statusFilter === 'ALL' || policy.status === statusFilter),
    [mode, policies, statusFilter],
  );

  const publishMutation = useMutation({
    mutationFn: (policyId: string) =>
      apiFetch<RoutePolicy>(`/api/v1/merchants/${activeMerchantId}/route-policies/${policyId}/publish`, { method: 'POST' }),
    onSuccess: (policy) => {
      qc.invalidateQueries({ queryKey: ['route-policies', activeMerchantId] });
      if ((policy.validationIssues ?? []).length > 0) {
        toast.error('Policy has validation issues');
      } else {
        toast.success('Policy published');
      }
    },
  });

  const archiveMutation = useMutation({
    mutationFn: (policyId: string) =>
      apiFetch<RoutePolicy>(`/api/v1/merchants/${activeMerchantId}/route-policies/${policyId}/archive`, { method: 'POST' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['route-policies', activeMerchantId] });
      toast.success('Policy archived');
    },
  });

  return (
    <div className="space-y-6 max-w-6xl">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">Routing Policies</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Manage versioned payment routing for {mode} traffic.
          </p>
        </div>
        <Link href="/routing/policies/new" className={buttonVariants()}>
          <Plus className="size-4 mr-2" /> New Policy
        </Link>
      </div>

      <div className="flex items-center justify-between gap-3">
        <div className="text-sm text-muted-foreground">
          {visiblePolicies.length} policies
        </div>
        <Select value={statusFilter} onValueChange={(value) => setStatusFilter(value as typeof statusFilter)}>
          <SelectTrigger className="w-44">
            <SelectValue placeholder="Filter status" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All statuses</SelectItem>
            <SelectItem value="ACTIVE">Active</SelectItem>
            <SelectItem value="DRAFT">Draft</SelectItem>
            <SelectItem value="ARCHIVED">Archived</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {isLoading ? (
        <p className="text-sm text-muted-foreground">Loading policies...</p>
      ) : visiblePolicies.length === 0 ? (
        <Card>
          <CardContent className="py-12 text-center text-muted-foreground">
            No routing policies for this mode.
          </CardContent>
        </Card>
      ) : (
        <div className="overflow-hidden rounded-md border bg-white">
          <table className="w-full text-sm">
            <thead className="border-b bg-gray-50 text-left text-xs uppercase text-muted-foreground">
              <tr>
                <th className="px-4 py-3 font-medium">Policy</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3 font-medium">Version</th>
                <th className="px-4 py-3 font-medium">Published</th>
                <th className="px-4 py-3 font-medium">Validation</th>
                <th className="px-4 py-3 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {visiblePolicies.map((policy) => (
                <tr key={policy.id} className="border-b last:border-0">
                  <td className="px-4 py-3">
                    <div className="font-medium">{policy.name}</div>
                    {policy.description && (
                      <div className="mt-1 max-w-md truncate text-xs text-muted-foreground">{policy.description}</div>
                    )}
                  </td>
                  <td className="px-4 py-3"><StatusBadge status={policy.status} /></td>
                  <td className="px-4 py-3">v{policy.policyVersion}</td>
                  <td className="px-4 py-3 text-muted-foreground">
                    {policy.publishedAt ? new Date(policy.publishedAt).toLocaleString() : '-'}
                  </td>
                  <td className="px-4 py-3">
                    {(policy.validationIssues ?? []).length > 0 ? (
                      <Badge variant="destructive">{policy.validationIssues!.length} issues</Badge>
                    ) : (
                      <Badge variant="outline">Ready</Badge>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex justify-end gap-2">
                      <Link href={`/routing/policies/${policy.id}`} className={buttonVariants({ variant: 'outline', size: 'sm' })}>
                        {policy.status === 'DRAFT' ? <Pencil className="size-4" /> : <Eye className="size-4" />}
                      </Link>
                      {policy.status !== 'ARCHIVED' && (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => archiveMutation.mutate(policy.id)}
                          disabled={archiveMutation.isPending}
                        >
                          <Archive className="size-4" />
                        </Button>
                      )}
                      {policy.status === 'DRAFT' && (
                        <Button
                          size="sm"
                          onClick={() => publishMutation.mutate(policy.id)}
                          disabled={publishMutation.isPending}
                        >
                          <Send className="size-4" />
                        </Button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function StatusBadge({ status }: { status: RoutePolicy['status'] }) {
  if (status === 'ACTIVE') return <Badge className="bg-green-600">Active</Badge>;
  if (status === 'ARCHIVED') return <Badge variant="secondary">Archived</Badge>;
  return <Badge variant="outline">Draft</Badge>;
}
