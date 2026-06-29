package com.masonx.virtualaccount.config;

import com.masonx.contracts.rail.RailSettlementEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

/**
 * Separate Kafka consumer factory for {@link RailSettlementEvent}.
 *
 * <p>The default VA consumer factory hard-codes {@code SettlementEvent} as the
 * deserialization target. Rail settlement events arrive on a different topic with
 * a different type, so they need their own factory and container.
 */
@Configuration
public class RailKafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${va.kafka.consumer.group-id:masonxpay-virtual-account}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, RailSettlementEvent> railSettlementConsumerFactory() {
        JsonDeserializer<RailSettlementEvent> deserializer =
                new JsonDeserializer<>(RailSettlementEvent.class, false);
        deserializer.addTrustedPackages("com.masonx.*");

        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,           groupId + "-rail",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,  "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        ), new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, RailSettlementEvent>
    railSettlementListenerContainerFactory(
            ConsumerFactory<String, RailSettlementEvent> railSettlementConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, RailSettlementEvent>();
        factory.setConsumerFactory(railSettlementConsumerFactory);
        return factory;
    }
}
