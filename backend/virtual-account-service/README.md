# virtual-account-service

Double-entry ledger service for MasonXPay virtual accounts and card issuing. Optional and independently deployable — the main payment gateway works without it.

Own database: `msx_virtual_account_test` (Postgres, separate from the gateway DB).  
Design doc: [`docs/engineering/virtual-account-guide.md`](../../docs/engineering/virtual-account-guide.md)  
Build progress: [`docs/changelog/virtual-account-service/roadmap.md`](../../docs/changelog/virtual-account-service/roadmap.md)

## Phase MR additions

Phase MR extended this service with card issuing and rail settlement capabilities:

**New AccountTypes**

| Type | Role | Description |
|---|---|---|
| `PREPAID_CARD` | TENANT | Ring-fenced wallet bound to a VirtualCard lifecycle |
| `CARD_NETWORK_RECEIVABLE` | EXTERNAL | Amounts owed by card network between sale and settlement |
| `BANK_RAIL_RECEIVABLE` | EXTERNAL | Amounts owed from bank rail between pain.001 and pacs.002 ACSC |
| `SUSPENSE_UNKNOWN_TXN` | PLATFORM | Card transactions timed out — outcome unknown, reversal pending |

**VirtualCard entity**  
`virtual_card` table links `masked_pan` → `vcc_account_id` (PREPAID_CARD) and `owner_account_id` (WALLET). Lifecycle: ACTIVE → FROZEN / EXPIRED / CLOSED.

**Card issuer endpoint**  
`POST /internal/issuer/authorize` — called by `rail-simulator`'s card-network-sim for BIN 999999. Checks available balance on the PREPAID_CARD account, freezes the auth amount, returns approve/decline.

**VA Account Management APIs** (all require `X-Internal-Token`)  
- `POST /internal/va/accounts` — create a TENANT account (WALLET, CASH, etc.) for a merchant  
- `GET /v1/va/accounts/{accountId}?merchantId=` — account balance and status; validates merchantId ownership  
- `GET /v1/va/accounts?merchantId=&page=&size=` — paginated account list for a merchant

**Rail settlement consumer**  
`SettlementEventConsumer` extended to handle `RailSettlementEvent` from Kafka topic `rail.payment.settled`:
- Card sale: DR CARD_NETWORK_RECEIVABLE / CR PREPAID_CARD, release freeze
- Bank settle: DR BANK_RAIL_RECEIVABLE / CR target account
- Bank return: reverse the settlement journal
- All journals idempotent on `source_event_id`

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
\d  va_ledger_entry       -- partitioned by HASH(account_id), 64 buckets
\d  va_inbox_event        -- partitioned by HASH(event_id), 8 buckets
SELECT * FROM va_account; -- account rows
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

Data is **left in the DB after each test** — connect to `localhost:5442` and inspect `va_account` and `va_ledger_entry` while the test is fresh.

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
