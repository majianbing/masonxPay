package com.masonx.paygateway.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class PaymentGatewayEvent extends ApplicationEvent {

    private final UUID merchantId;
    private final String eventType;
    private final UUID resourceId;
    private final String payload;

    public PaymentGatewayEvent(Object source, UUID merchantId, String eventType,
                               UUID resourceId, String payload) {
        super(source);
        this.merchantId = merchantId;
        this.eventType = eventType;
        this.resourceId = resourceId;
        this.payload = payload;
    }

    public UUID getMerchantId() { return merchantId; }
    public String getEventType() { return eventType; }
    public UUID getResourceId() { return resourceId; }
    public String getPayload() { return payload; }
}
