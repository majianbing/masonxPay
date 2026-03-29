'use client';

import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import {
  useReactTable,
  getCoreRowModel,
  flexRender,
  createColumnHelper,
} from '@tanstack/react-table';
import { format } from 'date-fns';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';

interface PaymentIntent {
  id: string;
  amount: number;
  currency: string;
  status: string;
  resolvedProvider: string;
  connectorAccountLabel: string | null;
  mode: string;
  createdAt: string;
}

interface PageResponse {
  content: PaymentIntent[];
  totalElements: number;
  totalPages: number;
  number: number;
}

const col = createColumnHelper<PaymentIntent>();

const columns = [
  col.accessor('id', {
    header: 'ID',
    cell: (i) => <span className="font-mono text-xs">{i.getValue().slice(0, 12)}…</span>,
  }),
  col.accessor('amount', {
    header: 'Amount',
    cell: (i) => `${(i.getValue() / 100).toFixed(2)} ${i.row.original.currency}`,
  }),
  col.accessor('status', {
    header: 'Status',
    cell: (i) => <StatusBadge status={i.getValue()} />,
  }),
  col.accessor('resolvedProvider', {
    header: 'Provider',
    cell: (i) => {
      const provider = i.getValue();
      const label = i.row.original.connectorAccountLabel;
      if (!provider) return '—';
      return (
        <span>
          {provider}
          {label && <span className="text-muted-foreground"> — {label}</span>}
        </span>
      );
    },
  }),
  col.accessor('mode', {
    header: 'Mode',
    cell: (i) => (
      <span className={`text-xs px-2 py-0.5 rounded-full ${i.getValue() === 'TEST' ? 'bg-yellow-100 text-yellow-700' : 'bg-green-100 text-green-700'}`}>
        {i.getValue()}
      </span>
    ),
  }),
  col.accessor('createdAt', {
    header: 'Created',
    cell: (i) => format(new Date(i.getValue()), 'MMM d, yyyy HH:mm'),
  }),
];

function useDebounce<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return debounced;
}

