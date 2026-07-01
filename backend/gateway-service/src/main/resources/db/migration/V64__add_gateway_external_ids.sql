-- Public resource IDs for gateway-owned payment core rows.
--
-- Phase 4 keeps these columns nullable so the migration is additive while
-- application writers are moved over in later phases. Existing rows are
-- backfilled with prefix+numeric IDs. A later migration can enforce NOT NULL
-- after all creation paths assign external_id.

ALTER TABLE IF EXISTS refunds
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

WITH numbered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM refunds
    WHERE external_id IS NULL
)
UPDATE refunds t
SET external_id = 'rf_' || numbered.rn
FROM numbered
WHERE t.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS idx_refunds_external_id
    ON refunds(external_id)
    WHERE external_id IS NOT NULL;

ALTER TABLE IF EXISTS outbox_events
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

WITH numbered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM outbox_events
    WHERE external_id IS NULL
)
UPDATE outbox_events t
SET external_id = 'evt_' || (1000000000000 + numbered.rn)
FROM numbered
WHERE t.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_events_external_id
    ON outbox_events(external_id)
    WHERE external_id IS NOT NULL;

ALTER TABLE IF EXISTS gateway_events
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

WITH numbered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM gateway_events
    WHERE external_id IS NULL
)
UPDATE gateway_events t
SET external_id = 'evt_' || (2000000000000 + numbered.rn)
FROM numbered
WHERE t.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS idx_gateway_events_external_id
    ON gateway_events(external_id)
    WHERE external_id IS NOT NULL;

ALTER TABLE IF EXISTS webhook_deliveries
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

WITH numbered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM webhook_deliveries
    WHERE external_id IS NULL
)
UPDATE webhook_deliveries t
SET external_id = 'whd_' || numbered.rn
FROM numbered
WHERE t.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS idx_webhook_deliveries_external_id
    ON webhook_deliveries(external_id)
    WHERE external_id IS NOT NULL;

-- Legacy unsharded tables still exist in older databases. Add columns there as
-- well, even though runtime routing uses the 64 logical shard tables.
ALTER TABLE IF EXISTS payment_intents
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

WITH numbered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM payment_intents
    WHERE external_id IS NULL
)
UPDATE payment_intents t
SET external_id = 'pi_' || (90000000000000 + numbered.rn)
FROM numbered
WHERE t.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_intents_external_id
    ON payment_intents(external_id)
    WHERE external_id IS NOT NULL;

ALTER TABLE IF EXISTS payment_requests
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

WITH numbered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM payment_requests
    WHERE external_id IS NULL
)
UPDATE payment_requests t
SET external_id = 'pr_' || (90000000000000 + numbered.rn)
FROM numbered
WHERE t.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_requests_external_id
    ON payment_requests(external_id)
    WHERE external_id IS NOT NULL;

DO $$
DECLARE
    i INT;
    suffix TEXT;
    shard_offset BIGINT;
BEGIN
    FOR i IN 0..63 LOOP
        suffix := lpad(i::text, 2, '0');
        shard_offset := i::BIGINT * 1000000000000;

        EXECUTE format('
            ALTER TABLE IF EXISTS payment_intents_%s
            ADD COLUMN IF NOT EXISTS external_id VARCHAR(40)
        ', suffix);

        EXECUTE format('
            WITH numbered AS (
                SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
                FROM payment_intents_%s
                WHERE external_id IS NULL
            )
            UPDATE payment_intents_%s t
            SET external_id = ''pi_'' || (%s + numbered.rn)
            FROM numbered
            WHERE t.id = numbered.id
        ', suffix, suffix, shard_offset);

        EXECUTE format('
            CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_intents_%s_external_id
            ON payment_intents_%s(external_id)
            WHERE external_id IS NOT NULL
        ', suffix, suffix);

        EXECUTE format('
            ALTER TABLE IF EXISTS payment_requests_%s
            ADD COLUMN IF NOT EXISTS external_id VARCHAR(40)
        ', suffix);

        EXECUTE format('
            WITH numbered AS (
                SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
                FROM payment_requests_%s
                WHERE external_id IS NULL
            )
            UPDATE payment_requests_%s t
            SET external_id = ''pr_'' || (%s + numbered.rn)
            FROM numbered
            WHERE t.id = numbered.id
        ', suffix, suffix, shard_offset);

        EXECUTE format('
            CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_requests_%s_external_id
            ON payment_requests_%s(external_id)
            WHERE external_id IS NOT NULL
        ', suffix, suffix);
    END LOOP;
END $$;
