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
- `docs/planning/rag-assistant-plan.md`: RAG support assistant plan.
- `docs/planning/payment-operations-agent-plan.md`: payment operations agent plan.
- `docs/planning/multi-rail-iso8583-iso20022-plan.md`: Phase MR — ISO8583 card rail, ISO 20022 bank rail, VCC product, ledger integration milestone tracker.
- `docs/planning/reusable-fee-engine-plan.md`: reusable PPC9/gateway fee engine boundary, rule model, snapshots, and FE roadmap.
- `docs/engineering/development-guide.md`: engineering docs index.
- `docs/engineering/connector-development.md`: connector implementation workflow.
- `docs/engineering/testing-strategy.md`: test coverage and placement rules.
- `docs/archive/payment-gateway-full-prompt.md`: historical full project prompt/reference.

## Modules

- `backend/`: Maven multi-module reactor (Java 21, Spring Boot 3.2). Sub-modules:
  - `common/`: shared utilities — error model, ID generation (`com.masonx.common`), tenant context.
  - `contracts/`: shared event contracts — `EventEnvelope`, settlement DTOs, `RailSettlementEvent`, `RailPaymentResolvedEvent` (`com.masonx.contracts`).
  - `fee-engine/`: reusable stateless fee calculation engine for prepaid issuing and gateway economics (`com.masonx.feeengine`).
  - `gateway-service/`: payment gateway — intents, providers, routing, webhooks, sharding, Kafka workers, Redis hot path, projections, subscriptions, disputes, audit log (`com.masonx.paygateway`).
  - `virtual-account-service/`: double-entry ledger, VA accounts, balance management, prepaid card program platform (card programs, issuer partners/adapters, cardholders, card lifecycle, controls, clearing/settlement reconciliation), Kafka settlement consumer (`com.masonx.virtualaccount`).
  - `rail-service/`: ISO 8583 card rail and ISO 20022 bank rail client — canonical payment model, Netty/jPOS adapters, rail router, settlement event publisher, reconciliation API (`com.masonx.rail`).
  - `rail-simulator/`: two-sided network simulator — card-network-sim (Netty TCP, port 9091) and bank-rail-sim (HTTP, port 9090).
- `ai-service/`: optional top-level Python AI coprocessor for RAG, model orchestration, embeddings, and evals. It is not a Maven module and must not own payment, connector, routing, ledger, approval, tenant, or credential state.
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
- AI capabilities: advisory only, and they must stay that way. The RAG assistant has a bootstrap docs-only implementation that answers from approved docs/help content and does not read operational payment data. The payment operations agent is still planned; it investigates and proposes, validators and humans approve, and deterministic workers execute.
- AI service placement: `ai-service/` is a top-level Python service, not part of `backend/`. The Java gateway remains the policy gate for identity, tenant scope, TEST/LIVE mode scope, RBAC, approval state, and payment-domain mutation.

## Current Track

High-throughput H1-H5b, H7, and H8 are complete:

- H1: logical payment sharding.
- H2: financial state/idempotency hardening.
- H3: Kafka outbox publisher.
- H4: async webhook/projection workers.
- H5: Redis hot path.
- H5b: preview profile.
- H7: benchmark/simulator observability.
- H8: capacity proof — ~190 charges/s (postgres-only), ~250/s (Redis+Kafka infra); bottleneck and scale levers documented in `bench/RESULTS.md`.

Phase 4 (Merchant Operations) is now complete: 4.6 merchant audit log delivered.

Phase MR (Multi-Rail) is now complete (MR0–MR5):

- MR0: module skeleton, canonical model, DB tables, Docker Compose.
- MR1: ISO 8583 full auth flow — Netty TCP, jPOS codec, VisaSim/MastercardSim, VCC issuer (BIN 999999).
- MR2: timeout/UNKNOWN/reversal discipline — UNKNOWN ≠ FAILED; 0400 reversal; late-response detection.
- MR3: ISO 20022 bank rail — pain.001 → pain.002 → pacs.002 → camt.054; SepaSimAdapter + FedNowSimAdapter.
- MR4: Kafka settlement events → VA ledger; reconciliation exceptions API.
- MR5: VA Account Management APIs; gateway→rail bridge; `RailPaymentResolvedConsumer`.

See `docs/planning/multi-rail-iso8583-iso20022-plan.md`.

Provider connectors added since MR: Flutterwave (TEST hosted checkout, verified) and Paystack (TEST hosted checkout; sandbox verification blocked on Paystack account activation).

Phase PPC (Prepaid Card Program Platform) extends the ledger-backed VCC foundation into a sponsor-bank-compatible program-manager model. MasonXPay is the card-program/program-manager layer, not the licensed issuer; the rail simulator is the first issuer adapter. PPC1–PPC6 are complete; PPC7–PPC8 are substantially delivered:

