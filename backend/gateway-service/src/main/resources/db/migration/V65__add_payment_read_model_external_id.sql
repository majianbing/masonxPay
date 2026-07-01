ALTER TABLE payment_read_models
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

UPDATE payment_read_models prm
SET external_id = pi.external_id
FROM payment_intents pi
WHERE prm.payment_intent_id = pi.id
  AND prm.external_id IS NULL
  AND pi.external_id IS NOT NULL;

DO $$
DECLARE
    i INT;
    suffix TEXT;
BEGIN
    FOR i IN 0..63 LOOP
        suffix := lpad(i::text, 2, '0');

        EXECUTE format('
            UPDATE payment_read_models prm
            SET external_id = pi.external_id
            FROM payment_intents_%s pi
            WHERE prm.payment_intent_id = pi.id
              AND prm.external_id IS NULL
              AND pi.external_id IS NOT NULL
        ', suffix);
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_read_models_external_id
    ON payment_read_models(external_id)
    WHERE external_id IS NOT NULL;
