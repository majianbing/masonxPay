CREATE TABLE IF NOT EXISTS card_control_profile (
    card_id VARCHAR(64) PRIMARY KEY REFERENCES virtual_card(card_id),
    merchant_id VARCHAR(64) NOT NULL,
    mode VARCHAR(10) NOT NULL,
    controls_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_card_control_profile_merchant_mode
    ON card_control_profile (merchant_id, mode);
