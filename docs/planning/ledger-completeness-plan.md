# MasonXPay Ledger Completeness — Phase LC

## Purpose

Phase LC completes the `virtual-account-service` double-entry ledger with three standard accounting primitives that are missing from the current implementation:

1. **Journal Entry header** (`va_transaction`) — a persisted, first-class record of every posting group: what it was, why it happened, which payment originated it, and which accounting period it belongs to. `LedgerPostingCommand` is the in-memory command; `va_transaction` is the stored journal header.

2. **General Ledger (GL) query API** — endpoints to read account entry history, paginated and ordered by `entry_seq`.

3. **Account Statement and Trial Balance** — a period-based account statement and a system-wide trial balance report for auditors.

The design follows the **two-layer ledger architecture** used by Stripe, Adyen, Wise, and every major ERP:

- **System layer** (existing, untouched): `va_ledger_entry` — append-only, HMAC-chained, 64-shard, throughput-optimised posting engine.
- **Financial/audit layer** (new): `va_transaction` — journal entry header carrying business context, accounting date, and payment cross-reference.

## Architecture Decisions

| Question | Decision | Rationale |
|---|---|---|
| Where does entry_type/description/payment_reference live? | Separate `va_transaction` table | Audit layer concerns do not belong on immutable posting rows |
| Shards for `va_transaction`? | 64 — matching `va_ledger_entry` | Operational symmetry; future scale headroom |
| Shard key? | `HASH(transaction_id)` | Primary writes and reads are by `transaction_id` |
| Does `va_ledger_entry` gain new columns? | One only: `effective_date DATE` | Required for accounting-grade period statements without cross-partition joins |
| How are statement opening/closing balances computed? | **Signed entry sums by effective_date, not `balance_after` snapshots** | `balance_after` is ordered by `entry_seq` (posting order), not effective date. A backdated entry's `balance_after` includes later-period activity. Sums from entries filtered by effective_date are correct by definition regardless of posting order. |
| Transaction detail cross-partition cost? | Documented as audit-only 64-partition fan-out | Acceptable; future escape hatch is `va_transaction_entry(transaction_id, ledger_account_id)` mapping table |
| VOIDED status? | Deferred from Phase LC | Requires `reversal_of_transaction_id` link; POSTED-only in this phase |
| Trial balance bound? | Unbounded in Phase LC | Admin-only, low account count; future: pagination or nightly `va_trial_balance_snapshot` |
| Platform/EXTERNAL legs in transaction detail response? | Included when merchant owns a TENANT leg | Acceptable for internal use; document exposure intentionally. Future merchant-facing API must redact platform/external account IDs |

## Milestones

### LC1 — Journal Entry Header + `effective_date` on Entries

**Status**: `[x]` complete (2026-06-30) — 43/43 unit tests passing

#### V8 DB migration (`V8__va_transaction.sql`)

**New `va_transaction` table — 64 HASH(transaction_id) partitions:**

```sql
CREATE TABLE va_transaction (
    transaction_id       VARCHAR(32)   NOT NULL,
    entry_type           VARCHAR(50)   NOT NULL,   -- VARCHAR not PG enum; new types cost no migration
    description          VARCHAR(255),
    payment_reference_id VARCHAR(64),              -- gateway payment_intent_id; nullable
    effective_date       DATE          NOT NULL,   -- accounting date; may differ from created_at
    status               VARCHAR(20)   NOT NULL DEFAULT 'POSTED',  -- POSTED only in Phase LC
    mode                 va_mode       NOT NULL,
    org_id               VARCHAR(64),              -- nullable; set for TENANT transactions
    merchant_id          VARCHAR(64),              -- nullable; NULL for PLATFORM/EXTERNAL
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
) PARTITION BY HASH (transaction_id);
-- 64 child partitions (same explicit CREATE TABLE pattern as V4)
```

Per-partition indexes (DO block):
- `PRIMARY KEY (transaction_id)`
- `INDEX ON (merchant_id, effective_date) WHERE merchant_id IS NOT NULL`
- `INDEX ON (payment_reference_id) WHERE payment_reference_id IS NOT NULL`
- `INDEX ON (entry_type, mode, effective_date)`

