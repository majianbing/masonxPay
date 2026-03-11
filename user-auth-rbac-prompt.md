# User & Auth Architecture — Payment Gateway

---

## Overview: Two Separate User Realms

A critical architectural decision: the platform has **two completely separate user systems** with different auth flows, permission models, and security requirements. They do NOT share a `User` table.

```
┌─────────────────────────────────┐    ┌─────────────────────────────────┐
│        PLATFORM ADMIN           │    │         MERCHANT PORTAL          │
│     (Admin Dashboard)           │    │      (Merchant Dashboard)        │
│                                 │    │                                  │
│  Super Admin                    │    │  Merchant Owner                  │
│  Platform Ops                   │    │  Merchant Developer              │
│  Finance / Compliance           │    │  Merchant Finance                │
│                                 │    │  Merchant Viewer                 │
│  → manage all merchants         │    │  → manage their own merchant     │
│  → approve KYB                  │    │  → team member management        │
│  → platform-wide reports        │    │  → API keys, webhooks, routing   │
│  → system config                │    │                                  │
└─────────────────────────────────┘    └─────────────────────────────────┘
         Separate auth realm                   Separate auth realm
         (internal tool, post-MVP)             (public-facing, MVP)
```

---

## 1. Merchant Portal — User & RBAC

### 1.1 Domain Model

#### `User`
Represents a person who registers and logs into the Merchant Portal.

```
User
  id (UUID)
  email
  passwordHash
  status        (ACTIVE | SUSPENDED)
  createdAt / updatedAt
```

#### `MerchantUser`
Join table — a user can belong to multiple merchants with different roles.

```
MerchantUser
  id (UUID)
  userId
  merchantId
  role          (OWNER | ADMIN | DEVELOPER | FINANCE | VIEWER)
  invitedBy     (userId, nullable — null if self-registered as OWNER)
  status        (PENDING_INVITE | ACTIVE | REVOKED)
  createdAt / updatedAt
```

---

### 1.2 Roles

Five pre-defined roles. Custom roles are post-MVP.

| Role | Description |
|---|---|
| `OWNER` | Full control. Can delete the merchant, manage billing, invite/revoke members |
| `ADMIN` | Full control except merchant deletion and billing |
| `DEVELOPER` | API keys, webhooks, routing rules, logs. No financial actions |
| `FINANCE` | Payments, refunds, chargebacks. No config changes |
| `VIEWER` | Read-only access across all resources |

---

### 1.3 Resources & Actions

```
Resources:
  PAYMENT | REFUND | CHARGEBACK
  API_KEY | WEBHOOK | ROUTING_RULE
  LOG | MEMBER | MERCHANT_SETTINGS

Actions:
  READ | CREATE | UPDATE | DELETE | EXECUTE
```

---

### 1.4 Role → Permission Map

Hardcoded in application code (not DB-driven) for MVP. Migrate to DB-driven in Phase 2.

| Resource | Action | OWNER | ADMIN | DEVELOPER | FINANCE | VIEWER |
|---|---|:---:|:---:|:---:|:---:|:---:|
| PAYMENT | READ | ✅ | ✅ | ✅ | ✅ | ✅ |
| PAYMENT | CREATE / EXECUTE | ✅ | ✅ | ❌ | ✅ | ❌ |
| REFUND | ALL | ✅ | ✅ | ❌ | ✅ | ❌ |
| CHARGEBACK | ALL | ✅ | ✅ | ❌ | ✅ | ❌ |
| API_KEY | ALL | ✅ | ✅ | ✅ | ❌ | ❌ |
| WEBHOOK | ALL | ✅ | ✅ | ✅ | ❌ | ❌ |
| ROUTING_RULE | ALL | ✅ | ✅ | ✅ | ❌ | ❌ |
| LOG | READ | ✅ | ✅ | ✅ | ✅ | ✅ |
| MEMBER | ALL | ✅ | ✅ | ❌ | ❌ | ❌ |
| MERCHANT_SETTINGS | READ | ✅ | ✅ | ✅ | ✅ | ✅ |
| MERCHANT_SETTINGS | UPDATE | ✅ | ✅ | ❌ | ❌ | ❌ |
| MERCHANT_SETTINGS | DELETE | ✅ | ❌ | ❌ | ❌ | ❌ |

---

### 1.5 Spring Security Implementation

Use method-level `@PreAuthorize` annotations backed by a custom `PermissionEvaluator`.

