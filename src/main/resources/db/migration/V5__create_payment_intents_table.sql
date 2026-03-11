CREATE TABLE payment_intents (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id         UUID         NOT NULL REFERENCES merchants(id),
    mode                VARCHAR(10)  NOT NULL,
    amount              BIGINT       NOT NULL,
    currency            VARCHAR(10)  NOT NULL,
    status              VARCHAR(30)  NOT NULL DEFAULT 'REQUIRES_PAYMENT_METHOD',
    capture_method      VARCHAR(20)  NOT NULL DEFAULT 'AUTOMATIC',
    idempotency_key     VARCHAR(255) NOT NULL,
    resolved_provider   VARCHAR(20),
    provider_payment_id VARCHAR(255),
    provider_response   TEXT,
    metadata            TEXT,
    success_url         VARCHAR(500),
    cancel_url          VARCHAR(500),
    failure_url         VARCHAR(500),
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (merchant_id, idempotency_key)
);

CREATE INDEX idx_payment_intents_merchant_id ON payment_intents(merchant_id);
CREATE INDEX idx_payment_intents_status      ON payment_intents(status);
