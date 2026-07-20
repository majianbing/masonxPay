# VA Bench And Local Seeds

This directory contains local-only tooling for exercising the Virtual Account
ledger through service APIs. The scripts do not insert ledger rows directly; they
post through VA endpoints so balances, `entry_seq`, journal headers, and HMAC
chains remain valid.

## Capacity Bench

Use the isolated bench stack when measuring ledger throughput or correctness:

```bash
cd bench/va/
docker compose up --build
k6 run k6/va-capacity.js -e BASE_URL=http://localhost:8087 -e SCENARIO=correctness
```

The bench stack enables `/internal/bench/*` only inside the bench VA service on
host port `8087`. See `RESULTS.md` for scenarios, thresholds, and historical
results.

## Dashboard Seed

`dashboard-seed.mjs` creates display data for an existing local merchant in the
normal dev stack. It is intended for checking the dashboard VA account list and
ledger-entry detail pages.

What it creates:

- Two wallet top-ups via `POST /internal/bench/post`
- One VCC via `POST /v1/vcc/cards`
- One card funding journal via `POST /v1/vcc/cards/{cardId}/fund`

Dashboard expectation:

- `/virtual-account` shows merchant-level financial accounts (`CASH`, `WALLET`,
  `MERCHANT_RECEIVABLE`) and hides per-card backing accounts.
- The VCC seed still creates `PREPAID_CARD` and `PREPAID_CARD_HOLD` ledger
  accounts, but those are implementation accounts for card lifecycle/audit.
  They should be exposed through a VCC-specific view, not the main VA account
  list.

The default auth-hold seed is disabled because it depends on the local
`card_authorization` schema being current. Set `SEED_AUTHS=true` only after your
local DB has the latest auth migrations.

### Run Against The Dev Stack

The normal compose stack keeps bench endpoints disabled by default. Enable them
only for the local seed run:

```bash
VA_BENCH_ENABLED=true docker compose --profile infra up -d --build virtual-account
```

Then run the seed script:

```bash
BASE_URL=http://127.0.0.1:8086 \
MERCHANT_ID=0228f8c4-20e7-4e7e-a983-26d1d0e80378 \
CASH_ACCOUNT_ID=ac_866936551769112576 \
WALLET_ACCOUNT_ID=ac_866936551794278400 \
node bench/va/dashboard-seed.mjs
```

If `CASH_ACCOUNT_ID` and `WALLET_ACCOUNT_ID` are omitted, the script resolves the
merchant's TEST `CASH` and `WALLET` accounts from `GET /v1/va/accounts`.

After checking the dashboard, restart VA without the bench flag:

```bash
docker compose --profile infra up -d --build virtual-account
```

### Environment

| Var | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8086` | VA service base URL |
| `INTERNAL_TOKEN` | `internal-dev-secret` | `X-Internal-Token` value |
| `MERCHANT_ID` | Demo UUID from local stack | VA merchant UUID |
| `CASH_ACCOUNT_ID` | empty | Optional explicit CASH account |
| `WALLET_ACCOUNT_ID` | empty | Optional explicit WALLET account |
| `RUN_ID` | timestamp-based | Idempotency suffix for generated data |
| `SEED_AUTHS` | `false` | Also create issuer auth holds |