**`effective_date` added to `va_ledger_entry` — safe 3-step migration:**

`CURRENT_DATE` is a volatile function and does not trigger Postgres's fast constant-default path. Use `created_at::date` for the backfill so existing rows get a historically accurate value rather than a fixed sentinel that would corrupt period reports:

```sql
-- Step 1: add nullable (instant; no table rewrite)
ALTER TABLE va_ledger_entry ADD COLUMN effective_date DATE;

-- Step 2: backfill existing rows from their own created_at
-- Note: if no production ledger history exists (bench/dev only), any constant is safe;
-- created_at::date is always correct and safe for both cases.
UPDATE va_ledger_entry SET effective_date = created_at::date WHERE effective_date IS NULL;

-- Step 3: set NOT NULL
ALTER TABLE va_ledger_entry ALTER COLUMN effective_date SET NOT NULL;
```

Runtime inserts always supply `effective_date` explicitly from `LedgerPostingCommand.effectiveDate()`.

**Per-partition indexes on `va_ledger_entry`** (DO block, two indexes per partition):

```sql
-- Audit path: fetch all entries for a transaction (cross-partition fan-out)
CREATE INDEX idx_va_ledger_entry_N_txn ON va_ledger_entry_N (transaction_id);

-- Statement path: account-scoped date-range scans and signed-sum aggregations
-- Covers: sumDebitNetBeforeDate, sumDebitNetUpToDate, findByAccountIdAndEffectiveDateRange
CREATE INDEX idx_va_ledger_entry_N_eff ON va_ledger_entry_N (ledger_account_id, effective_date, entry_seq);
```

The `transaction_id` index: `WHERE transaction_id = ?` still fans out across all 64 partitions — **audit-only path**, not the posting hot path.

The `(ledger_account_id, effective_date, entry_seq)` compound index: all statement queries are account-scoped and filter by `effective_date`, so this index supports both range scans and aggregate sums without touching entries outside the date window.

#### Domain changes

New `TransactionType` enum (`domain/constant/TransactionType.java`):
`SETTLEMENT, REFUND, FEE, REVERSAL, CORRECTION, CARD_SALE, CARD_REVERSAL, BANK_TRANSFER, INTERNAL`

`LedgerPostingCommand` carries: `entryType`, `description` (nullable), `paymentReferenceId` (nullable), `effectiveDate`, `mode`, `orgId` (nullable), `merchantId` (nullable).

New `TransactionRecord` read DTO (`domain/ledger/TransactionRecord.java`).

New `TransactionRepository` (`domain/ledger/TransactionRepository.java`): `insert(LedgerPostingCommand)` + `findById(String)`.

`LedgerEntry` record gains `effectiveDate` field. `LedgerEntryRepository.insert()` SQL updated to include `effective_date`.

#### Engine change (`LedgerPostingService`)

Inject `TransactionRepository`. At top of `post()`, before account locking: `txRepo.insert(tx)`. Populate `effective_date` on each `LedgerEntry` from `tx.effectiveDate()`.

#### Call-site updates

Every `LedgerPostingCommand` construction supplies the journal header fields:

| Handler | `entryType` | `merchantId` | `orgId` | `mode` |
|---|---|---|---|---|
| `LedgerSettlementHandler` — settlement | `SETTLEMENT` | `cmd.tenant().merchantId()` | `cmd.tenant().orgId()` | `cmd.tenant().mode()` |
| `LedgerSettlementHandler` — refund | `REFUND` | `cmd.tenant().merchantId()` | `cmd.tenant().orgId()` | `cmd.tenant().mode()` |
| `CardRailSettlementHandler` — CARD_SALE | `CARD_SALE` | `cardAccount.merchantId()` | `cardAccount.orgId()` | `cardAccount.mode()` |
| `CardRailSettlementHandler` — BANK_CREDIT_TRANSFER | `BANK_TRANSFER` | `event.merchantId()` | null | `RAIL_MODE` |
| `CardRailSettlementHandler` — BANK_RETURN | `REVERSAL` | `event.merchantId()` | null | `RAIL_MODE` |
| `BenchController.post()` | `INTERNAL` | null | null | `Mode.LIVE` |

