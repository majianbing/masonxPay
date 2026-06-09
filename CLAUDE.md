# MasonXPay Project Skeleton

MasonXPay is a Java/Spring Boot and Next.js payment operations platform. It supports multi-provider payments, merchant dashboard workflows, SDK checkout, webhook delivery, observability, and a high-throughput payment-core track.

## Root Orientation

- `README.md`: setup, product overview, Docker/local/preview instructions.
- `AGENTS.md`: repository rules and agent operating constraints.
- `docs/README.md`: structured documentation index.
- `docs/architecture/overview.md`: durable system map and core invariants.
- `docs/architecture/security-boundaries.md`: tenant/mode, PCI, provider, webhook, and AI data boundaries.
- `docs/architecture/payment-core.md`: payment state, idempotency, transaction, and outbox invariants.
- `docs/planning/roadmap.md`: product phases and future tracks.
- `docs/planning/high-throughput-payment-core-plan.md`: sharding, Kafka, Redis, projections, and preview design.
- `docs/planning/payment-orchestration-routing-retry-plan.md`: Phase O orchestration tracker for instruments, routing, retry, and capability-aware simulation.
- `docs/planning/ai-control-plane-plan.md`: AI-assisted payment operations control-plane design.
- `docs/engineering/development-guide.md`: engineering docs index.
- `docs/engineering/connector-development.md`: connector implementation workflow.
- `docs/engineering/testing-strategy.md`: test coverage and placement rules.
- `docs/archive/payment-gateway-full-prompt.md`: historical full project prompt/reference.

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
- Search/read views: Postgres projection tables now; OpenSearch remains an optional future adapter if search outgrows Postgres, not state authority.
- Runtime routing: deterministic rules and service logic.
- Advanced orchestration: Phase O adds payment instruments, account capability checks, route policies, route simulation, and outcome-aware retry/fallback. `docs/planning/payment-orchestration-routing-retry-plan.md` is the durable status tracker.
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

Phase 4 (Merchant Operations) is now complete: 4.6 merchant audit log delivered.

Next likely work:

- Phase 5: platform maturity — rate limiting (5.2), platform admin UI (5.1), API versioning strategy (5.3), reconciliation (5.5).
- H6: dashboard search/read projection hardening, search polish, and operational readiness around `payment_read_models`.
- Phase O: O1-O5 and O3b routing UI consolidation are done; next orchestration work is O6 optional portable-card support only when cross-PSP portability becomes a real requirement.
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

## Engineering Style

- Java: 4-space indent, `com.masonx.paygateway` root, constructor injection, DTOs at API boundaries.
- TypeScript/React: 2-space indent, PascalCase components, camelCase functions, `@/` imports.
- Business logic out of controllers. Comments only when intent is non-obvious. No broad `catch (Exception)` without a clear fallback and logging strategy.
- Add or update tests for business logic, state transitions, auth boundaries, routing, webhooks, and bug fixes.

## Hard Boundaries

- Keep tenant isolation on every table and query.
- Keep TEST/LIVE mode isolation as a separate boundary from tenant isolation. Any resource that can exist in both environments must be scoped by both merchant and mode in tables, repositories, services, APIs, dashboard query keys, and tests.
- Keep merchant portal users and platform admin users in separate tables and realms.
- Do not weaken payment security, webhook verification, auth, CORS, CSP, or MFA.
- Never log secrets, tokens, PAN/card data, CVV, private keys, raw provider payloads, or signature headers.
- Every action that moves funds — charge, capture, refund, recurring invoice payment — must have an idempotency guarantee: a deterministic key derived from stable identifiers (never random UUIDs) sent to the provider, and an idempotent DB state check before executing so retries and concurrent workers cannot double-charge or double-refund.
- Keep provider calls outside DB transactions.
- Keep webhook/outbox writes atomic with payment state.
- Keep Redis/Kafka/read projections/OpenSearch out of the authoritative payment-state path.
- Keep the backend as a clean modular monolith; cross-module calls go through services/interfaces or outbox events, not direct shortcuts into another module's internals.
- Every merchant-facing list API that can grow beyond 50 items must use Spring Data `Pageable` with `@PageableDefault(size=20)` and return `Page<T>`. Frontend list components must bind a `page` state to the query key and render prev/next controls. Unbounded `List<T>` responses are only acceptable for small, bounded sets (e.g. items on a single record).
- Keep route fallback credential-safe: provider-scoped payment tokens can only be reused on the original provider account. Cross-route fallback requires a portable instrument, future vault/network token support, or explicit customer re-authorization.
- Keep external AI model calls outside the sensitive data boundary; use redacted, aggregated evidence only, and support a no-external-AI mode.
- Keep browser payment UI centralized in `sdk/browser/src/index.ts`.
- Use `docs/engineering/development-guide.md` as the engineering index; use the focused engineering docs for connector, SDK, MFA, testing, and database rules.

## Before Final Response

Summarize: files changed and why, tests run and results, remaining risks or follow-up work.
