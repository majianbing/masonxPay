# Accounting-Grade Double-Entry — Refactor Plan

Status: **Implementation in review on branch `refactor/accounting-grade-double-entry`. Code changed locally; not pushed.**

`virtual-account-service`'s posting engine (`LedgerPostingService`, `NetZeroValidator`, `AssetConsistencyValidator`, `InsufficientBalanceValidator`, HMAC hash-chained `va_ledger_entry`) is a genuine double-entry system for everything that goes through it. This plan closes the gaps found by manual review + a second independent pass (codex): call sites that reach around the engine and mutate `va_account.balance` / `va_account.frozen_balance` directly, plus a couple of missing invariant checks inside the engine itself.

## Goals

- Fix the confirmed active correctness bug (stale-balance overwrite on card sale settlement).
- Make the class of bug that caused it structurally impossible at the repository API level.
- Model authorization holds (available → frozen) as real balanced ledger entries instead of a mutable counter, so the hold lifecycle is auditable and tamper-evident like every other movement.
- Close the missing idempotency guarantee on card funding (hard boundary in `CLAUDE.md`).
- Close the missing account-scope (mode/asset/tenant) check inside the posting engine.
- Fix `closeCard` so it cannot silently discard an outstanding hold or leave a "closed" account still postable.

## Non-Goals

- No DB triggers enforcing net-zero or entry/transaction linkage — the partitioned (`HASH(account_id)`, 64 buckets) table layout makes cross-partition trigger enforcement expensive; a scheduled reconciliation job is the intended defense-in-depth instead (Phase 7, optional).
- No change to `va_ledger_entry` immutability (append-only; corrections stay compensating entries, never `UPDATE`/`DELETE`).
- No change to the sharding/partition design ([[project_va_ledger_sharding]] decisions stand).
- No renaming of existing `VirtualCard`/VCC-specific identifiers ([[feedback_domain_naming_card_products]] — new accounts/entities introduced here use generic naming).

## Findings (evidence basis for this plan)

| # | Finding | Location | Severity |
|---|---|---|---|
| 1 | `handleCardSale` posts the ledger entry (which lowers `PREPAID_CARD.balance`), then immediately overwrites `va_account.balance` back to the **pre-post snapshot** while releasing the hold, because it reuses the `cardAccount` object fetched before `ledger.postIfNew(...)` ran. | `backend/virtual-account-service/src/main/java/com/masonx/virtualaccount/domain/CardRailSettlementHandler.java:91-111` | **Critical — active bug** |
| 2 | No ledger entries exist for the available→held transition at all. `IssuerAuthService`, `CardRailSettlementHandler` (auth/reversal paths), and `VirtualCardService.closeCard` all mutate `frozen_balance` via direct `UPDATE`. `AccountRepository.updateBalance` accepts both `balance` and `frozen_balance` with no caller restriction, which is what made Finding 1 possible. | `.../vcc/IssuerAuthService.java:84-85`, `.../domain/CardRailSettlementHandler.java:109-111,133-135`, `.../vcc/VirtualCardService.java:190-191`, `.../domain/ledger/AccountRepository.java:139-143` | **Architectural — highest** |
| 3 | `VirtualCardService.fundCard()` moves funds via `ledger.postDirect()` using a freshly generated Snowflake `txId` as `source_event_id` on every call. `FundVccRequest` carries no client idempotency key, so the DB `UNIQUE(account_id, source_event_id)` backstop never fires on a retry — a retried request double-funds the card. | `.../vcc/VirtualCardService.java:110-136`, `.../vcc/dto/FundVccRequest.java` | **High — real double-charge risk, violates CLAUDE.md's funds-movement idempotency boundary** |
| 4 | `closeCard` force-zeroes `frozen_balance` directly (no entry) after the ledger sweep, and never sets `va_account.status = CLOSED` — only the `VirtualCard` row is marked closed, so the underlying account stays `ACTIVE` and postable. | `.../vcc/VirtualCardService.java:163-192` | **Medium** |
| 5 | No validator checks that a posted entry's target account actually matches the transaction's declared `mode`/`merchantId`, or that the account's own `asset` matches the entry's `asset`. `AssetConsistencyValidator` only checks entries agree with *each other*. | `.../domain/ledger/validator/AssetConsistencyValidator.java`, `.../domain/ledger/PostTransaction.java` (only 3 validators registered total: `NetZeroValidator`, `AssetConsistencyValidator`, `InsufficientBalanceValidator`) | **Medium — tenant/mode isolation gap** |
| 6 | No DB-level proof that all `va_ledger_entry` legs for a `transaction_id` sum to zero, or that every entry has a matching `va_transaction`; enforcement is Java-only. | `db/migration/va/V4__va_ledger_entry.sql`, `V8__va_transaction.sql` | **Low — defense-in-depth** |
| 7 | `EntryStatus.REVERSED` is defined and documented but never set anywhere; every insert hardcodes `POSTED`. | `.../domain/constant/EntryStatus.java`, `.../domain/po/LedgerEntry.java` | **Low — will likely resolve as a side effect of Phase 5** |

