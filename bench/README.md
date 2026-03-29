# MasonXPay — Performance Benchmark

Load test suite for the MasonXPay payment gateway API, built with [k6](https://k6.io/).

## Prerequisites

- Docker + Docker Compose plugin
- The stack running with resource constraints applied (see below)

## Quick start

```bash
# 1. Start the stack with t3.small resource constraints
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --build

# 2. Run the full benchmark (~14 min)
docker compose -f docker-compose.yml -f docker-compose.bench.yml --profile bench run --rm k6
```

Results are saved to `bench/results/` after each run:

| File | Contents |
|------|----------|
| `report.html` | Self-contained HTML report with per-scenario charts — open in any browser |
| `results.json` | Line-by-line JSON of every request data point — useful for custom analysis |

## Instance-size profiles

The overlay constrains each service to simulate a specific EC2 instance. Pass env vars to switch profiles:

| Profile | Backend | Database | Dashboard | Command prefix |
|---------|---------|----------|-----------|----------------|
| **t3.small** (default) | 1.0 CPU / 1 GB | 0.5 CPU / 512 MB | 0.25 CPU / 256 MB | _(none)_ |
| **t3.medium** | 1.25 CPU / 2 GB | 0.5 CPU / 1 GB | 0.25 CPU / 512 MB | `BENCH_BACKEND_CPU=1.25 BENCH_BACKEND_MEM=2g BENCH_DB_MEM=1g` |
| **t3.large** | 1.75 CPU / 3 GB | 0.75 CPU / 2 GB | 0.25 CPU / 512 MB | `BENCH_BACKEND_CPU=1.75 BENCH_BACKEND_MEM=3g BENCH_DB_CPU=0.75 BENCH_DB_MEM=2g` |

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
| 50% | Create payment intent | API key | `POST /api/v1/payment-intents` |
| 30% | Get payment intent by ID | API key | `GET /api/v1/payment-intents/:id` |
| 20% | List payment intents (paginated) | JWT | `GET /api/v1/merchants/:id/payment-intents` |

The create path exercises: API key lookup → idempotency check → DB write → routing evaluation.
The list path exercises: JWT verification → RBAC check → JPA Specification query + pagination.

## Thresholds

Thresholds are evaluated per-scenario so the spike (stress) scenario does not inflate average-load SLA results.

| Scenario | Metric | Threshold |
|----------|--------|-----------|
| smoke | p(95) create | < 300 ms |
| smoke | p(95) get | < 100 ms |
| smoke | p(95) list | < 200 ms |
| average_load | p(95) create | < 500 ms |
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

**Baseline numbers (t3.small, Docker on Mac):**

| Scenario | Median create | Median get | p(95) create | Throughput |
|----------|--------------|------------|--------------|------------|
| smoke | ~5 ms | ~4 ms | ~15 ms | ~8 req/s |
| average_load | ~5 ms | ~4 ms | ~200 ms | ~30 req/s |
| spike | ~100 ms | ~80 ms | ~1.4 s | ~70 req/s peak |

> Note: Docker Desktop on Mac adds virtualisation overhead. Latency on a real EC2 instance will be lower.

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
