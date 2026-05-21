# MasonXPay

MasonXPay is a Java/Spring Boot and Next.js payment operations platform. It supports multi-provider payments, hosted checkout, embedded checkout, routing rules, webhooks, observability, and a high-throughput payment-core track.

## Product Inspiration

The orchestration idea behind MasonXPay was shaped by studying strong market examples such as [Hyperswitch](https://hyperswitch.io) and [Yuno](https://y.uno). Seeing how these products approach payment orchestration, provider abstraction, routing, and operational visibility helped clarify the kind of platform MasonXPay should become: an independent implementation focused on multi-provider payment operations, resilient payment state, and merchant-facing control.

## Live Demo

Hosted on Vercel + Render + Neon free tier. The first request may take around 30 seconds while the backend wakes up.

| Item | Value |
|------|-------|
| Dashboard | https://masonx-pay.vercel.app |
| Demo user | `demo@masonx.me` |
| Demo password | `demo@masonx.me` |
| Backend API | https://masonxpay.onrender.com |

## What It Includes

- Multi-provider connector management: Stripe, Square, Braintree, Mollie, and TEST-only Mason Simulator.
- Payment lifecycle: create, confirm, capture, cancel, refund, idempotency, and webhook delivery.
- Checkout options: hosted payment links and embedded browser SDK checkout.
- Merchant dashboard: payments, refunds, routing rules, connectors, API keys, webhooks, logs, and team access.
- High-throughput core: 64 logical payment shards, ShardingSphere-JDBC, Kafka outbox/workers, Redis hot path, and payment read projections.
- Observability: Prometheus metrics, Grafana dashboards, Kafka JMX metrics, alert rules, and request tracing.
- Benchmarks: k6 scenarios for create, confirm, refund, idempotency replay, get, and list flows.

## Repository Layout

```text
backend/        Java 21 Spring Boot API
dashboard/      Next.js merchant dashboard and hosted checkout
sdk/server/     TypeScript server SDK
sdk/browser/    TypeScript browser checkout SDK
monitor/        Prometheus, Grafana, Kafka JMX, alerting
bench/          k6 benchmark scenarios and results
cloud-deploy/   AWS deployment references
docs/           Roadmap and architecture plans
```

## Recommended Local Run

Use Docker Compose. This is the supported local path and does not require local Java, Node, or PostgreSQL setup.

```bash
cp .env.docker.example .env
docker compose up --build
```

Open:

| Service | URL |
|---------|-----|
| Dashboard | http://localhost:3000 |
| Backend API | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3001 |

Grafana login: `admin` / `admin`.

First build can take several minutes because Maven and Next.js dependencies are downloaded inside Docker. Later starts are faster because Docker layers are cached.

## Local Demo Flow

1. Open http://localhost:3000.
2. Register a local account.
3. Add a TEST connector, or enable Mason Simulator for benchmark/provider-flow testing.
4. Create a payment link or use the integration demo at http://localhost:3000/demo.html.
5. Watch metrics in Grafana at http://localhost:3001.

## Useful Commands

```bash
# Follow all logs
docker compose logs -f

# Follow backend logs
docker compose logs -f backend

# Stop containers and keep data
docker compose down

# Stop containers and reset local data
docker compose down -v

# Rebuild after code changes
docker compose up --build

# OR, With Redis/Kafka
docker compose --profile infra up --build
```

## Benchmarks

Run the recommended benchmark profile:

```bash
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.bench.yml --profile bench run --rm k6
```

Benchmark outputs are written to `bench/results/`. See [bench/README.md](bench/README.md) for simulator success-rate settings and Grafana panel interpretation.

## Documentation

- [Roadmap](docs/ROADMAP.md)
- [High-throughput payment core plan](docs/HIGH_THROUGHPUT_PAYMENT_CORE_PLAN.md)
- [AI-assisted operations control plane](docs/AI_CONTROL_PLANE_PLAN.md)
- [Development guide](docs/DEVELOPMENT_GUIDE.md)
- [Server SDK](sdk/server/README.md)

## Tech Stack

| Layer | Technology |
|-------|------------|
| Backend | Java 21, Spring Boot 3.2, Spring Security, Spring Data JPA |
| Database | PostgreSQL, Flyway, ShardingSphere-JDBC |
| Async | Apache Kafka, Spring Kafka, transactional outbox |
| Hot path | Redis, Redisson |
| Dashboard | Next.js 15, Tailwind CSS, shadcn/ui, TanStack Query |
| SDKs | TypeScript server SDK and browser SDK |
| Observability | Micrometer, Prometheus, Grafana, Kafka JMX |

## Security Notes

- API requests use `Authorization: Bearer sk_xxx`.
- Browser checkout uses publishable `pk_xxx` keys.
- Provider credentials are encrypted at rest.
- Provider webhooks require signature verification.
- Do not put production secrets in `.env` files; use a deployment secret manager.
