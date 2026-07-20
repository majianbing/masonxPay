# Virtual Account — Design and Implementation Guide

# Red Lines
- Correctness and idempotency. Two layers: `va_inbox_event` (PK on `event_id`) is the first line of defense, enforced inside `LedgerFacade#postIfNew` / `LedgerFacade#postAllIfNew` — callers do not call the inbox directly. `UNIQUE(ledger_account_id, source_event_id)` on `va_ledger_entry` is the DB-level safety net — it prevents the same event from posting twice to the same ledger account even if the facade is bypassed via `postDirect`. Both layers are required. Note: `UNIQUE(ledger_account_id, source_event_id)` includes the partition key so it is valid on the HASH-partitioned table; one event legitimately creates entries on multiple accounts with the same `source_event_id` — the constraint is per-account, not global.
- Precision: the foundational layer supports high-precision decimals (up to 8 places, e.g. `0.00000000`) for future crypto support. Keep precision requirements separate from the business layer — e.g. card payments use `0.00` (2 decimals), crypto uses `0.00000000` (8 decimals).
- Defend against manual data changes at the DB layer: protect balance updates with a balance signature, and allow balances to be modified only through the VA APIs.
- The Postgres database is the single source of truth.
- Use `SELECT ... FOR UPDATE` for stable, serialized concurrent updates.
- ID utils for new business code, DO NOT use UUID anymore, use snowflake ID with business prefix, for example `ac_{snowflakeId}`.

# Boundary
- Apply an anti-corruption layer (ACL) at the Kafka consumer edge: the inbound adapter maps `contracts` events to VA-native commands; domain/ledger code never imports `contracts`. Cross-service seam decisions live in `docs/refactor/modularize-backend.md`.
- Gateway PSP/acquirer principal is not a VA balance. In normal payment-gateway mode, merchants own their Stripe/Square/Braintree/Mollie/acquirer accounts and MasonXPay does not hold those funds. A merchant can configure multiple external PSP accounts, each with its own balance, payout timing, reserve/dispute behavior, and reporting semantics. Do not collapse those external balances into one "Virtual Account" balance unless a separate treasury/reconciliation product is explicitly designed.
- Gateway-originated platform fees are the carve-out: the fee MasonXPay is owed or has earned on gateway volume is platform money and may be booked in VA through `PLATFORM_FEE_RECEIVABLE` and later `FEE_INCOME`. That does not make the merchant's PSP principal MasonXPay-held funds.
- Do not reuse today's pooled `EXTERNAL provider_id` mirror-account pattern for bring-your-own PSP balances. That pattern is appropriate when MasonXPay is the counterparty to a rail/network/provider position. Merchant-owned Stripe/Square accounts are segregated merchant buckets; pooling them into one `EXTERNAL:stripe` row would misstate many merchants' funds as one MasonXPay position.

# Account Model

The VA core is a generic **double-entry ledger**. One account primitive represents every scenario — cash, crypto wallet, debit card, credit card, the platform's own books, and external provider mirrors — distinguished by attributes, not by separate models. Business semantics live **above** the ledger as per-type policy; the core stays asset- and semantics-agnostic.

## Two orthogonal dimensions

Ownership/scoping and account classification are independent. Do not stack them into one hierarchy.

- **Ownership (scoping):** `mode → org → merchant`. Applies to TENANT accounts only.
- **Classification:** `account_role` + `account_type` + `normal_balance`.

## Account roles

- **TENANT** — owned by a merchant/org (balances, wallets, credit lines). Scoped by `mode/org/merchant`.
- **PLATFORM** — the operator's own books (fee income, clearing/settlement, suspense). No merchant owner.
- **EXTERNAL** — a mirror of an outside party (provider, bank, blockchain) held inside the ledger so the closed double-entry stays balanced when money crosses the boundary. Carries `provider_id` instead of a merchant.

## Account types & normal balance

The ledger uses **platform-books convention**: accounts state the platform's own
balance sheet. Merchant and cardholder funds held by the platform are platform
**liabilities** (CREDIT-normal); money mirrors and amounts owed to the platform
are **assets** (DEBIT-normal). Balances are always positive magnitudes — the
posting engine applies entry direction against `normal_balance`.

