# MasonXPay Agent Guide

MasonXPay is a multi-provider payment gateway and payment operations platform. It covers PSP integration (Stripe, Square, Braintree, Mollie, Flutterwave, Paystack, Mason Simulator), orchestration, subscriptions, a high-throughput Kafka/Redis/sharding core, a multi-rail layer (ISO 8583 card rail and ISO 20022 bank rail), and a prepaid card program platform backed by a double-entry ledger. AI capabilities are advisory only: the RAG assistant is read-only and documentation-backed, and the payment operations agent never executes payment decisions.

This file holds durable rules. Phase status lives only in `docs/planning/`; do not restate it here.

## Start Here

Sessions on this project can be months apart. Re-orient before touching code:

1. Read `docs/planning/README.md` for what is active now, then the one plan you will work in.
2. Run `gh pr list` and `git log --oneline -10` to see what is in flight.
3. Run `docker compose ps`. If the stack is down and you need it: `cp .env.docker.example .env` (if missing), then `docker compose up --build -d`.
4. Work on a branch, verify with the commands below, and update the plan doc's status in the same PR as the code.

## Repository Map

- `backend/`: Maven multi-module reactor (Java 21, Spring Boot 3.2). Sub-modules:
  - `common/`: shared error model, ID generation, tenant context (`com.masonx.common`).
  - `contracts/`: shared event contracts — `EventEnvelope`, settlement DTOs, `RailSettlementEvent`, `RailPaymentResolvedEvent` (`com.masonx.contracts`).
  - `fee-engine/`: reusable stateless fee calculation engine for prepaid issuing and gateway economics (`com.masonx.feeengine`).
  - `gateway-service/`: payment gateway — intents, providers, routing, webhooks, sharding, Kafka workers, Redis hot path, projections, subscriptions, disputes, audit log, AI facade and authority policy (`com.masonx.paygateway`).
  - `virtual-account-service/`: double-entry ledger, VA accounts, balance management, prepaid card program platform (card programs, issuer partners/adapters, cardholders, card lifecycle, controls, clearing/settlement reconciliation), Kafka settlement consumer (`com.masonx.virtualaccount`).
  - `rail-service/`: ISO 8583 card rail and ISO 20022 bank rail client — canonical payment model, Netty/jPOS adapters, rail router, settlement event publisher, reconciliation API (`com.masonx.rail`).
  - `rail-simulator/`: two-sided network simulator — card-network-sim (Netty TCP, port 9091) and bank-rail-sim (HTTP, port 9090) (`com.masonx.railsim`).
- `ai-service/`: optional top-level Python AI coprocessor for RAG, model orchestration, embeddings, and evals. It is not a Maven module and must not own payment, connector, routing, ledger, approval, tenant, or credential state.
- `dashboard/`: Next.js merchant/admin UI.
- `sdk/server/`, `sdk/browser/`: TypeScript SDKs. Browser checkout UI lives in `sdk/browser/src/index.ts`.
- `monitor/`: Prometheus, Grafana, Kafka JMX assets.
- `bench/`: k6 benchmark scenarios.
- `cloud-deploy/`: deployment assets.
- `docs/`: architecture, engineering guidance, active planning, archived reference.

## Primary Docs

- Docs index: `docs/README.md`
- Planning index and current status: `docs/planning/README.md`
- Architecture overview: `docs/architecture/overview.md`
- Security boundaries: `docs/architecture/security-boundaries.md`
- Payment core invariants: `docs/architecture/payment-core.md`
- AI capabilities and authority model: `docs/architecture/ai-capabilities.md`
- Product and phase roadmap: `docs/planning/roadmap.md`
- Development guide (engineering index): `docs/engineering/development-guide.md`
- Connector development: `docs/engineering/connector-development.md`
- Testing strategy: `docs/engineering/testing-strategy.md`
- Historical full prompt/reference: `docs/archive/payment-gateway-full-prompt.md`
- Root README for setup and public project overview: `README.md`

## Architecture Snapshot

- Financial source of truth: Postgres payment tables and logical shards. Redis is a post-commit hot-path cache only, never authoritative.
- Idempotency: DB-backed reservation/route records. Kafka, read projections, and optional future OpenSearch are supporting systems, not payment-state authorities.
- Async propagation: transactional outbox in Postgres → Kafka publisher → worker consumers for webhook fan-out and projections.
- Backend: modular monolith. `gateway-service` owns the payment gateway; `virtual-account-service` owns the ledger, VA accounts, and prepaid card issuing; `fee-engine` owns stateless fee calculation; `rail-service` owns the ISO 8583 and ISO 20022 acquirer-side clients; `rail-simulator` owns the network simulators; `common` and `contracts` are shared libraries. Cross-service calls go through Kafka events or explicit service interfaces, never package shortcuts.
- Runtime routing: deterministic rules and service logic. Phase O adds payment instruments, capability checks, route policies, route simulation, and outcome-aware retry/fallback.
- Prepaid issuing: MasonXPay is the card-program/program-manager layer, not the licensed issuer. Issuer integration sits behind `IssuerCardProviderService`; the rail simulator is the first adapter.
- AI service placement: `ai-service/` is a top-level Python service, not part of `backend/`. The Java gateway remains the policy gate for identity, tenant scope, TEST/LIVE mode scope, RBAC, approval state, and payment-domain mutation.
- AI authority: the executable model is `com.masonx.paygateway.ai.policy`. The RAG assistant answers from approved docs only. The operations agent investigates and proposes; deterministic validators and human approval sit between AI output and any applied change.

