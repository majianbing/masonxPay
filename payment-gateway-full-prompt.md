# Stripe-Like Multi-Router Payment Gateway — Full Implementation Prompt

---

## 🎯 Project Overview

Build a **production-grade, Stripe-like payment gateway** with multi-provider routing as its core differentiator. The system allows merchants to accept payments through multiple payment providers (Stripe, PayPal, etc.) with configurable routing rules, full payment lifecycle management, webhook event delivery, and a merchant-facing dashboard.

---

## 🧱 System Architecture

```
┌─────────────────────────────────────────────────────┐
│                  Merchant Website                    │
│         (integrates via TypeScript SDK)              │
└──────────────┬──────────────────────────────────────┘
               │  @gateway/js (browser SDK)
               ▼
┌─────────────────────────────────────────────────────┐
│              Payment Gateway API                     │
│         (Spring Boot 3.x / JDK 21)                  │
│                                                      │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │  Payment    │  │   Routing    │  │  Webhook   │ │
│  │  Service    │  │   Engine     │  │  Delivery  │ │
│  └─────────────┘  └──────────────┘  └────────────┘ │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │  Merchant   │  │   Provider   │  │    Log     │ │
│  │  Service    │  │  Abstraction │  │  Service   │ │
│  └─────────────┘  └──────────────┘  └────────────┘ │
└──────────┬─────────────────┬───────────────────────┘
           │                 │
    ┌──────▼──────┐   ┌──────▼──────┐
    │   Stripe    │   │   PayPal    │
    └─────────────┘   └─────────────┘

┌─────────────────────────────────────────────────────┐
│              Merchant Dashboard                      │
│          (Next.js 15 / shadcn/ui)                   │
└─────────────────────────────────────────────────────┘
```

---

## 1. Backend Service — Java / Spring Boot

### Tech Stack

- **JDK 21** — use virtual threads via `spring.threads.virtual.enabled=true`
- **Spring Boot 3.3.x**
- **Spring Security** — API key authentication + JWT for dashboard
- **Spring Data JPA + PostgreSQL** — primary persistence
- **Spring Data Redis** — idempotency key store, rate limiting, caching
- **Spring Events / RabbitMQ or Kafka** — async webhook delivery
- **Flyway** — database migrations
- **MapStruct** — DTO mapping
- **OpenAPI 3 / Springdoc** — API documentation

---

### 1.1 Domain Model

#### `Merchant`
Core business entity. A user can own multiple merchants.

```
Merchant
  id (UUID)
  userId (owner)
  name, displayName
  status (PENDING_KYB | ACTIVE | SUSPENDED | CLOSED)
  mode   (TEST | LIVE)
  kybInfo:
    businessName, businessType
    registrationNumber, country
    documents (list of file refs)
    kybStatus (PENDING | APPROVED | REJECTED)
  defaultCurrency
  createdAt / updatedAt
```

#### `ApiKey`
Each merchant gets a publishable + secret key pair per mode.

```
ApiKey
  id (UUID)
  merchantId
  mode   (TEST | LIVE)
  type   (PUBLISHABLE | SECRET)
  keyHash                          ← store hash only, show plaintext once
  prefix (e.g. "pk_live_", "sk_test_")
  status (ACTIVE | REVOKED)
  lastUsedAt
  createdAt / revokedAt
```

#### `Account`
Merchant's settlement / payout destination.

```
Account
  id (UUID)
  merchantId
  type     (BANK_ACCOUNT | VIRTUAL_ACCOUNT)
  currency
  bankName, accountNumber, routingNumber (encrypted at rest, AES-256)
  status   (PENDING_VERIFICATION | ACTIVE | SUSPENDED)
  createdAt / updatedAt
```

#### `RoutingRule`
Merchant-configurable rules that determine which provider handles a payment.

```
RoutingRule
  id (UUID)
  merchantId
  priority         (int — lower value = higher priority)
  conditions:
    currency       (list, e.g. ["USD", "EUR"])
    amountMin / amountMax
    countryCodes   (list)
    paymentMethodTypes (list)
  targetProvider   (STRIPE | PAYPAL | ...)
  fallbackProvider (STRIPE | PAYPAL | null)
  enabled          (boolean)
  createdAt / updatedAt
```

