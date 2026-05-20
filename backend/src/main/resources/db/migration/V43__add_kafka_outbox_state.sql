ALTER TABLE outbox_events
    ADD COLUMN kafka_published BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN kafka_published_at TIMESTAMPTZ,
    ADD COLUMN kafka_publish_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN kafka_last_error TEXT;

CREATE INDEX idx_outbox_events_kafka_unpublished
    ON outbox_events (created_at)
    WHERE kafka_published = FALSE;

CREATE INDEX idx_outbox_events_kafka_cleanup
    ON outbox_events (created_at)
    WHERE published = TRUE AND kafka_published = TRUE;
