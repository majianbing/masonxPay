CREATE TABLE routing_rules (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id          UUID        NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    priority             INT         NOT NULL,
    enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    currencies           TEXT,
    amount_min           BIGINT,
    amount_max           BIGINT,
    country_codes        TEXT,
    payment_method_types TEXT,
    target_provider      VARCHAR(20) NOT NULL,
    fallback_provider    VARCHAR(20),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_routing_rules_merchant_id ON routing_rules(merchant_id);