#### `PaymentIntent`
Central payment entity — created first, then progresses through states.

```
PaymentIntent
  id (UUID)
  merchantId
  mode   (TEST | LIVE)
  amount, currency
  status:
    REQUIRES_PAYMENT_METHOD
    REQUIRES_CONFIRMATION
    REQUIRES_ACTION          ← 3DS, redirect
    PROCESSING
    REQUIRES_CAPTURE         ← manual capture flow
    SUCCEEDED
    CANCELED
    FAILED
  captureMethod      (AUTOMATIC | MANUAL)
  idempotencyKey                   ← unique per merchant, 24hr dedup window
  resolvedProvider   (STRIPE | PAYPAL | ...)
  providerPaymentId                ← provider's own reference ID
  providerResponse   (JSONB)       ← raw provider response
  metadata           (JSONB)       ← merchant custom key-value
  successUrl, cancelUrl, failureUrl  ← per-payment redirect URLs
  expiresAt
  createdAt / updatedAt
```

#### `PaymentRequest`
Individual charge attempt under a PaymentIntent.

```
PaymentRequest
  id (UUID)
  paymentIntentId
  amount, currency
  paymentMethodType  (CARD | PAYPAL | BANK_TRANSFER | ...)
  status             (PENDING | SUCCEEDED | FAILED)
  providerRequestId
  providerResponse   (JSONB)
  failureCode, failureMessage
  createdAt
```

#### `Capture`
For manual capture flow (authorize now, capture later).

```
Capture
  id (UUID)
  paymentIntentId
  amount                           ← partial capture supported
  status (PENDING | SUCCEEDED | FAILED)
  providerCaptureId
  capturedAt
```

#### `Refund`

```
Refund
  id (UUID)
  paymentIntentId
  amount                           ← partial refunds supported
  reason (DUPLICATE | FRAUDULENT | CUSTOMER_REQUEST)
  status (PENDING | SUCCEEDED | FAILED)
  providerRefundId
  metadata (JSONB)
  createdAt / updatedAt
```

#### `Chargeback`

```
Chargeback
  id (UUID)
  paymentIntentId
  amount, currency
  reason
  status (OPEN | UNDER_REVIEW | WON | LOST)
  dueDate
  evidence           (JSONB)       ← merchant-submitted dispute evidence
  providerDisputeId
  createdAt / updatedAt
```

#### `WebhookEndpoint`
Configured at merchant level — receives all events for that merchant.

```
WebhookEndpoint
  id (UUID)
  merchantId
  url
  signingSecret                    ← HMAC-SHA256 key, shown once on creation
  subscribedEvents (list, e.g. ["payment.succeeded", "refund.created"])
  status (ACTIVE | DISABLED)
  createdAt / updatedAt
```

#### `WebhookDelivery`
Full audit trail for every webhook attempt.

```
WebhookDelivery
  id (UUID)
  webhookEndpointId
  eventId
  status       (PENDING | DELIVERED | FAILED)
  httpStatusCode
  requestBody  (JSONB)
  responseBody
  attemptCount
  nextRetryAt
  deliveredAt / createdAt
```

#### `Event`
System-generated events for webhook fan-out and internal pub/sub.

```
Event
  id (UUID)
  merchantId
  type         (e.g. "payment.succeeded", "refund.created", "chargeback.opened")
  resourceType, resourceId         ← what entity triggered it
  payload      (JSONB)
  createdAt
```

#### `Log`
Structured audit / integration logs for developer debugging.

```
Log
  id (UUID)
  merchantId
  requestId    (trace ID)
  type         (API_REQUEST | PROVIDER_CALL | WEBHOOK_DELIVERY | ROUTING_DECISION)
  method, path
  requestHeaders, requestBody
  responseStatus, responseBody
  durationMs
  apiKeyId                         ← which key made the request
  createdAt
```

---

### 1.2 Routing Engine

The core differentiator. Evaluate rules in priority order and resolve a provider per payment.

