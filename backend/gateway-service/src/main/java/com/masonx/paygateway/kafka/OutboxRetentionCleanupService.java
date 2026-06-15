package com.masonx.paygateway.kafka;

import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "app.kafka.outbox", name = "enabled", havingValue = "true")
public class OutboxRetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetentionCleanupService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaOutboxProperties properties;

    public OutboxRetentionCleanupService(OutboxEventRepository outboxEventRepository,
                                         KafkaOutboxProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.kafka.outbox.cleanup-cron:0 30 2 * * *}")
    @Transactional
    public void cleanupPublishedOutboxEvents() {
        Instant cutoff = Instant.now().minus(properties.getRetentionDays(), ChronoUnit.DAYS);
        List<OutboxEvent> expired = outboxEventRepository
                .findByPublishedTrueAndKafkaPublishedTrueAndCreatedAtBeforeOrderByCreatedAtAsc(
                        cutoff,
                        PageRequest.of(0, properties.getCleanupBatchSize()));

        outboxEventRepository.deleteAll(expired);

        if (!expired.isEmpty()) {
            log.info("Deleted {} fully published outbox events older than {}", expired.size(), cutoff);
        }
    }
}
