CREATE TABLE webhook_endpoints (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id      UUID        NOT NULL REFERENCES merchants(id),
    url              TEXT        NOT NULL,
    signing_secret   VARCHAR(64) NOT NULL,
    description      TEXT,
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    subscribed_events TEXT       NOT NULL DEFAULT 'payment_intent.succeeded,payment_intent.failed,payment_intent.canceled',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_endpoints_merchant ON webhook_endpoints(merchant_id);

CREATE TABLE gateway_events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id  UUID         NOT NULL REFERENCES merchants(id),
    event_type   VARCHAR(100) NOT NULL,
    resource_id  UUID         NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_gateway_events_merchant ON gateway_events(merchant_id);

CREATE TABLE webhook_deliveries (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    gateway_event_id    UUID        NOT NULL REFERENCES gateway_events(id),
    webhook_endpoint_id UUID        NOT NULL REFERENCES webhook_endpoints(id),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    http_status         INT,
    response_body       TEXT,
    attempt_count       INT         NOT NULL DEFAULT 0,
    next_retry_at       TIMESTAMPTZ,
    last_attempted_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_deliveries_retry ON webhook_deliveries(status, next_retry_at)
    WHERE status IN ('PENDING', 'RETRYING');
