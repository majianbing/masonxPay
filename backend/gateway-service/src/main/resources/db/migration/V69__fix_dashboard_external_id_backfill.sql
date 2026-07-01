-- Same fix as V68, applied to V66's backfill targets: disputes, dispute_evidence_files,
-- scheduled_retry_jobs, and webhook_endpoints. V66 backfilled any pre-existing rows in
-- these tables with small sequential suffixes (dp_1, def_1, retry_1, whe_1, ...), which
-- trivially exposes creation order/count and doesn't resemble a real Snowflake ID.
--
-- Re-derive those specific backfill artifacts using the same bit layout as
-- SnowflakeIdGenerator (backend/common/.../id/SnowflakeIdGenerator.java):
--   [41 bits] ms since 2020-01-01 UTC  [10 bits] node id  [12 bits] sequence
-- Node id 63 is reserved for migration backfills only (see V68) and is never assigned to
-- a live SnowflakeIdGenerator bean, so these values can never collide with an
-- application-generated external_id.
--
-- New rows written by the app already get real application-generated Snowflake IDs; this
-- migration only touches leftover small-integer artifacts from V66 (matched via regex so
-- any already-correct value, or a table that had no pre-existing rows when V66 ran, is
-- left untouched).

CREATE OR REPLACE FUNCTION masonx_backfill_snowflake_id(seq bigint) RETURNS bigint AS $$
DECLARE
    epoch_ms      CONSTANT bigint := 1577836800000; -- matches SnowflakeIdGenerator EPOCH (2020-01-01 UTC)
    backfill_node CONSTANT bigint := 63;             -- reserved backfill-only node id
    now_ms        bigint;
BEGIN
    now_ms := (extract(epoch from clock_timestamp()) * 1000)::bigint - epoch_ms;
    RETURN (now_ms << 22) | (backfill_node << 12) | (seq % 4096);
END;
$$ LANGUAGE plpgsql VOLATILE;

UPDATE disputes
SET external_id = 'dp_' || masonx_backfill_snowflake_id(numbered.rn)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM disputes
    WHERE external_id ~ '^dp_[0-9]{1,6}$'
) numbered
WHERE disputes.id = numbered.id;

UPDATE dispute_evidence_files
SET external_id = 'def_' || masonx_backfill_snowflake_id(numbered.rn)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM dispute_evidence_files
    WHERE external_id ~ '^def_[0-9]{1,6}$'
) numbered
WHERE dispute_evidence_files.id = numbered.id;

UPDATE scheduled_retry_jobs
SET external_id = 'retry_' || masonx_backfill_snowflake_id(numbered.rn)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM scheduled_retry_jobs
    WHERE external_id ~ '^retry_[0-9]{1,6}$'
) numbered
WHERE scheduled_retry_jobs.id = numbered.id;

UPDATE webhook_endpoints
SET external_id = 'whe_' || masonx_backfill_snowflake_id(numbered.rn)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM webhook_endpoints
    WHERE external_id ~ '^whe_[0-9]{1,6}$'
) numbered
WHERE webhook_endpoints.id = numbered.id;

DROP FUNCTION masonx_backfill_snowflake_id(bigint);
