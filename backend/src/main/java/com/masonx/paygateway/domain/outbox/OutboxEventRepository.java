package com.masonx.paygateway.domain.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc(Pageable pageable);

    /** Count of unprocessed events — used as the webhook queue-depth gauge (Phase 2.4). */
    long countByPublishedFalse();
}
