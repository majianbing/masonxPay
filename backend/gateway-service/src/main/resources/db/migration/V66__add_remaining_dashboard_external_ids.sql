ALTER TABLE disputes
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

UPDATE disputes
SET external_id = 'dp_' || numbered.rn
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM disputes
    WHERE external_id IS NULL
) numbered
WHERE disputes.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS ux_disputes_external_id
    ON disputes (external_id)
    WHERE external_id IS NOT NULL;

ALTER TABLE dispute_evidence_files
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

UPDATE dispute_evidence_files
SET external_id = 'def_' || numbered.rn
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM dispute_evidence_files
    WHERE external_id IS NULL
) numbered
WHERE dispute_evidence_files.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS ux_dispute_evidence_files_external_id
    ON dispute_evidence_files (external_id)
    WHERE external_id IS NOT NULL;

ALTER TABLE scheduled_retry_jobs
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

UPDATE scheduled_retry_jobs
SET external_id = 'retry_' || numbered.rn
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM scheduled_retry_jobs
    WHERE external_id IS NULL
) numbered
WHERE scheduled_retry_jobs.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS ux_scheduled_retry_jobs_external_id
    ON scheduled_retry_jobs (external_id)
    WHERE external_id IS NOT NULL;

ALTER TABLE webhook_endpoints
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

UPDATE webhook_endpoints
SET external_id = 'whe_' || numbered.rn
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM webhook_endpoints
    WHERE external_id IS NULL
) numbered
WHERE webhook_endpoints.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS ux_webhook_endpoints_external_id
    ON webhook_endpoints (external_id)
    WHERE external_id IS NOT NULL;
