package com.masonx.paygateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.audit.AuditAction;
import com.masonx.paygateway.domain.audit.MerchantAuditLog;
import com.masonx.paygateway.domain.audit.MerchantAuditLogRepository;
import com.masonx.paygateway.security.MerchantUserDetails;
import com.masonx.paygateway.web.dto.MerchantAuditLogResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class MerchantAuditLogService {

    private static final Logger log = LoggerFactory.getLogger(MerchantAuditLogService.class);

    private final MerchantAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public MerchantAuditLogService(MerchantAuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records an audit event. Resolves the actor from the current security context;
     * actor fields are null for system-triggered operations with no HTTP principal.
     */
    public void record(UUID merchantId, AuditAction action,
                       String resourceType, String resourceId, String resourceLabel,
                       Map<String, Object> metadata) {
        MerchantAuditLog entry = new MerchantAuditLog();
        entry.setMerchantId(merchantId);
        entry.setAction(action);
        entry.setResourceType(resourceType);
        entry.setResourceId(resourceId);
        entry.setResourceLabel(resourceLabel);
        entry.setActorUserId(resolveActorUserId());
        entry.setActorEmail(resolveActorEmail());

        if (metadata != null && !metadata.isEmpty()) {
            try {
                entry.setMetadata(objectMapper.writeValueAsString(metadata));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize audit metadata for action {}: {}", action, e.getMessage());
            }
        }

        repository.save(entry);
    }

    @Transactional(readOnly = true)
    public Page<MerchantAuditLogResponse> list(UUID merchantId, String action, Pageable pageable) {
        Page<MerchantAuditLog> page;
        if (action != null && !action.isBlank()) {
            AuditAction actionEnum = AuditAction.valueOf(action.toUpperCase());
            page = repository.findAllByMerchantIdAndActionOrderByCreatedAtDesc(merchantId, actionEnum, pageable);
        } else {
            page = repository.findAllByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
        }
        return page.map(MerchantAuditLogResponse::from);
    }

    private UUID resolveActorUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof MerchantUserDetails ud) {
                return ud.getUserId();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String resolveActorEmail() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof MerchantUserDetails ud) {
                return ud.getUsername();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
