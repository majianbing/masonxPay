# virtual-account-service

Double-entry ledger service for MasonXPay virtual accounts. Optional and independently deployable — the main payment gateway works without it.

Own database: `msx_virtual_account_test` (Postgres, separate from the gateway DB).  
Design doc: [`docs/engineering/virtual-account-guide.md`](../../docs/engineering/virtual-account-guide.md)  
Build progress: [`docs/changelog/virtual-account-service/roadmap.md`](../../docs/changelog/virtual-account-service/roadmap.md)

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
