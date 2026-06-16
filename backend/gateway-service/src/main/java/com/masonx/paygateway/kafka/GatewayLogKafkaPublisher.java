package com.masonx.paygateway.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.config.GatewayLogProperties;
import com.masonx.paygateway.domain.log.GatewayLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.gateway-logs", name = "mode", havingValue = "KAFKA")
public class GatewayLogKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(GatewayLogKafkaPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayLogProperties properties;

    public GatewayLogKafkaPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                    ObjectMapper objectMapper,
                                    GatewayLogProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publish(GatewayLog entry) {
        try {
            String key = entry.getMerchantId() != null ? entry.getMerchantId().toString() : entry.getTraceId();
            String payload = objectMapper.writeValueAsString(GatewayLogKafkaEvent.from(entry));
            kafkaTemplate.send(properties.getTopic(), key, payload)
                    .exceptionally(ex -> {
                        log.warn("Failed to enqueue gateway log event to Kafka: {}", ex.getMessage());
                        return null;
                    });
        } catch (Exception ex) {
            log.warn("Failed to serialize gateway log event for Kafka: {}", ex.getMessage());
        }
    }
}
