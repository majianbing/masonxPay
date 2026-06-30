package com.masonx.paygateway.kafka;

import com.masonx.contracts.rail.RailPaymentResolvedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

/**
 * Dedicated Kafka consumer configuration for {@link RailPaymentResolvedEvent}.
 *
 * <p>Active only when {@code app.rail.enabled=true}. When the rail bridge is not
 * configured the {@link RailPaymentResolvedConsumer} and this factory are not created,
 * preventing spurious consumer-group registrations and class-not-found errors.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.rail", name = "enabled", havingValue = "true")
public class RailKafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, RailPaymentResolvedEvent> railResolvedConsumerFactory(
            KafkaProperties kafkaProperties,
            @Value("${app.rail.kafka.consumer.group-id:gateway-rail-resolved}") String groupId) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        JsonDeserializer<RailPaymentResolvedEvent> valueDeserializer =
                new JsonDeserializer<>(RailPaymentResolvedEvent.class, false);
        valueDeserializer.addTrustedPackages("com.masonx.contracts.rail", "com.masonx.contracts",
                "com.masonx.common.tenant");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), valueDeserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, RailPaymentResolvedEvent>
    railResolvedListenerContainerFactory(
            ConsumerFactory<String, RailPaymentResolvedEvent> railResolvedConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, RailPaymentResolvedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(railResolvedConsumerFactory);
        return factory;
    }
}
