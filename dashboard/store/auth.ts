'use client';

import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { setTokens, clearTokens } from '@/lib/api';

export type AppMode = 'TEST' | 'LIVE';

export interface AuthUser {
  id: string;
  email: string;
  mfaEnabled: boolean;
}

export interface MerchantMembership {
  merchantId: string;
  merchantName: string;
  role: string;
}

export interface OrgMembership {
  organizationId: string;
  organizationName: string;
  orgRole: string;
  merchants: MerchantMembership[];
}

interface AuthState {
  user: AuthUser | null;
  accessToken: string | null;
  refreshToken: string | null;
  memberships: OrgMembership[];
  activeOrgId: string | null;
  activeMerchantId: string | null;
  mode: AppMode;

  setAuth: (
    user: AuthUser,
    accessToken: string,
    refreshToken: string,
    memberships: OrgMembership[],
  ) => void;
  setMfaEnabled: (enabled: boolean) => void;
  setMemberships: (memberships: OrgMembership[]) => void;
  setActiveMerchant: (orgId: string, merchantId: string) => void;
  toggleMode: () => void;
  logout: () => void;
}

/** Derive default org+merchant from memberships list */
function defaultSelection(memberships: OrgMembership[]) {
  const firstOrg = memberships[0];
  const firstMerchant = firstOrg?.merchants[0];
  return {
    activeOrgId: firstOrg?.organizationId ?? null,
    activeMerchantId: firstMerchant?.merchantId ?? null,
  };
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      user: null,
      accessToken: null,
      refreshToken: null,
      memberships: [],
      activeOrgId: null,
      activeMerchantId: null,
      mode: 'TEST',

      setAuth: (user, accessToken, refreshToken, memberships) => {
        setTokens(accessToken, refreshToken);
        set({
          user,
          accessToken,
          refreshToken,
          memberships,
          ...defaultSelection(memberships),
        });
      },

      setMfaEnabled: (enabled) =>
        set((s) => ({ user: s.user ? { ...s.user, mfaEnabled: enabled } : null })),

      setMemberships: (memberships) =>
        set((s) => ({
          memberships,
          // Only reset selection if current selections are no longer valid
          ...(memberships.some((o) => o.organizationId === s.activeOrgId &&
              o.merchants.some((m) => m.merchantId === s.activeMerchantId))
            ? {}
            : defaultSelection(memberships)),
        })),

      setActiveMerchant: (orgId, merchantId) =>
        set({ activeOrgId: orgId, activeMerchantId: merchantId }),

      toggleMode: () =>
        set((s) => ({ mode: s.mode === 'TEST' ? 'LIVE' : 'TEST' })),

      logout: () => {
        clearTokens();
        set({
          user: null,
          accessToken: null,
          refreshToken: null,
          memberships: [],
          activeOrgId: null,
          activeMerchantId: null,
        });
      },
    }),
    {
      name: 'gateway-auth',
      onRehydrateStorage: () => (state) => {
        // Sync persisted tokens into the apiFetch module after page reload
        if (state?.accessToken && state?.refreshToken) {
          setTokens(state.accessToken, state.refreshToken);
        }
      },
    },
  ),
);
