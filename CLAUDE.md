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

- PostgreSQL + Flyway — 22 migrations (V1–V22)
- `ddl-auto: validate` in production — Flyway owns the schema, never Hibernate
- `gateway_logs` is time-partitioned (V13)
- All financial state transitions go through the service layer — no direct repo writes from controllers

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
- Add value to `PaymentProvider` enum
- Create `XxxCredentials` record implementing `ProviderCredentials` (sealed — add to `permits`)
- Update `CredentialsCodec`: `encode`, `decode`, `clientKeyFor`, `clientConfigFor` switch cases
- Add credential fields to `CreateProviderAccountRequest` DTO
- Add `XxxPaymentProviderService` implementing `PaymentProviderService` (`brand`, `charge`, `refund`)
- Add `XxxWebhookController` at `POST /api/v1/providers/xxx/webhook` for status reconciliation
- Add SDK dependency to `pom.xml`
- If the provider needs a dynamic client token (like Braintree), add `GET /pub/xxx-client-token`
- Verify `mvn compile` passes

### 2. Dashboard (merchant portal)
- Add provider to `PROVIDERS` array and `PROVIDER_META` in `connectors/page.tsx`
- Add credential fields to the Zod schema + `superRefine` validation
- Add `<SelectItem>` to the provider dropdown
- Add conditional field block in the form
- Add brand entry to `lib/provider-brands.tsx` (name, SVG icon, color)

### 3. SDK + Pay Page (update together — they share the same pattern)
- **Pay page** (`dashboard/app/pay/[token]/page.tsx`): add `XxxCardForm` component and wire into `CheckoutForm`
- **Browser SDK** (`sdk/browser/src/index.ts`): add `mountXxx`, `submitXxx`, update `selectProvider`, `submit`, `destroyProviderForms`, `brandName`
- Both use the same tokenize → gateway token → checkout flow; only the provider-specific JS SDK init differs

### Provider client-key pattern
| Provider | `clientKey` returned by checkout-session | Client-side init |
|---|---|---|
| Stripe | `publishableKey` (static) | `Stripe(clientKey)` |
| Square | `applicationId` (static) | `Square.payments(clientKey, locationId)` |
| Braintree | `merchantId` (static) | Needs `GET /pub/braintree-client-token` → Drop-in UI |

Providers that require a dynamic server-generated token (like Braintree) need an extra public endpoint under `/pub/**` — already whitelisted in `SecurityConfig`.

### 4. README (root `README.md`)
- Add the new connector to the supported connectors list
- Include: name, sandbox signup link, what credentials are required

### Planned connectors
- [x] Stripe
- [x] Square
- [x] Braintree
- [ ] Mollie
- [ ] Razorpay

## Do NOT Suggest

- **Redis** — unless a real measured bottleneck exists
- **Kafka / RocketMQ** — Postgres queue pattern is intentional
- **Caffeine L1 cache** — for routing rules or API keys
- **`ddl-auto: create` or `ddl-auto: update`** — Flyway owns the schema
- **Sharing admin and merchant tables** — two separate realms, always
- **Embedding merchant roles in JWT claims** — always resolve from DB
