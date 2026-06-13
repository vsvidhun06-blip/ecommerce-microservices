package com.ecommerce.inventoryservice.config;

import com.ecommerce.inventoryservice.event.OrderCancelledEvent;
import com.ecommerce.inventoryservice.event.OrderCreatedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Typed Kafka consumer factories for the events the inventory service handles.
 *
 * The cross-service events are published with JSON type headers disabled, so
 * each listener container factory pins its own target type via a
 * {@link JsonDeserializer} — the source class's package is irrelevant. The
 * deserializer is wrapped in an {@link ErrorHandlingDeserializer} so a poison
 * (unparseable) record surfaces as a handled error rather than wedging the
 * consumer; the DLQ phase routes those aside.
 */
@Configuration
public class InventoryKafkaConfig {

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

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> listenerFactory(
            Class<T> type, CommonErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, T> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(typedConsumerFactory(type));
        // Bounded in-process retries, then dead-letter to <topic>.DLT.
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderCreatedEvent> orderCreatedListenerFactory(
            CommonErrorHandler kafkaErrorHandler) {
        return listenerFactory(OrderCreatedEvent.class, kafkaErrorHandler);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderCancelledEvent> orderCancelledListenerFactory(
            CommonErrorHandler kafkaErrorHandler) {
        return listenerFactory(OrderCancelledEvent.class, kafkaErrorHandler);
    }
}
