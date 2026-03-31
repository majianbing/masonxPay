'use client';

import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { toast } from 'sonner';
import { Plus, Trash2, Star, PlayCircle, GripVertical } from 'lucide-react';
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
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
  arrayMove,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

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
  createdAt: string;
}

const PROVIDERS = ['STRIPE', 'SQUARE', 'BRAINTREE'] as const;
type Provider = typeof PROVIDERS[number];

const PROVIDER_META: Record<Provider, { label: string; description: string }> = {
  STRIPE: {
    label: 'Stripe',
    description: 'Cards, iDEAL, Amazon Pay, Link, Sofort, and other redirect-based methods via Stripe.js.',
  },
  SQUARE: {
    label: 'Square',
    description: 'Card payments and Google Pay via the Square Web Payments SDK.',
  },
  BRAINTREE: {
    label: 'Braintree',
    description: 'PayPal-owned gateway. Supports cards, PayPal, Venmo, and local payment methods.',
  },
};

const createSchema = z.object({
  provider: z.enum(PROVIDERS),
  mode: z.enum(['TEST', 'LIVE']),
  label: z.string().min(1, 'Label required'),
  primary: z.boolean(),
  weight: z.coerce.number().int().min(1).max(100).default(1),
  // Stripe
  secretKey: z.string().optional(),
  publishableKey: z.string().optional(),
  // Square
  accessToken: z.string().optional(),
  applicationId: z.string().optional(),
  locationId: z.string().optional(),
  // Braintree
  btMerchantId: z.string().optional(),
  btPublicKey: z.string().optional(),
  btPrivateKey: z.string().optional(),
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
});
type CreateForm = z.infer<typeof createSchema>;

// ─── Sortable brand card ──────────────────────────────────────────────────────

