'use client';

import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { format } from 'date-fns';
import { Plus, UserRound } from 'lucide-react';
import { toast } from 'sonner';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';

interface BillingCustomer {
  id: string;
  merchantId: string;
  mode: 'TEST' | 'LIVE';
  email: string | null;
  name: string | null;
  metadata: Record<string, string>;
  createdAt: string;
  updatedAt: string;
}

interface CustomerPaymentMethod {
  id: string;
  customerId: string;
  paymentInstrumentId: string;
  status: 'ACTIVE' | 'DETACHED';
  defaultMethod: boolean;
  createdAt: string;
  // Instrument display fields
  provider: string | null;
  cardBrand: string | null;
  last4: string | null;
  expiryMonth: number | null;
  expiryYear: number | null;
}

interface CustomerFormState {
  name: string;
  email: string;
  metadata: string;
}

const emptyForm: CustomerFormState = { name: '', email: '', metadata: '' };

export default function CustomersPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<BillingCustomer | null>(null);
  const [form, setForm] = useState<CustomerFormState>(emptyForm);
  const [selectedCustomer, setSelectedCustomer] = useState<BillingCustomer | null>(null);
  const [instrumentId, setInstrumentId] = useState('');

  const customersKey = useMemo(() => ['customers', activeMerchantId, mode], [activeMerchantId, mode]);
  const paymentMethodsKey = useMemo(
    () => ['customer-payment-methods', activeMerchantId, mode, selectedCustomer?.id],
    [activeMerchantId, mode, selectedCustomer?.id],
  );

  const { data: customers, isLoading } = useQuery<BillingCustomer[]>({
    queryKey: customersKey,
    queryFn: () => apiFetch<BillingCustomer[]>(`/api/v1/merchants/${activeMerchantId}/customers?mode=${mode}`),
    enabled: !!activeMerchantId,
  });

  const { data: paymentMethods, isLoading: paymentMethodsLoading } = useQuery<CustomerPaymentMethod[]>({
    queryKey: paymentMethodsKey,
    queryFn: () => apiFetch<CustomerPaymentMethod[]>(
      `/api/v1/merchants/${activeMerchantId}/customers/${selectedCustomer?.id}/payment-methods?mode=${mode}`,
    ),
    enabled: !!activeMerchantId && !!selectedCustomer,
  });

  const saveMutation = useMutation({
    mutationFn: async () => {
      const body = JSON.stringify({
        name: form.name.trim() || null,
        email: form.email.trim() || null,
        metadata: parseMetadata(form.metadata),
      });
      if (editing) {
        return apiFetch<BillingCustomer>(
          `/api/v1/merchants/${activeMerchantId}/customers/${editing.id}?mode=${mode}`,
          { method: 'PATCH', body },
        );
      }
      return apiFetch<BillingCustomer>(
        `/api/v1/merchants/${activeMerchantId}/customers?mode=${mode}`,
        { method: 'POST', body },
      );
    },
    onSuccess: (customer) => {
      toast.success(editing ? 'Customer updated' : 'Customer created');
      queryClient.invalidateQueries({ queryKey: customersKey });
      setDialogOpen(false);
      setEditing(null);
      setForm(emptyForm);
      setSelectedCustomer(customer);
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string; title?: string };
      toast.error(e.detail ?? e.title ?? 'Could not save customer');
    },
  });

  const attachMutation = useMutation({
    mutationFn: () => apiFetch<CustomerPaymentMethod>(
      `/api/v1/merchants/${activeMerchantId}/customers/${selectedCustomer?.id}/payment-methods?mode=${mode}`,
      {
        method: 'POST',
        body: JSON.stringify({ paymentInstrumentId: instrumentId.trim(), defaultMethod: true }),
      },
    ),
    onSuccess: () => {
      toast.success('Payment method attached');
      setInstrumentId('');
      queryClient.invalidateQueries({ queryKey: paymentMethodsKey });
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string; title?: string };
      toast.error(e.detail ?? e.title ?? 'Could not attach payment method');
    },
  });

  const setDefaultMutation = useMutation({
    mutationFn: (method: CustomerPaymentMethod) => apiFetch<CustomerPaymentMethod>(
      `/api/v1/merchants/${activeMerchantId}/customers/${selectedCustomer?.id}/payment-methods?mode=${mode}`,
      {
        method: 'POST',
        body: JSON.stringify({ paymentInstrumentId: method.paymentInstrumentId, defaultMethod: true }),
      },
    ),
    onSuccess: () => {
      toast.success('Default payment method updated');
      queryClient.invalidateQueries({ queryKey: paymentMethodsKey });
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string; title?: string };
      toast.error(e.detail ?? e.title ?? 'Could not update default');
    },
  });

  const detachMutation = useMutation({
    mutationFn: (methodId: string) => apiFetch(
      `/api/v1/merchants/${activeMerchantId}/customers/${selectedCustomer?.id}/payment-methods/${methodId}?mode=${mode}`,
      { method: 'DELETE' },
    ),
    onSuccess: () => {
      toast.success('Payment method detached');
      queryClient.invalidateQueries({ queryKey: paymentMethodsKey });
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string; title?: string };
      toast.error(e.detail ?? e.title ?? 'Could not detach');
    },
  });

  function openCreate() {
    setEditing(null);
    setForm(emptyForm);
    setDialogOpen(true);
  }

  function openEdit(customer: BillingCustomer) {
    setEditing(customer);
    setForm({
      name: customer.name ?? '',
      email: customer.email ?? '',
      metadata: metadataToText(customer.metadata),
    });
    setDialogOpen(true);
  }

  const rows = customers ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Customers</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Customer records and reusable payment-method links for future subscription billing.
          </p>
        </div>
        <Button onClick={openCreate} className="gap-1.5">
          <Plus className="size-4" />
          Customer
        </Button>
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_360px]">
        <Card>
          <CardContent className="p-0">
            <table className="w-full text-sm">
              <thead className="border-b bg-gray-50">
                <tr>
                  {['Customer', 'Email', 'Metadata', 'Created', ''].map((header) => (
                    <th key={header} className="px-4 py-3 text-left font-medium text-muted-foreground text-xs">
                      {header}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {isLoading ? (
                  <tr><td colSpan={5} className="py-10 text-center text-muted-foreground">Loading...</td></tr>
                ) : rows.length === 0 ? (
                  <tr><td colSpan={5} className="py-10 text-center text-muted-foreground">No customers found</td></tr>
                ) : rows.map((customer) => (
                  <tr
                    key={customer.id}
                    className="border-b last:border-0 hover:bg-gray-50 cursor-pointer"
                    onClick={() => setSelectedCustomer(customer)}
                  >
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <div className="flex size-8 items-center justify-center rounded-md bg-muted">
                          <UserRound className="size-4 text-muted-foreground" />
                        </div>
                        <div>
                          <div className="font-medium">{customer.name || 'Unnamed customer'}</div>
                          <div className="font-mono text-xs text-muted-foreground">{shortId(customer.id)}</div>
                        </div>
                      </div>
                    </td>
                    <td className="px-4 py-3">{customer.email || '-'}</td>
                    <td className="px-4 py-3">
                      <div className="flex max-w-64 flex-wrap gap-1">
                        {Object.entries(customer.metadata ?? {}).slice(0, 3).map(([key, value]) => (
                          <Badge key={key} variant="outline">{key}: {value}</Badge>
                        ))}
                        {Object.keys(customer.metadata ?? {}).length === 0 && (
                          <span className="text-xs text-muted-foreground">-</span>
                        )}
                      </div>
                    </td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">
                      {format(new Date(customer.createdAt), 'MMM d, yyyy HH:mm')}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={(event) => {
                          event.stopPropagation();
                          openEdit(customer);
                        }}
                      >
                        Edit
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="space-y-4">
            {selectedCustomer ? (
              <>
                <div>
                  <h2 className="text-base font-medium">{selectedCustomer.name || 'Unnamed customer'}</h2>
                  <p className="font-mono text-xs text-muted-foreground">{selectedCustomer.id}</p>
                </div>

                <div className="space-y-2">
                  <Label htmlFor="instrumentId">Attach payment instrument</Label>
                  <Input
                    id="instrumentId"
                    placeholder="PaymentInstrument UUID"
                    value={instrumentId}
                    onChange={(event) => setInstrumentId(event.target.value)}
                  />
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={!instrumentId.trim() || attachMutation.isPending}
                    onClick={() => attachMutation.mutate()}
                  >
                    Attach as default
                  </Button>
                </div>

                <div className="space-y-2">
                  <h3 className="text-sm font-medium">Payment methods</h3>
                  {paymentMethodsLoading ? (
                    <p className="text-sm text-muted-foreground">Loading...</p>
                  ) : !paymentMethods?.length ? (
                    <p className="text-sm text-muted-foreground">No payment methods attached</p>
                  ) : paymentMethods.map((method) => (
                    <div key={method.id} className="rounded-md border p-3 text-sm">
                      <div className="flex items-center justify-between gap-2">
                        <div className="space-y-0.5">
                          <p className="font-medium">{cardLabel(method)}</p>
                          <p className="text-xs text-muted-foreground">{providerLabel(method.provider)}</p>
                        </div>
                        <div className="flex items-center gap-1.5">
                          {method.defaultMethod
                            ? <Badge variant="secondary">Default</Badge>
                            : (
                              <Button
                                size="sm"
                                variant="outline"
                                className="h-7 px-2 text-xs"
                                disabled={setDefaultMutation.isPending}
                                onClick={() => setDefaultMutation.mutate(method)}
                              >
                                Set default
                              </Button>
                            )}
                          <Button
                            size="sm"
                            variant="ghost"
                            className="h-7 px-2 text-xs text-muted-foreground hover:text-red-600"
                            disabled={detachMutation.isPending || method.defaultMethod}
                            title={method.defaultMethod ? 'Cannot detach the default payment method' : 'Detach'}
                            onClick={() => detachMutation.mutate(method.id)}
                          >
                            Detach
                          </Button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              </>
            ) : (
              <div className="py-8 text-center text-sm text-muted-foreground">
                Select a customer to manage payment methods.
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{editing ? 'Edit Customer' : 'Create Customer'}</DialogTitle>
            <DialogDescription>
              Store customer profile data for future subscription and invoice workflows.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="customerName">Name</Label>
              <Input
                id="customerName"
                value={form.name}
                onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="customerEmail">Email</Label>
              <Input
                id="customerEmail"
                type="email"
                value={form.email}
                onChange={(event) => setForm((current) => ({ ...current, email: event.target.value }))}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="customerMetadata">Metadata</Label>
              <textarea
                id="customerMetadata"
                className="min-h-24 w-full rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
                placeholder="tier=gold&#10;account_manager=team-a"
                value={form.metadata}
                onChange={(event) => setForm((current) => ({ ...current, metadata: event.target.value }))}
              />
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>Cancel</Button>
            <Button disabled={saveMutation.isPending} onClick={() => saveMutation.mutate()}>
              {editing ? 'Save' : 'Create'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function parseMetadata(value: string) {
  return Object.fromEntries(
    value
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        const separator = line.indexOf('=');
        if (separator === -1) return [line, ''];
        return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
      })
      .filter(([key]) => key),
  );
}

function metadataToText(metadata: Record<string, string>) {
  return Object.entries(metadata ?? {}).map(([key, value]) => `${key}=${value}`).join('\n');
}

function shortId(value: string) {
  return `${value.slice(0, 12)}...`;
}

function cardLabel(method: CustomerPaymentMethod): string {
  if (method.cardBrand && method.last4) {
    const brand = method.cardBrand.charAt(0).toUpperCase() + method.cardBrand.slice(1);
    const expiry = method.expiryMonth && method.expiryYear
      ? ` · ${String(method.expiryMonth).padStart(2, '0')}/${String(method.expiryYear).slice(-2)}`
      : '';
    return `${brand} ···· ${method.last4}${expiry}`;
  }
  if (method.provider === 'SIMULATOR') return 'Test card (Simulator)';
  return `···· ${method.paymentInstrumentId.slice(-6)}`;
}

function providerLabel(provider: string | null): string {
  const labels: Record<string, string> = {
    STRIPE: 'Stripe', SQUARE: 'Square', BRAINTREE: 'Braintree',
    MOLLIE: 'Mollie', SIMULATOR: 'Mason Simulator',
  };
  return provider ? (labels[provider] ?? provider) : 'Unknown provider';
}
