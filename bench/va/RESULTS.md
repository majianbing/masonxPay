# VA Service Capacity Bench Results

## Stack

| Component           | CPUs | Memory | Note                          |
|---------------------|------|--------|-------------------------------|
| virtual-account-service | 2  | 2 GB  | Single node; bench endpoints active |
| va-bench-postgres   | 4    | 4 GB   | `max_connections=200`, `shared_buffers=256MB` |

## Why VA is different from the gateway bench

| Dimension           | Gateway           | VA Service           |
|---------------------|-------------------|----------------------|
| External call per TX | Yes (~120–380ms) | None                 |
| DB hold time         | ~420ms           | ~3–8ms (TX only)     |
| Connection pool risk | HIGH (ceiling at ~190 TPS) | LOW        |
| Extra CPU per TX     | None             | HMAC-SHA256 × 2 entries |
| Lock pattern         | None (append-only) | SELECT FOR UPDATE on 2 accounts |
| Expected bottleneck  | Pool / connector hold | PG WAL / I/O / lock serialization |

Little's law comparison:
- Gateway: 80 conns ÷ 0.42s hold ≈ **190 TPS ceiling**
- VA:      40 conns ÷ 0.005s hold ≈ **8,000 TPS theoretical max**

## Correctness invariants (VA/LC-specific gate)

After every run except warmup, teardown verifies both accounts in the hotspot pair
or both accounts in the first 5 pairs:

1. **Balance OK** — `ledger_account.balance` == last entry's `balance_after`
2. **Balance Sum OK** — account balance equals the signed sum of all posted entries,
   using the account's normal balance
3. **Seq OK** — `entry_seq` is gapless: 1, 2, 3, … N with no duplicates
4. **Chain OK** — HMAC chain is unbroken: each entry's signature recomputes correctly
5. **Journal OK** — every ledger entry has a persisted `va_transaction` header,
   every transaction touching the verified account is net-zero, and tenant-account
   journal headers carry the account's merchant/org/mode scope
6. **Trial Balance OK** — the mode/asset trial balance remains balanced
7. **Duplicate Event OK** — setup posts one duplicate event through `postIfNew`;
   the first delivery posts exactly one journal and two lines, the duplicate is skipped

A correctness failure means a concurrency bug regardless of the TPS number.
The k6 script now calls `fail()` when any invariant is violated, so CI or shell
exit status can treat correctness failures as failed runs.

## Scenarios

| Scenario      | Command                                                  | Purpose                     |
|---------------|----------------------------------------------------------|-----------------------------|
| warmup        | `k6 run k6/va-capacity.js -e SCENARIO=warmup`           | JVM warm-up — discard       |
| ramp          | `k6 run k6/va-capacity.js -e SCENARIO=ramp`             | Find throughput knee        |
| soak          | `k6 run k6/va-capacity.js -e SCENARIO=soak -e TARGET_RATE=150` | 150 TPS headline  |
| spike         | `k6 run k6/va-capacity.js -e SCENARIO=spike -e PEAK_RATE=800` | Burst tolerance    |
| correctness   | `k6 run k6/va-capacity.js -e SCENARIO=correctness`      | Invariant check (hotspot)   |

### Contention modes (CONTENTION env)

| Mode    | VU→pair mapping         | Use                                      |
|---------|-------------------------|------------------------------------------|
| spread  | VU n → pair (n % N)     | Baseline throughput, minimal lock conflict |
| hotspot | all VUs → pair 0        | SELECT FOR UPDATE serialization ceiling  |

Set `LC_CHECKS=false` only when you intentionally want a pure latency run
without journal/trial-balance/duplicate-event checks. The default is `true`.
Set `INTERNAL_TOKEN` if the bench service uses a non-default `X-Internal-Token`;
the local default is `internal-dev-secret`.

## How to run

```bash
# 1. Build image + start stack
cd bench/va/
docker compose up --build

# 2. Warmup (discard results)
k6 run k6/va-capacity.js -e BASE_URL=http://localhost:8087 -e SCENARIO=warmup

# 3. Ramp — find the knee
k6 run k6/va-capacity.js -e BASE_URL=http://localhost:8087 -e SCENARIO=ramp -e RAMP_TO=2000

# 4. Soak at 150 TPS (the headline)
k6 run k6/va-capacity.js -e BASE_URL=http://localhost:8087 -e SCENARIO=soak -e TARGET_RATE=350 -e DURATION=6h

# 5. Correctness check (50 VUs → pair 0, 5000 iterations, teardown verifies)
k6 run k6/va-capacity.js -e BASE_URL=http://localhost:8087 -e SCENARIO=correctness

# 6. Hotspot throughput (all VUs → 1 account, find SELECT FOR UPDATE ceiling)
k6 run k6/va-capacity.js -e BASE_URL=http://localhost:8087 -e SCENARIO=soak -e CONTENTION=hotspot -e DURATION=5m

# 7. Wipe bench data and start fresh
docker compose down -v && docker compose up --build
```

## Gate (soak, spread contention)

- `va_post_ms p99 < 50ms`  — tighter than gateway's 100ms; justified (no connector call)
- `va_sys_errors rate < 0.001`  — less than 0.1% errors
- Correctness teardown must print `ALL PASS`

## Results log

<!-- Fill in after each run. Include: date, SCENARIO, CONTENTION, TARGET_RATE, p50/p99, gate pass/fail, correctness result, bottleneck notes. -->

| Date | Scenario | Contention | Rate | p50 | p99 | Gate | Correctness | Notes |
|------|----------|------------|------|-----|-----|------|-------------|-------|
| 2026-06-20 | soak  | spread | 150/s  | 2.8ms  | 9.1ms  | PASS (p99✓, errors✓) | FAIL chain brokenChain=1 | HMAC scale bug — see fix below |
| 2026-06-20 | spike | spread | 800/s  | 1.5ms  | 10.5ms | PASS (errors✓) | FAIL chain brokenChain=1 | same root cause |

### Bug: HMAC chain always breaks at seq=1 (fixed 2026-06-20)

**Symptom:** All verified accounts report `chain=false brokenChain=1` despite `balance=true seq=true`.
Throughput and latency gates pass; only the chain invariant fails.

**Root cause:** `SignatureInput.canonical()` used `BigDecimal.toPlainString()` directly, which is
scale-sensitive. Two mismatches:

1. `amount` at posting comes from the request body as `BigDecimal("10.00")` (scale 2 → `"10.00"`);
   at verify it is read from `NUMERIC(38,8)` → `BigDecimal("10.00000000")` (scale 8 → `"10.00000000"`).
2. The old canonical input also included `frozenBalance`; that field has since been retired
   and hold balances are represented by separate ledger accounts.

The scale mismatch diverged in the canonical string, so every single entry's HMAC differed between post and
verify — chain fails at the first entry (seq=1) on every account.

**Fix:** `SignatureInput.canonical()` now calls `.stripTrailingZeros().toPlainString()` on all
three `BigDecimal` fields. `"10.00"`, `"10.00000000"` → `"10"`. `"0"`, `"0.00000000"` → `"0"`.
Regression test added to `BalanceSignatureServiceTest#scale_variants_produce_same_signature`.

**Action required before re-running:** wipe bench DB (`docker compose down -v && docker compose up --build`)
because existing entries were signed with the old buggy canonical strings.