- **Fund-holding liability** (`normal_balance = CREDIT`): `WALLET`, `PREPAID_CARD`, `PREPAID_CARD_HOLD` — merchant/cardholder money the platform owes back; cannot go negative (no overdraft).
- **Asset** (`normal_balance = DEBIT`): `CASH` — external-world money mirror (bank transfer, provider settlement landing).
- **Liability** (`normal_balance = CREDIT`): `CREDIT_LINE` — balance = amount owed; bounded by `credit_limit`; available credit = `credit_limit − outstanding`.
- **Receivable** (`normal_balance = DEBIT`): `RECEIVABLE` — money owed to the platform by a customer. Balance = outstanding amount. Settlement (customer pays) and write-off (财务核销) are both ledger entries that reduce this balance.
- **Merchant debt** (`normal_balance = DEBIT`): `MERCHANT_RECEIVABLE` — TENANT-scoped platform asset booked when a bank-return shortfall exceeds the merchant wallet; auto-created (one per merchant/mode/asset) and recouped from later inbound settlements before the wallet is credited.
- **Reserve** (`normal_balance = DEBIT`): `RESERVE` — merchant reserve funds held by the platform. Owned by the **TENANT** (the reserve is still the merchant's money, just restricted — the merchant can see their reserve balance). Fixed reserve and rolling reserve are identical at the ledger layer — both move $X into this account. The calculation of $X (fixed amount vs percentage) is business-layer policy, not a ledger concern.
- **Bad debt** (`normal_balance = DEBIT`): `BAD_DEBT` — PLATFORM account used as the debit side of a write-off entry (销账 - 财务核销). Represents uncollectable receivables expensed by the platform.
- Platform/external accounts (`PLATFORM_FEE_RECEIVABLE`, `FEE_INCOME`, `CLEARING`, `SUSPENSE`, `BAD_DEBT`, provider mirror) use the same primitive with the appropriate role. Gateway settlement fees accrue into `PLATFORM_FEE_RECEIVABLE` (DEBIT-normal asset); `FEE_INCOME` is reserved for later true revenue recognition.

Balance is **derived/materialized** from append-only entries, respecting `normal_balance`. Never `UPDATE`/`DELETE` an entry; corrections and reversals are new compensating entries.

## Cards are instruments, not accounts

A card (debit or credit) is an **access instrument** bound to an underlying account. Debit vs credit differs only in what the card points at:

- **Debit card** → fund-holding account (`WALLET`/`PREPAID_CARD`); spend decreases balance; cannot go below 0.
- **Credit card** → credit-line account (`CREDIT_LINE`); spend increases outstanding; up to `credit_limit`.

Same model, same provider mechanics — only `normal_balance` + limit policy change.

## Dashboard account visibility

Ledger accounts are the financial primitive; VCCs are a business product layered
on top. A merchant can create many VCCs, and each VCC creates implementation
ledger accounts (`PREPAID_CARD`, `PREPAID_CARD_HOLD`) to keep card funding,
authorization holds, settlement, and close-sweep auditable.

The merchant Virtual Account dashboard should therefore show only merchant-level
financial accounts by default:

- `CASH`
- `WALLET`
- `MERCHANT_RECEIVABLE`

Per-card backing accounts must not be mixed into the main Virtual Account list.
They remain queryable through ledger/account detail APIs for audit and debugging,
but merchant-facing card lifecycle views belong in the VCC domain UI.

## Asset, not currency

Use `asset` (USD, BTC, USDC…) + `asset_class` (FIAT|CRYPTO) + `scale`, not ISO-4217 `currency`. Precision is asset-driven (cards 2dp, crypto 8dp), stored as `NUMERIC(38, scale)`, never float. (See Red Lines.)

## Customer / cardholder

The customer is an **external identity**, modeled as a relationship — not a scope level:

- `ledger_account` — the ledger account (core).
- `va_customer` — a **thin** local reference to an external identity (`external_customer_id` + minimal display ref). PII lives outside VA.
- Future customer-account links should reference `ledger_account_id`; PII and customer ownership semantics live above the ledger.

## Schema sketch

```
ledger_account             -- generic ledger account; ONLY universal columns
  ledger_account_id VARCHAR(32) pk                  -- snowflake ID, e.g. ac_{snowflakeId}
  mode          TEST|LIVE
  ledger_account_role TENANT|PLATFORM|EXTERNAL
  org_id, merchant_id      (set for TENANT)        provider_id (set for EXTERNAL)
  ledger_account_type CASH|WALLET|CREDIT_LINE|RECEIVABLE|RESERVE|PREPAID_CARD|PREPAID_CARD_HOLD|PLATFORM_FEE_RECEIVABLE|FEE_INCOME|CLEARING|SUSPENSE|BAD_DEBT
  -- RESERVE is TENANT-owned: the merchant's restricted funds, visible on their balance sheet
  asset         USD|BTC|USDC...  + asset_class FIAT|CRYPTO + scale
  normal_balance DEBIT|CREDIT
  balance        NUMERIC(38, scale)                -- materialized; updated atomically with every entry
  status        ACTIVE|FROZEN|CLOSED

va_ledger_entry            -- append-only, immutable, double-entry; NEVER UPDATE or DELETE
                           -- partitioned: HASH(ledger_account_id), 64 buckets
  entry_id         VARCHAR(32)        -- snowflake ID, e.g. le_{snowflakeId}
  transaction_id   VARCHAR(32)        -- groups the balanced entry set
  ledger_account_id VARCHAR(32)
  direction        DEBIT|CREDIT
  amount           NUMERIC(38, scale)
  asset            VARCHAR(20)
  entry_seq        BIGINT             -- per-account monotonically increasing counter; defines chain order for HMAC and period accounting (not created_at)
  balance_after    NUMERIC(38, scale) -- running balance snapshot after this entry
  prev_signature   VARCHAR(64)        -- balance_signature of the immediately preceding entry (GENESIS for seq=1); makes each entry self-verifiable without fetching a second row
  balance_signature VARCHAR(64)       -- HMAC-SHA256 tamper-evident chain; see Balance Integrity section
  source_event_id  VARCHAR(64)        -- dedup + traceability; UNIQUE(ledger_account_id, source_event_id) prevents same-event reposts per account
  status           POSTED|REVERSED    -- no PENDING: pre-auth / pending amounts are represented by hold-account ledger entries
  created_at       TIMESTAMPTZ

credit_line_profile (ledger_account_id fk, credit_limit, cycle, ...) -- type extensions
wallet_profile      (ledger_account_id fk, chain, address, ...)      (class-table inheritance)
cash_profile        (ledger_account_id fk, payout_config, ...)
```

Type-specific columns live **only** in extension tables — never on `ledger_account`. Per-type policy (sign, can-go-negative, precision, allowed ops) lives in code, selected by `ledger_account_type`.

## Holds & available balance

Holds (冻结/解冻) are real ledger movements. The ledger no longer has a mutable `frozen_balance` column. Availability is modeled by splitting a product balance across ledger accounts:

- **Available account:** for VCCs this is `PREPAID_CARD` (CREDIT-normal liability).
- **Hold account:** for VCCs this is the paired `PREPAID_CARD_HOLD` (CREDIT-normal liability).
- **Freeze / authorization hold:** `DR PREPAID_CARD`, `CR PREPAID_CARD_HOLD` — liability moves available → held.
- **Release / reversal:** `DR PREPAID_CARD_HOLD`, `CR PREPAID_CARD` — held liability returns to available.
- **Settlement of held funds:** `DR PREPAID_CARD_HOLD`, `CR CARD_NETWORK_RECEIVABLE` — held cardholder liability is extinguished into an obligation to the network (issuing-side payable; type name retained until CoA cleanup).

Balances are positive magnitudes regardless of normal balance, so the user-facing values are:

```text
available = balance(available ledger account)
held      = balance(hold ledger account)
total     = available + held
```

Individual hold metadata (reason, expiry, dispute reference, auth code replay) is business-layer concern and lives above the ledger. The ledger records the amount movement and keeps it auditable through normal journal entries.

**Relationship to entry status:** pre-authorization amounts are not `PENDING` entries. They are normal `POSTED` hold journals that move value from available to held. Reversals and settlement are also `POSTED` compensating journals, usually with `TransactionType.REVERSAL` or the rail settlement type.

## Financial concepts — ledger mapping

| Concept | Ledger representation |
|---|---|
| 余额 (Ledger Balance) | `ledger_account.balance` |
| 可用余额 (Available Balance) | Balance on the available ledger account, e.g. `PREPAID_CARD` |
| 冻结余额 (Reserved/Held Balance) | Balance on the paired hold ledger account, e.g. `PREPAID_CARD_HOLD` |
| 冻结资金 (Hold) | `DR available account / CR hold account` (liability moves available → held) |
| 解冻资金 (Release) | `DR hold account / CR available account` (held liability returns) |
| 挂账 (Receivable/Outstanding) | Balance on a `RECEIVABLE` account |
| 销账 - 客户补款 (Settlement) | DEBIT `CASH`, CREDIT `RECEIVABLE` |
| 销账 - 财务核销 (Write-off) | DEBIT `BAD_DEBT` (PLATFORM), CREDIT `RECEIVABLE` |
| 保证金 (Reserve) | Balance on a `RESERVE` account |

# Internal & External Accounts

The ledger is a **closed** double-entry system: every transaction's debits and credits net to zero. Real money enters/leaves via providers, banks, chains — so the outside party is represented by an **EXTERNAL mirror account** ("a window to the outside world") that absorbs the other side of every boundary-crossing movement. Its balance is your live net position with that party.

Example — VCC **credit**-card loop (TENANT cardholder credit line, PLATFORM books, EXTERNAL provider window):

Notation: each step shows `DEBIT account $amount | CREDIT account $amount`. Every step nets to zero.

```
A. Card draw $100
   DEBIT  EXTERNAL provider   $100  |  CREDIT TENANT credit_line  $100   (net 0)
   (platform records provider receivable; cardholder outstanding increases)

B. Fee 2% = $2
   DEBIT  EXTERNAL provider   $2    |  CREDIT PLATFORM fee_income $2     (net 0)
   (fee embedded in provider receivable; platform earns $2)

C. Provider settles $102
   DEBIT  PLATFORM clearing   $102  |  CREDIT EXTERNAL provider   $102   (net 0)
   (clearing receives $102 from provider; receivable cleared)

D. Cardholder repays $102
   DEBIT  PLATFORM clearing   $102  |  CREDIT TENANT credit_line  $102   (net 0)
   (clearing receives repayment; cardholder outstanding zeroed)

END: EXTERNAL provider = 0, TENANT credit_line = 0, PLATFORM net = +$2 fee
```

Debit card is the same loop with step A flipped: `DEBIT TENANT cash $100 | CREDIT EXTERNAL provider $100` (cardholder asset decreases directly), and no repayment step D.

# Responsibility Boundary — Issuer vs Orchestrator

Two business scenarios, **one ledger model**, switched by a per-program authority flag.

- **A — Issuer (you are the bank).** VA is the **system of record**. VA **authorizes** spends (checks balance/limit, places holds), posts, and reconciles only against its own settlement bank. No external system to defer to.
- **B — Orchestrator (gateway over an external bank/PSP).** The **provider is the system of record**. VA **records** provider-authorized events into a mirror ledger and **reconciles** against the provider's statements; discrepancies resolve in the provider's favor.

**This is not a distributed transaction.** It is a distributed-**state** problem solved by reconciliation: record locally with idempotency → converge to the system of record → reconcile and repair drift. (This is what the idempotent-posting / reconciliation / outbox-replay seam decisions pay for.)

**Design:** `system_of_record = INTERNAL | EXTERNAL` per account-program.
- Ledger mechanics: one implementation for both.
- Authorization path: branches on the flag (VA authorizes vs VA records).
- Reconciliation engine: runs hard for `EXTERNAL`, light for `INTERNAL`.

**VA's responsibility boundary:** VA owns the **ledger** (truth-of-record for A, mirror-of-record for B) **+ the reconciliation engine**. The authorization role and reconciliation intensity switch by the authority flag. Provider issuance/settlement integration lives **outside** VA as a connector (like the gateway's existing connector pattern), feeding VA via events.

