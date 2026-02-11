package com.ecommerce.notificationservice.service;

import com.ecommerce.notificationservice.event.UserCreatedEvent;
import com.ecommerce.notificationservice.model.Notification;
import com.ecommerce.notificationservice.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public void handleUserCreated(UserCreatedEvent event) {
        log.info("📧 Processing welcome notification for user: {}", event.getUsername());

        String message = String.format(
                "Welcome %s! Your account has been created successfully on %s. We're excited to have you with us!",
                event.getUsername(),
                event.getCreatedAt()
        );

        Notification notification = new Notification();
        notification.setUserId(event.getId());
        notification.setUsername(event.getUsername());
        notification.setEmail(event.getEmail());
        notification.setMessage(message);
        notification.setType("WELCOME");
        notification.setRead(false);

        notificationRepository.save(notification);
        log.info("✅ Welcome notification saved for user ID: {}", event.getId());
    }

    @Transactional
    public void handleOrderCreated(Long userId, String username, String email, String orderDetails) {
        log.info("📧 Processing order notification for user: {}", username);

        String message = String.format("Hello %s! Your order has been confirmed. %s", username, orderDetails);

        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setUsername(username);
        notification.setEmail(email);
        notification.setMessage(message);
        notification.setType("ORDER_CONFIRMATION");
        notification.setRead(false);

        notificationRepository.save(notification);
        log.info("✅ Order notification saved for user ID: {}", userId);
    }

    public List<Notification> getUserNotifications(Long userId) {
        return notificationRepository.findByUserId(userId);
    }

    public List<Notification> getAllNotifications() {
        return notificationRepository.findAll();
    }
}