- PPC1: issuer partner / card program / cardholder model, tenant+mode scoped, with active-program/active-cardholder gates on card creation.
- PPC2: `IssuerCardProviderService` adapter boundary + dispatcher; `RAIL_SIM` adapter owns simulator card-token/PAN behavior.
- PPC3: lifecycle APIs (withdraw, lock, unlock, logical close, terminate), expanded card statuses, transition guards.
- PPC4: program/card JSON controls evaluated before balance checks, deterministic decline reasons, daily velocity.
- PPC5: internal auth-reversal endpoint, idempotent hold-release postings, cumulative release tracking, opt-in stale-hold expiry worker.
- PPC6: clearing/refund/original-credit ingestion — auth matching, settlement journals, clearing-event records, conservative parking, cumulative refund protection.
- PPC7 (partial): issuer settlement-report ingestion + report-vs-clearing-vs-ledger reconciliation summaries; EXTERNAL system-of-record balance reconciliation still open.
- PPC8 (partial): dashboard `Issuing` shell — Programs, Cardholders, Cards, Controls, Authorization history, Settlement views; merchant-safe exception actions still deferred.

See `docs/planning/prepaid-card-program-platform-plan.md` and `docs/planning/reusable-fee-engine-plan.md`.

Next likely work:

- Phase PPC remainder: PPC9 reusable fee-engine foundation has FE1-FE2 complete (stateless module, Aviator expression matching, fixed/percentage calculation, rounding, validation); next is prepaid schedule persistence and assessment snapshots. Also finish PPC7 EXTERNAL reconciliation and PPC8 ops actions; PPC0 naming cleanup. Create-card idempotency/atomicity and issuer lifecycle partial-failure reconciliation are now covered in the prepaid-card service foundation.
- Phase RAG: docs-backed support assistant — bootstrap delivered. RAG0-RAG3 and RAG6 are complete: allowlisted sources, audience filtering, refusals, Qdrant ingestion with stable chunk/point IDs, gateway facade with budgets and audit, and a governance eval suite. Retrieval is lexical/token-hash, not semantic. Remaining: RAG4 feedback controls, RAG5 framework comparison, RAG7 production hardening (vector DB auth/TLS, alerting, runbooks), and RAG8 semantic retrieval quality (embedding baseline, recall@k/MRR/nDCG, chunking, hybrid/rerank, groundedness). See `docs/planning/rag-assistant-plan.md`.
- Phase AI: model-agnostic payment operations agent — telemetry-to-incident detection, investigation workflow, policy change proposals, human approval, deterministic execution. See `docs/planning/payment-operations-agent-plan.md`.
- Phase 15 (deferred): platform maturity — rate limiting, platform admin UI, API versioning strategy. Lower priority.
- Phase O: O6 optional portable-card support only when cross-PSP portability becomes a real requirement.

## Key Commands

```bash
docker compose up --build
docker compose -p masonxpay-preview --env-file .env.preview -f docker-compose.yml -f docker-compose.preview.yml up --build
cd backend && mvn compile                                        # all modules
cd backend && mvn test                                           # all modules
cd backend && mvn -pl fee-engine test                            # fee engine only
cd backend && mvn -pl gateway-service test                       # gateway only
cd backend && mvn -pl virtual-account-service test               # VA only
cd dashboard && npm run build
```

## Testing Strategy

Follow the test pyramid for feature work:

- Unit tests are the primary correctness layer for deterministic business logic: routing, capability matching, retry decisions, validators, state transitions, security helpers, and mapping edge cases.
- Integration tests cover module boundaries: repositories, migrations, controllers, auth/tenant scope, transaction behavior, outbox writes, and simulator-backed provider flows.
- E2E/smoke tests are limited to critical merchant/customer journeys: hosted checkout, payment links, connector preview, dashboard capability/routing configuration, and webhook delivery.
- Prefer Mason Simulator for payment-flow tests that do not specifically need a real provider's behavior (Stripe, Square, Braintree, Mollie, Flutterwave, Paystack).
- Do not mark a feature complete only because an E2E path works; business rules still need unit or integration coverage.
- Do not claim test success unless the command actually ran.

Keep tests modular:

- Put tests in the package that owns the behavior: `service/routing` for routing/capability logic, `provider` for provider adapters, `web` for controllers, Kafka/Redis/projection packages for infrastructure behavior.
- Keep each test class focused on one behavior owner.
- Use local builders/helpers first; add shared fixtures only when they reduce real duplication without hiding important setup.
- Name tests by behavior and expected result.
- Keep E2E tests separate from fast test suites and run them with an explicit command/profile.

## Engineering Style

- Java: 4-space indent, constructor injection, DTOs at API boundaries. Root packages: `com.masonx.paygateway` (gateway-service), `com.masonx.virtualaccount` (virtual-account-service), `com.masonx.feeengine` (fee-engine), `com.masonx.common` (common), `com.masonx.contracts` (contracts).
- TypeScript/React: 2-space indent, PascalCase components, camelCase functions, `@/` imports.
- Frontend display: never use raw internal IDs as the primary label for human-facing controls, tables, cards, or selections when a name, description, masked identifier, provider label, email, reference, or other human-readable field is available. IDs may appear as secondary monospace metadata, detail copy, or debug/admin context.
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
- The RAG assistant must remain read-only and retrieve only approved documentation/help sources. Its vector database must not contain production logs, raw database rows, provider payloads, webhook bodies, secrets, credentials, card data, customer PII, or payment/ledger records.
- Keep external AI model calls outside the sensitive data boundary; use redacted, aggregated evidence only, and support a no-external-AI mode.
- Keep browser payment UI centralized in `sdk/browser/src/index.ts`.
- Use `docs/engineering/development-guide.md` as the engineering index; use the focused engineering docs for connector, SDK, MFA, testing, and database rules.

## Before Final Response

Summarize: files changed and why, tests run and results, remaining risks or follow-up work.
