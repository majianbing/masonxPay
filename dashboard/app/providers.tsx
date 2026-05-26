'use client';

import { QueryClientProvider } from '@tanstack/react-query';
import { getQueryClient } from '@/lib/queryClient';
import { Toaster } from '@/components/ui/sonner';
import AuthSessionBridge from '@/components/auth-session-bridge';

export default function Providers({ children }: { children: React.ReactNode }) {
  const queryClient = getQueryClient();
  return (
    <QueryClientProvider client={queryClient}>
      <AuthSessionBridge />
      {children}
      <Toaster richColors position="top-right" />
    </QueryClientProvider>
  );
}
