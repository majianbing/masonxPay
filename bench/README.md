# MasonXPay — Performance Benchmark

Load test suite for the MasonXPay payment gateway API, built with [k6](https://k6.io/).

## Prerequisites

- Docker + Docker Compose plugin
- The stack running with resource constraints applied (see below)
- For Kafka/Redis benchmarks, start Compose with the optional `infra` profile

## Quick start

```bash
# 1. Start the default Postgres-only stack with t3.small resource constraints
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --build

# 2. Run the full benchmark (~14 min)
docker compose -f docker-compose.yml -f docker-compose.bench.yml --profile bench run --rm k6
```

The default benchmark path matches the merge-safe live-demo runtime: Postgres is authoritative, Kafka and Redis are disabled, and webhook delivery uses the DB-backed outbox poller.
The bench overlay enables the Mason Simulator provider (`PROVIDER_SIMULATOR_ENABLED=true`) so k6 can test confirm/refund through the normal provider-account, routing, dispatcher, state-machine, and outbox path without calling real PSP sandboxes.

Set `SIMULATOR_SUCCESS_RATE_PERCENT=95` or another 0-100 value when you want synthetic PSP declines during a benchmark run:

```bash
SIMULATOR_SUCCESS_RATE_PERCENT=95 \
  docker compose -f docker-compose.yml -f docker-compose.bench.yml --profile bench run --rm k6
```

To benchmark the optional Kafka/Redis path:

```bash
# Start Kafka/Redis plus the constrained app stack
KAFKA_OUTBOX_ENABLED=true KAFKA_WEBHOOK_CONSUMER_ENABLED=true \
KAFKA_PAYMENT_PROJECTION_ENABLED=true WEBHOOK_OUTBOX_POLLER_ENABLED=false \
REDIS_HOT_PATH_ENABLED=true REDIS_RATE_LIMIT_ENABLED=true \
REDIS_IDEMPOTENCY_CACHE_ENABLED=true REDIS_PROVIDER_HEALTH_CACHE_ENABLED=true \
docker compose -f docker-compose.yml -f docker-compose.bench.yml --profile infra up -d --build

# Run k6 with a runtime label so results can be compared
RUN_MODE=infra docker compose -f docker-compose.yml -f docker-compose.bench.yml \
  --profile bench --profile infra run --rm k6
```

Kafka/Redis panels only show activity when the infra path is enabled and the selected Grafana time range includes fresh outbox traffic.

Results are saved to `bench/results/` after each run:

| File | Contents |
|------|----------|
| `report.html` | Self-contained HTML report with per-scenario charts — open in any browser |
| `results.json` | Line-by-line JSON of every request data point — useful for custom analysis |

## Grafana dashboards

Open Grafana at `http://localhost:3001` while the stack is running.

| Dashboard | Purpose |
|-----------|---------|
| `MasonXPay — Payments` | Home/payment operations view: volume, success rate, provider latency, outbox, Kafka, projection, and connector health |
| `MasonXPay — Bench` | k6 run view: VUs, request rate, error rate, operation latency, and status-code mix |

Important panel behavior:

- `Payment Volume` uses total `payment_intent_confirmed_total` counters. It is stable for manual tests and does not disappear after a short burst.
- `Overall Success Rate` uses confirmed intent counters. It changes only after confirm attempts reach a terminal status.
- `Charge Latency p50 / p95` uses backend histogram buckets from `payment_charge_latency_seconds_bucket`.
- `Kafka Outbox Publish Rate` is zero unless `KAFKA_OUTBOX_ENABLED=true` and lifecycle events are published during the selected time range.
- `Kafka Broker Throughput` is zero when no Kafka messages move through the broker. For local sparse traffic this is normal.
- `Connector Success Rate` is rolling connector health from recent payment request rows, not the simulator's configured target rate.

If Grafana looks stale after dashboard edits, restart Grafana or wait for provisioning reload:

```bash
docker compose restart grafana
```

If backend metric shape changed, rebuild/restart the backend:

```bash
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --build backend grafana prometheus
```

## Instance-size profiles

The overlay constrains each service to simulate a specific EC2 instance. Pass env vars to switch profiles:

| Profile | Backend | Database | Dashboard | Command prefix |
|---------|---------|----------|-----------|----------------|
| **t3.small** (default) | 1.0 CPU / 1 GB | 0.5 CPU / 512 MB | 0.25 CPU / 256 MB | _(none)_ |
| **t3.medium** | 1.25 CPU / 2 GB | 0.5 CPU / 1 GB | 0.25 CPU / 512 MB | `BENCH_BACKEND_CPU=1.25 BENCH_BACKEND_MEM=2g BENCH_DB_MEM=1g` |
| **t3.large** | 1.75 CPU / 3 GB | 0.75 CPU / 2 GB | 0.25 CPU / 512 MB | `BENCH_BACKEND_CPU=1.75 BENCH_BACKEND_MEM=3g BENCH_DB_CPU=0.75 BENCH_DB_MEM=2g` |

When `--profile infra` is enabled, the overlay also constrains Kafka and Redis:

| Service | Default limit | Override |
|---------|---------------|----------|
| Kafka | 0.75 CPU / 1 GB | `BENCH_KAFKA_CPU`, `BENCH_KAFKA_MEM` |
| Redis | 0.25 CPU / 256 MB | `BENCH_REDIS_CPU`, `BENCH_REDIS_MEM` |

Example — run against a t3.medium-sized stack:

```bash
BENCH_BACKEND_CPU=1.25 BENCH_BACKEND_MEM=2g BENCH_DB_MEM=1g \
  docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --build

docker compose -f docker-compose.yml -f docker-compose.bench.yml --profile bench run --rm k6
```

## Test scenarios

All three scenarios run sequentially in a single `k6 run` (~14 min total):

| Scenario | VUs | Duration | Purpose |
|----------|-----|----------|---------|
| `smoke` | 1 | 1 min | Sanity check — verify all endpoints return correct status codes |
| `average_load` | 0 → 20 | 8 min | Sustained load modelling ~100 k tx/day; production SLA validation |
| `spike` | 0 → 100 | 4 min | Stress ceiling — how the system behaves at maximum concurrency |

## Workload mix

Each VU iteration randomly selects one operation:

| Weight | Operation | Auth | Endpoint |
|--------|-----------|------|----------|
| 35% | Create payment intent | API key | `POST /api/v1/payment-intents` |
| 10% | Create + confirm | API key | `POST /api/v1/payment-intents`, `POST /api/v1/payment-intents/:id/confirm` |
| 5% | Create + confirm + refund | API key | `POST /api/v1/payment-intents/:id/refunds` |
| 15% | Replay idempotency key | API key | `POST /api/v1/payment-intents` |
| 20% | Get payment intent by ID | API key | `GET /api/v1/payment-intents/:id` |
| 15% | List payment intents (paginated) | JWT | `GET /api/v1/merchants/:id/payment-intents` |

The create path exercises: API key lookup → idempotency reservation → sharded payment intent write.
The confirm/refund paths use a TEST-mode `SIMULATOR` connector account created during setup. They exercise routing, provider credential loading, retry orchestration, payment request writes, state transition locks, refund available-amount checks, and transactional outbox writes.
The simulator connector stores its success rate in provider config, so benchmark traffic can model a degraded PSP without changing application code. For example, `SIMULATOR_SUCCESS_RATE_PERCENT=80` creates a connector whose simulator operations succeed roughly 80% of the time.
The idempotency replay path exercises the sharded Postgres idempotency registry and, when Redis is enabled, the Redis route cache.
The list path exercises: JWT verification → RBAC check → Kafka-fed read model when projection is enabled, or authoritative-table fallback when Kafka projection is disabled.

New create/confirm/refund operations generate idempotency keys with `runId + scenario + operation + VU + iteration`. This keeps `smoke`, `average_load`, and `spike` from accidentally replaying each other's payment intents. The dedicated idempotency replay path intentionally reuses setup-created seed keys.

## Thresholds

Thresholds are evaluated per-scenario so the spike (stress) scenario does not inflate average-load SLA results.

| Scenario | Metric | Threshold |
|----------|--------|-----------|
| smoke | p(95) create | < 300 ms |
| smoke | p(95) confirm | < 600 ms |
| smoke | p(95) refund | < 700 ms |
| smoke | p(95) idempotency replay | < 150 ms |
| smoke | p(95) get | < 100 ms |
| smoke | p(95) list | < 200 ms |
| average_load | p(95) create | < 500 ms |
| average_load | p(95) confirm | < 800 ms |
| average_load | p(95) refund | < 800 ms |
| average_load | p(95) idempotency replay | < 250 ms |
| average_load | p(95) get | < 200 ms |
| average_load | p(95) list | < 300 ms |
| spike | p(95) all ops | < 3000 ms |
| all | error rate | < 1% |

Exit code `0` = all thresholds met. Exit code `99` = one or more thresholds exceeded.

## Interpreting results

**What to look for in the summary table:**

- `checks_succeeded` should be 100% — any failures mean incorrect API responses
- `http_req_failed` should be 0% — non-2xx responses indicate the system is shedding load
- Compare `med` vs `p(95)` — a large gap means high tail latency under concurrency
- `max` values during the spike scenario show worst-case queuing time
- Compare `pi_idempotency_ms` between `RUN_MODE=postgres_only` and `RUN_MODE=infra` to see whether Redis improves duplicate-create fast paths
- Compare list latency between modes: Postgres-only lists from authoritative tables; infra mode can list from the Kafka-fed read model

**Baseline numbers (t3.small, Docker on Mac):**

| Scenario | Median create | Median get | p(95) create | Throughput |
|----------|--------------|------------|--------------|------------|
| smoke | ~5 ms | ~4 ms | ~15 ms | ~8 req/s |
| average_load | ~5 ms | ~4 ms | ~200 ms | ~30 req/s |
| spike | ~100 ms | ~80 ms | ~1.4 s | ~70 req/s peak |

> Note: Docker Desktop on Mac adds virtualisation overhead. Latency on a real EC2 instance will be lower.

These baseline numbers were captured before the high-throughput sharding/Kafka/Redis work. Treat them as historical reference until a fresh H7 benchmark run is recorded.

**Graceful degradation under spike:** The system slows down significantly at 100 VUs on t3.small but maintains 0% errors — requests queue and complete rather than failing. This is the expected behaviour for the target scale (≤ 100 k tx/day per merchant).

## Files

```
bench/
  k6/
    script.js       k6 test script (setup + three scenarios + thresholds)
  results/
    report.html     generated after each run — charts per scenario
    results.json    raw time-series JSON for custom post-processing
  README.md         this file
docker-compose.bench.yml   Compose overlay — resource limits + k6 service
```
