package com.ecommerce.notificationservice.listener;

import com.ecommerce.notificationservice.event.UserCreatedEvent;
import com.ecommerce.notificationservice.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class UserEventsListener {

    private static final Logger log = LoggerFactory.getLogger(UserEventsListener.class);
    private final NotificationService notificationService;

    public UserEventsListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(
            topics = "${app.kafka.user-created-topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "userCreatedKafkaListenerContainerFactory"
    )
    public void onUserCreated(UserCreatedEvent event) {
        log.info("✅ Received UserCreatedEvent: id={}, username={}, email={}",
                event.getId(), event.getUsername(), event.getEmail());

        notificationService.handleUserCreated(event);
    }
}