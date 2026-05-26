'use client';

import { useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { setOnTokenRefreshFailed } from '@/lib/api';
import { useAuthStore } from '@/store/auth';

const AUTH_PATHS = ['/login', '/register'];

export default function AuthSessionBridge() {
  const router = useRouter();
  const pathname = usePathname();
  const logout = useAuthStore((s) => s.logout);

  useEffect(() => {
    setOnTokenRefreshFailed(() => {
      logout();
      document.cookie = 'gw_session=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT';

      if (!AUTH_PATHS.some((path) => pathname.startsWith(path))) {
        router.replace(`/login?next=${encodeURIComponent(pathname)}`);
      }
    });

    return () => setOnTokenRefreshFailed(() => {});
  }, [logout, pathname, router]);

  return null;
}