```java
public interface RoutingEngine {
    ProviderResolution resolve(PaymentContext context, List<RoutingRule> rules);
}

// PaymentContext: currency, amount, country, paymentMethodType, merchantId
// ProviderResolution: primaryProvider, fallbackProvider
```

**Routing strategies:**

- **Rule-based** — match by currency / amount range / country / payment method type
- **Fallback** — if provider call fails or times out, retry with `fallbackProvider`
- **Default** — if no rules match, use merchant's `defaultProvider` setting

---

### 1.3 Payment Provider Abstraction

```java
public interface PaymentProvider {
    String getName();
    PaymentResult   createPayment(PaymentRequest req);
    CaptureResult   capture(String providerPaymentId, long amount);
    RefundResult    refund(String providerPaymentId, long amount, String reason);
    WebhookEvent    parseWebhook(String payload, String signature);
    boolean         verifyWebhookSignature(String payload, String signature, String secret);
}

// Implementations:
// StripePaymentProvider  implements PaymentProvider
// PayPalPaymentProvider  implements PaymentProvider
```

---

### 1.4 API Design

**Authentication:**
- Dashboard users → JWT Bearer token
- Merchant server-side API calls → `Authorization: Bearer sk_live_xxxxx`
- Merchant browser SDK calls → `X-Publishable-Key: pk_live_xxxxx`

**Idempotency:**
- All `POST` mutation endpoints accept `Idempotency-Key` header
- Dedup window: 24 hours, keyed by `merchantId + idempotencyKey`, stored in Redis
- Return cached response if duplicate detected within window

**Core Endpoints:**

```
# Auth
POST   /api/v1/auth/login
POST   /api/v1/auth/refresh

# Merchants
GET    /api/v1/merchants
POST   /api/v1/merchants
GET    /api/v1/merchants/{id}
PATCH  /api/v1/merchants/{id}

# API Keys
GET    /api/v1/merchants/{id}/api-keys
POST   /api/v1/merchants/{id}/api-keys
DELETE /api/v1/merchants/{id}/api-keys/{keyId}

# Routing Rules
GET    /api/v1/merchants/{id}/routing-rules
POST   /api/v1/merchants/{id}/routing-rules
PUT    /api/v1/merchants/{id}/routing-rules/{ruleId}
DELETE /api/v1/merchants/{id}/routing-rules/{ruleId}

# Payments
POST   /api/v1/payment-intents                      ← create
GET    /api/v1/payment-intents/{id}
POST   /api/v1/payment-intents/{id}/confirm         ← confirm + route
POST   /api/v1/payment-intents/{id}/capture         ← manual capture
POST   /api/v1/payment-intents/{id}/cancel
POST   /api/v1/payment-intents/{id}/refunds         ← create refund
GET    /api/v1/payment-intents/{id}/refunds

# Webhooks (outbound config)
GET    /api/v1/webhook-endpoints
POST   /api/v1/webhook-endpoints
DELETE /api/v1/webhook-endpoints/{id}
GET    /api/v1/webhook-endpoints/{id}/deliveries

# Provider Webhooks (inbound from Stripe / PayPal)
POST   /api/v1/providers/stripe/webhook
POST   /api/v1/providers/paypal/webhook

# Logs
GET    /api/v1/logs

# Accounts
GET    /api/v1/merchants/{id}/accounts
POST   /api/v1/merchants/{id}/accounts
```

---

## 2. User & Auth Architecture

### 2.1 Two Separate User Realms

The platform has **two completely separate user systems** with different auth flows, permission models, and security requirements. They do NOT share a `User` table.

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

### 2.2 Merchant Portal — User & RBAC

#### Domain Model

##### `User`
Represents a person who registers and logs into the Merchant Portal.

```
User
  id (UUID)
  email
  passwordHash
  status        (ACTIVE | SUSPENDED)
  createdAt / updatedAt
```

##### `MerchantUser`
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

#### Roles

Five pre-defined roles. Custom roles are post-MVP.