> **Note on CARD_SALE**: derive all three (`merchantId`, `orgId`, `mode`) from `cardAccount` (already fetched via `accountRepo.findByIdForUpdate(card.vccAccountId())`). Do not use the handler-level `RAIL_MODE` constant for the transaction header — the account's mode is the authoritative value and ensures the transaction header is consistent with the account it affects.

---

### LC2 — General Ledger Query API

**Status**: `[x]` complete (2026-06-30) — 53/53 unit tests passing

#### Repository additions (`LedgerEntryRepository`)

```java
List<LedgerEntry> findByAccountId(String accountId, int page, int size)   // ORDER BY entry_seq DESC
long countByAccountId(String accountId)
List<LedgerEntry> findByTransactionId(String transactionId)
    // Cross-partition audit query: fans out across all 64 va_ledger_entry partitions.
    // Per-partition transaction_id index reduces cost within each shard.
    // Acceptable for audit path only — not for throughput-sensitive callers.
```

#### New `LedgerQueryService` (`domain/ledger/LedgerQueryService.java`)

MasonXPay requires **both** merchant and mode scope on every resource where TEST/LIVE data can coexist. All account-scoped and transaction-scoped reads validate both dimensions:

- **Account reads**: `accountRepo.findById(accountId)` → assert `account.merchantId().equals(merchantId)` AND `account.mode().name().equals(mode)`. Throws `VA_ACCESS_DENIED` on either mismatch.
- **Transaction detail**: (1) `txRepo.findById(transactionId)` → assert `tx.mode().name().equals(mode)`; (2) `entryRepo.findByTransactionId(transactionId)` 64-partition fan-out; (3) verify at least one entry's account is TENANT, merchant-owned, and in the requested mode.
- Platform/EXTERNAL legs are included in the response when ownership passes. Intentional for internal use. **Future merchant-facing API must redact platform/external account IDs.** Document in engineering guide.

#### New `LedgerQueryController` (`ledger/LedgerQueryController.java`)

```
GET /v1/ledger/accounts/{accountId}/entries?merchantId=&mode=LIVE&page=0&size=20
    → PagedResult<LedgerEntryResponse>   (ordered entry_seq DESC)

GET /v1/ledger/transactions/{transactionId}?merchantId=&mode=LIVE
    → TransactionDetailResponse
    (validates tx.mode and at least one TENANT leg matches merchant + mode)
```

Auth: X-Internal-Token (INTERNAL role). `VaSecurityConfig`: add `/v1/ledger/**` to the INTERNAL requestMatcher.

**DTOs** (`ledger/dto/`):
- `LedgerEntryResponse`: `entryId, transactionId, direction, amount, asset, balanceAfter, entrySeq, effectiveDate, status, createdAt` — no HMAC fields.
- `TransactionDetailResponse`: `transactionId, entryType, description, paymentReferenceId, effectiveDate, status, mode, merchantId, createdAt, entries: List<LedgerEntryResponse>`.

---

### LC3 — Account Statement

**Status**: `[x]` complete (2026-06-30) — 58/58 unit tests passing

#### Core principle: compute from signed entry sums, not from `balance_after`

**Why `balance_after` cannot be used for effective-date period statements:**

`balance_after` is a running snapshot ordered by `entry_seq` (posting order). A backdated entry posted in March with `effective_date=January` has a `balance_after` that includes all February and March postings already committed before it. Reading that `balance_after` as "the January closing balance" is wrong.

**Correct approach: compute balance from scratch using signed sums filtered by `effective_date`:**

```
debitNet(account, cutoffDate) = Σ(amount WHERE direction=DEBIT AND effective_date ≤ cutoffDate)
                               − Σ(amount WHERE direction=CREDIT AND effective_date ≤ cutoffDate)

balance(account, cutoffDate) = normalBalance == DEBIT ? debitNet : −debitNet
```

This is accurate by definition, regardless of posting order. It correctly handles backdated entries and delayed Kafka settlements.

#### Verification formula (must respect `normalBalance`)

The net change in a period equals the balance movement for the period. Applied correctly by normalBalance:

