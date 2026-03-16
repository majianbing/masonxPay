# MasonXPay

> There is no open source Java version of the payment orchestration system, let's vibe coding one.

A fully-featured, Stripe-like payment gateway built with Java and Spring Boot — supporting multiple providers, intelligent routing, and both hosted and embeddable checkout.

## Repository layout

```
pay.masonx/
├── src/                   Spring Boot backend (Java 21)
├── dashboard/             Merchant dashboard (Next.js)
│   └── public/
│       ├── demo.html          Integration demo — try the API without writing code
│       ├── gateway-sdk.js     Browser SDK (full, ~13 KB)
│       └── gateway-sdk.min.js Browser SDK (compressed, ~6 KB)
├── sdk/
│   ├── server/            @gateway/server — Node.js / TypeScript server SDK
│   └── browser/           @gateway/browser — Browser SDK source (TypeScript)
└── cloud-deploy/          Deployment configs
```

---

## Features

- **Multi-provider routing** — Stripe and Square supported today; extensible to Adyen, PayPal, CyberSource, Airwallex. Weighted-random routing rules with priority, currency, amount, and country filters
- **Connector management** — connect multiple accounts per provider, set weights, designate a primary, preview charges from the dashboard
- **Two checkout modes**
  - **Hosted pay link** — create a link, share the URL, customer pays on your hosted `/pay/{token}` page
  - **Embedded form** (Pattern B) — drop `GatewayEmbedded` on your own page with `pk_xxx`; card never touches your server
- **Complete payment lifecycle** — create → confirm → refund, with idempotency keys
- **Role-based access control** — five roles (OWNER, ADMIN, DEVELOPER, FINANCE, VIEWER) enforced per-merchant
- **Webhook delivery** — configurable endpoints with HMAC signing, filterable by event type
- **API key pairs** — `pk_xxx` publishable (browser-safe) + `sk_xxx` secret (server-only), TEST and LIVE modes
- **Merchant dashboard** — payments, refunds, routing rules, connectors, API keys, logs, members
- **TypeScript SDKs** — server SDK (`@gateway/server`) and browser SDK (`@gateway/browser`)

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 3.2, Spring Security 6, Spring Data JPA |
| Database | PostgreSQL + Flyway migrations |
| Auth | JWT (jjwt 0.12.5) + API key authentication |
| Payment providers | Stripe, Square (REST, no vendor SDK dependency) |
| Dashboard | Next.js 14, Tailwind CSS, shadcn/ui, TanStack Query |
| Browser SDK | Vanilla TypeScript, esbuild for bundling |
| Server SDK | TypeScript, Node.js 18+ |

---

## Getting started

### 1. Prerequisites

- Java 21
- Maven
- PostgreSQL 14+
- Node.js 18+ (for the dashboard and SDKs)
- A Stripe test account **or** a Square sandbox account (at least one connector required)

### 2. Database

```bash
createdb paygateway
```

Flyway runs migrations automatically on startup — no manual schema setup needed.

### 3. Backend configuration

Create `src/main/resources/application-local.properties` (or set env vars):

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/paygateway
spring.datasource.username=your_db_user
spring.datasource.password=your_db_password

app.jwt.secret=a-very-long-random-string-at-least-32-chars
app.encryption.key=another-32-char-aes-key-for-credentials
```

Provider credentials (Stripe `sk_xxx`, Square `accessToken`) are stored **AES-256 encrypted** in the database — no provider keys in config files.

### 4. Run the backend

```bash
mvn spring-boot:run
# API available at http://localhost:8080
```

### 5. Run the dashboard

```bash
cd dashboard
npm install
npm run dev
# Dashboard available at http://localhost:3000
```

### 6. Try the integration demo

Open `http://localhost:3000/demo.html` in your browser. No code required — enter your API key and test payment links, direct API calls, and the embedded form (Pattern B) interactively.

---

## Integration — two paths

### Path A — Hosted pay link (simplest)

Your server creates a link; the customer pays on your hosted page.

