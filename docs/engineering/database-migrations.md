# Database And Migrations

Flyway owns the schema. Do not use Hibernate `ddl-auto: create` or `ddl-auto: update`.

## Baseline Rules

- Every new tenant-owned table must include `merchant_id`.
- Every read/write path must enforce tenant scope.
- Mode-scoped resources must include and enforce TEST/LIVE mode as a separate boundary from tenant scope.
- All financial state transitions go through the service layer, not direct controller/repository writes.
- Provider calls must stay outside DB transactions.

Mode-scoped resources include connectors, customers, payment links, subscriptions, invoices, retries, payment instruments, and dashboard list/detail queries.

## Flyway

- Add schema changes through versioned Flyway migrations.
- Keep production `ddl-auto` as `validate`.
- Keep migrations compatible with the configured local and Docker Postgres versions.
- Prefer explicit constraints for tenant scope, uniqueness, state machines, and idempotency records.

## ShardingSphere Single Tables

Every new non-sharded table added through Flyway must be registered in `DataSourceConfig.singleTables()` so ShardingSphere routes it directly to `ds_0`.

Missing registration can cause ShardingSphere to intercept Postgres-specific SQL such as:

- `FOR UPDATE SKIP LOCKED`
- JSON operators
- dialect-specific locking or update syntax

## Financial Tables

For tables involved in payment, refund, billing, retry, provider-reference, or webhook/outbox state:

- include `merchant_id`
- include mode where the resource can exist in TEST and LIVE
- use deterministic idempotency identifiers
- add uniqueness constraints for idempotent operations
- add status fields with explicit legal transitions in service code
- write outbox events atomically with state changes

## References

- [Security boundaries](../architecture/security-boundaries.md)
- [Payment core](../architecture/payment-core.md)
- [Sharding, Kafka, and Redis](../architecture/sharding-kafka-redis.md)
