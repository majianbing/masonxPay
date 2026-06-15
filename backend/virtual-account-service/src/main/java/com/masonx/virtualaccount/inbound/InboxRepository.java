package com.masonx.virtualaccount.inbound;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * DB-level idempotency for event consumption. {@link #markProcessed} inserts the
 * event id once; a redelivered event hits the primary-key conflict and is a
 * no-op, so effects run at most once regardless of Kafka redelivery.
 */
@Repository
public class InboxRepository {

    private final JdbcTemplate jdbc;

    public InboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this is the first time the event is seen (process it); false if duplicate (skip). */
    public boolean markProcessed(UUID eventId, String eventType) {
        int rows = jdbc.update(
                "INSERT INTO va_inbox_event (event_id, event_type) VALUES (?, ?) ON CONFLICT (event_id) DO NOTHING",
                eventId, eventType);
        return rows == 1;
    }
}
