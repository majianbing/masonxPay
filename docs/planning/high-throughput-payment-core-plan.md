# High-Throughput Payment Core Upgrade Plan

Stable architecture extracted from this tracker lives in [sharding, Kafka, and Redis](../architecture/sharding-kafka-redis.md) and [payment core](../architecture/payment-core.md). Keep this file focused on phase status, open decisions, and implementation notes.

This document records the planned evolution of MasonXPay from a single-node payment gateway into a high-throughput, low-latency payment core suitable for interview discussion and staged implementation.

The current system intentionally avoids Redis and Kafka/RabbitMQ for the MVP scale profile. This upgrade intentionally lifts that limitation for a new scale profile where write throughput, async event fan-out, and low-latency coordination justify additional infrastructure.

## Goals

- Keep the synchronous payment path low-latency and predictable.
- Preserve strong consistency for money-moving state transitions.
- Scale payment writes without concentrating load on hot merchants.
- Move dashboard search, analytics, webhook delivery, and operational views off the transactional payment path.
- Keep Postgres as the financial source of truth.
- Use Redis, Kafka, Postgres read models, and optional future OpenSearch only where they have clear ownership.
- Implement the upgrade in small, reviewable phases.

## Non-Goals

- Do not make Redis authoritative for payment state.
- Do not use read projections or search indexes for payment state checks.
- Do not introduce distributed transactions across Postgres, Kafka, Redis, and providers.
- Do not split into many microservices before the boundaries are proven.
- Do not weaken tenant isolation, webhook verification, request tracing, or log redaction.

## Target Architecture

```text
Client / merchant API
        |
        v
Spring Boot payment core
        |
        +--> Redis
        |    - idempotency hot cache
        |    - merchant/API rate limits
        |    - provider health/routing cache
        |    - short-lived checkout/session state
        |
        +--> Postgres payment shards
        |    - authoritative payment state
        |    - idempotency records
        |    - ledger entries
        |    - provider references
        |    - transactional outbox
        |
        +--> Outbox publisher
             |
             v
           Kafka
             |
             +--> webhook delivery workers
             +--> dashboard/read model workers
             +--> reconciliation workers
             +--> optional future OpenSearch indexing workers
```

## Data Ownership

| Data | Owner | Consistency |
|---|---|---|
| Payment state | Postgres payment shard | Strong |
| Idempotency decision | Postgres payment shard | Strong |
| Ledger entries | Postgres payment shard | Strong |
| Outbox events | Postgres payment shard | Strong with payment state |
| Provider reference mapping | Postgres payment shard | Strong |
| Redis idempotency cache | Redis | Best-effort optimization |
| Rate limit counters | Redis | Eventually consistent enough for throttling |
| Provider health/routing hints | Redis | Eventually consistent |
| Merchant dashboard search | Kafka-fed Postgres read models | Eventually consistent |
| Merchant analytics/read views | Kafka-fed projection tables | Eventually consistent |
| Future support/search scale-out | Optional OpenSearch adapter | Eventually consistent |

## Sharding Strategy

The current `payment_intents` and `payment_requests` table family will become a logical shard set. Both grow with payment traffic and both are on the high-volume payment path, so sharding only one would leave the other as a future bottleneck.

Use `payment_id` or merchant-provided `order_id` as the shard key, not `merchant_id`.

Reasoning:

- `merchant_id` sharding is convenient for dashboard queries but creates hot shards for large merchants, flash sales, and retry storms.
- `payment_id` or `order_id` distributes writes more evenly.
- Most correctness-critical operations naturally target one payment and can route directly to one shard.
- Merchant dashboard queries should use async read projections rather than cross-shard fan-out.

Initial implementation target:

```text
payment_intents_00
payment_intents_01
...
payment_intents_63

payment_requests_00
payment_requests_01
...
payment_requests_63
```

Phase H1 creates these physical tables with numeric suffixes. The app still writes to the original logical tables until the ShardingSphere datasource switch and repository routing are completed.

Routing rule:

```text
shard = hash(payment_id) % shard_count
```

Prefer logical table sharding in one Postgres instance first. This keeps local development manageable and creates a migration path to physical database shards later.

Initial shard count: 64 logical shards.

Rationale:

- Large enough to flatten write distribution for benchmark and interview-scale demos.
- Small enough to keep local Flyway migrations, schema inspection, and operational overhead manageable.
- Can map multiple logical shards to one physical database today and redistribute them across physical databases later.
- Avoids premature complexity from hundreds or thousands of tables before the workload proves it.

Changing shard count later is possible but requires a resharding plan, so use 64 as the stable initial target.

