# MasonXPay — Capacity Proof

A **defensible, reproducible** capacity proof for the charge-intent ("core") API.

## What this proves (and what it doesn't)

This is **not** a vanity throughput number. It answers one question:

> *Does a single, resource-capped node-set clear the target with headroom, and
> which resource saturates first?*

- **Measured (trustworthy):** the single-node-set ceiling on defined hardware, and
  the *first resource to saturate* (Postgres CPU / connections / lock-wait, Hikari
  pool-wait, JVM GC, backend CPU, nginx).
- **Inferred (argued, not measured):** "production does N TPS." That follows from
  the bottleneck analysis, not from the laptop number itself.

Read the absolute numbers as a **lower bound** captured under deliberate constraints.

## Target & gate (SLO)

| | |
|---|---|
| **Unit** | one *charge* = `POST /payment-intents` (create) + `POST /payment-intents/{id}/confirm` (charge). Driven open-model at a fixed arrival rate; the create is part of the charge cost. |
| **Average** | 100 charges/s sustained |
| **Peak** | 1000 charges/s |
| **Gate** | **confirm p99 ≤ 500ms** AND **system-error rate ≤ 0.1%**, held over a multi-hour soak |
| **Capacity** | max sustained charge-rate that holds the gate — always reported *with the saturating resource* |

**Two latencies reported.** k6 measures the **merchant view** (end-to-end HTTP). The
**platform-added latency** (end-to-end − injected connector think-time) is read from
backend metrics (`payment.charge.latency`), isolating our system from the mock's latency.

**Injected connector faults (~0.7%) are stimulus, not failures.** The system must
absorb them as graceful declines (a `FAILED` payment), never a 5xx. k6 tracks them
separately (`cap_declines`) and does **not** count them against the error budget.

## Rig

Two machines, **wired** (Thunderbolt bridge / gigabit Ethernet — *not* WiFi, whose
jitter would contaminate the p99 we gate on). Record idle RTT and disclose it.

```
  Air M2 24GB  ──wired──>  M1 32GB
  (k6, off-box)            (Docker SUT)
                           nginx :8088  ──>  backend  (node 1)  1c / 2g
                                         └─>  backend-2 (node 2) 1c / 2g
                                              │
                                              └─> postgres-capacity  2c / 4g  (isolated DB+volume)
                           [infra mode adds]  redis 0.5c/512m · kafka 1c/1g
                           prometheus · grafana · postgres-exporter
```

The load generator lives **off-box** so it never steals CPU from the system under
test (the biggest contamination source in single-box load tests).

## Connector latency model (synthetic, published)

In-process simulator (`MasonSimulatorPaymentProviderService`), **log-normal** fitted
to percentiles so the realistic right-skewed tail is present (the tail is what fills
connection/thread pools):

| p50 | p99 | hard cap | injected faults |
|-----|-----|----------|-----------------|
| 120ms | 380ms | 5000ms | ~0.7% (timeout/5xx) |

Fits **under** the 500ms end-to-end SLO with ~100ms of p99 headroom, so the test
measures whether the platform *preserves* that headroom under load. All knobs live in
`backend/src/main/resources/application-capacity.yml`.

> The distribution (not the per-request sequence) is the reproducible artifact —
> under concurrent virtual threads, per-call determinism is meaningless.

## Why open-model

k6 uses `constant-arrival-rate` / `ramping-arrival-rate`: a fixed **offered load**
independent of response time. If the system slows, load does **not** back off, so the
queue and the true ceiling become visible. Closed-model VUs (the older `script.js`)
hide saturation via **coordinated omission** and must not be used for a capacity claim.

## Data isolation & cleanup

- Backend runs `SPRING_PROFILES_ACTIVE=capacity` against a **dedicated** `postgres-capacity`
  (own database `paygateway_capacity`, own volume `postgres_capacity_data`, host port 5433).
- Run the whole stack under a **dedicated compose project** `masonxpay-cap` so cleanup
  cannot touch local/dev volumes:

```bash
# wipe ONLY the capacity store → next run starts clean (Flyway re-migrates)
docker compose -p masonxpay-cap \
  -f docker-compose.yml -f docker-compose.capacity.yml down -v
```

Stop the normal dev stack first — the capacity stack publishes host ports 9090 and 3001
(Prometheus/Grafana), plus 8088 (ALB) and 5433 (capacity DB).

## Runbook (step-by-step)

