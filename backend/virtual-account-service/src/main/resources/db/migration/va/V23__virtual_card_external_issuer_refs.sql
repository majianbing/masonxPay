-- Phase PPC2: persist issuer-adapter card references.
--
-- These are issuer/processor references only. Full PAN/CVV never belong in
-- virtual_card or any other VA core table.

ALTER TABLE virtual_card
    ADD COLUMN external_issuer_card_id VARCHAR(100),
    ADD COLUMN external_card_token VARCHAR(100);
