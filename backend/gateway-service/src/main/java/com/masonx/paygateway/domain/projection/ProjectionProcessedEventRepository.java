package com.masonx.paygateway.domain.projection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectionProcessedEventRepository extends JpaRepository<ProjectionProcessedEvent, UUID> {
    long countByStatus(ProjectionEventStatus status);
    Optional<ProjectionProcessedEvent> findFirstByStatusOrderByProcessedAtAsc(ProjectionEventStatus status);
}
