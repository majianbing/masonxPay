# Engineering Anti-Patterns

Avoid these shortcuts unless a concrete design change has been accepted and documented.

## Infrastructure

- Do not add Redis to an authority path. Redis is hot-path cache and soft coordination only.
- Do not make Kafka, RocketMQ, or another broker the source of payment correctness.
- Do not use read projections or search indexes for payment state checks.
- Do not add Caffeine L1 cache for routing rules or API keys without measured need and a clear invalidation plan.

## Database

- Do not use `ddl-auto: create` or `ddl-auto: update`.
- Do not add tenant-owned tables without `merchant_id`.
- Do not add mode-scoped resources without TEST/LIVE filtering.
- Do not forget `DataSourceConfig.singleTables()` for new non-sharded Flyway tables.

## Auth And Security

- Do not share admin and merchant user tables.
- Do not embed merchant roles in JWT claims as the source of permission truth.
- Do not weaken webhook signature verification for local/dev convenience.
- Do not log raw provider payloads, webhook signatures, secrets, tokens, PAN, CVV, or private keys.

## Payment State

- Do not put `@Transactional` on methods that include remote provider calls.
- Do not use random UUIDs as provider idempotency keys for money-moving operations.
- Do not let controllers directly mutate financial state.
- Do not use Redis locks to prevent double capture, double refund, or unsafe state transitions.

## Frontend And SDK

- Do not put provider-specific checkout UI in dashboard pages.
- Do not bypass `sdk/browser/src/index.ts` for browser payment UI.
- Do not duplicate SDK loading, skeleton, or provider lifecycle states in hosted checkout pages.
