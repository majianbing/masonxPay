# Virtual Account Service — Build Roadmap

Design doc: `docs/engineering/virtual-account-guide.md`

---

## Layer 1 — DB Migrations `[x]`

- `[x]` V2: repartition `va_inbox_event` → `PARTITION BY HASH(event_id)`, 8 buckets
- `[x]` V3: create `va_account` table (enums, extension tables, constraints, indexes)
- `[x]` V4: create `va_ledger_entry` table → `PARTITION BY HASH(account_id)`, 64 buckets

## Layer 2 — Common Module `[x]`

- `[x]` `SnowflakeIdGenerator` — time + node bits, thread-safe; used for `ac_`, `le_`, `tx_` prefixed IDs

## Layer 3 — Contracts Update `[x]`

- `[x]` Add money fields to `SettlementEvent` (amount, feeAmount, asset, assetClass, scale, direction) — schema v2, additive/nullable
- `[x]` `Direction` + `AssetClass` enums added to VA domain
- `[x]` `RecordSettlementCommand` updated with VA-typed money fields (BigDecimal, enums, netAmount derived)
- `[x]` `SettlementEventMapper` maps contract strings → VA enums with null-safe v1 defaults

---

## Completed

- **Layer 1 — DB Migrations** (2026-06-20): V2 inbox repartition, V3 va_account, V4 va_ledger_entry
- **Layer 2 — Common Module** (2026-06-20): SnowflakeIdGenerator (8 tests passing)
- **Layer 3 — Contracts Update** (2026-06-20): SettlementEvent v2 money fields, Direction/AssetClass enums, RecordSettlementCommand, SettlementEventMapper
- **Layer 4 — Domain Model & Repositories** (2026-06-20): VaAccount, LedgerEntry, AccountRole/Type/Status/NormalBalance/EntryStatus enums; AccountRepository (SELECT FOR UPDATE + 3 finder methods); LedgerEntryRepository; BalanceSignatureService (HMAC-SHA256 chain); LedgerPostingService (deadlock-safe posting, net-zero validation, negative balance guard). 24 unit tests passing.
- **Layer 5 — Wire-up** (2026-06-20): LedgerSettlementHandler replaces LoggingSettlementHandler; 3-entry (gross+fee) and 2-entry (net) settlement flows; refund reversal; AccountRepository finder methods for tenant/external/platform account lookup. Integration test (`LedgerSettlementHandlerIntegrationTest`) covers 7 scenarios against real Postgres — run with `mvn test -Pintegration -pl virtual-account-service -am` (requires `docker compose up`). Normal `mvn test` runs 24 unit tests only.