# Inbox & Idempotency

## va_inbox_event

`va_inbox_event` is the first line of dedup defense. The check is enforced inside `LedgerFacade#postIfNew` — inbound adapters (Kafka consumers, webhooks) call the facade directly and do not interact with `InboxRepository` themselves. A duplicate event hits a PK conflict, `postIfNew` returns `false`, and the adapter skips without error. The inbox insert and all ledger writes share the same DB transaction — so a crash between them rolls back the inbox row, making the event replayable.

```
va_inbox_event   PARTITION BY HASH(event_id), 8 buckets
  event_id       VARCHAR(64)   PK   -- dedup key; also the partition key
  event_type     VARCHAR(100)
  received_at    TIMESTAMPTZ        -- used by retention cleanup only
```

## Sharding: why HASH(event_id), not HASH(ledger_account_id) or RANGE(received_at)

**Why event_id as shard key:** dedup lookups query by `event_id`. With `HASH(event_id)`, the lookup hits exactly one partition — fast, no cross-partition scan. One event touches multiple accounts, so `ledger_account_id` is not a natural dedup key here.

**Why NOT RANGE(received_at):** dedup queries don't carry `received_at`. Without the partition key in the query, Postgres cannot prune partitions and must scan all of them — a cross-partition scan that makes dedup *slower* than a single table. RANGE(received_at) only helps data retention, not throughput.

