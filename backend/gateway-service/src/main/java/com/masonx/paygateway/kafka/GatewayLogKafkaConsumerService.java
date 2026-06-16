package com.masonx.paygateway.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.config.GatewayLogProperties;
import com.masonx.paygateway.domain.log.GatewayLog;
import com.masonx.paygateway.service.GatewayLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "app.gateway-logs", name = "mode", havingValue = "KAFKA")
public class GatewayLogKafkaConsumerService {

    private static final Logger log = LoggerFactory.getLogger(GatewayLogKafkaConsumerService.class);

    private final ObjectMapper objectMapper;
    private final GatewayLogService gatewayLogService;
    private final GatewayLogProperties properties;

    public GatewayLogKafkaConsumerService(ObjectMapper objectMapper,
                                          GatewayLogService gatewayLogService,
                                          GatewayLogProperties properties) {
        this.objectMapper = objectMapper;
        this.gatewayLogService = gatewayLogService;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${app.gateway-logs.topic:gateway.api.logs}",
            groupId = "${app.gateway-logs.group-id:masonxpay-gateway-log-writer}",
            containerFactory = "gatewayLogBatchKafkaListenerContainerFactory")
    public void consumeGatewayLogs(List<String> messages) {
        if (!properties.isConsumerStoreEnabled()) {
            return;
        }

        List<GatewayLog> logs = new ArrayList<>(messages.size());
        for (String message : messages) {
            try {
                logs.add(objectMapper.readValue(message, GatewayLogKafkaEvent.class).toGatewayLog());
            } catch (Exception ex) {
                log.warn("Skipping malformed gateway log event: {}", ex.getMessage());
            }
        }
        gatewayLogService.logBatch(logs);
    }
}
