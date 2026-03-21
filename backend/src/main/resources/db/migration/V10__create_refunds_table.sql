CREATE TABLE refunds (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_intent_id   UUID         NOT NULL REFERENCES payment_intents(id),
    merchant_id         UUID         NOT NULL REFERENCES merchants(id),
    amount              BIGINT       NOT NULL,
    currency            VARCHAR(10)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reason              VARCHAR(30),
    provider_refund_id  VARCHAR(255),
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refunds_payment_intent_id ON refunds(payment_intent_id);
CREATE INDEX idx_refunds_merchant_id       ON refunds(merchant_id);
