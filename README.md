# MasonXPay

> There is no open source Java version of the payment orchestration system, let's vibe coding one.

A fully-featured, [HyperSwitch](https://github.com/juspay/hyperswitch)-like payment gateway built with Java and Spring Boot — supporting multiple providers, intelligent routing, and both hosted and embeddable checkout.

## Live demo

Hosted on Vercel + Render + Neon (free tier — allow ~30s warm-up on first request).

| | |
|---|---|
| **Dashboard** | https://masonx-pay.vercel.app |
| **Username** | demo@masonx.me |
| **Password** | demo@masonx.me |
| **Backend API** | https://masonxpay.onrender.com |
| **Database** | Neon PostgreSQL (https://console.neon.tech) |

---

## Repository layout

```
pay.masonx/
├── backend/               Spring Boot backend (Java 21)
│   ├── src/
│   ├── pom.xml
│   └── Dockerfile
├── dashboard/             Merchant dashboard (Next.js)
│   ├── Dockerfile
│   └── public/
│       ├── demo.html          Integration demo — try the API without writing code
│       ├── gateway-sdk.js     Browser SDK (full, ~13 KB)
│       └── gateway-sdk.min.js Browser SDK (compressed, ~6 KB)
├── sdk/
│   ├── server/            @gateway/server — Node.js / TypeScript server SDK
│   └── browser/           @gateway/browser — Browser SDK source (TypeScript)
├── cloud-deploy/
│   └── aws/
│       ├── standalone/    Single-EC2 CloudFormation stack
│       └── managed/       EC2 + RDS + Amplify CloudFormation stack
└── docker-compose.yml     Local Docker quickstart (all services)
```

---

## Features

- **Multi-provider routing** — Stripe, Square, and Braintree supported today; extensible to Adyen, PayPal, Mollie, Razorpay, and more. Weighted-random routing rules with priority, currency, amount, and country filters
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
| Payment providers | Stripe, Square, Braintree |
| Dashboard | Next.js 15, Tailwind CSS, shadcn/ui, TanStack Query |
| Browser SDK | Vanilla TypeScript, esbuild for bundling |
| Server SDK | TypeScript, Node.js 18+ |

---

## Getting started

### Option A — Docker (recommended)

The fastest way to run the full stack locally. Only Docker is required — no Java, Node, or PostgreSQL needed on your machine.

**1. Clone and configure**

```bash
git clone https://github.com/your-org/pay.masonx.git
cd pay.masonx
cp .env.docker.example .env
```

Open `.env` and set the two required values:

```bash
# Required — generate a random secret:  openssl rand -base64 32
JWT_SECRET=your-random-secret-here

# Optional — add Stripe keys to enable payment processing
STRIPE_SECRET_KEY=sk_test_...
```

All other values have safe defaults for local development.

**2. Start the stack**

```bash
docker compose up --build
```

This builds and starts three containers:

| Container | URL | Description |
|-----------|-----|-------------|
| `dashboard` | http://localhost:3000 | Next.js merchant portal |
| `backend` | http://localhost:8080 | Spring Boot API |
| `postgres` | localhost:5432 | PostgreSQL (data persists in a Docker volume) |

> **First boot takes ~10–15 minutes** — Maven downloads dependencies and builds the JAR, then Next.js compiles the dashboard. Subsequent starts are fast (layers are cached).

**3. Create your account**

Open http://localhost:3000 and register. The first registration creates a merchant account with the OWNER role. Flyway runs all database migrations automatically on startup.

**4. Try the integration demo**

Open http://localhost:3000/demo.html — enter your API key and test payment links, direct API calls, and the embedded checkout form interactively. No code required.

---

**Useful commands**

```bash
# Follow logs for all services
docker compose logs -f

# Follow logs for one service
docker compose logs -f backend

# Stop everything (data is preserved)
docker compose down

# Stop and wipe the database volume (full reset)
docker compose down -v

# Rebuild after code changes
docker compose up --build
```

---

### Option B — Local development (manual)

Use this if you want hot-reload or are working on only one part of the stack.

**Prerequisites:** Java 21, Maven, PostgreSQL 14+, Node.js 18+

**1. Database**

```bash
createdb paygateway
```

Flyway runs migrations automatically on startup — no manual schema setup needed.

**2. Backend**

```bash
cd backend
cp ../.env .env          # spring-dotenv loads .env from the working directory
mvn spring-boot:run
# API available at http://localhost:8012 (local profile default)
```

Or set env vars directly:

```bash
DB_HOST=localhost DB_NAME=paygateway DB_USERNAME=your_user \
DB_PASSWORD=your_pass JWT_SECRET=your-secret \
mvn spring-boot:run -pl backend
```

Provider credentials (Stripe `sk_xxx`, Square `accessToken`) are stored **AES-256 encrypted** in the database — no provider keys in config files.

**3. Dashboard**

```bash
cd dashboard
echo "NEXT_PUBLIC_API_URL=http://localhost:8012" > .env.local
npm install
npm run dev
# Dashboard available at http://localhost:3000
```

**4. Try the integration demo**

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
