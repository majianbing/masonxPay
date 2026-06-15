ALTER TABLE scheduled_retry_jobs
    ADD COLUMN invoice_id UUID;

CREATE INDEX idx_scheduled_retry_jobs_invoice
    ON scheduled_retry_jobs (merchant_id, invoice_id)
    WHERE invoice_id IS NOT NULL;
