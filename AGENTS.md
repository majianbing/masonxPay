# MasonXPay Agent Guide

MasonXPay is evolving from a payment gateway into a payment operations platform. The core product is a multi-provider payment gateway; the scale track adds sharding, Kafka, Redis, projections, and preview operations; the future AI control plane remains advisory and never executes payment decisions directly.

## Repository Map

- `backend/`: Maven multi-module reactor (Java 21, Spring Boot 3.2). Sub-modules:
  - `common/`: shared error model, ID generation, tenant context (`com.masonx.common`).
  - `contracts/`: shared event contracts — `EventEnvelope`, settlement DTOs (`com.masonx.contracts`).
  - `gateway-service/`: payment gateway — intents, providers, routing, webhooks, sharding, Kafka workers, Redis hot path, projections, subscriptions, disputes, audit log (`com.masonx.paygateway`).
  - `virtual-account-service/`: double-entry ledger, VA accounts, balance management, Kafka settlement consumer (`com.masonx.virtualaccount`).
- `dashboard/`: Next.js merchant/admin UI.
- `sdk/server/`, `sdk/browser/`: TypeScript SDKs. Browser checkout UI lives in `sdk/browser/src/index.ts`.
- `monitor/`: Prometheus, Grafana, Kafka JMX assets.
- `bench/`: k6 benchmark scenarios.
- `cloud-deploy/`: deployment assets.
- `docs/`: structured architecture, engineering guidance, active planning, and archived historical reference.

## Primary Docs

- Docs index: `docs/README.md`
- Architecture overview: `docs/architecture/overview.md`
- Security boundaries: `docs/architecture/security-boundaries.md`
- Payment core invariants: `docs/architecture/payment-core.md`
- Product and phase roadmap: `docs/planning/roadmap.md`
- High-throughput payment core plan: `docs/planning/high-throughput-payment-core-plan.md`
- AI-assisted operations control-plane plan: `docs/planning/ai-control-plane-plan.md`
- Detailed development guide: `docs/engineering/development-guide.md`
- Connector development guide: `docs/engineering/connector-development.md`
- Testing strategy: `docs/engineering/testing-strategy.md`
- Full historical prompt/reference: `docs/archive/payment-gateway-full-prompt.md`
- Root README for setup and public project overview: `README.md`

## Architecture Snapshot

- Financial source of truth: Postgres payment tables and logical shards. Redis is a post-commit hot-path cache only, never authoritative.
- Idempotency: DB-backed reservation/route records. Kafka, read projections, and optional future OpenSearch are supporting systems, not payment-state authorities.
- Async propagation: transactional outbox in Postgres → Kafka publisher → worker consumers for webhook fan-out and projections.
- Backend: Maven multi-module reactor. `gateway-service` owns the payment gateway; `virtual-account-service` owns the double-entry ledger and VA accounts; `common` and `contracts` are shared libraries. Cross-service calls go through Kafka events or explicit service interfaces — never direct package shortcuts across module boundaries.
- AI control plane: advisory only. AI investigates and proposes; deterministic validators and human approval remain between AI output and any applied config change.

## Current Phases

- MVP/core gateway: complete enough for multi-provider payment flows, hosted checkout, dashboard, webhooks, RBAC, MFA, and observability.
- Phase 4 (Merchant Operations): complete — analytics, webhook management, event replay, customer vault, disputes, and merchant audit log (4.6) all delivered.
- High-throughput track H1-H8 complete: logical payment sharding, state/idempotency hardening, Kafka outbox/workers, Redis hot path, preview profile, benchmark/simulator observability, H6 read-model hardening, and capacity proof (~190 charges/s postgres-only, ~250/s with Redis+Kafka).
- Advanced orchestration Phase O: O1-O5 plus O3b routing UI consolidation are done. Current work includes provider-scoped `PaymentInstrument` rows from hosted checkout, seeded capability-aware route policies, connector capability management UI, route-policy list/create/edit/simulation UI, dry-run route simulation, simulator-backed local testing, audit-backed publish/archive, strict route-condition validation, outcome-action retry/fallback, and scheduled retry visibility for capture recovery. Track exact progress in `docs/planning/payment-orchestration-routing-retry-plan.md`.
- AI operations control plane: planned. AI analyzes, recommends, explains, and drafts config changes; validators, human approval, and deterministic routing remain authoritative.

## Commands

- `docker compose up --build`: run the default local stack.
- `docker compose -p masonxpay-preview --env-file .env.preview -f docker-compose.yml -f docker-compose.preview.yml up --build`: run the preview stack.
- `cd backend && mvn compile`: compile backend.
- `cd backend && mvn test`: run backend tests.
- `cd dashboard && npm run build`: build dashboard.
- `cd sdk/server && npm run build`: build server SDK.
- `cd sdk/browser && npm run build && npm run bundle`: build/browser bundle SDK.
- `docker compose -f docker-compose.yml -f docker-compose.bench.yml --profile bench run --rm k6`: run benchmark profile.

## Non-Negotiable Architecture Rules

