# MasonXPay

MasonXPay is a Java/Spring Boot and Next.js payment platform covering multi-provider payment orchestration, a double-entry virtual account ledger with card issuing, an ISO 8583 / ISO 20022 multi-rail track, merchant operations, and a high-throughput payment-core track.

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
- Payment orchestration: route policies, capability-aware routing, and outcome-aware retry/fallback.
- Merchant operations: disputes, subscriptions and invoicing, scheduled payment retries, and a merchant audit log.
- Merchant dashboard: payments, refunds, routing rules, connectors, API keys, webhooks, logs, and team access.
- Virtual Account service: a double-entry ledger (CASH/WALLET/receivable accounts) with a merchant-facing ledger and statement view, plus a Virtual Credit Card (VCC) product backed by the same ledger — VA acts as card issuer for BIN 999999.
- Multi-rail infrastructure: ISO 8583 card rail (Netty + jPOS), ISO 20022 bank rail (HTTP + JAXB), two-sided network simulator, UNKNOWN state and reversal discipline, and Kafka-driven ledger settlement.
- High-throughput core: 64 logical payment shards, ShardingSphere-JDBC, Kafka outbox/workers, Redis hot path, and payment read projections.
- Observability: Prometheus metrics, Grafana dashboards, Kafka JMX metrics, alert rules, and request tracing.
- Benchmarks: k6 scenarios for create, confirm, refund, idempotency replay, get, and list flows.
- AI assistant: an early-stage, budget-gated dashboard assistant scoped to approved docs, with a separate Python AI coprocessor (`ai-service/`) for embeddings and eval runs.

## Repository Layout

```text
backend/
  common/                  Shared error model, ID generation, tenant context
  contracts/               Shared event contracts (outbox, settlement, rail events)
  gateway-service/         Payment gateway — intents, routing, providers, webhooks
  virtual-account-service/ Double-entry ledger, VA accounts, VCC issuer
  rail-service/            ISO 8583 / ISO 20022 rail client and reconciliation API
  rail-simulator/          Two-sided network simulator (card-network + bank-rail)
ai-service/     Optional Python AI coprocessor for RAG, model orchestration, embeddings, and evals
dashboard/      Next.js merchant dashboard and hosted checkout
sdk/server/     TypeScript server SDK
sdk/browser/    TypeScript browser checkout SDK
monitor/        Prometheus, Grafana, Kafka JMX, alerting
bench/          k6 benchmark scenarios and results
cloud-deploy/   AWS deployment references
docs/           Architecture, engineering guidance, planning, and archive
```

`ai-service/` is intentionally top-level rather than a `backend/` Maven module. The Java backend remains the payment-domain authority; the AI service owns only AI-specific concerns such as document chunks, embeddings, prompt/model adapters, redacted model-call audit, and evaluation runs.

## Recommended Local Run

Use Docker Compose. This is the supported local path and does not require local Java, Node, or PostgreSQL setup.

```bash
cp .env.docker.example .env
docker compose up --build
```

The default command starts only the core stack — Postgres, `gateway-service`, and the dashboard. Everything else (rail, virtual account, Kafka/Redis, observability, AI) is opt-in behind a Compose profile:

| Service | URL | Profile |
|---------|-----|---------|
| Dashboard | http://localhost:3000 | default |
| Backend API (gateway) | http://localhost:8080 | default |
| Rail service API | http://localhost:8081 | `rail` |
| Rail simulator (HTTP) | http://localhost:9099 | `rail` |
| Rail simulator (ISO8583 TCP) | localhost:9091 | `rail` |
| Virtual account service | http://localhost:8086 | `virtual-account` |
| AI service | http://localhost:8090 | `ai` |
| Prometheus | http://localhost:9090 | `infra` |
| Grafana | http://localhost:3001 | `infra` |

`--profile infra` brings up everything at once (Redis, Kafka, rail, virtual account, and observability); the narrower `rail`, `virtual-account`, and `ai` profiles bring up only that track plus its own dependencies. Example:

```bash
docker compose --profile virtual-account up --build   # gateway + dashboard + VA ledger + Kafka
docker compose --profile infra up --build              # everything
```

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

# OR, everything (Redis/Kafka, rail, virtual account, observability)
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

- [Docs index](docs/README.md)
- [Architecture overview](docs/architecture/overview.md)
- [Security boundaries](docs/architecture/security-boundaries.md)
- [Payment core](docs/architecture/payment-core.md)
- [Roadmap](docs/planning/roadmap.md)
- [High-throughput payment core plan](docs/planning/high-throughput-payment-core-plan.md)
- [Payment orchestration, routing, retry, and instrument plan](docs/planning/payment-orchestration-routing-retry-plan.md)
- [Multi-rail ISO 8583 / ISO 20022 plan](docs/planning/multi-rail-iso8583-iso20022-plan.md)
- [RAG support assistant plan](docs/planning/rag-assistant-plan.md)
- [Payment operations agent plan](docs/planning/payment-operations-agent-plan.md)
- [Development guide](docs/engineering/development-guide.md)
- [Connector development](docs/engineering/connector-development.md)
- [Virtual account ledger guide](docs/engineering/virtual-account-guide.md)
- [Gateway to virtual account event integration](docs/architecture/gateway-va-event-integration.md)
- [Testing strategy](docs/engineering/testing-strategy.md)
- [Server SDK](sdk/server/README.md)

## Tech Stack

| Layer | Technology |
|-------|------------|
| Backend | Java 21, Spring Boot 3.2, Spring Security, Spring Data JPA |
| Database | PostgreSQL, Flyway, ShardingSphere-JDBC |
| Async | Apache Kafka, Spring Kafka, transactional outbox |
| Hot path | Redis, Redisson |
| Card rail | Netty (TCP transport), jPOS (ISO 8583 codec / GenericPackager) |
| Bank rail | HTTP + XML, JAXB POJOs (ISO 20022 pain/pacs/camt messages) |
| Dashboard | Next.js 15, Tailwind CSS, shadcn/ui, TanStack Query |
| SDKs | TypeScript server SDK and browser SDK |
| Observability | Micrometer, Prometheus, Grafana, Kafka JMX |

## Security Notes

- API requests use `Authorization: Bearer sk_xxx`.
- Browser checkout uses publishable `pk_xxx` keys.
- Provider credentials are encrypted at rest.
- Provider webhooks require signature verification.
- Do not put production secrets in `.env` files; use a deployment secret manager.