## Phase Plan

Legend: pending until approved.

| Phase | Scope | Verification |
|---|---|---|
| 0 | Approve this plan; decide the hold-modeling approach (Phase 5), the `fundCard` idempotency-key shape (Phase 4), and the close-with-open-hold policy (Phase 6). | Plan approved; no code. |
| 1 | Fix the Finding 1 stale-balance overwrite in `handleCardSale`. | `cd backend && mvn -pl virtual-account-service test` passes; add a regression test asserting `va_account.balance` matches the posted entry's `balance_after` after a card-sale settlement. |
| 2 | Narrow `AccountRepository.updateBalance` into `updateBalance(accountId, newBalance)` (ledger-owned, called only from `LedgerPostingService`) and `updateFrozenBalance(accountId, newFrozenBalance)` (used by the remaining direct hold call sites until Phase 5 lands). Makes the Finding 1 bug class structurally impossible elsewhere. | `mvn -pl virtual-account-service test` passes; no call site can pass a stale `balance` into a frozen-only update. |
| 3 | Add a locked-account scope validator. The current validator interfaces are not enough for this check: `TransactionValidator` sees only `tx`, and `EntryValidator` sees only `(draft, account, newBalance)`. This phase must either extend `EntryValidator` to receive the parent `PostTransaction`, or add a new post-lock validator hook that receives `(tx, draft, account, newBalance)`. Required checks: `account.asset()` must equal `draft.asset()`; `account.mode()` must equal `tx.mode()`; for `TENANT` accounts, `account.merchantId()` must equal `tx.merchantId()`. | New validator unit tests; `mvn -pl virtual-account-service test` passes once the local Mockito/JDK blocker below is resolved. |
| 4 | Fix `fundCard()` idempotency: add a request idempotency key to `FundVccRequest`, route through `LedgerFacade.postIfNew` (inbox-backed) instead of `postDirect` + fresh Snowflake `source_event_id`. | Regression test: two identical `fundCard` calls with the same key post exactly one journal. |
| 5 | Model authorization holds as real ledger entries (see Implementation Details below). Retire `frozen_balance` as an independently-mutated column; derive it from the paired hold account. Move `IssuerAuthService` and both `CardRailSettlementHandler` auth/reversal/settlement paths onto `LedgerFacade`. Reversal journals should normally remain `POSTED` and use `TransactionType.REVERSAL`; do not use `EntryStatus.REVERSED` unless a separate void/supersession model is deliberately designed. | New migration + entity/service tests; `mvn -pl virtual-account-service,rail-service -am test` passes once the local Mockito/JDK blocker below is resolved; MR-suite rail settlement tests still pass. |
| 6 | Fix `closeCard`: release any outstanding hold through a real reversal entry (or reject close while a hold exists — see Review Questions), then set `va_account.status = CLOSED` on the card account (and its paired hold account). | Regression test: closing a card with an open hold either auto-releases it via a ledger entry or is rejected with a clear error; closed account rejects further postings via the existing `AccountStatus.ACTIVE` check. |
| 7 | Optional: scheduled reconciliation job — Σdebits = Σcredits per `transaction_id`; `va_account.balance` matches the account's last `balance_after`. No DB triggers. | Job runs in a non-prod profile first; alerts wired before enabling broadly. Can be deferred to Phase 15 platform-maturity work. |

## High-Priority Implementation Details

### Phase 1 — stale-balance fix

`handleCardSale` must use the balance the ledger actually wrote, not the pre-post snapshot, when releasing the hold. Two ways to get there:
- Re-fetch the account (`accountRepo.findByIdForUpdate`) after `ledger.postIfNew` returns `true`, before computing `newFrozen`.
- Or (cleaner, and what Phase 2 pushes toward) stop passing `balance` into the frozen-release call at all — use `updateFrozenBalance(accountId, newFrozen)` so there is no `balance` argument to go stale.

