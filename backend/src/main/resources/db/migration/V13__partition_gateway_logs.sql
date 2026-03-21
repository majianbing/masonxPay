-- Convert gateway_logs to a 6-month range-partitioned table.
--
-- PostgreSQL requires partition key (created_at) to be part of any unique
-- constraint, which conflicts with JPA's single-field @Id. We therefore drop
-- the DB-level PK and rely on UUID uniqueness (negligible collision risk).
-- Application-level identity (@Id) and queries are unaffected.

-- 1. Preserve existing data
ALTER TABLE gateway_logs RENAME TO gateway_logs_legacy;
ALTER INDEX idx_gateway_logs_merchant_created RENAME TO idx_gateway_logs_legacy_merchant_created;
ALTER INDEX idx_gateway_logs_type             RENAME TO idx_gateway_logs_legacy_type;
-- V12 index (may not exist in all environments — ignore if absent)
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_gateway_logs_merchant_mode') THEN
    ALTER INDEX idx_gateway_logs_merchant_mode RENAME TO idx_gateway_logs_legacy_merchant_mode;
  END IF;
END $$;

-- 2. Create the new partitioned parent table (no PK — see note above)
CREATE TABLE gateway_logs (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    merchant_id     UUID         REFERENCES merchants(id),
    api_key_id      UUID         REFERENCES api_keys(id),
    request_id      VARCHAR(64),
    type            VARCHAR(30)  NOT NULL,
    method          VARCHAR(10),
    path            TEXT,
    request_headers TEXT,
    request_body    TEXT,
    response_status INT,
    response_body   TEXT,
    duration_ms     BIGINT,
    mode            VARCHAR(10),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
) PARTITION BY RANGE (created_at);

-- 3. Create initial 6-month partitions
--    2025_h2 — catches any historical data before the current period
CREATE TABLE gateway_logs_2025_h2 PARTITION OF gateway_logs
    FOR VALUES FROM ('2025-07-01 00:00:00+00') TO ('2026-01-01 00:00:00+00');

--    2026_h1 — current period (project is at 2026-03-15)
CREATE TABLE gateway_logs_2026_h1 PARTITION OF gateway_logs
    FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');

--    2026_h2 — next period (pre-created buffer)
CREATE TABLE gateway_logs_2026_h2 PARTITION OF gateway_logs
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');

-- 4. Migrate existing data (mode column already present from V12)
INSERT INTO gateway_logs
SELECT id, merchant_id, api_key_id, request_id, type, method, path,
       request_headers, request_body, response_status, response_body,
       duration_ms, mode, created_at
FROM gateway_logs_legacy;

-- 5. Drop legacy table (indexes are dropped automatically)
DROP TABLE gateway_logs_legacy;

-- 6. Recreate indexes on the partitioned parent (PostgreSQL propagates to all partitions)
CREATE INDEX idx_gateway_logs_merchant_created ON gateway_logs(merchant_id, created_at DESC);
CREATE INDEX idx_gateway_logs_type             ON gateway_logs(type);
CREATE INDEX idx_gateway_logs_merchant_mode    ON gateway_logs(merchant_id, mode);
