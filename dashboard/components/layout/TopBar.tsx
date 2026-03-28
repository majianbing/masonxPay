'use client';

import { useRouter } from 'next/navigation';
import { LogOut } from 'lucide-react';
import { toast } from 'sonner';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import ModeToggle from './ModeToggle';

export default function TopBar() {
  const router = useRouter();
  const user = useAuthStore((s) => s.user);
  const refreshToken = useAuthStore((s) => s.refreshToken);
  const logout = useAuthStore((s) => s.logout);

  async function handleLogout() {
    try {
      if (refreshToken) {
        await apiFetch('/api/v1/auth/logout', {
          method: 'POST',
          body: JSON.stringify({ refreshToken }),
        });
      }
    } catch {
      // ignore
    }
    logout();
    document.cookie = 'gw_session=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT';
    router.push('/login');
    toast.success('Signed out');
  }

  return (
    <header className="h-14 border-b bg-white flex items-center px-6 gap-4 shrink-0">
      <div className="flex-1" />
      <ModeToggle />
      <span className="text-sm text-muted-foreground">{user?.email}</span>
      <Button variant="ghost" size="icon" onClick={handleLogout} title="Sign out">
        <LogOut className="size-4" />
      </Button>
    </header>
  );
}
