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
account                    -- generic ledger account; ONLY universal columns
  account_id    uuid pk
  mode          TEST|LIVE
  account_role  TENANT|PLATFORM|EXTERNAL
  org_id, merchant_id      (set for TENANT)        provider_id (set for EXTERNAL)
  account_type  CASH|WALLET|CREDIT_LINE|FEE_INCOME|CLEARING|SUSPENSE
  asset         USD|BTC|USDC...  + asset_class FIAT|CRYPTO + scale
  normal_balance DEBIT|CREDIT
  balance       NUMERIC(38, scale)                 -- derived/materialized
  status        ACTIVE|FROZEN|CLOSED

ledger_entry               -- append-only, immutable, double-entry
  entry_id, transaction_id (groups the balanced set), account_id,
  direction DEBIT|CREDIT, amount, asset,
  source_event_id  UNIQUE  -- DB-level idempotency
  status PENDING|POSTED|REVERSED, created_at

credit_line_profile (account_id fk, credit_limit, cycle, ...)   -- type extensions
wallet_profile      (account_id fk, chain, address, ...)         (class-table inheritance)
cash_profile        (account_id fk, payout_config, ...)
```

Type-specific columns live **only** in extension tables — never on `account`. Per-type policy (sign, can-go-negative, precision, allowed ops) lives in code, selected by `account_type`.

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

