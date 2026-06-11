# Capacity Proof — Results Log

Footprint unless noted: **2 backend nodes + 1 Postgres**, nginx ALB, `postgres_only`
mode (**no Redis/Kafka**), connector simulator log-normal p50 120 / p99 380ms.
Measurements **co-located on the M1** (WiFi link to the M2 contaminates p99 until the
wired link arrives) — treat absolute numbers as directional; the *before/after deltas*
under identical conditions are the meaningful signal.

Gate: `create p99 ≤ 100ms` (pure-platform, no connector) AND system errors ≤ 0.1% AND 0 dropped.

---

## Baseline — BEFORE fix (OSIV on)

### Throughput sweep

| backends | rate | create p99 | confirm p99 | sys_err | dropped | achieved | verdict |
|---|---|---|---|---|---|---|---|
| 1 CPU ea | 100/s | 28ms | 501ms | 0% | 0 | ~95/s | PASS |
| 1 CPU ea | 200/s | **6.79s** | 7.18s | 0% | 686 | 156/s | collapse |
| **2 CPU ea** | 100/s | 44ms | 488ms | 0% | 0 | ~95/s | PASS |
| **2 CPU ea** | 200/s | **470ms** | 993ms | 0% | ~0 | **184/s** | edge |
| 2 CPU ea | 300/s | 11.7s | 12.3s | 9.6% | 2904 | 192/s | collapse |

**Ceiling ≈ 185–190 charges/s** on 2-CPU backends. Doubling backend CPU moved the knee
~150 → ~190/s — only **+30%**, not 2×.

### Bottleneck evidence @ ~220/s (2 CPU backends)

| signal | value | reading |
|---|---|---|
| backend-1 / -2 CPU | **151% / 151%** of 200% cap | **not CPU-bound** |
| postgres CPU | 272% of 400% | ~half, fine |
| nginx CPU | 8% | idle |
| **Hikari active** | **40 / 40** each node | pool **exhausted** |
| **Hikari pending** | **139 + 253** | ~400 threads queued for a connection |
| **PG idle-in-transaction** | **43** | connections held open, no query running |
| PG active queries | 8 | Postgres barely working |
| PG lock waits | 0 | not lock contention |

**Diagnosis:** throughput is **pool-bound**, not CPU-bound. Each in-flight charge holds a
DB connection for its whole request (~420ms incl. the connector sleep), so 80 connections
cap throughput at ~190/s while CPU and Postgres sit under 60%.

**Root cause:** `spring.jpa.open-in-view` was unset → Spring default **true**. Open-Session-
In-View keeps the EntityManager's connection checked out for the entire HTTP request,
including `dispatcher.charge()` (the 120–380ms simulator call). The explicit confirm/
orchestrator code correctly keeps the remote call outside every `txTemplate` block, but
OSIV holds the connection anyway — defeating the "no connection across a remote call" rule.

**Fix applied:** `spring.jpa.open-in-view: false` in `application-capacity.yml`. Expected:
connection held only during the short DB write blocks (~tens of ms), not the connector
call → hold time drops ~20×, so the pool should stop being the wall and the knee should
jump well past 190/s (next wall: backend CPU or PG).

---

## Attempt 1 — OSIV off — REGRESSED, reverted

`spring.jpa.open-in-view: false`, rebuilt. Result was **worse**, not better:

| rate | create p99 | sys_err | achieved | note |
|---|---|---|---|---|
| 200/s | 4.01s | 19.9% | 112/s | (cold JVM, no warmup) |
| 400/s | 2.53s | 4.9% | 194/s | |
| 600/s | 2.28s | 2.5% | 224/s | |

New errors = **`HikariPool-1 Connection is not available, timed out after 10s`**, thrown from
`ApiKeyAuthFilter → findByKeyHash → ShardingSphere DriverDatabaseConnectionManager → Hikari`.
OSIV-off did **not** release connections during the connector call; it added connection-
acquisition churn through ShardingSphere under virtual-thread overload → pool timeouts.
**Reverted.**

## Corrected diagnosis

**Ceiling = pool_size ÷ request_duration.** Little's law: 80 connections ÷ ~0.42s mean
request (dominated by the 120–380ms connector call) ≈ **190/s** — matches exactly. A DB
connection is held for the *whole* request including the connector sleep, and the hold is
entangled with ShardingSphere's connection manager / the EntityManager lifecycle (OSIV-off
alone did not break it). Backend CPU (151%/200%) and PG (272%/400%) both have headroom.

**Levers:**
1. **Brute force (matches the documented scale path):** raise the pool + PG `max_connections`
   so `pool_size` rises → throughput should scale ~linearly until PG connection overhead/CPU
   binds. Beyond that, **PgBouncer** (transaction pooling) is the documented next step.
