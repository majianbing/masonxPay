'use client';

import { Suspense, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { toast } from 'sonner';
import { ShieldCheck } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { useAuthStore, OrgMembership } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';

const credSchema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
});
const mfaSchema = z.object({
  code: z.string().min(6).max(8),
});
type CredData = z.infer<typeof credSchema>;
type MfaData = z.infer<typeof mfaSchema>;

interface LoginResponse {
  accessToken: string | null;
  refreshToken: string | null;
  userId: string | null;
  email: string | null;
  memberships: OrgMembership[] | null;
  mfaRequired: boolean;
  mfaSessionToken: string | null;
  mfaEnabled: boolean;
}

export default function LoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const setAuth = useAuthStore((s) => s.setAuth);

  const [loading, setLoading] = useState(false);
  const [mfaSessionToken, setMfaSessionToken] = useState<string | null>(null);
  const [useBackupCode, setUseBackupCode] = useState(false);

  const credForm = useForm<CredData>({ resolver: zodResolver(credSchema) });
  const mfaForm = useForm<MfaData>({ resolver: zodResolver(mfaSchema) });

  async function onCredSubmit(data: CredData) {
    setLoading(true);
    try {
      const res = await apiFetch<LoginResponse>('/api/v1/auth/login', {
        method: 'POST',
        body: JSON.stringify(data),
        skipAuth: true,
      });

      if (res.mfaRequired && res.mfaSessionToken) {
        setMfaSessionToken(res.mfaSessionToken);
        return;
      }

      completeLogin(res);
    } catch (err: unknown) {
      const e = err as { detail?: string; title?: string };
      toast.error(e.detail ?? e.title ?? 'Login failed');
    } finally {
      setLoading(false);
    }
  }

  async function onMfaSubmit(data: MfaData) {
    if (!mfaSessionToken) return;
    setLoading(true);
    try {
      const res = await apiFetch<LoginResponse>('/api/v1/auth/mfa/verify', {
        method: 'POST',
        body: JSON.stringify({ mfaSessionToken, code: data.code }),
        skipAuth: true,
      });
      completeLogin(res);
    } catch (err: unknown) {
      const e = err as { detail?: string; title?: string };
      toast.error(e.detail ?? e.title ?? 'Invalid code');
    } finally {
      setLoading(false);
    }
  }

  function completeLogin(res: LoginResponse) {
    setAuth(
      { id: res.userId!, email: res.email!, mfaEnabled: res.mfaEnabled },
      res.accessToken!,
      res.refreshToken!,
      res.memberships ?? [],
    );
    document.cookie = 'gw_session=1; path=/; SameSite=Lax';
    router.push(searchParams.get('next') ?? '/overview');
  }

  // ── Step 2: MFA code ──────────────────────────────────────────────────────
  if (mfaSessionToken) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Card className="w-full max-w-md">
          <CardHeader>
            <div className="flex items-center gap-2">
              <ShieldCheck className="size-5 text-primary" />
              <CardTitle>Two-factor authentication</CardTitle>
            </div>
            <CardDescription>
              {useBackupCode
                ? 'Enter one of your backup codes (e.g. ABCD-1234).'
                : 'Open your authenticator app and enter the 6-digit code.'}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={mfaForm.handleSubmit(onMfaSubmit)} className="space-y-4">
              <div className="space-y-1">
                <Label>{useBackupCode ? 'Backup code' : 'Authentication code'}</Label>
                <Input
                  autoFocus
                  autoComplete="one-time-code"
                  inputMode={useBackupCode ? 'text' : 'numeric'}
                  placeholder={useBackupCode ? 'XXXX-XXXX' : '000000'}
                  {...mfaForm.register('code')}
                />
                {mfaForm.formState.errors.code && (
                  <p className="text-xs text-red-500">{mfaForm.formState.errors.code.message}</p>
                )}
              </div>
              <Button type="submit" className="w-full" disabled={loading}>
                {loading ? 'Verifying…' : 'Verify'}
              </Button>
              <button
                type="button"
                className="w-full text-center text-sm text-muted-foreground hover:text-foreground"
                onClick={() => { setUseBackupCode((b) => !b); mfaForm.reset(); }}
              >
                {useBackupCode ? '← Use authenticator app instead' : 'Use a backup code instead'}
              </button>
              <button
                type="button"
                className="w-full text-center text-sm text-muted-foreground hover:text-foreground"
                onClick={() => setMfaSessionToken(null)}
              >
                ← Back to login
              </button>
            </form>
          </CardContent>
        </Card>
      </div>
    );
  }

  // ── Step 1: credentials ───────────────────────────────────────────────────
  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>Sign in to MasonXPay</CardTitle>
          <CardDescription>Enter your credentials to access your dashboard</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={credForm.handleSubmit(onCredSubmit)} className="space-y-4">
            <div className="space-y-1">
              <Label htmlFor="email">Email</Label>
              <Input id="email" type="email" autoComplete="email" {...credForm.register('email')} />
              {credForm.formState.errors.email && (
                <p className="text-xs text-red-500">{credForm.formState.errors.email.message}</p>
              )}
            </div>
            <div className="space-y-1">
              <Label htmlFor="password">Password</Label>
              <Input id="password" type="password" autoComplete="current-password" {...credForm.register('password')} />
              {credForm.formState.errors.password && (
                <p className="text-xs text-red-500">{credForm.formState.errors.password.message}</p>
              )}
            </div>
            <Button type="submit" className="w-full" disabled={loading}>
              {loading ? 'Signing in…' : 'Sign in'}
            </Button>
            <p className="text-center text-sm text-muted-foreground">
              No account?{' '}
              <Link href="/register" className="underline">Register</Link>
            </p>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
