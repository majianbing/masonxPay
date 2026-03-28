'use client';

import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { toast } from 'sonner';
import { Plus, Trash2, GripVertical, ToggleLeft, ToggleRight } from 'lucide-react';
import {
  DndContext, closestCenter, KeyboardSensor, PointerSensor,
  useSensor, useSensors, DragEndEvent,
} from '@dnd-kit/core';
import {
  arrayMove, SortableContext, sortableKeyboardCoordinates,
  verticalListSortingStrategy, useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent } from '@/components/ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog';

interface RoutingRule {
  id: string;
  priority: number;
  enabled: boolean;
  weight: number;
  targetProvider: string;
  fallbackProvider?: string;
  currencies: string[];
  countryCodes: string[];
  paymentMethodTypes: string[];
  amountMin: number | null;
  amountMax: number | null;
}

const schema = z.object({
  targetProvider: z.string().min(1, 'Required'),
  fallbackProvider: z.string().optional(),
  currencies: z.string().optional(),
  countryCodes: z.string().optional(),
  paymentMethodTypes: z.string().optional(),
  amountMin: z.string().optional(),
  amountMax: z.string().optional(),
  weight: z.string().optional(),
});
type FormData = z.infer<typeof schema>;

export default function RoutingRulesPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const qc = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [rules, setRules] = useState<RoutingRule[]>([]);

  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const { isLoading } = useQuery<RoutingRule[]>({
    queryKey: ['routing-rules', activeMerchantId],
    queryFn: async () => {
      const data = await apiFetch<RoutingRule[]>(`/api/v1/merchants/${activeMerchantId}/routing-rules`);
      setRules(data.sort((a, b) => a.priority - b.priority));
      return data;
    },
    enabled: !!activeMerchantId,
  });

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const { register, handleSubmit, reset, formState: { errors } } = useForm<FormData>({
    resolver: zodResolver(schema) as any,
    defaultValues: { weight: '1' },
  });

  const createMutation = useMutation({
    mutationFn: (data: FormData) =>
      apiFetch(`/api/v1/merchants/${activeMerchantId}/routing-rules`, {
        method: 'POST',
        body: JSON.stringify({
          targetProvider: data.targetProvider.toUpperCase(),
          fallbackProvider: data.fallbackProvider ? data.fallbackProvider.toUpperCase() : undefined,
          priority: rules.length + 1,
          enabled: true,
          weight: data.weight ? parseInt(data.weight) || 1 : 1,
          currencies: data.currencies ? data.currencies.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean) : [],
          countryCodes: data.countryCodes ? data.countryCodes.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean) : [],
          paymentMethodTypes: data.paymentMethodTypes ? data.paymentMethodTypes.split(',').map((s) => s.trim().toLowerCase()).filter(Boolean) : [],
          amountMin: data.amountMin ? parseInt(data.amountMin) : undefined,
          amountMax: data.amountMax ? parseInt(data.amountMax) : undefined,
        }),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['routing-rules', activeMerchantId] });
      setCreateOpen(false);
      reset({ weight: '1' });
      toast.success('Rule created');
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string };
      toast.error(e.detail ?? 'Failed to create rule');
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) =>
      apiFetch(`/api/v1/merchants/${activeMerchantId}/routing-rules/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['routing-rules', activeMerchantId] });
      toast.success('Rule deleted');
    },
  });

  const updateMutation = useMutation({
    mutationFn: (rule: Partial<RoutingRule> & { id: string }) =>
      apiFetch(`/api/v1/merchants/${activeMerchantId}/routing-rules/${rule.id}`, {
        method: 'PUT',
        body: JSON.stringify(rule),
      }),
  });

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (!over || active.id === over.id) return;

    setRules((prev) => {
      const oldIdx = prev.findIndex((r) => r.id === active.id);
      const newIdx = prev.findIndex((r) => r.id === over.id);
      const next = arrayMove(prev, oldIdx, newIdx);
      next.forEach((r, i) => {
        if (r.priority !== i + 1) {
          updateMutation.mutate({ id: r.id, priority: i + 1, targetProvider: r.targetProvider, enabled: r.enabled, weight: r.weight });
        }
      });
      return next;
    });
  }

  function handleToggleEnabled(rule: RoutingRule) {
    const updated = { ...rule, enabled: !rule.enabled };
    setRules((prev) => prev.map((r) => (r.id === rule.id ? updated : r)));
    updateMutation.mutate(
      { id: rule.id, priority: rule.priority, targetProvider: rule.targetProvider, enabled: !rule.enabled, weight: rule.weight },
      {
        onError: () => {
          setRules((prev) => prev.map((r) => (r.id === rule.id ? rule : r)));
          toast.error('Failed to update rule');
        },
      },
    );
  }

  return (
    <div className="space-y-6 max-w-2xl">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Routing Rules</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Drag to reorder priority. When multiple rules match, traffic is split proportionally by weight.
          </p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="size-4 mr-1" /> Add Rule
        </Button>
      </div>

      {isLoading ? (
        <p className="text-muted-foreground">Loading…</p>
      ) : rules.length === 0 ? (
        <Card>
          <CardContent className="py-12 text-center text-muted-foreground">
            No routing rules. Default provider (Stripe) will be used.
          </CardContent>
        </Card>
      ) : (
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
          <SortableContext items={rules.map((r) => r.id)} strategy={verticalListSortingStrategy}>
            <div className="space-y-2">
              {rules.map((rule, i) => (
                <SortableRuleCard
                  key={rule.id}
                  rule={rule}
                  index={i}
                  onDelete={() => {
                    if (confirm('Delete this rule?')) deleteMutation.mutate(rule.id);
                  }}
                  onToggleEnabled={() => handleToggleEnabled(rule)}
                />
              ))}
            </div>
          </SortableContext>
        </DndContext>
      )}

      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>Create Routing Rule</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => createMutation.mutate(d))} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1">
                <Label>Target Provider <span className="text-red-500">*</span></Label>
                <Input placeholder="STRIPE" {...register('targetProvider')} />
                {errors.targetProvider && <p className="text-xs text-red-500">{errors.targetProvider.message}</p>}
              </div>
              <div className="space-y-1">
                <Label>Fallback Provider</Label>
                <Input placeholder="ADYEN (optional)" {...register('fallbackProvider')} />
              </div>
            </div>
            <div className="space-y-1">
              <Label>Weight</Label>
              <Input type="number" min="1" placeholder="1" {...register('weight')} />
              <p className="text-xs text-muted-foreground">
                Traffic share relative to other matching rules (e.g. 7 + 3 = 70% / 30% split)
              </p>
            </div>
            <div className="space-y-1">
              <Label>Currencies (comma-separated, optional)</Label>
              <Input placeholder="USD, EUR" {...register('currencies')} />
            </div>
            <div className="space-y-1">
              <Label>Country Codes (comma-separated, optional)</Label>
              <Input placeholder="US, GB, DE" {...register('countryCodes')} />
            </div>
            <div className="space-y-1">
              <Label>Payment Method Types (comma-separated, optional)</Label>
              <Input placeholder="card, bank_transfer" {...register('paymentMethodTypes')} />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1">
                <Label>Min Amount (cents)</Label>
                <Input type="number" placeholder="0" {...register('amountMin')} />
              </div>
              <div className="space-y-1">
                <Label>Max Amount (cents)</Label>
                <Input type="number" placeholder="999999" {...register('amountMax')} />
              </div>
            </div>
            <DialogFooter>
              <Button variant="ghost" type="button" onClick={() => { setCreateOpen(false); reset({ weight: '1' }); }}>Cancel</Button>
              <Button type="submit" disabled={createMutation.isPending}>Create</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function SortableRuleCard({
  rule, index, onDelete, onToggleEnabled,
}: {
  rule: RoutingRule; index: number; onDelete: () => void; onToggleEnabled: () => void;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: rule.id,
  });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <div ref={setNodeRef} style={style} {...attributes}>
      <Card className={rule.enabled ? '' : 'opacity-60'}>
        <CardContent className="p-4 flex items-start gap-3">
          <button {...listeners} className="text-muted-foreground hover:text-foreground cursor-grab active:cursor-grabbing mt-0.5">
            <GripVertical className="size-4" />
          </button>
          <div className="flex items-center justify-center size-6 rounded-full bg-gray-100 text-xs font-medium shrink-0 mt-0.5">
            {index + 1}
          </div>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <p className="font-medium text-sm">{rule.targetProvider}</p>
              {rule.fallbackProvider && (
                <span className="text-xs text-muted-foreground">→ {rule.fallbackProvider}</span>
              )}
              <span className="text-xs bg-blue-50 text-blue-700 px-1.5 py-0.5 rounded font-medium">
                w:{rule.weight ?? 1}
              </span>
            </div>
            <div className="flex gap-1 mt-1.5 flex-wrap">
              {rule.currencies?.length > 0 && rule.currencies.map((c) => (
                <span key={c} className="text-xs bg-gray-100 px-1.5 py-0.5 rounded">{c}</span>
              ))}
              {rule.countryCodes?.length > 0 && rule.countryCodes.map((c) => (
                <span key={c} className="text-xs bg-purple-50 text-purple-700 px-1.5 py-0.5 rounded">{c}</span>
              ))}
              {rule.paymentMethodTypes?.length > 0 && rule.paymentMethodTypes.map((t) => (
                <span key={t} className="text-xs bg-orange-50 text-orange-700 px-1.5 py-0.5 rounded">{t}</span>
              ))}
              {rule.amountMin != null && (
                <span className="text-xs text-muted-foreground">≥ {rule.amountMin / 100}</span>
              )}
              {rule.amountMax != null && (
                <span className="text-xs text-muted-foreground">≤ {rule.amountMax / 100}</span>
              )}
            </div>
          </div>
          <button
            onClick={onToggleEnabled}
            className="text-muted-foreground hover:text-foreground shrink-0 mt-0.5"
            title={rule.enabled ? 'Disable rule' : 'Enable rule'}
          >
            {rule.enabled
              ? <ToggleRight className="size-5 text-green-600" />
              : <ToggleLeft className="size-5" />}
          </button>
          <Button variant="ghost" size="icon" className="text-red-500 shrink-0 -mr-1" onClick={onDelete}>
            <Trash2 className="size-4" />
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
