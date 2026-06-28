ALTER TABLE payment_read_models
    ADD COLUMN IF NOT EXISTS payment_method_type VARCHAR(30);

CREATE INDEX IF NOT EXISTS idx_payment_read_models_method
    ON payment_read_models (merchant_id, mode, payment_method_type);