Lookup registries must be sharded too. They should not become central bottleneck tables after the payment tables are sharded:

```text
payment_idempotency_keys_00 ... _63
shard key = hash(merchant_id + idempotency_key)

provider_payment_refs_00 ... _63
shard key = hash(provider + connector_account_id + provider_payment_id)
```

Registry rows are compact routing pointers. They should store the owning `payment_intent_id` and `payment_shard_id`, then the caller routes to the authoritative payment shard.

Recommended Spring component:

- Apache ShardingSphere-JDBC at the `DataSource` layer.
- Keep the application model pointed at logical table names where practical.
- Use explicit shard routing helpers for operations that must resolve by `payment_id`.

## ID Strategy

The shard key should be `payment_id` for MasonXPay-owned flows and merchant `order_id` only when it is guaranteed unique and stable for the merchant flow.

Recommended initial choice: keep the existing payment ID shape if it is already externally visible, and route with:

```text
shard = hash(payment_id) % 64
```

If new IDs are introduced, prefer UUIDv7 plus hash routing.

| Option | Pros | Cons |
|---|---|---|
| Existing ID + hash routing | Lowest migration risk; preserves API compatibility; works with current records; hides shard topology | Distribution depends on existing ID entropy; may need validation tests; not naturally time-sortable if current ID is random |
| UUIDv7 + hash routing | Time-sortable for logs/debugging; standardizing quickly across systems; good DB locality when used as raw ID; still distributes well after hashing | Newer than UUIDv4; Java/library support must be verified; timestamp component should not be used directly for sharding |
| ULID + hash routing | Human-friendly; lexicographically sortable; broadly understood in app code | Monotonic/time prefix can create hot ranges if used directly; must hash before routing; more custom handling than UUID |
| Snowflake-style ID | Compact; can encode time and topology; strong ordering story | Leaks topology if shard bits are embedded; adds ID generator availability concerns; more moving parts for little initial benefit |

Do not route by raw timestamp prefixes. Always hash the final stable payment identifier before modulo routing.

## Query Model

Authoritative payment reads:

- `GET /payments/{paymentId}` routes directly to the owning shard.
- Confirm, capture, void, refund, webhook reconcile, and payment status checks route by `payment_id`.
- Webhooks that arrive with provider references must resolve provider reference to `payment_id`, then route to the owning shard.

Dashboard/search reads:

- Do not fan out to all payment shards for normal dashboard screens.
- Publish payment lifecycle events through the outbox and Kafka.
- Build Postgres-backed `payment_read_models` for merchant search, filters, payment lists, and dashboard views.
- Keep read models and any future OpenSearch adapter out of the correctness path.

## Consistency Rules

Strong consistency is required for:

- payment creation
- confirm/capture/void/refund transitions
- idempotency records
- ledger entries
- outbox event creation
- webhook deduplication
- provider reference mapping

Use the owning Postgres shard for these guarantees:

- short database transactions
- unique constraints
- state transition validation
- row-level locking where needed
- optimistic version columns where useful
- append-only ledger records

Provider calls must stay outside database transactions.

Kafka provides durable async propagation, not correctness. Redis provides latency and coordination help, not correctness. Postgres read models provide dashboard and support views, not authority. OpenSearch remains an optional future adapter if read-model search outgrows Postgres.

## Distributed Locks

Avoid Redis distributed locks for financial correctness.

Allowed Redis lock use cases:

- suppress duplicate non-critical background work
- coordinate provider health refreshes
- throttle expensive reconciliation scans
- guard scheduled jobs where duplicate execution is tolerable

Forbidden Redis lock use cases:

- preventing double capture
- preventing double refund
- enforcing payment state transitions
- making ledger writes safe
- replacing idempotency table constraints

The database must remain the final gate for money-state correctness.

## Kafka Event Backbone

Use Spring for Apache Kafka rather than hiding the payment event model behind broad abstractions.

Kafka responsibilities:

- payment lifecycle event fan-out
- webhook delivery work queue
- Postgres read-model projection updates
- reconciliation events
- merchant notification events
- durable worker status for failed async processing

Partitioning:

- Key payment lifecycle events by `payment_id` to preserve per-payment ordering.
- Key merchant aggregate events by `merchant_id` only for aggregate consumers that need merchant locality.

Decision: use Kafka, not RabbitMQ, for the high-throughput profile.

Rationale:

- Payment lifecycle events benefit from an append-only replayable log.
- Consumer groups fit webhook delivery, projections, reconciliation, and analytics fan-out.
- Per-payment ordering can be preserved by keying events with `payment_id`.
- Backpressure and lag are visible operational signals.
- Replay is valuable for rebuilding Postgres read models, aggregate projections, and any future search indexes.