```typescript
// Server (Node.js) — using @gateway/server
import { GatewayNode } from '@gateway/server';

const gateway = new GatewayNode('sk_test_xxx', { merchantId: 'your-merchant-id' });

const link = await gateway.paymentLinks.create({
  title: 'Order #123',
  amount: 4200,       // cents
  currency: 'usd',
  redirectUrl: 'https://yourshop.com/thank-you',
});

// Send link.payUrl to your customer — they open it and pay
// https://pay.yourgateway.com/pay/TOKEN
```

### Path B — Embedded form (Pattern B, no server round-trip before the form)

Drop the browser SDK on your checkout page. The customer's card never touches your server.

```html
<!-- 1. Load the SDK -->
<script src="https://pay.yourgateway.com/gateway-sdk.min.js"></script>
<div id="payment-form"></div>

<script>
  // 2. Mount with your publishable key — fetches available providers automatically
  const gw = new GatewayEmbedded('pk_test_xxx', {
    baseUrl: 'https://api.yourgateway.com',
  });
  await gw.mount('#payment-form');

  // 3. When the customer clicks Pay, you receive a gateway token
  gw.on('token', async ({ gatewayToken }) => {
    // 4. Send to YOUR server — never confirm client-side
    await fetch('/your-server/pay', {
      method: 'POST',
      body: JSON.stringify({ gatewayToken, amount: 4200, currency: 'usd' }),
    });
  });
</script>
```

```typescript
// 5. Your server confirms with @gateway/server
const intent = await gateway.paymentIntents.create({ amount, currency, idempotencyKey: '...' });
const result = await gateway.paymentIntents.confirm(intent.id, {
  paymentMethodId: gatewayToken, // gw_tok_xxx — routing already resolved in the browser
});
```

---

## SDKs

### `@gateway/server` — server-side (Node.js / TypeScript)

See [`sdk/server/README.md`](sdk/server/README.md) for the full reference.

```bash
cd sdk/server && npm install && npm run build
```

Covers: payment intents, refunds, API keys, webhook endpoints, webhook verification, routing rules, logs.

### `@gateway/browser` — browser-side (TypeScript / plain JS)

See [`sdk/browser/README.md`](sdk/browser/README.md) for the full reference.

```bash
cd sdk/browser && npm install

npm run build   # TypeScript → dist/  (for npm consumers)
npm run bundle  # Minified IIFE → dist/gateway-sdk.min.js  (for <script> tag consumers)
```

The pre-built files (`gateway-sdk.js` and `gateway-sdk.min.js`) are served directly from `dashboard/public/` and can be copied to any CDN or static host.

---

## API overview

All merchant API endpoints are under `/api/v1/` and require `Authorization: Bearer sk_xxx`.
Public checkout endpoints are under `/pub/` and require no authentication (or `pk_xxx` for the embedded SDK path).

| Resource | Endpoint prefix |
|----------|----------------|
| Auth | `/api/v1/auth/` |
| Payment intents | `/api/v1/payment-intents/` |
| Payment links | `/api/v1/merchants/{id}/payment-links/` |
| Connectors | `/api/v1/merchants/{id}/connectors/` |
| Routing rules | `/api/v1/merchants/{id}/routing-rules/` |
| Webhook endpoints | `/api/v1/merchants/{id}/webhook-endpoints/` |
| API keys | `/api/v1/merchants/{id}/api-keys/` |
| Members | `/api/v1/merchants/{id}/members/` |
| Hosted checkout | `/pub/checkout-session`, `/pub/tokenize`, `/pub/pay/{token}/checkout` |

---

## RBAC — roles and permissions

| Role | Payments | Refunds | Connectors | Routing | API Keys | Members |
|------|----------|---------|------------|---------|----------|---------|
| OWNER | R/W | R/W | R/W | R/W | R/W | R/W |
| ADMIN | R/W | R/W | R/W | R/W | R/W | R/W |
| DEVELOPER | R/W | R | R/W | R/W | R/W | R |
| FINANCE | R/W | R/W | R | R | R | R |
| VIEWER | R | R | R | R | R | R |
