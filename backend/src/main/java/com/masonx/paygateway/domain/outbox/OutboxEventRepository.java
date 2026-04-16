package com.masonx.paygateway.domain.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc(Pageable pageable);

    /** Count of unprocessed events — used as the webhook queue-depth gauge (Phase 2.4). */
    long countByPublishedFalse();

    /**
     * Re-reads a single unpublished outbox event inside a transaction with a row-level lock.
     * FOR UPDATE SKIP LOCKED: if another node already holds the lock on this row, returns
     * empty immediately rather than blocking — preventing duplicate webhook delivery on
     * multi-node deployments.
     *
     * Must be called from within an active transaction (txTemplate.execute).
     */
    @Query(value = "SELECT * FROM outbox_events WHERE id = :id AND published = false FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    Optional<OutboxEvent> findByIdUnpublishedForUpdate(@Param("id") UUID id);
}
