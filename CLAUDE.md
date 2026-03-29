# MasonXPay — Payment Gateway

HyperSwitch-like open-source payment gateway. Java 21 / Spring Boot 3.2 / Maven.
Package root: `com.masonx.paygateway`

## Repository Layout

```
backend/                          Spring Boot (src/, pom.xml, Dockerfile)
dashboard/                        Next.js 15 merchant portal (Dockerfile)
sdk/                              TypeScript SDKs (server + browser)
cloud-deploy/aws/standalone/      Single EC2 CloudFormation
cloud-deploy/aws/managed/         EC2 + RDS + Amplify CloudFormation
docker-compose.yml                Local quickstart (all 3 services)
```

## Key Architectural Decisions

### No Redis — deliberate
- Postgres handles idempotency: unique constraint + `INSERT ON CONFLICT`
- Postgres handles distributed locking: `SELECT FOR UPDATE`
- Refresh token rotation stored as SHA-256 hash in DB
- JWT access tokens are stateless — no blacklist cache needed
- Target volume ≤100k tx/day per merchant — Postgres is not the bottleneck
- Scaling path: read replica → PgBouncer → Redis (in that order, only when measured)

### No Kafka / MQ — deliberate
- Webhook delivery uses Spring `ApplicationEventPublisher` (in-process)
- `gateway_events` + `webhook_deliveries` tables act as the durable queue
- `@Scheduled` poller every 60s, exponential backoff: 30s → 5m → 30m → 2h → 8h
- Known gap: event publish is NOT atomic with the DB write (transactional outbox)
  → TODO comment in `PaymentIntentService.publishEvent()`
  → Accepted trade-off at current scale

### No L1 Cache (Caffeine) — deliberate
- API key lookups, routing rules, connector config are indexed DB reads (<1ms)
- Write-dominant system — caching adds invalidation complexity for negligible gain
- Revisit only when a measurable latency problem is observed

### JWT Token Invalidation Trade-off
- Logout revokes the refresh token only; the 24h access token remains valid
- Escape hatch: `token_version` int column on `users`, embed in JWT, increment on logout
  → TODO comment in `JwtService.generateAccessToken()`

## Two Separate User Realms — DO NOT Share Tables

| Realm | Tables | Status |
|---|---|---|
| Merchant portal | `users`, `merchant_users` | MVP |
| Platform admin | `admin_users`, `admin_audit_logs` | post-MVP, no UI/endpoints yet |

## RBAC

- 5 roles: `OWNER > ADMIN > DEVELOPER | FINANCE > VIEWER`
- `@PreAuthorize("@permissionEvaluator.hasPermission(auth, #merchantId, 'RESOURCE', 'ACTION')")`
- Membership always resolved from DB (never embedded in JWT) — enables immediate revocation

## Security

| Concern | Approach |
|---|---|
| Provider credentials (Stripe sk_xxx, etc.) | AES-256 encrypted in DB |
| Refresh tokens | SHA-256 hashed in DB, rotated on each use |
| Invite tokens | SHA-256 hashed, 48h expiry, single-use |
| Webhook payloads | HMAC-SHA256 signed (Stripe-compatible format) |

## Database

- PostgreSQL + Flyway — 26 migrations (V1–V26)
- `ddl-auto: validate` in production — Flyway owns the schema, never Hibernate
- `gateway_logs` is time-partitioned (V13)
- All financial state transitions go through the service layer — no direct repo writes from controllers
- `payment_links.pinned_connector_id` — used by connector preview to scope checkout to one account (V26)

## Local Development

**Docker (recommended):**
```bash
cp .env.docker.example .env
docker compose up --build     # first boot ~10-15 min (Maven + Next.js build)
```

**Manual:**
```bash
cd backend && mvn spring-boot:run   # port 8012, uses application-local.yml
cd dashboard && npm run dev         # port 3000
```

## Adding a New Payment Connector

Always follow this order. Each layer depends on the previous one.

### 1. Backend

**Enum + credentials (sealed interface)**
- Add value to `PaymentProvider` enum
- Create `XxxCredentials` record implementing `ProviderCredentials` (sealed — add to `permits`)

**Codec** — most error-prone step, four switch cases must all be consistent:
- `encode` — serialize credentials to encrypted blob
- `decode` — deserialize and return the correct `XxxCredentials` type
- `clientKeyFor` — return the publishable/client key the browser SDK receives; returning `null` silently hides this provider from the checkout picker
- `clientConfigFor` — return any extra map the SDK needs (e.g. Square needs `{"locationId": "..."}`)

**DTO + service**
- Add credential fields to `CreateProviderAccountRequest` DTO
- Create `XxxPaymentProviderService` implementing `PaymentProviderService` (`brand`, `charge`, `refund`) — register as `@Service`, `PaymentProviderDispatcher` picks it up automatically

**Webhook controller**
- `POST /api/v1/providers/xxx/webhook` — verify provider signature, reconcile payment intent status

**pom.xml** — add the provider's Java SDK dependency

**Dynamic client token** (only if needed — Braintree pattern):
- Add `GET /pub/xxx-client-token` — already whitelisted in `SecurityConfig` under `/pub/**`
- SDK calls this before mounting the form; store the token; pass to the Drop-in/Elements init

**Verify:** `mvn compile`

