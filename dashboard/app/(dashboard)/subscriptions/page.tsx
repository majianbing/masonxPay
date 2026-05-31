'use client';

import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { format } from 'date-fns';
import { CalendarClock, Copy, CreditCard, Link2, Plus, RefreshCw } from 'lucide-react';
import { toast } from 'sonner';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Badge } from '@/components/ui/badge';
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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

interface BillingCustomer {
  id: string;
  email: string | null;
  name: string | null;
}

interface SubscriptionItem {
  id: string;
  description: string;
  amount: number;
  quantity: number;
}

interface Subscription {
  id: string;
  customerId: string;
  mode: 'TEST' | 'LIVE';
  status: 'INCOMPLETE' | 'TRIALING' | 'ACTIVE' | 'PAST_DUE' | 'CANCELED' | 'UNPAID';
  currency: string;
  intervalUnit: 'DAY' | 'WEEK' | 'MONTH' | 'YEAR';
  intervalCount: number;
  currentPeriodStart: string | null;
  currentPeriodEnd: string | null;
  trialEndsAt: string | null;
  items: SubscriptionItem[];
  metadata: Record<string, string>;
  createdAt: string;
  updatedAt: string;
}

interface Invoice {
  id: string;
  subscriptionId: string;
  status: 'OPEN' | 'PAID' | 'VOID' | 'UNCOLLECTIBLE';
  amountDue: number;
  amountPaid: number;
  currency: string;
  periodStart: string;
  periodEnd: string;
  dueAt: string | null;
  createdAt: string;
}

interface InvoicePaymentResult {
  invoiceId: string;
  invoiceStatus: string;
  subscriptionStatus: string;
  paymentIntentId: string | null;
  attemptNumber: number;
  success: boolean;
  failureCode: string | null;
  failureMessage: string | null;
}

interface SubscriptionCheckoutLink {
  id: string;
  subscriptionId: string;
  customerId: string;
  token: string;
  status: 'ACTIVE' | 'USED' | 'EXPIRED' | 'CANCELED';
  checkoutUrl: string;
  expiresAt: string | null;
  completedAt: string | null;
  createdAt: string;
}

interface FormState {
  customerId: string;
  description: string;
  amount: string;
  quantity: string;
  currency: string;
  intervalUnit: Subscription['intervalUnit'];
  intervalCount: string;
  trialDays: string;
  metadata: string;
}

const emptyForm: FormState = {
  customerId: '',
  description: 'Subscription plan',
  amount: '2900',
  quantity: '1',
  currency: 'USD',
  intervalUnit: 'MONTH',
  intervalCount: '1',
  trialDays: '0',
  metadata: '',
};

