package com.masonx.paygateway.kafka;

import com.masonx.paygateway.config.GatewayLogProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(prefix = "app.gateway-logs", name = "mode", havingValue = "KAFKA")
public class GatewayLogKafkaTopicConfig {

    @Bean
    public NewTopic gatewayApiLogsTopic(GatewayLogProperties properties) {
        return TopicBuilder.name(properties.getTopic())
                .partitions(properties.getTopicPartitions())
                .replicas(1)
                .build();
    }
}
