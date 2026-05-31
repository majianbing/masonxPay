ALTER TABLE invoices
    ADD COLUMN mode VARCHAR(10) NOT NULL DEFAULT 'TEST';

ALTER TABLE invoice_payment_attempts
    ADD COLUMN mode VARCHAR(10) NOT NULL DEFAULT 'TEST';

CREATE INDEX idx_invoices_merchant_mode_status
    ON invoices (merchant_id, mode, status, due_at);
