-- One MERCHANT_RECEIVABLE account per (merchant, mode, asset).
--
-- The account is auto-created on first shortfall by concurrent settlement
-- consumers; this arbiter index makes create-if-absent race-safe
-- (INSERT ... ON CONFLICT DO NOTHING + re-find). Scoped to this type only —
-- other TENANT types (e.g. PREPAID_CARD) legitimately have many accounts
-- per merchant.

CREATE UNIQUE INDEX uq_ledger_account_merchant_receivable
    ON ledger_account (merchant_id, mode, asset)
    WHERE ledger_account_type = 'MERCHANT_RECEIVABLE';
