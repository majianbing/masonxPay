CREATE TABLE scheduled_retry_jobs (
    id                   UUID PRIMARY KEY,
    merchant_id          UUID NOT NULL,
    operation            VARCHAR(40) NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    payment_intent_id    UUID,
    refund_id            UUID,
    connector_account_id UUID,
    attempt_count        INTEGER NOT NULL DEFAULT 0,
    max_attempts         INTEGER NOT NULL,
    next_run_at          TIMESTAMPTZ NOT NULL,
    last_error_code      VARCHAR(80),
    last_error_message   TEXT,
    retry_reason         TEXT,
    payload_json         TEXT,
    locked_at            TIMESTAMPTZ,
    locked_by            VARCHAR(120),
    completed_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_scheduled_retry_attempts CHECK (attempt_count >= 0 AND max_attempts > 0),
    CONSTRAINT chk_scheduled_retry_target CHECK (payment_intent_id IS NOT NULL OR refund_id IS NOT NULL)
);

CREATE INDEX idx_scheduled_retry_jobs_merchant_created
    ON scheduled_retry_jobs (merchant_id, created_at DESC);

CREATE INDEX idx_scheduled_retry_jobs_due
    ON scheduled_retry_jobs (status, next_run_at);

CREATE INDEX idx_scheduled_retry_jobs_payment_intent
    ON scheduled_retry_jobs (merchant_id, payment_intent_id);

CREATE INDEX idx_scheduled_retry_jobs_refund
    ON scheduled_retry_jobs (merchant_id, refund_id);
