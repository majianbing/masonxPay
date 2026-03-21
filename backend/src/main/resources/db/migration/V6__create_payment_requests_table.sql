CREATE TABLE payment_requests (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_intent_id   UUID         NOT NULL REFERENCES payment_intents(id) ON DELETE CASCADE,
    amount              BIGINT       NOT NULL,
    currency            VARCHAR(10)  NOT NULL,
    payment_method_type VARCHAR(50)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    provider_request_id VARCHAR(255),
    provider_response   TEXT,
    failure_code        VARCHAR(100),
    failure_message     VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_requests_intent_id ON payment_requests(payment_intent_id);
