CREATE TABLE api_keys (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id  UUID         NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    mode         VARCHAR(10)  NOT NULL,
    type         VARCHAR(15)  NOT NULL,
    key_hash     VARCHAR(64)  NOT NULL UNIQUE,
    prefix       VARCHAR(20)  NOT NULL,
    name         VARCHAR(100),
    status       VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    last_used_at TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    revoked_at   TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_api_keys_merchant_id ON api_keys(merchant_id);
CREATE INDEX idx_api_keys_key_hash    ON api_keys(key_hash);