Given Phase 2 lands immediately after, prefer landing Phase 2's narrower method first if sequencing allows; otherwise fix Phase 1 with a re-fetch and let Phase 2 remove the sharp edge.

### Test-environment blocker

Current local verification has a known blocker: `cd backend && mvn -pl virtual-account-service test` fails on Mockito inline Byte Buddy self-attach under the current Homebrew JDK 21 VM. The non-Mockito validator tests run, but Mockito-backed service tests error before exercising product behavior. Before treating future `mvn test` failures as product regressions, fix the Mockito/JDK test configuration or run with the project's known-good Java setup.

### Phase 5 — authorization holds as ledger entries

Recommended shape (fits the existing append-only, HMAC-chained engine without a `PENDING`/mutate-in-place status, which the migration comment explicitly disallows — "NEVER UPDATE or DELETE rows"):

- Every `PREPAID_CARD` account gets a paired hold account, same `accountRole`/`orgId`/`merchantId`/`asset`, created at card-creation time. New `AccountType.PREPAID_CARD_HOLD` (generic naming per [[feedback_domain_naming_card_products]] — not VCC-specific).
- **Auth placement** (`IssuerAuthService.authorize`): `DR PREPAID_CARD_HOLD / CR PREPAID_CARD` for the auth amount, posted through `LedgerFacade`. This is the entry that currently doesn't exist at all.
- **Auth reversal** (`CardRailSettlementHandler.handleCardReversal`, currently posts *nothing*): `DR PREPAID_CARD / CR PREPAID_CARD_HOLD` — a real reversing journal. Keep the ledger entries `POSTED`; use `TransactionType.REVERSAL` to describe the accounting intent.
- **Settlement** (`CardRailSettlementHandler.handleCardSale`): change from `DR CARD_NETWORK_RECEIVABLE / CR PREPAID_CARD` to `DR CARD_NETWORK_RECEIVABLE / CR PREPAID_CARD_HOLD` — releases the hold into the network payable instead of crediting the primary account a second time (which is the root cause Finding 1 exploited).
- `VaAccount.frozenBalance()` becomes a derived read of the paired hold account's balance rather than a stored/mutated column; `availableBalance()` becomes just the primary account's own `balance`.
- Clarify response semantics during this phase: API fields that users read as "total card funds" should not silently become "available only." For VCC responses, expose or document both available funds (primary account balance) and held funds (paired hold account balance), and define whether the existing `balance` field means total (`available + held`) or available.
- This needs a schema migration (new `PREPAID_CARD_HOLD` accounts, a link from card → hold account — likely on `virtual_card` or `va_account` itself) and touches `IssuerAuthService`, `CardRailSettlementHandler`, `VirtualCardService`, `VaAccount`, `AccountResponse`/`VccResponse` DTOs. Highest-risk phase — do it only after Phases 1-4 are shipped and stable.

## Risks

| Risk | Mitigation |
|---|---|
| Phase 5 schema/behavior change touches live card-issuing accounts | Additive migration (new hold accounts alongside existing ones); keep `frozen_balance` column readable and dual-verified against the derived value for a transition window before removing it. |
| Changing `CardRailSettlementHandler` entry shape changes what new transactions look like vs. historical ones | Only applies going forward; historical `va_ledger_entry` rows are immutable and are not rewritten. |
| `fundCard` request-shape change (idempotency key) breaks existing callers | Add the key as optional only for a short migration window. During that window, either derive a deterministic fallback from a caller reference or keep the endpoint unavailable to retrying clients; do not preserve an indefinitely unsafe unkeyed money-movement path. Reject unkeyed requests after caller migration — same additive pattern used in [[project_phase_mr]]/gateway-id-standardization precedent. |
| Rejecting `closeCard` on an open hold surprises callers relying on today's silent-zero behavior | Return a specific error (e.g. `VA_CARD_HAS_OPEN_HOLD`) and document the required release-then-close flow; or auto-release via a real reversal entry — decide in Phase 0. |
| New account-scope validator (Phase 3) false-positives on a legitimate cross-scope posting (e.g. platform fee/clearing accounts) | Write validator tests against every existing `TransactionType` produced today (`CARD_SALE`, `BANK_TRANSFER`, `REVERSAL`, `INTERNAL`) before enabling; scope the merchantId check to `TENANT`-role accounts only, not `PLATFORM`/`EXTERNAL`. |

