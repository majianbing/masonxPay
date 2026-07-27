'use client';

import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { RefreshCcw, Scale } from 'lucide-react';
import { apiFetch } from '@/lib/api';
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

interface CardProgram {
  programId: string;
  name: string;
  currency: string;
  status: string;
}

interface SettlementReport {
  reportId: string;
  programId: string;
  issuerPartnerId: string;
  reportRef: string;
  settlementDate: string;
  currency: string;
  totalAmount: number | string;
  matchedAmount: number | string;
  exceptionAmount: number | string;
  lineCount: number;
  exceptionCount: number;
  status: string;
  createdAt: string;
}

interface SettlementSummary {
  programId: string;
  settlementDate: string;
  currency: string;
  issuerReportedAmount: number | string;
  issuerMatchedAmount: number | string;
  exceptionAmount: number | string;
  ledgerPostedAmount: number | string;
  ledgerDeltaAmount: number | string;
  reportCount: number;
  lineCount: number;
  exceptionCount: number;
}

export default function IssuingSettlementPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const [page, setPage] = useState(0);
  const [programId, setProgramId] = useState('');
  const [settlementDate, setSettlementDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [currency, setCurrency] = useState('USD');

  const programsQuery = useQuery<PageResponse<CardProgram>>({
    queryKey: ['card-programs', activeMerchantId, mode],
    enabled: !!activeMerchantId,
    queryFn: () => apiFetch(`/api/v1/merchants/${activeMerchantId}/va/card-programs?mode=${mode}&page=0&size=100`),
  });

  const programs = programsQuery.data?.content ?? [];
  const activePrograms = useMemo(() => programs.filter((program) => program.status === 'ACTIVE'), [programs]);
  const selectedProgram = activePrograms.find((program) => program.programId === programId) ?? activePrograms[0];
  const selectedProgramId = programId || selectedProgram?.programId || '';
  const selectedCurrency = currency || selectedProgram?.currency || 'USD';

  const reportsQuery = useQuery<PageResponse<SettlementReport>>({
    queryKey: ['card-settlement-reports', activeMerchantId, mode, selectedProgramId, page],
    enabled: !!activeMerchantId && !!selectedProgramId,
    queryFn: () => apiFetch(
      `/api/v1/merchants/${activeMerchantId}/va/card-programs/${selectedProgramId}/settlement-reports?mode=${mode}&page=${page}&size=20`,
    ),
  });

  const summaryQuery = useQuery<SettlementSummary>({
    queryKey: ['card-settlement-summary', activeMerchantId, mode, selectedProgramId, settlementDate, selectedCurrency],
    enabled: !!activeMerchantId && !!selectedProgramId && !!settlementDate && !!selectedCurrency,
    queryFn: () => apiFetch(
      `/api/v1/merchants/${activeMerchantId}/va/card-programs/${selectedProgramId}/settlement-reconciliation-summary`
      + `?mode=${mode}&settlementDate=${settlementDate}&currency=${selectedCurrency}`,
    ),
  });

  if (!activeMerchantId) {
    return (
      <div className="space-y-4">
        <PageHeader refreshing={false} onRefresh={() => undefined} />
        <section className="rounded-md border bg-white px-4 py-10 text-center text-sm text-muted-foreground">
          Select a merchant to review prepaid card settlement.
        </section>
      </div>
    );
  }

  const reports = reportsQuery.data?.content ?? [];

  return (
    <div className="space-y-5">
      <PageHeader
        refreshing={programsQuery.isFetching || reportsQuery.isFetching || summaryQuery.isFetching}
        onRefresh={() => {
          programsQuery.refetch();
          reportsQuery.refetch();
          summaryQuery.refetch();
        }}
      />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm">Reconciliation Summary</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-3 md:grid-cols-3">
            <div className="space-y-1.5">
              <Label>Card Program</Label>
              <Select
                value={selectedProgramId}
                onValueChange={(v) => {
                  setProgramId(v ?? '');
                  setPage(0);
                  const program = activePrograms.find((item) => item.programId === v);
                  if (program) setCurrency(program.currency);
                }}
              >
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
              <Label>Settlement Date</Label>
              <Input type="date" value={settlementDate} onChange={(e) => setSettlementDate(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label>Currency</Label>
              <Input value={selectedCurrency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} maxLength={3} />
            </div>
          </div>

          {!selectedProgramId ? (
            <div className="rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
              Create an active card program before reviewing settlement reports.
            </div>
          ) : summaryQuery.isLoading ? (
            <div className="rounded-md border px-4 py-8 text-center text-sm text-muted-foreground">Loading summary...</div>
          ) : (
            <div className="grid gap-3 md:grid-cols-4">
              <Metric label="Issuer reported" value={formatAmount(summaryQuery.data?.issuerReportedAmount, selectedCurrency)} />
              <Metric label="Matched clearing" value={formatAmount(summaryQuery.data?.issuerMatchedAmount, selectedCurrency)} />
              <Metric label="Ledger posted" value={formatAmount(summaryQuery.data?.ledgerPostedAmount, selectedCurrency)} />
              <Metric label="Ledger delta" value={formatAmount(summaryQuery.data?.ledgerDeltaAmount, selectedCurrency)} tone={Number(summaryQuery.data?.ledgerDeltaAmount ?? 0) === 0 ? 'normal' : 'warn'} />
              <Metric label="Reports" value={String(summaryQuery.data?.reportCount ?? 0)} />
              <Metric label="Lines" value={String(summaryQuery.data?.lineCount ?? 0)} />
              <Metric label="Exceptions" value={String(summaryQuery.data?.exceptionCount ?? 0)} tone={(summaryQuery.data?.exceptionCount ?? 0) === 0 ? 'normal' : 'warn'} />
              <Metric label="Exception amount" value={formatAmount(summaryQuery.data?.exceptionAmount, selectedCurrency)} tone={Number(summaryQuery.data?.exceptionAmount ?? 0) === 0 ? 'normal' : 'warn'} />
            </div>
          )}
        </CardContent>
      </Card>

      <section className="rounded-md border bg-white">
        <div className="flex items-center justify-between border-b px-4 py-3">
          <div>
            <h2 className="text-sm font-semibold">Settlement Reports</h2>
            <p className="text-xs text-muted-foreground">Issuer report batches for the selected prepaid card program</p>
          </div>
          <Badge variant="outline">{reportsQuery.data?.totalElements ?? 0} total</Badge>
        </div>
        {reportsQuery.isLoading ? (
          <div className="px-4 py-10 text-center text-sm text-muted-foreground">Loading reports...</div>
        ) : reports.length === 0 ? (
          <div className="px-4 py-10 text-center text-sm text-muted-foreground">
            No issuer settlement reports have been ingested for this program.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-left text-xs uppercase text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">Report</th>
                  <th className="px-4 py-3 font-medium">Date</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                  <th className="px-4 py-3 font-medium">Reported</th>
                  <th className="px-4 py-3 font-medium">Matched</th>
                  <th className="px-4 py-3 font-medium">Exceptions</th>
                  <th className="px-4 py-3 font-medium">Lines</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {reports.map((report) => (
                  <tr key={report.reportId} className="hover:bg-gray-50">
                    <td className="px-4 py-3">
                      <div className="font-medium">{report.reportRef}</div>
                      <div className="text-xs text-muted-foreground">{formatDateTime(report.createdAt)}</div>
                    </td>
                    <td className="px-4 py-3">{report.settlementDate}</td>
                    <td className="px-4 py-3"><StatusBadge status={report.status} /></td>
                    <td className="px-4 py-3">{formatAmount(report.totalAmount, report.currency)}</td>
                    <td className="px-4 py-3">{formatAmount(report.matchedAmount, report.currency)}</td>
                    <td className="px-4 py-3">{formatAmount(report.exceptionAmount, report.currency)}</td>
                    <td className="px-4 py-3">{report.lineCount} lines; {report.exceptionCount} exceptions</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="flex items-center justify-between border-t px-4 py-3 text-sm">
          <span className="text-muted-foreground">
            Page {page + 1} of {Math.max(reportsQuery.data?.totalPages ?? 1, 1)}
          </span>
          <div className="flex gap-2">
            <Button variant="outline" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
              Previous
            </Button>
            <Button
              variant="outline"
              disabled={page + 1 >= (reportsQuery.data?.totalPages ?? 1)}
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
          <Scale className="size-5 text-primary" />
          <h1 className="text-2xl font-semibold tracking-normal">Settlement</h1>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">Review issuer report totals, clearing matches, and ledger reconciliation deltas.</p>
      </div>
      <Button variant="outline" onClick={onRefresh} disabled={refreshing}>
        <RefreshCcw className="mr-2 size-4" />
        Refresh
      </Button>
    </div>
  );
}

function Metric({ label, value, tone = 'normal' }: { label: string; value: string; tone?: 'normal' | 'warn' }) {
  return (
    <div className={`rounded-md border px-3 py-3 ${tone === 'warn' ? 'border-amber-200 bg-amber-50' : 'bg-white'}`}>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-1 text-lg font-semibold">{value}</div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const variant = status === 'MATCHED' || status === 'RECONCILED' ? 'default' : status === 'EXCEPTION' ? 'destructive' : 'secondary';
  return <Badge variant={variant}>{status}</Badge>;
}

function programLabel(program?: CardProgram) {
  return program ? `${program.name} (${program.currency})` : null;
}

function formatAmount(value: number | string | null | undefined, currency: string) {
  const n = typeof value === 'number' ? value : Number(value ?? 0);
  return `${Number.isFinite(n) ? n.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }) : '0.00'} ${currency}`;
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}