| Role | Description |
|---|---|
| `OWNER` | Full control. Can delete the merchant, manage billing, invite/revoke members |
| `ADMIN` | Full control except merchant deletion and billing |
| `DEVELOPER` | API keys, webhooks, routing rules, logs. No financial actions |
| `FINANCE` | Payments, refunds, chargebacks. No config changes |
| `VIEWER` | Read-only access across all resources |

#### Resources & Actions

```
Resources:
  PAYMENT | REFUND | CHARGEBACK
  API_KEY | WEBHOOK | ROUTING_RULE
  LOG | MEMBER | MERCHANT_SETTINGS

Actions:
  READ | CREATE | UPDATE | DELETE | EXECUTE
```

#### Role → Permission Map

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

#### Spring Security Implementation

Use method-level `@PreAuthorize` annotations backed by a custom `PermissionEvaluator`.

```java
@PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
public PaymentIntent getPaymentIntent(UUID merchantId, UUID paymentIntentId) { ... }

@PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'API_KEY', 'CREATE')")
public ApiKey createApiKey(UUID merchantId, CreateApiKeyRequest req) { ... }
```

```java
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

> Merchant membership and role are resolved from DB on each request — not embedded in JWT — to ensure revocation takes effect immediately.

#### Team Invite Flow

```
OWNER / ADMIN sends invite
  → POST /api/v1/merchants/{id}/members { email, role }
  → MerchantUser created with status = PENDING_INVITE
  → Email sent with signed invite token (expires 48h)

Invitee accepts
  → POST /api/v1/invites/{token}/accept
  → If user exists: MerchantUser status → ACTIVE
  → If new user: create User first, then activate MerchantUser
```

#### Auth Endpoints

```
POST   /api/v1/auth/register                        ← creates User + Merchant + OWNER MerchantUser
POST   /api/v1/auth/login                           ← returns JWT access + refresh token
POST   /api/v1/auth/refresh
POST   /api/v1/auth/logout

GET    /api/v1/merchants/{id}/members
POST   /api/v1/merchants/{id}/members               ← invite member
PATCH  /api/v1/merchants/{id}/members/{uid}         ← update role
DELETE /api/v1/merchants/{id}/members/{uid}         ← revoke access

GET    /api/v1/invites/{token}
POST   /api/v1/invites/{token}/accept
```

---

### 2.3 Platform Admin — Deferred (Post-MVP)

Design the table boundary now to avoid migrations later. Tables are created in the initial Flyway migration but have no UI in MVP.

#### `AdminUser`
Completely separate from `User`. Internal staff only.

```
AdminUser
  id (UUID)
  email
  passwordHash
  role          (SUPER_ADMIN | OPS | FINANCE | COMPLIANCE)
  status        (ACTIVE | SUSPENDED)
  mfaEnabled    (boolean)          ← enforce MFA for all admin users
  createdAt / updatedAt
```

#### `AdminAuditLog`
Every admin action is immutably logged.

```
AdminAuditLog
  id (UUID)
  adminUserId
  action        (e.g. APPROVE_KYB, SUSPEND_MERCHANT, RESET_API_KEY)
  resourceType, resourceId
  before        (JSONB)
  after         (JSONB)
  ipAddress
  createdAt
```

#### Admin Roles (Post-MVP Reference)

| Role | Responsibilities |
|---|---|
| `SUPER_ADMIN` | Full platform access, manage admin users, system config |
| `OPS` | Merchant management, KYB approval, dispute handling |
| `FINANCE` | Platform-wide financial reports, payout management |
| `COMPLIANCE` | KYB review, chargeback oversight, audit log access |

#### MVP Approach

- `AdminUser` and `AdminAuditLog` tables created via Flyway from day one
- Seed one `SUPER_ADMIN` record via Flyway data migration or bootstrap script
- No Admin Dashboard UI in MVP
- Admin JWT auth endpoint exists but is not publicly documented

---

## 3. TypeScript SDK — Two Packages

### `@gateway/js` — Browser SDK

Handles frontend payment flow, secure card data collection, and 3DS redirect.

```typescript
const gateway = new GatewayJS('pk_live_xxxxx');