export default function PaymentsPage() {
  const router = useRouter();
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);

  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [providerFilter, setProviderFilter] = useState('ALL');
  const [idSearch, setIdSearch] = useState('');
  const [labelSearch, setLabelSearch] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');

  const debouncedId = useDebounce(idSearch, 400);
  const debouncedLabel = useDebounce(labelSearch, 400);

  // Reset to page 0 whenever any filter changes
  useEffect(() => { setPage(0); }, [statusFilter, providerFilter, debouncedId, debouncedLabel, dateFrom, dateTo]);

  const { data, isLoading } = useQuery<PageResponse>({
    queryKey: ['payment-intents', activeMerchantId, page, statusFilter, providerFilter, debouncedId, debouncedLabel, dateFrom, dateTo, mode],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '20', sort: 'createdAt,desc', mode });
      if (statusFilter !== 'ALL') params.set('status', statusFilter);
      if (providerFilter !== 'ALL') params.set('provider', providerFilter);
      if (debouncedId) params.set('search', debouncedId);
      if (debouncedLabel) params.set('labelSearch', debouncedLabel);
      if (dateFrom) params.set('dateFrom', dateFrom);
      if (dateTo) params.set('dateTo', dateTo);
      return apiFetch<PageResponse>(`/api/v1/merchants/${activeMerchantId}/payment-intents?${params}`);
    },
    enabled: !!activeMerchantId,
  });

  const table = useReactTable({
    data: data?.content ?? [],
    columns,
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    pageCount: data?.totalPages ?? 0,
  });

  const hasFilters = statusFilter !== 'ALL' || providerFilter !== 'ALL' || idSearch || labelSearch || dateFrom || dateTo;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Payments</h1>
      </div>

      {/* Filter bar */}
      <div className="flex flex-wrap gap-2 items-center">
        <Input
          placeholder="Search by ID…"
          value={idSearch}
          onChange={(e) => setIdSearch(e.target.value)}
          className="w-44"
        />
        <Select value={statusFilter} onValueChange={(v) => setStatusFilter(v ?? 'ALL')}>
          <SelectTrigger className="w-44">
            <SelectValue placeholder="All statuses" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All statuses</SelectItem>
            <SelectItem value="SUCCEEDED">Succeeded</SelectItem>
            <SelectItem value="FAILED">Failed</SelectItem>
            <SelectItem value="PROCESSING">Processing</SelectItem>
            <SelectItem value="CANCELED">Canceled</SelectItem>
          </SelectContent>
        </Select>
        <Select value={providerFilter} onValueChange={(v) => setProviderFilter(v ?? 'ALL')}>
          <SelectTrigger className="w-36">
            <SelectValue placeholder="All providers" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All providers</SelectItem>
            <SelectItem value="STRIPE">Stripe</SelectItem>
            <SelectItem value="SQUARE">Square</SelectItem>
            <SelectItem value="BRAINTREE">Braintree</SelectItem>
          </SelectContent>
        </Select>
        <Input
          placeholder="Connector label…"
          value={labelSearch}
          onChange={(e) => setLabelSearch(e.target.value)}
          className="w-40"
        />
        <input
          type="date"
          value={dateFrom}
          onChange={(e) => setDateFrom(e.target.value)}
          className="h-9 rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
          title="From date"
        />
        <span className="text-muted-foreground text-sm">→</span>
        <input
          type="date"
          value={dateTo}
          onChange={(e) => setDateTo(e.target.value)}
          className="h-9 rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
          title="To date"
        />
        {hasFilters && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setStatusFilter('ALL');
              setProviderFilter('ALL');
              setIdSearch('');
              setLabelSearch('');
              setDateFrom('');
              setDateTo('');
            }}
          >
            Clear
          </Button>
        )}
      </div>

      <Card>
        <CardContent className="p-0">
          <table className="w-full text-sm">
            <thead className="border-b bg-gray-50">
              {table.getHeaderGroups().map((hg) => (
                <tr key={hg.id}>
                  {hg.headers.map((h) => (
                    <th key={h.id} className="px-4 py-3 text-left font-medium text-muted-foreground">
                      {flexRender(h.column.columnDef.header, h.getContext())}
                    </th>
                  ))}
                </tr>
              ))}
            </thead>
            <tbody>
              {isLoading ? (
                <tr><td colSpan={6} className="py-12 text-center text-muted-foreground">Loading…</td></tr>
              ) : table.getRowModel().rows.length === 0 ? (
                <tr><td colSpan={6} className="py-12 text-center text-muted-foreground">No payments found</td></tr>
              ) : (
                table.getRowModel().rows.map((row) => (
                  <tr
                    key={row.id}
                    className="border-b last:border-0 hover:bg-gray-50 cursor-pointer"
                    onClick={() => router.push(`/payments/${row.original.id}`)}
                  >
                    {row.getVisibleCells().map((cell) => (
                      <td key={cell.id} className="px-4 py-3">
                        {flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </td>
                    ))}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>

      <div className="flex items-center justify-between text-sm text-muted-foreground">
        <span>{data?.totalElements ?? 0} total payments</span>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Previous
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={page >= (data?.totalPages ?? 1) - 1}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </Button>
        </div>
      </div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const color: Record<string, string> = {
    SUCCEEDED: 'bg-green-100 text-green-700',
    FAILED: 'bg-red-100 text-red-700',
    CANCELED: 'bg-gray-100 text-gray-600',
    PROCESSING: 'bg-blue-100 text-blue-700',
    REQUIRES_PAYMENT_METHOD: 'bg-orange-100 text-orange-700',
  };
  return (
    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${color[status] ?? 'bg-gray-100 text-gray-600'}`}>
      {status.replace(/_/g, ' ')}
    </span>
  );
}
