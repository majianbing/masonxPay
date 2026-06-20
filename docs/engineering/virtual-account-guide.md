# Virtual Account — Design and Implementation Guide

# Red Lines
- Correctness and idempotency.
- Precision: the foundational layer supports high-precision decimals (up to 8 places, e.g. `0.00000000`) for future crypto support. Keep precision requirements separate from the business layer — e.g. card payments use `0.00` (2 decimals), crypto uses `0.00000000` (8 decimals).
- Defend against manual data changes at the DB layer: protect balance updates with a balance signature, and allow balances to be modified only through the VA APIs.
- The Postgres database is the single source of truth.
- Use `SELECT ... FOR UPDATE` for stable, serialized concurrent updates.
- ID utils for new business code, DO NOT use UUID anymore, use snowflake ID with business prefix, for example `ac_{snowflakeId}`

# Boundary
- Apply an anti-corruption layer (ACL) at the Kafka consumer edge: the inbound adapter maps `contracts` events to VA-native commands; domain/ledger code never imports `contracts`. Cross-service seam decisions live in `docs/refactor/modularize-backend.md`.

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

- **Asset** (`normal_balance = DEBIT`): `CASH`, `WALLET` — value you hold; normally cannot go negative.
- **Liability** (`normal_balance = CREDIT`): `CREDIT_LINE` — balance = amount owed; bounded by `credit_limit`; available credit = `credit_limit − outstanding`.
- **Receivable** (`normal_balance = DEBIT`): `RECEIVABLE` — money owed to the platform by a customer. Balance = outstanding amount. Settlement (customer pays) and write-off (财务核销) are both ledger entries that reduce this balance.
- **Reserve** (`normal_balance = DEBIT`): `RESERVE` — merchant reserve funds held by the platform. Fixed reserve and rolling reserve are identical at the ledger layer — both move $X into this account. The calculation of $X (fixed amount vs percentage) is business-layer policy, not a ledger concern.
- Platform/external accounts (`FEE_INCOME`, `CLEARING`, `SUSPENSE`, provider mirror) use the same primitive with the appropriate role.

Balance is **derived/materialized** from append-only entries, respecting `normal_balance`. Never `UPDATE`/`DELETE` an entry; corrections and reversals are new compensating entries.

## Cards are instruments, not accounts

A card (debit or credit) is an **access instrument** bound to an underlying account. Debit vs credit differs only in what the card points at:

- **Debit card** → asset account (`CASH`/`WALLET`); spend decreases balance; cannot go below 0.
- **Credit card** → liability account (`CREDIT_LINE`); spend increases outstanding; up to `credit_limit`.

Same model, same provider mechanics — only `normal_balance` + limit policy change.

## Asset, not currency

Use `asset` (USD, BTC, USDC…) + `asset_class` (FIAT|CRYPTO) + `scale`, not ISO-4217 `currency`. Precision is asset-driven (cards 2dp, crypto 8dp), stored as `NUMERIC(38, scale)`, never float. (See Red Lines.)

## Customer / cardholder

The customer is an **external identity**, modeled as a relationship — not a scope level:

- `va_account` — the ledger account (core).
- `va_customer` — a **thin** local reference to an external identity (`external_customer_id` + minimal display ref). PII lives outside VA.
- `va_account_customer` — the link `(account_id, customer_id, role: PRIMARY|AUTHORIZED)`; many-to-many.

## Schema sketch

```
va_account                 -- generic ledger account; ONLY universal columns
  account_id    VARCHAR(32) pk                     -- snowflake ID, e.g. ac_{snowflakeId}
  mode          TEST|LIVE
  account_role  TENANT|PLATFORM|EXTERNAL
  org_id, merchant_id      (set for TENANT)        provider_id (set for EXTERNAL)
  account_type  CASH|WALLET|CREDIT_LINE|RECEIVABLE|RESERVE|FEE_INCOME|CLEARING|SUSPENSE
  asset         USD|BTC|USDC...  + asset_class FIAT|CRYPTO + scale
  normal_balance DEBIT|CREDIT
  balance        NUMERIC(38, scale)                -- materialized; updated atomically with every entry
  frozen_balance NUMERIC(38, scale) DEFAULT 0      -- funds on hold; see Holds section below
  -- available_balance = balance - frozen_balance  (derived, never stored)
  status        ACTIVE|FROZEN|CLOSED

va_ledger_entry            -- append-only, immutable, double-entry; NEVER UPDATE or DELETE
                           -- partitioned: HASH(account_id), 64 buckets
  entry_id         VARCHAR(32)        -- snowflake ID, e.g. le_{snowflakeId}
  transaction_id   VARCHAR(32)        -- groups the balanced entry set
  account_id       VARCHAR(32)
  direction        DEBIT|CREDIT
  amount           NUMERIC(38, scale)
  asset            VARCHAR(20)
  balance_after    NUMERIC(38, scale) -- running balance snapshot after this entry
  balance_signature VARCHAR(64)       -- HMAC-SHA256 tamper-evident chain; see Balance Integrity section
  source_event_id  VARCHAR(64) UNIQUE -- DB-level idempotency
  status           PENDING|POSTED|REVERSED
  created_at       TIMESTAMPTZ

credit_line_profile (account_id fk, credit_limit, cycle, ...)   -- type extensions
wallet_profile      (account_id fk, chain, address, ...)         (class-table inheritance)
cash_profile        (account_id fk, payout_config, ...)
```