## Review Questions

1. Hold model: paired `PREPAID_CARD_HOLD` sub-account (recommended above) vs. `PENDING`-status entries against the same account? The latter needs a status-flip mechanism that sits awkwardly against this table's append-only design.
2. Once holds are derived from the ledger, should the `frozen_balance` column on `va_account` be dropped entirely, or kept as a materialized cache synced on every posting (read-performance tradeoff)?
3. For `fundCard`, should the idempotency key be caller-supplied (merchant sends a request ID) or derived server-side from a natural key (e.g. `cardId` + amount + a caller-supplied client reference)?
4. `closeCard` with an open hold: auto-release via a reversal entry, or hard-reject until the caller releases it explicitly?
5. Does the Phase 3 account-scope validator need an escape hatch for any legitimate cross-tenant/cross-mode posting, or is "always same mode, tenant accounts always match merchantId" a safe universal invariant given today's `TransactionType`s?
6. Should `EntryStatus.REVERSED` be removed/deferred until a real void/supersession model exists, or kept as a reserved enum value while reversal journals remain `POSTED`?

## Implementation Review (Phases 1-6, post-Codex)

Independent review of the Codex implementation against this plan (code read + `mvn -pl virtual-account-service -am test`, which passed, 76/76). Phases 1-5 matched the plan; Phase 6 landed ahead of Phase 5 as noted in Progress. Two gaps found that weren't in the Progress notes:

1. **No backfill / inconsistent null-handling for `hold_account_id`.** `V9__prepaid_card_hold_account.sql` adds the column nullable with no backfill for pre-existing cards. `IssuerAuthService.authorize()` and `CardRailSettlementHandler.handleCardSale/handleCardReversal` unconditionally `.orElseThrow()` on the hold-account lookup — a card without a hold account fails auth/settlement/reversal outright. `getCard`/`listCards`/`closeCard` were made null-safe; the money-moving paths were not.
2. **Silent semantic change to `VccResponse.balance`.** Before Phase 5, `balance` meant total card funds (available + held). After, `toResponse` sets `avail = balance` — they're always equal now, since held funds live in a separate account. No `totalBalance` field was added and no doc/changelog note flagged the change, even though this plan's own Phase 5 section called out that exact risk.

Also flagged: `frozen_balance` is now write-once-then-dead (never mutated after account creation) but still fed into every `LedgerEntry.frozen_balance` snapshot and the HMAC signature input (Review Question 2, previously open); `IssuerAuthService.authorize()` ignores `postIfNew`'s return value, so a genuine duplicate ISO8583 auth retry (same RRN/STAN) skips the second hold correctly but still returns a freshly generated `authCode` instead of replaying the original.

## Decision Log

1. **Pre-existing cards without `hold_account_id`:** no backfill needed. Test/lab environment only — existing accounts can be cleaned up rather than migrated. No code change required for this gap.
2. **`VccResponse.balance` semantics:** splitting `balance` (available) and `frozenBalance` (held) is accepted as correct. A `totalBalance` convenience field is a business-layer concern to build later on top of the fundamental account layer, not part of this refactor — noted here for future work, not implemented now.
3. **`frozen_balance` column:** removed entirely (not kept as a cache). Retired from `va_account`, `va_ledger_entry`, `LedgerEntry`, `ChainHead`, `SignatureInput` (and its canonical HMAC string), `AccountResponse`, `TrialBalanceResponse`, and all call sites. No backward-compatibility shim — consistent with decision 1 (test/lab data, not preserved across this change). See `V10__drop_frozen_balance.sql`.
4. **`IssuerAuthService.authorize()` not replaying `authCode` on duplicate retry:** not worth a design change right now. Left as a `TODO` comment at the call site; no behavior change.
5. **Existing ledger rows before `V10`:** no compatibility path needed. `V10__drop_frozen_balance.sql` intentionally changes the HMAC canonical string by removing `frozen_balance`; any pre-V10 ledger row was signed with the old format and is not expected to remain postable after this refactor. Local/test environments should be reset with Docker volume cleanup before applying the migration. Production-grade migration would need a separate compatibility or re-signing plan, but that is explicitly out of scope for this branch.
6. **Historical migration comments:** comments in earlier migrations (`V3`, `V4`, `V5`, `V7`) still describe the old `frozen_balance` model because migrations are historical artifacts. They are superseded by `V9` paired hold accounts and `V10` column removal; current architecture should be read from this refactor note and the latest schema state, not old migration comments in isolation.

