-- V67 backfilled merchants/customers/subscriptions/invoices with small sequential
-- suffixes (mer_1, cus_1, sub_1, inv_1, ...). That trivially exposes creation order
-- and total row count, doesn't resemble a real Snowflake ID, and violates the
-- refactor plan's own backfill requirement to "not expose ordering assumptions
-- stronger than Snowflake provides" (docs/refactor/gateway-id-standardization.md).
--
-- Re-derive those specific backfill artifacts using the same bit layout as
-- SnowflakeIdGenerator (backend/common/.../id/SnowflakeIdGenerator.java):
--   [41 bits] ms since 2020-01-01 UTC  [10 bits] node id  [12 bits] sequence
-- Node id 63 is reserved for migration backfills only and is never assigned to a
-- live SnowflakeIdGenerator bean (gateway=2, rail=1, virtual-account=0), so these
-- values can never collide with an application-generated external_id.
--
-- New rows written after this branch's GatewayIdService wiring already get real
-- application-generated Snowflake IDs; this migration only touches the leftover
-- small-integer artifacts from V67 (matched via regex so any already-correct
-- value is left untouched).

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

UPDATE merchants
SET external_id = 'mer_' || masonx_backfill_snowflake_id(numbered.rn)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM merchants
    WHERE external_id ~ '^mer_[0-9]{1,6}$'
) numbered
WHERE merchants.id = numbered.id;

UPDATE customers
SET external_id = 'cus_' || masonx_backfill_snowflake_id(numbered.rn)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM customers
    WHERE external_id ~ '^cus_[0-9]{1,6}$'
) numbered
WHERE customers.id = numbered.id;

UPDATE subscriptions
SET external_id = 'sub_' || masonx_backfill_snowflake_id(numbered.rn)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM subscriptions
    WHERE external_id ~ '^sub_[0-9]{1,6}$'
) numbered
WHERE subscriptions.id = numbered.id;

UPDATE invoices
SET external_id = 'inv_' || masonx_backfill_snowflake_id(numbered.rn)
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM invoices
    WHERE external_id ~ '^inv_[0-9]{1,6}$'
) numbered
WHERE invoices.id = numbered.id;

DROP FUNCTION masonx_backfill_snowflake_id(bigint);
