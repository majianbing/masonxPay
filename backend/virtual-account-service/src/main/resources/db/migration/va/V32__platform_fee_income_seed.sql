-- Platform revenue account for prepaid-card fee postings in the local TEST/USD stack.
-- PLATFORM_FEE_RECEIVABLE is reserved for gateway settlement receivable accruals;
-- prepaid card creation fees recognize platform fee income directly for now.

INSERT INTO ledger_account (
    ledger_account_id, mode, ledger_account_role, org_id, merchant_id, provider_id,
    ledger_account_type, asset, asset_class, scale, normal_balance, account_class,
    balance, status
) VALUES (
    'va_platform_fee_income_usd', 'TEST', 'PLATFORM', NULL, NULL, NULL,
    'FEE_INCOME', 'USD', 'FIAT', 2, 'CREDIT', 'REVENUE',
    0, 'ACTIVE'
)
ON CONFLICT (ledger_account_id) DO NOTHING;