Type-specific columns live **only** in extension tables — never on `va_account`. Per-type policy (sign, can-go-negative, precision, allowed ops) lives in code, selected by `account_type`.

## Holds & available balance

Holds (冻结/解冻) do not move money — they constrain availability. The fundamental layer handles this with a single `frozen_balance` field on `va_account`, updated atomically under `SELECT FOR UPDATE`:

- **Freeze (冻结):** `frozen_balance += amount`
- **Release (解冻):** `frozen_balance -= amount`
- **Available balance (可用余额):** `balance - frozen_balance` — derived on read, never stored

Individual hold metadata (reason, expiry, dispute reference) is business-layer concern and lives above the fundamental layer. The fundamental layer only tracks the total frozen amount.

## Financial concepts — ledger mapping

| Concept | Ledger representation |
|---|---|
| 余额 (Ledger Balance) | `va_account.balance` |
| 可用余额 (Available Balance) | `balance − frozen_balance` (derived) |
| 冻结余额 (Reserved/Held Balance) | `va_account.frozen_balance` |
| 冻结资金 (Hold) | `frozen_balance += amount` (no ledger entry) |
| 解冻资金 (Release) | `frozen_balance -= amount` (no ledger entry) |
| 挂账 (Receivable/Outstanding) | Balance on a `RECEIVABLE` account |
| 销账 - 客户补款 (Settlement) | DEBIT `CASH`, CREDIT `RECEIVABLE` |
| 销账 - 财务核销 (Write-off) | DEBIT `BAD_DEBT` (PLATFORM), CREDIT `RECEIVABLE` |
| 保证金 (Reserve) | Balance on a `RESERVE` account |

# Internal & External Accounts

The ledger is a **closed** double-entry system: every transaction's debits and credits net to zero. Real money enters/leaves via providers, banks, chains — so the outside party is represented by an **EXTERNAL mirror account** ("a window to the outside world") that absorbs the other side of every boundary-crossing movement. Its balance is your live net position with that party.

Example — VCC **credit**-card loop (TENANT cardholder credit line, PLATFORM books, EXTERNAL provider window):

```
A. Card draw $100   TENANT credit line +100   |  EXTERNAL provider +100      (net 0)
B. Fee 2%           TENANT credit line +2      |  PLATFORM fee income +2      (net 0)
C. Settle provider  EXTERNAL provider -100     |  PLATFORM clearing  -100     (net 0)
D. Cardholder repay TENANT credit line -102    |  PLATFORM clearing  +102     (net 0)
END: tenant 0, provider 0, kept +$2 fee, net cash +$2
```

Debit card is the same loop with step A flipped (cardholder asset account **−100**) and no repayment step.

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

# Ledger Entry Sharding

`va_ledger_entry` uses **flat `HASH(account_id)` partitioning with 64 buckets** — no time-based partition layer.

**Why `account_id`:** all performance-critical queries (balance calculation, account history, reconciliation) are scoped by account. Entries for one transaction naturally span multiple accounts/buckets — that is acceptable because cross-transaction joins across accounts are never needed.

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
  = va_account.balance   -- must always hold
```

**Atomic posting.** Every entry insert and the corresponding `va_account.balance` update happen in the same DB transaction — no entry without a balance update, no balance update without an entry.

Because entries are immutable and timestamped, the equation holds for any window:

```
opening = SUM(entries WHERE created_at <  period_start)
credits = SUM(credit entries WHERE created_at IN period)
debits  = SUM(debit  entries WHERE created_at IN period)
closing = opening + credits − debits   ← guaranteed by immutability
```

## Balance signature (tamper-evident hash chain)

Direct DB edits to `va_account.balance` or `va_ledger_entry.amount` must be detectable even if Postgres-level controls are bypassed. Each `va_ledger_entry` carries a `balance_signature`:

```
balance_signature = HMAC_SHA256(
  app_secret,
  account_id || amount || direction || balance_after || txid || prev_signature
)
```

`prev_signature` chains every entry to the one before it. Any tampering with a past entry or the account balance breaks every subsequent signature in the chain.

```
entry_1: sig_1 = HMAC(... || balance_after_1 || sig_0)
entry_2: sig_2 = HMAC(... || balance_after_2 || sig_1)
entry_3: sig_3 = HMAC(... || balance_after_3 || sig_2)
```

**Required schema additions:**

```
va_ledger_entry
  balance_after      NUMERIC(38, scale)   -- running balance snapshot after this entry
  balance_signature  VARCHAR(64)          -- HMAC-SHA256 hex
```

**On every entry insert:**
1. Read the previous entry's `balance_signature` for this account (within the same `SELECT FOR UPDATE` lock).
2. Compute the new signature.
3. Insert entry + update `va_account.balance` atomically.

**Validation:** on any read or reconciliation pass, recompute the chain from `sig_0` and compare. A mismatch pinpoints the tampered entry.

**Key management rules:**
- `app_secret` lives in the application (environment secret / KMS); never stored in the DB.
- The app DB role must have no `UPDATE`/`DELETE` on `va_ledger_entry` and no direct `UPDATE balance` on `va_account` — enforced via `REVOKE`. The signature is defense-in-depth for higher-privilege DB compromise.

**What this catches:** direct balance edits, deleted entries, amount tampering.  
**What this does not catch:** an attacker who also has the `app_secret` and rewrites the chain — that is a key management problem, not a schema problem.

