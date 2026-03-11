package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.log.GatewayLog;
import com.masonx.paygateway.domain.log.GatewayLogRepository;
import com.masonx.paygateway.domain.log.GatewayLogType;
import com.masonx.paygateway.web.dto.GatewayLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class GatewayLogService {

    private final GatewayLogRepository gatewayLogRepository;

    public GatewayLogService(GatewayLogRepository gatewayLogRepository) {
        this.gatewayLogRepository = gatewayLogRepository;
    }

    /**
     * Persists a log entry asynchronously so it never blocks the request thread.
     */
    @Async("webhookExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(GatewayLog entry) {
        gatewayLogRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public Page<GatewayLogResponse> list(UUID merchantId, String type, Pageable pageable) {
        Page<GatewayLog> page;
        if (type != null && !type.isBlank()) {
            GatewayLogType logType = GatewayLogType.valueOf(type.toUpperCase());
            page = gatewayLogRepository.findAllByMerchantIdAndTypeOrderByCreatedAtDesc(merchantId, logType, pageable);
        } else {
            page = gatewayLogRepository.findAllByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
        }
        return page.map(GatewayLogResponse::from);
    }
}