```java
// Custom annotation for cleaner usage
@PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
public PaymentIntent getPaymentIntent(UUID merchantId, UUID paymentIntentId) { ... }

@PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'API_KEY', 'CREATE')")
public ApiKey createApiKey(UUID merchantId, CreateApiKeyRequest req) { ... }
```

```java
// PermissionEvaluator resolves: current user → MerchantUser role → RolePermissionMap → allow/deny
@Component
public class GatewayPermissionEvaluator implements PermissionEvaluator {
    public boolean hasPermission(Authentication auth, UUID merchantId, String resource, String action) {
        UUID userId = extractUserId(auth);
        MerchantUser membership = merchantUserRepo.findByUserIdAndMerchantId(userId, merchantId);
        if (membership == null || membership.getStatus() != ACTIVE) return false;
        return RolePermissionMap.allows(membership.getRole(), resource, action);
    }
}
```

**JWT claims for Merchant Portal:**
```json
{
  "sub": "user-uuid",
  "email": "mason@example.com",
  "type": "MERCHANT_USER",
  "iat": 1710000000,
  "exp": 1710086400
}
```

Merchant membership and role are resolved from DB on each request — not embedded in JWT — to ensure revocation takes effect immediately.

---

### 1.6 Team Invite Flow

```
OWNER / ADMIN sends invite
  → POST /api/v1/merchants/{id}/members { email, role }
  → MerchantUser created with status = PENDING_INVITE
  → Email sent with signed invite token (expires 48h)

Invitee accepts
  → GET /api/v1/invites/{token}/accept
  → If user exists: MerchantUser status → ACTIVE
  → If new user: create User first, then activate MerchantUser
```

---

### 1.7 MVP Auth Endpoints

```
POST  /api/v1/auth/register          ← self-register, creates User + Merchant + OWNER MerchantUser
POST  /api/v1/auth/login             ← returns JWT access token + refresh token
POST  /api/v1/auth/refresh           ← rotate refresh token
POST  /api/v1/auth/logout

GET   /api/v1/merchants/{id}/members         ← list team members
POST  /api/v1/merchants/{id}/members         ← invite member
PATCH /api/v1/merchants/{id}/members/{uid}   ← update role
DELETE /api/v1/merchants/{id}/members/{uid}  ← revoke access

GET   /api/v1/invites/{token}        ← get invite info
POST  /api/v1/invites/{token}/accept ← accept invite
```

---

## 2. Platform Admin — Deferred (Post-MVP)

Design the boundary now to avoid painful migrations later. The `AdminUser` table is created in the initial Flyway migration but has no UI in MVP.

### 2.1 Domain Model

#### `AdminUser`
Completely separate from `User`. Internal staff only.

```
AdminUser
  id (UUID)
  email
  passwordHash
  role          (SUPER_ADMIN | OPS | FINANCE | COMPLIANCE)
  status        (ACTIVE | SUSPENDED)
  mfaEnabled    (boolean) ← enforce MFA for all admin users
  createdAt / updatedAt
```

#### `AdminAuditLog`
Every admin action is immutably logged.

```
AdminAuditLog
  id (UUID)
  adminUserId
  action        (e.g. APPROVE_KYB, SUSPEND_MERCHANT, RESET_API_KEY)
  resourceType
  resourceId
  before        (JSONB)
  after         (JSONB)
  ipAddress
  createdAt
```

### 2.2 Admin Roles (Post-MVP Reference)

| Role | Responsibilities |
|---|---|
| `SUPER_ADMIN` | Full platform access, manage admin users, system config |
| `OPS` | Merchant management, KYB approval, dispute handling |
| `FINANCE` | Platform-wide financial reports, payout management |
| `COMPLIANCE` | KYB review, chargeback oversight, audit log access |

### 2.3 MVP Approach

- `AdminUser` and `AdminAuditLog` tables created via Flyway migration from day one
- Seed one `SUPER_ADMIN` record via Flyway data migration or bootstrap script
- No Admin Dashboard UI in MVP
- Admin JWT auth endpoint exists but is not publicly documented

---

## 3. Scope Summary

### MVP (Phase 1)
- `User` registration and JWT login
- `MerchantUser` with 5 hardcoded roles
- Method-level `@PreAuthorize` permission checks
- Team invite flow (email → accept)
- `AdminUser` table seeded, no UI

### Phase 2
- Admin Dashboard UI (KYB approval, merchant management, platform reports)
- DB-driven custom permissions (resource/action managed via UI)
- MFA enforcement for admin and optionally merchant owners
- Admin audit log UI
- SSO / OAuth2 login for merchant portal