The transactional outbox remains mandatory:

```text
DB transaction:
  update payment state
  insert ledger entry if applicable
  insert outbox event

After commit:
  outbox publisher publishes to Kafka
  mark outbox event Kafka-published
```

The existing DB outbox remains the recovery source. Kafka publication uses a separate
`kafka_published` state so webhook fan-out state and Kafka delivery state do not block
or overwrite each other. Published outbox rows are retained only long enough for replay
and incident investigation, then cleaned in small batches when both webhook fan-out and
Kafka publication are complete.

Event/log lifecycle:

- `outbox_events`: cleanup is safe only when `published = TRUE` and `kafka_published = TRUE`.
- `gateway_events` and `webhook_deliveries`: keep longer than outbox rows because merchants may need delivery history and replay.
- `gateway_logs`: remains a side-flow audit/observability table and should keep its existing partition/retention policy.
- Projection/search history belongs in purpose-built read models or optional future search indexes, not in the payment state check path.

## Redis Usage

Use Redisson and Spring Data Redis for explicit, bounded use cases:

- idempotency hot cache backed by Postgres uniqueness
- Redisson-backed per-merchant/API key/IP rate limiting
- provider health and circuit breaker hint cache
- short-lived checkout/session data
- duplicate suppression for async workers where DB verification still exists

Every Redis-backed path must define:

- authoritative fallback
- TTL
- failure behavior when Redis is unavailable
- whether stale data is acceptable

## Search and Read Projections

Decision: use Postgres-backed `payment_read_models` for H6 dashboard/search projections. Keep OpenSearch as an optional future adapter only if dashboard/support search outgrows Postgres. Elasticsearch is intentionally out of scope for now.

Postgres read-model responsibilities:

- merchant payment search
- payment list views
- support filters
- dashboard-facing denormalized rows
- projection backfill and repair visibility

Read models and future search indexes must not be used for:

- payment status authority
- confirm/capture/refund decisions
- idempotency decisions
- ledger correctness

Reasoning:

- Postgres read models keep local/preview operations simpler and match the current implementation.
- The projection can lag; UI should tolerate eventual consistency and expose lag/failure signals where useful.
- Direct payment status checks still route to the owning Postgres shard.
- OpenSearch can be added later behind the projection boundary without changing payment correctness semantics.

Merchant aggregate limits are eventually consistent by default. If a future product requirement needs hard real-time merchant limits, design a separate authoritative aggregate path keyed by merchant and time bucket rather than overloading the payment shard model.

## Observability Requirements

Add metrics before claiming throughput improvements:

- payment create latency
- payment confirm latency
- provider latency by provider/account
- shard distribution by payment count and write rate
- shard routing failures
- Redis hit/miss rates
- Redis unavailable fallback count
- Kafka publish latency
- Kafka consumer lag
- outbox backlog and oldest unpublished event age
- payment projection lag and failed projection event count
- webhook retry and worker failure counts

Keep `X-Request-Id` propagation across API, outbox, Kafka, workers, and provider calls.

## Implementation Phases

### Phase H0: Architecture Baseline

- Record this plan.
- Update roadmap to identify the high-throughput track.
- Document that Redis/Kafka restrictions are intentionally lifted only for this scale profile.
- Define benchmark targets before implementation.

### Phase H1: Sharding Foundation

- Introduce shard-count configuration.
- Add payment shard routing utility and tests.
- Add logical table shard migrations for the core payment request/state tables.
- Backfill existing `payment_intents` and `payment_requests` into the shard tables for local/demo environments.
- Integrate ShardingSphere-JDBC.
- Keep existing APIs behavior-compatible.
- Add tests proving same `payment_id` routes consistently.

### Phase H2: Strong Idempotency and State Machine Hardening

- Ensure idempotency records live on the owning payment shard.
- Add or verify unique constraints for payment/order idempotency.
- Add optimistic versioning and row-level locking around payment transitions.
- Add regression tests for duplicate confirm/capture/refund races.

Current H2 progress:

- `payment_intents` now has a JPA `@Version` column across the logical table and all 64 shard tables.
- Create-payment idempotency now reserves `(merchant_id, idempotency_key)` in the sharded idempotency registry before inserting the owning payment intent.
- Confirm, capture, cancel, and refund setup paths lock the owning payment intent row during short DB decision/update transactions.
- Refunds validate against available refundable amount: original intent amount minus active `PENDING` and `SUCCEEDED` refunds.
- Outbox and stale-intent lock queries use JPQL pessimistic locks instead of native `FOR UPDATE SKIP LOCKED`, keeping them compatible with ShardingSphere-JDBC.
- H2 test coverage includes duplicate-create Docker smoke checks and a formal concurrent refund over-refund regression test.
- Follow-up hardening: add formal concurrent confirm, capture, and cancel race tests around the provider/orchestrator boundary.

