-- Ledger hardening:
-- 1) Enforce per-account entry_seq uniqueness in the database.
-- 2) Store the HMAC key id used for each ledger-entry signature so historical
--    rows remain verifiable after future key rotation.

ALTER TABLE va_ledger_entry
    ADD COLUMN IF NOT EXISTS signature_key_id VARCHAR(64) NOT NULL DEFAULT 'default';

DO $$
DECLARE
    i INT;
BEGIN
    FOR i IN 0..63 LOOP
        EXECUTE format(
            'CREATE UNIQUE INDEX IF NOT EXISTS va_ledger_entry_%s_account_entry_seq_uq ON va_ledger_entry_%s (ledger_account_id, entry_seq)',
            i, i
        );
    END LOOP;
END $$;
