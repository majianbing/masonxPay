ALTER TABLE virtual_card
    ADD COLUMN card_token_id VARCHAR(64);

UPDATE virtual_card
SET card_token_id = 'legacy_' || card_id
WHERE card_token_id IS NULL;

ALTER TABLE virtual_card
    ALTER COLUMN card_token_id SET NOT NULL;

CREATE UNIQUE INDEX uq_virtual_card_token_id ON virtual_card (card_token_id);

CREATE INDEX idx_virtual_card_token_active ON virtual_card (card_token_id, status)
    WHERE status = 'ACTIVE';
