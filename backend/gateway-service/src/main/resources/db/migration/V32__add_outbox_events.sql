-- 1.1: Transactional outbox for payment events.
-- Written in the same DB transaction as the payment intent save, so a JVM crash
-- between the save and the webhook dispatch can no longer silently drop an event.
-- A scheduler in WebhookDeliveryService polls this table and fires the Spring event.
CREATE TABLE outbox_events (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID         NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    resource_id UUID         NOT NULL,
    payload     TEXT         NOT NULL,
    published   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Partial index: only unpublished rows — keeps the poller query fast as the table grows.
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (created_at) WHERE published = FALSE;
