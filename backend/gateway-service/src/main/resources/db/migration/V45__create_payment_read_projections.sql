CREATE TABLE payment_read_models (
    payment_intent_id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    mode VARCHAR(10) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(10) NOT NULL,
    status VARCHAR(30) NOT NULL,
    capture_method VARCHAR(20),
    resolved_provider VARCHAR(20),
    connector_account_id UUID,
    provider_payment_id VARCHAR(255),
    idempotency_key VARCHAR(255),
    order_id VARCHAR(255),
    description VARCHAR(500),
    billing_email VARCHAR(255),
    refunded_amount_succeeded BIGINT NOT NULL DEFAULT 0,
    last_refund_id UUID,
    last_refund_status VARCHAR(20),
    search_text TEXT,
    source_created_at TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ,
    last_event_id UUID,
    last_event_type VARCHAR(100),
    last_event_created_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_read_models_merchant_created
    ON payment_read_models (merchant_id, source_created_at DESC);

CREATE INDEX idx_payment_read_models_merchant_status_created
    ON payment_read_models (merchant_id, status, source_created_at DESC);

CREATE INDEX idx_payment_read_models_merchant_mode_created
    ON payment_read_models (merchant_id, mode, source_created_at DESC);

CREATE INDEX idx_payment_read_models_merchant_provider_created
    ON payment_read_models (merchant_id, resolved_provider, source_created_at DESC);

CREATE INDEX idx_payment_read_models_search
    ON payment_read_models USING gin (to_tsvector('simple', coalesce(search_text, '')));

CREATE TABLE projection_processed_events (
    outbox_event_id UUID PRIMARY KEY,
    consumer_name VARCHAR(100) NOT NULL,
    merchant_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    resource_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_projection_processed_events_merchant_processed
    ON projection_processed_events (merchant_id, processed_at DESC);

CREATE INDEX idx_projection_processed_events_status_processed
    ON projection_processed_events (status, processed_at DESC);
