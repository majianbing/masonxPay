-- Fix #3 (merchant debt): MERCHANT_RECEIVABLE ledger account type.
--
-- DEBIT-normal platform asset, TENANT-scoped: money a merchant owes the
-- platform, booked when a bank return exceeds the merchant's wallet balance
-- and paid down by recoupment from subsequent inbound settlements.
--
-- ALTER TYPE ... ADD VALUE must not share a transaction with statements that
-- USE the new value (Postgres restriction) — the partial unique index that
-- references it lives in V14.

ALTER TYPE ledger_account_type ADD VALUE IF NOT EXISTS 'MERCHANT_RECEIVABLE';
