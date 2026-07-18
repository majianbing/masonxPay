# Ledger Accounting Layer — Naming and Posting Rules Refactor

Status: **Implementation in progress on branch `refactor/ledger-accounting-layer`.**

This plan follows the accounting-grade double-entry refactor. The current ledger is mechanically stronger now: holds are ledger-native, `frozen_balance` is removed, account balances are projected from posted entries, and posting goes through the ledger engine. The next upgrade is semantic and architectural: make the code and schema names match the accounting model, and separate business posting rules from the low-level posting engine.

## Target Architecture

```text
External Event / API Command
        |
        v
Application Service
        |
        v
Posting Rule / Accounting Policy
        |
        v
LedgerPostingCommand(s)
        |
        v
Ledger Posting Engine
        |
        v
Journal Header (va_transaction)
        |
        v
Ledger Entries (va_ledger_entry)
        |
        v
Ledger Account Projection (ledger_account)
```

The key distinction is:

- A **business event** is something that happened in the product domain: card authorization, VCC funding, rail settlement, payout, reserve release.
- A **posting rule** translates that event into one or more accounting commands.
- An **accounting command** is an in-memory instruction to post one balanced journal.
- A **journal** is the persisted accounting header, currently `va_transaction`.
- A **ledger entry** is one debit/credit leg, currently `va_ledger_entry`.
- A **ledger account** is an accounting primitive with normal balance, asset, scope, status, and current balance projection.
- A **wallet** is a business aggregate built above ledger accounts; it is not the ledger account itself.

## Goals

- Rename `PostTransaction` to `LedgerPostingCommand` so the code no longer confuses an in-memory posting request with the persisted journal header.
- Introduce posting rule classes that own business formulas and account-selection policy.
- Keep `LedgerPostingService` focused on the mechanical ledger engine responsibilities only.
- Rename Java domain types from `VaAccount` to `LedgerAccount`.
- Rename the DB table `va_account` to `ledger_account`.
- Use a clean local/dev DB reset path instead of production-compatible rename migrations.
- Preserve the existing accounting guarantees: net-zero validation, account scope validation, sorted locking, append-only entries, materialized balance projection, and HMAC chain verification.

## Non-Goals

- No production data migration or compatibility shim. Local/dev databases may be dropped and rebuilt.
- No merchant dashboard UI in this refactor. This prepares the accounting layer that dashboard APIs should consume later.
- No wallet product model yet. Wallet can be introduced after ledger account naming is cleaned up.
- No DB rename for `va_transaction` or `va_ledger_entry` in this pass unless review explicitly expands scope.
- No change to business behavior for VCC funding, authorization holds, reversals, settlement, statements, or trial balance.

## Naming Decisions

| Current | Proposed | Reason |
|---|---|---|
| `PostTransaction` | `LedgerPostingCommand` | It is a command to post one balanced journal, not the persisted transaction itself. The name ties it directly to the posting engine contract. |
| `EntryDraft` | `AccountingEntryDraft` | It is a pre-persistence debit/credit leg. The suffix avoids confusion with persisted `LedgerEntry`. |
| `VaAccount` | `LedgerAccount` | The row represents an accounting account, not a wallet or user account. |
| `AccountRepository` | `LedgerAccountRepository` | Makes ownership explicit and avoids generic "account" overload. |
| `AccountType` | `LedgerAccountType` | Types such as `PREPAID_CARD_HOLD`, `FEE_INCOME`, `CLEARING`, `TAX_PAYABLE` are ledger-account categories. |
| `AccountRole` | `LedgerAccountRole` | Scope class of the ledger account: tenant, platform, external. |
| `AccountStatus` | `LedgerAccountStatus` | Posting eligibility belongs to the ledger account. |
| `va_account` | `ledger_account` | DB table should express the accounting primitive directly. |
| `va_ledger_entry.account_id` | `ledger_account_id` | Recommended for clean semantics because clean DB reset is acceptable. |

`LedgerPostingService` stays as-is. It already behaves like the engine, but keeping the Spring service name avoids extra churn.

## Wallet Boundary

`ledger_account` should not know that it is part of a wallet. It should know only:

- mode
- account role
- org / merchant / provider scope
- ledger account type
- asset and scale
- normal balance
- current projected balance
- status

Wallet is a business aggregate above the ledger:

```text
MerchantWallet
  - available ledger account
  - hold ledger account
  - reserve ledger account
  - pending settlement ledger account, if needed later
```

Wallet reads should derive product-facing fields from ledger accounts:

```text
available = balance(available ledger account)
held      = balance(hold ledger account)
reserved  = balance(reserve ledger account)
total     = available + held + reserved
```

Wallet writes should become business commands/events that are translated by posting rules:

