'use client';

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import {
  AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
} from 'recharts';
import { format, subDays } from 'date-fns';

interface PaymentIntent {
  id: string;
  amount: number;
  currency: string;
  status: string;
  createdAt: string;
}

interface PageResponse {
  content: PaymentIntent[];
  totalElements: number;
}

export default function OverviewPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const accessToken = useAuthStore((s) => s.accessToken);
  const mode = useAuthStore((s) => s.mode);

  const { data } = useQuery<PageResponse>({
    queryKey: ['payment-intents', activeMerchantId, 'overview', mode],
    queryFn: () =>
      apiFetch<PageResponse>(`/api/v1/merchants/${activeMerchantId}/payment-intents?size=200&mode=${mode}`),
    enabled: !!accessToken && !!activeMerchantId,
  });

  const payments = data?.content ?? [];

  const totalVolume = payments
    .filter((p) => p.status === 'SUCCEEDED')
    .reduce((sum, p) => sum + p.amount, 0);

  const successRate = payments.length
    ? Math.round((payments.filter((p) => p.status === 'SUCCEEDED').length / payments.length) * 100)
    : 0;

  // Build daily chart data for last 7 days
  const chartData = Array.from({ length: 7 }, (_, i) => {
    const day = subDays(new Date(), 6 - i);
    const label = format(day, 'MMM d');
    const dayStr = format(day, 'yyyy-MM-dd');
    const volume = payments
      .filter(
        (p) =>
          p.status === 'SUCCEEDED' &&
          p.createdAt.startsWith(dayStr),
      )
      .reduce((sum, p) => sum + p.amount / 100, 0);
    return { day: label, volume };
  });

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Overview</h1>

      <div className="grid grid-cols-3 gap-4">
        <MetricCard title="Total Volume (USD)" value={`$${(totalVolume / 100).toFixed(2)}`} />
        <MetricCard title="Total Payments" value={String(payments.length)} />
        <MetricCard title="Success Rate" value={`${successRate}%`} />
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Revenue — Last 7 Days</CardTitle>
        </CardHeader>
        <CardContent>
          <ResponsiveContainer width="100%" height={220}>
            <AreaChart data={chartData} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
              <XAxis dataKey="day" tick={{ fontSize: 12 }} />
              <YAxis tick={{ fontSize: 12 }} tickFormatter={(v) => `$${v}`} />
              <Tooltip formatter={(v) => [`$${Number(v).toFixed(2)}`, 'Volume']} />
              <Area
                type="monotone"
                dataKey="volume"
                stroke="#6366f1"
                fill="#ede9fe"
                strokeWidth={2}
              />
            </AreaChart>
          </ResponsiveContainer>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-sm font-medium">Recent Payments</CardTitle></CardHeader>
        <CardContent>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-muted-foreground text-left">
                <th className="pb-2">ID</th>
                <th className="pb-2">Amount</th>
                <th className="pb-2">Status</th>
                <th className="pb-2">Date</th>
              </tr>
            </thead>
            <tbody>
              {payments.slice(0, 10).map((p) => (
                <tr key={p.id} className="border-b last:border-0">
                  <td className="py-2 font-mono text-xs">{p.id.slice(0, 8)}…</td>
                  <td className="py-2">{(p.amount / 100).toFixed(2)} {p.currency}</td>
                  <td className="py-2"><StatusBadge status={p.status} /></td>
                  <td className="py-2 text-muted-foreground">{format(new Date(p.createdAt), 'MMM d, HH:mm')}</td>
                </tr>
              ))}
              {payments.length === 0 && (
                <tr><td colSpan={4} className="py-6 text-center text-muted-foreground">No payments yet</td></tr>
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}

function MetricCard({ title, value }: { title: string; value: string }) {
  return (
    <Card>
      <CardContent className="pt-6">
        <p className="text-xs text-muted-foreground">{title}</p>
        <p className="text-2xl font-bold mt-1">{value}</p>
      </CardContent>
    </Card>
  );
}

function StatusBadge({ status }: { status: string }) {
  const color: Record<string, string> = {
    SUCCEEDED: 'bg-green-100 text-green-700',
    FAILED: 'bg-red-100 text-red-700',
    CANCELED: 'bg-gray-100 text-gray-600',
    PROCESSING: 'bg-blue-100 text-blue-700',
  };
  return (
    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${color[status] ?? 'bg-gray-100 text-gray-600'}`}>
      {status}
    </span>
  );
}
