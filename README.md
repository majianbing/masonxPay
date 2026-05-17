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
├── monitor/               Observability stack (Prometheus + Grafana)
│   ├── kafka/
│   │   ├── Dockerfile         Apache Kafka image with Prometheus JMX exporter
│   │   └── kafka-jmx.yml      Kafka broker JMX metric rules
│   ├── prometheus/
│   │   ├── prometheus.yml     Scrape config (backend + Kafka JMX)
│   │   └── alert_rules.yml    Alerting rules (success rate, latency, queues, Kafka health)
│   └── grafana/
│       ├── provisioning/      Auto-wired datasource + dashboard loader
│       └── dashboards/        payments.json — pre-built payments dashboard
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
- **Complete payment lifecycle** — create → confirm → capture → refund, with idempotency keys and manual capture support
- **High-throughput payment core track** — 64 logical payment shards via ShardingSphere-JDBC, local/demo shard backfill, optimistic payment versioning, row-level locks for financial state transitions, Kafka-backed outbox publication, Kafka webhook fan-out, Kafka-fed payment read projection, and Redis hot-path optimization
- **Role-based access control** — five roles (OWNER, ADMIN, DEVELOPER, FINANCE, VIEWER) enforced per-merchant
- **Webhook delivery** — transactional outbox pattern, Kafka consumer fan-out in the Docker high-throughput profile, configurable endpoints with HMAC signing, exponential backoff retry
- **Async read models** — Kafka projection worker maintains tenant-scoped payment read models with processed-event idempotency tracking
- **API key pairs** — `pk_xxx` publishable (browser-safe) + `sk_xxx` secret (server-only), TEST and LIVE modes
- **Merchant dashboard** — payments, refunds, routing rules, connectors, API keys, logs, members
- **TypeScript SDKs** — server SDK (`@gateway/server`) and browser SDK (`@gateway/browser`)
- **Observability** — Micrometer metrics at `/actuator/prometheus`, per-request trace ID propagation (`X-Request-Id`), Grafana dashboard with payment volume / latency / success rates / connector health / Kafka health, Redis fallback/hit counters, Prometheus alerting rules

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 3.2, Spring Security 6, Spring Data JPA |
| Database | PostgreSQL + Flyway migrations + ShardingSphere-JDBC logical payment shards |
| Async events | Spring Kafka + Apache Kafka local broker + transactional outbox |
| Hot path | Redis + Redisson for distributed rate limiting, idempotency route cache, and provider health hints |
| Auth | JWT (jjwt 0.12.5) + API key authentication |
| Payment providers | Stripe, Square, Braintree |
| Observability | Micrometer + Prometheus + Grafana + Kafka JMX exporter |
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

The Docker profile uses `PAYMENT_SHARD_COUNT=64` by default. Flyway creates and backfills the local/demo logical payment shard tables automatically.
It also enables Kafka webhook fan-out by default and disables the scheduled webhook outbox poller, so local Docker runs exercise the async-worker path.

**2. Start the stack**

```bash
docker compose up --build
```

This builds and starts six containers:

| Container | URL | Description |
|-----------|-----|-------------|
| `dashboard` | http://localhost:3000 | Next.js merchant portal |
| `backend` | http://localhost:8080 | Spring Boot API |
| `postgres` | localhost:5432 | PostgreSQL (data persists in a Docker volume) |
| `kafka` | localhost:9094 | Apache Kafka broker for outbox event publication |
| `prometheus` | http://localhost:9090 | Metrics scraper (backend + Kafka JMX every 15s) |
| `grafana` | http://localhost:3001 | Payments + Kafka dashboard — login: admin / admin |

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

**Kafka quick checks**

```bash
# List local Kafka topics
docker compose exec kafka env -u KAFKA_OPTS \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# Verify Prometheus sees the Kafka JMX exporter
curl 'http://localhost:9090/api/v1/query?query=up%7Bjob%3D%22kafka-jmx%22%7D'

# Inspect broker metrics directly
curl http://localhost:7071/metrics

# Verify the webhook worker consumer group
docker compose exec kafka env -u KAFKA_OPTS \
  /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group masonxpay-webhook-worker
```

Docker defaults:

```bash
KAFKA_WEBHOOK_CONSUMER_ENABLED=true
KAFKA_WEBHOOK_CONSUMER_GROUP_ID=masonxpay-webhook-worker
KAFKA_PAYMENT_PROJECTION_ENABLED=true
KAFKA_PAYMENT_PROJECTION_GROUP_ID=masonxpay-payment-projection
PAYMENT_PROJECTION_BACKFILL_ENABLED=true
REDIS_HOT_PATH_ENABLED=true
REDIS_HOT_PATH_FAIL_OPEN=true
REDIS_RATE_LIMIT_ENABLED=true
REDIS_IDEMPOTENCY_CACHE_ENABLED=true
REDIS_PROVIDER_HEALTH_CACHE_ENABLED=true
WEBHOOK_OUTBOX_POLLER_ENABLED=false
```

The `local` Spring profile uses the same Kafka path with `localhost:9094` and Redis at `localhost:6379`, so you can run Postgres, Kafka, and Redis in Docker while starting the backend from Maven.
Production can provide a managed Redis endpoint with `REDIS_URL`, for example `redis://host:6379` or `rediss://host:6380`; Docker can keep using `REDIS_HOST` and `REDIS_PORT`.
Manual backend runs without the `local` profile keep Kafka consumers and Redis hot-path behavior disabled unless explicitly overridden.

---

### Option A2 — Preview profile