```text
WalletHoldRequested
  -> WalletHoldPostingRule
  -> LedgerPostingCommand
     DR Merchant Hold
     CR Merchant Available
```

This keeps product semantics out of the ledger engine while preserving accounting-grade auditability.

## Posting Rules Layer

Today, application services build `AccountingEntryDraft` lists directly before calling the ledger. That works, but it spreads accounting policy across services.

The target split:

```text
Application Service:
  - validates product workflow
  - loads product aggregate
  - calls posting rule
  - submits returned LedgerPostingCommand(s) through the event-level facade

Posting Rule:
  - selects ledger accounts
  - calculates amounts
  - applies merchant fee / reserve / tax / settlement policy
  - produces one or more balanced LedgerPostingCommand objects

Ledger Posting Engine:
  - validates command shape
  - validates net-zero and asset consistency
  - validates ledger account scope/status
  - locks ledger accounts in deterministic order
  - computes balance projection
  - appends ledger entries
  - updates ledger_account.balance
  - signs/verifies per-account HMAC chain
```

The engine must not understand business formulas such as:

- merchant fee percentage
- reserve ratio
- tax policy
- interchange/network fee model
- card settlement lifecycle policy
- payout availability rules

Those belong in posting rules.

Initial rule classes:

- `VccFundingPostingRule`
- `CardAuthHoldPostingRule`
- `CardSettlementPostingRule`
- `RailSettlementPostingRule`

Possible interface shape:

```java
public interface PostingRule<T> {
    List<LedgerPostingCommand> build(T event);
}
```

Posting rules return `List<LedgerPostingCommand>` from day one. For simple flows, the rule may return one command. For future flows, one business event can return multiple journal commands without changing the engine contract. Event-driven callers submit the whole list through `LedgerFacade#postAllIfNew`, so the inbox reserves the business event once and every generated journal posts in the same transaction.

## Schema Strategy

Because local/dev DB reset is allowed, prefer rewriting the VA Flyway history instead of adding compatibility rename migrations.

Recommended schema cleanup:

- Rewrite `V3__va_account.sql` as `V3__ledger_account.sql`, creating `ledger_account` directly because the whole local/dev DB will be rebuilt.
- Update every later migration that references `ledger_account`.
- Replace `va_ledger_entry.account_id` with `ledger_account_id`.
- Update indexes, constraints, comments, and seed data names.
- Update HMAC signature input field names in code only if the canonical string currently includes key labels. If the canonical string is positional, keep behavior stable except for the value source field rename.

Tables after this pass:

```text
ledger_account
va_transaction
va_ledger_entry
virtual_card
va_inbox
```

We can revisit whether `va_transaction` should become `journal_entry` and whether `va_ledger_entry` should become `ledger_entry` after this narrower rename lands. Renaming all three now increases churn without solving the most overloaded name.

## Implementation Plan

| Phase | Scope | Verification |
|---|---|---|
| 0 | Review and approve this document. Final decisions recorded: `LedgerPostingCommand`, `AccountingEntryDraft`, keep `LedgerPostingService`, rename `account_id` to `ledger_account_id`, rewrite Flyway history, posting rules return `List<LedgerPostingCommand>`. | Plan approved; no code. |
| 1 | Rename command model: `PostTransaction` -> `LedgerPostingCommand`; update validators, facade, posting service, tests, and docs. | VA tests pass. No behavior change. |
| 2 | Rename Java ledger account domain: `VaAccount` -> `LedgerAccount`; `AccountRepository` -> `LedgerAccountRepository`; constants as agreed. | VA tests pass. No behavior change. |
| 3 | Rename DB table/columns in Flyway history and SQL: `va_account` -> `ledger_account`; `account_id` -> `ledger_account_id` where the column points to a ledger account. | Clean DB migration from empty volume succeeds. Schema inspection confirms old table/column names are gone except historical docs if intentionally retained. |
| 4 | Introduce posting rule package and interfaces returning `List<LedgerPostingCommand>`. Move VCC funding/auth/reversal/settlement entry construction from application services into rule classes. | Existing VCC and rail tests pass; add focused unit tests for each rule's generated debits/credits. |
| 5 | Move rail settlement entry construction into posting rules. Keep application services responsible for workflow/idempotency only. | VA + rail tests pass. |
| 6 | Update docs and diagrams to reflect business event -> posting rule -> accounting command -> ledger engine. | Docs grep shows old names removed or intentionally historical. |
| 7 | Docker clean rebuild and runtime smoke: drop local/dev VA DB volume, run migrations, perform VCC funding/auth/settlement smoke. | Health check passes; Flyway applies cleanly; VCC smoke confirms net-zero journals and signed chains. |

