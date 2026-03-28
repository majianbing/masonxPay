'use client';

import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { toast } from 'sonner';
import { Copy, Plus, Trash2, ExternalLink, Link2 } from 'lucide-react';
import { format } from 'date-fns';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent } from '@/components/ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog';

interface PaymentLink {
  id: string;
  token: string;
  title: string;
  description: string | null;
  amount: number;
  currency: string;
  mode: string;
  status: string;
  payUrl: string;
  createdAt: string;
}

const createSchema = z.object({
  title: z.string().min(1, 'Title required'),
  description: z.string().optional(),
  amount: z.number().min(0.5, 'Minimum $0.50').multipleOf(0.01),
  currency: z.enum(['USD', 'EUR', 'GBP', 'CAD', 'AUD']),
  redirectUrl: z.string().url('Must be a valid URL').optional().or(z.literal('')),
});
type CreateForm = z.infer<typeof createSchema>;

export default function PaymentLinksPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const qc = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [confirmLink, setConfirmLink] = useState<PaymentLink | null>(null);

  const { data: links = [], isLoading } = useQuery<PaymentLink[]>({
    queryKey: ['payment-links', activeMerchantId, mode],
    queryFn: () =>
      apiFetch<PaymentLink[]>(
        `/api/v1/merchants/${activeMerchantId}/payment-links?mode=${mode}`,
      ),
    enabled: !!activeMerchantId,
  });

  const { register, handleSubmit, reset, formState: { errors } } = useForm<CreateForm>({
    resolver: zodResolver(createSchema),
    defaultValues: { currency: 'USD' },
  });

  const createMutation = useMutation({
    mutationFn: (data: CreateForm) =>
      apiFetch<PaymentLink>(
        `/api/v1/merchants/${activeMerchantId}/payment-links?mode=${mode}`,
        {
          method: 'POST',
          body: JSON.stringify({
            title: data.title,
            description: data.description || null,
            amount: Math.round(data.amount * 100),
            currency: data.currency.toLowerCase(),
            redirectUrl: data.redirectUrl || null,
          }),
        },
      ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['payment-links', activeMerchantId, mode] });
      setCreateOpen(false);
      reset();
      toast.success('Payment link created');
    },
    onError: (err: unknown) => {
      toast.error((err as { detail?: string }).detail ?? 'Failed to create link');
    },
  });

  const deactivateMutation = useMutation({
    mutationFn: (linkId: string) =>
      apiFetch(`/api/v1/merchants/${activeMerchantId}/payment-links/${linkId}`, { method: 'DELETE' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['payment-links', activeMerchantId, mode] });
      toast.success('Payment link deactivated');
    },
  });

  function copyLink(url: string) {
    navigator.clipboard.writeText(url);
    toast.success('Link copied to clipboard');
  }

  const fmt = (amount: number, currency: string) =>
    new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount / 100);

  return (
    <div className="space-y-6 max-w-4xl">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Payment Links</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Create a link, send it to your customer — no integration needed.
          </p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="size-4 mr-1" /> New Link
        </Button>
      </div>

      {links.length === 0 && !isLoading && (
        <Card>
          <CardContent className="py-16 flex flex-col items-center gap-3">
            <Link2 className="size-10 text-muted-foreground" />
            <p className="text-muted-foreground text-sm">No payment links yet.</p>
            <Button onClick={() => setCreateOpen(true)}>
              <Plus className="size-4 mr-1" /> Create your first link
            </Button>
          </CardContent>
        </Card>
      )}

      {links.length > 0 && (
        <Card>
          <CardContent className="p-0">
            <table className="w-full text-sm">
              <thead className="border-b bg-gray-50">
                <tr>
                  {['Title', 'Amount', 'Status', 'Created', 'Link', ''].map((h) => (
                    <th key={h} className="px-4 py-3 text-left font-medium text-muted-foreground text-xs">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {links.map((l) => (
                  <tr key={l.id} className="border-b last:border-0">
                    <td className="px-4 py-3">
                      <p className="font-medium">{l.title}</p>
                      {l.description && (
                        <p className="text-xs text-muted-foreground truncate max-w-xs">{l.description}</p>
                      )}
                    </td>
                    <td className="px-4 py-3 font-medium">{fmt(l.amount, l.currency)}</td>
                    <td className="px-4 py-3">
                      <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                        l.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                      }`}>
                        {l.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">
                      {format(new Date(l.createdAt), 'MMM d, yyyy')}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1 max-w-xs">
                        <span className="font-mono text-xs text-muted-foreground truncate">{l.payUrl}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1 justify-end">
                        <Button variant="ghost" size="icon" title="Copy link" onClick={() => copyLink(l.payUrl)}>
                          <Copy className="size-4" />
                        </Button>
                        <Button
                          variant="ghost" size="icon" title="Open link"
                          onClick={() => window.open(l.payUrl, '_blank')}
                        >
                          <ExternalLink className="size-4" />
                        </Button>
                        {l.status === 'ACTIVE' && (
                          <Button
                            variant="ghost" size="icon" title="Deactivate"
                            className="text-red-500 hover:text-red-700"
                            onClick={() => setConfirmLink(l)}
                          >
                            <Trash2 className="size-4" />
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>
      )}

      <Dialog open={!!confirmLink} onOpenChange={(o) => { if (!o) setConfirmLink(null); }}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>Deactivate link?</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground">
            <span className="font-medium text-foreground">&ldquo;{confirmLink?.title}&rdquo;</span> will be
            deactivated immediately. Customers who visit the link will no longer be able to pay.
          </p>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmLink(null)}>Cancel</Button>
            <Button
              variant="destructive"
              disabled={deactivateMutation.isPending}
              onClick={() => {
                if (confirmLink) {
                  deactivateMutation.mutate(confirmLink.id, { onSettled: () => setConfirmLink(null) });
                }
              }}
            >
              Deactivate
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={createOpen} onOpenChange={(o) => { setCreateOpen(o); if (!o) reset(); }}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              New Payment Link
              <span className={`text-xs px-2 py-0.5 rounded font-medium ${mode === 'TEST' ? 'bg-yellow-100 text-yellow-700' : 'bg-green-100 text-green-700'}`}>
                {mode}
              </span>
            </DialogTitle>
          </DialogHeader>
          <form onSubmit={handleSubmit((d) => createMutation.mutate(d))} className="space-y-4">
            <div className="space-y-1">
              <Label>Title</Label>
              <Input placeholder="e.g. Consulting session — 1 hour" {...register('title')} />
              {errors.title && <p className="text-xs text-red-500">{errors.title.message}</p>}
            </div>
            <div className="space-y-1">
              <Label>Description <span className="text-muted-foreground">(optional)</span></Label>
              <Input placeholder="What the customer is paying for" {...register('description')} />
            </div>
            <div className="flex gap-3">
              <div className="flex-1 space-y-1">
                <Label>Amount</Label>
                <Input type="number" step="0.01" min="0.50" placeholder="42.00" {...register('amount', { valueAsNumber: true })} />
                {errors.amount && <p className="text-xs text-red-500">{errors.amount.message}</p>}
              </div>
              <div className="w-28 space-y-1">
                <Label>Currency</Label>
                <select className="border rounded-md px-3 py-2 text-sm w-full bg-white" {...register('currency')}>
                  {['USD', 'EUR', 'GBP', 'CAD', 'AUD'].map((c) => (
                    <option key={c}>{c}</option>
                  ))}
                </select>
              </div>
            </div>
            <div className="space-y-1">
              <Label>Redirect URL <span className="text-muted-foreground">(optional)</span></Label>
              <Input placeholder="https://yourshop.com/thank-you" {...register('redirectUrl')} />
              {errors.redirectUrl && <p className="text-xs text-red-500">{errors.redirectUrl.message}</p>}
              <p className="text-xs text-muted-foreground">Buyer is redirected here after successful payment.</p>
            </div>
            <DialogFooter>
              <Button variant="ghost" type="button" onClick={() => setCreateOpen(false)}>Cancel</Button>
              <Button type="submit" disabled={createMutation.isPending}>Create Link</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
