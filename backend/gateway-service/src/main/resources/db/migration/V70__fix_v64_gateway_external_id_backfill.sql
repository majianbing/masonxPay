-- V64 introduced gateway external_id columns before application writers assigned
-- real Snowflake IDs everywhere. Its backfill used small counters or deterministic
-- numeric offsets for refunds, events, webhook deliveries, and legacy/sharded
-- payment core tables. Rewrite only those known V64 artifacts to Snowflake-layout
-- values while leaving already-correct application-generated IDs untouched.
--
-- Layout matches SnowflakeIdGenerator:
--   [41 bits] ms since 2020-01-01 UTC  [10 bits] node id  [12 bits] sequence
-- Node id 63 is reserved for migration backfills.

CREATE OR REPLACE FUNCTION masonx_v64_backfill_snowflake_id(seq bigint, stream_offset_ms bigint)
RETURNS bigint AS $$
DECLARE
    epoch_ms      CONSTANT bigint := 1577836800000;
    backfill_node CONSTANT bigint := 63;
    base_ms       bigint;
    zero_based    bigint;
BEGIN
    zero_based := GREATEST(seq - 1, 0);
    base_ms := (extract(epoch from clock_timestamp()) * 1000)::bigint - epoch_ms;
    RETURN ((base_ms + stream_offset_ms + (zero_based / 4096)) << 22)
        | (backfill_node << 12)
        | (zero_based % 4096);
END;
$$ LANGUAGE plpgsql VOLATILE;

UPDATE refunds
SET external_id = 'rf_' || masonx_v64_backfill_snowflake_id(numbered.rn, 10)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM refunds
    WHERE external_id ~ '^rf_[0-9]{1,6}$'
) numbered
WHERE refunds.id = numbered.id;

UPDATE outbox_events
SET external_id = 'evt_' || masonx_v64_backfill_snowflake_id(numbered.rn, 20)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM outbox_events
    WHERE CASE WHEN external_id ~ '^evt_[0-9]+$'
        THEN substring(external_id from 5)::bigint
        ELSE NULL
    END BETWEEN 1000000000000 AND 1999999999999
) numbered
WHERE outbox_events.id = numbered.id;

UPDATE gateway_events
SET external_id = 'evt_' || masonx_v64_backfill_snowflake_id(numbered.rn, 30)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM gateway_events
    WHERE CASE WHEN external_id ~ '^evt_[0-9]+$'
        THEN substring(external_id from 5)::bigint
        ELSE NULL
    END BETWEEN 2000000000000 AND 2999999999999
) numbered
WHERE gateway_events.id = numbered.id;

UPDATE webhook_deliveries
SET external_id = 'whd_' || masonx_v64_backfill_snowflake_id(numbered.rn, 40)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM webhook_deliveries
    WHERE external_id ~ '^whd_[0-9]{1,6}$'
) numbered
WHERE webhook_deliveries.id = numbered.id;

UPDATE payment_intents
SET external_id = 'pi_' || masonx_v64_backfill_snowflake_id(numbered.rn, 50)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM payment_intents
    WHERE CASE WHEN external_id ~ '^pi_[0-9]+$'
        THEN substring(external_id from 4)::bigint
        ELSE NULL
    END BETWEEN 90000000000000 AND 90999999999999
) numbered
WHERE payment_intents.id = numbered.id;

UPDATE payment_requests
SET external_id = 'pr_' || masonx_v64_backfill_snowflake_id(numbered.rn, 60)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM payment_requests
    WHERE CASE WHEN external_id ~ '^pr_[0-9]+$'
        THEN substring(external_id from 4)::bigint
        ELSE NULL
    END BETWEEN 90000000000000 AND 90999999999999
) numbered
WHERE payment_requests.id = numbered.id;

DO $$
DECLARE
    i INT;
    suffix TEXT;
    shard_min BIGINT;
    shard_max BIGINT;
BEGIN
    FOR i IN 0..63 LOOP
        suffix := lpad(i::text, 2, '0');
        shard_min := i::BIGINT * 1000000000000;
        shard_max := (i::BIGINT + 1) * 1000000000000;

        EXECUTE format('
            WITH numbered AS (
                SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
                FROM payment_intents_%1$s
                WHERE CASE WHEN external_id ~ ''^pi_[0-9]+$''
                    THEN substring(external_id from 4)::bigint
                    ELSE NULL
                END BETWEEN %2$s AND %3$s
            )
            UPDATE payment_intents_%1$s t
            SET external_id = ''pi_'' || masonx_v64_backfill_snowflake_id(numbered.rn, %4$s)
            FROM numbered
            WHERE t.id = numbered.id
        ', suffix, shard_min, shard_max - 1, 100 + i);

        EXECUTE format('
            WITH numbered AS (
                SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
                FROM payment_requests_%1$s
                WHERE CASE WHEN external_id ~ ''^pr_[0-9]+$''
                    THEN substring(external_id from 4)::bigint
                    ELSE NULL
                END BETWEEN %2$s AND %3$s
            )
            UPDATE payment_requests_%1$s t
            SET external_id = ''pr_'' || masonx_v64_backfill_snowflake_id(numbered.rn, %4$s)
            FROM numbered
            WHERE t.id = numbered.id
        ', suffix, shard_min, shard_max - 1, 200 + i);
    END LOOP;
END $$;

DROP FUNCTION masonx_v64_backfill_snowflake_id(bigint, bigint);
