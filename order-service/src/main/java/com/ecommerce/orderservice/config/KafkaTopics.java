package com.ecommerce.orderservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Saga topic names + explicit topic creation.
 *
 * Both Saga participants declare the same four topics so either service can be
 * the one to bootstrap them (whichever starts first). Auto-create is on at the
 * broker, but declaring them here makes the partition/replication choices
 * explicit and deterministic (single-broker dev: one partition, replicas 1).
 */
@Configuration
public class KafkaTopics {

    public static final String ORDER_CREATED = "order-created";
    public static final String INVENTORY_RESERVED = "inventory-reserved";
    public static final String INVENTORY_FAILED = "inventory-failed";
    public static final String ORDER_CANCELLED = "order-cancelled";

    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(ORDER_CREATED).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic inventoryReservedTopic() {
        return TopicBuilder.name(INVENTORY_RESERVED).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic inventoryFailedTopic() {
        return TopicBuilder.name(INVENTORY_FAILED).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic orderCancelledTopic() {
        return TopicBuilder.name(ORDER_CANCELLED).partitions(1).replicas(1).build();
    }
}