The SUT runs on the **M1**; k6 runs **natively on the Air M2** over the wired link.

### Phase 0 — one-time setup

1. **Connect both Macs by wire.** Either a direct Thunderbolt/USB-C cable, or (simpler)
   both plugged into the same **gigabit switch/router via Ethernet** (USB-C→Ethernet
   adapters). On a shared router they share your LAN, e.g. `192.168.50.x`.
   **Use Ethernet, not WiFi** — WiFi jitter lands directly in the p99 you gate on.
   Find the M1's address on its *wired* interface:
   ```bash
   # try each until one returns a 192.168.50.x address; note which iface is Ethernet
   ipconfig getifaddr en0 ; ipconfig getifaddr en6 ; ipconfig getifaddr en7
   networksetup -listallhardwareports   # confirm that iface is "Ethernet", not "Wi-Fi"
   ```
   Substitute that address for `192.168.50.31` (M1) in every command below.
2. **Record idle RTT** (disclose it next to p99). From the M2: `ping -c 20 192.168.50.31`.
   If you see ms-level jitter / variance here, you're on WiFi — switch to wire before
   trusting any p99.
3. **On the M2:** `brew install k6`, then clone this repo / `perf/capacity-proof` branch
   (k6 runs `bench/k6/capacity.js` locally there).
4. **On the M1:** stop other compose stacks that publish `8088/9090/3001/5433`
   (e.g. `docker compose -p masonxpay stop`), and allow incoming connections if the
   macOS firewall is on.

### Phase 1 — start the SUT (M1)

**Minimal baseline (Postgres-only):**
```bash
cd ~/Desktop/masonxPay

docker compose -p masonxpay stop

docker compose -p masonxpay-cap \
  -f docker-compose.yml -f docker-compose.capacity.yml up -d --build \
  nginx backend backend-2 postgres-capacity prometheus grafana postgres-exporter redis kafka

# Important after any backend rebuild/recreate: force nginx to recreate too.
# Backend containers receive new Docker IPs; an old nginx process may keep stale
# upstream state and send most traffic to one surviving backend. This is visible
# as one backend CPU >100% while the other is nearly idle.
docker compose -p masonxpay-cap \
  -f docker-compose.yml -f docker-compose.capacity.yml \
  up -d --force-recreate nginx

docker compose -p masonxpay-cap \
  -f docker-compose.yml -f docker-compose.capacity.yml \
  exec nginx nginx -T

docker compose -p masonxpay-cap -f docker-compose.yml -f docker-compose.capacity.yml ps
```
The capacity stack is **collision-free** — node-1 doesn't publish 8080 and the base
`postgres` is dropped (`!override`/`!reset`), so it stands up alongside a running dev
stack (only 8088/5433/9090/3001 are published). `dashboard` is intentionally not started.

After the first warmup starts, check that both backend nodes receive traffic. If CPU is
lopsided, inspect nginx logs for stale upstream IPs:

```bash
docker compose -p masonxpay-cap \
  -f docker-compose.yml -f docker-compose.capacity.yml \
  logs --since=2m nginx | rg 'connect\\(\\) failed|upstream server temporarily disabled'
```

> **No dashboard needed.** k6's `setup()` provisions everything over the API before load
> starts — it registers the synthetic merchants, creates their API keys, and adds the
> SIMULATOR connector. The Next.js dashboard is a human UI only and is excluded from the SUT.

### Phase 2 — drive load (Air M2)

```bash
export BASE_URL=http://192.168.50.31:8088                    # nginx ALB on the M1 (your M1 IP)
export RUN_MODE=postgres_only                                # or: infra
export K6_PROMETHEUS_RW_SERVER_URL=http://192.168.50.31:9090/api/v1/write
export K6_PROMETHEUS_RW_TREND_STATS="p(50),p(95),p(99),p(99.9),avg,max"

# NOTE: the -o flag is passed INLINE. zsh does not word-split an unquoted `$RW`
# variable (bash does), so `k6 run $RW` would pass it as one bad argument.
SCENARIO=warmup k6 run -o experimental-prometheus-rw bench/k6/capacity.js                       # a) DISCARD (JVM/JIT)
SCENARIO=ramp   RAMP_TO=1200 k6 run -o experimental-prometheus-rw bench/k6/capacity.js          # b) find the knee
SCENARIO=soak   TARGET_RATE=100 DURATION=2h k6 run -o experimental-prometheus-rw bench/k6/capacity.js  # c) HEADLINE
SCENARIO=spike  PEAK_RATE=1000 DURATION=5m k6 run -o experimental-prometheus-rw bench/k6/capacity.js   # d) peak
```
**Finding capacity:** in the ramp, the **knee** is the arrival rate where `cap_confirm_ms`
p99 first crosses **500ms** or `cap_system_errors` lifts off zero (watch the k6 line or the
Grafana charge-latency panel). Set the soak `TARGET_RATE` to ~80% of the knee to prove
sustained headroom, or to 100 for the stated target.

