'use client';

import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { toast } from 'sonner';
import { Plus, Trash2, RefreshCw, Copy, ChevronDown, ChevronUp } from 'lucide-react';
import { format } from 'date-fns';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog';
import { Badge } from '@/components/ui/badge';

interface WebhookEndpoint {
  id: string;
  url: string;
  description: string;
  status: string;
  subscribedEvents: string[];
  signingSecret: string;
  createdAt: string;
}

interface WebhookDelivery {
  id: string;
  gatewayEventId: string;
  status: string;
  httpStatus: number | null;
  responseBody: string | null;
  attemptCount: number;
  lastAttemptedAt: string | null;
  createdAt: string;
}

const ALL_EVENTS = [
  'payment_intent.succeeded',
  'payment_intent.failed',
  'payment_intent.canceled',
];

const schema = z.object({
  url: z.string().url('Must be a valid URL'),
  description: z.string().optional(),
});
type FormData = z.infer<typeof schema>;

export default function WebhooksPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const qc = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [selectedEvents, setSelectedEvents] = useState<string[]>(ALL_EVENTS);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [revealSecret, setRevealSecret] = useState<string | null>(null);

  const { data: endpoints = [], isLoading } = useQuery<WebhookEndpoint[]>({
    queryKey: ['webhook-endpoints', activeMerchantId],
    queryFn: () =>
      apiFetch<WebhookEndpoint[]>(`/api/v1/merchants/${activeMerchantId}/webhook-endpoints`),
    enabled: !!activeMerchantId,
  });

  const { register, handleSubmit, reset, formState: { errors } } = useForm<FormData>({
    resolver: zodResolver(schema),
  });

  const createMutation = useMutation({
    mutationFn: (data: FormData) =>
      apiFetch<WebhookEndpoint>(`/api/v1/merchants/${activeMerchantId}/webhook-endpoints`, {
        method: 'POST',
        body: JSON.stringify({ ...data, subscribedEvents: selectedEvents }),
      }),
    onSuccess: (ep) => {
      qc.invalidateQueries({ queryKey: ['webhook-endpoints', activeMerchantId] });
      setCreateOpen(false);
      reset();
      setRevealSecret(ep.signingSecret);
      toast.success('Webhook endpoint created');
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string };
      toast.error(e.detail ?? 'Failed to create endpoint');
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) =>
      apiFetch(`/api/v1/merchants/${activeMerchantId}/webhook-endpoints/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['webhook-endpoints', activeMerchantId] });
      toast.success('Endpoint deleted');
    },
  });

  const rotateMutation = useMutation({
    mutationFn: (id: string) =>
      apiFetch<WebhookEndpoint>(`/api/v1/merchants/${activeMerchantId}/webhook-endpoints/${id}/rotate-secret`, {
        method: 'POST',
      }),
    onSuccess: (ep) => {
      qc.invalidateQueries({ queryKey: ['webhook-endpoints', activeMerchantId] });
      setRevealSecret(ep.signingSecret);
      toast.success('Secret rotated');
    },
  });

  return (
    <div className="space-y-6 max-w-3xl">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Webhooks</h1>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="size-4 mr-1" /> Add Endpoint
        </Button>
      </div>

      {isLoading ? (
        <p className="text-muted-foreground">Loading…</p>
      ) : endpoints.length === 0 ? (
        <Card><CardContent className="py-12 text-center text-muted-foreground">No webhook endpoints configured</CardContent></Card>
      ) : (
        <div className="space-y-3">
          {endpoints.map((ep) => (
            <Card key={ep.id}>
              <CardContent className="p-4">
                <div className="flex items-start justify-between">
                  <div>
                    <p className="font-medium text-sm">{ep.url}</p>
                    {ep.description && <p className="text-xs text-muted-foreground mt-0.5">{ep.description}</p>}
                    <div className="flex gap-1 mt-2 flex-wrap">
                      {ep.subscribedEvents.map((e) => (
                        <span key={e} className="text-xs bg-blue-50 text-blue-700 px-2 py-0.5 rounded-full">{e}</span>
                      ))}
                    </div>
                  </div>
                  <div className="flex items-center gap-1 shrink-0">
                    <span className={`text-xs px-2 py-0.5 rounded-full ${ep.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>
                      {ep.status}
                    </span>
                    <Button variant="ghost" size="icon" title="Rotate secret" onClick={() => rotateMutation.mutate(ep.id)}>
                      <RefreshCw className="size-4" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="text-red-500"
                      onClick={() => {
                        if (confirm('Delete this endpoint?')) deleteMutation.mutate(ep.id);
                      }}
                    >
                      <Trash2 className="size-4" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => setExpandedId(expandedId === ep.id ? null : ep.id)}
                    >
                      {expandedId === ep.id ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
                    </Button>
                  </div>
                </div>

                {expandedId === ep.id && (
                  <DeliveryLog endpointId={ep.id} merchantId={activeMerchantId!} epCreatedAt={ep.createdAt} epId={ep.id} />
                )}
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Create dialog */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>Add Webhook Endpoint</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => createMutation.mutate(d))} className="space-y-4">
            <div className="space-y-1">
              <Label>URL</Label>
              <Input placeholder="https://yoursite.com/webhook" {...register('url')} />
              {errors.url && <p className="text-xs text-red-500">{errors.url.message}</p>}
            </div>
            <div className="space-y-1">
              <Label>Description (optional)</Label>
              <Input placeholder="Production webhook" {...register('description')} />
            </div>
            <div className="space-y-2">
              <Label>Events to subscribe</Label>
              {ALL_EVENTS.map((e) => (
                <label key={e} className="flex items-center gap-2 text-sm cursor-pointer">
                  <input
                    type="checkbox"
                    checked={selectedEvents.includes(e)}
                    onChange={(ev) =>
                      setSelectedEvents(
                        ev.target.checked
                          ? [...selectedEvents, e]
                          : selectedEvents.filter((x) => x !== e),
                      )
                    }
                  />
                  {e}
                </label>
              ))}
            </div>
            <DialogFooter>
              <Button variant="ghost" type="button" onClick={() => setCreateOpen(false)}>Cancel</Button>
              <Button type="submit" disabled={createMutation.isPending}>Create</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Reveal secret dialog */}
      <Dialog open={!!revealSecret} onOpenChange={() => setRevealSecret(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>Signing Secret</DialogTitle></DialogHeader>
          <p className="text-sm text-muted-foreground">
            Save this signing secret now. It won&apos;t be shown again.
          </p>
          <div className="flex items-center gap-2 bg-gray-50 rounded-md p-3 font-mono text-sm break-all">
            {revealSecret}
            <Button
              variant="ghost"
              size="icon"
              className="shrink-0"
              onClick={() => {
                navigator.clipboard.writeText(revealSecret ?? '');
                toast.success('Copied');
              }}
            >
              <Copy className="size-4" />
            </Button>
          </div>
          <DialogFooter>
            <Button onClick={() => setRevealSecret(null)}>Done</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function DeliveryLog({ endpointId, merchantId, epCreatedAt, epId }: {
  endpointId: string; merchantId: string; epCreatedAt: string; epId: string;
}) {
  const { data: deliveries = [], isLoading } = useQuery<WebhookDelivery[]>({
    queryKey: ['webhook-deliveries', endpointId],
    queryFn: () =>
      apiFetch<WebhookDelivery[]>(
        `/api/v1/merchants/${merchantId}/webhook-endpoints/${endpointId}/deliveries`,
      ),
  });

  const statusColor: Record<string, string> = {
    SUCCEEDED: 'bg-green-100 text-green-700',
    FAILED: 'bg-red-100 text-red-700',
    RETRYING: 'bg-yellow-100 text-yellow-700',
    PENDING: 'bg-gray-100 text-gray-600',
  };

  return (
    <div className="mt-3 pt-3 border-t text-xs space-y-3">
      <div className="flex gap-4 text-muted-foreground">
        <span>ID: <span className="font-mono text-foreground">{epId}</span></span>
        <span>Created: {format(new Date(epCreatedAt), 'MMM d, yyyy HH:mm')}</span>
      </div>
      <div>
        <p className="font-medium text-foreground mb-2">Recent Deliveries</p>
        {isLoading ? (
          <p className="text-muted-foreground">Loading…</p>
        ) : deliveries.length === 0 ? (
          <p className="text-muted-foreground italic">No deliveries yet</p>
        ) : (
          <div className="space-y-1">
            {deliveries.map((d) => (
              <div key={d.id} className="flex items-center gap-3 bg-gray-50 rounded px-3 py-1.5">
                <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${statusColor[d.status] ?? 'bg-gray-100'}`}>
                  {d.status}
                </span>
                {d.httpStatus && (
                  <span className={d.httpStatus < 300 ? 'text-green-600' : 'text-red-600'}>
                    HTTP {d.httpStatus}
                  </span>
                )}
                <span className="text-muted-foreground">
                  {d.attemptCount} attempt{d.attemptCount !== 1 ? 's' : ''}
                </span>
                <span className="text-muted-foreground ml-auto">
                  {format(new Date(d.createdAt), 'MMM d HH:mm:ss')}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
