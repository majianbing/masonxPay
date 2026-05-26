'use client';

import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { Archive, ArrowLeft, CheckCircle2, FlaskConical, Plus, Save, Send, Trash2 } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { PROVIDER_BRAND } from '@/lib/provider-brands';
import { useAuthStore } from '@/store/auth';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

interface ProviderAccount {
  id: string;
  provider: string;
  label: string;
  status?: string;
}

interface ProviderCapability {
  id?: string;
  providerAccountId: string;
  paymentMethodType: string;
  country: string | null;
  currency: string | null;
  minAmount: number | null;
  maxAmount: number | null;
  supportsManualCapture: boolean;
  supportsRefund: boolean;
  supportsPartialRefund: boolean;
  supports3ds: boolean;
  supportsRedirect: boolean;
  supportsProviderToken: boolean;
  supportsVaultToken: boolean;
  supportsNetworkToken: boolean;
  supportsInstallments: boolean;
  enabled: boolean;
}

interface RoutePolicy {
  id: string;
  mode: 'TEST' | 'LIVE';
  name: string;
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED';
  policyVersion: number;
  description?: string;
  publishedAt?: string;
  routes: PolicyRoute[];
  validationIssues: ValidationIssue[];
}

interface PolicyRoute {
  id: string;
  routeOrder: number;
  name: string;
  defaultRoute: boolean;
  conditionsJson: string;
  steps: PolicyStep[];
}

interface PolicyStep {
  id: string;
  stepOrder: number;
  providerAccountId: string;
  trafficWeight: number;
  maxCostBps?: number;
  skipIfDegraded: boolean;
  outcomeActionsJson: string;
}

interface ValidationIssue {
  code: string;
  message: string;
}

interface AuditLog {
  id: string;
  action: string;
  beforeStatus?: string;
  afterStatus?: string;
  createdAt: string;
}

interface SimulateRouteResponse {
  matched: boolean;
  candidates: Array<{
    accountId: string;
    provider: string;
    label: string;
    status: string;
    primary: boolean;
    weight: number;
  }>;
}

interface RouteDraft {
  key: string;
  name: string;
  defaultRoute: boolean;
  conditionsJson: string;
  steps: StepDraft[];
}

interface StepDraft {
  key: string;
  providerAccountId: string;
  trafficWeight: string;
  maxCostBps: string;
  skipIfDegraded: boolean;
  outcomeActionsJson: string;
}

const DEFAULT_OUTCOMES = '{"APPROVED":"finish","PROVIDER_TIMEOUT":"next","PROVIDER_ERROR":"next","DECLINED":"finish"}';
const DEFAULT_CONDITION = '{"all":[{"field":"currency","operator":"eq","value":"USD"}]}';
const EMPTY_CONNECTORS: ProviderAccount[] = [];
const EMPTY_CAPABILITIES: ProviderCapability[] = [];
const EMPTY_POLICIES: RoutePolicy[] = [];
const EMPTY_AUDIT_LOGS: AuditLog[] = [];

function newKey() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function accountLabel(account?: ProviderAccount) {
  if (!account) return 'Select connector';
  const brand = PROVIDER_BRAND[account.provider]?.name ?? account.provider;
  return `${brand} - ${account.label}`;
}

function capabilitySummary(rows: ProviderCapability[] | undefined) {
  const enabled = (rows ?? []).filter((row) => row.enabled);
  if (!rows) return 'Select connector to view accepted capabilities';
  if (enabled.length === 0) return 'Capabilities unspecified: treated as allowed';

  const methods = compactUnique(enabled.map((row) => row.paymentMethodType)).slice(0, 3);
  const currencies = compactUnique(enabled.map((row) => row.currency)).slice(0, 3);
  const countries = compactUnique(enabled.map((row) => row.country)).slice(0, 3);
  const flags = [
    enabled.some((row) => row.supportsManualCapture) ? 'manual capture' : null,
    enabled.some((row) => row.supports3ds) ? '3DS' : null,
    enabled.some((row) => row.supportsRedirect) ? 'redirect' : null,
  ].filter(Boolean);

  return [
    methods.length > 0 ? methods.join('/') : 'any method',
    currencies.length > 0 ? currencies.join('/') : 'any currency',
    countries.length > 0 ? countries.join('/') : 'any country',
    ...flags,
  ].join(' • ');
}