Use preview when you want a production-like local runtime before H6 reliability work. It still runs on your Mac through Docker, but it uses stricter defaults than local development: `SPRING_PROFILES_ACTIVE=preview`, Kafka async workers enabled, Redis hot path enabled through `REDIS_URL`, webhook DB polling disabled, health details hidden, projection backfill disabled by default, shorter observability retention, and named preview consumer groups.

Your 32GB M1 Pro is enough for this single-node preview stack. Kafka is still one broker, so it mimics behavior and operations, not multi-AZ production durability.

```bash
docker compose -p masonxpay-preview --env-file .env.preview \
  -f docker-compose.yml \
  -f docker-compose.preview.yml \
  up --build
```

If the normal Docker stack is already running, stop it first because preview uses the same host ports. The `masonxpay-preview` project name keeps preview volumes separate from the default local stack.

Useful preview checks:

```bash
docker compose -p masonxpay-preview --env-file .env.preview -f docker-compose.yml -f docker-compose.preview.yml ps

docker compose -p masonxpay-preview --env-file .env.preview -f docker-compose.yml -f docker-compose.preview.yml logs -f backend

docker compose -p masonxpay-preview --env-file .env.preview -f docker-compose.yml -f docker-compose.preview.yml exec kafka env -u KAFKA_OPTS \
  /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group masonxpay-payment-projection-preview
```

Use `.env.preview` for local preview values only. Keep real production secrets in a deployment secret manager, not in this file.

---

### Option B — Local development (manual)

Use this if you want hot-reload or are working on only one part of the stack.

**Prerequisites:** Java 21, Maven, PostgreSQL 14+, Node.js 18+

**1. Database**

```bash
docker compose up -d postgres kafka
```

Flyway runs migrations automatically on startup — no manual schema setup needed.

**2. Backend**

```bash
cd backend
cp ../.env .env          # spring-dotenv loads .env from the working directory
mvn spring-boot:run
# API available at http://localhost:8012 (local profile default)
```

The local Spring profile connects to Kafka through `localhost:9094`, enables Kafka outbox publication, enables the webhook consumer, enables the payment projection consumer, and disables the scheduled webhook outbox poller. To run the backend without Kafka, override:

```bash
KAFKA_OUTBOX_ENABLED=false KAFKA_WEBHOOK_CONSUMER_ENABLED=false \
KAFKA_PAYMENT_PROJECTION_ENABLED=false \
PAYMENT_PROJECTION_BACKFILL_ENABLED=false \
REDIS_HOT_PATH_ENABLED=false \
WEBHOOK_OUTBOX_POLLER_ENABLED=true mvn spring-boot:run
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

**Architecture: the SDK is the single source of truth for all client-side payment UI.**
The provider picker, payment form inputs, pay button, and result callbacks all live inside the SDK.
Pages and apps (including the hosted `/pay/[token]` page) are consumers — they call `mountCheckout()` and handle `onSuccess`/`onError`. They own no provider-specific logic.

#### Two modes

| Mode | Use case | Entry point |
|------|----------|-------------|
| **Hosted** | Merchant-created pay links at `/pay/{token}` | `gw.mountCheckout(el, { linkToken, onSuccess, onError })` |
| **Embedded** | Drop the form into your own page with `pk_xxx` | `gw.mount(el)` + listen for `token` event |

#### Hosted mode — `mountCheckout`

```ts
import { GatewayEmbedded } from '@gateway/browser';

const gw = new GatewayEmbedded('', { baseUrl: 'https://your-backend' });

gw.on('ready', () => { /* form is mounted and interactive */ });

await gw.mountCheckout(document.getElementById('payment-container'), {
  linkToken: 'lnk_xxx',          // from the pay link URL
  onSuccess: (result) => {
    console.log(result.paymentIntentId); // charge complete
  },
  onError: (err) => {
    console.error(err.message);
  },
});

// Cleanup (e.g. React useEffect return)
gw.destroy();
```

#### Embedded mode — `mount` + `token` event

```ts
const gw = new GatewayEmbedded('pk_test_xxx', { baseUrl: 'https://your-backend' });

gw.on('ready', () => { /* form ready */ });
gw.on('token', async ({ paymentMethodId }) => {
  // paymentMethodId is a gateway token (gw_tok_xxx)
  // send it to your server to call POST /api/v1/payment-intents
});
gw.on('error', ({ message }) => { /* show error */ });

await gw.mount('#payment-container');
```

#### Local development — no build step required

The dashboard references the SDK as a local package (`file:../sdk/browser`).
Next.js is configured with `transpilePackages: ['@gateway/browser']` and the SDK `main` points directly to `src/index.ts`, so **Next.js imports and compiles the TypeScript source at dev time**.

```
# Terminal 1
cd backend && mvn spring-boot:run

# Terminal 2 — SDK changes are picked up automatically; no sdk build needed
cd dashboard && npm run dev
```

When you edit `sdk/browser/src/index.ts`, Turbopack detects the change through the symlink and hot-reloads the pay page. If a change isn't reflected, restart `npm run dev`.

#### Docker — SDK is built into the image

`docker compose up --build` copies `sdk/browser/` into the dashboard image before `npm ci`, so the container always uses the SDK source as it exists at build time. To pick up SDK changes in Docker, re-run `docker compose up --build`.

#### Distributable bundle (for `<script>` tag consumers)

```bash
cd sdk/browser && npm install
npm run bundle  # → dist/gateway-sdk.min.js
```

The pre-built files (`gateway-sdk.js` and `gateway-sdk.min.js`) in `dashboard/public/` can be served from any CDN or static host.

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
