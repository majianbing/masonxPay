-- Platform fee accrual asset used by gateway settlement fee splits.
--
-- This replaces the previous use of FEE_INCOME as a debit-side fee leg.
-- FEE_INCOME remains available for later true revenue recognition, where it
-- should be credited by a separate revenue/cash recognition journal.

ALTER TYPE ledger_account_type ADD VALUE IF NOT EXISTS 'PLATFORM_FEE_RECEIVABLE';
