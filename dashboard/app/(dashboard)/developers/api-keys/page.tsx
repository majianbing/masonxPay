'use client';

import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { toast } from 'sonner';
import { Copy, Plus, Trash2, Eye, EyeOff, Check } from 'lucide-react';
import { format } from 'date-fns';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent } from '@/components/ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog';
import { cn } from '@/lib/utils';

interface ApiKey {
  id: string;
  name: string;
  mode: string;
  type: string;          // SECRET | PUBLISHABLE
  prefix: string;
  plaintextKey?: string; // always present for PUBLISHABLE; null for SECRET
  status: string;
  createdAt: string;
  lastUsedAt?: string;
}

interface ApiKeyPair {
  secretKey: ApiKey & { secretPlaintext?: string };
  publishableKey: ApiKey;
}

const createSchema = z.object({ name: z.string().min(1, 'Name required') });
type CreateForm = z.infer<typeof createSchema>;

function CopyButton({ text, className }: { text: string; className?: string }) {
  const [copied, setCopied] = useState(false);
  function copy() {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }
  return (
    <button onClick={copy} className={cn('text-muted-foreground hover:text-foreground transition-colors', className)}>
      {copied ? <Check className="size-3.5 text-green-500" /> : <Copy className="size-3.5" />}
    </button>
  );
}

function KeyRow({
  label,
  value,
  redact,
  onToggleRedact,
}: {
  label: string;
  value: string;
  redact?: boolean;
  onToggleRedact?: () => void;
}) {
  const display = redact ? value.substring(0, value.indexOf('_', value.indexOf('_') + 1) + 1) + '•'.repeat(24) : value;
  return (
    <div className="space-y-1">
      <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">{label}</p>
      <div className="flex items-center gap-2 bg-gray-50 rounded-md px-3 py-2 font-mono text-xs border min-w-0">
        <span className="flex-1 truncate min-w-0">{display}</span>
        {onToggleRedact && (
          <button onClick={onToggleRedact} className="text-muted-foreground hover:text-foreground shrink-0">
            {redact ? <Eye className="size-3.5" /> : <EyeOff className="size-3.5" />}
          </button>
        )}
        <CopyButton text={value} className="shrink-0" />
      </div>
    </div>
  );
}