```
For DEBIT-normal (CASH, WALLET, RECEIVABLE, RESERVE, BAD_DEBT):
  periodNetChange = Σ(DEBIT in period) − Σ(CREDIT in period)
  closingBalance  = openingBalance + periodNetChange

For CREDIT-normal (CREDIT_LINE):
  periodNetChange = Σ(CREDIT in period) − Σ(DEBIT in period)
  closingBalance  = openingBalance + periodNetChange
```

Or equivalently, using the same `debitNet` definition:
```
  closingBalance = openingBalance + (normalBalance == DEBIT ? periodDebitNet : −periodDebitNet)
```

This always holds by the immutability + net-zero invariant. The formula in the original plan (`opening + credits − debits`) was only correct for CREDIT-normal accounts and wrong for DEBIT-normal accounts.

#### Boundary math (correct, no off-by-one)

Given a period `[start_date, end_date]`:

- **Opening balance** = `balance(account, start_date - 1 day)` computed via signed sum of all entries where `effective_date < start_date` (strictly before; exclusive). Zero if no prior entries.
- **Period entries** = entries where `effective_date BETWEEN start_date AND end_date`, ordered by `entry_seq ASC`.
- **Closing balance** = `balance(account, end_date)` computed via signed sum of all entries where `effective_date <= end_date`.
- **Net change** = `closingBalance − openingBalance`.

The opening anchor is computed from entries strictly outside the period. Period entries are strictly inside. No double-counting.

#### Repository additions (`LedgerEntryRepository`)

```java
// Σ(DEBIT amounts) − Σ(CREDIT amounts) for entries with effective_date < beforeDate
// Caller applies normalBalance sign: balance = DEBIT ? debitNet : −debitNet
BigDecimal sumDebitNetBeforeDate(String accountId, LocalDate beforeDate)

// Same for entries with effective_date <= toDate
BigDecimal sumDebitNetUpToDate(String accountId, LocalDate toDate)

// Entries in [fromDate, toDate] ordered by entry_seq ASC (for display)
List<LedgerEntry> findByAccountIdAndEffectiveDateRange(String accountId, LocalDate fromDate, LocalDate toDate)
```

All three queries are account-scoped → one partition hit.

SQL pattern for the sum queries:
```sql
SELECT COALESCE(
  SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0
) FROM va_ledger_entry
WHERE ledger_account_id = ? AND status = 'POSTED' AND effective_date < ?
```

#### Service logic (`LedgerQueryService`)

```java
BigDecimal toBalance(BigDecimal debitNet, NormalBalance normalBalance) {
    return normalBalance == NormalBalance.DEBIT ? debitNet : debitNet.negate();
}

AccountStatementResponse statement(String accountId, String merchantId, String mode, LocalDate from, LocalDate to) {
    LedgerAccount account = assertOwnership(accountId, merchantId, mode);  // validates both merchantId AND mode
    BigDecimal openDebitNet  = entryRepo.sumDebitNetBeforeDate(accountId, from);
    BigDecimal closeDebitNet = entryRepo.sumDebitNetUpToDate(accountId, to);
    BigDecimal opening = toBalance(openDebitNet,  account.normalBalance());
    BigDecimal closing = toBalance(closeDebitNet, account.normalBalance());
    List<LedgerEntry> entries = entryRepo.findByAccountIdAndEffectiveDateRange(accountId, from, to);
    // compute totalDebits, totalCredits from entries
    ...
}
```

#### Controller endpoint (added to `LedgerQueryController`)

```
GET /v1/ledger/accounts/{accountId}/statement?merchantId=&mode=LIVE&from=2026-01-01&to=2026-06-30
    Auth: X-Internal-Token
    → AccountStatementResponse
```

`AccountStatementResponse` (record):
```
accountId, asset, normalBalance, fromDate, toDate,
openingBalance,         // computed from signed sums, effective_date < fromDate
closingBalance,         // computed from signed sums, effective_date <= toDate
totalDebits,            // Σ(DEBIT amounts in period)
totalCredits,           // Σ(CREDIT amounts in period)
netChange,              // closingBalance - openingBalance
entries: List<LedgerEntryResponse>   (ordered by entry_seq ASC)
```

