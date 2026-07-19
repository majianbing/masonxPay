# Ledger Review Hardening — Card Auth Idempotency, Settlement Parking, Merchant Debt

Status: **Complete. Delivered on branch `fix/card-auth-idempotent-replay`, 2026-07-19.**

A production-grade review of the post-refactor ledger (see
`docs/refactor/ledger-accounting-layer-refactor.md`) found three critical/high
gaps in the failure perimeter around the posting engine. This document records
what was fixed, the invariants now enforced, and what was deliberately deferred.

## Fix #1 — Idempotent card authorization (approve-without-hold)

**Defect:** the issuer auth path ignored the idempotency result: a duplicate
event ID silently skipped the hold but still returned APPROVED with a fresh
auth code — an approval backed by no reserved funds.

**Fix:**

- `card_authorization` decision log with `UNIQUE(issuer_id, authorization_id)`.
  The issuer side (card-network-sim, issuer `RAIL_SIM`) mints `authorizationId`,
  unique per distinct authorization, and reuses it on retries.
- Duplicate deliveries **replay the stored decision** — checked before and again
  after taking the account locks (concurrent-delivery race).
- **Fail-closed invariant: APPROVED ⟹ exactly one hold journal.** If the ledger
  inbox reports the hold event consumed while no decision record exists, the
  request declines with `AUTH_STATE_ANOMALY` — never approves unheld.
- Role separation: `CardAuthorizationService` is the issuer-agnostic decision
  core; raw ISO 8583 vocabulary (STAN/RRN, DE39 mapping, DE38 auth codes) stays
  in the rail layer. Cards are resolved by simulator `cardTokenId`, never by
  masked PAN.

**Verification:** unit suite + `bench/va/card` — smoke walk, `replay-storm`
(500 concurrent deliveries of one authorizationId → exactly one hold), and
`auth-rate` throughput, all against the real Postgres ledger.

## Fix #2 — Settlement exception parking (no event ever dropped)

**Defect:** unpostable settlement events were dropped with a log line — handler
lookup misses acked-and-lost the event; unhandled exceptions hit spring-kafka's
default retry-then-skip.

**Fix:**

- `settlement_exception` parking table: every delivery ends **posted, deduped,
  or parked** with payload + reason code. `UNIQUE(event_id)`; redelivery bumps
  `delivery_count`; a redelivery reopens DISCARDED rows but never RESOLVED ones
  (resolved implies posted). An inbox `hasProcessed` pre-check prevents parking
  already-posted duplicates.
- Handlers park known-unpostable events directly; the Kafka `DefaultErrorHandler`
  retries transient failures with exponential backoff and its recoverer parks
  everything else — log-and-skip is unreachable.
- Ops API under `/internal/va/settlement-exceptions`: list/inspect/**retry**
  (re-drives the original handler; idempotent) /discard (note required).
  Prometheus gauge `va_settlement_exceptions_open` for backlog alerting.

**Verification:** unit suites + live park → retry (re-park) → fix condition →
retry (resolve) loop on the bench stack.

## Fix #3 — Platform-books convention and merchant debt

**Defect (two entangled):** (a) bank returns exceeding the merchant's wallet
balance were unpostable — no debt model; (b) WALLET accounts were DEBIT-normal
while settlement journals CREDITed them, so inbound settlements to empty
wallets were rejected outright and to funded wallets *decreased* them.

**Fix — one root cause:** the journal directions were already correct under
platform-books convention; the account labels were wrong.

- `WALLET`, `PREPAID_CARD`, `PREPAID_CARD_HOLD` → **CREDIT-normal platform
  liabilities**. Card-network seed accounts → CREDIT (issuing-side payable
  semantics). `BANK_RAIL_RECEIVABLE` stays a DEBIT-normal asset. The four
  VCC/card posting rules' legs flipped accordingly; bank settle/return journal
  directions unchanged. `PLATFORM_FEE_RECEIVABLE` (DEBIT-normal) now accrues
  gateway settlement fees; `FEE_INCOME` reserved for revenue recognition.
- `MERCHANT_RECEIVABLE` (DEBIT-normal, TENANT, one per merchant/mode/asset via
  partial unique index, race-safe create-if-absent): bank returns are **always
  postable** — wallet drained up to its balance, shortfall booked as merchant
  debt, full amount off the rail receivable.
- **Recoupment:** inbound bank settlements pay down open merchant debt before
  crediting the wallet.
- Accepted race: the wallet balance is read outside the posting locks; a
  concurrent spend invalidates the split, the engine rejects, the event parks
  (fix #2), and a retry recomputes. Rare, visible, self-correcting.
- V7 Flyway history rewritten in place per project convention — **local/dev DB
  reset required** (`docker compose down -v`).

**Verification:** rule-level leg tests for every flipped journal plus waterfall
and recoupment cases; live money story on the bench stack: settle 200 to an
*empty* wallet (posts — the old bug), fund card 150, return 200 → wallet 0 +
`MERCHANT_RECEIVABLE` 150, settle 100 → debt recouped to 50, wallet stays 0;
system-wide trial balance held throughout.

## Deferred (tracked)

- Chart-of-accounts cleanup: rename `CARD_NETWORK_RECEIVABLE` to reflect payable
  semantics; gateway CASH-path conventions; account class dimension
  (asset/liability/income/expense) and period close — review finding #8.
- Internal cash→wallet transfer API (the shape the card bench already uses).
- `CardUnloadPostingRule` — partial withdraw from an active card to the wallet.
- Gateway-settlement recoupment (lands with CASH/WALLET unification).
- Hold expiry and reversal matching keyed on `card_authorization`.
- Debt lifecycle beyond the ledger: limits, notifications, write-off to `BAD_DEBT`.

## Changelog

- 2026-07-19: Fixes #1 and #2 committed (`c463932`) after codex review.
- 2026-07-19: Fix #3 implemented and runtime-verified; codex review added
  `PLATFORM_FEE_RECEIVABLE` (V15) and card-token test alignment.