## Initial Recommendation

Approve Phases 1-4 first — independent of each other, low risk, no schema changes, fast to ship:
1. Fix the active stale-balance bug.
2. Narrow the repository API so that bug class can't recur.
3. Add the missing account-scope validator.
4. Fix `fundCard` idempotency.

Then design-review Phase 5 in detail (it's the actual "accounting-grade" deliverable this branch is named for) before writing any migration. Phase 6 depends on Phase 5's hold-release mechanism. Phase 7 is optional and can be deferred to the Phase 15 platform-maturity track per `CLAUDE.md`.

## Progress

- Phase 1/2 complete: stale card-sale balance overwrite fixed by splitting ledger-owned balance updates from frozen-only updates.
- Test environment blocker resolved for this module: VA unit tests use Mockito's subclass mock maker and now run locally.
- Phase 3 complete: locked-account scope validator added for asset, mode, and tenant checks.
- Phase 4 complete: `fundCard` now requires an idempotency key and posts through inbox-backed `LedgerFacade#postIfNew`.
- Phase 6 partially complete ahead of Phase 5: `closeCard` now rejects open holds and marks the backing account `CLOSED` instead of force-zeroing balances. Full ledger-native hold release still depends on Phase 5.
- Phase 5 complete: paired `PREPAID_CARD_HOLD` accounts added; new cards create a hold account; issuer auth, auth reversal, and card sale settlement now post balanced hold journals. Application code no longer writes `va_account.frozen_balance`.
- Post-implementation review complete: 76/76 VA unit tests verified passing; confirmed Phases 1-5 match the plan; found and logged the `hold_account_id` backfill gap and the `VccResponse.balance` semantics change (see Implementation Review above).
- `frozen_balance` column fully removed per Decision Log #3: new migration `V10__drop_frozen_balance.sql` drops it from `va_account` and `va_ledger_entry`; `VaAccount`, `LedgerEntry`, `ChainHead`, `SignatureInput` (HMAC canonical string), `AccountResponse`, and `TrialBalanceResponse` updated to match; all call sites and tests updated. `mvn -pl virtual-account-service -am test` passes (76/76) after the change.
- TODO comment added at `IssuerAuthService.authorize()` per Decision Log #4 — no behavior change.
- Review follow-up accepted: V10 does not preserve or verify pre-existing ledger rows; clean Docker volume reset is the migration path for this branch. Refactor doc status corrected from draft/no-code to implementation-in-review.
- Final local verification complete: `mvn -pl virtual-account-service -am test` passed; `mvn -pl virtual-account-service,rail-service -am test` passed after aligning `rail-service` with the existing Mockito subclass mock-maker test configuration used by gateway/VA; Docker VA rebuild applied Flyway V10 successfully; V10 E2E smoke verified idempotent VCC funding, auth hold movement to `PREPAID_CARD_HOLD`, net-zero VCC journals, and no remaining `frozen_balance` columns.

## Changelog

- 2026-07-02: Plan created on branch `refactor/accounting-grade-double-entry` from manual review + independent codex pass findings.
- 2026-07-02: Completed Phases 1-4 and the non-ledger-native part of Phase 6 in local commits; VA unit suite passes.
- 2026-07-02: Completed Phase 5 paired hold-account implementation; VA unit suite passes.
- 2026-07-02: Reviewed Phases 1-6 implementation; logged decisions on the `hold_account_id` backfill gap (accepted, test data), `VccResponse.balance` semantics (accepted, `totalBalance` deferred to business layer), `frozen_balance` column (removed via `V10__drop_frozen_balance.sql`), and the auth-code replay gap (TODO only). VA unit suite passes (76/76) after `frozen_balance` removal.
- 2026-07-02: Updated review notes: branch is implementation-in-review, V10 requires a clean DB reset because old HMAC signatures are intentionally not preserved, and older migration comments are historical/superseded.
- 2026-07-02: Final verification recorded before PR: VA + rail Maven suites pass, Docker Flyway V10 migration applied cleanly, and V10 runtime ledger smoke passed.