**Why 8 buckets (not 64):** the inbox is a simple insert-and-forget table, far lighter than the ledger. 8 buckets distributes write contention sufficiently without incurring unnecessary partition overhead.

## Retention

A periodic DELETE job removes events older than N days, targeting each child partition individually. Postgres names HASH partitions with a numeric suffix:

```sql
DELETE FROM va_inbox_event_0 WHERE received_at < now() - INTERVAL '180 days';
DELETE FROM va_inbox_event_1 WHERE received_at < now() - INTERVAL '180 days';
-- ... through va_inbox_event_7
```

Target child partitions directly (not the parent `va_inbox_event`) — this keeps lock scope to one partition at a time, allows spreading deletes across time, and makes progress observable per shard. No composite partitioning or partition detachment needed.

**Retention window sizing:** size to 2–3× your Kafka retention policy. If Kafka keeps 30 days, a 90-day inbox window provides full replay safety with headroom. 180 days is a conservative default. Events older than the retention window pose negligible replay risk in practice — Kafka will not redeliver events that old.

# Ledger Entry Sharding

`va_ledger_entry` uses **flat `HASH(ledger_account_id)` partitioning with 64 buckets** — no time-based partition layer.

**Why `ledger_account_id`:** all performance-critical queries (balance calculation, account history, reconciliation) are scoped by ledger account. Entries for one transaction naturally span multiple accounts/buckets — that is acceptable because cross-transaction joins across accounts are never needed.

