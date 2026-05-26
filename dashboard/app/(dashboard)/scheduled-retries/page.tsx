'use client';

import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { format } from 'date-fns';
import { toast } from 'sonner';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

interface ScheduledRetryJob {
  id: string;
  operation: 'PAYMENT_CAPTURE' | 'REFUND';
  status: 'SCHEDULED' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'CANCELED';
  paymentIntentId: string | null;
  refundId: string | null;
  connectorAccountId: string | null;
  attemptCount: number;
  maxAttempts: number;
  nextRunAt: string;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  retryReason: string | null;
  lockedAt: string | null;
  lockedBy: string | null;
  completedAt: string | null;
  createdAt: string;
}

const STATUS_OPTIONS = ['ALL', 'SCHEDULED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELED'];

const STATUS_STYLE: Record<string, string> = {
  SCHEDULED: 'bg-blue-100 text-blue-700',
  PROCESSING: 'bg-purple-100 text-purple-700',
  SUCCEEDED: 'bg-green-100 text-green-700',
  FAILED: 'bg-red-100 text-red-700',
  CANCELED: 'bg-gray-100 text-gray-600',
};

export default function ScheduledRetriesPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const queryClient = useQueryClient();
  const [status, setStatus] = useState('ALL');

  const queryKey = useMemo(() => ['scheduled-retries', activeMerchantId, status], [activeMerchantId, status]);

  const { data, isLoading } = useQuery<ScheduledRetryJob[]>({
    queryKey,
    queryFn: () => {
      const params = new URLSearchParams();
      if (status !== 'ALL') params.set('status', status);
      const suffix = params.toString() ? `?${params}` : '';
      return apiFetch<ScheduledRetryJob[]>(
        `/api/v1/merchants/${activeMerchantId}/scheduled-retries${suffix}`,
      );
    },
    enabled: !!activeMerchantId,
  });

  const cancelMutation = useMutation({
    mutationFn: (jobId: string) =>
      apiFetch<ScheduledRetryJob>(
        `/api/v1/merchants/${activeMerchantId}/scheduled-retries/${jobId}/cancel`,
        { method: 'POST' },
      ),
    onSuccess: () => {
      toast.success('Scheduled retry canceled');
      queryClient.invalidateQueries({ queryKey: ['scheduled-retries', activeMerchantId] });
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string; title?: string };
      toast.error(e.detail ?? e.title ?? 'Could not cancel retry');
    },
  });

  const jobs = data ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Scheduled Retries</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Delayed recovery jobs for manual captures. Refund auto-retry is disabled by default to avoid duplicate fund movement.
          </p>
        </div>
        <Select value={status} onValueChange={(v) => setStatus(v ?? 'ALL')}>
          <SelectTrigger className="w-44">
            <SelectValue placeholder="All statuses" />
          </SelectTrigger>
          <SelectContent>
            {STATUS_OPTIONS.map((value) => (
              <SelectItem key={value} value={value}>
                {value === 'ALL' ? 'All statuses' : label(value)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <Card>
        <CardContent className="p-0">
          <table className="w-full text-sm">
            <thead className="border-b bg-gray-50">
              <tr>
                {['Job', 'Operation', 'Status', 'Target', 'Attempts', 'Next run', 'Last error', ''].map((header) => (
                  <th key={header} className="px-4 py-3 text-left font-medium text-muted-foreground text-xs">
                    {header}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr><td colSpan={8} className="py-10 text-center text-muted-foreground">Loading...</td></tr>
              ) : jobs.length === 0 ? (
                <tr><td colSpan={8} className="py-10 text-center text-muted-foreground">No scheduled retries found</td></tr>
              ) : jobs.map((job) => (
                <tr key={job.id} className="border-b last:border-0 hover:bg-gray-50">
                  <td className="px-4 py-3 font-mono text-xs">{shortId(job.id)}</td>
                  <td className="px-4 py-3">{label(job.operation)}</td>
                  <td className="px-4 py-3">
                    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${STATUS_STYLE[job.status] ?? 'bg-gray-100 text-gray-600'}`}>
                      {label(job.status)}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <div className="space-y-1">
                      {job.paymentIntentId && <div className="font-mono text-xs">pi {shortId(job.paymentIntentId)}</div>}
                      {job.refundId && <div className="font-mono text-xs">re {shortId(job.refundId)}</div>}
                    </div>
                  </td>
                  <td className="px-4 py-3">{job.attemptCount} / {job.maxAttempts}</td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">{formatDate(job.nextRunAt)}</td>
                  <td className="px-4 py-3 max-w-72">
                    <div className="truncate text-xs" title={job.lastErrorMessage ?? job.retryReason ?? ''}>
                      {job.lastErrorCode ? `${job.lastErrorCode}: ` : ''}
                      {job.lastErrorMessage ?? job.retryReason ?? '-'}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-right">
                    {job.status === 'SCHEDULED' && (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={cancelMutation.isPending}
                        onClick={() => cancelMutation.mutate(job.id)}
                      >
                        Cancel
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      <div className="text-sm text-muted-foreground">{jobs.length} retry job{jobs.length === 1 ? '' : 's'}</div>
    </div>
  );
}

function label(value: string) {
  return value.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (char) => char.toUpperCase());
}

function shortId(value: string) {
  return `${value.slice(0, 12)}...`;
}

function formatDate(value: string | null) {
  if (!value) return '-';
  return format(new Date(value), 'MMM d, yyyy HH:mm');
}
