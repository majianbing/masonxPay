# virtual-account-service

Double-entry ledger service for MasonXPay ledger accounts and card issuing. Optional and independently deployable — the main payment gateway works without it.

Own database: `msx_virtual_account_test` (Postgres, separate from the gateway DB).
Design doc: [`docs/engineering/virtual-account-guide.md`](../../docs/engineering/virtual-account-guide.md)
Build progress: [`docs/changelog/virtual-account-service/roadmap.md`](../../docs/changelog/virtual-account-service/roadmap.md)

## Phase MR additions

Phase MR extended this service with card issuing and rail settlement capabilities:

**New LedgerAccountTypes**

| Type | Role | Normal | Description |
|---|---|---|---|
| `PREPAID_CARD` | TENANT | CREDIT | Ring-fenced cardholder funds bound to a VirtualCard lifecycle |
| `PREPAID_CARD_HOLD` | TENANT | CREDIT | Authorized-but-unsettled prepaid card funds |
| `CARD_NETWORK_RECEIVABLE` | EXTERNAL | CREDIT | Issuing-side obligation to the card network (payable semantics; name kept until CoA cleanup) |
| `BANK_RAIL_RECEIVABLE` | EXTERNAL | DEBIT | Money sitting at the bank rail owed to the platform |
| `SUSPENSE_UNKNOWN_TXN` | PLATFORM | DEBIT | Card transactions timed out — outcome unknown, reversal pending |
| `MERCHANT_RECEIVABLE` | TENANT | DEBIT | Merchant debt from bank-return shortfalls; recouped from later settlements |

**Normal-balance convention (platform books):** merchant and cardholder funds held
by the platform are liabilities — `WALLET`, `PREPAID_CARD`, and `PREPAID_CARD_HOLD`
are CREDIT-normal. `CASH` (external-world money mirror) and receivables are
DEBIT-normal assets. Balances are always positive magnitudes; the engine applies
direction against normal balance.

**VirtualCard entity**
`virtual_card` table links `card_token_id` → `vcc_account_id` (PREPAID_CARD), `hold_account_id` (PREPAID_CARD_HOLD), and `owner_account_id` (WALLET). `masked_pan` is display/audit metadata only. Lifecycle: ACTIVE → FROZEN / EXPIRED / CLOSED.

**Card authorization decision endpoint** (rail-sim issuer adapter)
`POST /internal/issuer/authorize` — called by `rail-simulator`'s card-network-sim (issuer `RAIL_SIM`) for BIN 999999. The simulator derives `cardTokenId` from the test PAN; VA does not identify cards by masked PAN. Idempotent on the issuer-minted `authorizationId`: each decision is recorded in `card_authorization`, duplicate deliveries replay the stored decision, and an APPROVED response is always backed by exactly one `DR PREPAID_CARD_HOLD / CR PREPAID_CARD` hold journal (fail-closed: an unattributable hold state declines with `AUTH_STATE_ANOMALY` instead of approving unheld).

**Ledger Account Management APIs** (all require `X-Internal-Token`)
- `POST /internal/va/accounts` — create a TENANT account (WALLET, CASH, etc.) for a merchant
- `GET /v1/va/accounts/{ledgerAccountId}?merchantId=` — account balance and status; validates merchantId ownership
- `GET /v1/va/accounts?merchantId=&page=&size=` — paginated account list for a merchant

**Rail settlement consumer**
`SettlementEventConsumer` extended to handle `RailSettlementEvent` from Kafka topic `rail.settlement.events`:
- Card sale: DR PREPAID_CARD_HOLD / CR card network settlement account
- Card reversal: DR PREPAID_CARD_HOLD / CR PREPAID_CARD (hold released)
- Bank settle: DR BANK_RAIL_RECEIVABLE / CR merchant WALLET — recouping any open
  MERCHANT_RECEIVABLE balance first (CR debt before CR wallet)