## Risk Areas

| Risk | Mitigation |
|---|---|
| Broad mechanical rename misses SQL string or test fixture | Use `rg` sweeps for `LedgerPostingCommand`, `LedgerAccount`, `ledger_account`, and `ledger_account_id`; run VA + rail tests. |
| Rewriting Flyway history breaks an existing local DB | Explicitly require Docker/local DB cleanup before running this branch. This is accepted for hot development. |
| Renaming `account_id` too broadly changes product account IDs outside the ledger | Only rename columns/fields that reference ledger accounts. Do not rename merchant account, provider account, user account, or payment account identifiers. |
| Posting rule extraction accidentally changes journal shape | Add rule-level tests that assert exact generated debit/credit legs for current VCC and rail flows. |
| Dashboard/API work starts before naming stabilizes | Finish this refactor before merchant dashboard VA integration. Product-facing APIs should expose wallet/card concepts, while audit pages can expose ledger accounts and journals. |

## Review Decisions

1. Final command name: `LedgerPostingCommand`.
2. Final draft leg name: `AccountingEntryDraft`.
3. Keep `LedgerPostingService`; do not rename it to `LedgerPostingEngine`.
4. Rename `va_ledger_entry.account_id` to `ledger_account_id` now because clean DB reset is allowed.
5. Rewrite Flyway history directly instead of adding `V11`.
6. Posting rules return `List<LedgerPostingCommand>` from day one to support future multi-journal events.

## Recommended Approval Scope

Approve Phases 1-4 as one refactor branch:

1. Rename `PostTransaction` to `LedgerPostingCommand`.
2. Rename Java and DB ledger account concepts.
3. Introduce posting rules for current VCC flows.
4. Prove clean DB rebuild and existing VA/rail tests.

Defer wallet aggregate implementation to the merchant dashboard/API design. This refactor should prepare the ledger foundation, not add a new wallet product surface yet.

## Progress

- Branch created: `refactor/ledger-accounting-layer`.
- Phase 1 complete: `PostTransaction` renamed to `LedgerPostingCommand`; validators, facade, posting service, tests, and docs updated.
- Phase 2 complete: Java ledger account domain renamed from `VaAccount`/`AccountRepository`/`AccountType`/`AccountRole`/`AccountStatus` to `LedgerAccount`/`LedgerAccountRepository`/`LedgerAccountType`/`LedgerAccountRole`/`LedgerAccountStatus`.
- Phase 3 complete in source: Flyway history rewritten for clean local/dev DB rebuild; `V3__ledger_account.sql` now creates `ledger_account` directly; `va_ledger_entry.account_id` is now `ledger_account_id`; old `V10__drop_frozen_balance.sql` removed because frozen-balance cleanup is folded into history.
- Phase 4/5 complete in source: posting-rule package added; VCC funding, close sweep, issuer auth hold, card sale/reversal settlement, bank rail settlement/return, and gateway settlement entry construction moved out of application services into posting rules returning `List<LedgerPostingCommand>`. Added direct posting-rule tests for the current debit/credit formulas.
- Review polish complete: event-driven callers now use `LedgerFacade#postAllIfNew`, so one business event reserves the inbox once and can post multiple generated journals atomically. Added `LedgerFacadeTest` coverage for posting every command and skipping all commands on duplicate delivery.
- Verification so far: `mvn -pl virtual-account-service -am test` passed with 85 VA tests; `mvn -pl virtual-account-service,rail-service -am test` passed with 85 VA tests and 45 rail tests; clean Docker DB rebuild applied Flyway V1-V9 successfully; runtime VCC smoke confirmed idempotent funding, issuer auth hold, and net-zero app-posted VCC journals.
- Related docs updated: VA engineering guide, MR E2E guide, backend package example, multi-rail plan, ledger-completeness plan, and the supersession note in the accounting-grade double-entry refactor doc now match the ledger-account and posting-rule architecture.
- Remaining verification: user-run `bench/va/` scripts are still in progress outside this pass.

## Changelog

- 2026-07-18: Draft created for review before starting the ledger-account naming and posting-rules refactor.
- 2026-07-18: Review decisions recorded and implementation started on `refactor/ledger-accounting-layer`; Java/schema renames and posting-rule extraction completed in source; VA + rail Maven suites pass; clean Docker Flyway/schema smoke and VCC runtime smoke pass.
- 2026-07-18: Review polish added `LedgerFacade#postAllIfNew` for event-level multi-command posting and direct posting-rule tests for current journal formulas.
- 2026-07-18: Active docs updated for `ledger_account`, `ledger_account_id`, `LedgerPostingCommand`, posting rules, and ledger-native hold accounts.
