# MasonXPay Project Skeleton

MasonXPay is a Java/Spring Boot and Next.js payment operations platform. It supports multi-provider payments, merchant dashboard workflows, SDK checkout, webhook delivery, observability, and a high-throughput payment-core track.

## Root Orientation

- `README.md`: setup, product overview, Docker/local/preview instructions.
- `AGENTS.md`: repository rules and agent operating constraints.
- `docs/ROADMAP.md`: product phases and future tracks.
- `docs/HIGH_THROUGHPUT_PAYMENT_CORE_PLAN.md`: sharding, Kafka, Redis, projections, and preview design.
- `docs/AI_CONTROL_PLANE_PLAN.md`: AI-assisted payment operations control-plane design.
- `docs/DEVELOPMENT_GUIDE.md`: detailed implementation guide and connector/SDK rules.
- `docs/payment-gateway-full-prompt.md`: historical full project prompt/reference.

## Modules

- `backend/`: Spring Boot API and payment core.
- `dashboard/`: Next.js merchant portal and admin surfaces.
- `sdk/browser/`: browser checkout UI and provider SDK integration.
- `sdk/server/`: server SDK.
- `monitor/`: Prometheus/Grafana/Kafka monitoring assets.
- `bench/`: k6 scenarios.
- `cloud-deploy/`: cloud deployment assets.

## Architecture Snapshot

- Financial source of truth: Postgres payment tables and logical shards.
- Idempotency: DB-backed reservation/route records, with Redis as a post-commit hot-path cache.
- Async propagation: transactional outbox in Postgres, Kafka publisher/consumers for high-throughput worker fan-out.
- Search/read views: projection tables now; OpenSearch planned for dashboard/support search, not state authority.
- Runtime routing: deterministic rules and service logic.
- AI control plane: planned advisory layer only. AI may investigate and propose; validators and humans approve; deterministic workers execute.

## Current Track

High-throughput H1-H5b is complete:

- H1: logical payment sharding.
- H2: financial state/idempotency hardening.
- H3: Kafka outbox publisher.
- H4: async webhook/projection workers.
- H5: Redis hot path.
- H5b: preview profile.

Next likely work:

- H6: dashboard search/read projections.
- H7: benchmarks and failure-mode documentation.
- Phase AI: model-agnostic AI-assisted operations control plane.

## Key Commands

```bash
docker compose up --build
docker compose -p masonxpay-preview --env-file .env.preview -f docker-compose.yml -f docker-compose.preview.yml up --build
cd backend && mvn compile
cd backend && mvn test
cd dashboard && npm run build
```

## Hard Boundaries

- Keep tenant isolation on every table and query.
- Do not weaken payment security, webhook verification, auth, CORS, CSP, or MFA.
- Keep provider calls outside DB transactions.
- Keep Redis/Kafka/OpenSearch out of the authoritative payment-state path.
- Keep external AI model calls outside the sensitive data boundary; use redacted, aggregated evidence only, and support a no-external-AI mode.
- Keep browser payment UI centralized in `sdk/browser/src/index.ts`.
- Use `docs/DEVELOPMENT_GUIDE.md` for detailed connector, SDK, MFA, and implementation rules.
