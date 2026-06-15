ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS mode VARCHAR(10) NOT NULL DEFAULT 'TEST';

CREATE INDEX IF NOT EXISTS idx_subscriptions_merchant_mode
    ON subscriptions (merchant_id, mode, created_at DESC);
