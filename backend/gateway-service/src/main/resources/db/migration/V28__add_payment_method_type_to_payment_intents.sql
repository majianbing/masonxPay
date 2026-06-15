-- Tracks whether the payment used a card or a redirect-based method (iDEAL, ACH, etc.).
-- Nullable for backward compat — existing rows have no type information; the stale-intent
-- job treats NULL the same as a non-card method (conservative 7-day threshold).
ALTER TABLE payment_intents
    ADD COLUMN payment_method_type VARCHAR(50);
