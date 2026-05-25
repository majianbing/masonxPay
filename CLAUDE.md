# MasonXPay Project Skeleton

MasonXPay is a Java/Spring Boot and Next.js payment operations platform. It supports multi-provider payments, merchant dashboard workflows, SDK checkout, webhook delivery, observability, and a high-throughput payment-core track.

## Root Orientation

- `README.md`: setup, product overview, Docker/local/preview instructions.
- `AGENTS.md`: repository rules and agent operating constraints.
- `docs/ROADMAP.md`: product phases and future tracks.
- `docs/HIGH_THROUGHPUT_PAYMENT_CORE_PLAN.md`: sharding, Kafka, Redis, projections, and preview design.
- `docs/PAYMENT_ORCHESTRATION_ROUTING_RETRY_PLAN.md`: Phase O orchestration tracker for instruments, routing, retry, and capability-aware simulation.
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
- Advanced orchestration: Phase O adds payment instruments, account capability checks, route policies, route simulation, and outcome-aware retry/fallback. `docs/PAYMENT_ORCHESTRATION_ROUTING_RETRY_PLAN.md` is the durable status tracker.
- AI control plane: planned advisory layer only. AI may investigate and propose; validators and humans approve; deterministic workers execute.

## Current Track

High-throughput H1-H5b and H7 are complete:

- H1: logical payment sharding.
- H2: financial state/idempotency hardening.
- H3: Kafka outbox publisher.
- H4: async webhook/projection workers.
- H5: Redis hot path.
- H5b: preview profile.
- H7: benchmark/simulator observability.

Next likely work:

- H6: dashboard search/read projections.
- Phase O: continue advanced payment orchestration. O1 and O4 are done; O2 has capability APIs/UI but payment-link hosted-checkout end-to-end validation is open; O3 has usable backend foundations with remaining route-policy audit history, strict validation, and dashboard editing/simulation UI.
- Phase AI: model-agnostic AI-assisted operations control plane after deterministic orchestration is mature.

## Key Commands

```bash
docker compose up --build
docker compose -p masonxpay-preview --env-file .env.preview -f docker-compose.yml -f docker-compose.preview.yml up --build
cd backend && mvn compile
cd backend && mvn test
cd dashboard && npm run build
```

## Testing Strategy

Follow the test pyramid for feature work:

- Unit tests are the primary correctness layer for deterministic business logic: routing, capability matching, retry decisions, validators, state transitions, security helpers, and mapping edge cases.
- Integration tests cover module boundaries: repositories, migrations, controllers, auth/tenant scope, transaction behavior, outbox writes, and simulator-backed provider flows.
- E2E/smoke tests are limited to critical merchant/customer journeys: hosted checkout, payment links, connector preview, dashboard capability/routing configuration, and webhook delivery.
- Prefer Mason Simulator for payment-flow tests that do not specifically need Stripe, Square, Braintree, or Mollie behavior.
- Do not mark a feature complete only because an E2E path works; business rules still need unit or integration coverage.
- Do not claim test success unless the command actually ran.

Keep tests modular:

- Put tests in the package that owns the behavior: `service/routing` for routing/capability logic, `provider` for provider adapters, `web` for controllers, Kafka/Redis/projection packages for infrastructure behavior.
- Keep each test class focused on one behavior owner.
- Use local builders/helpers first; add shared fixtures only when they reduce real duplication without hiding important setup.
- Name tests by behavior and expected result.
- Keep E2E tests separate from fast test suites and run them with an explicit command/profile.

## Hard Boundaries

- Keep tenant isolation on every table and query.
- Do not weaken payment security, webhook verification, auth, CORS, CSP, or MFA.
- Keep provider calls outside DB transactions.
- Keep Redis/Kafka/OpenSearch out of the authoritative payment-state path.
- Keep route fallback credential-safe: provider-scoped payment tokens can only be reused on the original provider account. Cross-route fallback requires a portable instrument, future vault/network token support, or explicit customer re-authorization.
- Keep external AI model calls outside the sensitive data boundary; use redacted, aggregated evidence only, and support a no-external-AI mode.
- Keep browser payment UI centralized in `sdk/browser/src/index.ts`.
- Use `docs/DEVELOPMENT_GUIDE.md` for detailed connector, SDK, MFA, and implementation rules.
