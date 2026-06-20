# Virtual Account Service — Build Roadmap

Design doc: `docs/engineering/virtual-account-guide.md`

---

## Layer 1 — DB Migrations `[x]`

- `[x]` V2: repartition `va_inbox_event` → `PARTITION BY HASH(event_id)`, 8 buckets
- `[x]` V3: create `va_account` table (enums, extension tables, constraints, indexes)
- `[x]` V4: create `va_ledger_entry` table → `PARTITION BY HASH(account_id)`, 64 buckets
- `[x]` V5: add `frozen_balance` + `prev_signature` columns to `va_ledger_entry` — makes each entry self-verifiable (no second read needed for chain verification)

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
- **Correctness fixes & hardening** (2026-06-20):
  - `SignatureInput.canonical()` — `stripTrailingZeros().toPlainString()` on all BigDecimal fields; fixes HMAC mismatch between posting (request-body scale) and verification (DB NUMERIC(38,8) scale). All bench soak/spike runs previously showed `chain=false brokenChain=1`; now passes.
  - `SnowflakeIdGenerator` — spin-wait on clock drift ≤ 5 ms instead of throwing; eliminates 35 errors/800-TPS spike run caused by Docker NTP corrections of 1 ms.
  - V5 migration — `frozen_balance` + `prev_signature` columns on `va_ledger_entry`.
  - `LedgerEntryRepository` — `findLastChainHead()` replaces `findLastAnchor()`; returns full last-entry fields for pre-post verification.
  - `LedgerPostingService` — pre-post tamper checks: (A) `va_account.balance == last_entry.balance_after` (catches direct account row edits); (B) HMAC recompute of last entry (catches direct ledger entry edits). Throws `VA_BALANCE_MISMATCH` / `VA_CHAIN_TAMPERED` respectively. Balance check fires before chain read (fail fast).
  - `LedgerFacade` — new public posting API; `LedgerPostingService.post()` made package-private. Two explicit paths: `postIfNew(tx, eventId, eventType)` (inbox enforced here, not in consumers) and `postDirect(tx)` (explicit bypass for bench/corrections). `SettlementEventConsumer` simplified to a pure adapter.
  - `BenchController.verify()` — uses stored `frozen_balance` and `prev_signature` from entry rows; no longer hardcodes `BigDecimal.ZERO` or tracks `prevSig` in a loop variable.
  - 19 unit tests passing.
