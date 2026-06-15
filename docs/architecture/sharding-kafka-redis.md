# Sharding, Kafka, And Redis

The high-throughput profile adds sharding, Kafka, Redis, and projections while preserving Postgres as the financial source of truth.

## Ownership

| Component | Owns | Must Not Own |
|---|---|---|
| Postgres shards | payment state, idempotency, provider references, outbox, ledger | external async fan-out |
| Redis | hot-path cache, rate limits, soft coordination, provider health hints | financial correctness |
| Kafka | committed event propagation to workers | payment-state authority |
| Read projections | dashboard/search views and analytics support | authoritative state checks |

## Sharding Strategy

High-volume payment tables are logically sharded. The shard key is a stable payment identifier, not `merchant_id`, so large merchants, flash sales, and retry storms do not create hot tenant shards.

Normal correctness-critical operations should route directly to one shard by payment ID. Webhooks that arrive with provider references must resolve the provider reference to a payment ID, then route to the owning shard.

Dashboard queries should not fan out across payment shards. They should read from Kafka-fed projection tables or future search indexes.

## Redis Rules

Redis is allowed for:

- idempotency hot-cache lookups after the database source of truth exists
- merchant/API rate limiting
- provider health and routing hints
- short-lived checkout/session state
- duplicate suppression for non-critical background work

Redis is forbidden for:

- preventing double capture
- preventing double refund
- enforcing payment state transitions
- making ledger writes safe
- replacing idempotency table constraints

The database must remain the final gate for money-state correctness.

## Kafka Rules

Kafka is fed by the transactional outbox after payment state commits. Consumers must be idempotent because delivery can repeat. Kafka workers should process webhook fan-out, projections, reconciliation, notifications, and operational read models without becoming part of synchronous payment correctness.

## No-Loss Delivery

The no-loss anchor is the transactional outbox, not Kafka. An event is durable in Postgres before it is published, so a lost Kafka message is simply re-published from the outbox. The producer, broker, and consumer config below protects the one hop the outbox cannot see.

| Coordinator | Setting | Value | Why |
|---|---|---|---|
| Producer | `acks` | `all` | wait for all in-sync replicas, not just the leader |
| Producer | `enable.idempotence` | `true` | retries cannot duplicate or reorder |
| Broker | `replication.factor` | `3` | survive one broker loss with quorum intact |
| Broker | `min.insync.replicas` | `2` | a write needs two durable copies or it fails (back-pressures instead of silently dropping) |
| Broker | `unclean.leader.election.enable` | `false` | never elect an out-of-sync replica leader — the classic silent committed-message loss |
| Consumer | `enable.auto.commit` | `false` | commit the offset only after processing succeeds |

Two ordering rules carry the guarantee:

- Producer (outbox relay): publish, await the broker ack, and only then mark the outbox row published. Never mark published before the ack.
- Consumer: process the side effect, then commit the offset. Never commit first.

This yields at-least-once delivery with idempotent effects: no event is lost, and unavoidable duplicates are collapsed by the DB idempotency constraints. It is not Kafka-side exactly-once, and it does not move financial truth into Kafka — Postgres remains the final gate.

## Single-Table Routing

Every new non-sharded table added through Flyway must be registered in `DataSourceConfig.singleTables()` so ShardingSphere routes it directly to `ds_0`. Missing registration can cause ShardingSphere to intercept Postgres-specific SQL such as `FOR UPDATE SKIP LOCKED` or JSON operators.

## References

- [Payment core](payment-core.md)
- [High-throughput payment core plan](../planning/high-throughput-payment-core-plan.md)