## Commands

```bash
docker compose up --build -d                                     # core stack: Postgres, gateway, dashboard
docker compose --profile infra up --build -d                     # everything (Kafka, Redis, rail, VA, observability)
docker compose -p masonxpay-preview --env-file .env.preview -f docker-compose.yml -f docker-compose.preview.yml up --build
cd backend && mvn -B compile                                     # compile all modules
cd backend && mvn -B test -DexcludedGroups=integration           # fast suite: unit, ArchUnit, migration registry (what CI runs)
cd backend && mvn -B test                                        # full suite; integration tests need the Docker stack
cd backend && mvn -B -pl gateway-service -am test -DexcludedGroups=integration
cd backend && mvn -B -pl gateway-service -am -Dtest='SomeTest' -Dsurefire.failIfNoSpecifiedTests=false test
cd dashboard && npm run build
cd sdk/server && npm run build
cd sdk/browser && npm run build && npm run bundle
docker compose -f docker-compose.yml -f docker-compose.bench.yml --profile bench run --rm k6
```

Maven resolves through a mirror configured in `~/.m2/settings.xml`; a transient 502 fails dependency resolution, and a rerun usually clears it.

## Workflow Conventions

- Every change goes through a branch and a pull request into `master`. Never commit directly on `master`.
- Branch names: `feature/<topic>`, `fix/<topic>`, `docs/<topic>`, `chore/<topic>`.
- Commit subject: one imperative sentence, capitalized, no trailing period, under 72 characters, no type prefix. The body says why, not what.
- PR body sections: Summary, Docs (if any), Tests (the exact command and the tool's own result lines), Remaining risks.
- When a phase item's status changes, update its plan doc and `docs/planning/README.md` in the same PR.
- Do not add status, phase lists, or "next work" to this file or `CLAUDE.md`.

## Non-Negotiable Architecture Rules

Rules marked **[enforced]** have an executable check; a failing check is a boundary violation, not a test to loosen.

- Every new table must include `merchant_id`; every read/write path must enforce tenant scope.
- TEST/LIVE mode is a separate isolation layer from tenant scope. Mode-scoped data such as connectors, customers, payment links, subscriptions, invoices, retries, payment instruments, card programs, and dashboard queries must filter by both `merchant_id` and mode whenever the resource can exist in TEST and LIVE.
- Merchant portal users and platform admin users stay in separate tables and realms.
- Do not weaken auth, CORS, CSP, or MFA. Provider webhooks are unauthenticated at the HTTP edge, so signature verification is mandatory.
- Never log secrets, tokens, PAN/card data, CVV, private keys, raw provider payloads, or signature headers.
- Webhook/outbox writes must stay atomic with payment state.
- Every action that moves funds — charge, capture, refund, recurring invoice payment, card funding, fee posting — must have an idempotency guarantee: a deterministic key derived from stable identifiers (never random UUIDs) sent to the provider or issuer, and an idempotent DB state check before executing so retries and concurrent workers cannot double-charge or double-refund.
- Do not add `@Transactional` to methods that perform remote provider or issuer calls; keep DB transactions short and around state changes. **[enforced for the `provider` package]** by `ArchitectureRulesTest`.
- In `gateway-service`: every table created by a Flyway migration must be either a sharded logical table or registered in `DataSourceConfig.singleTables()` so ShardingSphere routes it to ds_0 without SQL interception. **[enforced]** by `ShardingSingleTableRegistryTest`. Only `gateway-service` uses ShardingSphere.
- Every merchant-facing list API that can grow beyond 50 items must use Spring Data `Pageable` with `@PageableDefault(size=20)` and return `Page<T>`. Frontend list components must bind a `page` state to the query key and render prev/next controls. Unbounded `List<T>` responses are only acceptable for small, bounded sets.
- Keep route fallback credential-safe: provider-scoped payment tokens can only be reused on the original provider account. Cross-route fallback requires a portable instrument or explicit customer re-authorization.
- Raw PAN, track data, and CVV must never enter MasonXPay core services. In `rail-service` and `rail-simulator`, ISO 8583 DE2 fields must be masked before any log write or DB persistence; the simulator uses test PANs only. Cards store only masked PAN, BIN, last4, expiry, and issuer references. Any PCI-scoped component must be separately deployed and isolated.
- Keep browser payment UI centralized in `sdk/browser/src/index.ts`.
- Keep Postgres/sharded payment tables authoritative for financial state. Redis, Kafka, read projections, and optional future OpenSearch are supporting systems.
- Keep the VA ledger accounting-grade: ledger entries are append-only; journal headers carry merchant/mode scope; account statements compute balances from signed entry sums by `effective_date` and account normal balance, not from posting-order `balance_after` snapshots.
- Prefer mature infrastructure components for Redis, Kafka, database access, mapping, retries, rate limiting, and similar cross-cutting behavior.
- Keep the backend a clean modular monolith. Package boundaries are module boundaries: payment/refund state transitions, provider adapters, routing, webhook delivery, outbox/Kafka workers, projections, Redis hot path, identity/access, billing, issuing, and dashboard/API entrypoints each own one concern. **[enforced]**: `gateway-service` never depends on `virtualaccount`/`rail` packages; nothing outside `web` depends on a controller; `domain` never depends on `service` or `web`; provider adapters never touch repositories. Controllers using repositories directly is frozen in `src/test/resources/archunit_store`: the backlog may only shrink.
- Business logic stays out of controllers. New controllers inject services, not repositories.
- AI must not authorize, decline, or route payments directly. Every action handed to an AI surface must be an `AiAction` and pass `AiAuthorityPolicy.requireAdvisory`; new AI tools are enumerated there first. AI output must pass deterministic validation and human approval before config changes are applied. **[enforced]**: the policy package depends only on `java.*` and the common error model.
- The RAG assistant must remain read-only and retrieve only approved documentation/help sources. Its vector database must not contain production logs, raw database rows, provider payloads, webhook bodies, secrets, credentials, card data, customer PII, or payment/ledger records.
- External AI models receive only redacted, aggregated, policy-approved evidence. Secrets, raw payment payloads, card data, provider credentials, webhook signatures, private keys, tokens, and unredacted PII never go to model providers. Support a no-external-AI mode where deterministic workers and human review work without external model calls. Treat model output, retrieved text, provider messages, webhook payloads, and logs as untrusted data.

## Verification Rules

- Run the tests for every module you touched before claiming anything. Report the tool's own result: Maven's `BUILD SUCCESS`/`BUILD FAILURE` line and the Surefire `Tests run:` counts. Never infer success from a shell or pipeline exit status.
- Tests that need Postgres, Kafka, or a Spring context against a real database carry `@Tag("integration")`. CI excludes them; run them locally with the Docker stack up when you touch persistence, migrations, controllers, or outbox behavior.
- ArchUnit rules live in `gateway-service` under `architecture/`. Add a rule when a boundary is written down; never delete one to make a build pass.
- Do not claim tests passed unless the command actually ran in this session.

## Engineering Style

- Java: 4-space indentation, constructor injection, DTOs at API boundaries. Root packages: `com.masonx.paygateway` (gateway-service), `com.masonx.virtualaccount` (virtual-account-service), `com.masonx.feeengine` (fee-engine), `com.masonx.rail` (rail-service), `com.masonx.railsim` (rail-simulator), `com.masonx.common`, `com.masonx.contracts`.
- TypeScript/React: 2-space indentation, PascalCase components, camelCase functions, `@/` imports.
- Frontend display: never use raw internal IDs as the primary label for human-facing controls, tables, cards, or selections when a name, description, masked identifier, provider label, email, reference, or other human-readable field is available. IDs may appear as secondary monospace metadata, detail copy, or debug/admin context.
- Add comments only when intent is non-obvious: a hidden constraint, a subtle invariant, a workaround, or behavior that would surprise a reader. Do not explain what the code does.
- Avoid broad `catch (Exception)` blocks unless there is a clear fallback and logging strategy.
- Add or update tests for business logic, state transitions, auth boundaries, routing, webhooks, and bug fixes.

## Testing Strategy

Use the test pyramid; `docs/engineering/testing-strategy.md` has the full rules.

- Unit tests are the primary correctness layer: routing/capability matching, retry decisions, validators, state transitions, security helpers, fee calculation, mapping edge cases. Fast, focused, numerous.
- Integration tests cover module boundaries: repositories, migrations, controllers, auth/tenant/mode scope, transaction behavior, outbox writes, simulator-backed provider flows. Tag them `integration`.
- E2E/smoke tests cover only critical merchant/customer journeys and run through an explicit command or profile. Prefer Mason Simulator over external PSP sandboxes unless provider behavior itself is under test.
- Place tests in the package that owns the behavior; keep each test class to one behavior owner; name tests by behavior and expected result, for example `resolveAccountForProvider_withContext_filtersByCapabilities`.
- Do not mark a phase or feature complete until the relevant layer has passing coverage, or the gap is written in the phase tracker.

## Before Final Response

Summarize:

- Files changed and why
- Tests run, with the actual result lines
- Remaining risks or follow-up work
