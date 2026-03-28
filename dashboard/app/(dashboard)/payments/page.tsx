'use client';

import { useState } from 'react';
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
    cell: (i) => i.getValue() ?? '—',
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

export default function PaymentsPage() {
  const router = useRouter();
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState('ALL');

  const { data, isLoading } = useQuery<PageResponse>({
    queryKey: ['payment-intents', activeMerchantId, page, statusFilter, mode],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '20', sort: 'createdAt,desc', mode });
      if (statusFilter !== 'ALL') params.set('status', statusFilter);
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

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Payments</h1>
      </div>

      <div className="flex gap-3">
        <Select value={statusFilter} onValueChange={(v: string | null) => setStatusFilter(v ?? 'ALL')}>
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
