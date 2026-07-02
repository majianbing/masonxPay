-- Phase AGDE cleanup: frozen_balance is superseded by paired PREPAID_CARD_HOLD
-- accounts (see V9). No code path writes va_account.frozen_balance anymore —
-- authorization holds are now real ledger entries against a paired account.
-- The per-entry va_ledger_entry.frozen_balance snapshot (and its role in the
-- HMAC signature) was only ever a byproduct of the account-level field, so it
-- is retired at the same time. Column drop on the partitioned parent cascades
-- to all va_ledger_entry_* partitions automatically.
--
-- Decision: existing rows are not preserved across this change (test/lab data
-- only at this stage) — no backfill or dual-write transition.

ALTER TABLE va_account DROP COLUMN frozen_balance;
ALTER TABLE va_ledger_entry DROP COLUMN frozen_balance;
