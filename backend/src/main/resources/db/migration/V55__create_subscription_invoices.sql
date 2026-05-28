CREATE TABLE IF NOT EXISTS invoices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    amount_due BIGINT NOT NULL,
    amount_paid BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(10) NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    due_at TIMESTAMPTZ,
    next_payment_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_invoices_subscription_period UNIQUE (merchant_id, subscription_id, period_start, period_end)
);

CREATE INDEX IF NOT EXISTS idx_invoices_subscription_created
    ON invoices (merchant_id, subscription_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_invoices_status_due
    ON invoices (merchant_id, status, due_at);

CREATE TABLE IF NOT EXISTS invoice_payment_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    invoice_id UUID NOT NULL,
    payment_intent_id UUID,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    failure_code VARCHAR(100),
    failure_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_invoice_attempt_number UNIQUE (merchant_id, invoice_id, attempt_number)
);

CREATE INDEX IF NOT EXISTS idx_invoice_attempts_invoice
    ON invoice_payment_attempts (merchant_id, invoice_id, attempt_number);
