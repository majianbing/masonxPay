# Payment Core

The payment core owns money-moving state transitions. It must remain deterministic, idempotent, tenant-scoped, and auditable.

## Source Of Truth

Postgres payment tables and logical shards are authoritative for financial state. Redis, Kafka, read projections, OpenSearch, dashboard caches, and provider callbacks support operations but do not decide payment truth.

Strong consistency is required for:

- payment creation
- confirm, capture, cancel, and refund transitions
- idempotency records
- provider reference mappings
- ledger entries
- outbox event creation
- webhook deduplication

## Idempotency

Every action that moves funds must have two protections:

- an idempotent database state check before executing
- a deterministic provider idempotency key derived from stable identifiers

Provider idempotency keys must not be random UUIDs. They should be derived from payment intent, refund, invoice, attempt, or operation identifiers so retries and concurrent workers cannot double-charge or double-refund.

## Transaction Discipline

Provider calls must happen outside database transactions. Keep transactions short and wrap only state changes, lock acquisition, idempotency reservation, provider-reference persistence, and outbox writes.

Do not add `@Transactional` to methods that perform remote provider calls. Use two-phase service flows where the system reserves or validates state, calls the provider, then records the result in a separate transaction.

## Outbox And Webhooks

Webhook/outbox writes must stay atomic with payment state changes. The database outbox is the recovery source. Kafka publication, webhook fan-out, dashboard projections, and read-model updates consume committed outbox data and must be idempotent.

Provider webhook controllers must deduplicate inbound events before processing and must reconcile into normal payment state services rather than directly mutating state.

## Module Ownership

Payment/refund state transitions belong in backend services, not controllers. Cross-module interactions should go through service interfaces or outbox events. Controllers translate API requests and responses only.

## References

- [Security boundaries](security-boundaries.md)
- [Sharding, Kafka, and Redis](sharding-kafka-redis.md)
- [High-throughput payment core plan](../planning/high-throughput-payment-core-plan.md)
