-- Adds a deterministic leg discriminator to ledger-entry idempotency.
--
-- source_event_id identifies the upstream business event. source_event_leg
-- identifies the semantic leg for one account inside that event, for example
-- "principal", "fee", "tax", or "default". Posting rules own this value.
-- It must be stable across retries; never use a generated id.

ALTER TABLE va_ledger_entry
    ADD COLUMN IF NOT EXISTS source_event_leg VARCHAR(64) NOT NULL DEFAULT 'default';

DO $$
DECLARE
    i INT;
    idx RECORD;
BEGIN
    FOR i IN 0..63 LOOP
        FOR idx IN
            SELECT indexname
            FROM pg_indexes
            WHERE schemaname = current_schema()
              AND tablename = format('va_ledger_entry_%s', i)
              AND indexdef LIKE 'CREATE UNIQUE INDEX%'
              AND indexdef LIKE '%ledger_account_id%'
              AND indexdef LIKE '%source_event_id%'
              AND indexdef NOT LIKE '%source_event_leg%'
        LOOP
            EXECUTE format('DROP INDEX IF EXISTS %I', idx.indexname);
        END LOOP;

        EXECUTE format(
            'CREATE UNIQUE INDEX IF NOT EXISTS va_ledger_entry_%s_account_source_leg_uq ON va_ledger_entry_%s (ledger_account_id, source_event_id, source_event_leg)',
            i, i
        );
    END LOOP;
END $$;
