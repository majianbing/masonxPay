-- Add mode column to rail_payment for TEST/LIVE isolation.
ALTER TABLE rail_payment
    ADD COLUMN mode VARCHAR(4) NOT NULL DEFAULT 'TEST';

CREATE INDEX idx_rail_payment_merchant_mode ON rail_payment (merchant_id, mode, created_at DESC);