### Phase 3 — record (per run)

Gate: **confirm p99 ≤ 500ms AND `cap_system_errors` ≤ 0.1%**, held through the soak.

- **k6 summary** (merchant view): `cap_confirm_ms` p99 (gated), `cap_system_errors`,
  `cap_declines` (injected faults — *not* failures), `cap_rate_limited_429`.
- **Grafana** `http://localhost:3001` — *what saturated first*: PG CPU / connections /
  lock-wait, Hikari pool-wait, JVM GC, per-node backend CPU, nginx.
- Write down: sustained rate held · confirm p99 · system-error rate · **first resource to
  saturate** (that last item is the real finding).

### Phase 4 — production-shaped (infra), then compare

```bash
docker compose -p masonxpay-cap -f docker-compose.yml -f docker-compose.capacity.yml down
KAFKA_OUTBOX_ENABLED=true KAFKA_WEBHOOK_CONSUMER_ENABLED=true KAFKA_PAYMENT_PROJECTION_ENABLED=true \
WEBHOOK_OUTBOX_POLLER_ENABLED=false REDIS_HOT_PATH_ENABLED=true REDIS_RATE_LIMIT_ENABLED=true \
REDIS_IDEMPOTENCY_CACHE_ENABLED=true REDIS_PROVIDER_HEALTH_CACHE_ENABLED=true \
docker compose -p masonxpay-cap \
  -f docker-compose.yml -f docker-compose.capacity.yml --profile infra up -d --build \
  nginx backend backend-2 postgres-capacity redis kafka prometheus grafana postgres-exporter
```
Re-run Phase 2 with `RUN_MODE=infra`. Compare throughput, p99, and the cost each layer adds.

### Phase 5 — graceful-shedding run (optional)

Restore Sentinel system-protection (no file edit — env knobs) and push past the knee to
show the system *sheds* rather than collapses:
```bash
CAPACITY_SENTINEL_HIGHEST_SYSTEM_LOAD=3.0 CAPACITY_SENTINEL_HIGHEST_CPU_USAGE=0.85 \
docker compose -p masonxpay-cap \
  -f docker-compose.yml -f docker-compose.capacity.yml up -d \
  nginx backend backend-2 postgres-capacity prometheus grafana postgres-exporter
# then drive SCENARIO=spike at a rate above the measured knee
```

### Cleanup (safe — touches only capacity data)

```bash
docker compose -p masonxpay-cap -f docker-compose.yml -f docker-compose.capacity.yml down -v
docker compose -p masonxpay up -d      # bring the dev stack back
```

## Disclosed caveats (read before citing numbers)

1. **Docker-on-Mac virtualization.** Postgres WAL `fsync` goes through a VM; this may be
   an artificial ceiling. We keep `synchronous_commit=on` (payment-grade durability) —
   we do **not** weaken durability to inflate throughput.
2. **Single node-set.** One Postgres, two app nodes, one host: contention bugs are
   visible; multi-node/distributed failure modes are **not**.
3. **Load generator co-residence avoided** via the second machine, but both Macs and the
   wired link are disclosed; idle RTT is recorded alongside p99.
4. **Sentinel system-protection disabled** for the raw-ceiling run (so it doesn't shed at
   the ceiling we measure). The Phase 5 run restores `highest-cpu-usage: 0.85` /
   `highest-system-load: 3.0` via the `CAPACITY_SENTINEL_*` env knobs to demonstrate
   graceful shedding.

## Files

| Path | Role |
|------|------|
| `docker-compose.capacity.yml` | overlay: nginx + 2 backend nodes + isolated Postgres + limits |
| `bench/nginx/nginx.conf` | thin ALB (least-conn, access-log off) |
| `backend/src/main/resources/application-capacity.yml` | the capacity Spring profile (every knob) |
| `bench/k6/capacity.js` | open-model charge load script |
| `bench/CAPACITY.md` | this document |