`normalBalance` is included in the response so the caller understands the sign convention.

---

### LC4 — Trial Balance

**Status**: `[x]` complete (2026-06-30) — 62/62 unit tests passing; 66/66 integration tests passing

#### Repository addition (`LedgerAccountRepository`)

```java
List<LedgerAccount> findAllByModeAndAsset(Mode mode, String asset)  // no LIMIT — admin report
```

#### Controller endpoint (added to `LedgerQueryController`)

```
GET /internal/ledger/trial-balance?mode=LIVE&asset=USD
    Auth: X-Internal-Token (no merchant scope — platform-wide admin report)
    → TrialBalanceResponse
```

`TrialBalanceResponse` (record):
```
mode, asset, asOf (Instant),
totalDebitSideBalance,    // Σ(balance on DEBIT-normal accounts)
totalCreditSideBalance,   // Σ(balance on CREDIT-normal accounts)
balanced (boolean),       // totalDebitSide.compareTo(totalCreditSide) == 0
rows: List<TrialBalanceRow>
    each: ledgerAccountId, ledgerAccountType, ledgerAccountRole, merchantId, normalBalance, balance
```

`balanced = false` signals tampering not caught by the per-account HMAC chain, a posting engine bug, or manually-loaded seed data with unbalanced starting balances.

**Known scale limit**: unbounded in Phase LC. When account count grows, add pagination or a nightly-materialized `va_trial_balance_snapshot` table. Document this in the engineering guide.

---

## Files to Create / Modify

| File | Action |
|---|---|
| `db/migration/va/V8__va_transaction.sql` | Create — `va_transaction` 64 partitions + PKs/indexes; 3-step `effective_date` ADD to `va_ledger_entry`; `transaction_id` index per `va_ledger_entry_N` |
| `domain/constant/TransactionType.java` | Create |
| `domain/ledger/LedgerPostingCommand.java` | Carries journal header fields |
| `domain/po/LedgerEntry.java` | Add `effectiveDate` |
| `domain/ledger/TransactionRecord.java` | Create |
| `domain/ledger/TransactionRepository.java` | Create |
| `domain/ledger/LedgerPostingService.java` | Inject `TransactionRepository`; insert tx header; populate `effective_date` on entries |
| `domain/ledger/LedgerEntryRepository.java` | Update insert SQL; add 5 query methods |
| `domain/ledger/LedgerAccountRepository.java` | Add `findAllByModeAndAsset` |
| `domain/ledger/LedgerQueryService.java` | Create (signed-sum balance logic, ownership checks) |
| `ledger/LedgerQueryController.java` | Create (5 endpoints) |
| `ledger/dto/LedgerEntryResponse.java` | Create |
| `ledger/dto/TransactionDetailResponse.java` | Create |
| `ledger/dto/AccountStatementResponse.java` | Create (includes `normalBalance` field) |
| `ledger/dto/TrialBalanceResponse.java` | Create |
| `config/VaSecurityConfig.java` | Add `/v1/ledger/**` to INTERNAL role |
| `domain/LedgerSettlementHandler.java` | Supply new `LedgerPostingCommand` fields |
| `domain/CardRailSettlementHandler.java` | Supply new fields; derive `merchantId` from `cardAccount.merchantId()` for CARD_SALE |
| `bench/BenchController.java` | Supply new `LedgerPostingCommand` fields |
| `docs/engineering/virtual-account-guide.md` | Add 5 new sections |
| `docs/changelog/virtual-account-service/roadmap.md` | Add LC1–LC4 items |

---

## Tests

**Unit** (no Docker):
- `LedgerQueryServiceTest`:
  - `toBalance()` correct for both `DEBIT` and `CREDIT` normal balance
  - Statement opening/closing: backdated entry posts AFTER period closes but with `effective_date` inside period — appears in period entries, not in opening balance
  - Statement opening/closing: entry posted in period month but with `effective_date` in prior month — appears in opening balance, not in period entries
  - Trial balance: `balanced = true` with seed debit+credit accounts
  - Ownership denial for wrong `merchantId`
  - Transaction detail: merchant with no TENANT leg denied; merchant with TENANT leg gets full response including platform/external legs

