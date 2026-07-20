package com.masonx.virtualaccount.inbound.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.common.tenant.Mode;
import com.masonx.contracts.merchant.MerchantCreatedEvent;
import com.masonx.virtualaccount.provisioning.MerchantLedgerProvisioningService;
import com.masonx.virtualaccount.provisioning.MerchantProvisioningCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "va.kafka.consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GatewayLifecycleEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(GatewayLifecycleEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final MerchantLedgerProvisioningService provisioningService;

    public GatewayLifecycleEventConsumer(ObjectMapper objectMapper,
                                         MerchantLedgerProvisioningService provisioningService) {
        this.objectMapper = objectMapper;
        this.provisioningService = provisioningService;
    }

    @KafkaListener(
            topics = "${va.kafka.gateway-lifecycle-topic:payment.lifecycle.events}",
            containerFactory = "gatewayLifecycleListenerContainerFactory"
    )
    public void consume(String rawMessage) {
        JsonNode message = readMessage(rawMessage);
        String eventType = message.path("eventType").asText("");
        if (!MerchantCreatedEvent.TYPE.equals(eventType)) {
            return;
        }

        MerchantCreatedEvent event = objectMapper.convertValue(
                message.path("payload"), MerchantCreatedEvent.class);
        boolean processed = provisioningService.provisionIfNew(toCommand(event));
        if (processed) {
            log.info("Provisioned VA ledger accounts for merchant={}", event.merchantId());
        } else {
            log.info("Skipped duplicate merchant provisioning event: eventId={}",
                    event.envelope().eventId());
        }
    }

    private JsonNode readMessage(String rawMessage) {
        try {
            return objectMapper.readTree(rawMessage);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid gateway lifecycle event payload", e);
        }
    }

    private MerchantProvisioningCommand toCommand(MerchantCreatedEvent event) {
        List<Mode> modes = event.modes() == null
                ? List.of(Mode.TEST)
                : event.modes().stream().map(Mode::valueOf).toList();
        return new MerchantProvisioningCommand(
                event.envelope().eventId(),
                event.organizationId(),
                event.merchantId(),
                event.merchantName(),
                modes,
                event.defaultAsset());
    }
}