**Why flat (no time layer):** a two-level `RANGE(created_at) + HASH` was considered but rejected. The time layer only pays off for hot/cold archival, which is deferred.

**Hot/cold archival (deferred):** plan is to keep ~180 days in Postgres, then archive older partitions to cheaper storage. 180 days covers standard card chargeback windows (Visa/MC common case is 120 days; some fraud dispute types can reach 540 days — revisit when implementing). When hot/cold is implemented, it is handled at the archival layer with no schema topology change.

# Balance Integrity & Tamper Detection

## The accounting equation invariant

For any account, over any time window:

```
Opening Balance + Credits − Debits = Closing Balance
```

The design guarantees this through two properties:

**Immutable ledger.** `va_ledger_entry` is append-only — no UPDATE, no DELETE ever. Balance can always be recomputed from scratch:

```sql
SUM(amount FILTER direction = CREDIT) − SUM(amount FILTER direction = DEBIT)
  = ledger_account.balance   -- must always hold
```

**Atomic posting.** Every entry insert and the corresponding `ledger_account.balance` update happen in the same DB transaction — no entry without a balance update, no balance update without an entry.

Because entries are immutable and sequenced, the equation holds for any window. Use `entry_seq` — not `created_at` — as the ordering anchor. `created_at` has millisecond precision and is non-deterministic under concurrency; `entry_seq` is a per-account monotonic counter set atomically on insert.

```
opening  = balance_after WHERE entry_seq = (last seq before period_start)
credits  = SUM(amount WHERE direction=CREDIT AND entry_seq IN period range)
debits   = SUM(amount WHERE direction=DEBIT  AND entry_seq IN period range)
closing  = opening + credits − debits   ← guaranteed by immutability + entry_seq ordering
```

## Balance signature (tamper-evident hash chain)

Direct DB edits to `ledger_account.balance` or `va_ledger_entry.amount` must be detectable even if Postgres-level controls are bypassed. Each `va_ledger_entry` carries a `balance_signature`:

```
balance_signature = HMAC_SHA256(
  app_secret,
  ledger_account_id || entry_seq || amount || direction || balance_after || txid || prev_signature
)
```

`prev_signature` chains every entry to the one before it. Any tampering with a past entry or the account balance breaks every subsequent signature in the chain.

