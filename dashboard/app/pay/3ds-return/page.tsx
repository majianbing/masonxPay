'use client';

/**
 * 3DS Return Page
 *
 * Stripe redirects here after a 3DS challenge completes (both success and failure).
 * This page is opened inside the SDK's iframe overlay, so it must send a postMessage
 * to the parent window then show a neutral "completing payment" spinner.
 *
 * URL params injected by Stripe:
 *   payment_intent_client_secret  — PI client secret (not needed here; used by the SDK caller)
 *   redirect_status               — "succeeded" | "failed" | "canceled"
 *
 * Custom param passed by our SDK when it set return_url:
 *   linkToken                     — payment link token so the parent can poll the right PI
 *
 * useSearchParams() requires a Suspense boundary in Next.js App Router.
 * The Spinner component is used both as the visible UI and as the Suspense fallback.
 */

import { Suspense, useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';

function Spinner() {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        height: '100vh',
        fontFamily: 'system-ui, sans-serif',
        color: '#374151',
        background: '#ffffff',
      }}
    >
      <svg
        style={{ marginBottom: 16, animation: 'spin 1s linear infinite' }}
        width="40"
        height="40"
        viewBox="0 0 40 40"
        fill="none"
      >
        <circle cx="20" cy="20" r="16" stroke="#e5e7eb" strokeWidth="4" />
        <path
          d="M20 4a16 16 0 0 1 16 16"
          stroke="#6366f1"
          strokeWidth="4"
          strokeLinecap="round"
        />
        <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
      </svg>
      <p style={{ margin: 0, fontSize: 15 }}>Completing your payment&hellip;</p>
    </div>
  );
}

function ThreeDsReturnInner() {
  const searchParams = useSearchParams();
  const [sent, setSent] = useState(false);

  useEffect(() => {
    // Only message once (StrictMode double-invoke guard)
    if (sent) return;
    setSent(true);

    const linkToken                 = searchParams.get('linkToken') ?? undefined;
    const redirectStatus            = searchParams.get('redirect_status') ?? undefined;
    const paymentIntentClientSecret = searchParams.get('payment_intent_client_secret') ?? undefined;

    try {
      window.parent.postMessage(
        { type: 'gw:3ds_complete', linkToken, redirectStatus, paymentIntentClientSecret },
        '*',
      );
    } catch {
      // postMessage can fail if opener is gone — nothing to do
    }
  }, [searchParams, sent]);

  return <Spinner />;
}

export default function ThreeDsReturnPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <ThreeDsReturnInner />
    </Suspense>
  );
}
