'use client';

import { useEffect, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { toast } from 'sonner';
import { Plus, Trash2, Star, PlayCircle, GripVertical, ArrowLeft, CheckCircle2, SlidersHorizontal, Save, X } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { format } from 'date-fns';
import {
  DndContext,
  PointerSensor,
  useSensor,
  useSensors,
  closestCenter,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
  arrayMove,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { PROVIDER_BRAND } from '@/lib/provider-brands';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

// ─── Provider catalogue ───────────────────────────────────────────────────────

interface ProviderAccount {
  id: string;
  provider: string;
  mode: string;
  label: string;
  credentialHint: string;
  clientKey: string | null;
  primary: boolean;
  weight: number;
  status: string;
  displayOrder: number;
  createdAt: string;
}

interface ProviderCapability {
  id?: string;
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

const PROVIDERS = ['STRIPE', 'SQUARE', 'BRAINTREE', 'MOLLIE', 'SIMULATOR'] as const;
type Provider = typeof PROVIDERS[number];
const SIMULATOR_PROVIDER_ENABLED = process.env.NEXT_PUBLIC_ENABLE_SIMULATOR_PROVIDER === 'true';
const PAYMENT_METHOD_OPTIONS = [
  { value: 'card', label: 'Card', detail: 'Standard card payments from hosted checkout or PSP SDKs.' },
  { value: 'apple_pay', label: 'Apple Pay', detail: 'Wallet payment through Apple Pay.' },
  { value: 'google_pay', label: 'Google Pay', detail: 'Wallet payment through Google Pay.' },
  { value: 'cash_app', label: 'Cash App', detail: 'Cash App Pay through Square.' },
  { value: 'paypal', label: 'PayPal', detail: 'PayPal wallet or Braintree PayPal flow.' },
  { value: 'ideal', label: 'iDEAL', detail: 'Dutch bank redirect payment method.' },
  { value: 'bancontact', label: 'Bancontact', detail: 'Belgian bank/card redirect payment method.' },
  { value: 'klarna', label: 'Klarna', detail: 'Buy-now-pay-later or invoice flow.' },
  { value: 'sofort', label: 'Sofort', detail: 'European bank redirect payment method.' },
  { value: 'link', label: 'Link', detail: 'Stripe Link wallet checkout.' },
  { value: 'local_method', label: 'Local method', detail: 'Generic local/redirect method when a provider-specific method is not listed.' },
] as const;

const PROVIDER_META: Record<Provider, {
  label: string;
  tagline: string;
  description: string;
  methods: string[];
  docsUrl: string;
  popular?: boolean;
}> = {
  STRIPE: {
    label: 'Stripe',
    tagline: 'Full-stack payments platform for the internet',
    description: 'Cards, iDEAL, Amazon Pay, Link, Sofort, and other redirect-based methods via Stripe.js.',
    methods: ['Cards', 'iDEAL', 'Link', 'Amazon Pay', 'Wallets'],
    docsUrl: 'https://stripe.com/docs',
    popular: true,
  },
  SQUARE: {
    label: 'Square',
    tagline: 'Payments for in-person and online commerce',
    description: 'Card payments and digital wallets via the Square Web Payments SDK.',
    methods: ['Cards', 'Google Pay', 'Apple Pay', 'Cash App'],
    docsUrl: 'https://developer.squareup.com',
  },
  BRAINTREE: {
    label: 'Braintree',
    tagline: 'PayPal-owned gateway with broad payment coverage',
    description: 'PayPal-owned gateway. Supports cards, PayPal, Venmo, and local payment methods.',
    methods: ['Cards', 'PayPal', 'Venmo', 'Local'],
    docsUrl: 'https://developer.paypal.com/braintree/docs',
  },
  MOLLIE: {
    label: 'Mollie',
    tagline: 'The payments platform for Europe',
    description: 'EU-focused gateway. iDEAL, Klarna, Bancontact, credit cards, and 30+ payment methods via hosted checkout.',
    methods: ['iDEAL', 'Cards', 'Klarna', 'Bancontact', 'PayPal'],
    docsUrl: 'https://docs.mollie.com',
  },
  SIMULATOR: {
    label: 'Mason Simulator',
    tagline: 'Synthetic PSP for benchmark and preview testing',
    description: 'TEST-only fake provider that exercises routing, confirm, capture, refund, and outbox flows without calling external PSP sandboxes.',
    methods: ['Cards', 'Synthetic latency', 'Synthetic failures'],
    docsUrl: '/docs/planning/high-throughput-payment-core-plan.md',
  },
};

// ─── Zod schema ───────────────────────────────────────────────────────────────

const createSchema = z.object({
  provider: z.enum(['STRIPE', 'SQUARE', 'BRAINTREE', 'MOLLIE', 'SIMULATOR'] as const),
  mode: z.enum(['TEST', 'LIVE']),
  label: z.string().min(1, 'Label required'),
  primary: z.boolean(),
  weight: z.coerce.number().int().min(1).max(100).default(1),
  secretKey: z.string().optional(),
  publishableKey: z.string().optional(),
  accessToken: z.string().optional(),
  applicationId: z.string().optional(),
  locationId: z.string().optional(),
  btMerchantId: z.string().optional(),
  btPublicKey: z.string().optional(),
  btPrivateKey: z.string().optional(),
  // Mollie
  mollieApiKey: z.string().optional(),
  // Mason Simulator
  simulatorSuccessRatePercent: z.coerce.number().min(0).max(100).default(100),
  // Phase 3.5: fee configuration
  fixedFeeCents: z.coerce.number().int().min(0).default(0),
  rateBps: z.coerce.number().int().min(0).default(0),
}).superRefine((data, ctx) => {
  if (data.provider === 'STRIPE' && !data.secretKey) {
    ctx.addIssue({ code: 'custom', path: ['secretKey'], message: 'Secret key required' });
  }
  if (data.provider === 'SQUARE') {
    if (!data.accessToken) ctx.addIssue({ code: 'custom', path: ['accessToken'], message: 'Access token required' });
    if (!data.applicationId) ctx.addIssue({ code: 'custom', path: ['applicationId'], message: 'Application ID required' });
    if (!data.locationId) ctx.addIssue({ code: 'custom', path: ['locationId'], message: 'Location ID required' });
  }
  if (data.provider === 'BRAINTREE') {
    if (!data.btMerchantId) ctx.addIssue({ code: 'custom', path: ['btMerchantId'], message: 'Merchant ID required' });
    if (!data.btPublicKey) ctx.addIssue({ code: 'custom', path: ['btPublicKey'], message: 'Public key required' });
    if (!data.btPrivateKey) ctx.addIssue({ code: 'custom', path: ['btPrivateKey'], message: 'Private key required' });
  }
  if (data.provider === 'MOLLIE') {
    if (!data.mollieApiKey) ctx.addIssue({ code: 'custom', path: ['mollieApiKey'], message: 'API key required' });
  }
  if (data.provider === 'SIMULATOR' && data.mode !== 'TEST') {
    ctx.addIssue({ code: 'custom', path: ['provider'], message: 'Mason Simulator is only available in TEST mode' });
  }
});
type CreateForm = z.infer<typeof createSchema>;

function defaultCapability(): ProviderCapability {
  return {
    paymentMethodType: 'card',
    country: null,
    currency: null,
    minAmount: null,
    maxAmount: null,
    supportsManualCapture: true,
    supportsRefund: true,
    supportsPartialRefund: true,
    supports3ds: false,
    supportsRedirect: false,
    supportsProviderToken: true,
    supportsVaultToken: false,
    supportsNetworkToken: false,
    supportsInstallments: false,
    enabled: true,
  };
}

function capabilityPayload(rows: ProviderCapability[]) {
  return rows.map((row) => ({
    paymentMethodType: row.paymentMethodType.trim().toLowerCase(),
    country: row.country?.trim() ? row.country.trim().toUpperCase() : null,
    currency: row.currency?.trim() ? row.currency.trim().toUpperCase() : null,
    minAmount: row.minAmount ?? null,
    maxAmount: row.maxAmount ?? null,
    supportsManualCapture: row.supportsManualCapture,
    supportsRefund: row.supportsRefund,
    supportsPartialRefund: row.supportsPartialRefund,
    supports3ds: row.supports3ds,
    supportsRedirect: row.supportsRedirect,
    supportsProviderToken: row.supportsProviderToken,
    supportsVaultToken: row.supportsVaultToken,
    supportsNetworkToken: row.supportsNetworkToken,
    supportsInstallments: row.supportsInstallments,
    enabled: row.enabled,
  }));
}

// ─── Provider picker card ─────────────────────────────────────────────────────

function ProviderPickerCard({
  provider,
  selected,
  onClick,
}: {
  provider: Provider;
  selected: boolean;
  onClick: () => void;
}) {
  const meta = PROVIDER_META[provider];
  const brand = PROVIDER_BRAND[provider];

  return (
    <button
      type="button"
      onClick={onClick}
      className={[
        'relative w-full text-left rounded-xl border-2 px-5 py-4 transition-all duration-150',
        'hover:shadow-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-indigo-500',
        'flex items-center gap-5',
        selected
          ? 'border-indigo-500 bg-indigo-50/60 shadow-sm'
          : 'border-gray-200 bg-white hover:border-gray-300',
      ].join(' ')}
    >
      {/* Logo */}
      <div className="size-12 flex-shrink-0 flex items-center justify-center rounded-xl bg-white shadow-sm border border-gray-100">
        <div className="size-7 [&>svg]:!size-7">{brand?.icon}</div>
      </div>

      {/* Name + tagline + method tags */}
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <p className="font-semibold text-sm text-gray-900">{meta.label}</p>
          {meta.popular && (
            <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded-full bg-indigo-100 text-indigo-600 uppercase tracking-wide">
              Popular
            </span>
          )}
        </div>
        <p className="text-xs text-muted-foreground mt-0.5 leading-snug">{meta.tagline}</p>
        <div className="flex flex-wrap gap-1 mt-2">
          {meta.methods.map((m) => (
            <span key={m} className="text-[10px] px-1.5 py-0.5 rounded-full bg-gray-100 text-gray-600 font-medium">
              {m}
            </span>
          ))}
        </div>
      </div>

      {/* Selected indicator */}
      <div className="flex-shrink-0 size-5">
        {selected && <CheckCircle2 className="size-5 text-indigo-600" />}
      </div>
    </button>
  );
}

// ─── Sortable brand card ──────────────────────────────────────────────────────

function SortableProviderCard({
  provider,
  connectors,
  onSetPrimary,
  onDelete,
  onPreview,
  onCapabilities,
}: {
  provider: Provider;
  connectors: ProviderAccount[];
  onSetPrimary: (id: string) => void;
  onDelete: (account: ProviderAccount) => void;
  onPreview: (id: string) => void;
  onCapabilities: (account: ProviderAccount) => void;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: provider });
  const brand = PROVIDER_BRAND[provider];
  const meta = PROVIDER_META[provider];

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <div ref={setNodeRef} style={style}>
      <Card>
        <CardHeader className="pb-3">
          <div className="flex items-center gap-3">
            <button
              {...attributes}
              {...listeners}
              className="cursor-grab active:cursor-grabbing text-muted-foreground hover:text-foreground flex-shrink-0"
              aria-label="Drag to reorder"
            >
              <GripVertical className="size-4" />
            </button>

            {/* Provider logo */}
            <div className="size-8 flex items-center justify-center rounded-lg bg-white shadow-sm border border-gray-100 flex-shrink-0">
              <div className="size-5 [&>svg]:!size-5">{brand?.icon}</div>
            </div>

            <div className="min-w-0">
              <CardTitle className="text-base">{meta.label}</CardTitle>
              <CardDescription className="truncate">{meta.description}</CardDescription>
            </div>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          <table className="w-full text-sm">
            <thead className="border-b bg-gray-50">
              <tr>
                {['Label', 'Credential', 'Client Key', 'Weight', 'Status', 'Added', ''].map((h) => (
                  <th key={h} className="px-4 py-2.5 text-left font-medium text-muted-foreground text-xs">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {connectors.filter((c) => c.provider === provider).map((c) => (
                <tr key={c.id} className="border-b last:border-0 hover:bg-gray-50/50 transition-colors">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <span className="font-medium">{c.label}</span>
                      {c.primary && <Badge variant="secondary" className="text-xs px-1.5 py-0">Primary</Badge>}
                    </div>
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-muted-foreground">{c.credentialHint}</td>
                  <td className="px-4 py-3 font-mono text-xs text-muted-foreground max-w-[140px] truncate">
                    {c.clientKey ?? <span className="text-orange-500">not set</span>}
                  </td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">{c.weight}</td>
                  <td className="px-4 py-3">
                    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${c.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>
                      {c.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground text-xs">{format(new Date(c.createdAt), 'MMM d, yyyy')}</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1 justify-end">
                      <Button variant="ghost" size="icon" title="Preview cashier" onClick={() => onPreview(c.id)}>
                        <PlayCircle className="size-4 text-muted-foreground hover:text-primary" />
                      </Button>
                      <Button variant="ghost" size="icon" title="Capabilities" onClick={() => onCapabilities(c)}>
                        <SlidersHorizontal className="size-4 text-muted-foreground hover:text-primary" />
                      </Button>
                      {!c.primary && (
                        <Button variant="ghost" size="icon" title="Set as primary" onClick={() => onSetPrimary(c.id)}>
                          <Star className="size-4 text-muted-foreground" />
                        </Button>
                      )}
                      <Button
                        variant="ghost" size="icon" className="text-red-500 hover:text-red-700"
                        onClick={() => onDelete(c)}
                      >
                        <Trash2 className="size-4" />
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}

function CapabilityDialog({
  account,
  open,
  rows,
  isLoading,
  isSaving,
  onOpenChange,
  onRowsChange,
  onSave,
}: {
  account: ProviderAccount | null;
  open: boolean;
  rows: ProviderCapability[];
  isLoading: boolean;
  isSaving: boolean;
  onOpenChange: (open: boolean) => void;
  onRowsChange: (rows: ProviderCapability[]) => void;
  onSave: () => void;
}) {
  function updateRow(index: number, patch: Partial<ProviderCapability>) {
    onRowsChange(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));
  }

  function toggleFlag(index: number, key: keyof ProviderCapability) {
    updateRow(index, { [key]: !rows[index][key] } as Partial<ProviderCapability>);
  }

  function amount(value: string) {
    return value === '' ? null : Number(value);
  }

  function methodSelectValue(value: string) {
    return PAYMENT_METHOD_OPTIONS.some((option) => option.value === value) ? value : '_custom';
  }

  const displayRows = rows.length > 0 ? rows : [defaultCapability()];

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="h-[80vh] !w-[80vw] !max-w-[80vw] sm:!max-w-[80vw] grid-rows-[auto_minmax(0,1fr)_auto]">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-base">
            <SlidersHorizontal className="size-4" />
            {account ? `${account.label} accepted payment capabilities` : 'Accepted payment capabilities'}
          </DialogTitle>
          <p className="text-sm text-muted-foreground">
            These rows describe what this connector can process. Routing policies choose preferred connector order, then use these capabilities to skip connector steps that cannot handle the payment.
          </p>
        </DialogHeader>

        {isLoading ? (
          <div className="py-10 text-center text-sm text-muted-foreground">Loading capabilities...</div>
        ) : (
          <div className="min-h-0 space-y-3 overflow-y-auto pr-1">
            {displayRows.map((row, index) => (
              <div key={row.id ?? index} className="rounded-lg border bg-white p-3 space-y-3">
                <div className="grid grid-cols-2 md:grid-cols-6 gap-3">
                  <div className="space-y-1 md:col-span-2">
                    <Label>Method</Label>
                    <Select
                      value={methodSelectValue(row.paymentMethodType)}
                      onValueChange={(value) => {
                        if (value && value !== '_custom') {
                          updateRow(index, { paymentMethodType: value });
                        }
                      }}
                    >
                      <SelectTrigger className="w-full">
                        <SelectValue placeholder="Select method" />
                      </SelectTrigger>
                      <SelectContent className="min-w-72">
                        {PAYMENT_METHOD_OPTIONS.map((option) => (
                          <SelectItem key={option.value} value={option.value}>
                            <span className="flex flex-col items-start gap-0.5">
                              <span>{option.label}</span>
                              <span className="text-xs text-muted-foreground">{option.detail}</span>
                            </span>
                          </SelectItem>
                        ))}
                        <SelectItem value="_custom">Other / custom</SelectItem>
                      </SelectContent>
                    </Select>
                    {methodSelectValue(row.paymentMethodType) === '_custom' && (
                      <Input
                        value={row.paymentMethodType}
                        onChange={(e) => updateRow(index, { paymentMethodType: e.target.value })}
                        placeholder="e.g. eps, giropay, wechat_pay"
                      />
                    )}
                    <p className="text-xs leading-5 text-muted-foreground">
                      The checkout payment method this connector can process. Routing policies can prefer this connector, but capabilities decide whether the step is executable for the payment.
                    </p>
                  </div>
                  <div className="space-y-1">
                    <Label>Country</Label>
                    <Input
                      value={row.country ?? ''}
                      onChange={(e) => updateRow(index, { country: e.target.value })}
                      placeholder="US"
                      maxLength={2}
                    />
                  </div>
                  <div className="space-y-1">
                    <Label>Currency</Label>
                    <Input
                      value={row.currency ?? ''}
                      onChange={(e) => updateRow(index, { currency: e.target.value })}
                      placeholder="USD"
                      maxLength={10}
                    />
                  </div>
                  <div className="space-y-1">
                    <Label>Min</Label>
                    <Input
                      type="number"
                      min={0}
                      value={row.minAmount ?? ''}
                      onChange={(e) => updateRow(index, { minAmount: amount(e.target.value) })}
                    />
                  </div>
                  <div className="space-y-1">
                    <Label>Max</Label>
                    <Input
                      type="number"
                      min={0}
                      value={row.maxAmount ?? ''}
                      onChange={(e) => updateRow(index, { maxAmount: amount(e.target.value) })}
                    />
                  </div>
                </div>

                <div className="grid grid-cols-2 md:grid-cols-5 xl:grid-cols-10 gap-2 text-sm">
                  {[
                    ['enabled', 'Enabled'],
                    ['supportsProviderToken', 'Provider token'],
                    ['supportsVaultToken', 'Vault token'],
                    ['supportsNetworkToken', 'Network token'],
                    ['supportsManualCapture', 'Manual capture'],
                    ['supportsRefund', 'Refund'],
                    ['supportsPartialRefund', 'Partial refund'],
                    ['supports3ds', '3DS'],
                    ['supportsRedirect', 'Redirect'],
                    ['supportsInstallments', 'Installments'],
                  ].map(([key, label]) => (
                    <label key={key} className="flex items-center gap-2 rounded-md border px-2 py-1.5">
                      <input
                        type="checkbox"
                        checked={Boolean(row[key as keyof ProviderCapability])}
                        onChange={() => toggleFlag(index, key as keyof ProviderCapability)}
                        className="size-4 rounded"
                      />
                      <span>{label}</span>
                    </label>
                  ))}
                </div>

                <div className="flex justify-end">
                  <Button
                    variant="ghost"
                    size="sm"
                    type="button"
                    disabled={displayRows.length === 1}
                    onClick={() => onRowsChange(rows.filter((_, i) => i !== index))}
                    className="text-red-600 hover:text-red-700"
                  >
                    <X className="size-3.5" /> Remove
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}

        <DialogFooter className="items-center justify-between sm:justify-between">
          <Button
            variant="outline"
            type="button"
            onClick={() => onRowsChange([...displayRows, defaultCapability()])}
          >
            <Plus className="size-4" /> Add row
          </Button>
          <div className="flex items-center gap-2">
            <Button variant="ghost" type="button" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="button" disabled={isSaving || isLoading} onClick={onSave}>
              <Save className="size-4" />
              {isSaving ? 'Saving...' : 'Save'}
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function ConnectorsPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const router = useRouter();
  const qc = useQueryClient();

  const [createOpen, setCreateOpen] = useState(false);
  const [dialogStep, setDialogStep] = useState<'select' | 'configure'>('select');
  const [selectedProvider, setSelectedProvider] = useState<Provider>('STRIPE');
  const [confirmDelete, setConfirmDelete] = useState<ProviderAccount | null>(null);
  const [capabilityAccount, setCapabilityAccount] = useState<ProviderAccount | null>(null);
  const [capabilityRows, setCapabilityRows] = useState<ProviderCapability[]>([]);

  const { data: connectors = [], isLoading } = useQuery<ProviderAccount[]>({
    queryKey: ['connectors', activeMerchantId, mode],
    queryFn: () => apiFetch<ProviderAccount[]>(`/api/v1/merchants/${activeMerchantId}/connectors?mode=${mode}`),
    enabled: !!activeMerchantId,
  });

  const activeProviders = PROVIDERS.filter((p) => connectors.some((c) => c.provider === p));
  const [brandOrder, setBrandOrder] = useState<Provider[]>([]);

  // Server returns connectors pre-sorted by displayOrder; key on that order so we
  // only resync brandOrder when the saved order actually changes (avoids re-running
  // on every render while `connectors` defaults to a fresh `[]`).
  const savedOrderKey = connectors.map((c) => c.provider).join(',');

  useEffect(() => {
    const order: Provider[] = [];
    for (const c of connectors) {
      const provider = c.provider as Provider;
      if (!order.includes(provider)) order.push(provider);
    }
    setBrandOrder(order);
  }, [savedOrderKey]);

  const orderedProviders = (() => {
    const known = brandOrder.filter((p) => activeProviders.includes(p));
    const added = activeProviders.filter((p) => !known.includes(p));
    return [...known, ...added];
  })();

  const { register, handleSubmit, reset, setValue, watch, formState: { errors } } = useForm<CreateForm>({
    resolver: zodResolver(createSchema) as any,
    defaultValues: {
      provider: 'STRIPE',
      mode: mode as 'TEST' | 'LIVE',
      primary: true,
      weight: 1,
      simulatorSuccessRatePercent: 100,
    },
  });

  const { data: capabilities = [], isLoading: capabilitiesLoading } = useQuery<ProviderCapability[]>({
    queryKey: ['connector-capabilities', activeMerchantId, capabilityAccount?.id],
    queryFn: () =>
      apiFetch<ProviderCapability[]>(
        `/api/v1/merchants/${activeMerchantId}/connectors/${capabilityAccount!.id}/capabilities`,
      ),
    enabled: !!activeMerchantId && !!capabilityAccount,
  });

  useEffect(() => {
    if (!capabilityAccount) return;
    setCapabilityRows(capabilities.length > 0 ? capabilities : [defaultCapability()]);
  }, [capabilities, capabilityAccount]);

  const createMutation = useMutation({
    mutationFn: (data: CreateForm) =>
      apiFetch<ProviderAccount>(`/api/v1/merchants/${activeMerchantId}/connectors`, {
        method: 'POST',
        body: JSON.stringify({ ...data, mode }),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['connectors', activeMerchantId, mode] });
      closeDialog();
      toast.success('Connector added');
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string };
      toast.error(e.detail ?? 'Failed to add connector');
    },
  });

  const setPrimaryMutation = useMutation({
    mutationFn: (accountId: string) =>
      apiFetch<ProviderAccount>(`/api/v1/merchants/${activeMerchantId}/connectors/${accountId}/set-primary`, { method: 'POST' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['connectors', activeMerchantId, mode] });
      toast.success('Primary connector updated');
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (accountId: string) =>
      apiFetch(`/api/v1/merchants/${activeMerchantId}/connectors/${accountId}`, { method: 'DELETE' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['connectors', activeMerchantId, mode] });
      setConfirmDelete(null);
      toast.success('Connector removed');
    },
  });

  const reorderMutation = useMutation({
    mutationFn: (items: { provider: string; displayOrder: number }[]) =>
      apiFetch(`/api/v1/merchants/${activeMerchantId}/connectors/reorder`, {
        method: 'PUT',
        body: JSON.stringify({ items }),
      }),
    onError: () => toast.error('Failed to save order'),
  });

  const saveCapabilitiesMutation = useMutation({
    mutationFn: () =>
      apiFetch<ProviderCapability[]>(
        `/api/v1/merchants/${activeMerchantId}/connectors/${capabilityAccount!.id}/capabilities`,
        {
          method: 'PUT',
          body: JSON.stringify(capabilityPayload(capabilityRows)),
        },
      ),
    onSuccess: (saved) => {
      setCapabilityRows(saved.length > 0 ? saved : [defaultCapability()]);
      qc.invalidateQueries({ queryKey: ['connector-capabilities', activeMerchantId, capabilityAccount?.id] });
      toast.success('Capabilities saved');
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string };
      toast.error(e.detail ?? 'Failed to save capabilities');
    },
  });

  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 5 } }));

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    const oldIndex = orderedProviders.indexOf(active.id as Provider);
    const newIndex = orderedProviders.indexOf(over.id as Provider);
    const newOrder = arrayMove(orderedProviders, oldIndex, newIndex);
    setBrandOrder(newOrder);
    reorderMutation.mutate(newOrder.map((provider, idx) => ({ provider, displayOrder: idx })));
  }

  function openDialog() {
    setDialogStep('select');
    setSelectedProvider('STRIPE');
    reset({ provider: 'STRIPE', mode: mode as 'TEST' | 'LIVE', primary: true, weight: 1, simulatorSuccessRatePercent: 100 });
    setCreateOpen(true);
  }

  function closeDialog() {
    setCreateOpen(false);
    reset();
  }

  function confirmProvider() {
    setValue('provider', selectedProvider);
    setDialogStep('configure');
  }

  const currentMode = mode as 'TEST' | 'LIVE';
  const selectableProviders = currentMode === 'TEST'
    ? PROVIDERS.filter((provider) => provider !== 'SIMULATOR' || SIMULATOR_PROVIDER_ENABLED)
    : PROVIDERS.filter((provider) => provider !== 'SIMULATOR');

  return (
    <div className="space-y-6 max-w-3xl">
      {/* Page header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Connectors</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Connect payment provider accounts. Real provider credentials are encrypted at rest with AES-256.
          </p>
        </div>
        <Button onClick={openDialog}>
          <Plus className="size-4 mr-1" /> Add Connector
        </Button>
      </div>

      {/* Empty state */}
      {connectors.length === 0 && !isLoading && (
        <Card>
          <CardContent className="py-12 text-center">
            <p className="text-muted-foreground text-sm">No connectors yet.</p>
            <p className="text-muted-foreground text-sm mt-1">
              Add a provider account to start processing payments.
            </p>
            <Button className="mt-4" onClick={openDialog}>
              <Plus className="size-4 mr-1" /> Add Connector
            </Button>
          </CardContent>
        </Card>
      )}

      {/* Sortable connector list */}
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <SortableContext items={orderedProviders} strategy={verticalListSortingStrategy}>
          <div className="space-y-4">
            {orderedProviders.map((provider) => (
              <SortableProviderCard
                key={provider}
                provider={provider}
                connectors={connectors}
                onSetPrimary={(id) => setPrimaryMutation.mutate(id)}
                onDelete={(account) => setConfirmDelete(account)}
                onPreview={(id) => router.push(`/connectors/${id}/preview`)}
                onCapabilities={(account) => setCapabilityAccount(account)}
              />
            ))}
          </div>
        </SortableContext>
      </DndContext>

      <CapabilityDialog
        account={capabilityAccount}
        open={!!capabilityAccount}
        rows={capabilityRows}
        isLoading={capabilitiesLoading}
        isSaving={saveCapabilitiesMutation.isPending}
        onRowsChange={setCapabilityRows}
        onOpenChange={(open) => {
          if (!open) {
            setCapabilityAccount(null);
            setCapabilityRows([]);
          }
        }}
        onSave={() => saveCapabilitiesMutation.mutate()}
      />

      {/* ── Delete confirmation ────────────────────────────────────────────── */}
      <Dialog open={!!confirmDelete} onOpenChange={(open) => { if (!open) setConfirmDelete(null); }}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>Remove connector</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground">
            Remove <span className="font-medium text-foreground">&ldquo;{confirmDelete?.label}&rdquo;</span>? This cannot be undone.
          </p>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setConfirmDelete(null)}>Cancel</Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={() => confirmDelete && deleteMutation.mutate(confirmDelete.id)}
            >
              Remove
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ── Add connector — two-step dialog ───────────────────────────────── */}
      <Dialog open={createOpen} onOpenChange={(open) => { if (!open) closeDialog(); }}>

        {/* ── Step 1: Provider picker ── */}
        {dialogStep === 'select' && (
          <DialogContent className="h-[80vh] !w-[80vw] !max-w-[80vw] sm:!max-w-[80vw] grid-rows-[auto_minmax(0,1fr)_auto]">
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2 text-lg">
                Choose a payment provider
                <span className={`text-xs px-2 py-0.5 rounded font-medium ${currentMode === 'TEST' ? 'bg-yellow-100 text-yellow-700' : 'bg-green-100 text-green-700'}`}>
                  {currentMode}
                </span>
              </DialogTitle>
              <p className="text-sm text-muted-foreground pt-1">
                Select the gateway you want to connect. You can add multiple accounts per provider.
              </p>
            </DialogHeader>

            <div className="flex flex-col gap-3 py-2">
                  {selectableProviders.map((p) => (
                <ProviderPickerCard
                  key={p}
                  provider={p}
                  selected={selectedProvider === p}
                  onClick={() => setSelectedProvider(p)}
                />
              ))}
            </div>

            <DialogFooter className="pt-2">
              <Button variant="ghost" onClick={closeDialog}>Cancel</Button>
              <Button onClick={confirmProvider}>
                Continue with {PROVIDER_META[selectedProvider].label} →
              </Button>
            </DialogFooter>
          </DialogContent>
        )}

        {/* ── Step 2: Credential form ── */}
        {dialogStep === 'configure' && (
          <DialogContent className="h-[80vh] !w-[80vw] !max-w-[80vw] sm:!max-w-[80vw] grid-rows-[auto_minmax(0,1fr)_auto]">
            <DialogHeader>
              {/* Back button + provider identity */}
              <button
                type="button"
                onClick={() => setDialogStep('select')}
                className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground mb-3 -mt-1 w-fit transition-colors"
              >
                <ArrowLeft className="size-3.5" /> Back
              </button>

              <div className="flex items-center gap-3">
                <div className="size-10 flex items-center justify-center rounded-xl bg-white border border-gray-200 shadow-sm flex-shrink-0">
                  <div className="size-6 [&>svg]:!size-6">{PROVIDER_BRAND[selectedProvider]?.icon}</div>
                </div>
                <div>
                  <DialogTitle className="text-base flex items-center gap-2">
                    Connect {PROVIDER_META[selectedProvider].label}
                    <span className={`text-xs px-2 py-0.5 rounded font-medium ${currentMode === 'TEST' ? 'bg-yellow-100 text-yellow-700' : 'bg-green-100 text-green-700'}`}>
                      {currentMode}
                    </span>
                  </DialogTitle>
                  <p className="text-xs text-muted-foreground mt-0.5">{PROVIDER_META[selectedProvider].tagline}</p>
                </div>
              </div>
            </DialogHeader>

            <form
              onSubmit={handleSubmit((d) => { setValue('mode', currentMode); createMutation.mutate(d); })}
              className="space-y-4 pt-1"
            >
              <input type="hidden" {...register('provider')} value={selectedProvider} />
              <input type="hidden" {...register('mode')} value={currentMode} />

              <div className="space-y-1">
                <Label>Label</Label>
                <Input placeholder="e.g. Stripe US Sandbox" {...register('label')} />
                {errors.label && <p className="text-xs text-red-500">{errors.label.message}</p>}
              </div>

              {/* ── Stripe fields ── */}
              {selectedProvider === 'STRIPE' && (
                <>
                  <div className="space-y-1">
                    <Label>Secret Key</Label>
                    <Input type="password" placeholder={currentMode === 'TEST' ? 'sk_test_…' : 'sk_live_…'} {...register('secretKey')} />
                    {errors.secretKey && <p className="text-xs text-red-500">{errors.secretKey.message}</p>}
                    <p className="text-xs text-muted-foreground">AES-256 encrypted at rest. Never returned via API.</p>
                  </div>
                  <div className="space-y-1">
                    <Label>Publishable Key <span className="text-muted-foreground">(optional)</span></Label>
                    <Input placeholder={currentMode === 'TEST' ? 'pk_test_…' : 'pk_live_…'} {...register('publishableKey')} />
                    <p className="text-xs text-muted-foreground">Required for the hosted checkout page to load Stripe.js.</p>
                  </div>
                </>
              )}

              {/* ── Square fields ── */}
              {selectedProvider === 'SQUARE' && (
                <>
                  <div className="space-y-1">
                    <Label>Access Token</Label>
                    <Input type="password" placeholder={currentMode === 'TEST' ? 'EAAAEHy…' : 'EAAA…'} {...register('accessToken')} />
                    {errors.accessToken && <p className="text-xs text-red-500">{errors.accessToken.message}</p>}
                    <p className="text-xs text-muted-foreground">Server-side auth. AES-256 encrypted at rest.</p>
                  </div>
                  <div className="space-y-1">
                    <Label>Application ID</Label>
                    <Input placeholder={currentMode === 'TEST' ? 'sandbox-sq0idb-…' : 'sq0idp-…'} {...register('applicationId')} />
                    {errors.applicationId && <p className="text-xs text-red-500">{errors.applicationId.message}</p>}
                    <p className="text-xs text-muted-foreground">Client-side identifier for the Square Web Payments SDK.</p>
                  </div>
                  <div className="space-y-1">
                    <Label>Location ID</Label>
                    <Input placeholder="L…" {...register('locationId')} />
                    {errors.locationId && <p className="text-xs text-red-500">{errors.locationId.message}</p>}
                    <p className="text-xs text-muted-foreground">Square location where payments will be processed.</p>
                  </div>
                </>
              )}

              {/* ── Braintree fields ── */}
              {selectedProvider === 'BRAINTREE' && (
                <>
                  <div className="space-y-1">
                    <Label>Merchant ID</Label>
                    <Input placeholder="e.g. abc123xyz" {...register('btMerchantId')} />
                    {errors.btMerchantId && <p className="text-xs text-red-500">{errors.btMerchantId.message}</p>}
                    <p className="text-xs text-muted-foreground">Found in Braintree Control Panel → Account → My User.</p>
                  </div>
                  <div className="space-y-1">
                    <Label>Public Key</Label>
                    <Input placeholder="e.g. pkey_…" {...register('btPublicKey')} />
                    {errors.btPublicKey && <p className="text-xs text-red-500">{errors.btPublicKey.message}</p>}
                    <p className="text-xs text-muted-foreground">Used with private key for server-side API auth.</p>
                  </div>
                  <div className="space-y-1">
                    <Label>Private Key</Label>
                    <Input type="password" placeholder="••••••••" {...register('btPrivateKey')} />
                    {errors.btPrivateKey && <p className="text-xs text-red-500">{errors.btPrivateKey.message}</p>}
                    <p className="text-xs text-muted-foreground">AES-256 encrypted at rest. Never returned via API.</p>
                  </div>
                </>
              )}

              {/* ── Mollie fields ── */}
              {selectedProvider === 'MOLLIE' && (
                <div className="space-y-1">
                  <Label>API Key</Label>
                  <Input
                    type="password"
                    placeholder={currentMode === 'TEST' ? 'test_…' : 'live_…'}
                    {...register('mollieApiKey')}
                  />
                  {errors.mollieApiKey && <p className="text-xs text-red-500">{errors.mollieApiKey.message}</p>}
                  <p className="text-xs text-muted-foreground">
                    Found in Mollie Dashboard → Developers → API keys. AES-256 encrypted at rest.
                  </p>
                </div>
              )}

              {/* ── Mason Simulator fields ── */}
              {selectedProvider === 'SIMULATOR' && (
                <div className="rounded-lg border border-blue-200 bg-blue-50/60 p-3 text-sm text-blue-900 space-y-3">
                  <p>
                    Mason Simulator has no external credentials. It is TEST-only and uses backend benchmark
                    settings for latency and timeouts.
                  </p>
                  <div className="space-y-1">
                    <Label className="text-blue-950">Success Rate <span className="text-blue-700">(%)</span></Label>
                    <Input
                      type="number"
                      min={0}
                      max={100}
                      step="0.1"
                      placeholder="95"
                      {...register('simulatorSuccessRatePercent')}
                    />
                    {errors.simulatorSuccessRatePercent && (
                      <p className="text-xs text-red-600">{errors.simulatorSuccessRatePercent.message}</p>
                    )}
                    <p className="text-xs text-blue-800">
                      Use values below 100 to generate synthetic PSP declines for routing, alerting, and dashboard tests.
                    </p>
                  </div>
                </div>
              )}

              {/* ── Fee configuration (Phase 3.5) ── */}
              <div className="rounded-lg border border-dashed border-gray-200 bg-gray-50/50 p-3 space-y-3">
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Fee Configuration <span className="normal-case font-normal">(optional — used for cost-aware routing)</span></p>
                <div className="grid grid-cols-2 gap-3">
                  <div className="space-y-1">
                    <Label>Fixed Fee <span className="text-muted-foreground">(cents)</span></Label>
                    <Input type="number" min={0} placeholder="30" {...register('fixedFeeCents')} />
                    <p className="text-xs text-muted-foreground">e.g. 30 = $0.30 per transaction</p>
                  </div>
                  <div className="space-y-1">
                    <Label>Rate <span className="text-muted-foreground">(basis points)</span></Label>
                    <Input type="number" min={0} placeholder="290" {...register('rateBps')} />
                    <p className="text-xs text-muted-foreground">e.g. 290 = 2.90%</p>
                  </div>
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <Label>Weight <span className="text-muted-foreground">(1–100)</span></Label>
                  <Input type="number" min={1} max={100} placeholder="1" {...register('weight')} />
                </div>
                <div className="flex items-end pb-1">
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input type="checkbox" {...register('primary')} className="size-4 rounded" />
                    <span className="text-sm">Set as primary</span>
                  </label>
                </div>
              </div>

              <DialogFooter>
                <Button variant="ghost" type="button" onClick={closeDialog}>Cancel</Button>
                <Button type="submit" disabled={createMutation.isPending}>
                  {createMutation.isPending ? 'Connecting…' : 'Connect'}
                </Button>
              </DialogFooter>
            </form>
          </DialogContent>
        )}
      </Dialog>
    </div>
  );
}