```
entry_1: sig_1 = HMAC(... || balance_after_1 || sig_0)
entry_2: sig_2 = HMAC(... || balance_after_2 || sig_1)
entry_3: sig_3 = HMAC(... || balance_after_3 || sig_2)
```

**Schema columns (on `va_ledger_entry`):**

```
balance_after      NUMERIC(38, scale)   -- running balance snapshot after this entry
prev_signature     VARCHAR(64)          -- balance_signature of the preceding entry (GENESIS for seq=1)
balance_signature  VARCHAR(64)          -- HMAC-SHA256 hex of this entry
```

Storing `balance_after` and `prev_signature` in each row makes every entry self-verifiable — no second read needed, and historical verification does not require reading the current account row.

**On every entry insert (`LedgerPostingService`, called via `LedgerFacade`):**
1. `SELECT FOR UPDATE` on `ledger_account` — locks the account row for the duration of the transaction.
2. Validate new balance is non-negative (`computeBalance` — fast in-memory check before any DB reads).
3. Fetch the last entry for this account (`findLastChainHead`).
4. **Tamper check A — balance cross-check:** assert `ledger_account.balance == last_entry.balance_after`. Catches a direct `UPDATE ledger_account SET balance = X` that left `va_ledger_entry` intact. Thrown as `VA_BALANCE_MISMATCH`.
5. **Tamper check B — HMAC chain integrity:** recompute `last_entry.balance_signature` from its stored `prev_signature` and other fields; compare to stored value. Catches a direct `UPDATE va_ledger_entry SET balance_after = X`. Thrown as `VA_CHAIN_TAMPERED`.
6. Compute `entry_seq = last_entry.entry_seq + 1`, new `balance_after`, new `balance_signature` (chaining to `last_entry.balance_signature` as `prev_signature`).
7. Insert the `va_ledger_entry` row (with `prev_signature`) and update `ledger_account.balance` atomically in the same transaction.

**Verification (reconciliation / bench verify endpoint):** each entry is self-verifiable — recompute `balance_signature` from the entry's own stored fields and compare. No external context needed.

**Key management rules:**
- `app_secret` lives in the application (environment secret / KMS); never stored in the DB.
- The app DB role must have no `UPDATE`/`DELETE` on `va_ledger_entry` and no direct `UPDATE balance` on `ledger_account` — enforced via `REVOKE`. The signature is defense-in-depth for higher-privilege DB compromise.

**What this catches:** direct `ledger_account.balance` edits (check A), direct `va_ledger_entry` row edits (check B), deleted entries, and amount tampering.
**What this does not catch:** an attacker who also has the `app_secret` and rewrites the chain — that is a key management problem, not a schema problem.

## Posting API — LedgerFacade

`LedgerFacade` is the only public entry point into the ledger engine from outside the `domain.ledger` package. `LedgerPostingService` (the engine) has a package-private `post()` method — it cannot be called directly from handlers, consumers, or controllers.

Three posting paths:

| Method | Use case | Idempotency |
|---|---|---|
| `postIfNew(tx, eventId, eventType)` | Single-journal event-driven callers and bench duplicate checks | Inbox check enforced here; returns `false` on duplicate |
| `postAllIfNew(commands, eventId, eventType)` | Event-driven callers whose posting rule returns one or more journals | One inbox reservation for the business event; every generated command posts in the same transaction |
| `postDirect(tx)` | Bench endpoints, internal corrections, admin API | No inbox; DB `UNIQUE(ledger_account_id, source_event_id)` is the only backstop |

**All new event-driven handlers should use `postAllIfNew` when they call a posting rule.** Using `postDirect` without a comment explaining why is a red flag in code review.

Inbound adapters (e.g. `SettlementEventConsumer`) are thin: they map the wire event to a domain command and call the handler. They do not interact with `InboxRepository` directly.

## Application-layer invariants (not enforceable by DB)

With HASH partitioning, entries for one transaction span multiple partitions. Two correctness invariants must be enforced at the application layer on every transaction commit — the DB cannot enforce them across partitions:

**Transaction balance (net-zero):** the sum of all DEBIT amounts must equal the sum of all CREDIT amounts across every entry sharing the same `transaction_id`. A transaction that doesn't net to zero must never be committed.

**Asset consistency:** all entries in a transaction must share the same `asset`. A transaction that DEBITs USD and CREDITs BTC would pass net-zero but corrupt the books. Reject at the service layer before writing.
