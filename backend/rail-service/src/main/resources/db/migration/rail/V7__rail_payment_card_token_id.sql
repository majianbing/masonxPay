ALTER TABLE rail_payment
    ADD COLUMN IF NOT EXISTS card_token_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_rail_payment_card_token_id
    ON rail_payment (card_token_id)
    WHERE card_token_id IS NOT NULL;
