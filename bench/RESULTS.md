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

## AFTER fix (OSIV off) — TODO

Re-run after `up -d --build`. Capture the same sweep + the @load bottleneck sample.
Watch for: (a) higher knee, (b) Hikari pending → ~0, (c) PG idle-in-transaction → ~0,
(d) **no new LazyInitializationException** surfacing as system errors (OSIV-off risk).

| backends | rate | create p99 | confirm p99 | sys_err | dropped | achieved | verdict |
|---|---|---|---|---|---|---|---|
| 2 CPU ea | … | | | | | | |

---

## Notes / next
- Then repeat **with Redis + Kafka (`infra` mode)** for the production-shaped comparison.
- Clean, publishable numbers still require the **wired** M2 link (off-box load gen).
