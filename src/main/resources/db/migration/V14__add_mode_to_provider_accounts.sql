-- Connectors are now scoped by mode: TEST keys live under TEST, LIVE keys under LIVE.
-- Existing connectors are defaulted to TEST (safe — use them only with test card tokens).
ALTER TABLE provider_accounts ADD COLUMN mode VARCHAR(10) NOT NULL DEFAULT 'TEST';

-- The old primary uniqueness was (merchant_id, provider).
-- With mode added, each merchant can have one primary per provider per mode.
DROP INDEX idx_provider_accounts_primary;
CREATE UNIQUE INDEX idx_provider_accounts_primary
    ON provider_accounts(merchant_id, provider, mode)
    WHERE is_primary = true AND status = 'ACTIVE';

CREATE INDEX idx_provider_accounts_merchant_mode ON provider_accounts(merchant_id, mode);