function compactUnique(values: Array<string | null | undefined>) {
  return Array.from(new Set(values.map((value) => value?.trim()).filter(Boolean))) as string[];
}

function defaultRoute(providerAccountId = ''): RouteDraft {
  return {
    key: newKey(),
    name: 'Default route',
    defaultRoute: true,
    conditionsJson: '{}',
    steps: [defaultStep(providerAccountId)],
  };
}

function defaultStep(providerAccountId = ''): StepDraft {
  return {
    key: newKey(),
    providerAccountId,
    trafficWeight: '100',
    maxCostBps: '',
    skipIfDegraded: true,
    outcomeActionsJson: DEFAULT_OUTCOMES,
  };
}

function routeFromPolicy(route: PolicyRoute): RouteDraft {
  return {
    key: route.id,
    name: route.name,
    defaultRoute: route.defaultRoute,
    conditionsJson: route.conditionsJson,
    steps: route.steps.map((step) => ({
      key: step.id,
      providerAccountId: step.providerAccountId,
      trafficWeight: String(step.trafficWeight),
      maxCostBps: step.maxCostBps ? String(step.maxCostBps) : '',
      skipIfDegraded: step.skipIfDegraded,
      outcomeActionsJson: step.outcomeActionsJson,
    })),
  };
}

export default function PolicyEditor({ policyId }: { policyId?: string }) {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const qc = useQueryClient();
  const router = useRouter();
  const [name, setName] = useState('Test checkout routing');
  const [description, setDescription] = useState('Dry-run policy for simulator checkout flows.');
  const [routes, setRoutes] = useState<RouteDraft[]>([]);
  const [simulation, setSimulation] = useState({
    amount: '2500',
    currency: 'USD',
    country: 'US',
    paymentMethodType: 'card',
    metadata: '',
  });

  const { data: connectors = EMPTY_CONNECTORS } = useQuery<ProviderAccount[]>({
    queryKey: ['policy-connectors', activeMerchantId, mode],
    queryFn: () => apiFetch<ProviderAccount[]>(`/api/v1/merchants/${activeMerchantId}/connectors?mode=${mode}`),
    enabled: !!activeMerchantId,
  });

  const { data: connectorCapabilities = EMPTY_CAPABILITIES } = useQuery<ProviderCapability[]>({
    queryKey: ['policy-connector-capabilities', activeMerchantId, mode, connectors.map((connector) => connector.id).join(',')],
    queryFn: async () => {
      const nested = await Promise.all(connectors.map((connector) =>
        apiFetch<ProviderCapability[]>(
          `/api/v1/merchants/${activeMerchantId}/connectors/${connector.id}/capabilities`,
        ).catch(() => []),
      ));
      return nested.flat();
    },
    enabled: !!activeMerchantId && connectors.length > 0,
  });

  const { data: policies = EMPTY_POLICIES } = useQuery<RoutePolicy[]>({
    queryKey: ['route-policies', activeMerchantId],
    queryFn: () => apiFetch<RoutePolicy[]>(`/api/v1/merchants/${activeMerchantId}/route-policies`),
    enabled: !!activeMerchantId,
  });

  const visiblePolicies = useMemo(
    () => policies.filter((policy) => policy.mode === mode),
    [mode, policies],
  );
  const selectedPolicy = policyId ? visiblePolicies.find((policy) => policy.id === policyId) ?? null : null;
  const activePolicy = visiblePolicies.find((policy) => policy.status === 'ACTIVE');
  const editable = !selectedPolicy || selectedPolicy.status !== 'ACTIVE';

  const { data: auditLogs = EMPTY_AUDIT_LOGS } = useQuery<AuditLog[]>({
    queryKey: ['route-policy-audit', activeMerchantId, policyId],
    queryFn: () => apiFetch<AuditLog[]>(`/api/v1/merchants/${activeMerchantId}/route-policies/${policyId}/audit-logs`),
    enabled: !!activeMerchantId && !!policyId,
  });

  const connectorById = useMemo(
    () => new Map(connectors.map((connector) => [connector.id, connector])),
    [connectors],
  );
  const capabilitiesByConnectorId = useMemo(() => {
    const map = new Map<string, ProviderCapability[]>();
    connectorCapabilities.forEach((capability) => {
      const rows = map.get(capability.providerAccountId) ?? [];
      rows.push(capability);
      map.set(capability.providerAccountId, rows);
    });
    return map;
  }, [connectorCapabilities]);
  const firstConnectorId = connectors[0]?.id ?? '';

  useEffect(() => {
    if (!selectedPolicy) {
      setName('Test checkout routing');
      setDescription('Dry-run policy for simulator checkout flows.');
      setRoutes([defaultRoute(firstConnectorId)]);
      return;
    }
    setName(selectedPolicy.name);
    setDescription(selectedPolicy.description ?? '');
    setRoutes((selectedPolicy.routes ?? []).map(routeFromPolicy));
  }, [firstConnectorId, selectedPolicy]);

  const saveMutation = useMutation({
    mutationFn: () => {
      const body = JSON.stringify(policyPayload(mode, name, description, routes));
      if (selectedPolicy && selectedPolicy.status !== 'ACTIVE') {
        return apiFetch<RoutePolicy>(`/api/v1/merchants/${activeMerchantId}/route-policies/${selectedPolicy.id}`, {
          method: 'PUT',
          body,
        });
      }
      return apiFetch<RoutePolicy>(`/api/v1/merchants/${activeMerchantId}/route-policies`, {
        method: 'POST',
        body,
      });
    },
    onSuccess: (policy) => {
      qc.invalidateQueries({ queryKey: ['route-policies', activeMerchantId] });
      router.replace(`/routing/policies/${policy.id}`);
      toast.success('Draft saved');
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string };
      toast.error(e.detail ?? 'Failed to save draft');
    },
  });

  const publishMutation = useMutation({
    mutationFn: (policyId: string) =>
      apiFetch<RoutePolicy>(`/api/v1/merchants/${activeMerchantId}/route-policies/${policyId}/publish`, { method: 'POST' }),
    onSuccess: (policy) => {
      qc.invalidateQueries({ queryKey: ['route-policies', activeMerchantId] });
      qc.invalidateQueries({ queryKey: ['route-policy-audit', activeMerchantId, policy.id] });
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
    onSuccess: (policy) => {
      qc.invalidateQueries({ queryKey: ['route-policies', activeMerchantId] });
      qc.invalidateQueries({ queryKey: ['route-policy-audit', activeMerchantId, policy.id] });
      toast.success('Policy archived');
    },
  });

  const simulateMutation = useMutation({
    mutationFn: () =>
      apiFetch<SimulateRouteResponse>(`/api/v1/merchants/${activeMerchantId}/route-simulations`, {
        method: 'POST',
        body: JSON.stringify({
          mode,
          amount: Number(simulation.amount),
          currency: simulation.currency,
          country: simulation.country,
          paymentMethodType: simulation.paymentMethodType,
          metadata: parseMetadata(simulation.metadata),
        }),
      }),
    onError: (err: unknown) => {
      const e = err as { detail?: string };
      toast.error(e.detail ?? 'Simulation failed');
    },
  });

  function addConditionalRoute() {
    setRoutes((prev) => [
      ...prev,
      {
        key: newKey(),
        name: `Route ${prev.length + 1}`,
        defaultRoute: false,
        conditionsJson: DEFAULT_CONDITION,
        steps: [defaultStep(firstConnectorId)],
      },
    ]);
  }

  function updateRoute(key: string, patch: Partial<RouteDraft>) {
    setRoutes((prev) => prev.map((route) => {
      if (route.key !== key) return route;
      const next = { ...route, ...patch };
      if (patch.defaultRoute) {
        next.conditionsJson = '{}';
      }
      return next;
    }).map((route) => (patch.defaultRoute && route.key !== key ? { ...route, defaultRoute: false } : route)));
  }

  function updateStep(routeKey: string, stepKey: string, patch: Partial<StepDraft>) {
    setRoutes((prev) => prev.map((route) => route.key === routeKey
      ? { ...route, steps: route.steps.map((step) => (step.key === stepKey ? { ...step, ...patch } : step)) }
      : route));
  }

  function addStep(routeKey: string) {
    setRoutes((prev) => prev.map((route) => route.key === routeKey
      ? { ...route, steps: [...route.steps, defaultStep(firstConnectorId)] }
      : route));
  }

  return (
    <div className="space-y-6 max-w-6xl">
      <div className="flex items-center justify-between gap-3">
        <div>
          <Button variant="outline" size="sm" onClick={() => router.push('/routing/policies')} className="mb-3">
            <ArrowLeft className="size-4 mr-2" /> Back
          </Button>
          <h1 className="text-2xl font-semibold">{selectedPolicy ? selectedPolicy.name : 'New Routing Policy'}</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Policies define preferred connector order. Connector capabilities still filter out steps that cannot process the payment.
          </p>
        </div>
      </div>

      <div className="space-y-4">
          <Card>
            <CardContent className="space-y-5 pt-6">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex items-center gap-2">
                  <h2 className="text-lg font-semibold">{selectedPolicy ? `Policy v${selectedPolicy.policyVersion}` : 'New Draft'}</h2>
                  {selectedPolicy && <StatusBadge status={selectedPolicy.status} />}
                  {activePolicy && <Badge variant="secondary">Active {activePolicy.name}</Badge>}
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button onClick={() => saveMutation.mutate()} disabled={!editable || saveMutation.isPending}>
                    <Save className="size-4 mr-2" /> Save Draft
                  </Button>
                  {selectedPolicy && (
                    <Button
                      variant="outline"
                      onClick={() => publishMutation.mutate(selectedPolicy.id)}
                      disabled={publishMutation.isPending || selectedPolicy.status === 'ARCHIVED'}
                    >
                      <Send className="size-4 mr-2" /> Publish
                    </Button>
                  )}
                  {selectedPolicy && selectedPolicy.status !== 'ARCHIVED' && (
                    <Button variant="outline" onClick={() => archiveMutation.mutate(selectedPolicy.id)}>
                      <Archive className="size-4 mr-2" /> Archive
                    </Button>
                  )}
                </div>
              </div>

              <div className="grid gap-3 md:grid-cols-2">
                <div className="space-y-1">
                  <Label>Name</Label>
                  <Input value={name} onChange={(event) => setName(event.target.value)} disabled={!editable} />
                </div>
                <div className="space-y-1">
                  <Label>Mode</Label>
                  <Input value={mode} disabled />
                </div>
              </div>
              <div className="space-y-1">
                <Label>Description</Label>
                <Input value={description} onChange={(event) => setDescription(event.target.value)} disabled={!editable} />
              </div>

              {selectedPolicy?.validationIssues && selectedPolicy.validationIssues.length > 0 && (
                <div className="rounded-md border border-red-200 bg-red-50 p-3">
                  <p className="text-sm font-medium text-red-800">Validation issues</p>
                  <div className="mt-2 space-y-1">
                    {selectedPolicy.validationIssues.map((issue) => (
                      <p key={`${issue.code}-${issue.message}`} className="text-xs text-red-700">
                        {issue.code}: {issue.message}
                      </p>
                    ))}
                  </div>
                </div>
              )}

              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <div>
                    <h3 className="font-medium">Routes</h3>
                    <p className="text-xs text-muted-foreground">
                      Conditions support built-ins like amount, currency, country, payment_method_type, and registered metadata.* attributes.
                    </p>
                  </div>
                  <Button type="button" variant="outline" onClick={addConditionalRoute} disabled={!editable}>
                    <Plus className="size-4 mr-2" /> Add Route
                  </Button>
                </div>

                {routes.map((route, routeIndex) => (
                  <div key={route.key} className="rounded-md border p-4 space-y-4">
                    <div className="grid gap-3 md:grid-cols-[1fr_160px_auto]">
                      <div className="space-y-1">
                        <Label>Route name</Label>
                        <Input
                          value={route.name}
                          disabled={!editable}
                          onChange={(event) => updateRoute(route.key, { name: event.target.value })}
                        />
                      </div>
                      <div className="space-y-1">
                        <Label>Type</Label>
                        <Button
                          type="button"
                          variant={route.defaultRoute ? 'default' : 'outline'}
                          className="w-full"
                          disabled={!editable}
                          onClick={() => updateRoute(route.key, { defaultRoute: true })}
                        >
                          {route.defaultRoute ? 'Default' : 'Make Default'}
                        </Button>
                      </div>
                      <div className="flex items-end">
                        <Button
                          type="button"
                          variant="outline"
                          size="icon"
                          disabled={!editable || routes.length === 1}
                          onClick={() => setRoutes((prev) => prev.filter((item) => item.key !== route.key))}
                          aria-label="Remove route"
                        >
                          <Trash2 className="size-4" />
                        </Button>
                      </div>
                    </div>

                    <div className="space-y-1">
                      <Label>Conditions JSON</Label>
                      <textarea
                        value={route.conditionsJson}
                        disabled={!editable || route.defaultRoute}
                        onChange={(event) => updateRoute(route.key, { conditionsJson: event.target.value })}
                        className="min-h-24 w-full rounded-md border px-3 py-2 font-mono text-xs disabled:bg-gray-50"
                      />
                      <p className="text-xs text-muted-foreground">
                        Use {DEFAULT_CONDITION}. Operators include eq, not_eq, in, gt, gte, lt, lte, between, and missing where valid for the field type.
                      </p>
                    </div>

                    <div className="space-y-3">
                      <div className="flex items-center justify-between">
                        <Label>Steps</Label>
                        <Button type="button" variant="outline" size="sm" onClick={() => addStep(route.key)} disabled={!editable}>
                          <Plus className="size-3 mr-1" /> Add Step
                        </Button>
                      </div>
                      {route.steps.map((step, stepIndex) => (
                        <div key={step.key} className="grid gap-3 rounded-md bg-gray-50 p-3 md:grid-cols-[1.4fr_100px_110px_130px_auto]">
                          <div className="space-y-1">
                            <Label>Connector</Label>
                            <Select
                              value={step.providerAccountId}
                              onValueChange={(value) => updateStep(route.key, step.key, { providerAccountId: value ?? '' })}
                              disabled={!editable}
                            >
                              <SelectTrigger>
                                <SelectValue placeholder="Select connector">
                                  {accountLabel(connectorById.get(step.providerAccountId))}
                                </SelectValue>
                              </SelectTrigger>
                              <SelectContent>
                                {connectors.map((connector) => (
                                  <SelectItem key={connector.id} value={connector.id}>
                                    <span className="flex flex-col items-start gap-0.5">
                                      <span>{accountLabel(connector)}</span>
                                      <span className="text-xs text-muted-foreground">
                                        {capabilitySummary(capabilitiesByConnectorId.get(connector.id))}
                                      </span>
                                    </span>
                                  </SelectItem>
                                ))}
                              </SelectContent>
                            </Select>
                            <p className="text-xs leading-5 text-muted-foreground">
                              {capabilitySummary(capabilitiesByConnectorId.get(step.providerAccountId))}
                            </p>
                          </div>
                          <div className="space-y-1">
                            <Label>Weight</Label>
                            <Input
                              value={step.trafficWeight}
                              disabled={!editable}
                              onChange={(event) => updateStep(route.key, step.key, { trafficWeight: event.target.value })}
                            />
                          </div>
                          <div className="space-y-1">
                            <Label>Max bps</Label>
                            <Input
                              value={step.maxCostBps}
                              disabled={!editable}
                              onChange={(event) => updateStep(route.key, step.key, { maxCostBps: event.target.value })}
                            />
                          </div>
                          <div className="space-y-1">
                            <Label>Degraded</Label>
                            <Button
                              type="button"
                              variant={step.skipIfDegraded ? 'default' : 'outline'}
                              className="w-full"
                              disabled={!editable}
                              onClick={() => updateStep(route.key, step.key, { skipIfDegraded: !step.skipIfDegraded })}
                            >
                              {step.skipIfDegraded ? 'Skip' : 'Allow'}
                            </Button>
                          </div>
                          <div className="flex items-end">
                            <Button
                              type="button"
                              variant="outline"
                              size="icon"
                              disabled={!editable || route.steps.length === 1}
                              onClick={() => updateRoute(route.key, {
                                steps: route.steps.filter((item) => item.key !== step.key),
                              })}
                              aria-label={`Remove step ${stepIndex + 1}`}
                            >
                              <Trash2 className="size-4" />
                            </Button>
                          </div>
                          <div className="space-y-1 md:col-span-5">
                            <Label>Outcome actions JSON</Label>
                            <textarea
                              value={step.outcomeActionsJson}
                              disabled={!editable}
                              onChange={(event) => updateStep(route.key, step.key, { outcomeActionsJson: event.target.value })}
                              className="min-h-16 w-full rounded-md border px-3 py-2 font-mono text-xs disabled:bg-gray-50"
                            />
                          </div>
                        </div>
                      ))}
                    </div>
                    <p className="text-xs text-muted-foreground">Priority {routeIndex + 1}</p>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>

          <div className="grid gap-4 xl:grid-cols-2">
            <Card>
              <CardContent className="space-y-4 pt-6">
                <div className="flex items-center gap-2">
                  <FlaskConical className="size-4" />
                  <h2 className="font-semibold">Dry-run Simulation</h2>
                </div>
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field label="Amount minor units" value={simulation.amount} onChange={(amount) => setSimulation((prev) => ({ ...prev, amount }))} />
                  <Field label="Currency" value={simulation.currency} onChange={(currency) => setSimulation((prev) => ({ ...prev, currency }))} />
                  <Field label="Country" value={simulation.country} onChange={(country) => setSimulation((prev) => ({ ...prev, country }))} />
                  <Field label="Method" value={simulation.paymentMethodType} onChange={(paymentMethodType) => setSimulation((prev) => ({ ...prev, paymentMethodType }))} />
                </div>
                <div className="space-y-1">
                  <Label>Metadata JSON</Label>
                  <textarea
                    value={simulation.metadata}
                    onChange={(event) => setSimulation((prev) => ({ ...prev, metadata: event.target.value }))}
                    className="min-h-20 w-full rounded-md border px-3 py-2 font-mono text-xs"
                    placeholder='{"vip_tier":"gold"}'
                  />
                </div>
                <Button onClick={() => simulateMutation.mutate()} disabled={simulateMutation.isPending}>
                  <FlaskConical className="size-4 mr-2" /> Run Simulation
                </Button>
                {simulateMutation.data && (
                  <div className="rounded-md border p-3">
                    <div className="flex items-center gap-2 text-sm font-medium">
                      <CheckCircle2 className="size-4 text-green-600" />
                      {simulateMutation.data.matched ? 'Matched candidates' : 'No route matched'}
                    </div>
                    <div className="mt-3 space-y-2">
                      {simulateMutation.data.candidates.map((candidate) => (
                        <div key={candidate.accountId} className="flex items-center justify-between rounded-md bg-gray-50 px-3 py-2 text-sm">
                          <span>{candidate.provider} - {candidate.label}</span>
                          <span className="text-muted-foreground">weight {candidate.weight}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardContent className="space-y-3 pt-6">
                <h2 className="font-semibold">Audit History</h2>
                {auditLogs.length === 0 ? (
                  <p className="text-sm text-muted-foreground">No publish/archive events yet.</p>
                ) : auditLogs.map((log) => (
                  <div key={log.id} className="rounded-md border px-3 py-2 text-sm">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-medium">{log.action}</span>
                      <span className="text-xs text-muted-foreground">{new Date(log.createdAt).toLocaleString()}</span>
                    </div>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {log.beforeStatus ?? 'NONE'} to {log.afterStatus ?? 'NONE'}
                    </p>
                  </div>
                ))}
              </CardContent>
            </Card>
          </div>
      </div>
    </div>
  );
}

function Field({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <div className="space-y-1">
      <Label>{label}</Label>
      <Input value={value} onChange={(event) => onChange(event.target.value)} />
    </div>
  );
}

function StatusBadge({ status }: { status: RoutePolicy['status'] }) {
  if (status === 'ACTIVE') return <Badge className="bg-green-600">Active</Badge>;
  if (status === 'ARCHIVED') return <Badge variant="secondary">Archived</Badge>;
  return <Badge variant="outline">Draft</Badge>;
}

function policyPayload(mode: string, name: string, description: string, routes: RouteDraft[]) {
  return {
    mode,
    name,
    description,
    routes: routes.map((route, routeIndex) => ({
      name: route.name,
      routeOrder: routeIndex + 1,
      defaultRoute: route.defaultRoute,
      conditionsJson: route.defaultRoute ? '{}' : route.conditionsJson,
      steps: route.steps.map((step, stepIndex) => ({
        stepOrder: stepIndex + 1,
        providerAccountId: step.providerAccountId,
        trafficWeight: Number(step.trafficWeight || 100),
        maxCostBps: step.maxCostBps ? Number(step.maxCostBps) : undefined,
        skipIfDegraded: step.skipIfDegraded,
        outcomeActionsJson: step.outcomeActionsJson,
      })),
    })),
  };
}

function parseMetadata(raw: string) {
  if (!raw.trim()) return {};
  try {
    return JSON.parse(raw);
  } catch {
    toast.error('Metadata must be valid JSON');
    return {};
  }
}
