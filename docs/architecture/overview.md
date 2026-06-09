# MasonXPay Architecture Overview

MasonXPay is a payment operations platform built around a deterministic multi-provider payment gateway. The system supports hosted and embedded checkout, provider abstraction, route policies, webhooks, observability, high-throughput infrastructure, subscriptions, and a future advisory AI control plane.

## System Map

- `backend/`: Java 21 Spring Boot API, financial state transitions, provider adapters, routing, webhooks, sharding, Kafka workers, Redis hot path, migrations, and tests.
- `dashboard/`: Next.js merchant and admin UI.
- `sdk/server/`: TypeScript server SDK.
- `sdk/browser/`: browser checkout SDK and all client-side payment UI.
- `monitor/`: Prometheus, Grafana, Kafka JMX, and alerting assets.
- `bench/`: k6 benchmark scenarios.
- `cloud-deploy/`: deployment assets.

## Core Invariants

- Postgres payment tables and logical shards are the financial source of truth.
- Redis is a post-commit hot-path cache and soft coordination layer only.
- Kafka is used for async propagation from the transactional outbox; it is not a payment-state authority.
- Every tenant-owned table must include `merchant_id`, and every read/write path must enforce tenant scope.
- TEST/LIVE mode isolation is separate from tenant isolation and must be enforced for mode-scoped resources.
- Provider webhooks are unauthenticated at the HTTP edge, so signature verification is mandatory.
- Raw PAN, track data, and CVV must never enter MasonXPay core services.
- AI is advisory only. Deterministic validators and human approval must sit between AI output and any applied config change.

## Payment Runtime

Payment and refund state transitions are owned by backend services, not controllers. Any action that moves funds must have an idempotent DB state check before execution and a deterministic provider idempotency key derived from stable identifiers. Remote provider calls must happen outside database transactions; transactions should stay short and wrap state changes only.

Webhook and outbox writes must remain atomic with payment state. Kafka workers, projection consumers, Redis caches, and dashboard read models support operational workflows but do not own financial truth.

## Module Boundaries

The backend is a modular monolith. Payment/refund state transitions, provider adapters, routing, webhook delivery, outbox/Kafka workers, projections, Redis hot path, identity/access, billing, and dashboard/API entrypoints each own one concern. Cross-module calls should go through services, interfaces, or outbox events.

## Detailed References

- [Security boundaries](security-boundaries.md)
- [Payment core](payment-core.md)
- [Sharding, Kafka, and Redis](sharding-kafka-redis.md)
- [Routing and orchestration](routing-orchestration.md)
- [Subscriptions and billing](subscriptions-billing.md)
- [AI control plane](ai-control-plane.md)
- [Development guide](../engineering/development-guide.md)
- [Roadmap](../planning/roadmap.md)
