-- Add prev_signature to make each ledger entry self-verifiable.
--
-- prev_signature: the balance_signature of the immediately preceding entry
--   (or GENESIS for the first). Storing it here means a single entry row
--   carries everything needed to verify its own signature — no second read.
--
-- These columns fix the gap where chain integrity could only be checked
-- post-hoc (audit) but never on the hot path before accepting a new entry.

ALTER TABLE va_ledger_entry
    ADD COLUMN prev_signature  VARCHAR(64)    NOT NULL DEFAULT 'GENESIS';