- Every new table must include `merchant_id`; every read/write path must enforce tenant scope.
- TEST/LIVE mode is a separate isolation layer from tenant scope. Mode-scoped data such as connectors, customers, payment links, subscriptions, invoices, retries, payment instruments, and dashboard queries must filter by both `merchant_id` and mode whenever the resource can exist in TEST and LIVE.
- Merchant portal users and platform admin users stay separate.
- Do not weaken auth, CORS, CSP, or MFA. Provider webhooks are unauthenticated at the HTTP edge, so signature verification is mandatory.
- Never log secrets, tokens, PAN/card data, CVV, private keys, raw provider payloads, or signature headers.
- Webhook/outbox writes must stay atomic with payment state.
- Every action that moves funds — charge, capture, refund, recurring invoice payment — must have an idempotency guarantee: a deterministic key derived from stable identifiers (never random UUIDs) sent to the provider, and an idempotent DB state check before executing so retries and concurrent workers cannot double-charge or double-refund.
- Do not add `@Transactional` to methods that perform remote provider calls; keep DB transactions short and around state changes.
- Every new non-sharded table added via Flyway migration must also be registered in `DataSourceConfig.singleTables()` so ShardingSphere routes it directly to ds_0 without SQL interception. Omitting this causes ShardingSphere to fail on Postgres-specific syntax (e.g. FOR UPDATE SKIP LOCKED, JSON operators).
- Every merchant-facing list API that can grow beyond 50 items must use Spring Data `Pageable` with `@PageableDefault(size=20)` and return `Page<T>`. Frontend list components must bind a `page` state to the query key and render prev/next controls. Unbounded `List<T>` responses are only acceptable for small, bounded sets (e.g. items on a single record).
- Keep route fallback credential-safe: provider-scoped payment tokens can only be reused on the original provider account. Cross-route fallback requires a portable instrument or explicit customer re-authorization.
- Raw PAN, track data, and CVV must never enter MasonXPay core services at any phase — including future direct-acquiring phases. Network tokens (Visa VTS DPAN / Mastercard MDES) are the only card references that may cross the MasonXPay service boundary. Any future PCI-scoped component must be a separately deployed, isolated service.
- Keep browser payment UI centralized in `sdk/browser/src/index.ts`.
- Keep Postgres/sharded payment tables authoritative for financial state. Redis, Kafka, read projections, and optional future OpenSearch are supporting systems, not payment-state authorities.
- Prefer mature infrastructure components for Redis, Kafka, database access, mapping, retries, rate limiting, and similar cross-cutting behavior.
- Keep submodules clean and focused; avoid turning one module into a catch-all.
- Keep the backend as a clean modular monolith. Treat package boundaries as module boundaries: payment/refund state transitions, provider adapters, routing, webhook delivery, outbox/Kafka workers, projections, Redis hot path, identity/access, and dashboard/API entrypoints should each own one concern. Cross-module calls should go through services/interfaces or outbox events, not direct shortcuts into another module's internals.
- AI must not authorize, decline, or route payments directly. AI output must pass deterministic validation and human approval before config changes are applied.
- External AI models must receive only redacted, aggregated, policy-approved evidence. Secrets, raw payment payloads, card data, provider credentials, webhook signatures, private keys, tokens, and unredacted PII must never be sent to model providers. Support a no-external-AI mode where deterministic workers and human review function without external model calls.

## Engineering Style

- Java: 4-space indentation, constructor injection, DTOs at API boundaries. Root packages: `com.masonx.paygateway` (gateway-service), `com.masonx.virtualaccount` (virtual-account-service), `com.masonx.common`, `com.masonx.contracts`.
- TypeScript/React: 2-space indentation, PascalCase components, camelCase functions, `@/` imports.
- Keep business logic out of controllers.
- Add comments only when intent is non-obvious: a hidden constraint, a subtle invariant, a workaround for a known bug, or behavior that would surprise a reader. Do not explain what the code does; well-named identifiers already do that.
- Avoid broad `catch (Exception)` blocks unless there is a clear fallback and logging strategy.
- Add or update tests for business logic, state transitions, auth boundaries, routing, webhooks, and bug fixes.
- Do not claim tests passed unless they actually ran.

## Testing Strategy

Use the test pyramid for feature work. Do not rely on expensive E2E tests as the main correctness layer.

- Unit tests: cover deterministic business logic, validators, routing/capability matching, retry decisions, payment/refund state transitions, security helpers, and serialization/mapping edge cases. These should be fast, focused, and numerous.
- Integration tests: cover behavior crossing module boundaries, persistence, migrations, repositories, API controllers, auth/tenant scope, transaction behavior, outbox writes, and simulator-backed provider flows.
- E2E/smoke tests: cover only critical merchant/customer workflows such as hosted checkout, payment links, connector preview, dashboard capability/routing configuration, and webhook delivery. Prefer Mason Simulator over external PSP sandboxes unless the external provider behavior itself is under test.
- Do not mark a phase or feature complete until the relevant layer of the pyramid has passing coverage, or the remaining verification gap is explicitly documented in the phase tracker.

Keep tests modular and easy to navigate:

- Place backend unit tests next to the owning service/package: routing tests under `service` or `service/routing`, provider tests under `provider`, web/controller tests under `web`, Redis/Kafka/projection tests under their module packages.
- Keep test classes focused on one behavior owner. Avoid catch-all test classes that mix routing, provider calls, dashboard API behavior, and persistence setup.
- Use small builders or local helper methods inside tests before introducing shared fixtures. Add shared fixtures only when duplication is real and the fixture does not hide important setup.
- Name tests by behavior and expected result, for example `resolveAccountForProvider_withContext_filtersByCapabilities`.
- Keep simulator-backed tests deterministic by configuring success/failure rates explicitly.
- E2E tests should be grouped separately from fast unit/integration tests and run through an explicit command or Docker/preview profile.

## Before Final Response

Summarize:

- Files changed
- Why each change was made
- Tests run and results
- Remaining risks or follow-up work
