'use client';

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { format } from 'date-fns';

interface Merchant {
  id: string;
  name: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export default function MerchantSettingsPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);

  const { data: merchant, isLoading } = useQuery<Merchant>({
    queryKey: ['merchant', activeMerchantId],
    queryFn: () => apiFetch<Merchant>(`/api/v1/merchants/${activeMerchantId}`),
    enabled: !!activeMerchantId,
  });

  if (isLoading) return <div className="py-12 text-center text-muted-foreground">Loading…</div>;

  return (
    <div className="space-y-6 max-w-2xl">
      <h1 className="text-2xl font-semibold">Merchant Settings</h1>

      <Card>
        <CardHeader><CardTitle className="text-sm font-medium">Business Details</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-2 gap-4 text-sm">
          <Field label="Merchant ID" value={<span className="font-mono text-xs">{merchant?.id}</span>} />
          <Field label="Name" value={merchant?.name} />
          <Field label="Status" value={
            <span className={`text-xs px-2 py-0.5 rounded-full ${merchant?.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>
              {merchant?.status}
            </span>
          } />
          <Field label="Created" value={merchant?.createdAt ? format(new Date(merchant.createdAt), 'MMM d, yyyy') : '—'} />
        </CardContent>
      </Card>
    </div>
  );
}

function Field({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground mb-0.5">{label}</p>
      <div>{value ?? '—'}</div>
    </div>
  );
}