function SortableProviderCard({
  provider,
  connectors,
  onSetPrimary,
  onDelete,
  onPreview,
}: {
  provider: Provider;
  connectors: ProviderAccount[];
  onSetPrimary: (id: string) => void;
  onDelete: (account: ProviderAccount) => void;
  onPreview: (id: string) => void;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: provider });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <div ref={setNodeRef} style={style}>
      <Card>
        <CardHeader className="pb-3">
          <div className="flex items-start gap-2">
            <button
              {...attributes}
              {...listeners}
              className="mt-0.5 cursor-grab active:cursor-grabbing text-muted-foreground hover:text-foreground"
              aria-label="Drag to reorder"
            >
              <GripVertical className="size-4" />
            </button>
            <div>
              <CardTitle className="text-base">{PROVIDER_META[provider].label}</CardTitle>
              <CardDescription>{PROVIDER_META[provider].description}</CardDescription>
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
                <tr key={c.id} className="border-b last:border-0">
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
                    <span className={`text-xs px-2 py-0.5 rounded-full ${c.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>
                      {c.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground text-xs">{format(new Date(c.createdAt), 'MMM d, yyyy')}</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1 justify-end">
                      <Button variant="ghost" size="icon" title="Preview cashier" onClick={() => onPreview(c.id)}>
                        <PlayCircle className="size-4 text-muted-foreground hover:text-primary" />
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

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function ConnectorsPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const router = useRouter();
  const qc = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<ProviderAccount | null>(null);

  const { data: connectors = [], isLoading } = useQuery<ProviderAccount[]>({
    queryKey: ['connectors', activeMerchantId, mode],
    queryFn: () => apiFetch<ProviderAccount[]>(`/api/v1/merchants/${activeMerchantId}/connectors?mode=${mode}`),
    enabled: !!activeMerchantId,
  });

  // Brand order — seeded from active providers, manipulated by drag
  const activeProviders = PROVIDERS.filter((p) => connectors.some((c) => c.provider === p));
  const [brandOrder, setBrandOrder] = useState<Provider[]>([]);

  // Merge: use local order for known brands, append newly active brands at the end
  const orderedProviders = (() => {
    const known = brandOrder.filter((p) => activeProviders.includes(p));
    const added = activeProviders.filter((p) => !known.includes(p));
    return [...known, ...added];
  })();

  const { register, handleSubmit, reset, setValue, watch, formState: { errors } } = useForm<CreateForm>({
    resolver: zodResolver(createSchema) as any,
    defaultValues: { provider: 'STRIPE', mode: mode as 'TEST' | 'LIVE', primary: true, weight: 1 },
  });

  const createMutation = useMutation({
    mutationFn: (data: CreateForm) =>
      apiFetch<ProviderAccount>(`/api/v1/merchants/${activeMerchantId}/connectors`, {
        method: 'POST',
        body: JSON.stringify({ ...data, mode }),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['connectors', activeMerchantId, mode] });
      setCreateOpen(false);
      reset();
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

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
  );

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (!over || active.id === over.id) return;

    const oldIndex = orderedProviders.indexOf(active.id as Provider);
    const newIndex = orderedProviders.indexOf(over.id as Provider);
    const newOrder = arrayMove(orderedProviders, oldIndex, newIndex);
    setBrandOrder(newOrder);

    reorderMutation.mutate(
      newOrder.map((provider, idx) => ({ provider, displayOrder: idx })),
    );
  }

  const providerValue = watch('provider') as Provider;
  const currentMode = mode as 'TEST' | 'LIVE';

  return (
    <div className="space-y-6 max-w-3xl">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Connectors</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Connect your own payment provider accounts. Credentials are encrypted at rest with AES-256.
          </p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="size-4 mr-1" /> Add Connector
        </Button>
      </div>

      {connectors.length === 0 && !isLoading && (
        <Card>
          <CardContent className="py-12 text-center">
            <p className="text-muted-foreground text-sm">No connectors yet.</p>
            <p className="text-muted-foreground text-sm mt-1">Add a Stripe, Square, or Braintree account to start processing payments.</p>
            <Button className="mt-4" onClick={() => setCreateOpen(true)}>
              <Plus className="size-4 mr-1" /> Add Connector
            </Button>
          </CardContent>
        </Card>
      )}

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
              />
            ))}
          </div>
        </SortableContext>
      </DndContext>

      {/* ── Delete confirmation dialog ── */}
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

      {/* ── Add connector dialog ── */}
      <Dialog open={createOpen} onOpenChange={(open) => { setCreateOpen(open); if (!open) reset(); }}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              Add Connector
              <span className={`text-xs px-2 py-0.5 rounded font-medium ${currentMode === 'TEST' ? 'bg-yellow-100 text-yellow-700' : 'bg-green-100 text-green-700'}`}>
                {currentMode}
              </span>
            </DialogTitle>
          </DialogHeader>

          <form onSubmit={handleSubmit((d) => { setValue('mode', currentMode); createMutation.mutate(d); })} className="space-y-4">
            <input type="hidden" {...register('mode')} value={currentMode} />

            <div className="space-y-1">
              <Label>Provider</Label>
              <Select value={providerValue} onValueChange={(v) => setValue('provider', v as Provider)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="STRIPE">Stripe</SelectItem>
                  <SelectItem value="SQUARE">Square</SelectItem>
                  <SelectItem value="BRAINTREE">Braintree</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1">
              <Label>Label</Label>
              <Input placeholder="e.g. Square US Sandbox" {...register('label')} />
              {errors.label && <p className="text-xs text-red-500">{errors.label.message}</p>}
            </div>

            {/* ── Stripe fields ── */}
            {providerValue === 'STRIPE' && (
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
            {providerValue === 'SQUARE' && (
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
            {providerValue === 'BRAINTREE' && (
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
              <Button variant="ghost" type="button" onClick={() => setCreateOpen(false)}>Cancel</Button>
              <Button type="submit" disabled={createMutation.isPending}>Add Connector</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
