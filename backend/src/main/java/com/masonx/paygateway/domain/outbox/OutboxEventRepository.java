package com.masonx.paygateway.domain.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc(Pageable pageable);

    List<OutboxEvent> findByKafkaPublishedFalseOrderByCreatedAtAsc(Pageable pageable);

    List<OutboxEvent> findByPublishedTrueAndKafkaPublishedTrueAndCreatedAtBeforeOrderByCreatedAtAsc(
            Instant cutoff,
            Pageable pageable);

    Optional<OutboxEvent> findFirstByKafkaPublishedFalseOrderByCreatedAtAsc();

    /** Count of unprocessed events — used as the webhook queue-depth gauge (Phase 2.4). */
    long countByPublishedFalse();

    long countByKafkaPublishedFalse();

    /**
     * Re-reads a single unpublished outbox event inside a transaction with a row-level lock.
     * If another node already holds the lock, this waits briefly; when the first transaction
     * commits published=true, the predicate is rechecked and no duplicate delivery rows are
     * created.
     *
     * Must be called from within an active transaction (txTemplate.execute).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OutboxEvent o WHERE o.id = :id AND o.published = false")
    Optional<OutboxEvent> findByIdUnpublishedForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OutboxEvent o WHERE o.id = :id AND o.kafkaPublished = false")
    Optional<OutboxEvent> findByIdKafkaUnpublishedForUpdate(@Param("id") UUID id);
}
