'use client';

import { FormEvent, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CircleDollarSign, Play, Plus, RefreshCcw, Upload } from 'lucide-react';
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

interface CardProgram {
  programId: string;
  name: string;
  currency: string;
  status: string;
}

interface FeeSchedule {
  scheduleId: string;
  merchantId: string;
  mode: string;
  programId: string | null;
  bin: string | null;
  channel: string | null;
  name: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

interface FeeScheduleVersion {
  scheduleId: string;
  merchantId: string;
  mode: string;
  version: number;
  status: string;
  feeCurrency: string;
  feeScale: number;
  roundingMode: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  rulesJson: string;
  metadataJson: string;
  createdAt: string;
  publishedAt: string | null;
}

interface FeeAssessment {
  scheduleId: string;
  scheduleVersion: number;
  matchedRules: Array<{ ruleId: string; ruleVersion: number; ruleName: string }>;
  lines: FeeLine[];
  visibleTotals: Record<string, number | string>;
  hiddenTotals: Record<string, number | string>;
}

interface FeeLine {
  ruleId: string;
  ruleVersion: number;
  ruleName: string;
  componentId: string;
  name: string;
  visibility: string;
  currency: string;
  basisAmount: number | string | null;
  rawCalculatedAmount: number | string;
  amount: number | string;
  roundingMode: string;
  roundingScale: number;
}

interface FeeAssessmentSnapshot {
  assessment: {
    assessmentId: string;
    eventType: string;
    eventId: string;
    programId: string | null;
    cardId: string | null;
    scheduleId: string;
    scheduleVersion: number;
    visibleTotalsJson: string;
    hiddenTotalsJson: string;
    createdAt: string;
  };
  lines: Array<{
    lineId: number;
    ruleName: string;
    name: string;
    visibility: string;
    currency: string;
    amount: number | string;
  }>;
}

const ALL_PROGRAMS = '__all_programs__';
const DEFAULT_RULES = `[
  {
    "ruleId": "rule_card_create",
    "ruleVersion": 1,
    "name": "card_create_fixed",
    "priority": 100,
    "matchExpression": "eventType == 'CARD_CREATE'",
    "components": [
      {
        "componentId": "fixed_card_create",
        "name": "card_create_fee",
        "type": "FIXED",
        "amount": "1.00",
        "currency": "USD",
        "visibility": "MERCHANT_VISIBLE"
      }
    ],
    "visibility": "MERCHANT_VISIBLE",
    "stopProcessing": false,
    "metadata": {}
  }
]`;
const DEFAULT_CONTEXT = `{
  "eventType": "CARD_CREATE",
  "eventId": "preview_card_create",
  "cardCurrency": "USD",
  "accountCurrency": "USD",
  "channel": "VIRTUAL"
}`;

export default function IssuingFeesPage() {
  const queryClient = useQueryClient();
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const [schedulePage, setSchedulePage] = useState(0);
  const [assessmentPage, setAssessmentPage] = useState(0);
  const [scheduleName, setScheduleName] = useState('Card creation fees');
  const [programId, setProgramId] = useState(ALL_PROGRAMS);
  const [bin, setBin] = useState('');
  const [channel, setChannel] = useState('VIRTUAL');
  const [selectedScheduleId, setSelectedScheduleId] = useState('');
  const [feeCurrency, setFeeCurrency] = useState('USD');
  const [feeScale, setFeeScale] = useState(2);
  const [rulesJson, setRulesJson] = useState(DEFAULT_RULES);
  const [contextJson, setContextJson] = useState(DEFAULT_CONTEXT);
  const [previewResult, setPreviewResult] = useState<FeeAssessment | null>(null);

  const programsQuery = useQuery<PageResponse<CardProgram>>({
    queryKey: ['card-programs', activeMerchantId, mode],
    enabled: !!activeMerchantId,
    queryFn: () => apiFetch(`/api/v1/merchants/${activeMerchantId}/va/card-programs?mode=${mode}&page=0&size=100`),
  });

  const schedulesQuery = useQuery<PageResponse<FeeSchedule>>({
    queryKey: ['prepaid-fee-schedules', activeMerchantId, mode, schedulePage],
    enabled: !!activeMerchantId,
    queryFn: () => apiFetch(
      `/api/v1/merchants/${activeMerchantId}/va/prepaid-fees/schedules?mode=${mode}&page=${schedulePage}&size=20`,
    ),
  });

  const schedules = schedulesQuery.data?.content ?? [];
  const selectedSchedule = schedules.find((schedule) => schedule.scheduleId === selectedScheduleId) ?? schedules[0];
  const effectiveScheduleId = selectedScheduleId || selectedSchedule?.scheduleId || '';

  const versionsQuery = useQuery<FeeScheduleVersion[]>({
    queryKey: ['prepaid-fee-schedule-versions', activeMerchantId, mode, effectiveScheduleId],
    enabled: !!activeMerchantId && !!effectiveScheduleId,
    queryFn: () => apiFetch(
      `/api/v1/merchants/${activeMerchantId}/va/prepaid-fees/schedules/${effectiveScheduleId}/versions?mode=${mode}`,
    ),
  });

  const assessmentsQuery = useQuery<PageResponse<FeeAssessmentSnapshot>>({
    queryKey: ['prepaid-fee-assessments', activeMerchantId, mode, assessmentPage],
    enabled: !!activeMerchantId,
    queryFn: () => apiFetch(
      `/api/v1/merchants/${activeMerchantId}/va/prepaid-fees/assessments?mode=${mode}&page=${assessmentPage}&size=20`,
    ),
  });

  const programs = programsQuery.data?.content ?? [];
  const activePrograms = useMemo(() => programs.filter((program) => program.status === 'ACTIVE'), [programs]);
  const latestVersion = versionsQuery.data?.[0];
  const nextVersion = (latestVersion?.version ?? 0) + 1;
  const liveMutationLocked = mode === 'LIVE';

  const createSchedule = useMutation({
    mutationFn: () => apiFetch<FeeSchedule>(`/api/v1/merchants/${activeMerchantId}/va/prepaid-fees/schedules`, {
      method: 'POST',
      body: JSON.stringify({
        mode,
        name: scheduleName,
        status: 'ACTIVE',
        programId: programId === ALL_PROGRAMS ? null : programId,
        bin: bin.trim() || null,
        channel: channel.trim() || null,
      }),
    }),
    onSuccess: (schedule) => {
      setSelectedScheduleId(schedule.scheduleId);
      queryClient.invalidateQueries({ queryKey: ['prepaid-fee-schedules', activeMerchantId, mode] });
      toast.success('Fee schedule created');
    },
    onError: (error) => toast.error(errorMessage(error, 'Could not create fee schedule')),
  });

  const previewFee = useMutation({
    mutationFn: () => apiFetch<FeeAssessment>(`/api/v1/merchants/${activeMerchantId}/va/prepaid-fees/preview`, {
      method: 'POST',
      body: JSON.stringify({
        mode,
        scheduleId: effectiveScheduleId || 'preview',
        version: nextVersion,
        feeCurrency,
        feeScale,
        roundingMode: 'HALF_UP',
        rules: parseJson(rulesJson),
        metadata: {},
        context: parseJson(contextJson),
      }),
    }),
    onSuccess: (result) => {
      setPreviewResult(result);
      toast.success('Fee preview calculated');
    },
    onError: (error) => toast.error(errorMessage(error, 'Could not preview fee rules')),
  });

  const publishVersion = useMutation({
    mutationFn: () => apiFetch<FeeScheduleVersion>(
      `/api/v1/merchants/${activeMerchantId}/va/prepaid-fees/schedules/${effectiveScheduleId}/versions`,
      {
        method: 'POST',
        body: JSON.stringify({
          mode,
          version: nextVersion,
          status: 'ACTIVE',
          feeCurrency,
          feeScale,
          roundingMode: 'HALF_UP',
          effectiveFrom: new Date().toISOString(),
          rules: parseJson(rulesJson),
          metadata: {},
        }),
      },
    ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['prepaid-fee-schedule-versions', activeMerchantId, mode, effectiveScheduleId] });
      toast.success('Fee schedule version published');
    },
    onError: (error) => toast.error(errorMessage(error, 'Could not publish fee schedule version')),
  });

  function submitSchedule(e: FormEvent) {
    e.preventDefault();
    if (!activeMerchantId || !scheduleName.trim()) return;
    createSchedule.mutate();
  }

  function submitPreview(e: FormEvent) {
    e.preventDefault();
    previewFee.mutate();
  }

  if (!activeMerchantId) {
    return (
      <div className="space-y-4">
        <PageHeader refreshing={false} onRefresh={() => undefined} />
        <section className="rounded-md border bg-white px-4 py-10 text-center text-sm text-muted-foreground">
          Select a merchant to manage issuing fees.
        </section>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <PageHeader
        refreshing={schedulesQuery.isFetching || versionsQuery.isFetching || assessmentsQuery.isFetching}
        onRefresh={() => {
          programsQuery.refetch();
          schedulesQuery.refetch();
          versionsQuery.refetch();
          assessmentsQuery.refetch();
        }}
      />

      <div className="grid gap-4 xl:grid-cols-[0.9fr_1.3fr]">
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Fee Schedule</CardTitle>
          </CardHeader>
          <CardContent>
            {liveMutationLocked && (
              <div className="mb-3 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900">
                LIVE fee activation is restricted to platform admin/compliance workflow.
              </div>
            )}
            <form className="space-y-3" onSubmit={submitSchedule}>
              <div className="space-y-1.5">
                <Label>Name</Label>
                <Input value={scheduleName} onChange={(e) => setScheduleName(e.target.value)} />
              </div>
              <div className="space-y-1.5">
                <Label>Card Program Scope</Label>
                <Select value={programId} onValueChange={(v) => setProgramId(v ?? ALL_PROGRAMS)}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={ALL_PROGRAMS}>All active programs</SelectItem>
                    {activePrograms.map((program) => (
                      <SelectItem key={program.programId} value={program.programId}>
                        {programLabel(program)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-3 md:grid-cols-2">
                <div className="space-y-1.5">
                  <Label>BIN</Label>
                  <Input value={bin} onChange={(e) => setBin(e.target.value)} placeholder="Optional" />
                </div>
                <div className="space-y-1.5">
                  <Label>Channel</Label>
                  <Input value={channel} onChange={(e) => setChannel(e.target.value.toUpperCase())} />
                </div>
              </div>
              <Button type="submit" disabled={createSchedule.isPending || liveMutationLocked || !scheduleName.trim()}>
                <Plus className="mr-2 size-4" />
                Create schedule
              </Button>
            </form>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Preview and Publish</CardTitle>
          </CardHeader>
          <CardContent>
            <form className="space-y-3" onSubmit={submitPreview}>
              <div className="grid gap-3 md:grid-cols-[1.4fr_0.6fr_0.6fr]">
                <div className="space-y-1.5">
                  <Label>Schedule</Label>
                  <Select value={effectiveScheduleId} onValueChange={(v) => setSelectedScheduleId(v ?? '')}>
                    <SelectTrigger className="w-full">
                      <SelectValue placeholder="Select schedule">
                        {selectedSchedule ? scheduleLabel(selectedSchedule, programs) : null}
                      </SelectValue>
                    </SelectTrigger>
                    <SelectContent>
                      {schedules.map((schedule) => (
                        <SelectItem key={schedule.scheduleId} value={schedule.scheduleId}>
                          {scheduleLabel(schedule, programs)}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1.5">
                  <Label>Currency</Label>
                  <Input value={feeCurrency} onChange={(e) => setFeeCurrency(e.target.value.toUpperCase())} maxLength={3} />
                </div>
                <div className="space-y-1.5">
                  <Label>Scale</Label>
                  <Input
                    type="number"
                    min={0}
                    value={feeScale}
                    onChange={(e) => setFeeScale(Number(e.target.value))}
                  />
                </div>
              </div>
              <div className="grid gap-3 lg:grid-cols-2">
                <JsonField label="Rules JSON" value={rulesJson} onChange={setRulesJson} minHeight="300px" />
                <JsonField label="Preview Context JSON" value={contextJson} onChange={setContextJson} minHeight="300px" />
              </div>
              <div className="flex flex-wrap gap-2">
                <Button type="submit" disabled={previewFee.isPending}>
                  <Play className="mr-2 size-4" />
                  Preview
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  disabled={publishVersion.isPending || liveMutationLocked || !effectiveScheduleId}
                  onClick={() => publishVersion.mutate()}
                >
                  <Upload className="mr-2 size-4" />
                  Publish version {nextVersion}
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      </div>

      <PreviewPanel result={previewResult} />

      <div className="grid gap-4 xl:grid-cols-2">
        <SchedulesPanel
          schedules={schedules}
          programs={programs}
          loading={schedulesQuery.isLoading}
          total={schedulesQuery.data?.totalElements ?? 0}
          page={schedulePage}
          totalPages={schedulesQuery.data?.totalPages ?? 1}
          selectedScheduleId={effectiveScheduleId}
          onSelect={setSelectedScheduleId}
          onPage={setSchedulePage}
          versions={versionsQuery.data ?? []}
        />
        <AssessmentsPanel
          assessments={assessmentsQuery.data?.content ?? []}
          loading={assessmentsQuery.isLoading}
          total={assessmentsQuery.data?.totalElements ?? 0}
          page={assessmentPage}
          totalPages={assessmentsQuery.data?.totalPages ?? 1}
          onPage={setAssessmentPage}
        />
      </div>
    </div>
  );
}

function PageHeader({ refreshing, onRefresh }: { refreshing: boolean; onRefresh: () => void }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div>
        <div className="flex items-center gap-2">
          <CircleDollarSign className="size-5 text-primary" />
          <h1 className="text-2xl font-semibold tracking-normal">Issuing Fees</h1>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">
          Preview prepaid-card fee rules and publish schedule versions for simulator issuing.
        </p>
      </div>
      <Button variant="outline" onClick={onRefresh} disabled={refreshing}>
        <RefreshCcw className="mr-2 size-4" />
        Refresh
      </Button>
    </div>
  );
}

function JsonField({
  label,
  value,
  onChange,
  minHeight,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  minHeight: string;
}) {
  return (
    <div className="space-y-1.5">
      <Label>{label}</Label>
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-md border bg-white px-3 py-2 font-mono text-xs outline-none focus:ring-2 focus:ring-primary/30"
        style={{ minHeight }}
        spellCheck={false}
      />
    </div>
  );
}

function PreviewPanel({ result }: { result: FeeAssessment | null }) {
  return (
    <section className="rounded-md border bg-white">
      <div className="flex items-center justify-between border-b px-4 py-3">
        <div>
          <h2 className="text-sm font-semibold">Preview Result</h2>
          <p className="text-xs text-muted-foreground">Matched rules, visible fees, hidden fees, and rounded line amounts</p>
        </div>
        <Badge variant="outline">{result?.lines.length ?? 0} lines</Badge>
      </div>
      {!result ? (
        <div className="px-4 py-10 text-center text-sm text-muted-foreground">
          Run a preview to validate fee rules before publishing a schedule version.
        </div>
      ) : (
        <div className="grid gap-4 p-4 lg:grid-cols-[0.7fr_1.3fr]">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-1">
            <Metric label="Matched rules" value={String(result.matchedRules.length)} />
            <Metric label="Visible total" value={formatTotals(result.visibleTotals)} />
            <Metric label="Hidden total" value={formatTotals(result.hiddenTotals)} />
          </div>
          <FeeLinesTable lines={result.lines} />
        </div>
      )}
    </section>
  );
}

function SchedulesPanel({
  schedules,
  programs,
  loading,
  total,
  page,
  totalPages,
  selectedScheduleId,
  onSelect,
  onPage,
  versions,
}: {
  schedules: FeeSchedule[];
  programs: CardProgram[];
  loading: boolean;
  total: number;
  page: number;
  totalPages: number;
  selectedScheduleId: string;
  onSelect: (value: string) => void;
  onPage: (value: number) => void;
  versions: FeeScheduleVersion[];
}) {
  return (
    <section className="rounded-md border bg-white">
      <div className="flex items-center justify-between border-b px-4 py-3">
        <div>
          <h2 className="text-sm font-semibold">Schedules</h2>
          <p className="text-xs text-muted-foreground">Versioned fee configuration by merchant, mode, and optional program scope</p>
        </div>
        <Badge variant="outline">{total} total</Badge>
      </div>
      {loading ? (
        <div className="px-4 py-10 text-center text-sm text-muted-foreground">Loading schedules...</div>
      ) : schedules.length === 0 ? (
        <div className="px-4 py-10 text-center text-sm text-muted-foreground">No fee schedules have been created.</div>
      ) : (
        <div className="divide-y">
          {schedules.map((schedule) => (
            <button
              key={schedule.scheduleId}
              type="button"
              onClick={() => onSelect(schedule.scheduleId)}
              className={`w-full px-4 py-3 text-left text-sm transition-colors ${
                selectedScheduleId === schedule.scheduleId ? 'bg-primary/5' : 'hover:bg-gray-50'
              }`}
            >
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{schedule.name}</span>
                <StatusBadge status={schedule.status} />
                {schedule.channel && <Badge variant="outline">{schedule.channel}</Badge>}
              </div>
              <div className="mt-1 text-xs text-muted-foreground">
                {schedule.programId ? programName(programs, schedule.programId) : 'All programs'}
                {schedule.bin ? ` · BIN ${schedule.bin}` : ''}
              </div>
            </button>
          ))}
        </div>
      )}
      <div className="border-t px-4 py-3">
        <div className="mb-3 text-xs font-medium uppercase text-muted-foreground">Selected Versions</div>
        {versions.length === 0 ? (
          <div className="text-sm text-muted-foreground">No versions published for the selected schedule.</div>
        ) : (
          <div className="space-y-2">
            {versions.slice(0, 5).map((version) => (
              <div key={`${version.scheduleId}-${version.version}`} className="rounded-md border px-3 py-2 text-sm">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium">Version {version.version}</span>
                  <StatusBadge status={version.status} />
                </div>
                <div className="mt-1 text-xs text-muted-foreground">
                  {version.feeCurrency} · {version.roundingMode} · {formatDateTime(version.effectiveFrom)}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
      <Pager page={page} totalPages={totalPages} onPage={onPage} />
    </section>
  );
}

function AssessmentsPanel({
  assessments,
  loading,
  total,
  page,
  totalPages,
  onPage,
}: {
  assessments: FeeAssessmentSnapshot[];
  loading: boolean;
  total: number;
  page: number;
  totalPages: number;
  onPage: (value: number) => void;
}) {
  return (
    <section className="rounded-md border bg-white">
      <div className="flex items-center justify-between border-b px-4 py-3">
        <div>
          <h2 className="text-sm font-semibold">Recent Assessments</h2>
          <p className="text-xs text-muted-foreground">Immutable fee snapshots produced by prepaid-card workflows</p>
        </div>
        <Badge variant="outline">{total} total</Badge>
      </div>
      {loading ? (
        <div className="px-4 py-10 text-center text-sm text-muted-foreground">Loading assessments...</div>
      ) : assessments.length === 0 ? (
        <div className="px-4 py-10 text-center text-sm text-muted-foreground">
          No fee assessments exist yet. Card creation fees will appear here after a matching active schedule is published.
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-left text-xs uppercase text-muted-foreground">
              <tr>
                <th className="px-4 py-3 font-medium">Event</th>
                <th className="px-4 py-3 font-medium">Version</th>
                <th className="px-4 py-3 font-medium">Visible</th>
                <th className="px-4 py-3 font-medium">Hidden</th>
                <th className="px-4 py-3 font-medium">Created</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {assessments.map((snapshot) => (
                <tr key={snapshot.assessment.assessmentId} className="hover:bg-gray-50">
                  <td className="px-4 py-3">
                    <div className="font-medium">{snapshot.assessment.eventType}</div>
                    <div className="text-xs text-muted-foreground">{snapshot.lines.length} fee lines</div>
                  </td>
                  <td className="px-4 py-3">v{snapshot.assessment.scheduleVersion}</td>
                  <td className="px-4 py-3">{formatJsonTotals(snapshot.assessment.visibleTotalsJson)}</td>
                  <td className="px-4 py-3">{formatJsonTotals(snapshot.assessment.hiddenTotalsJson)}</td>
                  <td className="px-4 py-3">{formatDateTime(snapshot.assessment.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pager page={page} totalPages={totalPages} onPage={onPage} />
    </section>
  );
}

function FeeLinesTable({ lines }: { lines: FeeLine[] }) {
  if (lines.length === 0) {
    return <div className="rounded-md border px-4 py-8 text-center text-sm text-muted-foreground">No rules matched.</div>;
  }
  return (
    <div className="overflow-x-auto rounded-md border">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 text-left text-xs uppercase text-muted-foreground">
          <tr>
            <th className="px-4 py-3 font-medium">Fee</th>
            <th className="px-4 py-3 font-medium">Visibility</th>
            <th className="px-4 py-3 font-medium">Basis</th>
            <th className="px-4 py-3 font-medium">Raw</th>
            <th className="px-4 py-3 font-medium">Amount</th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {lines.map((line) => (
            <tr key={`${line.ruleId}-${line.componentId}`} className="hover:bg-gray-50">
              <td className="px-4 py-3">
                <div className="font-medium">{line.name}</div>
                <div className="text-xs text-muted-foreground">{line.ruleName}</div>
              </td>
              <td className="px-4 py-3"><Badge variant="outline">{line.visibility}</Badge></td>
              <td className="px-4 py-3">{line.basisAmount ?? '-'}</td>
              <td className="px-4 py-3">{line.rawCalculatedAmount}</td>
              <td className="px-4 py-3 font-medium">{formatAmount(line.amount, line.currency)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border bg-gray-50 px-3 py-2">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-1 text-sm font-medium">{value}</div>
    </div>
  );
}

function Pager({ page, totalPages, onPage }: { page: number; totalPages: number; onPage: (value: number) => void }) {
  return (
    <div className="flex items-center justify-between border-t px-4 py-3 text-sm">
      <span className="text-muted-foreground">Page {page + 1} of {Math.max(totalPages, 1)}</span>
      <div className="flex gap-2">
        <Button variant="outline" disabled={page === 0} onClick={() => onPage(Math.max(0, page - 1))}>
          Previous
        </Button>
        <Button variant="outline" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>
          Next
        </Button>
      </div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const variant = status === 'ACTIVE' ? 'default' : status === 'DRAFT' ? 'secondary' : 'outline';
  return <Badge variant={variant}>{status}</Badge>;
}

function parseJson(value: string) {
  return JSON.parse(value);
}

function programLabel(program: CardProgram) {
  return `${program.name} (${program.currency})`;
}

function programName(programs: CardProgram[], programId: string) {
  return programs.find((program) => program.programId === programId)?.name ?? programId;
}

function scheduleLabel(schedule: FeeSchedule, programs: CardProgram[]) {
  const scope = schedule.programId ? programName(programs, schedule.programId) : 'All programs';
  return `${schedule.name} · ${scope}`;
}

function formatTotals(totals: Record<string, number | string>) {
  const entries = Object.entries(totals);
  if (entries.length === 0) return '-';
  return entries.map(([currency, amount]) => formatAmount(amount, currency)).join(', ');
}

function formatJsonTotals(json: string) {
  try {
    return formatTotals(JSON.parse(json));
  } catch {
    return '-';
  }
}

function formatAmount(value: number | string | undefined | null, currency: string) {
  const number = Number(value ?? 0);
  return `${currency} ${number.toFixed(2)}`;
}

function formatDateTime(value: string | null | undefined) {
  if (!value) return '-';
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function errorMessage(error: unknown, fallback: string) {
  if (error instanceof SyntaxError) {
    return 'JSON is invalid';
  }
  if (error instanceof ApiError) {
    return error.detail ?? error.title;
  }
  return fallback;
}
