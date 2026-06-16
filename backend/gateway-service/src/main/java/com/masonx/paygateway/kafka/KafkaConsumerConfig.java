package com.masonx.paygateway.kafka;

import com.masonx.paygateway.config.GatewayLogProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> gatewayLogBatchKafkaListenerContainerFactory(
            KafkaProperties kafkaProperties,
            GatewayLogProperties properties) {
        Map<String, Object> consumerProperties = kafkaProperties.buildConsumerProperties(null);
        consumerProperties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.getBatchSize());
        ConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(consumerProperties);
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.setConcurrency(properties.getConsumerConcurrency());
        return factory;
    }
}