export default function ApiKeysPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const qc = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [newPair, setNewPair] = useState<ApiKeyPair | null>(null);
  const [redactSk, setRedactSk] = useState(true);

  const { data: keys = [], isLoading } = useQuery<ApiKey[]>({
    queryKey: ['api-keys', activeMerchantId],
    queryFn: () => apiFetch<ApiKey[]>(`/api/v1/merchants/${activeMerchantId}/api-keys`),
    enabled: !!activeMerchantId,
  });

  const { register, handleSubmit, reset, formState: { errors } } = useForm<CreateForm>({
    resolver: zodResolver(createSchema),
  });

  const createMutation = useMutation({
    mutationFn: (data: CreateForm) =>
      apiFetch<ApiKeyPair>(`/api/v1/merchants/${activeMerchantId}/api-keys`, {
        method: 'POST',
        body: JSON.stringify({ name: data.name, mode }),
      }),
    onSuccess: (pair) => {
      qc.invalidateQueries({ queryKey: ['api-keys', activeMerchantId] });
      setCreateOpen(false);
      reset();
      setRedactSk(true);
      setNewPair(pair);
      toast.success('API key pair created');
    },
    onError: (err: unknown) => {
      toast.error((err as { detail?: string }).detail ?? 'Failed to create key');
    },
  });

  const revokeMutation = useMutation({
    mutationFn: (keyId: string) =>
      apiFetch(`/api/v1/merchants/${activeMerchantId}/api-keys/${keyId}`, { method: 'DELETE' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['api-keys', activeMerchantId] });
      toast.success('Key revoked');
    },
  });

  // Group keys by name+mode into pairs
  const activeKeys = keys.filter((k) => k.status === 'ACTIVE' && k.mode === mode);
  const skKeys = activeKeys.filter((k) => k.type === 'SECRET');
  const pkMap = Object.fromEntries(
    activeKeys.filter((k) => k.type === 'PUBLISHABLE').map((k) => [k.name + k.mode, k])
  );

  // Also collect revoked keys for history
  const revokedKeys = keys.filter((k) => k.status !== 'ACTIVE' && k.mode === mode && k.type === 'SECRET');

  return (
    <div className="space-y-6 max-w-3xl">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">API Keys</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Each key pair has a <strong>secret key</strong> (server-side) and a <strong>publishable key</strong> (client-side / SDK).
          </p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="size-4 mr-1" /> New Key Pair
        </Button>
      </div>

      <Card>
        <CardContent className="p-0">
          <table className="w-full text-sm">
            <thead className="border-b bg-gray-50">
              <tr>
                {['Name', 'Secret Key', 'Publishable Key', 'Created', ''].map((h) => (
                  <th key={h} className="px-4 py-3 text-left font-medium text-muted-foreground text-xs">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr><td colSpan={5} className="py-8 text-center text-muted-foreground text-sm">Loading…</td></tr>
              ) : skKeys.length === 0 ? (
                <tr>
                  <td colSpan={5} className="py-10 text-center text-muted-foreground text-sm">
                    No active {mode} keys.{' '}
                    <button className="text-primary underline" onClick={() => setCreateOpen(true)}>Create a key pair</button>
                  </td>
                </tr>
              ) : (
                skKeys.map((sk) => {
                  const pk = pkMap[sk.name + sk.mode];
                  return (
                    <tr key={sk.id} className="border-b last:border-0">
                      <td className="px-4 py-3">
                        <p className="font-medium">{sk.name}</p>
                        <span className={cn('text-xs px-1.5 py-0.5 rounded font-medium',
                          sk.mode === 'TEST' ? 'bg-yellow-100 text-yellow-700' : 'bg-green-100 text-green-700')}>
                          {sk.mode}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-1.5 font-mono text-xs text-muted-foreground">
                          <span>{sk.prefix}•••</span>
                          <span className="text-xs text-gray-400 font-sans">(shown once)</span>
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        {pk ? (
                          <div className="flex items-center gap-1.5 font-mono text-xs">
                            <span className="text-muted-foreground truncate max-w-[160px]">{pk.plaintextKey}</span>
                            <CopyButton text={pk.plaintextKey!} />
                          </div>
                        ) : (
                          <span className="text-xs text-muted-foreground">—</span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">
                        {format(new Date(sk.createdAt), 'MMM d, yyyy')}
                      </td>
                      <td className="px-4 py-3">
                        <Button
                          variant="ghost" size="icon"
                          className="text-red-500 hover:text-red-700"
                          title="Revoke key pair"
                          onClick={() => {
                            if (confirm(`Revoke "${sk.name}"? Both the secret and publishable keys will stop working.`)) {
                              revokeMutation.mutate(sk.id);
                            }
                          }}
                        >
                          <Trash2 className="size-4" />
                        </Button>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {revokedKeys.length > 0 && (
        <div>
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide mb-2">Revoked</p>
          <Card>
            <CardContent className="p-0">
              <table className="w-full text-sm">
                <tbody>
                  {revokedKeys.map((k) => (
                    <tr key={k.id} className="border-b last:border-0 opacity-50">
                      <td className="px-4 py-2.5 font-medium">{k.name}</td>
                      <td className="px-4 py-2.5 font-mono text-xs text-muted-foreground">{k.prefix}•••</td>
                      <td className="px-4 py-2.5 text-xs text-muted-foreground">
                        {format(new Date(k.createdAt), 'MMM d, yyyy')}
                      </td>
                      <td className="px-4 py-2.5">
                        <span className="text-xs px-2 py-0.5 rounded-full bg-red-100 text-red-600">REVOKED</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>
        </div>
      )}

      {/* Create dialog */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>Create Key Pair</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => createMutation.mutate(d))} className="space-y-4">
            <div className="space-y-1">
              <Label>Name</Label>
              <Input placeholder="e.g. Production backend" {...register('name')} />
              {errors.name && <p className="text-xs text-red-500">{errors.name.message}</p>}
            </div>
            <div className="rounded-lg bg-gray-50 border p-3 text-xs text-muted-foreground space-y-1">
              <p>Creates a <strong>sk_{mode.toLowerCase()}_xxx</strong> (secret) and <strong>pk_{mode.toLowerCase()}_xxx</strong> (publishable) pair in <strong>{mode}</strong> mode.</p>
              <p>The secret key is shown <strong>only once</strong> — copy it immediately.</p>
            </div>
            <DialogFooter>
              <Button variant="ghost" type="button" onClick={() => setCreateOpen(false)}>Cancel</Button>
              <Button type="submit" disabled={createMutation.isPending}>Create Pair</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* New pair reveal dialog */}
      <Dialog open={!!newPair} onOpenChange={() => setNewPair(null)}>
        <DialogContent className="max-w-lg max-h-[90vh] overflow-x-hidden overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Save your secret key now</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground">
            The <strong>secret key</strong> is shown only once and cannot be recovered. The publishable key is always visible in the table.
          </p>

          {newPair && (
            <div className="space-y-4 mt-1 min-w-0 overflow-hidden">
              <KeyRow
                label="Secret Key — copy now, never shown again"
                value={newPair.secretKey.secretPlaintext ?? ''}
                redact={redactSk}
                onToggleRedact={() => setRedactSk((v) => !v)}
              />
              <KeyRow
                label="Publishable Key — safe to use in client-side code"
                value={newPair.publishableKey.plaintextKey ?? ''}
              />
            </div>
          )}

          <div className="rounded-lg bg-yellow-50 border border-yellow-200 p-3 text-xs text-yellow-800">
            Store the secret key in an environment variable (<code className="bg-yellow-100 px-1 rounded">GATEWAY_API_KEY</code>). Never commit it to source control or use it client-side.
          </div>

          <DialogFooter>
            <Button onClick={() => setNewPair(null)}>I've saved both keys</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