**Integration** (`-Pintegration`, requires `docker compose up`):
- Post settlement → `va_transaction` row: correct `entry_type`, `merchant_id`, `effective_date`; `va_ledger_entry` rows carry `effective_date`
- GL query: correct pagination, `entry_seq DESC` order
- Statement: post in Jan, post in Feb; Jan statement shows opening=0, correct Jan entries, correct closing; Feb statement shows opening=Jan closing, correct Feb entries
- Backdated scenario: post March entry with `effective_date=January`; Jan statement includes it; Feb statement does not; March statement opening balance reflects it
- Trial balance: `balanced = true` after net-zero postings

```bash
cd backend && mvn test -Pintegration -pl virtual-account-service -am
```

---

## Review Log

| Finding | Fix |
|---|---|
| `va_transaction` missing `merchant_id` | Added `merchant_id` + `org_id` (nullable); indexed on `(merchant_id, effective_date)` |
| Statement uses `created_at` not `effective_date` | `effective_date` denormalized onto `va_ledger_entry`; statements filter by it |
| Off-by-one boundary | Opening = last entry strictly BEFORE period (exclusive); period entries = BETWEEN dates |
| Cross-partition cost understated | Documented honestly as 64-partition fan-out; future mapping table named as escape hatch |
| VOIDED not designed | Deferred from Phase LC; POSTED only |
| Transaction detail no merchant scope | Requires `?merchantId=`; service validates TENANT leg ownership |
| Trial balance unbounded | Acceptable Phase LC; future pagination/snapshot noted |
| `balance_after` wrong for effective-date statements | **Replaced with signed debit-net sums filtered by `effective_date`** — correct regardless of posting order |
| Verification formula wrong for debit-normal accounts | **Fixed**: `netChange = closing − opening`; sign derived from `normalBalance` via `toBalance()` helper |
| `CURRENT_DATE` default not safe | **Fixed**: 3-step migration; backfill uses `created_at::date` (not a fixed sentinel) |
| CARD_SALE `merchantId = null` | **Fixed**: derive `merchantId`, `orgId`, and `mode` all from `cardAccount` (already fetched) |
| Platform/EXTERNAL legs in transaction detail | **Documented**: intentional for internal use; future merchant-facing API must redact |
| `/v1/ledger/**` missing mode scope | **Fixed**: all endpoints require `?mode=` param; service validates `account.mode()` AND `tx.mode()` alongside `merchantId` |
| Statement queries missing effective_date index | **Fixed**: V8 migration adds `(ledger_account_id, effective_date, entry_seq)` index per `va_ledger_entry_N` partition (in same DO block as transaction_id index) |
| Backfill sentinel `'2026-01-01'` corrupts history | **Fixed**: backfill uses `created_at::date` — historically accurate for all rows |
| CARD_SALE `orgId` and `mode` not addressed | **Fixed**: derive all three from `cardAccount`; CARD_SALE no longer uses handler-level `RAIL_MODE` constant |
| **Integration: `InternalTokenFilter` not granting ROLE_INTERNAL for `/v1/ledger/**`** | **Fixed**: `InternalTokenFilter.java:57` now includes `uri.startsWith("/v1/ledger/")` in the path check that grants `ROLE_INTERNAL`. Tests added in `InternalTokenFilterTest.java`. |
| **Integration: `InboxRepository` partitioned upsert missing conflict clause** | **Fixed**: `InboxRepository.java:23` — idempotency insert now ends with `ON CONFLICT DO NOTHING`, preventing duplicate-key errors on Kafka redelivery. |

## Integration Test Results (2026-06-30)

**66/66 tests passing** after the two blockers above were fixed by integration test run.

Smoke-tested endpoints (Docker stack, all LC paths):
- `GET /v1/ledger/accounts/{id}/entries` — paginated, entry_seq DESC
- `GET /v1/ledger/transactions/{id}` — tx header + all entry legs
- `GET /v1/ledger/accounts/{id}/statement` — opening/closing balances, period entries
- `GET /internal/ledger/trial-balance` — balanced=true after net-zero postings
