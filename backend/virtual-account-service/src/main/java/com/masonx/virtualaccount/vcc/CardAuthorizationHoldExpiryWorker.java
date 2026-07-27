package com.masonx.virtualaccount.vcc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CardAuthorizationHoldExpiryWorker {

    private static final Logger log = LoggerFactory.getLogger(CardAuthorizationHoldExpiryWorker.class);

    private final CardAuthorizationLifecycleService lifecycleService;
    private final boolean enabled;
    private final Duration holdTtl;
    private final int batchSize;

    public CardAuthorizationHoldExpiryWorker(CardAuthorizationLifecycleService lifecycleService,
                                             @Value("${app.card-auth.hold-expiry.enabled:false}") boolean enabled,
                                             @Value("${app.card-auth.hold-expiry.ttl-hours:168}") long ttlHours,
                                             @Value("${app.card-auth.hold-expiry.batch-size:100}") int batchSize) {
        this.lifecycleService = lifecycleService;
        this.enabled = enabled;
        this.holdTtl = Duration.ofHours(ttlHours);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.card-auth.hold-expiry.poll-ms:300000}")
    public void expireStaleHolds() {
        if (!enabled) {
            return;
        }
        int released = lifecycleService.expireStaleHolds(holdTtl, batchSize);
        if (released > 0) {
            log.info("Expired stale card authorization holds count={}", released);
        }
    }
}
