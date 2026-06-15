ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS mode VARCHAR(10) NOT NULL DEFAULT 'TEST';

CREATE INDEX IF NOT EXISTS idx_customers_merchant_mode_created
    ON customers (merchant_id, mode, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_customers_merchant_mode_email
    ON customers (merchant_id, mode, email);
