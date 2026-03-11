CREATE TABLE gateway_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_gateway_logs_merchant_created ON gateway_logs(merchant_id, created_at DESC);
CREATE INDEX idx_gateway_logs_type ON gateway_logs(type);
