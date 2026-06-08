'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  AreaChart, Area, BarChart, Bar,
  XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, Cell,
} from 'recharts';
import { format, subDays, parseISO } from 'date-fns';
import {
  BarChart2, Plug, Zap, TrendingUp, CreditCard, CheckCircle2, XCircle, ArrowRight,
} from 'lucide-react';
import Link from 'next/link';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { buttonVariants } from '@/components/ui/button';
import { cn } from '@/lib/utils';

// ─── Types ────────────────────────────────────────────────────────────────────

interface AnalyticsSummary {
  totalVolumeCents: number;
  totalCount: number;
  succeededCount: number;
  failedCount: number;
  conversionRate: number;
}

interface BreakdownItem {
  key: string;
  count: number;
  volumeCents: number;
}

interface TimeSeriesPoint {
  date: string;
  volumeCents: number;
  count: number;
}

interface AnalyticsResponse {
  summary: AnalyticsSummary;
  breakdown: BreakdownItem[];
  timeSeries: TimeSeriesPoint[];
}

// ─── Constants ────────────────────────────────────────────────────────────────

const PRESETS = [
  { label: '7D', days: 7 },
  { label: '30D', days: 30 },
  { label: '90D', days: 90 },
] as const;

const GROUP_BY_OPTIONS = [
  { value: 'status', label: 'Status' },
  { value: 'connector', label: 'Connector' },
  { value: 'currency', label: 'Currency' },
] as const;

const STATUS_COLORS: Record<string, string> = {
  SUCCEEDED: '#22c55e',
  FAILED: '#ef4444',
  CANCELED: '#94a3b8',
  PROCESSING: '#3b82f6',
  REQUIRES_ACTION: '#f59e0b',
  REQUIRES_CAPTURE: '#8b5cf6',
  REQUIRES_CONFIRMATION: '#64748b',
  REQUIRES_PAYMENT_METHOD: '#64748b',
};

const CONNECTOR_COLORS: Record<string, string> = {
  STRIPE: '#635BFF',
  SQUARE: '#00D632',
  BRAINTREE: '#009CDE',
  MOLLIE: '#0069FF',
  SIMULATOR: '#6366f1',
  Unknown: '#94a3b8',
};

function barColor(key: string, groupBy: string): string {
  if (groupBy === 'status') return STATUS_COLORS[key] ?? '#6366f1';
  if (groupBy === 'connector') return CONNECTOR_COLORS[key] ?? '#6366f1';
  return '#6366f1';
}

