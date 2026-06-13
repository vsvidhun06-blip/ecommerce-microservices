package com.ecommerce.orderservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dead-letter routing for the order service's Saga consumers
 * (INVENTORY_RESERVED / INVENTORY_FAILED).
 *
 * A {@link DefaultErrorHandler} retries a failing record a bounded number of
 * times (in-process, with a fixed back-off) and, once exhausted, hands it to a
 * {@link DeadLetterPublishingRecoverer} that republishes it to
 * {@code <topic>.DLT}. This keeps a poison record (unparseable payload) or a
 * persistently failing handler from blocking the partition — the consumer
 * advances and the bad record is parked for inspection/replay.
 *
 * Two publishing templates are wired so both failure shapes survive: a poison
 * record carries raw {@code byte[]} (deserialization failed before a POJO
 * existed), while a business-logic failure carries the deserialized event POJO.
 * The recoverer picks the template by value type.
 */
@Configuration
public class KafkaDlqConfig {

    public static final String DLT_SUFFIX = ".DLT";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Producer for republishing poison (deserialization-failed) records: the
     * value is the original raw {@code byte[]}, but the key was deserialized fine
     * (always a String here), so the key keeps a StringSerializer.
     */
    @Bean
    public KafkaTemplate<String, byte[]> rawValueKafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaTemplate<String, Object> kafkaTemplate,
            KafkaTemplate<String, byte[]> rawValueKafkaTemplate) {
        // Order matters: a byte[] value (poison record) maps to the raw template;
        // everything else (event POJOs) falls through to the JSON one.
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, rawValueKafkaTemplate);
        templates.put(Object.class, kafkaTemplate);
        return new DeadLetterPublishingRecoverer(templates);
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        // 2 retries, 1s apart, then publish to <topic>.DLT.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }

    @Bean
    public NewTopic inventoryReservedDlt() {
        return TopicBuilder.name(KafkaTopics.INVENTORY_RESERVED + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic inventoryFailedDlt() {
        return TopicBuilder.name(KafkaTopics.INVENTORY_FAILED + DLT_SUFFIX).partitions(1).replicas(1).build();
    }
}
