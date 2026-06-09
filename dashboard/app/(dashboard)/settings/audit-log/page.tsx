'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { format } from 'date-fns';
import { ChevronLeft, ChevronRight } from 'lucide-react';

interface AuditLogEntry {
  id: string;
  actorEmail: string | null;
  action: string;
  resourceType: string | null;
  resourceId: string | null;
  resourceLabel: string | null;
  metadata: string | null;
  createdAt: string;
}

interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

const ACTION_LABELS: Record<string, string> = {
  CONNECTOR_CREATED: 'Connector created',
  CONNECTOR_UPDATED: 'Connector updated',
  CONNECTOR_DELETED: 'Connector deleted',
  REFUND_ISSUED: 'Refund issued',
  MEMBER_INVITED: 'Member invited',
  MEMBER_ROLE_CHANGED: 'Role changed',
  MEMBER_REVOKED: 'Member revoked',
  API_KEY_CREATED: 'API key created',
  API_KEY_REVOKED: 'API key revoked',
};

const ACTION_COLORS: Record<string, string> = {
  CONNECTOR_CREATED: 'bg-green-100 text-green-700',
  CONNECTOR_UPDATED: 'bg-blue-100 text-blue-700',
  CONNECTOR_DELETED: 'bg-red-100 text-red-700',
  REFUND_ISSUED: 'bg-orange-100 text-orange-700',
  MEMBER_INVITED: 'bg-purple-100 text-purple-700',
  MEMBER_ROLE_CHANGED: 'bg-yellow-100 text-yellow-700',
  MEMBER_REVOKED: 'bg-red-100 text-red-700',
  API_KEY_CREATED: 'bg-green-100 text-green-700',
  API_KEY_REVOKED: 'bg-red-100 text-red-700',
};

function MetadataSummary({ raw }: { raw: string | null }) {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    const parts = Object.entries(parsed).map(([k, v]) => `${k}: ${v}`);
    return <span className="text-xs text-muted-foreground">{parts.join(' · ')}</span>;
  } catch {
    return null;
  }
}

const ALL_ACTIONS = 'ALL';

export default function AuditLogPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const [page, setPage] = useState(0);
  const [actionFilter, setActionFilter] = useState(ALL_ACTIONS);

  const { data, isLoading } = useQuery<Page<AuditLogEntry>>({
    queryKey: ['audit-logs', activeMerchantId, page, actionFilter],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '20' });
      if (actionFilter !== ALL_ACTIONS) params.set('action', actionFilter);
      return apiFetch<Page<AuditLogEntry>>(
        `/api/v1/merchants/${activeMerchantId}/audit-logs?${params}`
      );
    },
    enabled: !!activeMerchantId,
  });

  function handleFilterChange(value: string) {
    setActionFilter(value);
    setPage(0);
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Audit Log</h1>
        <Select value={actionFilter} onValueChange={handleFilterChange}>
          <SelectTrigger className="w-48">
            <SelectValue placeholder="All actions" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL_ACTIONS}>All actions</SelectItem>
            {Object.entries(ACTION_LABELS).map(([value, label]) => (
              <SelectItem key={value} value={value}>{label}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">
            {data ? `${data.totalElements} events` : 'Events'}
          </CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          {isLoading ? (
            <div className="py-12 text-center text-sm text-muted-foreground">Loading…</div>
          ) : !data?.content.length ? (
            <div className="py-12 text-center text-sm text-muted-foreground">No audit events yet.</div>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b bg-gray-50 text-xs text-muted-foreground">
                  <th className="px-4 py-3 text-left font-medium">Time</th>
                  <th className="px-4 py-3 text-left font-medium">Actor</th>
                  <th className="px-4 py-3 text-left font-medium">Action</th>
                  <th className="px-4 py-3 text-left font-medium">Resource</th>
                  <th className="px-4 py-3 text-left font-medium">Details</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((entry) => (
                  <tr key={entry.id} className="border-b last:border-0 hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 whitespace-nowrap text-xs text-muted-foreground">
                      {format(new Date(entry.createdAt), 'MMM d, yyyy HH:mm:ss')}
                    </td>
                    <td className="px-4 py-3 text-xs">
                      {entry.actorEmail ?? <span className="text-muted-foreground">system</span>}
                    </td>
                    <td className="px-4 py-3">
                      <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${ACTION_COLORS[entry.action] ?? 'bg-gray-100 text-gray-600'}`}>
                        {ACTION_LABELS[entry.action] ?? entry.action}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-xs max-w-[180px] truncate">
                      {entry.resourceLabel ?? entry.resourceId ?? '—'}
                    </td>
                    <td className="px-4 py-3">
                      <MetadataSummary raw={entry.metadata} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </CardContent>
      </Card>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground">
            Page {data.number + 1} of {data.totalPages}
          </span>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => setPage((p) => p - 1)} disabled={page === 0}>
              <ChevronLeft className="size-4" />
              Prev
            </Button>
            <Button variant="outline" size="sm" onClick={() => setPage((p) => p + 1)} disabled={page >= data.totalPages - 1}>
              Next
              <ChevronRight className="size-4" />
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
