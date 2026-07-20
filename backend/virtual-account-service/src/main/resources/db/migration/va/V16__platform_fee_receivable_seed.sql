-- Familiar platform account for gateway settlement fee accruals in the local
-- TEST/USD stack. Positive-fee gateway settlements fail fast if this account is
-- missing, so seed the common simulator account by default.

INSERT INTO ledger_account (
    ledger_account_id, mode, ledger_account_role, org_id, merchant_id, provider_id,
    ledger_account_type, asset, asset_class, scale, normal_balance,
    balance, status
) VALUES (
    'va_platform_fee_rcv_usd', 'TEST', 'PLATFORM', NULL, NULL, NULL,
    'PLATFORM_FEE_RECEIVABLE', 'USD', 'FIAT', 2, 'DEBIT',
    0, 'ACTIVE'
)
ON CONFLICT (ledger_account_id) DO NOTHING;
