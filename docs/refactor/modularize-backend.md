# Backend Modularization — Refactor Plan & Progress

Status: **Plan — awaiting approval to execute.** Branch: `refactor/modularize-backend`.

This document is the running source of truth for the backend modularization. Each step is verified green before the next begins, and this file is updated after every step (see [Step Plan](#step-plan) and [Changelog](#changelog)).

## Goal

Split the single-module backend into a Maven multi-module project that prepares a future **Virtual Account** capability for clean extraction into its own microservice — without disrupting the existing gateway. The split is structural and deployment-shaped now; it is not a rewrite of gateway internals.

## Target Architecture

```
backend/                          parent aggregator — packaging=pom (artifactId: backend-service)
├── common/                       com.masonx.common
│                                   BusinessException + error model
│                                   Mode (TEST/LIVE)
│                                   tenant kernel: OrgId / MerchantId / TenantRef (value objects only)
├── contracts/                    com.masonx.contracts
│                                   cross-service Kafka event schemas (transaction, settlement, ...)
│                                   every event carries org + merchant + mode
├── gateway-service/              com.masonx.paygateway  (UNCHANGED packages)
│                                   BOOTABLE — current code lifted as-is
│                                   produces transaction events via existing outbox → Kafka (H3)
└── virtual-account-service/      com.masonx.virtualaccount
                                    BOOTABLE — own @SpringBootApplication
                                    own DB: msx_virtual_account (separate database, same PG instance)
                                    idempotent Kafka consumer → fee / clearing / settlement
```

**Dependency edges (compile-time):**

- `gateway-service → common, contracts`
- `virtual-account-service → common, contracts`
- `contracts → common`
- Neither service depends on the other's implementation. The only runtime link between them is **Kafka**.

## Runtime Model (the key mental model)

`common` and `contracts` are **compile-time libraries (jars)**, not services and not in-memory calls. Each bootable app bakes its **own copy** of those jars into its own deployable:

```
gateway-service.jar   ← own copy of common + contracts classes
virtual-account.jar   ← own copy of common + contracts classes
```

Two processes, two JVMs, no shared memory. The only runtime communication is Kafka:

```
gateway JVM:  TransactionEvent ──serialize──▶ bytes ──▶ Kafka topic
                                                            │
VA JVM:       TransactionEvent ◀──deserialize── bytes ◀─────┘
```

The `contracts` classes define the message **shape** (shared as bytecode); the **data** crosses as a serialized Kafka message. The shared module guarantees both sides *start* from the same definition — it does not keep them in sync after deploy. Schema evolution must stay backward-compatible.

## Persistence & Data Boundaries

- VA owns a **separate database** `msx_virtual_account` on the **same Postgres instance** (created by an init script). VA has its **own** DataSource → EntityManagerFactory → JpaTransactionManager → Flyway, pointed at that database.
- VA **bypasses ShardingSphere entirely.** Gateway's hand-built `DataSourceConfig` and `singleTables()` are **untouched**.
- Each app has exactly **one** DataSource → the multi-datasource-in-one-JVM class of risk does not exist.
- No transaction spans both services; no cross-database foreign keys or joins.
- VA tables keep the **`va_` prefix** even though the database already isolates them. Rationale: the DB boundary is invisible in a query; the prefix is an explicit human signal of ownership (especially for junior developers). Per `docs/engineering/backend-guide.md`.

## Tenant & Mode Boundary

- Share the tenant **identity (kernel)** — `OrgId`, `MerchantId`, `Mode`, `TenantRef` — via `common`. These flow on events and scope queries.
- Do **not** share tenant **persistence**. Gateway's `Merchant` / `Organization` JPA entities and tables stay in gateway. VA must never map, join, or "inherit" them.
- If VA needs a local notion of merchant, it keeps its **own** entity in `msx_virtual_account`, keyed by `merchantId` / `mode` as plain values (no cross-DB FK), populated from consumed events.
- Every event carries `org + merchant + mode` from day one, even though VA's internal tenant enforcement/sub-layer design is **deferred** (to be designed later).

## Deployment / Docker Profiles

- **Minimum stack** (default `docker compose up`): `gateway + dashboard + pg` only. Unchanged.
- **`virtual-account` profile**: adds VA container + Kafka + the `msx_virtual_account` database.
- VA is **optional**, like Redis/Kafka. When VA/Kafka are absent, gateway does not publish settlement-bound events (outbox holds them; publisher is already profile-gated) — no errors.

## Interaction Flow

Rides the existing transactional outbox → Kafka path (H3) and the no-loss / idempotent-consumer rules in `docs/architecture/sharding-kafka-redis.md`:

```
gateway: commit payment state + outbox event (atomic)
       → outbox publisher → Kafka settlement topic (dedicated settlement event)
       → VA consumer group → fee / clearing / settlement in msx_virtual_account
```

VA is a new **idempotent** consumer group (delivery can repeat; settlement must not double-charge fees).

## Service Seam — Architecture Decisions

The seam between gateway and VA is fixed by the split, so these are decided now even though VA's internals are built later.

1. **Dedicated settlement domain event — not the webhook event.** Gateway publishes a purpose-built settlement/domain event, separate from the external webhook-facing payment-status events, so VA's money contract evolves independently of the webhook contract. Additive on the gateway side (one more event type); does not break VA optionality.
2. **First-class event envelope with `mode`.** Cross-service events use an explicit envelope: `eventId, eventType, schemaVersion, occurredAt, correlationId, orgId, merchantId, mode, payload`. `mode` (TEST/LIVE) is a first-class field, never buried in payload, so VA can honor the merchant+mode boundary. Closes a real gap: today's `outbox_events` row has `merchant_id` but no `mode` column.
3. **Outbox-replay bootstrap.** VA backfills by replaying from gateway's durable `outbox_events` table, not from Kafka retention (which won't cover enabling VA later). `OutboxRetentionCleanupService` must not purge faster than the worst-case "VA-enabled-later" window, or an explicit replay path is provided. Safe because posting is DB-level idempotent.
4. **Contract backward-compatibility enforcement.** Events are additive-only with an explicit `schemaVersion`, enforced in CI by a golden-sample deserialization test in the `contracts` module (old serialized samples must deserialize with current DTOs). Graduate to a schema registry / consumer-driven contracts when a second consumer appears.
5. **No sensitive data crosses the seam.** Only non-sensitive settlement fields (amounts, currency, references, tenant, mode). No PAN/CVV/raw provider payloads; VA stays out of PCI scope.
6. **Anti-corruption layer at VA's consumer edge.** VA maps `contracts` events to VA-native commands in its inbound adapter; VA domain/ledger code never imports `contracts`. Detail in `docs/engineering/virtual-account-guide.md`.



## Risks

| Risk | Mitigation |
|---|---|
| Contract/event versioning (the integration surface) | `contracts` is a deliberate, minimal, backward-compatible boundary; both services compile the same version |
| Consumer idempotency (double settlement) | VA dedupes on a deterministic key; DB idempotency constraint in `msx_virtual_account` |
| Shared `common` as soft coupling | keep `common` minimal (kernel + errors only); no gateway-flavored types; accept rebuild-both on common changes |
| Profile wiring ("VA absent" must be clean) | verify minimum stack boots with no VA/Kafka before adding the profile |
| `git mv` history | use `git mv` so reviewers see moves, not delete+add |
| Build/deploy path changes | Dockerfile + compose updated and verified in step 5 |

## Out of Scope (for this refactor)

- Carving gateway's internal packages into api/impl modules.
- Real fee/clearing/settlement business rules beyond one vertical slice.
- VA tenant enforcement / sub-layer design (deferred, designed separately).

## Step Plan

Legend: ☐ pending · ◐ in progress · ☑ done

| # | Step | Verify | Status |
|---|---|---|---|
| 1 | Parent aggregator pom (`backend-service`, packaging=pom); move current `pom.xml` → `gateway-service/pom.xml`; `git mv backend/src → backend/gateway-service/src`. Gateway keeps its `@SpringBootApplication`. | Reactor builds; gateway boots; existing test suite green. | ☐ |
| 2 | Extract `common` module: create `BusinessException` + error model; add `Mode` and tenant value objects (`OrgId`/`MerchantId`/`TenantRef`); relocate any existing shared primitives (measure references first). | Reactor builds; gateway green; no leaked entities/repos in `common`. | ☐ |
| 3 | `contracts` module: transaction event schema carrying `org + merchant + mode`. | Module builds; gateway can reference the event type. | ☐ |
| 4 | `virtual-account-service`: own `@SpringBootApplication`, own DataSource → `msx_virtual_account`, Flyway (`db/migration/va`, `va_` tables), idempotent Kafka consumer, one vertical slice (consume transaction → record a ledger/fee entry). | VA boots standalone; migration applies; consumes a test event idempotently. | ☐ |
| 5 | Docker: keep minimum stack; add `virtual-account` profile (VA + Kafka + `msx_virtual_account` DB); update `backend/Dockerfile` (reactor build, jar paths) and compose. | Minimum stack boots without VA; profile boots VA end-to-end. | ☐ |

## Decision Log

1. Multi-module Maven reactor; parent `backend-service` (packaging=pom).
2. Modules: `common`, `contracts`, `gateway-service`, `virtual-account-service`.
3. No single `app` bootstrap; gateway and VA are each independently bootable.
4. Gateway lifted as-is; package `com.masonx.paygateway` unchanged.
5. VA package `com.masonx.virtualaccount`; own DB `msx_virtual_account` (separate database, same PG instance); own DataSource/Flyway; bypasses ShardingSphere.
6. Interaction is Kafka-only, on the existing outbox → Kafka path; VA is an idempotent consumer group.
7. `contracts` carries `org + merchant + mode` on every event from day one.
8. `common` holds the tenant **kernel** (identity value objects) + `Mode` + `BusinessException` only — never tenant entities/tables.
9. VA tables keep the `va_` prefix as a human ownership signal.
10. VA is optional; default stack is `gateway + dashboard + pg`.
11. VA internal tenant enforcement/sub-layer design deferred.
12. Gateway publishes a **dedicated settlement domain event** (additive), separate from webhook events. _(seam #1)_
13. Cross-service event **envelope carries first-class `mode`** plus eventId/version/correlation/tenant; closes the missing-mode gap in `outbox_events`. _(seam #2)_
14. VA **bootstraps via outbox replay**, not Kafka retention; cleanup retention guards the "VA-later" window. _(seam #3)_
15. Contracts are **additive-only with `schemaVersion`**; CI golden-sample compatibility test in the `contracts` module. _(seam #4)_
16. **No sensitive data crosses the seam**; VA stays out of PCI scope. _(seam #5)_
17. VA applies an **anti-corruption layer** at its consumer edge. _(seam #6; detail in virtual-account-guide.md)_

## Changelog

- _(plan written; no code changed yet)_
