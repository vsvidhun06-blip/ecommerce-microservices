package com.ecommerce.orderservice.config;

import com.ecommerce.orderservice.event.InventoryFailedEvent;
import com.ecommerce.orderservice.event.InventoryReservedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Typed Kafka consumer factories for the inventory outcomes the order service
 * reacts to (INVENTORY_RESERVED / INVENTORY_FAILED).
 *
 * Mirrors the inventory service's config: cross-service events are published
 * with JSON type headers disabled, so each listener container factory pins its
 * own target type via a {@link JsonDeserializer} — the publishing class's
 * package is irrelevant. The deserializer is wrapped in an
 * {@link ErrorHandlingDeserializer} so a poison record surfaces as a handled
 * error instead of wedging the consumer.
 *
 * The producer side (KafkaTemplate) is auto-configured from the
 * {@code spring.kafka.producer.*} properties.
 */
@Configuration
public class OrderKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    private <T> ConsumerFactory<String, T> typedConsumerFactory(Class<T> type) {
        JsonDeserializer<T> jsonDeserializer = new JsonDeserializer<>(type);
        jsonDeserializer.addTrustedPackages("*");
        jsonDeserializer.setUseTypeHeaders(false);

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        return new DefaultKafkaConsumerFactory<>(
                props,
                new ErrorHandlingDeserializer<>(new StringDeserializer()),
                new ErrorHandlingDeserializer<>(jsonDeserializer));
    }

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> listenerFactory(Class<T> type) {
        ConcurrentKafkaListenerContainerFactory<String, T> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(typedConsumerFactory(type));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, InventoryReservedEvent> inventoryReservedListenerFactory() {
        return listenerFactory(InventoryReservedEvent.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, InventoryFailedEvent> inventoryFailedListenerFactory() {
        return listenerFactory(InventoryFailedEvent.class);
    }
}