### Phase H3: Kafka Outbox Publisher

- Add Kafka dependency and local Docker Compose service. Done.
- Publish existing outbox events to Kafka after commit. Done.
- Track Kafka publish state separately from webhook fan-out state. Done.
- Add published-outbox retention cleanup for rows that completed both webhook fan-out and Kafka publication. Done.
- Add Kafka broker JMX metrics to Prometheus/Grafana. Done.
- Preserve database outbox as the recovery mechanism. Done.
- Defer idempotent Kafka consumers and worker status handling to H4.

Current H3 status:

- Docker uses the official Apache Kafka image, extended locally only to attach the Prometheus JMX exporter Java agent.
- The `payment.lifecycle.events` topic is created by Spring Kafka with 12 partitions.
- The publisher is at-least-once: every message carries `outboxEventId`, so H4 consumers must dedupe by event id.
- Kafka publication state is independent from webhook fan-out state through `kafka_published`, `kafka_published_at`, `kafka_publish_attempts`, and `kafka_last_error`.
- Prometheus scrapes backend outbox metrics and Kafka broker JMX metrics; Grafana includes Kafka health, outbox depth/age, publish rate, broker throughput, and under-replicated partitions.
- H3 test coverage covers successful publish, failed publish, and topic configuration.

### Phase H4: Async Workers

- Move webhook delivery work behind Kafka consumers. Done for the webhook fan-out worker.
- Add payment read projection worker. Done.
- Keep OpenSearch out of H4/H6 unless Postgres read-model search proves insufficient.
- Keep all workers idempotent.
- Defer reconciliation worker split and notification worker until there is a concrete production requirement.

Current H4 progress:

- Default Docker and `local` runs keep Kafka disabled and the scheduled DB outbox poller enabled, so the live demo can run with Postgres only.
- The optional Docker `infra` profile and the preview profile enable Kafka for high-throughput async fan-out when Kafka is available.
- The Kafka consumer reads `payment.lifecycle.events`, extracts `outboxEventId`, and calls the same idempotent webhook fan-out path used by the original outbox poller.
- `WebhookDeliveryService.processOutboxEvent(...)` locks only unpublished outbox rows, creates `gateway_events` and `webhook_deliveries`, marks the outbox row published, and then performs immediate webhook delivery attempts outside the database transaction.
- Kafka itself is not the retry ledger. Producer-side publication retry is driven by `outbox_events.kafka_published=false`, `kafka_publish_attempts`, and `kafka_last_error`. Webhook delivery retry is driven by `webhook_deliveries` status and attempt metadata. We are not adding Kafka-native DLQ topics for this phase.
- Malformed Kafka envelopes are skipped with a warning because they indicate a producer/envelope bug rather than a normal business retry case.
- `PaymentProjectionConsumerService` uses its own consumer group (`masonxpay-payment-projection`) so it does not compete with webhook delivery for Kafka partitions.
- Payment projection writes `payment_read_models`, a tenant-scoped dashboard/read-model table keyed by `payment_intent_id`.
- Projection idempotency and failure visibility use `projection_processed_events`, keyed by `outbox_event_id` and including `merchant_id`.
- Payment intent events upsert the read model. Refund events update refund summary fields when the payment projection already exists; missing payment projections are recorded as failed projection events for operational visibility.
- `PaymentProjectionBackfillService` can backfill `payment_read_models` from existing payment/refund rows through the physical datasource. It is enabled in Docker/local profiles and disabled by default in the base profile so production can run it intentionally.
- Dashboard payment list/search reads use `payment_read_models` when Kafka projection is enabled; with Kafka disabled, the list falls back to authoritative core payment tables. Payment detail always reads authoritative core payment tables.
- Projection health is exposed through `payment.projection.read_model.count`, `payment.projection.failed.count`, and `payment.projection.oldest_failed.age.seconds`, with Prometheus alerts for failed/stale projection events.

### Phase H5: Redis Hot Path

