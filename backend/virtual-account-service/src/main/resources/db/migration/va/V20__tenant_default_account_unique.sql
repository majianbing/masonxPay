-- Default merchant ledger accounts are provisioned asynchronously from gateway
-- merchant.created events. They must be idempotent under Kafka redelivery and
-- concurrent backfill/manual requests.

CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_account_tenant_default_account
    ON ledger_account (merchant_id, mode, asset, ledger_account_type)
    WHERE ledger_account_role = 'TENANT'
      AND ledger_account_type IN ('CASH', 'WALLET');