2. **Proper fix (deeper):** ensure no DB connection is held across `dispatcher.charge()` —
   requires untangling ShardingSphere's connection handling (connection mode / releasing the
   logical connection around the orchestrator). Higher effort; would lift the ceiling ~10×.

## Attempt 2 — pool 80/node (160 total) + PG max_connections=250

Confirms the pool ÷ hold-time model AND that raising the pool is the wrong lever:

| config | max throughput | create p99 @200/s | bottleneck @load |
|---|---|---|---|
| pool 40/node (80) | ~190/s | **470ms** | Hikari pool (PG 272%/400%) |
| pool 80/node (160) | ~273/s | **4.4s** | **PG CPU 412%/400% (pegged)** |

@ pool 80, 400/s: PG **412%** (pegged), Hikari 78–80 active + **~1,800 pending**, PG
**106 idle-in-transaction** / only **10 active queries**. Throughput rose 190→273/s but
latency collapsed — 160 connections thrash PG's 4 cores while 106 sit idle-in-transaction
(still held across the connector call). **Net: worse for the latency SLO.**

## Conclusion

The single-node ceiling on this footprint is **~190/s clean (pool 40) / ~270/s thrashing
(pool 80)**, and it is fundamentally set by **DB connections held across the connector
call** (`idle-in-transaction`). Raising the pool just moves the wall to PG-connection
overhead — it does **not** fix the root cause. Gate-passing (create p99 ≤ 100ms) knee is
~130/s and pool 80 makes it *worse*, not better → **revert to pool 40** for clean operation.

**To actually scale past this, two real levers (neither is "more pool"):**
1. **PgBouncer (transaction pooling)** — the documented scale path. Big app-side pool
   multiplexed onto a few real PG backends; the real backend is released between the short
   txTemplate blocks (during the connector call), so the connector-call hold stops tying up
   a PG backend. Breaks the pool ÷ hold-time ceiling without thrashing PG.
2. **Eliminate the connection hold across `dispatcher.charge()`** (untangle ShardingSphere/
   EM connection lifecycle) — then hold-time drops ~20× and a small pool serves high
   throughput. Higher-effort code change.

---

## Both-topologies comparison (pool 40, 2-CPU backends, co-located)

| rate | postgres-only create p99 | infra create p99 | infra sys_err / dropped |
|---|---|---|---|
| 100/s | 44ms | 46ms | 0% / 0 |
| 150/s | — | 232ms | 0% / 0 |
| 200/s | 470ms (edge) | **314ms** | 0% / 0 |
| 250/s | (collapsed by 300) | **395ms** | 0% / 0 |
| 300/s | collapse (11.7s) | 4.47s | 0.02% / 976 |

| | postgres-only | infra (Redis + Kafka) |
|---|---|---|
| **clean ceiling** | **~190/s** | **~250/s (+30%)** |
| **first bottleneck** | DB connection pool (held across connector call) | **backend CPU** |
| CPU @ saturation | PG-bound when pool raised | backend 203%/200% pegged, PG 328%/400%, Kafka 3%, Redis 10% |

**Finding (counterintuitive):** enabling the production infra **raised** single-node
capacity ~30%. Redis offloads provider-health/routing reads off Postgres and the DB
outbox poller is off (Kafka delivers async), so per-charge DB-connection pressure drops →
the pool stops being the wall → throughput climbs until **backend CPU** binds. Redis/Kafka
are themselves nearly idle (well-provisioned). So on this footprint, the infra layer is a
net win for throughput, not an overhead.

**Gate-passing knee** (create p99 ≤ 100ms) is ~120–130/s for both; infra degrades more
gracefully above it and pushes the clean throughput ceiling from ~190 → ~250/s.

## Headline (postgres-only baseline)
On a **2-vCPU app tier + 1 Postgres** footprint, the platform sustains:
- **~190 charges/s** (postgres-only), bottlenecked by DB connections held across the
  connector call — the documented PgBouncer scale point;
- **~250 charges/s** with the production Redis + Kafka layer, then backend-CPU-bound.

All co-located on one VM (WiFi blocks clean off-box load gen); the postgres-only vs infra
**delta** is trustworthy (identical conditions). Absolute, publishable numbers await the
**wired** M2 link.

## Open levers (not yet done)
- PgBouncer (transaction pooling) or eliminate the connector-call connection hold → would
  lift the postgres-only ceiling well past 190/s.
- More backend CPU / nodes → would lift the infra ceiling past 250/s (PG still has headroom).
- Wired M2 link → clean, publishable absolute numbers.