- Bank return — always postable: DR WALLET up to its balance; shortfall booked
  DR MERCHANT_RECEIVABLE (auto-created per merchant/mode/asset, race-safe via a
  partial unique index); CR BANK_RAIL_RECEIVABLE for the full amount
- All journals idempotent on `source_event_id`

**Settlement exception parking** (no event is ever dropped)
A settlement event delivery ends in exactly one of three states: posted, deduped as
replay, or parked in `settlement_exception` with its payload and a reason code.
Handlers park known-unpostable events directly (unknown card, missing wallet or
receivable account, insufficient balance on a bank return); the Kafka
`DefaultErrorHandler` retries transient failures with backoff and parks anything
that survives retries — spring-kafka's default log-and-skip is never reached.
Ops API (`X-Internal-Token`):
- `GET /internal/va/settlement-exceptions?status=OPEN&page=&size=` — worklist
- `GET /internal/va/settlement-exceptions/{id}` — inspect payload + reason
- `POST /internal/va/settlement-exceptions/{id}/retry` — re-drive through the original handler (idempotent); resolves on success, re-parks and bumps `delivery_count` if still unpostable
- `POST /internal/va/settlement-exceptions/{id}/discard` — requires a note
Prometheus gauge `va_settlement_exceptions_open` tracks the backlog for alerting.

---

## Standalone dev/test stack

A self-contained `docker-compose.yml` lives in this directory. It starts both Postgres and the VA Spring Boot service — no need to run the full main stack.

```
backend/virtual-account-service/
├── docker-compose.yml          ← Postgres (5442) + VA service (8086)
├── src/main/resources/
│   └── application-test.yml   ← Spring profile used by the compose service and IDEA
└── src/test/resources/
    └── application-test.yml   ← same settings, on the test classpath
```

### Step 1 — build the Docker image

```bash
# from backend/virtual-account-service/
cd ..                        # move to backend/
mvn package -pl virtual-account-service -am -DskipTests
cd virtual-account-service
```

### Step 2 — start the stack

```bash
docker compose up --build
```

This starts:
- **va-postgres** on `localhost:5442` — Flyway migrations run automatically when the app starts
- **virtual-account-service** on `localhost:8086` — Spring Boot app with `test` profile

Health check: `http://localhost:8086/actuator/health`

### Step 3 — connect to Postgres from Mac

Any DB client (TablePlus, DataGrip, DBeaver) or psql:

```bash
psql -h localhost -p 5442 -U pay_app_user -d msx_virtual_account_test
# password: password123
```

After the service starts, Flyway creates all tables including partitions:

```sql
\dt va_*                  -- all VA tables
\d  va_ledger_entry       -- partitioned by HASH(ledger_account_id), 64 buckets
\d  va_inbox_event        -- partitioned by HASH(event_id), 8 buckets
SELECT * FROM ledger_account; -- account rows
SELECT * FROM va_ledger_entry LIMIT 20;
```

---

## Running tests in IntelliJ IDEA

### Unit tests — no Docker needed

Right-click any test class → **Run**. No database or Kafka required.

Covers: `SnowflakeIdGeneratorTest`, `BalanceSignatureServiceTest`, `LedgerPostingServiceTest` (24 tests).

### Integration tests — requires `docker compose up`

1. Start the stack (Step 2 above)
2. Open `LedgerSettlementHandlerIntegrationTest` in IDEA
3. Set the Spring profile — one of:
   - Edit Run Configuration → **Active profiles**: `test`
   - Or add to VM options: `-Dspring.profiles.active=test`
4. Run the test or any individual method

Data is **left in the DB after each test** — connect to `localhost:5442` and inspect `ledger_account` and `va_ledger_entry` while the test is fresh.

**Tip — set the profile once for all tests:**
Run → Edit Configurations → Templates → JUnit → VM options: `-Dspring.profiles.active=test`

### Integration tests from command line

```bash
# from backend/
mvn test -Pintegration -pl virtual-account-service -am
```

---

## Stop the stack

```bash
docker compose down        # stops containers, data volume persists
docker compose down -v     # stops and wipes the data volume (clean slate)
```
