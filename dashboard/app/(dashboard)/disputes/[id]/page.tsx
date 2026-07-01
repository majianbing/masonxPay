'use client';

import { useParams, useRouter } from 'next/navigation';
import { useRef, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { format } from 'date-fns';
import { apiFetch, apiFetchForm } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { toast } from 'sonner';

interface EvidenceFile {
  id: string;
  externalId: string | null;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  url: string;
  createdAt: string;
}

interface Dispute {
  id: string;
  externalId: string | null;
  paymentIntentId: string | null;
  paymentIntentExternalId: string | null;
  provider: string;
  providerDisputeId: string;
  status: string;
  reason: string | null;
  amount: number;
  currency: string;
  evidenceDueBy: string | null;
  submittedAt: string | null;
  resolvedAt: string | null;
  createdAt: string;
  files: EvidenceFile[];
}

interface EvidenceForm {
  customerName: string;
  customerEmail: string;
  customerPurchaseIp: string;
  productDescription: string;
  customerCommunication: string;
  refundPolicy: string;
  refundPolicyDisclosure: string;
  shippingDocumentationUrl: string;
  uncategorizedText: string;
  fileIds: string[];
}

const STATUS_COLORS: Record<string, string> = {
  NEEDS_RESPONSE:         'bg-red-100 text-red-700',
  UNDER_REVIEW:           'bg-blue-100 text-blue-700',
  WON:                    'bg-green-100 text-green-700',
  LOST:                   'bg-gray-200 text-gray-600',
  CHARGE_REFUNDED:        'bg-gray-200 text-gray-600',
  WARNING_NEEDS_RESPONSE: 'bg-yellow-100 text-yellow-700',
  WARNING_UNDER_REVIEW:   'bg-yellow-100 text-yellow-700',
  WARNING_CLOSED:         'bg-gray-200 text-gray-600',
};

const canSubmit = (status: string) =>
  status === 'NEEDS_RESPONSE' || status === 'WARNING_NEEDS_RESPONSE';

export default function DisputeDetailPage() {
  const { id } = useParams<{ id: string }>();
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const router = useRouter();
  const qc = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [form, setForm] = useState<EvidenceForm>({
    customerName: '',
    customerEmail: '',
    customerPurchaseIp: '',
    productDescription: '',
    customerCommunication: '',
    refundPolicy: '',
    refundPolicyDisclosure: '',
    shippingDocumentationUrl: '',
    uncategorizedText: '',
    fileIds: [],
  });

  const { data: dispute, isLoading } = useQuery<Dispute>({
    queryKey: ['dispute', activeMerchantId, id],
    queryFn: () => apiFetch<Dispute>(`/api/v1/merchants/${activeMerchantId}/disputes/${id}`),
    enabled: !!activeMerchantId && !!id,
  });

  const uploadMutation = useMutation({
    mutationFn: (file: File) => {
      const fd = new FormData();
      fd.append('file', file);
      return apiFetchForm<EvidenceFile>(
        `/api/v1/merchants/${activeMerchantId}/disputes/${id}/evidence-files`,
        { method: 'POST', body: fd },
      );
    },
    onSuccess: (uploaded) => {
      qc.invalidateQueries({ queryKey: ['dispute', activeMerchantId, id] });
      setForm((f) => ({ ...f, fileIds: [...f.fileIds, uploaded.id] }));
      toast.success(`File uploaded: ${uploaded.fileName}`);
    },
    onError: () => toast.error('Upload failed'),
  });

  const submitMutation = useMutation({
    mutationFn: () =>
      apiFetch<Dispute>(`/api/v1/merchants/${activeMerchantId}/disputes/${id}/evidence`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(form),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['dispute', activeMerchantId, id] });
      toast.success('Evidence submitted — your response has been sent to the card network.');
    },
    onError: () => toast.error('Submission failed'),
  });

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (file) uploadMutation.mutate(file);
    e.target.value = '';
  }

  function set(field: keyof EvidenceForm) {
    return (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
      setForm((f) => ({ ...f, [field]: e.target.value }));
  }

  if (isLoading) {
    return <div className="py-12 text-center text-muted-foreground">Loading…</div>;
  }
  if (!dispute) {
    return <div className="py-12 text-center text-muted-foreground">Dispute not found.</div>;
  }

  const locked = !!dispute.submittedAt || !canSubmit(dispute.status);

  return (
    <div className="space-y-6 max-w-3xl">
      <button
        onClick={() => router.push('/disputes')}
        className="text-sm text-muted-foreground hover:text-foreground flex items-center gap-1"
      >
        ← Back to Disputes
      </button>

      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Dispute</h1>
          <p className="text-sm font-mono text-muted-foreground mt-0.5">{dispute.externalId ?? dispute.id}</p>
        </div>
        <span className={`text-sm px-3 py-1 rounded-full font-medium ${STATUS_COLORS[dispute.status] ?? 'bg-gray-100 text-gray-600'}`}>
          {dispute.status.replace(/_/g, ' ')}
        </span>
      </div>

      {/* Summary card */}
      <Card>
        <CardContent className="pt-4 grid grid-cols-2 gap-x-8 gap-y-3 text-sm">
          <div>
            <p className="text-xs text-muted-foreground">Amount</p>
            <p className="font-medium">{(dispute.amount / 100).toFixed(2)} {dispute.currency.toUpperCase()}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Provider</p>
            <p>{dispute.provider}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Reason</p>
            <p>{dispute.reason ? dispute.reason.replace(/_/g, ' ') : '—'}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Evidence Due</p>
            <p>{dispute.evidenceDueBy ? format(new Date(dispute.evidenceDueBy), 'MMM d, yyyy') : '—'}</p>
          </div>
          {dispute.paymentIntentId && (
            <div>
              <p className="text-xs text-muted-foreground">Payment</p>
              <button
                className="font-mono text-xs text-primary hover:underline"
                onClick={() => router.push(`/payments/${dispute.paymentIntentExternalId ?? dispute.paymentIntentId}`)}
              >
                {dispute.paymentIntentExternalId ?? `${dispute.paymentIntentId.slice(0, 20)}...`}
              </button>
            </div>
          )}
          {dispute.submittedAt && (
            <div>
              <p className="text-xs text-muted-foreground">Submitted At</p>
              <p>{format(new Date(dispute.submittedAt), 'MMM d, yyyy HH:mm')}</p>
            </div>
          )}
          {dispute.resolvedAt && (
            <div>
              <p className="text-xs text-muted-foreground">Resolved At</p>
              <p>{format(new Date(dispute.resolvedAt), 'MMM d, yyyy HH:mm')}</p>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Evidence files */}
      <Card>
        <CardHeader className="pb-2">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base">Evidence Files</CardTitle>
            {!locked && (
              <>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => fileInputRef.current?.click()}
                  disabled={uploadMutation.isPending}
                >
                  {uploadMutation.isPending ? 'Uploading…' : 'Upload File'}
                </Button>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/jpeg,image/png,image/gif,image/webp,application/pdf"
                  className="hidden"
                  onChange={handleFileChange}
                />
              </>
            )}
          </div>
        </CardHeader>
        <CardContent>
          {dispute.files.length === 0 ? (
            <p className="text-sm text-muted-foreground">No files uploaded yet.</p>
          ) : (
            <ul className="space-y-2">
              {dispute.files.map((f) => (
                <li key={f.externalId ?? f.id} className="flex items-center justify-between text-sm border rounded-md px-3 py-2">
                  <span className="font-medium">{f.fileName}</span>
                  <div className="flex items-center gap-4 text-xs text-muted-foreground">
                    <span>{(f.sizeBytes / 1024).toFixed(1)} KB</span>
                    <a href={f.url} target="_blank" rel="noreferrer" className="text-primary hover:underline">
                      View
                    </a>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {/* Evidence form */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">
            {locked ? 'Evidence Submitted' : 'Submit Evidence'}
          </CardTitle>
          {locked && !dispute.submittedAt && (
            <p className="text-sm text-muted-foreground">
              Evidence can only be submitted when the dispute status is NEEDS_RESPONSE.
            </p>
          )}
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-1">
              <label className="text-xs font-medium text-muted-foreground">Customer Name</label>
              <Input
                value={form.customerName}
                onChange={set('customerName')}
                disabled={locked}
                placeholder="Jane Smith"
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-muted-foreground">Customer Email</label>
              <Input
                value={form.customerEmail}
                onChange={set('customerEmail')}
                disabled={locked}
                placeholder="jane@example.com"
                type="email"
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-muted-foreground">Purchase IP</label>
              <Input
                value={form.customerPurchaseIp}
                onChange={set('customerPurchaseIp')}
                disabled={locked}
                placeholder="1.2.3.4"
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-muted-foreground">Shipping Documentation URL</label>
              <Input
                value={form.shippingDocumentationUrl}
                onChange={set('shippingDocumentationUrl')}
                disabled={locked}
                placeholder="https://…"
              />
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-muted-foreground">Product / Service Description</label>
            <textarea
              value={form.productDescription}
              onChange={set('productDescription')}
              disabled={locked}
              rows={3}
              placeholder="Describe what was sold and delivered…"
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-50"
            />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-muted-foreground">Customer Communication</label>
            <textarea
              value={form.customerCommunication}
              onChange={set('customerCommunication')}
              disabled={locked}
              rows={3}
              placeholder="Summary of your communications with the customer…"
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-50"
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-1">
              <label className="text-xs font-medium text-muted-foreground">Refund Policy</label>
              <textarea
                value={form.refundPolicy}
                onChange={set('refundPolicy')}
                disabled={locked}
                rows={2}
                placeholder="Your refund policy text…"
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-50"
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-muted-foreground">Refund Policy Disclosure</label>
              <textarea
                value={form.refundPolicyDisclosure}
                onChange={set('refundPolicyDisclosure')}
                disabled={locked}
                rows={2}
                placeholder="Where the policy was disclosed to the customer…"
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-50"
              />
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-muted-foreground">Additional Notes</label>
            <textarea
              value={form.uncategorizedText}
              onChange={set('uncategorizedText')}
              disabled={locked}
              rows={3}
              placeholder="Any other information relevant to this dispute…"
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-50"
            />
          </div>

          {!locked && (
            <Button
              onClick={() => submitMutation.mutate()}
              disabled={submitMutation.isPending}
            >
              {submitMutation.isPending ? 'Submitting…' : 'Submit Evidence'}
            </Button>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
