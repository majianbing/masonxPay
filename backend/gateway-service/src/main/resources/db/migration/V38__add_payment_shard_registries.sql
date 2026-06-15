DO $$
DECLARE
    i INT;
    suffix TEXT;
BEGIN
    FOR i IN 0..63 LOOP
        suffix := lpad(i::text, 2, '0');

        EXECUTE format('
            CREATE TABLE payment_idempotency_keys_%s (
                merchant_id       UUID         NOT NULL REFERENCES merchants(id),
                idempotency_key   VARCHAR(255) NOT NULL,
                payment_intent_id UUID         NOT NULL,
                payment_shard_id  INT          NOT NULL CHECK (payment_shard_id BETWEEN 0 AND 63),
                status            VARCHAR(20)  NOT NULL CHECK (status IN (''IN_PROGRESS'', ''COMPLETED'', ''FAILED'')),
                created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                PRIMARY KEY (merchant_id, idempotency_key)
            )', suffix);

        EXECUTE format('
            CREATE INDEX idx_payment_idempotency_keys_%s_intent
            ON payment_idempotency_keys_%s(payment_intent_id)', suffix, suffix);

        EXECUTE format('
            CREATE TABLE provider_payment_refs_%s (
                merchant_id         UUID         NOT NULL REFERENCES merchants(id),
                provider            VARCHAR(20)  NOT NULL,
                connector_account_id UUID        NOT NULL REFERENCES provider_accounts(id),
                provider_payment_id VARCHAR(255) NOT NULL,
                payment_intent_id   UUID         NOT NULL,
                payment_shard_id    INT          NOT NULL CHECK (payment_shard_id BETWEEN 0 AND 63),
                created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                PRIMARY KEY (provider, connector_account_id, provider_payment_id)
            )', suffix);

        EXECUTE format('
            CREATE INDEX idx_provider_payment_refs_%s_intent
            ON provider_payment_refs_%s(payment_intent_id)', suffix, suffix);

        EXECUTE format('
            CREATE INDEX idx_provider_payment_refs_%s_merchant
            ON provider_payment_refs_%s(merchant_id)', suffix, suffix);
    END LOOP;
END $$;