### 2. Dashboard (merchant portal)
- Add provider to `PROVIDERS` array and `PROVIDER_META` in `connectors/page.tsx`
- Add credential fields to the Zod schema + `superRefine` validation
- Add `<SelectItem>` to the provider dropdown
- Add conditional credential field block in the form
- Add brand entry to `lib/provider-brands.tsx` (name, SVG icon, color)

### 3. Browser SDK — single source of truth

**`sdk/browser/src/index.ts`** owns all client-side payment UI. Pages are consumers only.

- Add `private buildXxxForm(opt, container): Promise<void>` — populate the pre-attached live DOM slot; do NOT manage skeleton or call `disabled` here
- Add `private submitXxx(): Promise<void>`
- Update `selectProvider()`: add `else if (provider === 'XXX') await this.buildXxxForm(opt, slot)`
- Update `submit()`: add `else if (this.selectedProvider === 'XXX') await this.submitXxx()`
- Update `destroyProviderForms()`: tear down any SDK state for the new provider
- Update `brandName()` map

**Skeleton lifecycle** — `selectProvider()` owns it; builders must NOT call `showSkeleton`/`clearSkeleton`. Pre-attach a hidden `slot` to the live DOM before calling the builder (required for iframe-injecting SDKs like Square and Braintree).

**Stripe redirect pattern** (in-page vs redirect methods):
- Hosted mode uses `stripe.confirmPayment({ redirect: 'if_required' })` — handles both card (in-place) and redirect methods (iDEAL, Amazon Pay, etc.)
- On redirect return, `mountCheckout` detects `?payment_intent_client_secret` in the URL, calls `GET /pub/pay/{token}/stripe-result`, then fires `onSuccess`/`onError`
- `stripeClientSecret` is cached per `GatewayEmbedded` instance (set on first Stripe selection, cleared only on `destroy()`) — prevents creating multiple PIs when the user switches providers back and forth

**The pay page** (`dashboard/app/pay/[token]/page.tsx`) calls `gw.mountCheckout(containerEl, { linkToken, onSuccess, onError })` and renders nothing provider-specific. **Do NOT add provider-specific React/JSX to the pay page.**

### Provider client-key pattern
| Provider | `clientKey` from checkout-session | Client-side init |
|---|---|---|
| Stripe | `publishableKey` (static) | `Stripe(clientKey)` then `stripe.elements({ clientSecret })` |
| Square | `applicationId` (static) | `Square.payments(clientKey, locationId)` |
| Braintree | `merchantId` (static) | Needs `GET /pub/braintree-client-token` first → Drop-in UI |

### Connector preview — free once SDK is wired
The preview feature (`/connectors/[accountId]/preview`) works automatically for any provider once the SDK step is done. `POST /{accountId}/preview-link` creates a 15-min TEST payment link with `pinned_connector_id`, which scopes `checkout-session` to return only that provider and bypasses routing in `tokenize`. No extra preview-specific backend code needed per provider.

### 4. README (root `README.md`)
- Add the new connector to the supported connectors list
- Include: name, sandbox signup link, required credentials

### Planned connectors
- [x] Stripe
- [x] Square
- [x] Braintree
- [ ] Mollie
- [ ] Razorpay

## New Features with New Tables

Every new table must include a `merchant_id` column and all queries must be scoped to it. This is a multi-tenant system — data isolation between merchants is a hard requirement, not an afterthought. Before writing a migration or a repository method, verify that the tenant boundary is enforced at every read and write path.

## SDK Architecture Rule

**The browser SDK (`sdk/browser/src/index.ts`) is the single source of truth for all client-side payment UI.**

- Provider picker, payment form inputs, pay button, loading skeleton, and result handling all live in the SDK.
- Pages and apps are consumers: they call `mountCheckout()` and handle `onSuccess`/`onError`. They own no provider-specific JSX or logic.
- When adding a new provider, update the SDK. Pages update only if a new lifecycle callback is needed.
- `selectProvider()` owns the full skeleton lifecycle — show before builder, clear + reveal after. Builders receive a pre-attached hidden `<div>` slot and populate it; they are skeleton-unaware.
- The pay button has a periodic gloss sheen animation (CSS `::after` + `@keyframes gw-btn-sheen`); skeleton bars use a left-to-right shimmer sweep (`@keyframes gw-shimmer`).
- Hosted mode two-phase flow: `mountCheckout` → detect redirect return (`?payment_intent_client_secret`) or render form → `buildStripeForm` lazily calls `/prepare-stripe` and caches `stripeClientSecret` → `submitStripe` calls `confirmPayment({ redirect: 'if_required' })` → in-place completions call `/stripe-result` to create the DB record.

## Do NOT Suggest

- **Redis** — unless a real measured bottleneck exists
- **Kafka / RocketMQ** — Postgres queue pattern is intentional
- **Caffeine L1 cache** — for routing rules or API keys
- **`ddl-auto: create` or `ddl-auto: update`** — Flyway owns the schema
- **Sharing admin and merchant tables** — two separate realms, always
- **Embedding merchant roles in JWT claims** — always resolve from DB
- **`@Transactional` on methods that include remote calls** — holds a DB connection open for the duration of the HTTP call; exhausts the connection pool under load. Use an atomic compare-and-set UPDATE query to claim/release resources around the remote call instead.
