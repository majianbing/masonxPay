ALTER TABLE payment_tokens
    ADD COLUMN instrument_id UUID;

CREATE INDEX idx_payment_tokens_instrument_id
    ON payment_tokens (merchant_id, instrument_id);
