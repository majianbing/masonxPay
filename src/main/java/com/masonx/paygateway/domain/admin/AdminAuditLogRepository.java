package com.masonx.paygateway.domain.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {
}
