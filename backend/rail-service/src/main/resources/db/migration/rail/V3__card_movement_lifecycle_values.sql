-- MR review: make ISO8583 card lifecycle movements explicit.
-- Existing CARD_REVERSAL remains for compatibility, but new code should prefer
-- CARD_AUTH_REVERSAL or CARD_SALE_REVERSAL so auth-hold release and sale voids
-- do not share an ambiguous movement type.

ALTER TYPE rail_movement ADD VALUE IF NOT EXISTS 'CARD_AUTH_REVERSAL';
ALTER TYPE rail_movement ADD VALUE IF NOT EXISTS 'CARD_SALE_REVERSAL';
ALTER TYPE rail_movement ADD VALUE IF NOT EXISTS 'CARD_CLEARING_PRESENTMENT';
ALTER TYPE rail_movement ADD VALUE IF NOT EXISTS 'CARD_SETTLEMENT';
