'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { ShieldAlert, X } from 'lucide-react';
import { useAuthStore } from '@/store/auth';

const DISMISS_KEY = 'mfa_banner_dismissed';

export default function MfaWarningBanner() {
  const user = useAuthStore((s) => s.user);
  const router = useRouter();
  const [dismissed, setDismissed] = useState(true); // start hidden to avoid flash

  useEffect(() => {
    setDismissed(sessionStorage.getItem(DISMISS_KEY) === '1');
  }, []);

  if (!user || user.mfaEnabled || dismissed) return null;

  function dismiss() {
    sessionStorage.setItem(DISMISS_KEY, '1');
    setDismissed(true);
  }

  return (
    <div className="flex items-center gap-3 bg-amber-50 border-b border-amber-200 px-6 py-2.5 text-sm text-amber-800">
      <ShieldAlert className="size-4 shrink-0 text-amber-600" />
      <span className="flex-1">
        Your account doesn&apos;t have two-factor authentication enabled.
        We strongly recommend setting it up to protect your account.
      </span>
      <button
        className="text-amber-700 underline hover:text-amber-900 font-medium whitespace-nowrap"
        onClick={() => router.push('/settings/security')}
      >
        Set up now
      </button>
      <button
        className="ml-2 text-amber-600 hover:text-amber-900"
        aria-label="Dismiss"
        onClick={dismiss}
      >
        <X className="size-4" />
      </button>
    </div>
  );
}
