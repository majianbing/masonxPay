DO $$
DECLARE
    i INT;
    suffix TEXT;
BEGIN
    FOR i IN 0..63 LOOP
        suffix := lpad(i::text, 2, '0');

        EXECUTE format('
            CREATE TABLE payment_intents_%s (
                id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                merchant_id         UUID         NOT NULL REFERENCES merchants(id),
                mode                VARCHAR(10)  NOT NULL,
                amount              BIGINT       NOT NULL,
                currency            VARCHAR(10)  NOT NULL,
                status              VARCHAR(30)  NOT NULL DEFAULT ''REQUIRES_PAYMENT_METHOD'',
                capture_method      VARCHAR(20)  NOT NULL DEFAULT ''AUTOMATIC'',
                idempotency_key     VARCHAR(255) NOT NULL,
                resolved_provider   VARCHAR(20),
                connector_account_id UUID        REFERENCES provider_accounts(id),
                provider_payment_id VARCHAR(255),
                provider_response   TEXT,
                metadata            TEXT,
                success_url         VARCHAR(500),
                cancel_url          VARCHAR(500),
                failure_url         VARCHAR(500),
                payment_method_type VARCHAR(50),
                order_id            VARCHAR(255),
                description         VARCHAR(500),
                billing_details     TEXT,
                shipping_details    TEXT,
                trace_id            VARCHAR(36),
                action_type         VARCHAR(30),
                action_url          VARCHAR(2000),
                expires_at          TIMESTAMPTZ,
                created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                UNIQUE (merchant_id, idempotency_key)
            )', suffix);

        EXECUTE format('
            CREATE INDEX idx_payment_intents_%s_merchant_id
            ON payment_intents_%s(merchant_id)', suffix, suffix);

        EXECUTE format('
            CREATE INDEX idx_payment_intents_%s_status
            ON payment_intents_%s(status)', suffix, suffix);

        EXECUTE format('
            CREATE INDEX idx_payment_intents_%s_order_id
            ON payment_intents_%s(merchant_id, order_id)
            WHERE order_id IS NOT NULL', suffix, suffix);

        EXECUTE format('
            CREATE INDEX idx_payment_intents_%s_trace_id
            ON payment_intents_%s(trace_id)
            WHERE trace_id IS NOT NULL', suffix, suffix);

        EXECUTE format('
            CREATE INDEX idx_payment_intents_%s_provider_payment_id
            ON payment_intents_%s(provider_payment_id)
            WHERE provider_payment_id IS NOT NULL', suffix, suffix);

        EXECUTE format('
            CREATE TABLE payment_requests_%s (
                id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                merchant_id              UUID         REFERENCES merchants(id),
                payment_intent_id        UUID         NOT NULL REFERENCES payment_intents_%s(id) ON DELETE CASCADE,
                amount                   BIGINT       NOT NULL,
                currency                 VARCHAR(10)  NOT NULL,
                payment_method_type      VARCHAR(50)  NOT NULL,
                status                   VARCHAR(20)  NOT NULL DEFAULT ''PENDING'',
                provider_request_id      VARCHAR(255),
                provider_response        TEXT,
                failure_code             VARCHAR(100),
                failure_message          VARCHAR(500),
                connector_account_id     UUID,
                attempt_number           INTEGER      NOT NULL DEFAULT 1,
                attempt_type             VARCHAR(30)  NOT NULL DEFAULT ''PRIMARY'',
                provider_idempotency_key VARCHAR(255),
                created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
            )', suffix, suffix);

        EXECUTE format('
            CREATE INDEX idx_payment_requests_%s_intent_id
            ON payment_requests_%s(payment_intent_id)', suffix, suffix);

        EXECUTE format('
            CREATE INDEX idx_payment_requests_%s_intent_attempt
            ON payment_requests_%s(payment_intent_id, attempt_number)', suffix, suffix);

        EXECUTE format('
            CREATE INDEX idx_payment_requests_%s_connector_created
            ON payment_requests_%s(connector_account_id, created_at)
            WHERE connector_account_id IS NOT NULL', suffix, suffix);

        EXECUTE format('
            CREATE INDEX idx_payment_requests_%s_merchant_created
            ON payment_requests_%s(merchant_id, created_at)
            WHERE merchant_id IS NOT NULL', suffix, suffix);
    END LOOP;
END $$;
