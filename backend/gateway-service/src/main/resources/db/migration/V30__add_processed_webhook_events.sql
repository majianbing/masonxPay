-- 1.4: Inbound webhook deduplication
-- Tracks provider event IDs we've already processed to prevent double-processing
-- when providers retry webhook delivery on timeout.
CREATE TABLE processed_webhook_events (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    provider          VARCHAR(20)  NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    processed_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (provider, provider_event_id)
);
