-- Phase PPC1: link new prepaid cards to card programs and cardholders.
--
-- Columns are nullable for additive compatibility with existing simulator cards.
-- New card creation requires these values at the service/API boundary.

ALTER TABLE virtual_card
    ADD COLUMN program_id VARCHAR(32) REFERENCES card_program (program_id),
    ADD COLUMN issuer_partner_id VARCHAR(32) REFERENCES issuer_partner (issuer_partner_id),
    ADD COLUMN cardholder_id VARCHAR(32) REFERENCES cardholder (cardholder_id);

CREATE INDEX idx_virtual_card_program ON virtual_card (program_id);
CREATE INDEX idx_virtual_card_cardholder ON virtual_card (cardholder_id);
CREATE INDEX idx_virtual_card_issuer_partner ON virtual_card (issuer_partner_id);
