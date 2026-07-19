# Card Authorization Bench

Verifies the card authorization decision core (`CardAuthorizationService`) end-to-end
against a real Postgres ledger, through the rail-sim issuer adapter endpoint
`POST /internal/issuer/authorize`.

The invariant under test is the approve-without-hold fix:

> **APPROVED ⟹ exactly one hold journal** — no matter how many times the same
> `authorizationId` is delivered, sequentially or concurrently.

It reuses the VA bench stack (`bench/va/docker-compose.yml`) — no rail-service or
rail-simulator needed, because the bench drives the adapter endpoint directly with
bench-minted `authorizationId`s (in production that ID is minted by the issuer side).

## Prerequisites

- Docker + docker compose
- [k6](https://k6.io/docs/get-started/installation/)

## Start the stack

```bash
cd bench/va/
docker compose up --build
# wait for: virtual-account-service healthy on localhost:8087
```

The bench stack runs VA with `va.bench.enabled=true`, which exposes the
`/internal/bench/*` endpoints used for setup and ledger verification.

## Scenarios

Each run does its own setup: creates a merchant WALLET, funds it via a bench
counter-account, creates a VCC, and loads `FUND_AMOUNT` onto the card.

### 1. smoke — functional walk (run this after any auth-path change)

```bash
cd bench/va/card/
k6 run k6/card-auth.js
```

One iteration asserting, in order:

| Step | Expectation |
|---|---|
| First delivery of `authorizationId` A1 | `APPROVED`, card balance −amount, frozen +amount |
| **Duplicate delivery of A1** | **`APPROVED` replayed, balances unchanged — no second hold** |
| Over-balance auth A2 | `DECLINED` / `INSUFFICIENT_FUNDS`, nothing moves |
| Duplicate delivery of A2 | Same stored decline replayed |
| Unknown `cardTokenId` | `DECLINED` / `CARD_NOT_FOUND` |
| Missing `authorizationId` | HTTP 400 (contract) |

Teardown additionally proves conservation (`balance + frozen == FUND_AMOUNT`) and
wallet ledger invariants (balance projection, gapless `entry_seq`, HMAC chain,
balanced journals) via `/internal/bench/verify`.

### 2. replay-storm — concurrency proof of idempotent replay

```bash
k6 run k6/card-auth.js -e SCENARIO=replay-storm -e VUS=30 -e STORM_ITERS=500
```

30 concurrent VUs deliver the **same** `authorizationId` 500 times. Every response
must be `APPROVED`; teardown fails the run unless **exactly one** hold of
`AUTH_AMOUNT` moved. This exercises the check → lock → re-check path under real
`SELECT FOR UPDATE` contention.

### 3. auth-rate — decision-path throughput

```bash
k6 run k6/card-auth.js -e SCENARIO=auth-rate -e TARGET_RATE=100 -e DURATION=2m \
  -e FUND_AMOUNT=20000.00
```

Distinct authorizations at a constant arrival rate: replay lookup + account locks +
hold posting + decision insert per request. `card_auth_ms` p95/p99 is the headline;
`card_auth_sys_errors` is gated < 0.1%.

**Sizing:** `FUND_AMOUNT` must exceed `TARGET_RATE × duration_seconds × AUTH_AMOUNT`
or the card runs dry and later auths decline with `INSUFFICIENT_FUNDS` (counted in
`card_auth_declined`, not as errors).

## Env reference

| Var | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8087` | VA service (bench stack port) |
| `SCENARIO` | `smoke` | `smoke` \| `replay-storm` \| `auth-rate` |
| `FUND_AMOUNT` | `5000.00` | Card load at setup |
| `AUTH_AMOUNT` | `1.00` | Per-auth amount |
| `TARGET_RATE` | `50` | auths/sec (auth-rate) |
| `DURATION` | `1m` | auth-rate duration |
| `VUS` | `30` | concurrent VUs (replay-storm) |
| `STORM_ITERS` | `500` | total deliveries (replay-storm) |
| `INTERNAL_TOKEN` | `internal-dev-secret` | `X-Internal-Token` value |

## What this bench does NOT cover

- The ISO 8583 wire path (0100 → card-network-sim → VA). That end-to-end flow,
  including the sim minting `authorizationId` and mapping DE39/DE38, is covered by
  the manual flows in `docs/engineering/phase-mr-e2e-guide.md` (VCC section),
  which require the full dev compose stack with rail-service and rail-simulator.
- Reversal / settlement / hold-expiry lifecycle — those flows land with the
  follow-up work tracked in the multi-rail plan.

## Cleanup

```bash
cd bench/va/
docker compose down -v   # wipes bench DB (each run also uses fresh runId-scoped accounts)
```
