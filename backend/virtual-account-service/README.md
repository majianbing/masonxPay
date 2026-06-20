# virtual-account-service

Double-entry ledger service for MasonXPay virtual accounts. Optional and independently deployable — the main payment gateway works without it.

Own database: `msx_virtual_account` (Postgres, separate from the gateway DB).  
Design doc: [`docs/engineering/virtual-account-guide.md`](../../docs/engineering/virtual-account-guide.md)  
Build progress: [`docs/changelog/virtual-account-service/roadmap.md`](../../docs/changelog/virtual-account-service/roadmap.md)

---

## Running tests

There are two test layers with different requirements:

### Unit tests — no Docker needed

```bash
mvn test -pl virtual-account-service -am
```

Runs 24 unit tests (SnowflakeIdGenerator, BalanceSignatureService, LedgerPostingService). No database or Kafka required.

In **IntelliJ IDEA**: right-click any test class or the `src/test/java` folder → **Run Tests**. They work out of the box.

---

### Integration tests — require the VA test DB

Integration tests hit a real Postgres with the full partitioned schema (HASH partitions, PG enums). A standalone compose file is included so you don't need the full main stack.

**Step 1 — start the test DB** (from this directory):

```bash
docker compose up -d
```

This starts a Postgres 16 container on **port 5433** (avoids collision with the main stack on 5432). Data is stored in `tmpfs` — wiped clean on `docker compose down`.

**Step 2 — run integration tests**

From the command line:

```bash
mvn test -Pintegration -pl virtual-account-service -am
```

From **IntelliJ IDEA**:

1. Open `LedgerSettlementHandlerIntegrationTest`
2. Click the green run arrow next to the class or any test method
3. Before running, set the **Active profiles** field to `test`:
   - Edit the run configuration → **Active profiles**: `test`
   - Or: right-click → **Modify Run Configuration** → set `spring.profiles.active=test`
4. Run — IDEA will connect to `localhost:5433` and apply Flyway migrations automatically

**Alternative IDEA setup (once, applies to all integration tests):**

Go to **Run → Edit Configurations → Templates → JUnit**:
- **VM options**: `-Dspring.profiles.active=test`

All new test runs will inherit the profile.

**Step 3 — stop the test DB**

```bash
docker compose down
```

---

## Environment variables (integration tests)

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | Postgres host |
| `DB_PORT` | `5433` | Postgres port (test compose uses 5433) |
| `VA_TEST_DB_NAME` | `msx_virtual_account_test` | Test database name |
| `VA_DB_USERNAME` | `pay_app_user` | DB user |
| `VA_DB_PASSWORD` | `password123` | DB password |

---

## Connecting to the test DB manually

```bash
psql -h localhost -p 5433 -U pay_app_user -d msx_virtual_account_test
```

After the first integration test run, Flyway will have applied all migrations and you can inspect the schema:

```sql
\dt va_*         -- list all VA tables (partitioned)
\d va_account    -- account schema
\d va_ledger_entry  -- ledger entry (HASH partitioned, 64 buckets)
```