const result = await gateway.confirmPayment({
  clientSecret: 'pi_xxx_secret_xxx',    // from your server
  paymentMethod: {
    type: 'card',
    card: cardElement,                   // hosted card input component
  },
  returnUrl: 'https://yoursite.com/return'
});
```

Responsibilities:
- Render hosted card input (iframe, PCI scope isolation)
- Handle 3DS action redirects
- Poll payment intent status
- Emit typed events (`payment.succeeded`, `payment.failed`)

### `@gateway/node` — Server SDK

Backend-to-backend API calls for Node.js / TypeScript merchants.

```typescript
const gateway = new GatewayNode('sk_live_xxxxx');

const intent = await gateway.paymentIntents.create({
  amount: 1000,
  currency: 'usd',
  captureMethod: 'automatic',
  metadata: { orderId: 'order_123' },
  successUrl: 'https://yoursite.com/success',
  cancelUrl:  'https://yoursite.com/cancel',
});

// Verify inbound webhook
const event = gateway.webhooks.verify(payload, signature, signingSecret);
```

---

## 4. Merchant Dashboard

### Tech Stack

| Layer | Choice |
|---|---|
| Framework | Next.js 15 (App Router) |
| UI Components | shadcn/ui + Tailwind CSS |
| Data Tables | TanStack Table v8 |
| Forms | React Hook Form + Zod |
| Charts | Tremor |
| Client State | Zustand |
| Server State | TanStack Query |
| Auth | Clerk (MVP) → Auth.js (production self-hosted) |

### Key Pages & Features

```
/dashboard
  ├── Overview              ← revenue chart, key metrics (Tremor)
  ├── Payments
  │    ├── /list            ← TanStack Table, filter by status/date/provider
  │    └── /[id]            ← detail view, timeline, refund action
  ├── Refunds               ← list + initiate refund
  ├── Chargebacks           ← dispute management, evidence upload
  ├── Routing
  │    └── /rules           ← drag-to-reorder priority, rule builder UI
  ├── Developers
  │    ├── /api-keys        ← create/revoke pub + secret key pairs
  │    ├── /webhooks        ← endpoint config + delivery logs
  │    └── /logs            ← structured request/response log viewer
  ├── Team                  ← invite members, assign roles, revoke access
  ├── Settings
  │    ├── /merchant        ← KYB info, business details
  │    └── /account         ← payout bank account
  └── Test Mode toggle      ← global TEST ↔ LIVE switch
```

---

## 5. Security Requirements

- **API keys** — store bcrypt hash only; show plaintext once on creation
- **Webhook signatures** — HMAC-SHA256, include `timestamp` in signed payload to prevent replay attacks
- **Webhook retry** — exponential backoff, max 5 attempts: 1m → 5m → 30m → 2h → 8h
- **Card data** — never touch raw card numbers; delegate entirely to Stripe/PayPal hosted fields
- **Idempotency** — Redis-backed, 24hr dedup window per `merchantId + idempotencyKey`
- **Rate limiting** — per API key, configurable per merchant tier
- **Sensitive fields** — encrypt bank account numbers at rest (AES-256)
- **Admin access** — MFA enforced for all `AdminUser` accounts

---

## 6. MVP Scope (Phase 1)

### In Scope

- User auth + merchant creation with OWNER role
- Team invite flow with 5 hardcoded roles (OWNER / ADMIN / DEVELOPER / FINANCE / VIEWER)
- API key management (test mode only)
- PaymentIntent create → confirm → succeed / fail flow (automatic capture)
- Stripe as primary provider
- Basic rule-based routing (currency-based + fallback)
- Async webhook delivery with exponential retry
- Dashboard: payments list, payment detail, API keys, webhook config, logs, team management
- `@gateway/node` server SDK
- `AdminUser` + `AdminAuditLog` tables seeded, no UI

### Defer to Phase 2

- Admin Dashboard UI (KYB approval, merchant management, platform-wide reports)
- PayPal provider
- Manual capture flow
- Chargeback evidence management
- KYB document workflow
- Account / payout management
- Live mode (full PCI review process)
- `@gateway/js` browser SDK + hosted card fields
- DB-driven custom permissions
- MFA for merchant owners
- SSO / OAuth2 login
