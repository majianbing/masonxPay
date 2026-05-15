ALTER TABLE payment_intents
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

DO $$
DECLARE
    i INT;
    suffix TEXT;
BEGIN
    FOR i IN 0..63 LOOP
        suffix := lpad(i::text, 2, '0');
        EXECUTE format('
            ALTER TABLE payment_intents_%s
            ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0
        ', suffix);
    END LOOP;
END $$;