- Added Spring Data Redis, Redisson, and a local Docker Redis service.
- Added `RedisRateLimitFilter` for API-key payment create/confirm limits using Redisson `RRateLimiter`. Redis is the enforcement counter, but outage behavior is explicit and fail-open by default.
- Added `PaymentIdempotencyCache` for completed payment-create idempotency routes. It is a fast path only; misses and Redis failures fall back to the sharded Postgres idempotency registry. New routes are cached only after the Postgres transaction commits.
- Added `RedisProviderHealthCache` so routing health hints can be shared across app nodes. The existing in-memory health map remains the fallback.
- Added Redis hot-path metrics for rate-limit allow/block/fallback, idempotency hit/miss/fallback, and provider-health fallback.
- Base, Docker, and `local` profiles keep Redis disabled by default so live demos can run without Redis. The optional Docker `infra` profile plus explicit flags, and the preview profile, enable Redis for the high-throughput hot path.

### Phase H5b: Preview Runtime

- Added `application-preview.yml`, `.env.preview`, and `docker-compose.preview.yml` to run a production-like local stack before H6 reliability and operability work.
- Preview enables Kafka outbox publication, webhook consumers, payment projection consumers, Redis hot path, Prometheus, and Grafana by default.
- Preview disables the legacy webhook DB poller and keeps projection backfill opt-in so repair/backfill behavior is intentional.
- Preview uses `REDIS_URL=redis://redis:6379`, matching how managed Redis endpoints are usually provided in production.
- Preview is still a single-node laptop environment. It validates behavior, observability, and operational workflow, but not multi-node durability or regional failover.

### Phase H6: Dashboard Read Models

- Harden merchant payment list/search on Postgres-backed `payment_read_models`.
- Polish search filters, pagination, and dashboard query ergonomics.
- Add projection lag indicators where needed.
- Keep payment detail/status checks routed to authoritative shards.
- Keep OpenSearch as an optional future adapter only if read-model search outgrows Postgres.

### Phase H7: Benchmarks and Interview Narrative — Done

- Added k6 scenarios for create, confirm, refund, idempotency replay, get-by-id, and dashboard list flows.
- Added a TEST-only Mason Simulator provider as a normal connector/provider adapter so benchmarks exercise routing, provider credential loading, retry orchestration, payment request writes, state transitions, refund guards, and outbox writes without calling external PSP sandboxes.
- Added configurable simulator PSP success rates for degradation testing.
- Added benchmark overlay support for default Postgres-only mode and optional Kafka/Redis `infra` mode, with `RUN_MODE` labels for comparison.
- Updated Prometheus/Grafana dashboards for payment volume, success rate, provider latency, Kafka outbox publish rate, broker throughput, k6 operation latency, and HTTP status mix.
- Updated `bench/README.md` with local run commands, Grafana panel behavior, simulator PSP settings, and interpretation notes.

Follow-up hardening, not H7 blockers:

- Capture fresh benchmark baselines from `bench/results/` after repeated local and preview runs.
- Add deeper failure-mode playbooks:
  - Kafka unavailable
  - Redis unavailable
  - one shard degraded
  - payment projection lagging
  - provider timeout/retry storm

### Related Track: AI-Assisted Operations Control Plane

The AI-assisted control plane is intentionally split out of the high-throughput payment core plan. It depends on high-throughput telemetry, preview traffic simulation, routing rules, and deterministic rollback workers, but it is a separate product/operations track.

See [AI-assisted operations control plane plan](ai-control-plane-plan.md).

## Open Decisions

Closed decisions:

- Use 64 logical shards initially.
- Shard both `payment_intents` and `payment_requests`.
- Route by hashed `payment_id`; use merchant `order_id` only when it is stable and unique for that flow.
- Preserve existing IDs initially if they are already part of the public API; prefer UUIDv7 plus hash routing for new ID generation.
- Use Kafka for the high-throughput event backbone.
- Use Postgres-backed `payment_read_models` for H6 dashboard/search projections; keep OpenSearch as optional future scale-out only.
- Treat merchant aggregate limits as eventually consistent unless a future hard-limit requirement appears.

Migration decision:

- Local/demo environments use an idempotent Flyway backfill from the original `payment_intents` and `payment_requests` tables into the 64 shard tables.
- Production should not rely on a single Flyway data-copy step for live traffic. Use dual-write, batched backfill, per-shard validation, read cutover, and a rollback/archive window.

Still open:

- Whether ShardingSphere-JDBC can cover every needed JPA query cleanly or whether some hot-path repositories need explicit routing.
- Whether provider-reference lookup needs its own sharded mapping table or a compact global lookup table before routing to the payment shard.

## Current Recommendation

Use:

```text
Spring Boot
+ Apache ShardingSphere-JDBC
+ Spring for Apache Kafka
+ Spring Data Redis
+ Resilience4j
+ Actuator/Micrometer/Prometheus
+ optional OpenSearch adapter later only if dashboard/support search outgrows Postgres
```

Keep the implementation staged. The first code phase should be sharding foundation and tests, not Kafka or Redis.