export default function SubscriptionsPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [selectedSubscription, setSelectedSubscription] = useState<Subscription | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);

  const subscriptionsKey = useMemo(() => ['subscriptions', activeMerchantId, mode], [activeMerchantId, mode]);
  const customersKey = useMemo(() => ['customers', activeMerchantId, mode], [activeMerchantId, mode]);
  const checkoutLinksKey = useMemo(
    () => ['subscription-checkout-links', activeMerchantId, selectedSubscription?.id],
    [activeMerchantId, selectedSubscription?.id],
  );
  const invoicesKey = useMemo(
    () => ['invoices', activeMerchantId, selectedSubscription?.id],
    [activeMerchantId, selectedSubscription?.id],
  );

  const { data: customers } = useQuery<BillingCustomer[]>({
    queryKey: customersKey,
    queryFn: () => apiFetch<BillingCustomer[]>(`/api/v1/merchants/${activeMerchantId}/customers?mode=${mode}`),
    enabled: !!activeMerchantId,
  });

  const { data: subscriptions, isLoading } = useQuery<Subscription[]>({
    queryKey: subscriptionsKey,
    queryFn: () => apiFetch<Subscription[]>(`/api/v1/merchants/${activeMerchantId}/subscriptions?mode=${mode}`),
    enabled: !!activeMerchantId,
  });

  const { data: invoices, isLoading: invoicesLoading } = useQuery<Invoice[]>({
    queryKey: invoicesKey,
    queryFn: () => apiFetch<Invoice[]>(
      `/api/v1/merchants/${activeMerchantId}/invoices?subscriptionId=${selectedSubscription?.id}`,
    ),
    enabled: !!activeMerchantId && !!selectedSubscription,
  });

  const { data: checkoutLinks, isLoading: linksLoading } = useQuery<SubscriptionCheckoutLink[]>({
    queryKey: checkoutLinksKey,
    queryFn: () => apiFetch<SubscriptionCheckoutLink[]>(
      `/api/v1/merchants/${activeMerchantId}/subscriptions/${selectedSubscription?.id}/checkout-links`,
    ),
    enabled: !!activeMerchantId && !!selectedSubscription,
  });

  const createMutation = useMutation({
    mutationFn: () => apiFetch<Subscription>(`/api/v1/merchants/${activeMerchantId}/subscriptions?mode=${mode}`, {
      method: 'POST',
      body: JSON.stringify({
        customerId: form.customerId,
        currency: form.currency.trim().toUpperCase(),
        intervalUnit: form.intervalUnit,
        intervalCount: Number(form.intervalCount),
        trialDays: Number(form.trialDays || 0),
        metadata: parseMetadata(form.metadata),
        items: [{
          description: form.description.trim(),
          amount: Number(form.amount),
          quantity: Number(form.quantity),
        }],
      }),
    }),
    onSuccess: (subscription) => {
      toast.success('Subscription created');
      queryClient.invalidateQueries({ queryKey: subscriptionsKey });
      setSelectedSubscription(subscription);
      setDialogOpen(false);
      setForm(emptyForm);
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string; title?: string };
      toast.error(e.detail ?? e.title ?? 'Could not create subscription');
    },
  });

  const linkMutation = useMutation({
    mutationFn: () => apiFetch<SubscriptionCheckoutLink>(
      `/api/v1/merchants/${activeMerchantId}/subscriptions/${selectedSubscription?.id}/checkout-links`,
      { method: 'POST', body: JSON.stringify({}) },
    ),
    onSuccess: (link) => {
      toast.success('Checkout link created');
      queryClient.invalidateQueries({ queryKey: checkoutLinksKey });
      copyLink(link.checkoutUrl);
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string; title?: string };
      toast.error(e.detail ?? e.title ?? 'Could not create checkout link');
    },
  });

  const generateInvoiceMutation = useMutation({
    mutationFn: () => apiFetch<Invoice>(
      `/api/v1/merchants/${activeMerchantId}/subscriptions/${selectedSubscription?.id}/invoices/current-period`,
      { method: 'POST' },
    ),
    onSuccess: () => {
      toast.success('Invoice generated');
      queryClient.invalidateQueries({ queryKey: invoicesKey });
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string; title?: string };
      toast.error(e.detail ?? e.title ?? 'Could not generate invoice');
    },
  });

  const payInvoiceMutation = useMutation({
    mutationFn: (invoiceId: string) => apiFetch<InvoicePaymentResult>(
      `/api/v1/merchants/${activeMerchantId}/invoices/${invoiceId}/pay`,
      { method: 'POST' },
    ),
    onSuccess: (result) => {
      if (result.success) {
        toast.success(`Invoice paid — subscription is ${result.subscriptionStatus}`);
      } else {
        toast.error(result.failureMessage ?? result.failureCode ?? 'Payment failed');
      }
      queryClient.invalidateQueries({ queryKey: invoicesKey });
      queryClient.invalidateQueries({ queryKey: subscriptionsKey });
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string; title?: string };
      toast.error(e.detail ?? e.title ?? 'Could not pay invoice');
    },
  });

  const customerById = new Map((customers ?? []).map((customer) => [customer.id, customer]));
  const rows = subscriptions ?? [];

  function openCreate() {
    setForm({ ...emptyForm, customerId: customers?.[0]?.id ?? '' });
    setDialogOpen(true);
  }

  function selectedCustomerLabel(customerId: string) {
    const customer = customerById.get(customerId);
    if (!customer) return shortId(customerId);
    return customer.name || customer.email || shortId(customer.id);
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Subscriptions</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Create recurring billing schedules and share checkout links for customer authorization.
          </p>
        </div>
        <Button onClick={openCreate} className="gap-1.5" disabled={!customers?.length}>
          <Plus className="size-4" />
          Subscription
        </Button>
      </div>

      {!customers?.length && (
        <Card>
          <CardContent className="py-6 text-sm text-muted-foreground">
            Create a customer first, then return here to create a subscription link.
          </CardContent>
        </Card>
      )}

      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_420px]">
        <Card>
          <CardContent className="p-0">
            <table className="w-full text-sm">
              <thead className="border-b bg-gray-50">
                <tr>
                  {['Subscription', 'Customer', 'Amount', 'Interval', 'Status', 'Created'].map((header) => (
                    <th key={header} className="px-4 py-3 text-left text-xs font-medium text-muted-foreground">
                      {header}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {isLoading ? (
                  <tr><td colSpan={6} className="py-10 text-center text-muted-foreground">Loading...</td></tr>
                ) : rows.length === 0 ? (
                  <tr><td colSpan={6} className="py-10 text-center text-muted-foreground">No subscriptions found</td></tr>
                ) : rows.map((subscription) => (
                  <tr
                    key={subscription.id}
                    className="cursor-pointer border-b last:border-0 hover:bg-gray-50"
                    onClick={() => setSelectedSubscription(subscription)}
                  >
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <div className="flex size-8 items-center justify-center rounded-md bg-muted">
                          <CalendarClock className="size-4 text-muted-foreground" />
                        </div>
                        <div>
                          <div className="font-medium">{subscription.items[0]?.description || 'Subscription'}</div>
                          <div className="font-mono text-xs text-muted-foreground">{shortId(subscription.id)}</div>
                        </div>
                      </div>
                    </td>
                    <td className="px-4 py-3">{selectedCustomerLabel(subscription.customerId)}</td>
                    <td className="px-4 py-3">
                      {formatMoney(totalAmount(subscription), subscription.currency)}
                    </td>
                    <td className="px-4 py-3">
                      Every {subscription.intervalCount} {subscription.intervalUnit.toLowerCase()}
                      {subscription.intervalCount === 1 ? '' : 's'}
                    </td>
                    <td className="px-4 py-3"><StatusBadge status={subscription.status} /></td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">
                      {format(new Date(subscription.createdAt), 'MMM d, yyyy HH:mm')}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="space-y-4">
            {selectedSubscription ? (
              <>
                <div className="space-y-1">
                  <div className="flex items-center justify-between gap-2">
                    <h2 className="text-base font-medium">
                      {selectedSubscription.items[0]?.description || 'Subscription'}
                    </h2>
                    <StatusBadge status={selectedSubscription.status} />
                  </div>
                  <p className="font-mono text-xs text-muted-foreground">{selectedSubscription.id}</p>
                </div>

                <div className="grid gap-3 text-sm">
                  <Detail label="Customer" value={selectedCustomerLabel(selectedSubscription.customerId)} />
                  <Detail label="Recurring amount" value={formatMoney(totalAmount(selectedSubscription), selectedSubscription.currency)} />
                  <Detail
                    label="Billing interval"
                    value={`Every ${selectedSubscription.intervalCount} ${selectedSubscription.intervalUnit.toLowerCase()}${selectedSubscription.intervalCount === 1 ? '' : 's'}`}
                  />
                  <Detail
                    label="Trial"
                    value={selectedSubscription.trialEndsAt ? `Ends ${format(new Date(selectedSubscription.trialEndsAt), 'MMM d, yyyy HH:mm')}` : 'None'}
                  />
                </div>

                <div className="space-y-2">
                  <div className="flex items-center justify-between gap-2">
                    <h3 className="text-sm font-medium">Checkout links</h3>
                    <Button
                      variant="outline"
                      size="sm"
                      className="gap-1.5"
                      disabled={linkMutation.isPending}
                      onClick={() => linkMutation.mutate()}
                    >
                      <Link2 className="size-4" />
                      Create link
                    </Button>
                  </div>

                  {linksLoading ? (
                    <p className="text-sm text-muted-foreground">Loading...</p>
                  ) : !checkoutLinks?.length ? (
                    <p className="text-sm text-muted-foreground">No checkout links created</p>
                  ) : checkoutLinks.map((link) => (
                    <div key={link.id} className="space-y-2 rounded-md border p-3 text-sm">
                      <div className="flex items-center justify-between gap-2">
                        <Badge variant={link.status === 'ACTIVE' ? 'secondary' : 'outline'}>{link.status}</Badge>
                        <Button variant="ghost" size="sm" className="gap-1.5" onClick={() => copyLink(link.checkoutUrl)}>
                          <Copy className="size-4" />
                          Copy
                        </Button>
                      </div>
                      <div className="break-all font-mono text-xs text-muted-foreground">{link.checkoutUrl}</div>
                    </div>
                  ))}
                </div>

                <div className="space-y-2">
                  <div className="flex items-center justify-between gap-2">
                    <h3 className="text-sm font-medium">Invoices</h3>
                    <Button
                      variant="outline"
                      size="sm"
                      className="gap-1.5"
                      disabled={generateInvoiceMutation.isPending}
                      onClick={() => generateInvoiceMutation.mutate()}
                    >
                      <Plus className="size-4" />
                      Generate
                    </Button>
                  </div>
                  {invoicesLoading ? (
                    <p className="text-sm text-muted-foreground">Loading...</p>
                  ) : !invoices?.length ? (
                    <p className="text-sm text-muted-foreground">No invoices yet</p>
                  ) : invoices.map((invoice) => (
                    <div key={invoice.id} className="rounded-md border p-3 text-sm">
                      <div className="flex items-center justify-between gap-2">
                        <div className="space-y-0.5">
                          <p className="text-xs text-muted-foreground">
                            {format(new Date(invoice.periodStart), 'MMM d')}
                            {' – '}
                            {format(new Date(invoice.periodEnd), 'MMM d, yyyy')}
                          </p>
                          <p className="font-medium">{formatMoney(invoice.amountDue, invoice.currency)}</p>
                        </div>
                        <div className="flex items-center gap-2">
                          <InvoiceStatusBadge status={invoice.status} />
                          {invoice.status === 'OPEN' && (
                            <Button
                              size="sm"
                              variant="outline"
                              className="gap-1.5"
                              disabled={payInvoiceMutation.isPending}
                              onClick={() => payInvoiceMutation.mutate(invoice.id)}
                            >
                              {payInvoiceMutation.isPending
                                ? <RefreshCw className="size-3.5 animate-spin" />
                                : <CreditCard className="size-3.5" />}
                              Pay
                            </Button>
                          )}
                        </div>
                      </div>
                      {invoice.status === 'PAID' && (
                        <p className="mt-1 text-xs text-green-600">
                          Paid {formatMoney(invoice.amountPaid, invoice.currency)}
                        </p>
                      )}
                    </div>
                  ))}
                </div>
              </>
            ) : (
              <div className="py-8 text-center text-sm text-muted-foreground">
                Select a subscription to create and copy checkout links.
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>Create Subscription</DialogTitle>
            <DialogDescription>
              Draft a recurring schedule and generate a customer authorization link after saving.
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-2 sm:col-span-2">
              <Label>Customer</Label>
              <Select value={form.customerId} onValueChange={(value: string | null) => setForm((current) => ({ ...current, customerId: value ?? '' }))}>
                <SelectTrigger className="w-full">
                  <SelectValue placeholder="Select customer">
                    {form.customerId ? selectedCustomerLabel(form.customerId) : null}
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  {(customers ?? []).map((customer) => (
                    <SelectItem key={customer.id} value={customer.id}>
                      <span className="font-medium">{customer.name || customer.email}</span>
                      {customer.name && customer.email && (
                        <span className="ml-2 text-xs text-muted-foreground">{customer.email}</span>
                      )}
                      {!customer.name && !customer.email && (
                        <span className="font-mono text-xs text-muted-foreground">{shortId(customer.id)}</span>
                      )}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-2 sm:col-span-2">
              <Label htmlFor="description">Plan name</Label>
              <Input
                id="description"
                value={form.description}
                onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="amount">Amount in cents</Label>
              <Input
                id="amount"
                type="number"
                min="50"
                value={form.amount}
                onChange={(event) => setForm((current) => ({ ...current, amount: event.target.value }))}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="currency">Currency</Label>
              <Input
                id="currency"
                value={form.currency}
                onChange={(event) => setForm((current) => ({ ...current, currency: event.target.value.toUpperCase() }))}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="quantity">Quantity</Label>
              <Input
                id="quantity"
                type="number"
                min="1"
                value={form.quantity}
                onChange={(event) => setForm((current) => ({ ...current, quantity: event.target.value }))}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="trialDays">Free trial days</Label>
              <Input
                id="trialDays"
                type="number"
                min="0"
                value={form.trialDays}
                onChange={(event) => setForm((current) => ({ ...current, trialDays: event.target.value }))}
              />
            </div>

            <div className="space-y-2">
              <Label>Interval unit</Label>
              <Select value={form.intervalUnit} onValueChange={(value: string | null) => setForm((current) => ({ ...current, intervalUnit: (value ?? 'MONTH') as Subscription['intervalUnit'] }))}>
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="DAY">Day</SelectItem>
                  <SelectItem value="WEEK">Week</SelectItem>
                  <SelectItem value="MONTH">Month</SelectItem>
                  <SelectItem value="YEAR">Year</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-2">
              <Label htmlFor="intervalCount">Interval count</Label>
              <Input
                id="intervalCount"
                type="number"
                min="1"
                value={form.intervalCount}
                onChange={(event) => setForm((current) => ({ ...current, intervalCount: event.target.value }))}
              />
            </div>

            <div className="space-y-2 sm:col-span-2">
              <Label htmlFor="metadata">Metadata</Label>
              <textarea
                id="metadata"
                className="min-h-20 w-full rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
                placeholder="campaign=launch&#10;plan=pro"
                value={form.metadata}
                onChange={(event) => setForm((current) => ({ ...current, metadata: event.target.value }))}
              />
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>Cancel</Button>
            <Button disabled={!canSubmit(form) || createMutation.isPending} onClick={() => createMutation.mutate()}>
              Create
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function StatusBadge({ status }: { status: Subscription['status'] }) {
  const statusClass: Record<Subscription['status'], string> = {
    ACTIVE: 'border-green-200 bg-green-50 text-green-700',
    TRIALING: 'border-blue-200 bg-blue-50 text-blue-700',
    INCOMPLETE: 'border-amber-200 bg-amber-50 text-amber-700',
    PAST_DUE: 'border-red-200 bg-red-50 text-red-700',
    UNPAID: 'border-red-200 bg-red-50 text-red-700',
    CANCELED: 'border-gray-200 bg-gray-50 text-gray-600',
  };
  return <Badge variant="outline" className={statusClass[status]}>{status}</Badge>;
}

function InvoiceStatusBadge({ status }: { status: Invoice['status'] }) {
  const cls: Record<Invoice['status'], string> = {
    OPEN:           'border-amber-200 bg-amber-50 text-amber-700',
    PAID:           'border-green-200 bg-green-50 text-green-700',
    VOID:           'border-gray-200 bg-gray-50 text-gray-600',
    UNCOLLECTIBLE:  'border-red-200 bg-red-50 text-red-700',
  };
  return <Badge variant="outline" className={cls[status]}>{status}</Badge>;
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-md border px-3 py-2">
      <span className="text-muted-foreground">{label}</span>
      <span className="text-right font-medium">{value}</span>
    </div>
  );
}

function canSubmit(form: FormState) {
  return Boolean(
    form.customerId
    && form.description.trim()
    && Number(form.amount) >= 50
    && Number(form.quantity) >= 1
    && Number(form.intervalCount) >= 1
    && Number(form.trialDays || 0) >= 0
    && form.currency.trim(),
  );
}

function totalAmount(subscription: Subscription) {
  return subscription.items.reduce((sum, item) => sum + item.amount * item.quantity, 0);
}

function formatMoney(amount: number, currency: string) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: currency || 'USD',
  }).format(amount / 100);
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

function copyLink(value: string) {
  navigator.clipboard?.writeText(value).then(
    () => toast.success('Link copied'),
    () => toast.error('Could not copy link'),
  );
}

function shortId(value: string) {
  return `${value.slice(0, 12)}...`;
}
