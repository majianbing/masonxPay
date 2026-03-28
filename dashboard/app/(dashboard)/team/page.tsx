'use client';

import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { toast } from 'sonner';
import { Plus, Trash2 } from 'lucide-react';
import { format } from 'date-fns';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent } from '@/components/ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

interface Member {
  userId: string;
  email: string;
  role: string;
  status: string;
  createdAt: string;
}

const ROLES = ['ADMIN', 'DEVELOPER', 'FINANCE', 'VIEWER'];

const inviteSchema = z.object({
  email: z.string().email(),
  role: z.enum(['ADMIN', 'DEVELOPER', 'FINANCE', 'VIEWER']),
});
type InviteForm = z.infer<typeof inviteSchema>;

export default function TeamPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const qc = useQueryClient();
  const [inviteOpen, setInviteOpen] = useState(false);

  const { data: members = [], isLoading } = useQuery<Member[]>({
    queryKey: ['members', activeMerchantId],
    queryFn: () => apiFetch<Member[]>(`/api/v1/merchants/${activeMerchantId}/members`),
    enabled: !!activeMerchantId,
  });

  const { register, handleSubmit, setValue, reset, formState: { errors } } = useForm<InviteForm>({
    resolver: zodResolver(inviteSchema),
    defaultValues: { role: 'DEVELOPER' },
  });

  const inviteMutation = useMutation({
    mutationFn: (data: InviteForm) =>
      apiFetch(`/api/v1/merchants/${activeMerchantId}/members`, {
        method: 'POST',
        body: JSON.stringify(data),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['members', activeMerchantId] });
      setInviteOpen(false);
      reset();
      toast.success('Invite sent');
    },
    onError: (err: unknown) => {
      const e = err as { detail?: string };
      toast.error(e.detail ?? 'Failed to send invite');
    },
  });

  const revokeMutation = useMutation({
    mutationFn: (userId: string) =>
      apiFetch(`/api/v1/merchants/${activeMerchantId}/members/${userId}`, { method: 'DELETE' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['members', activeMerchantId] });
      toast.success('Access revoked');
    },
  });

  const roleColor: Record<string, string> = {
    OWNER: 'bg-purple-100 text-purple-700',
    ADMIN: 'bg-blue-100 text-blue-700',
    DEVELOPER: 'bg-cyan-100 text-cyan-700',
    FINANCE: 'bg-green-100 text-green-700',
    VIEWER: 'bg-gray-100 text-gray-600',
  };

  const statusColor: Record<string, string> = {
    ACTIVE: 'bg-green-100 text-green-700',
    PENDING_INVITE: 'bg-yellow-100 text-yellow-700',
    REVOKED: 'bg-red-100 text-red-700',
  };

  return (
    <div className="space-y-6 max-w-3xl">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Team</h1>
        <Button onClick={() => setInviteOpen(true)}>
          <Plus className="size-4 mr-1" /> Invite Member
        </Button>
      </div>

      <Card>
        <CardContent className="p-0">
          <table className="w-full text-sm">
            <thead className="border-b bg-gray-50">
              <tr>
                {['Email', 'Role', 'Status', 'Joined', ''].map((h) => (
                  <th key={h} className="px-4 py-3 text-left font-medium text-muted-foreground">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr><td colSpan={5} className="py-8 text-center text-muted-foreground">Loading…</td></tr>
              ) : members.map((m) => (
                <tr key={m.userId} className="border-b last:border-0">
                  <td className="px-4 py-3">{m.email}</td>
                  <td className="px-4 py-3">
                    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${roleColor[m.role] ?? 'bg-gray-100'}`}>
                      {m.role}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`text-xs px-2 py-0.5 rounded-full ${statusColor[m.status] ?? 'bg-gray-100'}`}>
                      {m.status.replace(/_/g, ' ')}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">
                    {format(new Date(m.createdAt), 'MMM d, yyyy')}
                  </td>
                  <td className="px-4 py-3">
                    {m.role !== 'OWNER' && m.status !== 'REVOKED' && (
                      <Button
                        variant="ghost"
                        size="icon"
                        className="text-red-500"
                        onClick={() => {
                          if (confirm(`Revoke access for ${m.email}?`)) revokeMutation.mutate(m.userId);
                        }}
                      >
                        <Trash2 className="size-4" />
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      <Dialog open={inviteOpen} onOpenChange={setInviteOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>Invite Team Member</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => inviteMutation.mutate(d))} className="space-y-4">
            <div className="space-y-1">
              <Label>Email</Label>
              <Input type="email" placeholder="colleague@company.com" {...register('email')} />
              {errors.email && <p className="text-xs text-red-500">{errors.email.message}</p>}
            </div>
            <div className="space-y-1">
              <Label>Role</Label>
              <Select defaultValue="DEVELOPER" onValueChange={(v: string | null) => setValue('role', (v ?? 'DEVELOPER') as InviteForm['role'])}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ROLES.map((r) => <SelectItem key={r} value={r}>{r}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <DialogFooter>
              <Button variant="ghost" type="button" onClick={() => setInviteOpen(false)}>Cancel</Button>
              <Button type="submit" disabled={inviteMutation.isPending}>Send Invite</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
