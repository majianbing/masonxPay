ALTER TABLE card_clearing_event
    ADD COLUMN IF NOT EXISTS original_rail_payment_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS issuer_id VARCHAR(32),
    ADD COLUMN IF NOT EXISTS original_authorization_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_card_clearing_event_rail_payment
    ON card_clearing_event (rail_payment_id);
