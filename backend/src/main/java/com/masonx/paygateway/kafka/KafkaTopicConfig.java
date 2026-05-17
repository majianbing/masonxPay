package com.masonx.paygateway.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(prefix = "app.kafka.outbox", name = "enabled", havingValue = "true")
public class KafkaTopicConfig {

    @Bean
    public NewTopic paymentLifecycleEventsTopic(KafkaOutboxProperties properties) {
        return TopicBuilder.name(properties.getTopic())
                .partitions(properties.getTopicPartitions())
                .replicas(1)
                .build();
    }
}