function fmt(cents: number) {
  return `$${(cents / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

// ─── Empty state ──────────────────────────────────────────────────────────────

function EmptyState() {
  return (
    <div className="flex flex-col items-center justify-center py-24 px-4 text-center">
      <div className="rounded-2xl bg-primary/8 p-5 mb-6">
        <BarChart2 className="size-12 text-primary/60" />
      </div>
      <h2 className="text-xl font-semibold text-foreground mb-2">No payment data yet</h2>
      <p className="text-sm text-muted-foreground max-w-sm mb-8">
        Analytics appear once you process your first payment. Connect a payment provider and make a test transaction to get started.
      </p>
      <div className="flex flex-col sm:flex-row gap-3">
        <Link href="/connectors" className={cn(buttonVariants())}>
          <Plug className="size-4 mr-2" />
          Connect a provider
        </Link>
        <Link href="/developers/quickstart" className={cn(buttonVariants({ variant: 'outline' }))}>
          <Zap className="size-4 mr-2" />
          View quickstart
        </Link>
      </div>

      {/* Ghost preview of the charts so they understand what they'll see */}
      <div className="mt-14 w-full max-w-3xl opacity-25 pointer-events-none select-none" aria-hidden>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-6">
          {['Volume', 'Payments', 'Success rate', 'Failed'].map((t) => (
            <div key={t} className="rounded-lg border bg-card p-4">
              <p className="text-xs text-muted-foreground mb-2">{t}</p>
              <div className="h-6 w-20 rounded bg-muted" />
            </div>
          ))}
        </div>
        <div className="rounded-lg border bg-card h-40" />
      </div>
    </div>
  );
}

// ─── No data in range ─────────────────────────────────────────────────────────

function NoDataInRange({ onReset }: { onReset: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <TrendingUp className="size-10 text-muted-foreground/40 mb-4" />
      <p className="text-sm font-medium text-foreground mb-1">No payments in this period</p>
      <p className="text-xs text-muted-foreground mb-4">Try a wider date range to see your data.</p>
      <button
        onClick={onReset}
        className={cn(buttonVariants({ variant: 'outline', size: 'sm' }))}
      >
        View last 30 days
      </button>
    </div>
  );
}

// ─── Summary cards ────────────────────────────────────────────────────────────

function SummaryCards({ summary }: { summary: AnalyticsSummary }) {
  const cards = [
    {
      label: 'Total volume',
      value: fmt(summary.totalVolumeCents),
      icon: TrendingUp,
      color: 'text-indigo-500',
      bg: 'bg-indigo-50',
    },
    {
      label: 'Total payments',
      value: summary.totalCount.toLocaleString(),
      icon: CreditCard,
      color: 'text-blue-500',
      bg: 'bg-blue-50',
    },
    {
      label: 'Success rate',
      value: `${(summary.conversionRate * 100).toFixed(1)}%`,
      icon: CheckCircle2,
      color: 'text-green-500',
      bg: 'bg-green-50',
    },
    {
      label: 'Failed payments',
      value: summary.failedCount.toLocaleString(),
      icon: XCircle,
      color: 'text-red-500',
      bg: 'bg-red-50',
    },
  ];

  return (
    <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
      {cards.map(({ label, value, icon: Icon, color, bg }) => (
        <Card key={label}>
          <CardContent className="pt-5 pb-4">
            <div className={cn('inline-flex rounded-lg p-2 mb-3', bg)}>
              <Icon className={cn('size-4', color)} />
            </div>
            <p className="text-xs text-muted-foreground mb-1">{label}</p>
            <p className="text-2xl font-bold tracking-tight">{value}</p>
          </CardContent>
        </Card>
      ))}
    </div>
  );
}

// ─── Custom tooltip ───────────────────────────────────────────────────────────

function ChartTooltip({ active, payload, label }: {
  active?: boolean; payload?: Array<{ value: number; name: string }>; label?: string;
}) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-lg border bg-white shadow-md px-3 py-2 text-sm">
      <p className="font-medium text-foreground mb-1">{label}</p>
      {payload.map((p) => (
        <p key={p.name} className="text-muted-foreground">
          {p.name === 'volumeCents' ? fmt(p.value) : `${p.value.toLocaleString()} payments`}
        </p>
      ))}
    </div>
  );
}

// ─── Main page ────────────────────────────────────────────────────────────────

export default function AnalyticsPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);

  const [preset, setPreset] = useState<7 | 30 | 90>(30);
  const [groupBy, setGroupBy] = useState<'status' | 'connector' | 'currency'>('status');

  const toDate = format(new Date(), 'yyyy-MM-dd');
  const fromDate = format(subDays(new Date(), preset - 1), 'yyyy-MM-dd');

  const { data, isLoading } = useQuery<AnalyticsResponse>({
    queryKey: ['analytics', activeMerchantId, mode, fromDate, toDate, groupBy],
    queryFn: () =>
      apiFetch<AnalyticsResponse>(
        `/api/v1/merchants/${activeMerchantId}/analytics?mode=${mode}&from=${fromDate}&to=${toDate}&groupBy=${groupBy}`
      ),
    enabled: !!activeMerchantId,
  });

  const hasAnyData = (data?.summary.totalCount ?? 0) > 0;
  const hasDataInRange = hasAnyData;

  const chartSeries = (data?.timeSeries ?? []).map((p) => ({
    ...p,
    day: format(parseISO(p.date), preset === 7 ? 'EEE' : 'MMM d'),
  }));

  if (isLoading) {
    return (
      <div className="space-y-6 animate-pulse">
        <div className="flex justify-between">
          <div className="h-8 w-32 rounded bg-muted" />
          <div className="h-8 w-48 rounded bg-muted" />
        </div>
        <div className="grid grid-cols-4 gap-4">
          {[...Array(4)].map((_, i) => (
            <div key={i} className="h-28 rounded-lg border bg-muted" />
          ))}
        </div>
        <div className="h-56 rounded-lg border bg-muted" />
        <div className="h-48 rounded-lg border bg-muted" />
      </div>
    );
  }

  // First-ever empty state (no data at all)
  if (!isLoading && !hasAnyData && !data?.timeSeries.some((p) => p.count > 0)) {
    return (
      <div className="space-y-4">
        <PageHeader
          preset={preset} setPreset={setPreset}
          groupBy={groupBy} setGroupBy={setGroupBy}
        />
        <EmptyState />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        preset={preset} setPreset={setPreset}
        groupBy={groupBy} setGroupBy={setGroupBy}
      />

      {/* Summary cards — always show */}
      <SummaryCards summary={data!.summary} />

      {/* Revenue over time */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-medium">Revenue over time (succeeded)</CardTitle>
        </CardHeader>
        <CardContent>
          {!hasDataInRange ? (
            <NoDataInRange onReset={() => setPreset(30)} />
          ) : (
            <ResponsiveContainer width="100%" height={220}>
              <AreaChart data={chartSeries} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <defs>
                  <linearGradient id="areaGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#6366f1" stopOpacity={0.18} />
                    <stop offset="95%" stopColor="#6366f1" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
                <XAxis dataKey="day" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                <YAxis
                  tick={{ fontSize: 11 }}
                  tickLine={false}
                  axisLine={false}
                  tickFormatter={(v) => `$${(v / 100).toFixed(0)}`}
                  width={55}
                />
                <Tooltip content={<ChartTooltip />} />
                <Area
                  type="monotone"
                  dataKey="volumeCents"
                  name="volumeCents"
                  stroke="#6366f1"
                  fill="url(#areaGrad)"
                  strokeWidth={2}
                  dot={false}
                  activeDot={{ r: 4, fill: '#6366f1' }}
                />
              </AreaChart>
            </ResponsiveContainer>
          )}
        </CardContent>
      </Card>

      {/* Breakdown bar chart */}
      <Card>
        <CardHeader className="pb-2">
          <div className="flex items-center justify-between">
            <CardTitle className="text-sm font-medium">
              Breakdown by {GROUP_BY_OPTIONS.find((o) => o.value === groupBy)?.label.toLowerCase()}
            </CardTitle>
            {data?.breakdown && data.breakdown.length > 0 && (
              <Link
                href="/payments"
                className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-1 transition-colors"
              >
                View payments <ArrowRight className="size-3" />
              </Link>
            )}
          </div>
        </CardHeader>
        <CardContent>
          {!data?.breakdown.length ? (
            <NoDataInRange onReset={() => setPreset(30)} />
          ) : (
            <div className="flex gap-8">
              <ResponsiveContainer width="100%" height={200}>
                <BarChart data={data.breakdown} layout="vertical" margin={{ top: 0, right: 16, left: 0, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#f1f5f9" />
                  <XAxis
                    type="number"
                    tick={{ fontSize: 11 }}
                    tickLine={false}
                    axisLine={false}
                    tickFormatter={(v) => v.toLocaleString()}
                  />
                  <YAxis
                    type="category"
                    dataKey="key"
                    tick={{ fontSize: 11 }}
                    tickLine={false}
                    axisLine={false}
                    width={110}
                  />
                  <Tooltip
                    formatter={(v, name) =>
                      name === 'volumeCents'
                        ? [fmt(Number(v)), 'Volume']
                        : [Number(v).toLocaleString(), 'Payments']
                    }
                  />
                  <Bar dataKey="count" name="count" radius={[0, 4, 4, 0]}>
                    {data.breakdown.map((entry) => (
                      <Cell key={entry.key} fill={barColor(entry.key, groupBy)} fillOpacity={0.85} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>

              {/* Legend table */}
              <div className="shrink-0 w-52 space-y-2 text-sm py-1">
                {data.breakdown.slice(0, 8).map((item) => (
                  <div key={item.key} className="flex items-center justify-between gap-2">
                    <div className="flex items-center gap-2 min-w-0">
                      <span
                        className="size-2.5 rounded-full shrink-0"
                        style={{ background: barColor(item.key, groupBy) }}
                      />
                      <span className="text-xs truncate text-foreground">{item.key}</span>
                    </div>
                    <div className="text-right shrink-0">
                      <span className="text-xs font-medium">{item.count.toLocaleString()}</span>
                      <span className="text-xs text-muted-foreground block">{fmt(item.volumeCents)}</span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

// ─── Page header with controls ────────────────────────────────────────────────

function PageHeader({
  preset, setPreset, groupBy, setGroupBy,
}: {
  preset: 7 | 30 | 90;
  setPreset: (v: 7 | 30 | 90) => void;
  groupBy: 'status' | 'connector' | 'currency';
  setGroupBy: (v: 'status' | 'connector' | 'currency') => void;
}) {
  return (
    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
      <h1 className="text-2xl font-semibold">Analytics</h1>

      <div className="flex items-center gap-3 flex-wrap">
        {/* Date preset toggle */}
        <div className="flex rounded-md border overflow-hidden text-sm">
          {PRESETS.map(({ label, days }) => (
            <button
              key={label}
              onClick={() => setPreset(days as 7 | 30 | 90)}
              className={cn(
                'px-3 py-1.5 transition-colors',
                preset === days
                  ? 'bg-primary text-primary-foreground font-medium'
                  : 'text-muted-foreground hover:bg-muted',
              )}
            >
              {label}
            </button>
          ))}
        </div>

        {/* Group by selector */}
        <div className="flex rounded-md border overflow-hidden text-sm">
          {GROUP_BY_OPTIONS.map(({ value, label }) => (
            <button
              key={value}
              onClick={() => setGroupBy(value as 'status' | 'connector' | 'currency')}
              className={cn(
                'px-3 py-1.5 transition-colors',
                groupBy === value
                  ? 'bg-primary text-primary-foreground font-medium'
                  : 'text-muted-foreground hover:bg-muted',
              )}
            >
              {label}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
