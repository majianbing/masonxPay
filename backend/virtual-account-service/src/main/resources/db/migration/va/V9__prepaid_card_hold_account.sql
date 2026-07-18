-- Phase AGDE: ledger-native authorization holds for prepaid cards.
--
-- New cards get a paired PREPAID_CARD_HOLD account. The primary PREPAID_CARD
-- account represents available funds; the hold account represents frozen funds.
-- Existing rows are left nullable for additive migration compatibility.

ALTER TYPE ledger_account_type ADD VALUE IF NOT EXISTS 'PREPAID_CARD_HOLD';

ALTER TABLE virtual_card
    ADD COLUMN hold_account_id VARCHAR(32) REFERENCES ledger_account (ledger_account_id);

CREATE INDEX idx_virtual_card_hold ON virtual_card (hold_account_id);
