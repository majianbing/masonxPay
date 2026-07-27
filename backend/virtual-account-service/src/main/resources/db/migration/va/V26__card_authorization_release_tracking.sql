ALTER TABLE card_authorization
    ADD COLUMN IF NOT EXISTS released_amount NUMERIC(38, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS release_reason VARCHAR(20),
    ADD COLUMN IF NOT EXISTS released_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_card_authorization_open_holds
    ON card_authorization (status, created_at)
    WHERE status = 'AUTHORIZED';
