'use client';

import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ShieldCheck, ShieldOff, Copy, Check } from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';

type Step = 'idle' | 'qr' | 'backup' | 'disable';

interface SetupData { secret: string; qrCodeUri: string; }

export default function SecurityPage() {
  const user = useAuthStore((s) => s.user);
  const setMfaEnabled = useAuthStore((s) => s.setMfaEnabled);
  const mfaEnabled = user?.mfaEnabled ?? false;

  const [step, setStep] = useState<Step>('idle');
  const [setupData, setSetupData] = useState<SetupData | null>(null);
  const [backupCodes, setBackupCodes] = useState<string[]>([]);
  const [confirmCode, setConfirmCode] = useState('');
  const [disableCode, setDisableCode] = useState('');
  const [copiedSecret, setCopiedSecret] = useState(false);
  const [copiedCodes, setCopiedCodes] = useState(false);

  // ── Setup ─────────────────────────────────────────────────────────────────

  const setupMutation = useMutation({
    mutationFn: () => apiFetch<SetupData>('/api/v1/auth/mfa/setup', { method: 'POST' }),
    onSuccess: (data) => { setSetupData(data); setStep('qr'); },
    onError: () => toast.error('Failed to start MFA setup'),
  });

  const confirmMutation = useMutation({
    mutationFn: (code: string) =>
      apiFetch<{ backupCodes: string[] }>('/api/v1/auth/mfa/confirm', {
        method: 'POST',
        body: JSON.stringify({ code }),
      }),
    onSuccess: (data) => {
      setBackupCodes(data.backupCodes);
      setMfaEnabled(true);
      setStep('backup');
      setConfirmCode('');
    },
    onError: () => toast.error('Invalid code — try again'),
  });

  // ── Disable ───────────────────────────────────────────────────────────────

  const disableMutation = useMutation({
    mutationFn: (code: string) =>
      apiFetch('/api/v1/auth/mfa', { method: 'DELETE', body: JSON.stringify({ code }) }),
    onSuccess: () => {
      setMfaEnabled(false);
      setStep('idle');
      setDisableCode('');
      toast.success('MFA disabled');
    },
    onError: () => toast.error('Invalid code — try again'),
  });

  // ── Copy helpers ──────────────────────────────────────────────────────────

  function copySecret() {
    if (!setupData) return;
    navigator.clipboard.writeText(setupData.secret);
    setCopiedSecret(true);
    setTimeout(() => setCopiedSecret(false), 2000);
  }

  function copyBackupCodes() {
    navigator.clipboard.writeText(backupCodes.join('\n'));
    setCopiedCodes(true);
    setTimeout(() => setCopiedCodes(false), 2000);
  }

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div className="space-y-6 max-w-2xl">
      <div>
        <h1 className="text-2xl font-semibold">Security</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Manage two-factor authentication for your account.
        </p>
      </div>

      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            {mfaEnabled
              ? <ShieldCheck className="size-5 text-green-600" />
              : <ShieldOff className="size-5 text-amber-500" />}
            <CardTitle className="text-base">Authenticator app (TOTP)</CardTitle>
          </div>
          <CardDescription>
            {mfaEnabled
              ? 'Two-factor authentication is active. Each login requires a code from your authenticator app.'
              : 'Add an extra layer of security. Works with Google Authenticator, Microsoft Authenticator, and any TOTP-compatible app.'}
          </CardDescription>
        </CardHeader>

        <CardContent>
          {/* ── Status badge ─────────────────────────────────────────────── */}
          {step === 'idle' && (
            <div className="space-y-4">
              <div className="flex items-center gap-2">
                <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                  mfaEnabled ? 'bg-green-100 text-green-700' : 'bg-amber-100 text-amber-700'
                }`}>
                  {mfaEnabled ? 'Enabled' : 'Not enabled'}
                </span>
              </div>
              {mfaEnabled ? (
                <Button variant="destructive" size="sm" onClick={() => setStep('disable')}>
                  Disable MFA
                </Button>
              ) : (
                <Button onClick={() => setupMutation.mutate()} disabled={setupMutation.isPending}>
                  {setupMutation.isPending ? 'Setting up…' : 'Set up authenticator'}
                </Button>
              )}
            </div>
          )}

          {/* ── QR code step ─────────────────────────────────────────────── */}
          {step === 'qr' && setupData && (
            <div className="space-y-6">
              <div>
                <p className="text-sm font-medium mb-3">
                  1. Scan this QR code with your authenticator app
                </p>
                <div className="inline-block p-4 bg-white border rounded-xl">
                  <QRCodeSVG value={setupData.qrCodeUri} size={180} />
                </div>
              </div>

              <div className="space-y-1">
                <p className="text-sm font-medium">
                  Or enter the setup key manually
                </p>
                <div className="flex items-center gap-2">
                  <code className="flex-1 bg-gray-50 border rounded-md px-3 py-2 text-xs font-mono tracking-widest select-all">
                    {setupData.secret}
                  </code>
                  <Button variant="outline" size="icon" onClick={copySecret} title="Copy key">
                    {copiedSecret ? <Check className="size-4 text-green-600" /> : <Copy className="size-4" />}
                  </Button>
                </div>
              </div>

              <div className="space-y-2">
                <p className="text-sm font-medium">
                  2. Enter the 6-digit code from your app to confirm
                </p>
                <div className="flex gap-2">
                  <Input
                    autoFocus
                    inputMode="numeric"
                    placeholder="000000"
                    maxLength={6}
                    value={confirmCode}
                    onChange={(e) => setConfirmCode(e.target.value.replace(/\D/g, ''))}
                    className="w-36"
                  />
                  <Button
                    onClick={() => confirmMutation.mutate(confirmCode)}
                    disabled={confirmCode.length < 6 || confirmMutation.isPending}
                  >
                    {confirmMutation.isPending ? 'Verifying…' : 'Activate MFA'}
                  </Button>
                  <Button variant="ghost" onClick={() => { setStep('idle'); setSetupData(null); }}>
                    Cancel
                  </Button>
                </div>
              </div>
            </div>
          )}

          {/* ── Backup codes step ─────────────────────────────────────────── */}
          {step === 'backup' && (
            <div className="space-y-4">
              <div className="bg-amber-50 border border-amber-200 rounded-lg p-4 text-sm text-amber-800">
                <p className="font-semibold mb-1">Save these backup codes now</p>
                <p>Each code can be used once if you lose access to your authenticator app. Store them somewhere safe.</p>
              </div>

              <div className="grid grid-cols-2 gap-2">
                {backupCodes.map((code) => (
                  <code key={code} className="bg-gray-50 border rounded-md px-3 py-2 text-xs font-mono text-center tracking-widest">
                    {code}
                  </code>
                ))}
              </div>

              <div className="flex gap-2">
                <Button variant="outline" size="sm" onClick={copyBackupCodes}>
                  {copiedCodes ? <><Check className="size-4 mr-1.5 text-green-600" />Copied</> : <><Copy className="size-4 mr-1.5" />Copy all</>}
                </Button>
                <Button
                  size="sm"
                  onClick={() => { setStep('idle'); setBackupCodes([]); }}
                >
                  Done
                </Button>
              </div>
            </div>
          )}

          {/* ── Disable step ─────────────────────────────────────────────── */}
          {step === 'disable' && (
            <div className="space-y-4">
              <p className="text-sm text-muted-foreground">
                Enter your current authenticator code or a backup code to disable MFA.
              </p>
              <div className="flex gap-2">
                <Input
                  autoFocus
                  placeholder="Code"
                  value={disableCode}
                  onChange={(e) => setDisableCode(e.target.value)}
                  className="w-40"
                />
                <Button
                  variant="destructive"
                  onClick={() => disableMutation.mutate(disableCode)}
                  disabled={!disableCode || disableMutation.isPending}
                >
                  {disableMutation.isPending ? 'Disabling…' : 'Disable MFA'}
                </Button>
                <Button variant="ghost" onClick={() => { setStep('idle'); setDisableCode(''); }}>
                  Cancel
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
