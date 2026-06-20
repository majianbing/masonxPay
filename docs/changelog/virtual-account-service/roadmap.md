# Virtual Account Service — Build Roadmap

Design doc: `docs/engineering/virtual-account-guide.md`

---

## Layer 1 — DB Migrations `[x]`

- `[x]` V2: repartition `va_inbox_event` → `PARTITION BY HASH(event_id)`, 8 buckets
- `[x]` V3: create `va_account` table (enums, extension tables, constraints, indexes)
- `[x]` V4: create `va_ledger_entry` table → `PARTITION BY HASH(account_id)`, 64 buckets

## Layer 2 — Common Module `[ ]`

- `[ ]` `SnowflakeIdGenerator` — time + node bits, thread-safe; used for `ac_`, `le_`, `tx_` prefixed IDs

## Layer 3 — Contracts Update `[ ]`

- `[ ]` Add money fields to `SettlementEvent` (amount, asset, asset_class, scale, direction, fee)
- `[ ]` Update `RecordSettlementCommand` + `SettlementEventMapper` to carry money fields through

## Layer 4 — Domain Model & Repositories `[ ]`

- `[ ]` Enums: `AccountRole`, `AccountType`, `Direction`, `AssetClass`
- `[ ]` `AccountRepository` — find by account_id, `SELECT FOR UPDATE`
- `[ ]` `LedgerEntryRepository` — insert, fetch last entry per account (for HMAC chain)
- `[ ]` `BalanceSignatureService` — HMAC-SHA256 chain computation and validation
- `[ ]` `LedgerPostingService` — core double-entry: lock → entry_seq → balance_after → signature → atomic write

## Layer 5 — Wire-up & Tests `[ ]`

- `[ ]` Real `SettlementHandler` replacing `LoggingSettlementHandler`
- `[ ]` Unit tests: `LedgerPostingService`, `BalanceSignatureService`
- `[ ]` Integration test: full settlement event → ledger entries flow

---

## Completed

- **Layer 1 — DB Migrations** (2026-06-20): V2 inbox repartition, V3 va_account, V4 va_ledger_entry
