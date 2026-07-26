ALTER TABLE card_authorization
    ADD COLUMN IF NOT EXISTS settled_amount NUMERIC(38, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS settled_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS card_clearing_event (
    clearing_event_id VARCHAR(40) PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    rail_payment_id VARCHAR(64) NOT NULL,
    movement_type VARCHAR(40) NOT NULL,
    card_id VARCHAR(32) NOT NULL REFERENCES virtual_card(card_id),
    auth_id VARCHAR(40) REFERENCES card_authorization(auth_id),
    merchant_id VARCHAR(64) NOT NULL,
    mode VARCHAR(10) NOT NULL,
    amount NUMERIC(38, 8) NOT NULL CHECK (amount > 0),
    currency VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_card_clearing_event_card
    ON card_clearing_event (card_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_card_clearing_event_merchant_mode
    ON card_clearing_event (merchant_id, mode, created_at DESC);
